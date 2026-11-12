#!/usr/bin/env bash
# ST56, the gathered findings worked off, played at the command line as a person would (B685).
#
# The gathered findings are porelo's B380, porelo-host's HO86 and porelo-frontend's F288. B380 was split
# into B649, B650 and B651 with five points dropped; HO86 moved to porelo's B666; B203 took the check of
# task names. What it does, against the porelo installed in this account:
#   1. reads the three repositories' issue indexes and checks each gathering issue holds nothing open
#      and each point that proved larger is filed as an issue of its own, and built (Review or Finished);
#   2. B649: a signed commit judged by 'porelo commit verify' with an allowed-signers file holding a
#      lone CR, a line ssh-keygen cannot parse, or a Unicode space before '#', beside git's own verdict;
#   3. B650: a stopped --no-gate task removed without --force; the suggestion for a refused task name;
#      one code for a start wrong twice; a project name that leaves a task no room, at 'project init';
#   4. B203: a task's commands answering by the task's name and by its container's name alike;
#   5. removes everything it made and lists what held and what did not.
# B651 (tests only), B666 (a host's push judge) and F288 (the desktop interface) show nothing at this
# command line; they are checked as filed.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l st56.sh --test-account [--expect-fail ISSUES] [--issues DIR]
# --expect-fail names the issues not yet in Review, comma-separated ('none' for none); default
# B649,B650,B651,B203,B666,F288. --issues names a directory holding porelo.md, porelo-host.md and
# porelo-frontend.md, the three issue indexes; without it they are read from GitHub's main branches.
# Exit 0 when nothing failed unexpectedly, 1 otherwise, 64 for wrong use.

set -u -o pipefail

EXPECTED="B649,B650,B651,B203,B666,F288"
TEST_ACCOUNT=no
ISSUES=""
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
    --issues)
        [ $# -ge 2 ] || {
            usage >&2
            exit 64
        }
        ISSUES="$2"
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
        echo "st56: unknown argument: $1" >&2
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
    echo "st56: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "st56: refused: this makes and removes tasks, projects and a vault in the account it runs in;" >&2
    echo "      run it only in a test account on the test machine, and say so with --test-account" >&2
    exit 64
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git timeout ssh-keygen awk; do
    command -v "$tool" >/dev/null || {
        echo "st56: refused: '$tool' is not installed" >&2
        exit 64
    }
done
[ -n "$ISSUES" ] || command -v curl >/dev/null || {
    echo "st56: refused: 'curl' is not installed" >&2
    exit 64
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/st56.XXXXXX") || exit 70
chmod 700 "$WORK"
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
PROJECT="st56-$RUN_ID"
PROJECTS_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects"
CONTAINERS=()
MADE_PROJECTS=()
MADE_DIRS=()
VAULT_MADE=no
CLEANED=no

cleanup() {
    [ "$CLEANED" = yes ] && return 0
    CLEANED=yes
    printf '\n== cleaning up what this run made\n'
    local container project dir
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
    for dir in "${MADE_DIRS[@]}"; do rm -rf -- "$dir"; done
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

# start <task> <output file> [more arguments]: a detached start in the run's project, answering its code.
start() {
    local task=$1 out=$2
    shift 2
    CONTAINERS+=("porelo-$PROJECT-$task")
    timeout 900 porelo task start "$task" --project "$PROJECT" --repository "$PROJECT" --agent stub \
        --detach --clearance deny "$@" >"$out" 2>&1
}

# ---------------------------------------------------------------- the issue indexes

# state <index file> <id>: the heading of the part of the index the issue's row stands in, or nothing.
state() {
    awk -v id="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" '
        /^##+ / { part = $0; sub(/^##+ /, "", part) }
        index($0, "<a id=\"" id "\"></a>") { print part; exit }' "$1"
}
row() { grep -F "<a id=\"$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')\"></a>" "$1" | head -1; }
# shellcheck disable=SC2329 # called through yes_if
built() {
    case "$1" in Review | Finished) return 0 ;; esac
    return 1
}

step "Read the issue indexes of porelo, porelo-host and porelo-frontend"
mkdir "$WORK/issues"
for repository in porelo porelo-host porelo-frontend; do
    if [ -n "$ISSUES" ]; then
        cp -- "$ISSUES/$repository.md" "$WORK/issues/$repository.md" 2>/dev/null
    else
        case "$repository" in
        porelo) path=issues/base/README.md ;;
        *) path=issues/README.md ;;
        esac
        curl -sfL --max-time 60 -o "$WORK/issues/$repository.md" \
            "https://raw.githubusercontent.com/porelo-ai/$repository/main/$path"
    fi
    if [ -s "$WORK/issues/$repository.md" ]; then
        read_ "$repository: $(grep -c '<a id=' "$WORK/issues/$repository.md") issue rows"
    else
        read_ "$repository: no index could be read"
    fi
done
INDEX="$WORK/issues/porelo.md"
HOST_INDEX="$WORK/issues/porelo-host.md"
FRONTEND_INDEX="$WORK/issues/porelo-frontend.md"
point "$(yes_if test -s "$INDEX" -a -s "$HOST_INDEX" -a -s "$FRONTEND_INDEX")" "" "the three issue indexes are read"

step "Read that the gathering issue B380 holds no open point"
b380=$(state "$INDEX" B380)
read_ "B380 stands under '${b380:-nowhere}'"
row "$INDEX" B380 | cut -c1-400 | shown
point "$(yes_if test "$b380" = Finished -o "$b380" = Rejected)" "" "B380 is closed"
point "$(row "$INDEX" B380 | grep -q 'B649' && row "$INDEX" B380 | grep -q 'B650' &&
    row "$INDEX" B380 | grep -q 'B651' && row "$INDEX" B380 | grep -q -i 'not worth it' && echo 0 || echo 1)" "" \
    "B380 names where each point went: B649, B650, B651, and the points decided not worth it"

step "Read that HO86 holds no open point and that its edges moved to porelo's B666"
ho86=$(state "$HOST_INDEX" HO86)
read_ "HO86 stands under '${ho86:-nowhere}'"
row "$HOST_INDEX" HO86 | cut -c1-400 | shown
point "$(yes_if test "$ho86" = Finished -o "$ho86" = Rejected)" "" "HO86 is closed"
point "$(row "$HOST_INDEX" HO86 | grep -q 'B666' && echo 0 || echo 1)" "" "HO86 names B666 as where its points went"

step "Read that F288 is filed in porelo-frontend, and where it stands"
f288=$(state "$FRONTEND_INDEX" F288)
read_ "F288 stands under '${f288:-nowhere}'"
point "$(yes_if test -n "$f288")" "" "F288 is filed"
point "$(yes_if built "$f288")" F288 "F288's points are worked off (F288 in Review or Finished)"

step "Read that each point that proved larger is an issue of its own, and where it stands"
for id in B649 B650 B651 B203 B666; do
    where=$(state "$INDEX" "$id")
    read_ "$id stands under '${where:-nowhere}'"
    point "$(yes_if test -n "$where")" "" "$id is filed"
    point "$(yes_if built "$where")" "$id" "$id is built (in Review or Finished)"
done

# ---------------------------------------------------------------- B649: signers files as git reads them

step "Make a commit signed with an SSH key, in a repository where it is new, as a push brings it"
S="$WORK/signers"
mkdir "$S"
ssh-keygen -q -t ed25519 -N '' -C alice -f "$S/alice" </dev/null
ssh-keygen -q -t ed25519 -N '' -C other -f "$S/other" </dev/null
OTHER=$(cut -d' ' -f2 <"$S/other.pub")
git init -q -b main "$S/work"
printf 'signed\n' >"$S/work/file"
git -C "$S/work" add file
env -u SSH_AUTH_SOCK git -C "$S/work" -c user.name=Alice -c user.email=alice@example.com -c gpg.format=ssh \
    -c user.signingkey="$S/alice" commit -q -S -m signed >"$S/commit.out" 2>&1
NEW=$(git -C "$S/work" rev-parse HEAD 2>/dev/null)
git init -q --bare "$S/arrived.git"
git -C "$S/arrived.git" fetch -q "$S/work" +refs/heads/main:refs/heads/incoming &&
    git -C "$S/arrived.git" update-ref -d refs/heads/incoming
: >"$S/certifiers"
read_ "commit ${NEW:-none}; $(git -C "$S/arrived.git" rev-list "${NEW:-HEAD}" --not --all 2>/dev/null | wc -l) commit(s) new to the bare repository"
shown <"$S/commit.out"
point "$(yes_if test -n "$NEW")" "" "a signed commit is made"

# judge <signers file>: porelo's exit code, and git's own verdict for the same file read beside it.
judge() {
    local file=$1 code git
    printf '0000000000000000000000000000000000000000 %s refs/heads/main\n' "$NEW" |
        porelo commit verify --repository "$S/arrived.git" --signers "$file" --certifiers "$S/certifiers" \
            >"$S/verify.out" 2>&1
    code=$?
    if env -u SSH_AUTH_SOCK git -C "$S/work" -c gpg.ssh.allowedSignersFile="$file" verify-commit HEAD \
        >"$S/git.out" 2>&1; then git=accepts; else git=refuses; fi
    read_ "porelo commit verify: exit $code; git verify-commit: $git"
    shown <"$S/verify.out" | head -n 6
    JUDGED=$code
    GIT=$git
}

ALICE="alice@example.com $(cut -d' ' -f1,2 <"$S/alice.pub")"
step "Judge the commit with an allowed-signers file of Alice's line alone"
printf '%s\n' "$ALICE" >"$S/plain"
judge "$S/plain"
point "$(yes_if test "$JUDGED" = 0 -a "$GIT" = accepts)" "" "porelo and git both accept the commit by a plain signers file"
BASELINE=$JUDGED

for case in cr unparsed unicode; do
    case "$case" in
    cr)
        label="a line holding a lone CR, 'carol<CR>ssh-ed25519 K'"
        printf '%s\ncarol\rssh-ed25519 %s\n' "$ALICE" "$OTHER" >"$S/$case"
        ;;
    unparsed)
        label="a line ssh-keygen cannot parse"
        printf '%s\nbob ssh-ed25519 not-a-key-at-all\n' "$ALICE" >"$S/$case"
        ;;
    unicode)
        label="a line beginning with U+2000 before '#'"
        printf '%s\n\342\200\200#x ssh-ed25519 %s\n' "$ALICE" "$OTHER" >"$S/$case"
        ;;
    esac
    step "Judge it with Alice's line and $label"
    judge "$S/$case"
    if [ "$BASELINE" != 0 ]; then
        point 1 "" "porelo reads a signers file with $label as git does (not measured: the plain file was refused)"
    elif [ "$GIT" != accepts ]; then
        point 1 "" "porelo reads a signers file with $label as git does (not measured: git refuses it too)"
    else
        point "$(yes_if test "$JUDGED" = 0)" B649 "porelo accepts a signers file with $label, as git does"
    fi
done

# ---------------------------------------------------------------- B650 and B203: a start's refusals

step "Make a vault of this run's own and a guarded project '$PROJECT' for the stub agent"
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
if make_project "$PROJECT" guarded 'image:
  from: "ubuntu:24.04"'; then
    point 0 "" "the vault and the project are there"
else
    point 1 "" "the vault and the project are there"
    finish
fi

step "Start a --no-gate task, stop it, and remove it without --force"
start loose "$WORK/loose.out" --no-gate
code=$?
shown <"$WORK/loose.out" | tail -n 15
read_ "start: exit $code"
point "$code" "" "a --no-gate task starts"
LOOSE="porelo-$PROJECT-loose"
timeout 300 porelo task stop "$LOOSE" >"$WORK/stop.out" 2>&1
read_ "stop: exit $?"

step "Ask for the stopped task by its own name and by its container's name"
for command in files status; do
    timeout 60 porelo task "$command" loose >"$WORK/by-name.out" 2>&1
    by_name=$?
    timeout 60 porelo task "$command" "$LOOSE" >"$WORK/by-container.out" 2>&1
    by_container=$?
    read_ "task $command loose: exit $by_name; task $command $LOOSE: exit $by_container"
    point "$(yes_if test "$by_name" = 0 -a "$by_container" = 0)" B203 \
        "'porelo task $command' finds a task by its own name and by its container's name"
done

step "Remove the stopped --no-gate task without --force"
timeout 300 porelo task remove "$LOOSE" --no-input >"$WORK/remove.out" 2>&1
code=$?
shown <"$WORK/remove.out" | tail -n 8
gone=1
podman container exists "$LOOSE" 2>/dev/null || gone=0
read_ "remove: exit $code; the container is $([ "$gone" = 0 ] && echo gone || echo 'still there')"
point "$(yes_if test "$code" = 0 -a "$gone" = 0)" B650 "a stopped --no-gate task is removed without --force"

step "Start tasks whose names no task may have, and read the name each refusal suggests"
for pair in '123 |123' 'People|people' 'Porelo-x|porelo-x'; do
    given=${pair%%|*}
    wrong=${pair#*|}
    start "$given" "$WORK/name.out"
    code=$?
    shown <"$WORK/name.out" | tail -n 6
    read_ "start '$given': exit $code"
    suggested=1
    grep -q -E "[\"'\`]${wrong}[\"'\`]" "$WORK/name.out" && suggested=0
    [ "$code" != 0 ] || point 1 "" "a start of the name '$given' is refused"
    point "$(yes_if test "$code" != 0 -a "$suggested" = 1)" B650 \
        "the refusal of '$given' suggests no name another rule refuses, such as '$wrong'"
done

step "Start with an unknown -r, alone and together with a name no task may have, and read both codes"
timeout 300 porelo task start fine --project "$PROJECT" -r no-such-repository --agent stub --detach \
    --clearance deny >"$WORK/alone.out" 2>&1
alone=$?
CONTAINERS+=("porelo-$PROJECT-fine")
timeout 300 porelo task start 'Not Fine' --project "$PROJECT" -r no-such-repository --agent stub --detach \
    --clearance deny >"$WORK/twice.out" 2>&1
twice=$?
shown <"$WORK/alone.out" | tail -n 4
shown <"$WORK/twice.out" | tail -n 4
read_ "unknown -r alone: exit $alone; with a refused name beside it: exit $twice"
point "$(yes_if test "$alone" != 0 -a "$alone" = "$twice")" B650 \
    "a start wrong twice answers the code its unknown -r answers alone"

step "Read a project of a name too long to leave a task room, at 'porelo project init'"
LONG="st56-$RUN_ID-$(printf '%s' 'a-name-that-leaves-a-task-no-room-at-all-in-the-end' | cut -c1-50)"
CHECKOUT="$WORK/long-checkout"
MADE_DIRS+=("$CHECKOUT")
git init -q -b main "$CHECKOUT" && printf 'x\n' >"$CHECKOUT/README.md" && git -C "$CHECKOUT" add -A &&
    git -C "$CHECKOUT" -c user.name=Story -c user.email=story@example.invalid commit -q -m initial
read_ "the name '$LONG' has ${#LONG} characters"
timeout 300 porelo project init "$CHECKOUT" --name "$LONG" >"$WORK/init.out" 2>&1
code=$?
shown <"$WORK/init.out" | tail -n 8
read_ "project init: exit $code"
point "$(yes_if test "$code" != 0 -a -n "$(grep -i -E 'room|too long' "$WORK/init.out")")" B650 \
    "a project name that leaves a task no room is refused at 'project init', naming the room"

finish
