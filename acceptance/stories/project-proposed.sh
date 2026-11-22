#!/usr/bin/env bash
# A Maven repository's build tools recognized and a project proposed, played at the command line as a person
# would, against the Maven ecosystem adapter as its package installs it.
#
# What it does, in the account it runs in, with the porelo installed there:
#   1. installs the adapter account-locally from its .deb (checked against the given sha256), as Porelo finds it
#      under $XDG_DATA_HOME/porelo/ecosystems;
#   2. reads a copy of a real Maven checkout that has its own porelo.yml, with that file set aside: offline (no
#      vendor reachable: every lookup through a proxy that answers nothing), once with its parent pom missing from the local Maven repository and
#      once with it there; then with the network; the tools the draft names are set beside the set-aside file's,
#      and the checkout's tracked files are checked to be unchanged;
#   3. reads small checkouts of its own: a pom naming a repository, beside the person's settings.xml with a mirror
#      of everything and a server holding a password; a pom with .mvn/extensions.xml; a pom with an external entity
#      and one with nested entities;
#   4. starts a task twice in the project default from a new checkout at a terminal: the first asks whether to
#      write porelo.yml, and after a no the second asks again;
#   5. removes everything it made and lists what held and what did not.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l project-proposed.sh --test-account --checkout DIR --adapter-deb FILE --adapter-sha256 HEX
#         --parent-pom FILE [--expect-fail ISSUES]
# DIR is a Maven checkout whose own porelo.yml says its tools; FILE for --parent-pom is the parent pom that
# checkout names, put into the local Maven repository as an install would put it. --expect-fail names the
# issues not yet in Review, comma-separated ('none' for none); default none.
# Exit 0 when nothing failed unexpectedly, 1 otherwise, 64 for wrong use.

set -u -o pipefail

EXPECTED=""
TEST_ACCOUNT=no
SOURCE=""
DEB=""
DEB_SHA256=""
PARENT_POM=""
usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}
wrong() {
    echo "project-proposed: $*" >&2
    usage >&2
    exit 64
}
while [ $# -gt 0 ]; do
    case "$1" in
    --expect-fail | --checkout | --adapter-deb | --adapter-sha256 | --parent-pom)
        [ $# -ge 2 ] || wrong "$1 needs a value"
        case "$1" in
        --expect-fail) EXPECTED="$2" ;;
        --checkout) SOURCE="$2" ;;
        --adapter-deb) DEB="$2" ;;
        --adapter-sha256) DEB_SHA256="$2" ;;
        --parent-pom) PARENT_POM="$2" ;;
        esac
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
    *) wrong "unknown argument: $1" ;;
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
# What the check prints (jq -e prints its verdict) is not the answer: only its status is.
yes_if() { if "$@" >/dev/null; then echo 0; else echo 1; fi; }
# holds <check> -- <check>: 0 when both hold, as one verdict
holds() {
    local first=()
    while [ "$1" != -- ]; do
        first+=("$1")
        shift
    done
    shift
    if "${first[@]}" >/dev/null && "$@" >/dev/null; then echo 0; else echo 1; fi
}

if [ "$(id -u)" -eq 0 ]; then
    echo "project-proposed: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "project-proposed: refused: this installs an adapter, writes ~/.m2 and starts tasks in the account it runs" >&2
    echo "      in; run it only in a test account on the test machine, and say so with --test-account" >&2
    exit 64
fi
[ -d "$SOURCE" ] && [ -f "$SOURCE/pom.xml" ] && [ -f "$SOURCE/porelo.yml" ] ||
    wrong "--checkout must name a Maven checkout with its own porelo.yml"
[ -f "$DEB" ] || wrong "--adapter-deb must name the adapter's package"
[[ $DEB_SHA256 =~ ^[0-9a-f]{64}$ ]] || wrong "--adapter-sha256 must be 64 lower-case hex digits"
[ -f "$PARENT_POM" ] || wrong "--parent-pom must name a pom file"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git jq timeout curl script dpkg-deb sha256sum python3; do
    command -v "$tool" >/dev/null || {
        echo "project-proposed: refused: '$tool' is not installed" >&2
        exit 64
    }
done
M2="$HOME/.m2"
if [ -e "$M2" ]; then
    echo "project-proposed: refused: $M2 is there; this run writes the person's Maven settings and local" >&2
    echo "      repository itself and removes them after, so it runs only where there are none" >&2
    exit 64
fi
ADAPTERS="${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems"
ADAPTER="$ADAPTERS/maven"
if [ -e "$ADAPTER" ]; then
    echo "project-proposed: refused: $ADAPTER is there already; this run installs and removes its own" >&2
    exit 64
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/project-proposed.XXXXXX") || exit 70
chmod 700 "$WORK"
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
CONTAINERS=()
DEFAULT_ENTRIES=()
VAULT_MADE=no
CLEANED=no
DEFAULT_IMAGE_BEFORE=no
podman image exists localhost/porelo/default 2>/dev/null && DEFAULT_IMAGE_BEFORE=yes

cleanup() {
    [ "$CLEANED" = yes ] && return 0
    CLEANED=yes
    printf '\n== cleaning up what this run made\n'
    local container entry
    for container in "${CONTAINERS[@]}"; do
        if podman container exists "$container" 2>/dev/null; then
            timeout 300 porelo task remove "$container" --force --no-input >/dev/null 2>&1
            podman container exists "$container" 2>/dev/null &&
                podman rm -f "$container" >/dev/null 2>&1
            read_ "task $container removed"
        fi
    done
    for entry in "${DEFAULT_ENTRIES[@]}"; do
        timeout 120 porelo project default remove "$entry" >/dev/null 2>&1 &&
            read_ "'$entry' removed from default"
        # The starts in default build default's image; it goes unless it was there before the run.
        if [ "$DEFAULT_IMAGE_BEFORE" = no ] && podman image exists localhost/porelo/default 2>/dev/null; then
            podman rmi -f localhost/porelo/default >/dev/null 2>&1 && read_ "default's image, made by this run, removed"
        fi
    done
    if [ "$VAULT_MADE" = yes ]; then
        porelo vault lock >/dev/null 2>&1
        read_ "the run's vault locked; it goes with the run's folder"
    fi
    rm -f -- "$ADAPTER"
    rmdir -- "$ADAPTERS" 2>/dev/null
    rm -rf -- "${M2:?}"
    read_ "the adapter and ~/.m2 removed"
    rm -rf -- "${WORK:?}"
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

# init <answer file> <checkout> [online]: porelo project init --json, offline unless 'online' is given.
init() {
    local out=$1 checkout=$2 online=${3:-}
    if [ "$online" = online ]; then
        timeout 300 porelo project init "$checkout" --json >"$out" 2>"$out.err"
    else
        # Every lookup goes through the person's proxy, as porelo hands it to the adapter, and this one answers
        # nothing: offline for every vendor asked. A namespace without a network is not used: an unprivileged one
        # is refused on Ubuntu, and the adapter fails to start inside podman's.
        timeout 300 env -u no_proxy -u NO_PROXY HTTPS_PROXY="$DEAD_PROXY" https_proxy="$DEAD_PROXY" \
            HTTP_PROXY="$DEAD_PROXY" http_proxy="$DEAD_PROXY" \
            porelo project init "$checkout" --json >"$out" 2>"$out.err"
    fi
}
# answered <answer file>: project init gave an answer with a draft, so a check of what it lacks means something
answered() { [ -s "$1" ] && jq -e '.draft | type == "string"' "$1" >/dev/null 2>&1; }
# a commit of everything in a checkout, so a start takes it and the tracked files can be compared
commit_all() {
    git -C "$1" add -A && git -C "$1" -c user.name=Story -c user.email=story@example.invalid \
        -c commit.gpgsign=false commit -q -m "$2"
}
tracked() { git -C "$1" ls-files -s | sha256sum | cut -d' ' -f1; }

# ---------------------------------------------------------------- the adapter as its package installs it

step "Install the Maven adapter account-locally from its package"
got=$(sha256sum "$DEB" | cut -d' ' -f1)
read_ "the package's sha256: $got"
if [ "$got" != "$DEB_SHA256" ]; then
    point 1 "" "the adapter's package is the one named"
    finish
fi
mkdir -p "$WORK/deb" "$ADAPTERS"
if ! dpkg-deb -x "$DEB" "$WORK/deb" || ! install -m 0755 "$WORK/deb/usr/libexec/porelo/ecosystems/maven" "$ADAPTER"; then
    point 1 "" "the adapter's package unpacks and installs"
    finish
fi
timeout 60 porelo ecosystems --json >"$WORK/ecosystems.out" 2>"$WORK/ecosystems.err"
cat "$WORK/ecosystems.out" "$WORK/ecosystems.err" | shown | head -n 6
point "$(yes_if jq -e --arg p "$ADAPTER" '[.. | objects | select(.name? == "maven" and .path? == $p and .refusal? == null)] | length == 1' \
    "$WORK/ecosystems.out")" "" "porelo finds the Maven adapter installed for this account, and usable"

step "Check that the proxy the offline reads go through answers nothing"
DEAD_PROXY="http://127.0.0.1:9"
if timeout 10 curl -s -o /dev/null --proxy "$DEAD_PROXY" https://repo.maven.apache.org/maven2/; then
    point 1 "" "a lookup through $DEAD_PROXY reaches nothing"
else
    point 0 "" "a lookup through $DEAD_PROXY reaches nothing"
fi
[ "${#FAILED[@]}" -eq 0 ] || finish

# ---------------------------------------------------------------- a real checkout, its porelo.yml set aside

step "Copy the real checkout with its porelo.yml set aside"
REAL="$WORK/real"
git clone -q --no-hardlinks "$SOURCE" "$REAL" && git -C "$REAL" rm -q porelo.yml && commit_all "$REAL" "set aside"
cp "$SOURCE/porelo.yml" "$WORK/porelo.yml.set-aside"
grep -v '^ *#' "$WORK/porelo.yml.set-aside" | shown | head -n 12
BEFORE=$(tracked "$REAL")
PARENT=$(sed -n '/<parent>/,/<\/parent>/p' "$REAL/pom.xml" | sed -n 's:.*<artifactId>\(.*\)</artifactId>.*:\1:p' | head -1)
read_ "the checkout's parent: ${PARENT:-none}"
[ -n "$PARENT" ] || wrong "--checkout must name a checkout whose pom names a parent"

step "Read it offline, its parent pom missing from the local Maven repository"
init "$WORK/missing.json" "$REAL"
code=$?
read_ "project init: exit $code"
shown <"$WORK/missing.json.err" | tail -n 5
jq -r '.values[]? | "\(.key) = \(.value)  \(.file):\(.line)  \(.origin)\(if .unknown then "  unknown: " + .note else "" end)"' \
    "$WORK/missing.json" 2>/dev/null | shown | head -n 30
point "$(yes_if test "$code" = 0)" "" "a checkout whose parent is not in the local repository is still read, offline"
point "$(yes_if jq -e --arg p "$PARENT" '[.values[]? | select(.unknown and (((.note // "") + (.value | tostring)) | contains($p)))] | length > 0' \
    "$WORK/missing.json")" "" "a value its missing parent sets is named unknown, naming the parent"

step "Put the parent pom into the local Maven repository, as an install puts it, and read the checkout offline again"
coordinates=$(python3 -I -c '
import sys, xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
ns = {"m": root.tag[1:].split("}")[0]} if root.tag.startswith("{") else {}
get = lambda name: root.find(("m:" if ns else "") + name, ns).text.strip()
print(get("groupId").replace(".", "/"), get("artifactId"), get("version"))
' "$PARENT_POM")
read -r group artifact version <<<"$coordinates"
mkdir -p "$M2/repository/$group/$artifact/$version"
cp "$PARENT_POM" "$M2/repository/$group/$artifact/$version/$artifact-$version.pom"
read_ "put as $group/$artifact/$version"
init "$WORK/offline.json" "$REAL"
code=$?
read_ "project init: exit $code"
jq -r '.values[]? | "\(.key) = \(.value)  \(.file):\(.line)  \(.origin)\(if .unknown then "  unknown: " + .note else "" end)"' \
    "$WORK/offline.json" 2>/dev/null | shown | head -n 30
point "$(yes_if jq -e '[.values[]? | select((.unknown | not) and (.key | test("java|jdk"; "i")) and (.value | tostring | test("(^|[^0-9.])25([^0-9]|$)")))] | length > 0' \
    "$WORK/offline.json")" "" "with the parent there, the Java release 25 the parent sets is named, offline"
# A value's file and line, or the machine it was read on, as the online read names repository.central with ':0'.
point "$(yes_if jq -e '(.values | length) > 0 and ([.values[] | select((.unknown | not) and ((.file // "") == "") and .origin != "MACHINE")] | length == 0)' \
    "$WORK/offline.json")" "" "every value found names the file it came from"
point "$(yes_if jq -e '[.values[]? | select(.unknown)] | length > 0' "$WORK/offline.json")" "" \
    "offline, a version only a vendor can tell is named unknown, never guessed"
if grep -q -E '[0-9a-f]{64}' "$WORK/offline.json"; then offline_digest=yes; else offline_digest=no; fi
read_ "a digest in the offline answer: $offline_digest"
# This checkout's own files pin no download; a digest offline could only be a guess.
point "$(holds answered "$WORK/offline.json" -- test "$offline_digest" = no)" "" \
    "offline, no download is pinned by a digest nobody could read"

step "Read it with the network"
init "$WORK/online.json" "$REAL" online
code=$?
read_ "project init: exit $code"
jq -r '.values[]? | "\(.key) = \(.value)  \(.file):\(.line)  \(.origin)\(if .unknown then "  unknown: " + .note else "" end)"' \
    "$WORK/online.json" 2>/dev/null | shown | head -n 30
point "$(yes_if test "$code" = 0)" "" "the checkout is read with the network"
# The digests the set-aside file pins: each must be the one the draft proposes, or the difference is listed.
jq -r '.draft // "", (.values[]? | .value | tostring)' "$WORK/online.json" >"$WORK/proposed.txt" 2>/dev/null
# The tools' digests: the base image is the project's choice, which the draft takes from porelo, not a tool.
read_ "the set-aside base image: $(grep -m1 -E '^ *from:' "$WORK/porelo.yml.set-aside" | sed 's/^ *//'); the draft's: $(jq -r '.draft' "$WORK/online.json" | grep -m1 -E '^ *from:' | sed 's/^ *//')"
pinned=$(grep -v -E '^ *#|^ *from:' "$WORK/porelo.yml.set-aside" | grep -o -E '\b[0-9a-f]{64}\b' | sort -u)
missing=()
for digest in $pinned; do
    grep -q "$digest" "$WORK/proposed.txt" || missing+=("$digest")
done
read_ "digests the set-aside porelo.yml pins: $(printf '%s\n' "$pinned" | grep -c .); not in the draft or its values: ${missing[*]:-none}"
point "$(yes_if test -n "$pinned" -a "${#missing[@]}" = 0)" "" "with the network, the draft pins what the set-aside porelo.yml pins, by the same digests"
point "$(yes_if test "$(tracked "$REAL")" = "$BEFORE" -a -z "$(git -C "$REAL" status --porcelain --ignored)")" "" \
    "the checkout's tracked files are unchanged and nothing is added to it"

# ---------------------------------------------------------------- rules: whose host is whose, no password

step "Write the person's settings.xml: a mirror of everything and a server holding a password"
PASSWORD="story-$RUN_ID-not-a-real-password"
cat >"$M2/settings.xml" <<SETTINGS
<settings>
  <mirrors>
    <mirror>
      <id>corp</id>
      <mirrorOf>*</mirrorOf>
      <url>https://nexus.corp.example/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <servers>
    <server>
      <id>corp</id>
      <username>story</username>
      <password>$PASSWORD</password>
    </server>
  </servers>
</settings>
SETTINGS
RULES="$WORK/rules"
mkdir -p "$RULES"
git init -q -b main "$RULES"
cat >"$RULES/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example.story</groupId>
  <artifactId>rules</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
  </properties>
  <repositories>
    <repository>
      <id>example</id>
      <url>https://repo.example.org/maven/</url>
    </repository>
  </repositories>
</project>
POM
commit_all "$RULES" initial
init "$WORK/rules.json" "$RULES"
code=$?
read_ "project init: exit $code"
jq -r '.draft // empty' "$WORK/rules.json" | shown | head -n 30
jq -r '.values[]? | "\(.key) = \(.value)  \(.file):\(.line)  \(.origin)"' "$WORK/rules.json" | shown | head -n 20
corp=$(jq -r '[.values[]? | select((.value | tostring) | contains("nexus.corp.example")) | .origin] | unique | join(",")' "$WORK/rules.json")
example=$(jq -r '[.values[]? | select((.value | tostring) | contains("repo.example.org")) | .origin] | unique | join(",")' "$WORK/rules.json")
read_ "nexus.corp.example from: ${corp:-nowhere}; repo.example.org from: ${example:-nowhere}"
point "$(yes_if test "$corp" = YOURS)" "" "the mirror's host is proposed as the person's own (YOURS), from their settings.xml"
point "$(holds answered "$WORK/rules.json" -- test -z "$example")" "" \
    "the pom's repository, which the mirror of everything stands in for, is not proposed beside it"
point "$(holds answered "$WORK/rules.json" -- test -z "$(jq -r '.draft' "$WORK/rules.json" | grep -o 'repo.maven.apache.org\|repo1.maven.org')")" "" \
    "a mirror of everything leaves Maven Central out of the draft"
point "$(yes_if jq -e '[.credentials_needed[]? | select(.id == "corp" and ((.command // "") | contains("vault")))] | length > 0' "$WORK/rules.json")" "" \
    "the server's credential is named by its id, with the command that stores it in the vault"
leaked=no
printf 'n\nn\nn\n' | timeout 300 script -q -e -c "porelo project init $RULES" "$WORK/rules-terminal.out" >/dev/null 2>&1
read_ "project init at a terminal, every offer declined: exit $?"
tr -d '\r' <"$WORK/rules-terminal.out" | grep -i -E 'corp|vault' | head -n 4 | shown
for place in "$WORK" "$RULES" "${XDG_CONFIG_HOME:-$HOME/.config}/porelo" "${XDG_DATA_HOME:-$HOME/.local/share}/porelo" \
    "${XDG_STATE_HOME:-$HOME/.local/state}/porelo" "${XDG_CACHE_HOME:-$HOME/.cache}"; do
    [ -e "$place" ] && grep -r -q -F "$PASSWORD" "$place" 2>/dev/null && leaked="yes, under $place"
done
read_ "the password found in an answer, an error, the terminal, the checkout or porelo's own files: $leaked"
point "$(holds answered "$WORK/rules.json" -- test "$leaked" = no)" "" \
    "the server's password appears in no answer, no error and no file of the checkout"
rm -f -- "$M2/settings.xml"

step "Read the same checkout without the person's settings.xml"
init "$WORK/own.json" "$RULES"
read_ "project init: exit $?"
own=$(jq -r '[.values[]? | select((.value | tostring) | contains("repo.example.org")) | "\(.origin) \(.file):\(.line)"] | unique | join(", ")' \
    "$WORK/own.json")
read_ "repo.example.org from: ${own:-nowhere}"
point "$(yes_if jq -e '[.values[]? | select(((.value | tostring) | contains("repo.example.org")) and .origin == "CHECKOUT" and .file == "pom.xml" and .line > 0)] | length > 0' \
    "$WORK/own.json")" "" "the pom's repository is proposed as the checkout's, with its file and line"

# ---------------------------------------------------------------- what is never done

step "Read a checkout with .mvn/extensions.xml naming an extension in no local repository"
EXT="$WORK/extensions"
mkdir -p "$EXT/.mvn"
git init -q -b main "$EXT"
sed 's/<artifactId>rules</<artifactId>extensions</' "$RULES/pom.xml" >"$EXT/pom.xml"
cat >"$EXT/.mvn/extensions.xml" <<'EXTENSIONS'
<extensions>
  <extension>
    <groupId>org.example.story</groupId>
    <artifactId>never-there</artifactId>
    <version>1</version>
  </extension>
</extensions>
EXTENSIONS
commit_all "$EXT" initial
init "$WORK/extensions.json" "$EXT"
code=$?
read_ "project init: exit $code"
jq -r '.values[]? | select((.file // "") | contains("extensions.xml")) | "\(.key) = \(.value)  \(.file):\(.line)  \(.note)"' \
    "$WORK/extensions.json" 2>/dev/null | shown
# That its code is never loaded is the adapter's own unit tests' to prove; here, an extension nobody could load
# leaves the read whole, offline.
point "$(holds test "$code" = 0 -- answered "$WORK/extensions.json")" "" \
    "a checkout with an extension no repository holds is still read, offline"
point "$(yes_if jq -e '[.values[]?, .not_placed[]?, .unplaced[]? | select(((.file // "") | tostring) | contains(".mvn/extensions.xml"))] | length > 0' \
    "$WORK/extensions.json")" "" "the answer names .mvn/extensions.xml as a finding of its own"

step "Read a pom with an external entity naming a local file, and one with nested entities"
SECRET_FILE="$WORK/a-local-file"
printf 'story-%s-local-content\n' "$RUN_ID" >"$SECRET_FILE"
XXE="$WORK/xxe"
mkdir -p "$XXE"
git init -q -b main "$XXE"
cat >"$XXE/pom.xml" <<POM
<?xml version="1.0"?>
<!DOCTYPE project [ <!ENTITY local SYSTEM "file://$SECRET_FILE"> ]>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example.story</groupId>
  <artifactId>xxe</artifactId>
  <version>1</version>
  <properties><maven.compiler.release>&local;</maven.compiler.release></properties>
</project>
POM
commit_all "$XXE" initial
init "$WORK/xxe.json" "$XXE"
code=$?
read_ "project init: exit $code; answer of $(wc -c <"$WORK/xxe.json") bytes"
cat "$WORK/xxe.json.err" | tail -n 3 | shown
# Read into the Java release it would be reported; an answer must come, or a refusal said, for the check to count.
entity_said() { grep -q -i -E 'entit|doctype|dtd' "$1"; }
xxe_answered() { answered "$WORK/xxe.json" || entity_said "$WORK/xxe.json.err"; }
point "$(holds xxe_answered -- test -z "$(grep -h -F "story-$RUN_ID-local-content" "$WORK/xxe.json" "$WORK/xxe.json.err")")" "" \
    "an external entity's file is not read into the answer"
NEST="$WORK/nested"
mkdir -p "$NEST"
git init -q -b main "$NEST"
{
    printf '<?xml version="1.0"?>\n<!DOCTYPE project [\n  <!ENTITY a "aaaaaaaaaa">\n'
    levels=(a b c d e f g h i)
    for index in 1 2 3 4 5 6 7 8; do
        prev=${levels[index - 1]}
        printf '  <!ENTITY %s "' "${levels[index]}"
        for _ in 1 2 3 4 5 6 7 8 9 10; do printf '&%s;' "$prev"; done
        printf '">\n'
    done
    printf ']>\n<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    printf '<groupId>org.example.story</groupId><artifactId>nested</artifactId><version>1</version>'
    printf '<description>&i;</description></project>\n'
} >"$NEST/pom.xml"
commit_all "$NEST" initial
init "$WORK/nested.json" "$NEST"
code=$?
read_ "project init: exit $code; answer of $(wc -c <"$WORK/nested.json") bytes"
jq -c '.plugins, .notes, (.unplaced[]?, .not_placed[]? | {key, file, note})' "$WORK/nested.json" 2>/dev/null | cut -c1-220 | head -n 8 | shown
tail -n 3 "$WORK/nested.json.err" | shown
# The adapter refuses any DTD, which holds the entities: the refusal names it in the pom's note.
point "$(holds test "$(wc -c <"$WORK/nested.json")" -lt 1000000 -- jq -e '[.unplaced[]?, .not_placed[]? | select(.file == "pom.xml" and ((.note // "") | test("refused.*(DTD|entit)"; "i")))] | length > 0' \
    "$WORK/nested.json")" "" "nested entities are refused by name, never expanded"

# ---------------------------------------------------------------- the first start in default asks once

step "Start a task twice in default from a new checkout, at a terminal"
FIRST="$WORK/first-$RUN_ID"
mkdir -p "$FIRST"
git init -q -b main "$FIRST"
sed 's/<artifactId>rules</<artifactId>first</' "$RULES/pom.xml" | sed '/<repositories>/,/<\/repositories>/d' >"$FIRST/pom.xml"
commit_all "$FIRST" initial
DEFAULT_ENTRIES+=("first-$RUN_ID")
# The stub agent asks for a credential at a start: a vault of this run's own holds a stand-in for it.
export PORELO_VAULT="$WORK/vault/vault.bin"
mkdir -m 700 "$WORK/vault"
passphrase=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')
VAULT_MADE=yes
{
    printf '%s\n' "$passphrase" | porelo vault init --passphrase-stdin
    printf '%s\n' "$passphrase" | porelo vault unlock --passphrase-stdin
    printf '%s' 'sk-porelo-story-not-a-real-key' | porelo vault put anthropic --type api-key
} 2>&1 | shown
unset passphrase
started=()
for run in 1 2; do
    CONTAINERS+=("porelo-default_one-$run-$RUN_ID")
    # In the checkout, as a person starts there; at a terminal, answering no.
    (cd "$FIRST" && printf 'n\n' | timeout 900 script -q -e -c \
        "porelo task start one-$run-$RUN_ID --agent stub --detach --clearance deny" \
        "$WORK/start-$run.out" >/dev/null 2>&1)
    code=$?
    made=no
    podman container exists "porelo-default_one-$run-$RUN_ID" 2>/dev/null && made=yes
    started+=("$code/$made")
    read_ "start $run: exit $code; its container made: $made"
    tr -d '\r' <"$WORK/start-$run.out" | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' |
        grep -i -E 'tool|propos|draft|adapter|ecosystem|maven|project\.yml|default|\[y/N\]' | head -n 12 | shown
done
point "$(yes_if test "${started[0]}" = 0/yes -a "${started[1]}" = 0/yes)" "" "both starts in default start their task"
# Either question a first start may ask about what the adapters found: in the checkout, or of a repository of default.
# The question whether to write porelo.yml from what the adapters found is asked at every start until a yes.
QUESTION='Write porelo\.yml here.*\?'
asked_first=$(tr -d '\r' <"$WORK/start-1.out" | grep -c -E "$QUESTION")
asked_second=$(tr -d '\r' <"$WORK/start-2.out" | grep -c -E "$QUESTION")
read_ "asked whether to write porelo.yml: first start $asked_first, second start $asked_second"
point "$(yes_if test "$asked_first" = 1)" "" "the first start in default asks once whether to write porelo.yml"
point "$(yes_if test "${started[1]}" = 0/yes -a "$asked_second" = 1)" "" \
    "after a no, the next start asks again, once"
point "$(yes_if test ! -e "$FIRST/.porelo.yml" -a ! -e "$FIRST/porelo.yml")" "" "a no writes neither porelo.yml nor .porelo.yml"

finish
