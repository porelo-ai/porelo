#!/usr/bin/env bash
# ST53, a start that waits for its helpers, played at the command line as a person would (B683).
#
# What it does, against the porelo installed in this account:
#   1. makes a vault and a guarded project of its own, so a start has a gate and a credential relay;
#   2. starts a task and reads, the moment the start returns, whether the gate and the relay are up;
#   3. starts a task whose gate cannot record that it is up and reads the start's code (70 expected);
#   4. the same for the relay;
#   5. starts two tasks of one name at once and reads whether the task is still running after both;
#   6. removes everything it made and lists what held and what did not.
#
# A gate or relay "does not come up" here because its pid file is taken: a directory stands where the
# helper writes its pid, so the helper never records it - which the start waits on.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l st53.sh --test-account [--expect-fail ISSUES]
# --expect-fail names the issues not yet in Review, comma-separated ('none' for none); a point waiting on
# one of them is listed as an expected failure; default none. Exit 0 when nothing failed unexpectedly, 1 otherwise,
# 64 for wrong use.
set -u -o pipefail

EXPECTED=""
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
        echo "st53: unknown argument: $1" >&2
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

if [ "$(id -u)" -eq 0 ]; then
    echo "st53: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "st53: refused: this makes and removes tasks, projects and a vault in the account it runs in;" >&2
    echo "      run it only in a test account on the test machine, and say so with --test-account" >&2
    exit 64
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git timeout pgrep; do
    command -v "$tool" >/dev/null || {
        echo "st53: refused: '$tool' is not installed" >&2
        exit 64
    }
done

WORK=$(mktemp -d "${TMPDIR:-/tmp}/st53.XXXXXX") || exit 70
chmod 700 "$WORK"
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
PROJECT="st53-$RUN_ID"
PROJECTS_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects"
RUNTIME="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo"
CONTAINERS=()
MADE_PROJECTS=()
VAULT_MADE=no
CLEANED=no

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
        rm -rf -- "${RUNTIME:?}/$container"
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

# A vault of the run's own, never the account's, as the acceptance kit makes one; the stub agent's
# provider needs a key in it, so the start has a credential broker and its relay.
make_vault() {
    export PORELO_VAULT="$WORK/vault/vault.bin"
    mkdir -m 700 "$WORK/vault"
    local passphrase
    passphrase=$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')
    VAULT_MADE=yes
    printf '%s\n' "$passphrase" | porelo vault init --passphrase-stdin 2>&1 | shown
    printf '%s\n' "$passphrase" | porelo vault unlock --passphrase-stdin 2>&1 | shown
    printf '%s' 'sk-porelo-story-not-a-real-key' | porelo vault put anthropic --type api-key 2>&1 | shown
    # A credential beyond the agent's own, for a declared provider (openrouter): the stub reaches its own over the
    # vault's socket, and only
    # another credential, reached over a URL in the task's namespace, gives a start its relay.
    printf '%s' 'st53-not-a-real-token' | porelo vault put st53 --type api-key 2>&1 | shown
    porelo vault status >/dev/null 2>&1
}

# make_project <name> <class> <more yaml>: a git repository in the home with its project file,
# linked where porelo reads a hand-written project, as the acceptance kit does.
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

# start <task> <output file> [more arguments]: a detached start, as the kit's, answering its code.
start() {
    local task=$1 out=$2
    shift 2
    CONTAINERS+=("porelo-${PROJECT}_$task")
    timeout 900 porelo task start "$task" --project "$PROJECT" --repository "$PROJECT" --agent stub \
        --detach --clearance deny --credential st53=openrouter "$@" >"$out" 2>&1
}

container_of() { sed -n 's/^container[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' "$1" | head -1; }

# alive <pid file>: 0 when the file records a process that is running now.
alive() {
    [ -f "$1" ] || return 1
    local pid
    pid=$(cut -d' ' -f1 <"$1")
    case "$pid" in '' | *[!0-9]*) return 1 ;; esac
    [ -d "/proc/$pid" ]
}

helpers_left() { pgrep -u "$(id -u)" -f -- "porelo/$1/" 2>/dev/null | wc -l; }

# ---------------------------------------------------------------- the story

step "Make a vault of this run's own, with a made-up key for the stub agent's provider"
make_vault
porelo vault list 2>&1 | shown
point "$(
    porelo vault list >/dev/null 2>&1
    echo $?
)" "" "the run's vault opens"

step "Make a guarded project '$PROJECT', so a start has a gate and a credential relay"
if make_project "$PROJECT" guarded 'image:
  from: "ubuntu:24.04"'; then
    read_ "project file at $HOME/$PROJECT/project.yml"
    point 0 "" "the project is there"
else
    point 1 "" "the project is there"
    finish
fi

step "Start task 'waits' and read, the moment the start returns, whether its gate and relay are up"
start waits "$WORK/waits.out"
code=$?
shown <"$WORK/waits.out" | tail -n 30
read_ "exit $code"
container=$(container_of "$WORK/waits.out")
[ -n "$container" ] || container="porelo-${PROJECT}_waits"
state="$RUNTIME/$container"
gate=1
relay=1
alive "$state/gate.pid" && gate=0
alive "$state/relay.pid" && relay=0
for each in gate relay; do
    read_ "$each.pid holds '$(head -c 80 "$state/$each.pid" 2>/dev/null | tr '\n' ' ')'"
done
read_ "the state directory holds: $(ls "$state" 2>/dev/null | tr '\n' ' ')"
read_ "gate.pid $([ "$gate" = 0 ] && echo 'names a running gate' || echo 'names no running gate')"
read_ "relay.pid $([ "$relay" = 0 ] && echo 'names a running relay' || echo 'names no running relay')"
point "$code" "" "a start of a task with a gate and a credential answers 0"
point "$((gate == 0 && relay == 0 && code == 0 ? 0 : 1))" "" \
    "a start that answers 0 has its gate and its relay up when it returns"
timeout 300 porelo task remove "$container" --force --no-input >/dev/null 2>&1
read_ "task $container removed again"

for helper in gate relay; do
    task="${helper}down"
    step "Start task '$task' whose $helper cannot record that it is up, and read the start's code"
    blocked="$RUNTIME/porelo-${PROJECT}_$task"
    # The start refuses a task whose state directory is there before it ("occupied or unrecognized"), so
    # the directory is put in place once the start has made the state directory itself, well before its
    # helpers start after the image and the container.
    (
        for _ in $(seq 1800); do
            if [ -d "$blocked" ]; then
                mkdir "$blocked/$helper.pid" 2>/dev/null
                exit 0
            fi
            sleep 0.1
        done
    ) &
    blocker=$!
    read_ "a directory is put at $blocked/$helper.pid, where the $helper writes its pid, once the start makes $blocked"
    start "$task" "$WORK/$task.out"
    code=$?
    kill "$blocker" 2>/dev/null
    wait "$blocker" 2>/dev/null
    shown <"$WORK/$task.out" | tail -n 30
    read_ "exit $code"
    container=$(container_of "$WORK/$task.out")
    [ -n "$container" ] || container="porelo-${PROJECT}_$task"
    if [ -d "$RUNTIME/$container/$helper.pid" ]; then
        read_ "the $helper never recorded its pid: the obstruction held"
    elif [ "$code" != 0 ] && grep -q -i "$helper" "$WORK/$task.out"; then
        # A helper that fails clears what stands at its pid file; the start's words say which failed.
        read_ "the $helper's pid file is gone after it failed, and the start names the $helper"
    else
        read_ "the $helper's pid file is not the directory this run put there: the obstruction missed"
        point 1 "" "the $helper was kept from coming up (script: the state directory moved)"
    fi
    point "$((code == 70 ? 0 : 1))" "" "a start whose $helper does not come up ends with code 70"
    sleep 2
    left=$(helpers_left "$container")
    read_ "$left process(es) still name the task's state directory"
    point "$((code != 0 && left == 0 ? 0 : 1))" "" \
        "a start whose $helper does not come up leaves none of its helpers running"
    timeout 300 porelo task remove "$container" --force --no-input >/dev/null 2>&1
    rm -rf -- "${RUNTIME:?}/porelo-${PROJECT}_$task"
    read_ "task $container removed again"
done

step "Start two tasks of one name, 'twin', at the same moment, and read both answers"
# Named for the cleanup here: a start in the background records nothing in this shell.
CONTAINERS+=("porelo-${PROJECT}_twin")
start twin "$WORK/twin-1.out" &
first=$!
start twin "$WORK/twin-2.out" &
second=$!
wait "$first"
code1=$?
wait "$second"
code2=$?
read_ "first start: exit $code1"
shown <"$WORK/twin-1.out" | tail -n 12
read_ "second start: exit $code2"
shown <"$WORK/twin-2.out" | tail -n 12
container="porelo-${PROJECT}_twin"
sleep 2
running=$(podman container inspect --format '{{.State.Running}}' "$container" 2>/dev/null)
read_ "the container $container is running: ${running:-not there}"
gate=1
alive "$RUNTIME/$container/gate.pid" && gate=0
read_ "its gate $([ "$gate" = 0 ] && echo 'runs' || echo 'does not run')"
point "$((code1 == 0 || code2 == 0 ? 0 : 1))" "" "one of two starts of one name starts the task"
point "$([ "$running" = true ] && [ "$gate" = 0 ] && echo 0 || echo 1)" "" \
    "two starts of one name never stop each other's task: it still runs with its gate"

finish
