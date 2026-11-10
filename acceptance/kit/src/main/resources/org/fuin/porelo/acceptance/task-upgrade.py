"""Real-machine upgrade driver. Invoked only by the opt-in package upgrade feature."""

import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import shlex
import socket
import ssl
import subprocess
import sys
import time


ROOT = Path.home() / "target/porelo-upgrade"
HOME = Path.home()
DATA = HOME / ".local/share/porelo"
CONFIG = HOME / ".config/porelo"
STATE = HOME / ".local/state/porelo"
RUNTIME = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")) / "porelo"
OLD = "porelo-porelo-kept"
UNIT = "porelo-acceptance-upgrade-provider"
FAKE = "sk-porelo-acceptance-not-a-real-key"


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def run(*args, input=None, timeout=600, ok=True):
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=timeout)
    if ok:
        # Do not repeat arguments, environment or request bodies in a failure.
        require(result.returncode == 0, f"{args[0]} exited {result.returncode}: "
                + (result.stdout + result.stderr).replace(FAKE, "<fake credential>"))
    return result.stdout if ok else result


def load():
    return json.loads((ROOT / "state.json").read_text())


def save(state):
    (ROOT / "state.json").write_text(json.dumps(state))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pinned(state, name):
    path = Path(state["pins"][name])
    expected = state["pins"][name + "_SHA256"]
    require(path.is_absolute() and path.is_file() and not path.is_symlink(),
            f"{name} must name a pre-staged regular file by absolute path on the VM")
    require(len(expected) == 64 and digest(path) == expected.lower(), f"{name} SHA-256 mismatch")
    return path


def place(source, target, mode=0o755):
    target.parent.mkdir(parents=True, exist_ok=True)
    pending = target.with_name(target.name + ".upgrade-new")
    require(not pending.exists(), f"leftover install file: {pending}")
    shutil.copyfile(source, pending)
    pending.chmod(mode)
    pending.replace(target)


def install(state, name):
    package = pinned(state, name)
    require(run("dpkg-deb", "-f", str(package), "Package").strip() == "porelo",
            f"{name} is not the porelo package")
    version = run("dpkg-deb", "-f", str(package), "Version").strip()
    require(version == state["pins"][name + "_VERSION"], f"{name} package version mismatch")
    state["installAttempt"] = name
    save(state)
    directory = ROOT / name.lower()
    directory.mkdir()
    run("dpkg-deb", "-x", str(package), str(directory))
    # The same payload and atomic binary replacement as acceptance/legs Deploy.accountInstall.
    binaries = [directory / "usr/bin/porelo", directory / "usr/bin/porelod"]
    hooks = list((directory / "usr/libexec/porelo/hooks").glob("*"))
    require(len(hooks) >= 2, f"{name} has no complete hook payload")
    for source in binaries + hooks:
        place(source, HOME / ".local/bin" / source.name)
    for area in ("providers", "egress"):
        sources = list((directory / "usr/share/porelo" / area).glob("*.yaml"))
        require(sources, f"{name} has no {area} payload")
        for source in sources:
            place(source, DATA / area / source.name, 0o644)
    unit = directory / "usr/lib/systemd/user/porelod.service"
    text = unit.read_text().replace("ExecStart=/usr/bin/porelod", "ExecStart=%h/.local/bin/porelod")
    require("ExecStart=%h/.local/bin/porelod\n" in text, "unexpected packaged daemon unit")
    target = HOME / ".config/systemd/user/porelod.service"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    run("systemctl", "--user", "daemon-reload")
    # Full setup would start the account daemon against a different vault. This scenario owns
    # the transient daemon from PoreloSteps and registers only the package's hooks here.
    run("porelo", "setup", "--hooks-only")
    print(f"{name}: version={version} sha256={digest(package)} file={package}")
    print(run("porelo", "--version").strip())
    state["installed"] = name
    save(state)


def call(method, parameters=None, more=False):
    connection = socket.socket(socket.AF_UNIX)
    connection.settimeout(600)
    connection.connect(str(RUNTIME / "porelod.sock"))
    message = {"method": "org.fuin.porelo.Tasks1." + method, "parameters": parameters or {}}
    if more:
        message["more"] = True
    connection.sendall(json.dumps(message).encode() + b"\0")
    return connection


def replies(connection):
    buffered = b""
    while True:
        chunk = connection.recv(65536)
        require(chunk, "daemon stream closed without a final reply")
        buffered += chunk
        while b"\0" in buffered:
            line, buffered = buffered.split(b"\0", 1)
            yield json.loads(line)


def answer(method, parameters=None):
    with call(method, parameters) as connection:
        reply = next(replies(connection))
        require("error" not in reply, f"{method} refused: {reply.get('error')}")
        return reply["parameters"]


def inspect(name):
    return json.loads(run("podman", "inspect", name))[0]


def remember(state, name):
    container = inspect(name)
    require(container["Name"] == name, "container name differs from the start's answer")
    state["tasks"][name] = container["Id"]
    save(state)
    return container


def exec_task(script, name=OLD, input=None):
    return run("podman", "exec", "--user", "agent", "-i", name, "sh", "-ec", script, input=input)


def certifier(state, project):
    """Places the project as its organization does, naming the one key that may certify its tasks."""
    work = ROOT / "person"
    work.mkdir(mode=0o700, exist_ok=True)
    if not (work / "person").exists():
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "person", "-f", str(work / "person"))
    places = STATE / "organization-projects"
    places.mkdir(parents=True, exist_ok=True)
    state.setdefault("placed", []).append(project)
    save(state)
    link = places / (project + ".git")
    if link.is_symlink():
        link.unlink()
    link.symlink_to(HOME / project)
    key = " ".join((work / "person.pub").read_text().split()[:2])
    (places / (project + ".json")).write_text(json.dumps({
        "project": project, "revision": "c0", "address": f"https://forge.example/{project}.git",
        "fetch_from": None, "homeserver": None, "room": None,
        "signing_keys": [{"login": "person", "role": "member", "key": key, "valid_from": "2000-01-01",
                          "valid_until": "2999-01-01", "revoked_since": None}]}))


def certificate(asked):
    """Signs the key a start asks for, as the person's own computer does."""
    work = ROOT / "person"
    public = work / "task.pub"
    public.write_text(asked["key"] + "\n")
    end = asked["valid_before"].replace("-", "").replace(":", "").replace("T", "")[:14]
    begin = time.strftime("%Y%m%d%H%M%S", time.gmtime(time.time() - 60))
    run("ssh-keygen", "-q", "-s", str(work / "person"), "-I", asked["key_id"], "-n", asked["principal"],
        "-V", begin + "Z:" + end + "Z", str(public))
    return (work / "task-cert.pub").read_text().strip()


def start(state, project, task, expected, certify=False):
    require(run("podman", "container", "exists", expected, ok=False).returncode == 1,
            f"refusing to take an existing container: {expected}")
    # Record the attempt before starting: cleanup also handles a partially failed start.
    state["attempts"].append({"project": project, "task": task})
    save(state)
    # Through porelod: a project that allows its tasks an MCP endpoint is started only there. AGENT
    # brings up the agent's own session, as the CLI's start does; without a prompt the default is a shell.
    parameters = {"task": task, "project": project, "repository": project, "agent": "stub",
                  "clearance": "deny", "mode": "AGENT"}
    if certify:
        # Certified as an interface has it, so the task signs its mail: a start whose starter
        # holds no certifier's key leaves the task unsigned, and its mail is held.
        certifier(state, project)
        parameters["certify"] = True
    with call("Start", parameters, more=certify) as connection:
        for reply in replies(connection):
            asked = reply.get("parameters", {}).get("certify")
            if asked:
                answering = {"start": asked["start"], "certificate": certificate(asked)}
                with call("Certify", answering) as certified:
                    answered = next(replies(certified))
                require("error" not in answered, f"Certify refused: {answered.get('error')}")
            if "error" in reply or not reply.get("continues"):
                break
    require("error" in reply or reply.get("parameters", {}).get("exitCode", 0) == 0,
            f"task start ended with exit code {reply.get('parameters', {}).get('exitCode')}")
    # Find only this account's tasks with the two exact logical labels/legacy spellings.
    found = []
    for name in run("podman", "ps", "-a", "--format", "{{.Names}}").splitlines():
        if name in {expected, f"porelo-{project}-{task}",
                    "porelo-" + (task if project == "porelo" else project + "_" + task)}:
            remember(state, name)
            found.append(name)
    require("error" not in reply, f"task start failed: {reply.get('error')} {reply.get('parameters')}")
    require(found == [expected], f"start did not create exactly the expected container: {found}")
    container = remember(state, expected)
    require(container["State"]["Running"], f"{expected} did not stay running")
    return container


def provider():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            self.do_GET()

        def do_GET(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            valid = (self.headers.get("x-api-key") == FAKE
                     or self.headers.get("Authorization") == "Bearer " + FAKE)
            body = b'{"reply":"upgrade-provider-ok"}' if valid else b'{"error":"credential missing"}'
            self.send_response(200 if valid else 401)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(ROOT / "provider.pem", ROOT / "provider-private.pem")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    (ROOT / "provider-port").write_text(str(server.server_port))
    server.serve_forever()


def local_services(state):
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-subj", "/CN=upgrade-provider", "-addext", "subjectAltName=IP:127.0.0.1",
        "-keyout", str(ROOT / "provider-private.pem"), "-out", str(ROOT / "provider.pem"))
    place(ROOT / "provider.pem", CONFIG / "ca-certificates.pem", 0o600)
    run("systemd-run", "--user", "--quiet", "--collect", "--unit=" + UNIT,
        "python3", str(ROOT / "driver.py"), "provider")
    state["providerStarted"] = True
    save(state)
    for _ in range(100):
        if (ROOT / "provider-port").exists():
            break
        time.sleep(0.1)
    require((ROOT / "provider-port").exists(), "local fake provider did not start")
    state["provider"] = "https://127.0.0.1:" + (ROOT / "provider-port").read_text()
    save(state)
    write_provider(state)
    destinations = DATA / "destinations"
    destinations.mkdir(parents=True, exist_ok=True)
    (destinations / "upgrade-route.yaml").write_text(
        f'name: upgrade-route\nupstream: {state["provider"]}\n'
        'auth_header: Authorization\nauth_prefix: "Bearer "\n')
    transports = DATA / "transports"
    transports.mkdir(parents=True, exist_ok=True)
    (transports / "porelo-message-transport-upgrade").write_text(
        '#!/usr/bin/python3\nimport json, sys\n'
        'if sys.argv[1] == "describe":\n'
        ' print(json.dumps({"scheme":"upgrade","poll":False,"confirms":"handover",'
        '"max_bytes":1048576,"credentials":[],"hosts":[],"attests":[],"secrets":"input"}))\n'
        'elif sys.argv[1] == "send":\n sys.stdin.read()\n sys.exit(75)\n'
        'else:\n sys.exit(64)\n')
    (transports / "porelo-message-transport-upgrade").chmod(0o755)
    description = json.loads(run(str(transports / "porelo-message-transport-upgrade"), "describe"))
    require(description["scheme"] == "upgrade", "the queue transport is not installed")


def write_provider(state):
    (DATA / "providers/anthropic.yaml").write_text(
        'name: anthropic\nlabel: Local upgrade fixture\n'
        f'upstream: {state["provider"]}\n'
        'dialects:\n  anthropic-messages: ""\n'
        'auth_header:\n  _default: x-api-key\n'
        'auth_prefix:\n  _default: ""\n'
        'token_env:\n  _default: ANTHROPIC_API_KEY\n')


def prepare():
    pins = json.load(sys.stdin)
    require(os.getuid() != 0 and run("id", "-un").strip() == pins["UPGRADE_USER"],
            "use the explicitly named disposable, unprivileged upgrade account")
    require(not os.environ.get("XDG_CONFIG_HOME") and not os.environ.get("XDG_STATE_HOME")
            and not os.environ.get("XDG_DATA_HOME"), "use the account's standard XDG roots")
    for program in ("dpkg-deb", "podman", "python3", "openssl", "systemd-run", "git"):
        require(shutil.which(program), f"install {program} before this run")
    require(not run("podman", "ps", "-aq").strip(), "the upgrade account already has containers")
    require(not (CONFIG / "projects").exists() or not list((CONFIG / "projects").iterdir()),
            "the upgrade account already has projects")
    for path in (CONFIG / "ca-certificates.pem", DATA / "destinations/upgrade-route.yaml",
                 DATA / "transports/porelo-message-transport-upgrade"):
        require(not path.exists() and not path.is_symlink(), f"upgrade fixture path already exists: {path}")
    require(run("loginctl", "show-user", str(os.getuid()), "-p", "Linger", "--value").strip() == "yes",
            "an administrator must enable lingering before this run")
    run("systemctl", "--user", "show", "-p", "Version")
    unit = run("systemctl", "--user", "show", UNIT, "-p", "LoadState", "--value", ok=False)
    require(unit.stdout.strip() == "not-found",
            "the upgrade provider unit already exists; inspect it before retrying")
    require(run("bash", "-lc", "command -v porelo").strip() == str(HOME / ".local/bin/porelo"),
            "first prepare this account with deploy --account and a login PATH for ~/.local/bin")
    state = {"pins": pins, "tasks": {}, "attempts": [], "prepared": False}
    # Validate all input bytes before installing the first package.
    for name in ("BASELINE", "CANDIDATE", "STUB", "MCP", "FILTER"):
        pinned(state, name)
    require(pins["BASELINE_SHA256"] != pins["CANDIDATE_SHA256"], "baseline and candidate are identical")
    for name in ("BASELINE", "CANDIDATE"):
        require(run("dpkg-deb", "-f", pins[name], "Version").strip() == pins[name + "_VERSION"],
                f"{name} version differs from its pin")
    save(state)
    install(state, "BASELINE")
    place(pinned(state, "STUB"), DATA / "agents/porelo-agent-stub")
    place(pinned(state, "MCP"), HOME / ".local/bin/porelo-mcp")
    place(pinned(state, "FILTER"), DATA / "filter/porelo-message-sluice-filter")
    local_services(state)
    print(run("porelo", "agents", "--verbose"))
    described = json.loads(run(str(DATA / "agents/porelo-agent-stub"), "describe"))
    definition = described.get("definition", described)
    require(definition.get("supportsResume"), "the installed stub does not declare session resume")
    ids = definition.get("sessionIds", {})
    directory, suffix = ids.get("directory"), ids.get("suffix")
    require(isinstance(directory, str) and Path(directory).parts and not Path(directory).is_absolute()
            and ".." not in Path(directory).parts and isinstance(suffix, str) and suffix,
            "the installed stub must declare its session files relative to the agent's home")
    state["sessionDirectory"], state["sessionSuffix"] = directory, suffix
    state["prepared"] = True
    save(state)


def mcp(method, arguments):
    request = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                          "params": {"name": method, "arguments": arguments}})
    answered = exec_task(
        'curl -sS --max-time 180 -w "\\n%{http_code}" -H "Authorization: Bearer $PORELO_TOKEN_PORELO_MCP" '
        '-H "Content-Type: application/json" -H "Accept: application/json" '
        '--data-binary @- "$PORELO_URL_PORELO_MCP"', input=request)
    body, _, status = answered.rpartition("\n")
    if status != "200":
        # Say what answered and what the task's helpers are, never a token.
        helpers = sorted(p.name for p in (RUNTIME / OLD).iterdir()) if (RUNTIME / OLD).is_dir() else []
        logs = ""
        for log in sorted((RUNTIME / OLD).glob("*mcp*.log")) if helpers else []:
            logs += f"\n{log.name}:\n" + "\n".join(log.read_text(errors="replace").splitlines()[-20:])
        raise AssertionError(f"MCP {method} answered HTTP {status}: {body[:2000]}\n"
                             f"helpers: {helpers}{logs}".replace(FAKE, "<fake credential>"))
    result = json.loads(body)
    require("error" not in result, "MCP could not reach its old task endpoint")
    return result["result"]["structuredContent"]


def route_fingerprint():
    # Fingerprint inside the task, without ever returning its token to the test runner.
    return exec_task('printf %s "$PORELO_TOKEN_UPGRADE_ROUTE" | sha256sum').split()[0]


def route_works():
    reply = exec_task('curl -fsS --max-time 20 -H "Authorization: Bearer $PORELO_TOKEN_UPGRADE_ROUTE" '
                      '"$PORELO_URL_UPGRADE_ROUTE/upgrade-witness"')
    require(json.loads(reply).get("reply") == "upgrade-provider-ok", "the old credential route failed")


def queued():
    mailbox = STATE / "mail" / OLD
    # The pass hands the message on in the daemon's own time; a deferring transport keeps it queued.
    for _ in range(1500):
        try:
            files = [p for p in (mailbox / "queue/upgrade").rglob("*.json")
                     if "upgrade-mail-before" in p.read_text()]
            result = {}
            for message in files:
                # A retry renames the message and its signature separately. Retry this reading
                # if it straddles that hop; never edit or freeze the host's queue for the test.
                signature = message.with_name(message.name + ".sig")
                result[message.name] = [digest(message), digest(signature)]
            if result:
                return result
        except FileNotFoundError:
            pass
        time.sleep(0.02)
    tree = "\n".join(str(p.relative_to(mailbox)) for p in sorted(mailbox.rglob("*")))
    log = run("porelo", "talk", "log", OLD, ok=False)
    raise AssertionError("the signed message is not in the old task's transport queue; its mailbox:\n"
                         + tree + "\ntalk log:\n"
                         + (log.stdout + log.stderr).replace(FAKE, "<fake credential>"))


def session_find(state):
    return ('find "$HOME"/' + shlex.quote(state["sessionDirectory"]) + ' -name '
            + shlex.quote("*" + state["sessionSuffix"]) + ' -type f ')


def session_files(state):
    return sorted(exec_task(session_find(state) + '-printf "%f\\n"').splitlines())


def helper_start(record):
    # Compare the kernel start counter too: an unchanged pid file alone could name a reused pid.
    return Path(f"/proc/{int(record.split()[0])}/stat").read_text().rsplit(")", 1)[1].split()[19]


def witnesses(state):
    exec_task("printf upgrade-workspace-before > /workspace/upgrade-sentinel; "
              "tmux send-keys -t porelo upgrade-session-before Enter")
    run("porelo", "talk", "tell", OLD, input="upgrade-inbox-before")
    said = run("porelo", "talk", "say", OLD, "reviewer", input="upgrade-mail-before")
    passed = run("porelo", "talk", "pass", OLD)
    try:
        state["queued"] = queued()
    except AssertionError as missing:
        raise AssertionError(f"{missing}\nsay said: {said}\npass said: {passed}") from None
    state["route"] = route_fingerprint()
    route_works()
    sessions = ""
    for _ in range(50):
        sessions = exec_task(session_find(state) + '-exec cat {} \\;')
        if "upgrade-session-before" in sessions:
            break
        time.sleep(0.1)
    require("upgrade-session-before" in sessions, "the old agent did not keep the session input")
    state["sessions"] = session_files(state)
    state["attempts"].append({"project": "porelo", "task": "child"})
    save(state)
    result = mcp("Start", {"project": "porelo", "repository": "porelo", "task": "child",
                           "agent": "stub", "prompt": "upgrade-child-before", "minutes": 1})
    require(result.get("exitCode") == 0, "the allowed MCP child did not finish successfully")
    state["child"] = result["container"]
    require(state["child"] == "porelo-porelo-child", "the baseline did not create a legacy MCP child")
    remember(state, state["child"])
    state["helpers"] = {p.name: p.read_text() for p in (RUNTIME / OLD).glob("*.pid")}
    require("gate.pid" in state["helpers"], "baseline gate helper missing")
    state["helperStarts"] = {name: helper_start(record) for name, record in state["helpers"].items()}
    save(state)


def retained(state):
    require(exec_task("cat /workspace/upgrade-sentinel").strip() == "upgrade-workspace-before",
            "workspace sentinel was lost")
    require(route_fingerprint() == state["route"], "resume changed the old route token")
    route_works()
    require(queued() == state["queued"], "queued signed mail changed bytes or path")
    require("upgrade-inbox-before" in exec_task("cat /run/porelo/mail/inbox/new/*"), "old mail was lost")
    run("porelo", "talk", "verify", OLD)
    run("porelo", "talk", "tell", OLD, input="upgrade-inbox-after")
    require("upgrade-inbox-after" in exec_task("cat /run/porelo/mail/inbox/new/*"), "live mail failed")
    run("podman", "exec", "--user", "agent", OLD, "sh", "-ec",
        "cd /workspace && timeout 20 git ls-remote porelo >/dev/null")


def running(state):
    container = inspect(OLD)
    require(container["Id"] == state["tasks"][OLD] and container["Name"] == OLD,
            "upgrade replaced the old container")
    require(container["State"]["Running"] and container["State"]["StartedAt"] == state["started"],
            "the old container was stopped or restarted during upgrade")
    for name, record in state["helpers"].items():
        require((RUNTIME / OLD / name).read_text() == record, f"upgrade replaced old helper {name}")
        require(helper_start(record) == state["helperStarts"][name], f"old helper {name} no longer runs")
    retained(state)


def watches(state):
    with call("Watch", more=True) as connection:
        reply = next(replies(connection))
        tasks = reply.get("parameters", {}).get("tasks", [])
        require(any(t["name"] == OLD and t["run"] == state["tasks"][OLD] for t in tasks),
                "Watch lost the old task")
    # A fresh blocked packet must arrive through the daemon's restored subscription to the
    # old clearance watcher, rather than merely appearing in a retained log.
    state["watchRound"] = state.get("watchRound", 0) + 1
    address = "192.0.2." + str(50 + state["watchRound"])
    save(state)
    with call("Prompts", more=True) as connection:
        connection.settimeout(20)
        time.sleep(1)
        blocked = subprocess.Popen(
            ["podman", "exec", "--user", "agent", OLD, "bash", "-c",
             f"timeout 3 bash -c 'echo > /dev/tcp/{address}/443'"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for reply in replies(connection):
                event = reply.get("parameters", {})
                if event.get("task") == OLD and event.get("destination") == address:
                    break
        finally:
            try:
                blocked.wait(timeout=10)
            except subprocess.TimeoutExpired:
                blocked.kill()
                blocked.wait()
                raise AssertionError("the blocked-packet probe did not end")
        require(blocked.returncode in (1, 124), "the packet probe was not denied or could not run")
    refused = mcp("Stop", {"task": OLD})
    require(refused.get("error", "").endswith(".NotItsOwn"), "the parent endpoint could stop its owner")
    child = state["child"]
    # A finished run leaves its container up; its parent stops it, which only its owner may, and
    # brings it back by its logical name under the name it was made with.
    stopped = mcp("Stop", {"task": child})
    require("error" not in stopped, f"the old parent could not stop its own old MCP child: {stopped}")
    result = mcp("Start", {"project": "porelo", "repository": "porelo", "task": "child",
                           "agent": "stub", "prompt": "upgrade-child-after", "minutes": 1})
    require(result.get("exitCode") == 0 and result.get("container") == child,
            f"the old parent could not resume its own old MCP child {child}: "
            + json.dumps(result)[:2000].replace(FAKE, "<fake credential>"))
    require(inspect(child)["Id"] == state["tasks"][child], "MCP resume replaced the old child")


def installation(state):
    process = answer("Process")
    require(process["executable"] == str(HOME / ".local/bin/porelod"), "daemon runs another install")
    require(shutil.which("porelo") == str(HOME / ".local/bin/porelo"), "CLI runs another install")
    descriptors = list((HOME / ".config/containers/oci/hooks.d").glob("porelo-hook-*.json"))
    require(len(descriptors) >= 2, "the account's hook descriptors are missing")
    for descriptor in descriptors:
        require(json.loads(descriptor.read_text())["hook"]["path"].startswith(str(HOME / ".local/bin") + "/"),
                "a hook runs another install")
    source = ROOT / state["installed"].lower()
    for original in [source / "usr/bin/porelo", source / "usr/bin/porelod"] + list(
            (source / "usr/libexec/porelo/hooks").glob("*")):
        require(digest(original) == digest(HOME / ".local/bin" / original.name),
                "CLI, daemon and hooks are not from one pinned package")


def remove_tasks(state):
    # A start may fail after podman create but before answering. This account was empty at entry;
    # take only exact attempted names with their exact project labels. Porelo checks recorded ids.
    names = run("podman", "ps", "-a", "--format", "{{.Names}}").splitlines()
    for attempt in state["attempts"]:
        project, task = attempt["project"], attempt["task"]
        possible = {f"porelo-{project}-{task}",
                    "porelo-" + (task if project == "porelo" else project + "_" + task)}
        for name in possible.intersection(names):
            if name not in state["tasks"]:
                container = inspect(name)
                require(container["Config"]["Labels"].get("org.fuin.porelo.project") == project,
                        f"cleanup refuses a different owner: {name}")
                remember(state, name)
    for name, identity in list(state["tasks"].items())[::-1]:
        exists = run("podman", "container", "exists", identity, ok=False)
        if exists.returncode == 1:
            continue
        require(exists.returncode == 0 and inspect(identity)["Name"] == name,
                f"refusing cleanup of a changed container: {name}")
        run("porelo", "task", "remove", name, "--force")
        require(run("podman", "container", "exists", identity, ok=False).returncode == 1,
                f"cleanup left container {name}")
    require(not run("podman", "ps", "-aq").strip(), "upgrade account has unrecorded containers; inspect it")
    # A removed task whose mail was recorded keeps its mailbox's record, and a retired name with a
    # mailbox stays taken: the next run could not start it again. Only this scenario's own names,
    # and only once their containers are gone.
    for name in state["tasks"]:
        shutil.rmtree(STATE / "mail" / name, ignore_errors=True)
        (STATE / "task-identities" / (name + ".json")).unlink(missing_ok=True)


def cleanup(state):
    if not state:
        shutil.rmtree(ROOT)
        return
    remove_tasks(state)
    active = run("systemctl", "--user", "is-active", UNIT, ok=False)
    if state.get("providerStarted") and active.returncode == 0:
        run("systemctl", "--user", "stop", UNIT)
    if state.get("installed") == "BASELINE" or state.get("installAttempt") == "BASELINE":
        install(state, "CANDIDATE")
    for path in (CONFIG / "ca-certificates.pem", DATA / "destinations/upgrade-route.yaml",
                 DATA / "transports/porelo-message-transport-upgrade"):
        path.unlink(missing_ok=True)
    for project in state.get("placed", []):
        for name in (project + ".json", project + ".git"):
            (STATE / "organization-projects" / name).unlink(missing_ok=True)
    # The account remains on the candidate; do not leave a provider pointing at the stopped fixture.
    source = ROOT / "candidate/usr/share/porelo/providers/anthropic.yaml"
    if source.exists():
        place(source, DATA / "providers/anthropic.yaml", 0o644)
    shutil.rmtree(ROOT)


def main():
    os.umask(0o077)
    os.environ["GIT_CONFIG_GLOBAL"] = "/dev/null"
    action = sys.argv[1]
    if action == "prepare":
        prepare()
        return
    if action == "provider":
        provider()
        return
    state = load() if (ROOT / "state.json").exists() else {}
    if action == "start-old":
        installation(state)
        container = start(state, "porelo", "kept", OLD, certify=True)
        state["started"] = container["State"]["StartedAt"]
        save(state)
    elif action == "witnesses":
        witnesses(state)
    elif action == "install-candidate":
        install(state, "CANDIDATE")
        write_provider(state)
    elif action == "installation":
        installation(state)
    elif action == "running":
        running(state)
    elif action == "watches":
        watches(state)
    elif action == "resume":
        run("porelo", "task", "stop", OLD)
        # By logical name, through porelod as its first start was.
        answer("Start", {"task": "kept", "project": "porelo", "repository": "porelo", "agent": "stub",
                         "clearance": "deny", "mode": "AGENT"})
        require(inspect(OLD)["Id"] == state["tasks"][OLD], "logical resume created a new container")
    elif action == "retained":
        retained(state)
        require(session_files(state) == state["sessions"], "resume created a fresh agent session")
        # Whether the resumed agent remembers its first input is shown by the attach that follows:
        # a start opens no session, only an attach does.
    elif action == "new-names":
        start(state, "porelo", "fresh", "porelo-fresh")
        start(state, "upgrade", "fresh", "porelo-upgrade_fresh")
    elif action == "remove-tasks":
        remove_tasks(state)
    elif action == "cleanup":
        cleanup(state)
    else:
        raise AssertionError("unknown upgrade action")
    print(action + ": OK")


if __name__ == "__main__":
    main()
