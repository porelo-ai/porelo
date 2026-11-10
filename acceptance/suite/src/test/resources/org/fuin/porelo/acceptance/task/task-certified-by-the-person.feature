@slow
Feature: A start through the daemon has the person's own computer certify its task

    A host holds no person's agent, and starts no task uncertified. So an interface asks the daemon's
    Start with certify, signs what its stream asks at the person's computer, and answers by Certify on
    another connection. Whether the machine is a host changes what happens without a certificate, not
    this way of getting one, so a machine that is no host shows it.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "certifying" of class "guarded" with a file in it
        And a daemon of this scenario's own

    Scenario: a certificate from the person's computer starts the task, and a refusal starts nothing
        # The project as an organization places it, naming the one key that may certify its tasks.
        When a script runs:
            """
            work=~/certifying/.git/person; mkdir -p "$work"
            ssh-keygen -q -t ed25519 -N '' -C person -f "$work/person"
            places="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/organization-projects"
            mkdir -p "$places"
            ln -sfnT ~/certifying "$places/certifying.git"
            python3 - "$work/person.pub" "$places/certifying.json" <<'PY'
            import json, sys
            key = " ".join(open(sys.argv[1]).read().split()[:2])
            json.dump({"project": "certifying", "revision": "c0", "address": "https://forge.example/certifying.git",
                       "fetch_from": None, "homeserver": None, "room": None,
                       "signing_keys": [{"login": "person", "role": "member", "key": key, "valid_from": "2000-01-01",
                                         "valid_until": "2999-01-01", "revoked_since": None}]},
                      open(sys.argv[2], "w"))
            PY
            cat > "$work/start.py" <<'PY'
            import datetime, json, os, subprocess, sys
            task, answer, work = sys.argv[1], sys.argv[2], sys.argv[3]
            def call(method, parameters, more=False):
                message = {"method": "org.fuin.porelo.Tasks1." + method, "parameters": parameters}
                if more:
                    message["more"] = True
                connection = subprocess.Popen(["porelo", "daemon", "connect"], stdin=subprocess.PIPE,
                                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                connection.stdin.write(json.dumps(message).encode() + b"\0")
                connection.stdin.flush()
                return connection
            def replies(connection):
                buffer = b""
                while True:
                    byte = connection.stdout.read(1)
                    if not byte:
                        return
                    if byte == b"\0":
                        yield json.loads(buffer)
                        buffer = b""
                    else:
                        buffer += byte
            def sign(asked):
                public = os.path.join(work, task + ".pub")
                open(public, "w").write(asked["key"] + "\n")
                end = datetime.datetime.fromisoformat(asked["valid_before"].replace("Z", "+00:00"))
                now = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=60)
                subprocess.run(["ssh-keygen", "-q", "-s", os.path.join(work, "person"), "-I", asked["key_id"],
                                "-n", asked["principal"],
                                "-V", now.strftime("%Y%m%d%H%M%SZ") + ":" + end.strftime("%Y%m%d%H%M%SZ"), public],
                               check=True)
                return open(os.path.join(work, task + "-cert.pub")).read().strip()
            start = call("Start", {"task": task, "project": "certifying", "agent": "stub", "repository": "certifying",
                                   "clearance": "deny", "keep": True, "certify": True}, more=True)
            asked = 0
            for reply in replies(start):
                parameters = reply.get("parameters", {})
                if "error" in reply:
                    print("error:", json.dumps(reply))
                if "certify" in parameters:
                    asked += 1
                    wanted = parameters["certify"]
                    print("asked for:", wanted["principal"])
                    print("certifiers:", len(wanted["certifiers"]))
                    answering = {"start": wanted["start"]}
                    if answer == "sign":
                        answering["certificate"] = sign(wanted)
                    else:
                        answering["refused"] = answer
                    answered = next(replies(call("Certify", answering)))
                    print("certify answered:", "error" not in answered)
                    spent = next(replies(call("Certify", answering)))
                    print("answered again:", spent.get("error", "accepted"))
                for line in [parameters.get("line")] if parameters.get("line") else []:
                    print("line:", line)
                if not reply.get("continues"):
                    print("exit code:", parameters.get("exitCode"))
                    print("action:", parameters.get("action"))
                    break
            start.terminate()
            print("asked:", asked)
            PY
            """
        Then it exits zero
        When a script runs:
            """
            timeout 600 python3 ~/certifying/.git/person/start.py refused 'no key of yours here' ~/certifying/.git/person
            echo "containers: $(podman ps -a --format '{{.Names}}' | grep -c porelo-certifying_refused)"
            """
        Then its output contains "asked for: task:certifying:refused"
        And its output contains "certifiers: 1"
        And its output contains "certify answered: True"
        And its output contains "NoSuchStart"
        And its output contains "did not certify task:certifying:refused: no key of yours here"
        And its output contains "exit code: 65"
        And its output contains "containers: 0"
        When a script runs:
            """
            timeout 900 python3 ~/certifying/.git/person/start.py signed sign ~/certifying/.git/person
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.List","parameters":{}}' \
                | timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1 | python3 -c '
            import json, sys
            for row in json.loads(sys.stdin.readline())["parameters"]["tasks"]:
                if row.get("name", "").endswith("certifying_signed"):
                    print("certified:", row.get("certified"))
                    print("certified until set:", bool(row.get("certifiedUntil")))'
            """
        Then its output contains "asked for: task:certifying:signed"
        And its output contains "certify answered: True"
        And its output contains "exit code: 0"
        And its output contains "certified: True"
        And its output contains "certified until set: True"
        When a script runs:
            """
            porelo task remove porelo-certifying_signed --force >/dev/null 2>&1
            places="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/organization-projects"
            rm -f "$places/certifying.json" "$places/certifying.git"
            true
            """

    Scenario: a start in default through the daemon, as a window asks it, needs no certificate and asks for none
        When a script runs:
            """
            base=$(mktemp -d)
            repo="dflt$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            git init -q --bare --initial-branch=main "$base/$repo.git"
            git init -q --initial-branch=main "$base/seed"
            git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
            git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
            porelo project default add "$base/$repo.git" --name "$repo" > /dev/null
            echo "added exit $?"
            cat > "$base/start.py" <<'PY'
            import json, subprocess, sys
            connection = subprocess.Popen(["porelo", "daemon", "connect"], stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            connection.stdin.write(json.dumps({"method": "org.fuin.porelo.Tasks1.Start", "more": True, "parameters": {
                "task": sys.argv[1], "project": "default", "agent": "stub", "repository": sys.argv[1],
                "clearance": "deny", "keep": True, "certify": True}}).encode() + b"\0")
            connection.stdin.flush()
            asked = 0
            buffer = b""
            while True:
                byte = connection.stdout.read(1)
                if not byte:
                    print("closed")
                    break
                if byte != b"\0":
                    buffer += byte
                    continue
                reply = json.loads(buffer)
                buffer = b""
                parameters = reply.get("parameters", {})
                if "error" in reply:
                    print("error:", json.dumps(reply))
                if "certify" in parameters:
                    asked += 1
                    print("asked for:", parameters["certify"]["principal"])
                    print("certifiers:", len(parameters["certify"]["certifiers"]))
                    break
                if parameters.get("line"):
                    print("line:", parameters["line"])
                if not reply.get("continues"):
                    print("exit code:", parameters.get("exitCode"))
                    break
            connection.terminate()
            print("asked:", asked)
            PY
            timeout 600 python3 "$base/start.py" "$repo"
            porelo task list | grep -cE "^$repo +default " | sed 's/^/a task in default: /'
            porelo task remove "porelo-default_$repo" --force > /dev/null 2>&1
            porelo project default remove "$repo" > /dev/null 2>&1
            rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/mirrors/default/$repo.git"
            rm -rf "$base"
            true
            """
        Then its output contains "added exit 0"
        And its output contains "asked: 0"
        And its output contains "exit code: 0"
        And its output contains "a task in default: 1"
