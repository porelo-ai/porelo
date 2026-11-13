#!/usr/bin/env bash
# ST30, work without a project, with plain refusals, played at the command line as a person would (B682).
#
# What it does, against the porelo installed in this account:
#   1. makes a plain checkout and starts a task in it with no project named: it goes into 'default', says
#      that 'default' has no room (B594), and 'task list' marks it as unsigned (B355);
#   2. asks for peers of 'default', refused naming a project repository as the way (B594), and for a
#      switch that turns every check off, which there is none of;
#   3. fetches from a forge inside the task, which fails as what it is, not as a credential (B33);
#   4. adds a repository a followed project - one of the organization's - names to 'default', and asks the
#      daemon, as the window does, whether it is offered to that project (B595); played only where this
#      account follows an organization's project;
#   5. starts in a checkout with no commit, on a repository whose upstream cannot be read and on one
#      whose fetch fails, reading that each refusal names what it is and the next step;
#   6. starts in a checkout whose project.yml nobody took (B457) and in one whose porelo.yml asks for more
#      than 'default' gives (B470), with no terminal;
#   7. removes everything it made and lists what held and what did not.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l st30.sh --test-account [--expect-fail ISSUES]
# --expect-fail names the issues not yet in Review, comma-separated ('none' for none); default B33,B355.
# Exit 0 when nothing failed unexpectedly, 1 otherwise, 64 for wrong use.

# The scripts sent into the task are single-quoted on purpose: they expand there, not here.
# shellcheck disable=SC2016
set -u -o pipefail

EXPECTED="B33,B355"
TEST_ACCOUNT=no
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
    --test-account)
        TEST_ACCOUNT=yes
        shift
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "st30: unknown argument: $1" >&2
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

if [ "$(id -u)" -eq 0 ]; then
    echo "st30: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "st30: refused: this makes and removes tasks, repositories of 'default' and a vault in the account" >&2
    echo "      it runs in; run it only in a test account on the test machine, and say so with --test-account" >&2
    exit 64
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git timeout python3 awk; do
    command -v "$tool" >/dev/null || {
        echo "st30: refused: '$tool' is not installed" >&2
        exit 64
    }
done

WORK=$(mktemp -d "${TMPDIR:-/tmp}/st30.XXXXXX") || exit 70
chmod 700 "$WORK"
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
CONTAINERS=()
DEFAULT_MADE=()
VAULT_MADE=no
CLEANED=no

cleanup() {
    [ "$CLEANED" = yes ] && return 0
    CLEANED=yes
    printf '\n== cleaning up what this run made\n'
    local container name
    for container in "${CONTAINERS[@]}"; do
        if podman container exists "$container" 2>/dev/null; then
            timeout 300 porelo task remove "$container" --force --no-input >/dev/null 2>&1
            podman container exists "$container" 2>/dev/null &&
                podman rm -f "$container" >/dev/null 2>&1
            read_ "task $container removed"
        fi
    done
    for name in "${DEFAULT_MADE[@]}"; do
        if porelo project default list 2>/dev/null | grep -q -E "^$name( |$)"; then
            timeout 120 porelo project default remove "$name" >/dev/null 2>&1
            read_ "'$name' taken out of 'default'"
        fi
    done
    if [ "$VAULT_MADE" = yes ]; then
        porelo vault lock >/dev/null 2>&1
        read_ "the run's vault locked and removed"
    fi
    chmod -R u+rwx "$WORK" 2>/dev/null
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

container_of() { sed -n 's/^container[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' "$1" | head -1; }

# checkout <name> <commit: yes|no>: a bare origin under the run's directory and a clone of it named
# <name>, as a person keeps one; with no commit, a fresh repository whose origin is that bare one.
checkout() {
    local name=$1 commit=$2
    git init -q --bare -b main "$WORK/origin/$name.git" || return 1
    if [ "$commit" = yes ]; then
        git init -q -b main "$WORK/seed-$name" &&
            printf 'the work\n' >"$WORK/seed-$name/README.md" &&
            git -C "$WORK/seed-$name" add -A &&
            git -C "$WORK/seed-$name" -c user.name=Story -c user.email=story@example.invalid commit -q -m first &&
            git -C "$WORK/seed-$name" push -q "$WORK/origin/$name.git" main &&
            git clone -q "$WORK/origin/$name.git" "$WORK/$name"
    else
        git init -q -b main "$WORK/$name" && git -C "$WORK/$name" remote add origin "$WORK/origin/$name.git"
    fi
}

# start_in <checkout> <task> <output file> [more arguments]: a start from the checkout, as a person types
# it standing there with no project named and no terminal, answering its code.
start_in() {
    local dir=$1 task=$2 out=$3
    shift 3
    CONTAINERS+=("porelo-default_$task")
    DEFAULT_MADE+=("$(basename "$dir")")
    (cd "$dir" && timeout 900 porelo task start "$task" --agent stub --detach --clearance deny --no-input "$@") \
        >"$out" 2>&1
}

# start_named <repository of default> <task> <output file>: a start in 'default' naming the repository.
start_named() {
    CONTAINERS+=("porelo-default_$2")
    (cd "$WORK" && timeout 900 porelo task start "$2" -p default -r "$1" --agent stub --detach --clearance deny \
        --no-input) >"$3" 2>&1
}

made() { podman container exists "$1" 2>/dev/null; }

# A refusal names what it is and what to do next. 'next' is read loosely: a command to run, or a git
# step a person takes, in the refusal's words.
# shellcheck disable=SC2329 # called through yes_if
names_next() { grep -q -E -i "porelo [a-z]|git (commit|push|clone|remote|fetch)|commit (first|one)|first commit|make a commit|run |check " "$1"; }

# ---------------------------------------------------------------- work in 'default'

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
mkdir -p "$WORK/origin"

PLAIN="st30-$RUN_ID-plain"
TASK="plain-$RUN_ID"
step "Make a plain checkout '$PLAIN' and start a task in it, naming no project"
checkout "$PLAIN" yes
start_in "$WORK/$PLAIN" "$TASK" "$WORK/plain.out"
code=$?
shown <"$WORK/plain.out" | tail -n 40
read_ "exit $code"
CONTAINER=$(container_of "$WORK/plain.out")
[ -n "$CONTAINER" ] || CONTAINER="porelo-default_$TASK"
point "$code" "" "a task starts in a checkout with no project named"
point "$(yes_if grep -q -F "in 'default' as '$PLAIN'" "$WORK/plain.out")" "" \
    "the start says the checkout went into 'default', and under which name"
porelo project default list >"$WORK/default.out" 2>&1
grep -E "^$PLAIN( |$)" "$WORK/default.out" | shown
point "$(yes_if grep -q -E "^$PLAIN( |$)" "$WORK/default.out")" "" "'project default' lists the checkout"
point "$(yes_if grep -q -i "'default' has no room" "$WORK/plain.out")" B594 \
    "the start says once that 'default' has no room, and starts"
timeout 60 porelo task list >"$WORK/list.out" 2>&1
row=$(grep -E "^$TASK +default( |$)" "$WORK/list.out" | head -n 1)
grep -E "^(TASK|task) " "$WORK/list.out" | head -n 1 | shown
printf '%s\n' "$row" | shown
point "$(yes_if test -n "$row")" "" "'task list' shows the task in 'default'"
point "$(yes_if grep -q -i -E 'unsigned|unverified' <<<"$row")" B355 \
    "'task list' marks a task that runs unsigned as such"

step "Ask for the peers of 'default', which talks to nobody"
timeout 60 porelo talk peers --project default >"$WORK/peers.out" 2>&1
code=$?
shown <"$WORK/peers.out"
read_ "exit $code"
point "$(yes_if test "$code" = 78)" B594 "peers for 'default' are refused as a refused setting (78)"
point "$(yes_if grep -q -i 'a project repository is the way' "$WORK/peers.out")" B594 \
    "the refusal names a project repository as the way"

step "Ask for a switch that turns every check off"
timeout 60 porelo task start "insecure-$RUN_ID" --insecure --agent stub --no-input >"$WORK/insecure.out" 2>&1
code=$?
shown <"$WORK/insecure.out" | head -n 4
read_ "exit $code"
point "$({ test "$code" = 64 && ! made "porelo-default_insecure-$RUN_ID"; } && echo 0 || echo 1)" "" \
    "there is no switch that turns every check off: '--insecure' is refused as unknown"

step "Fetch from a forge inside the task, anonymously, as the work itself would"
timeout 120 podman exec "$CONTAINER" sh -c 'cd /workspace && timeout 60 git ls-remote https://github.com/git/git HEAD' \
    >"$WORK/fetch.out" 2>&1
code=$?
shown <"$WORK/fetch.out" | tail -n 5
read_ "exit $code"
point "$({ test "$code" != 0 && ! grep -q -i -E '401|authentication|credential|password' "$WORK/fetch.out"; } && echo 0 || echo 1)" B33 \
    "a fetch the task cannot make fails as what it is, never as a credential problem"

# ---------------------------------------------------------------- a followed project

# ask <method> <parameters as JSON>: the daemon's answer, one line, as an interface gets it.
ask() {
    printf '{"method":"org.fuin.porelo.Tasks1.%s","parameters":%s}\0' "$1" "$2" |
        timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -n 1
}

step "Find a project of the organization this account follows, and a repository it names"
timeout 120 porelo project list --json >"$WORK/projects.json" 2>&1
FOLLOWED=$(python3 -I -c 'import json, sys
try:
    rows = json.load(open(sys.argv[1])).get("projects") or []
except Exception:
    rows = []
print(next((r.get("project") for r in rows if isinstance(r, dict) and r.get("source") == "organization"), "") or "")' \
    "$WORK/projects.json")
ADDRESS=""
if [ -n "$FOLLOWED" ]; then
    timeout 120 porelo project show "$FOLLOWED" --json >"$WORK/followed.json" 2>&1
    ADDRESS=$(python3 -I -c 'import json, sys
def walk(value):
    if isinstance(value, dict):
        for key in ("central", "upstream"):
            if isinstance(value.get(key), str) and value[key]:
                yield value[key]
        for each in value.values():
            yield from walk(each)
    elif isinstance(value, list):
        for each in value:
            yield from walk(each)
try:
    print(next(walk(json.load(open(sys.argv[1])).get("repositories") or []), ""))
except Exception:
    print("")' "$WORK/followed.json")
fi
read_ "followed project: '${FOLLOWED:-none}'; a repository it names: '${ADDRESS:-none}'"

if [ -z "$FOLLOWED" ] || [ -z "$ADDRESS" ]; then
    point 1 "" "a followed project naming a repository of 'default' is offered to take it (not played: this account follows no organization's project naming a repository)"
else
    NAMED="st30-$RUN_ID-named"
    step "Add that repository to 'default' as '$NAMED', and ask the daemon what is offered, as the window does"
    DEFAULT_MADE+=("$NAMED")
    timeout 120 porelo project default add "$ADDRESS" --name "$NAMED" 2>&1 | shown
    answer=$(ask NamedDefaultRepositories '{}')
    offered=$(python3 -I -c 'import json, sys
try:
    answer = json.loads(sys.argv[1])
except Exception:
    answer = {}
for value in (answer.get("parameters") or {}).values():
    if isinstance(value, list):
        for row in value:
            if isinstance(row, dict) and row.get("name") == sys.argv[2]:
                print("%s %s %s" % (row.get("project"), row.get("projectRepository"), row.get("removal")))' \
        "$answer" "$NAMED")
    read_ "offered: ${offered:-nothing for $NAMED}"
    point "$(yes_if test "${offered%% *}" = "$FOLLOWED")" B595 \
        "a repository of 'default' that a followed project names is offered to that project"
    point "$(yes_if grep -q -F "porelo project default remove $NAMED" <<<"$offered")" B595 \
        "the offer names the command that takes it out of 'default'"
    timeout 120 porelo project default remove "$NAMED" >"$WORK/leave.out" 2>&1
    code=$?
    shown <"$WORK/leave.out"
    point "$code" "" "the person takes it out of 'default'"
fi

# ---------------------------------------------------------------- plain refusals

EMPTY="st30-$RUN_ID-empty"
step "Start in a checkout '$EMPTY' that holds no commit"
checkout "$EMPTY" no
start_in "$WORK/$EMPTY" "empty-$RUN_ID" "$WORK/empty.out"
code=$?
shown <"$WORK/empty.out" | tail -n 20
read_ "exit $code"
point "$({ test "$code" != 0 && ! made "porelo-default_empty-$RUN_ID"; } && echo 0 || echo 1)" "" \
    "a start in a repository with no commit is refused, and nothing is made"
point "$(yes_if grep -q -i -E 'no commit|commit yet|holds no commit|is empty|empty repository' "$WORK/empty.out")" "" \
    "the refusal names a repository with no commit as what it is"
point "$(yes_if names_next "$WORK/empty.out")" "" "the refusal names the next step"

LOCKED="st30-$RUN_ID-locked"
step "Add a repository to 'default' whose upstream cannot be read, and start on it"
git init -q --bare -b main "$WORK/origin/$LOCKED.git"
chmod 000 "$WORK/origin/$LOCKED.git"
DEFAULT_MADE+=("$LOCKED")
timeout 120 porelo project default add "$WORK/origin/$LOCKED.git" --name "$LOCKED" >"$WORK/locked.out" 2>&1
added=$?
if [ "$added" = 0 ]; then
    start_named "$LOCKED" "locked-$RUN_ID" "$WORK/locked.out"
    code=$?
else
    code=$added
    read_ "'project default add' refused it already"
fi
shown <"$WORK/locked.out" | tail -n 20
read_ "exit $code"
point "$({ test "$code" != 0 && ! made "porelo-default_locked-$RUN_ID"; } && echo 0 || echo 1)" "" \
    "work on a repository whose upstream cannot be read is refused, and nothing is made"
point "$(yes_if grep -q -i -E 'cannot be read|could not read|cannot read|not readable|unreadable|permission denied' "$WORK/locked.out")" "" \
    "the refusal names an upstream that cannot be read as what it is"
point "$(yes_if names_next "$WORK/locked.out")" "" "the refusal names the next step"

GONE="st30-$RUN_ID-gone"
step "Add a repository to 'default' whose fetch fails - nothing answers at its address - and start on it"
PORT=$(python3 -I -c 'import socket
s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
DEFAULT_MADE+=("$GONE")
timeout 120 porelo project default add "http://127.0.0.1:$PORT/$GONE.git" --name "$GONE" >"$WORK/gone.out" 2>&1
added=$?
if [ "$added" = 0 ]; then
    start_named "$GONE" "gone-$RUN_ID" "$WORK/gone.out"
    code=$?
else
    code=$added
    read_ "'project default add' refused it already"
fi
shown <"$WORK/gone.out" | tail -n 20
read_ "exit $code"
point "$({ test "$code" != 0 && ! made "porelo-default_gone-$RUN_ID"; } && echo 0 || echo 1)" "" \
    "work on a repository whose fetch fails is refused, and nothing is made"
point "$(yes_if grep -q -i -E 'fetch|could not connect|connection refused|unreachable|cannot be reached|could not reach' "$WORK/gone.out")" "" \
    "the refusal names a failed fetch as what it is"
point "$(yes_if names_next "$WORK/gone.out")" "" "the refusal names the next step"

# ---------------------------------------------------------------- files a start does not take

FILED="st30-$RUN_ID-filed"
step "Start with no terminal in a checkout '$FILED' whose project.yml nobody took"
checkout "$FILED" yes
printf 'project:\n  name: "%s-project"\n  security_class: "guarded"\nimage:\n  from: "ubuntu:24.04"\n' "$FILED" \
    >"$WORK/$FILED/project.yml"
git -C "$WORK/$FILED" add project.yml &&
    git -C "$WORK/$FILED" -c user.name=Story -c user.email=story@example.invalid commit -q -m 'a project file'
start_in "$WORK/$FILED" "filed-$RUN_ID" "$WORK/filed.out"
code=$?
shown <"$WORK/filed.out" | tail -n 30
read_ "exit $code"
point "$(yes_if grep -q 'porelo: warning:' "$WORK/filed.out")" B457 \
    "a start going on without the project.yml it did not take says so as a warning"
point "$({ test "$code" = 0 && grep -q -F "'default'" "$WORK/filed.out"; } && echo 0 || echo 1)" B457 \
    "the start goes on in 'default', not from that file"
point "$(yes_if test "$(grep -c $'\e' "$WORK/filed.out")" = 0)" B457 "the warning is not painted into a file"

ASKS="st30-$RUN_ID-asks"
step "Start with no terminal in a checkout '$ASKS' whose porelo.yml asks to reach a host"
checkout "$ASKS" yes
printf 'egress:\n  domains: ["example.org"]\n' >"$WORK/$ASKS/porelo.yml"
git -C "$WORK/$ASKS" add porelo.yml &&
    git -C "$WORK/$ASKS" -c user.name=Story -c user.email=story@example.invalid commit -q -m 'what the task needs'
start_in "$WORK/$ASKS" "asks-$RUN_ID" "$WORK/asks.out"
code=$?
shown <"$WORK/asks.out" | tail -n 30
read_ "exit $code"
point "$({ test "$code" != 0 && ! made "porelo-default_asks-$RUN_ID"; } && echo 0 || echo 1)" B470 \
    "a repository's settings not yet allowed on this machine are refused without a terminal, and nothing is made"
point "$({ grep -q -F 'porelo task start' "$WORK/asks.out" && grep -q -i 'terminal' "$WORK/asks.out"; } && echo 0 || echo 1)" B470 \
    "the refusal names 'porelo task start' at a terminal as the next step"
point "$(yes_if grep -q -F 'example.org' "$WORK/asks.out")" B470 "the refusal names what the file asks for"

finish
