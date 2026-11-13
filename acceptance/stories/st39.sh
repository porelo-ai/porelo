#!/usr/bin/env bash
# ST39, a failed delivery named and kept visible, played at the command line as a person would (B681).
#
# What it does, against the porelo installed in this account:
#   1. stands up a forge of its own on this machine's loopback: a bare repository served over plain HTTP
#      by git http-backend, and an online project whose central repository it is;
#   2. starts a task and pushes from it once while the forge answers, so a delivery that works is seen;
#   3. has the forge refuse a push (a pre-receive hook), and the gate refuse a push to another branch
#      and a tag, and reads each answer's words and how long it took;
#   4. makes the forge stop answering - its server stopped and a listener in its place that takes the
#      connection and never answers - pushes again, and reads whether the push ends within the bound
#      (limits.pass_on, two minutes unless set) and says the forge did not answer (B197);
#   5. reads the failure afterwards in 'task logs', 'task status' and 'task list', and that the task's
#      status names its repository;
#   6. brings the forge back, pushes as the person to the project's repository, and reads whether the
#      task's next fetch picks it up, and whether a push from the task goes through again;
#   7. holds a message, refuses it for good and releases it, reading the release's words and the code the
#      daemon answers beside them (B179);
#   8. removes everything it made and lists what held and what did not.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l st39.sh --test-account [--expect-fail ISSUES] [--pass-on DURATION]
# --expect-fail names the issues not yet in Review, comma-separated ('none' for none); default B179,B197.
# --pass-on writes limits.pass_on into the project file, such as 30s, and takes it as the bound; without
# it the file sets none and the bound is the default, two minutes. Only once B197 is built does the
# project file take the key. Exit 0 when nothing failed unexpectedly, 1 otherwise, 64 for wrong use.

# The scripts sent into the task are single-quoted on purpose: they expand there, not here.
# shellcheck disable=SC2016
set -u -o pipefail

EXPECTED="B179,B197"
TEST_ACCOUNT=no
PASS_ON=""
usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}
while [ $# -gt 0 ]; do
    case "$1" in
    --expect-fail)
        [ $# -ge 2 ] || {
            usage >&2
            exit 64
        }
        EXPECTED="$2"
        [ "$EXPECTED" = none ] && EXPECTED=""
        shift 2
        ;;
    --pass-on)
        [ $# -ge 2 ] || {
            usage >&2
            exit 64
        }
        PASS_ON="$2"
        shift 2
        ;;
    --test-account)
        TEST_ACCOUNT=yes
        shift
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "st39: unknown argument: $1" >&2
        usage >&2
        exit 64
        ;;
    esac
done

# ---------------------------------------------------------------- the frame every story script shares

HELD=()
FAILED=()
WAITING=()
STEP=0
step() {
    STEP=$((STEP + 1))
    printf '\n== %d. %s\n' "$STEP" "$*"
}
read_() { printf '   read: %s\n' "$*"; }
shown() { sed 's/^/   | /'; }
expected() {
    case ",$EXPECTED," in *",$1,"*) return 0 ;; esac
    return 1
}
# point <0 when it held> <issue it waits on, or ''> <what the story says>
point() {
    local ok=$1 issue=$2 text=$3
    if [ "$ok" = 0 ]; then
        HELD+=("$text")
        printf '   HELD: %s\n' "$text"
        if [ -n "$issue" ] && expected "$issue"; then
            printf '   note: expected to wait on %s, and held anyway\n' "$issue"
        fi
    elif [ -n "$issue" ] && expected "$issue"; then
        WAITING+=("$text (waits on $issue)")
        printf '   DID NOT HOLD, as expected: waits on %s\n' "$issue"
    else
        FAILED+=("$text${issue:+ ($issue)}")
        printf '   DID NOT HOLD: %s\n' "$text"
    fi
}
yes_if() { if "$@"; then echo 0; else echo 1; fi; }

BOUND=120
if [ -n "$PASS_ON" ]; then
    [[ "$PASS_ON" =~ ^([0-9]+)([smh])$ ]] || {
        echo "st39: --pass-on takes a time with its unit, such as 30s, 2m or 1h" >&2
        exit 64
    }
    case "${BASH_REMATCH[2]}" in
    s) BOUND=$((10#${BASH_REMATCH[1]})) ;;
    m) BOUND=$((10#${BASH_REMATCH[1]} * 60)) ;;
    h) BOUND=$((10#${BASH_REMATCH[1]} * 3600)) ;;
    esac
fi

if [ "$(id -u)" -eq 0 ]; then
    echo "st39: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "st39: refused: this makes and removes a task, a project, a vault and a forge of its own in the" >&2
    echo "      account it runs in; run it only in a test account on the test machine, and say so with" >&2
    echo "      --test-account" >&2
    exit 64
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git timeout python3 awk; do
    command -v "$tool" >/dev/null || {
        echo "st39: refused: '$tool' is not installed" >&2
        exit 64
    }
done
[ -x "$(git --exec-path)/git-http-backend" ] || {
    echo "st39: refused: git has no http-backend here" >&2
    exit 64
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/st39.XXXXXX") || exit 70
chmod 700 "$WORK"
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
PROJECT="st39-$RUN_ID"
TASK=deliver
PROJECTS_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects"
FORGE_ROOT="$WORK/forge"
FORGE="$FORGE_ROOT/st39.git"
CONTAINERS=()
MADE_PROJECTS=()
VAULT_MADE=no
SERVER_PID=""
CLEANED=no

stop_server() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
        SERVER_PID=""
    fi
}

cleanup() {
    [ "$CLEANED" = yes ] && return 0
    CLEANED=yes
    printf '\n== cleaning up what this run made\n'
    local container project
    for container in "${CONTAINERS[@]}"; do
        if podman container exists "$container" 2>/dev/null; then
            timeout 300 porelo task remove "$container" --force --no-input >/dev/null 2>&1
            podman container exists "$container" 2>/dev/null &&
                podman rm -f "$container" >/dev/null 2>&1
            read_ "task $container removed"
        fi
    done
    for project in "${MADE_PROJECTS[@]}"; do
        timeout 300 porelo project remove "$project" --force --no-input >/dev/null 2>&1
        rm -f -- "$PROJECTS_DIR/$project"
        rm -rf -- "${HOME:?}/$project"
        podman images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null |
            grep -E "/porelo/$project(_[^:]*)?:" |
            while read -r image; do podman rmi -f "$image" >/dev/null 2>&1; done
        read_ "project $project removed"
    done
    if [ -n "$SERVER_PID" ]; then
        stop_server
        read_ "the forge stand-in stopped"
    fi
    if [ "$VAULT_MADE" = yes ]; then
        porelo vault lock >/dev/null 2>&1
        read_ "the run's vault locked and removed"
    fi
    rm -rf -- "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

finish() {
    cleanup
    printf '\n== what held (%d)\n' "${#HELD[@]}"
    local each
    for each in "${HELD[@]}"; do printf '   + %s\n' "$each"; done
    printf '== what did not hold, as expected (%d)\n' "${#WAITING[@]}"
    for each in "${WAITING[@]}"; do printf '   ~ %s\n' "$each"; done
    printf '== what did not hold (%d)\n' "${#FAILED[@]}"
    for each in "${FAILED[@]}"; do printf '   - %s\n' "$each"; done
    [ "${#FAILED[@]}" -eq 0 ]
    exit $?
}

make_project() {
    local name=$1 class=$2 more=$3 dir="$HOME/$1"
    if [ -e "$dir" ] || [ -e "$PROJECTS_DIR/$name" ] || [ -L "$PROJECTS_DIR/$name" ]; then
        echo "   refused: $dir or the project $name is already there"
        return 1
    fi
    MADE_PROJECTS+=("$name")
    mkdir -p "$dir" "$PROJECTS_DIR" || return 1
    git -C "$dir" init -q -b main || return 1
    printf 'the project\n' >"$dir/README.md"
    {
        printf 'project:\n  name: "%s"\n  security_class: "%s"\n' "$name" "$class"
        printf '%s\n' "$more"
    } >"$dir/project.yml"
    git -C "$dir" add -A &&
        git -C "$dir" -c user.name=Story -c user.email=story@example.invalid commit -q -m initial &&
        ln -sfnT -- "$dir" "$PROJECTS_DIR/$name"
}

container_of() { sed -n 's/^container[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' "$1" | head -1; }
# in_task <seconds> <script>: runs it in the task, bounded, as the acceptance suite does; in_agent as the
# agent's own user, which owns its mailbox.
in_task() { timeout "$1" podman exec "$CONTAINER" sh -c "$2" </dev/null; }
in_agent() { timeout "$1" podman exec --user agent "$CONTAINER" sh -c "$2" </dev/null; }
now() { date +%s; }

# ---------------------------------------------------------------- the forge

# The forge: git's own http-backend behind a small server on loopback, as a forge serves smart HTTP.
cat >"$WORK/forge.py" <<'PY'
import http.server, os, subprocess, sys
root, portfile, log, port = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])

def chunked(stream):
    body = b""
    while True:
        size = int(stream.readline().split(b";")[0].strip() or b"0", 16)
        if size == 0:
            stream.readline()
            return body
        body += stream.read(size)
        stream.readline()

class Handler(http.server.BaseHTTPRequestHandler):
    def serve(self):
        path, _, query = self.path.partition("?")
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = chunked(self.rfile)
        else:
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "GIT_PROJECT_ROOT": root,
               "GIT_HTTP_EXPORT_ALL": "1", "REQUEST_METHOD": self.command, "PATH_INFO": path,
               "QUERY_STRING": query, "CONTENT_TYPE": self.headers.get("Content-Type", ""),
               "CONTENT_LENGTH": str(len(body)), "REMOTE_ADDR": "127.0.0.1", "REMOTE_USER": "story"}
        for header, name in (("Content-Encoding", "HTTP_CONTENT_ENCODING"), ("Git-Protocol", "GIT_PROTOCOL")):
            if self.headers.get(header):
                env[name] = self.headers.get(header)
        out = subprocess.run(["git", "http-backend"], input=body, env=env, capture_output=True).stdout
        head, sep, rest = out.partition(b"\r\n\r\n")
        if not sep:
            head, sep, rest = out.partition(b"\n\n")
        status, headers = 200, []
        for line in head.decode("latin-1").splitlines():
            key, _, value = line.partition(":")
            if key.lower() == "status":
                status = int(value.split()[0])
            elif key:
                headers.append((key, value.strip()))
        self.send_response(status)
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(rest)))
        self.end_headers()
        self.wfile.write(rest)
        with open(log, "a") as f:
            f.write("%s %s %s %d\n" % (self.command, path, query, status))

    do_GET = do_POST = serve

    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
with open(portfile, "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
PY

# The forge gone silent: the port still takes a connection, and nothing is ever answered on it.
cat >"$WORK/silent.py" <<'PY'
import socket, sys
port, portfile, log = int(sys.argv[1]), sys.argv[2], sys.argv[3]
listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", port))
listener.listen(16)
with open(portfile, "w") as f:
    f.write(str(port))
held = []
while True:
    connection, _ = listener.accept()
    held.append(connection)
    with open(log, "a") as f:
        f.write("connection taken, never answered\n")
PY

# serve <forge|silent> <port, 0 for any>: starts the one or the other on the port, answering 0 once it listens.
serve() {
    local kind=$1 port=$2
    stop_server
    rm -f "$WORK/port"
    if [ "$kind" = forge ]; then
        python3 -I "$WORK/forge.py" "$FORGE_ROOT" "$WORK/port" "$WORK/forge.log" "$port" \
            >>"$WORK/server.out" 2>&1 &
    else
        python3 -I "$WORK/silent.py" "$port" "$WORK/port" "$WORK/silent.log" >>"$WORK/server.out" 2>&1 &
    fi
    SERVER_PID=$!
    for _ in $(seq 50); do
        [ -s "$WORK/port" ] && return 0
        sleep 0.2
    done
    return 1
}

step "Make a forge of this run's own: a bare repository served over plain HTTP on loopback"
mkdir -p "$FORGE_ROOT"
git init -q --bare -b main "$FORGE" && git -C "$FORGE" config http.receivepack true
serve forge 0
PORT=$(cat "$WORK/port" 2>/dev/null)
CENTRAL="http://127.0.0.1:${PORT:-0}/st39.git"
read_ "the forge answers at $CENTRAL"
git ls-remote "$CENTRAL" >"$WORK/ls-remote.out" 2>&1
code=$?
shown <"$WORK/ls-remote.out" | tail -n 5
point "$code" "" "the forge stand-in answers git over HTTP"
[ "$code" = 0 ] || {
    shown <"$WORK/server.out"
    finish
}

step "Make a vault of this run's own, with a made-up key for the stub agent's provider"
export PORELO_VAULT="$WORK/vault/vault.bin"
mkdir -m 700 "$WORK/vault"
passphrase=$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')
VAULT_MADE=yes
{
    printf '%s\n' "$passphrase" | porelo vault init --passphrase-stdin
    printf '%s\n' "$passphrase" | porelo vault unlock --passphrase-stdin
    printf '%s' 'sk-porelo-story-not-a-real-key' | porelo vault put anthropic --type api-key
} 2>&1 | shown
unset passphrase
point "$(
    porelo vault list >/dev/null 2>&1
    echo $?
)" "" "the run's vault opens"

step "Make an online project '$PROJECT' whose central repository is the forge, and fill the forge from it"
LIMITS=""
[ -n "$PASS_ON" ] && LIMITS="limits:
  pass_on: \"$PASS_ON\""
if make_project "$PROJECT" online "  central: \"$CENTRAL\"
image:
  from: \"ubuntu:24.04\"
$LIMITS"; then
    git -C "$HOME/$PROJECT" push -q "$CENTRAL" main >"$WORK/seed.out" 2>&1
    code=$?
    shown <"$WORK/seed.out"
    shown <"$HOME/$PROJECT/project.yml"
    read_ "the bound a delivery is held to: $BOUND s ($([ -n "$PASS_ON" ] && echo "limits.pass_on $PASS_ON" || echo 'the default, no limits.pass_on set'))"
    point "$code" "" "the project is there and the forge holds its main"
else
    point 1 "" "the project is there and the forge holds its main"
    finish
fi

# ---------------------------------------------------------------- deliveries

step "Start task '$TASK' in the project"
CONTAINERS+=("porelo-${PROJECT}_$TASK")
timeout 900 porelo task start "$TASK" --project "$PROJECT" --repository "$PROJECT" --agent stub --detach \
    --clearance deny >"$WORK/start.out" 2>&1
code=$?
shown <"$WORK/start.out" | tail -n 30
read_ "exit $code"
CONTAINER=$(container_of "$WORK/start.out")
[ -n "$CONTAINER" ] || CONTAINER="porelo-${PROJECT}_$TASK"
point "$code" "" "an online task starts against the forge"
[ "$code" = 0 ] || finish
for _ in $(seq 120); do
    podman exec "$CONTAINER" git -C /workspace rev-parse HEAD >/dev/null 2>&1 && break
    sleep 1
done
BRANCH=$(porelo task status "$CONTAINER" 2>/dev/null | awk '$1 == "forge" { print $2; exit }')
read_ "the task's branch at the forge: ${BRANCH:-none named}"

# deliver <name> <seconds> <refspec, expanded in the task>: commits in the task and pushes; sets
# DELIVERED (git's exit), TOOK (seconds) and WORDS (what git said, in a file).
deliver() {
    local name=$1 seconds=$2 refspec=$3 started
    WORDS="$WORK/push-$name.out"
    started=$(now)
    in_task "$((seconds + 30))" 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent \
        commit -q --allow-empty -m "st39: '"$name"'" && timeout '"$seconds"' git push porelo \
        "'"$refspec"'"' >"$WORDS" 2>&1
    DELIVERED=$?
    TOOK=$(($(now) - started))
    shown <"$WORDS" | tail -n 8
    read_ "push '$name': exit $DELIVERED after $TOOK s"
}
REASONS="$WORK/reasons"
: >"$REASONS"
# reason <words file>: the line that says why, as a person reads it - the forge's or the gate's own words.
reason() {
    grep -E -i 'refused|rejected|denied|declined|did not answer|error:' "$1" | grep -v -E '^To |hint:' | head -n 1 |
        sed -E 's/^remote: *//; s/^ *! \[[^]]*\] [^(]*\((.*)\)$/\1/'
}

step "Push from the task while the forge answers"
deliver answered 120 'HEAD:$PORELO_TASK_REF'
at_forge=$(git --git-dir="$FORGE" rev-parse --verify --quiet "refs/heads/${BRANCH:-none}" 2>/dev/null)
in_task_head=$(podman exec "$CONTAINER" git -C /workspace rev-parse HEAD 2>/dev/null)
read_ "the forge's ${BRANCH:-branch}: ${at_forge:-absent}; the task's HEAD: ${in_task_head:-unknown}"
point "$(yes_if test "$DELIVERED" = 0 -a -n "$at_forge" -a "$at_forge" = "$in_task_head")" "" \
    "a push from the task arrives at the forge at once"

step "Have the forge refuse the next push, as a forge's own rule does, and push"
cat >"$FORGE/hooks/pre-receive" <<'HOOK'
#!/bin/sh
echo "st39: this forge takes nothing today" >&2
exit 1
HOOK
chmod +x "$FORGE/hooks/pre-receive"
deliver forge-refused 120 'HEAD:$PORELO_TASK_REF'
rm -f "$FORGE/hooks/pre-receive"
reason "$WORDS" >>"$REASONS"
point "$(yes_if test "$DELIVERED" != 0 -a "$TOOK" -le "$BOUND")" "" \
    "a push the forge refuses ends within the bound ($BOUND s) and fails"
point "$(yes_if grep -q 'this forge takes nothing today' "$WORDS")" "" \
    "a push the forge refuses reaches the agent with the forge's own words"

step "Push to another branch than the task's own, which the gate refuses"
deliver other-branch 60 'HEAD:refs/heads/main'
reason "$WORDS" >>"$REASONS"
point "$(yes_if test "$DELIVERED" != 0 -a "$TOOK" -le "$BOUND")" "" \
    "a push to another branch is refused within the bound"

step "Push a tag, which the gate refuses"
in_task 60 'cd /workspace && git tag -f st39-tag' >/dev/null 2>&1
deliver tag 60 'refs/tags/st39-tag'
reason "$WORDS" >>"$REASONS"
point "$(yes_if test "$DELIVERED" != 0 -a "$TOOK" -le "$BOUND")" "" "a push of a tag is refused within the bound"

step "Make the forge stop answering: its server stopped, a listener in its place that never answers"
if serve silent "$PORT"; then
    read_ "127.0.0.1:$PORT takes a connection and answers nothing"
    point 0 "" "the forge stops answering"
else
    shown <"$WORK/server.out" | tail -n 5
    point 1 "" "the forge stops answering"
    finish
fi

step "Push from the task to the silent forge, and read when and how the push ends"
deliver silent "$((BOUND + 60))" 'HEAD:$PORELO_TASK_REF'
read_ "$(grep -c . "$WORK/silent.log" 2>/dev/null || echo 0) connection(s) the silent forge took"
reason "$WORDS" >>"$REASONS"
ended=1
[ "$TOOK" -le "$((BOUND + 15))" ] && [ "$DELIVERED" != 124 ] && ended=0
point "$ended" B197 "a push to a forge that does not answer ends within the bound ($BOUND s)"
point "$(yes_if test "$DELIVERED" != 0)" "" "a push to a forge that does not answer fails rather than passing"
point "$(yes_if grep -q -i 'did not answer' "$WORDS")" B197 "its refusal says the forge did not answer"
point "$(yes_if grep -q -i 'nothing was passed on' "$WORDS")" B197 "its refusal says nothing was passed on"
point "$(yes_if test "$ended" = 0 -a "$(grep -c -i 'upstream refused' "$WORDS")" = 0)" B197 \
    "a silence is not called a refusal"

step "Read that each refusal named its own reason"
shown <"$REASONS"
lines=$(grep -c . "$REASONS")
distinct=$(grep . "$REASONS" | sort -u | grep -c .)
read_ "$lines reason(s), $distinct distinct"
point "$(yes_if test "$lines" -ge 3 -a "$lines" = "$distinct")" "" \
    "the forge's refusal, the gate's refusals and the silence each say a reason of their own"
point "$(yes_if test "$lines" -ge 4 -a "$lines" = "$distinct")" B197 "the silence's reason is among them, its own"

# The gate's own push to the silent forge may still wait; the listener's end closes it.
serve forge "$PORT" || {
    shown <"$WORK/server.out" | tail -n 5
    point 1 "" "the forge answers again"
    finish
}
sleep 3

step "Read the failure afterwards: the task's logs, its status and the task list"
timeout 60 porelo task logs "$CONTAINER" gate.log >"$WORK/gate.log" 2>&1
shown <"$WORK/gate.log" | grep -i -E 'refused|did not answer|upstream' | tail -n 6
point "$(yes_if grep -q -i -E 'this forge takes nothing today|did not answer|upstream refused' "$WORK/gate.log")" "" \
    "the task's gate log keeps what the forge did"
timeout 60 porelo task status "$CONTAINER" >"$WORK/status.out" 2>&1
shown <"$WORK/status.out" | grep -E '^(task|state|forge|workspace|project) '
point "$(yes_if grep -q -E '^workspace .*never pushed to the gate' "$WORK/status.out")" "" \
    "'task status' shows the work the failed delivery left undelivered"
timeout 60 porelo task list >"$WORK/list.out" 2>&1
shown <"$WORK/list.out" | grep -F -e "$TASK" -e TASK | head -n 4
point "$(yes_if grep -q -F "$TASK" "$WORK/list.out")" "" "'task list' still lists the task after its failed delivery"
timeout 60 porelo task status "$CONTAINER" --json >"$WORK/status.json" 2>&1
named=$(python3 -I -c 'import json, sys
try:
    print(json.load(open(sys.argv[1])).get("repository") or "")
except Exception:
    print("")' "$WORK/status.json")
read_ "the task's status names its repository: '${named:-none}'"
point "$(yes_if test "$named" = "$PROJECT")" "" "the task's status names its repository"

step "Bring the forge back, push to the project's repository as the person, and fetch in the task"
git -C "$HOME/$PROJECT" -c user.name=Story -c user.email=story@example.invalid commit -q --allow-empty \
    -m "st39: the person's change"
PERSON=$(git -C "$HOME/$PROJECT" rev-parse HEAD)
git -C "$HOME/$PROJECT" push -q "$CENTRAL" main >"$WORK/person.out" 2>&1
read_ "the person's push: exit $?; main at the forge: $(git --git-dir="$FORGE" rev-parse main 2>/dev/null)"
# The gate asks the forge at most once every few seconds.
sleep 8
in_task 120 'cd /workspace && git fetch -q porelo main && git rev-parse FETCH_HEAD' >"$WORK/fetch.out" 2>&1
shown <"$WORK/fetch.out" | tail -n 4
point "$(yes_if grep -q -x "$PERSON" "$WORK/fetch.out")" "" "the task's next fetch picks up the person's push"

step "Push from the task again, now that the forge answers"
deliver again 120 'HEAD:$PORELO_TASK_REF'
at_forge=$(git --git-dir="$FORGE" rev-parse --verify --quiet "refs/heads/${BRANCH:-none}" 2>/dev/null)
in_task_head=$(podman exec "$CONTAINER" git -C /workspace rev-parse HEAD 2>/dev/null)
point "$(yes_if test "$DELIVERED" = 0 -a -n "$at_forge" -a "$at_forge" = "$in_task_head")" "" \
    "a push after the failure goes through once the forge answers"

# ---------------------------------------------------------------- a message not delivered (B179)

# ask <method> <parameters as JSON>: the daemon's answer, one line, as an interface gets it.
ask() {
    printf '{"method":"org.fuin.porelo.Tasks1.%s","parameters":%s}\0' "$1" "$2" |
        timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -n 1
}
field() {
    python3 -I -c 'import json, sys
try:
    answer = json.loads(sys.argv[1])
except Exception:
    answer = {}
print((answer.get("parameters") or {}).get(sys.argv[2]) or answer.get("error") or "")' "$1" "$2"
}

step "Have the task's agent write a message to nobody, which is held, and pass it"
in_agent 60 'printf "%s\n" "{\"messageId\": \"st39-held\", \"role\": \"ROLE_AGENT\", \"parts\": [{\"text\": \"A note for nobody.\"}], \"metadata\": {\"to\": \"\", \"via\": \"room\"}}" \
    > /run/porelo/mail/outbox/tmp/st39.json && mv /run/porelo/mail/outbox/tmp/st39.json /run/porelo/mail/outbox/new/' \
    >"$WORK/write.out" 2>&1
read_ "written: exit $?"
timeout 120 porelo talk pass "$CONTAINER" 2>&1 | shown
timeout 60 porelo talk held "$CONTAINER" >"$WORK/held.out" 2>&1
shown <"$WORK/held.out"
MESSAGE=$(awk 'NF && $0 != "nothing held" { print $1; exit }' "$WORK/held.out")
point "$(yes_if test -n "$MESSAGE")" "" "a message to nobody is held for a person"

if [ -n "$MESSAGE" ]; then
    step "Release the held message, then refuse it for good, then release it again"
    timeout 60 porelo talk release "$CONTAINER" "$MESSAGE" >"$WORK/release-1.out" 2>&1
    first=$?
    shown <"$WORK/release-1.out"
    read_ "release: exit $first"
    timeout 60 porelo talk release "$CONTAINER" "$MESSAGE" --refuse >"$WORK/refuse.out" 2>&1
    read_ "refuse for good: exit $?"
    shown <"$WORK/refuse.out"
    timeout 60 porelo talk release "$CONTAINER" "$MESSAGE" >"$WORK/release-2.out" 2>&1
    second=$?
    shown <"$WORK/release-2.out"
    read_ "release again: exit $second"
    point "$(yes_if test "$second" != 0 -a "$(grep -c -i 'refused it for good' "$WORK/release-2.out")" -gt 0)" "" \
        "a release of what a person refused for good is refused, saying so"

    step "Ask the daemon for the same release, as an interface does, and read its code"
    params=$(python3 -I -c 'import json, sys; print(json.dumps({"task": sys.argv[1], "id": sys.argv[2]}))' \
        "$CONTAINER" "$MESSAGE")
    answer=$(ask Release "$params")
    outcome=$(field "$answer" outcome)
    code=$(field "$answer" code)
    detail=$(field "$answer" detail)
    read_ "outcome '${outcome:-none}', code '${code:-none}', detail '${detail:-none}'"
    point "$(yes_if test "$outcome" = NOT_DELIVERABLE)" "" "the daemon answers NOT_DELIVERABLE"
    point "$(yes_if test "$code" = REFUSED_BEFORE)" B179 \
        "a release not deliverable carries its code beside the sentence: REFUSED_BEFORE"
fi

finish
