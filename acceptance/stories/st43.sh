#!/usr/bin/env bash
# ST43, a company's private package repository with no credential in the task, played at the command
# line as a person would (B684).
#
# What it does, against the porelo installed in this account:
#   1. stands up a Maven repository on this machine that demands Basic auth over TLS and serves one jar,
#      with a test authority of its own added to the account's ca-certificates.pem (no root needed);
#   2. stores the repository's credential with 'porelo vault put --type basic', in a vault of the run's own;
#   3. declares the repository in a project's tool_repositories with that credential;
#   4. starts a task; reads the settings the Maven adapter wrote and the broker's address;
#   5. fetches the jar with curl and builds a Maven project against it, both inside the task;
#   6. searches the task's environment, processes, files and mounts, and its helpers on the host,
#      for the credential, and finds none;
#   7. removes everything it made and lists what held and what did not.
#
# The credential is a test string made at the start of the run: written to owner-only files, handed on
# by standard input, never on a command line and never printed.
#
# Run it as the test account on the test machine, never as a person's own account:
#     bash -l st43.sh --test-account [--expect-fail ISSUES]
# --expect-fail names the issues not yet in Review, comma-separated (default none); a point waiting on
# one of them is listed as an expected failure. Exit 0 when nothing failed unexpectedly, 1 otherwise,
# 64 for wrong use.

# The scripts sent into the task are single-quoted on purpose: they expand there, not here.
# shellcheck disable=SC2016
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
        echo "st43: unknown argument: $1" >&2
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
    echo "st43: refused: run as the unprivileged test account, not as root" >&2
    exit 64
fi
if [ "$TEST_ACCOUNT" != yes ]; then
    echo "st43: refused: this makes and removes tasks, a project, a vault, a server and an authority in" >&2
    echo "      the account it runs in; run it only in a test account on the test machine, and say so" >&2
    echo "      with --test-account" >&2
    exit 64
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
for tool in porelo podman git timeout openssl python3 curl base64 sha256sum awk; do
    command -v "$tool" >/dev/null || {
        echo "st43: refused: '$tool' is not installed" >&2
        exit 64
    }
done

umask 077
WORK=$(mktemp -d "${TMPDIR:-/tmp}/st43.XXXXXX") || exit 70
RUN_ID=$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')
PROJECT="st43-$RUN_ID"
ENTRY="repository/st43-$RUN_ID"
PROJECTS_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects"
AUTHORITIES="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/ca-certificates.pem"
AUTHORITIES_KEPT=""
AUTHORITIES_TOUCHED=no
CONTAINERS=()
MADE_PROJECTS=()
VAULT_MADE=no
SERVER_PID=""
CLEANED=no

# The run's credential: a user and a password of 24 characters, made here, never a real one.
CRED_USER="st43-reader"
CRED_PASS=$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
printf '%s:%s' "$CRED_USER" "$CRED_PASS" >"$WORK/credential"
# What a search looks for: the password, the pair, and the pair as a Basic header carries it.
{
    printf '%s\n' "$CRED_PASS"
    printf '%s:%s\n' "$CRED_USER" "$CRED_PASS"
    base64 -w0 <"$WORK/credential"
    printf '\n'
} >"$WORK/needles"

# Every output this script shows passes through here, so the run's credential is never printed even
# where it would be a finding: it is replaced by [credential], and the search points name the finding.
redact() {
    awk -v needles="$WORK/needles" '
        BEGIN { while ((getline line < needles) > 0) if (line != "") n[++k] = line }
        {
            for (i = 1; i <= k; i++) {
                out = ""; rest = $0
                while ((at = index(rest, n[i])) > 0) {
                    out = out substr(rest, 1, at - 1) "[credential]"
                    rest = substr(rest, at + length(n[i]))
                }
                $0 = out rest
            }
            print "   | " $0
        }'
}
# count <stream>: how many lines carry any form of the credential; the lines themselves are never shown.
count() { grep -a -c -F -f "$WORK/needles"; }

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
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
        read_ "the stand-in repository stopped"
    fi
    if [ "$AUTHORITIES_TOUCHED" = yes ]; then
        if [ -n "$AUTHORITIES_KEPT" ]; then
            cat "$AUTHORITIES_KEPT" >"$AUTHORITIES"
            read_ "the account's ca-certificates.pem put back as it was"
        else
            rm -f -- "$AUTHORITIES"
            read_ "the ca-certificates.pem this run made removed"
        fi
    fi
    if [ "$VAULT_MADE" = yes ]; then
        porelo vault remove "$ENTRY" --without-revoking >/dev/null 2>&1
        porelo vault lock >/dev/null 2>&1
        read_ "the run's vault emptied, locked and removed"
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
# in_task <script>: runs it in the task as its user; in_task_reading also hands it this shell's input.
in_task() { timeout 900 podman exec "$CONTAINER" sh -c "$1" </dev/null; }
in_task_reading() { timeout 900 podman exec -i "$CONTAINER" sh -c "$1"; }

# ---------------------------------------------------------------- the stand-in repository

step "Make a test authority and a certificate for 127.0.0.1 it signs"
mkdir "$WORK/tls"
{
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout "$WORK/tls/ca.key" -out "$WORK/tls/ca.pem" \
        -subj "/CN=Porelo story ST43 test authority" &&
        openssl req -newkey rsa:2048 -nodes -keyout "$WORK/tls/server.key" -out "$WORK/tls/server.csr" \
            -subj "/CN=127.0.0.1" &&
        printf '%s\n' 'subjectAltName=IP:127.0.0.1,DNS:localhost' 'basicConstraints=CA:FALSE' \
            'keyUsage=digitalSignature,keyEncipherment' 'extendedKeyUsage=serverAuth' >"$WORK/tls/ext.cnf" &&
        openssl x509 -req -in "$WORK/tls/server.csr" -CA "$WORK/tls/ca.pem" -CAkey "$WORK/tls/ca.key" \
            -CAcreateserial -days 2 -extfile "$WORK/tls/ext.cnf" -out "$WORK/tls/server.pem"
} >"$WORK/tls/openssl.log" 2>&1
code=$?
read_ "openssl exit $code; $(openssl x509 -in "$WORK/tls/server.pem" -noout -subject -ext subjectAltName 2>/dev/null | tr '\n' ' ')"
point "$code" "" "a test authority and a server certificate are made"
[ "$code" = 0 ] || finish

step "Lay out a Maven repository with one jar, org.example.story:story-lib:1.0"
python3 -I - "$WORK/repo" <<'PY'
import hashlib, pathlib, sys, zipfile
base = pathlib.Path(sys.argv[1]) / "repository/story/org/example/story/story-lib/1.0"
base.mkdir(parents=True)
jar = base / "story-lib-1.0.jar"
with zipfile.ZipFile(jar, "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nCreated-By: porelo story ST43\r\n\r\n")
    z.writestr("story/greeting.txt", "read through the repository broker\n")
(base / "story-lib-1.0.pom").write_text(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
    '  <modelVersion>4.0.0</modelVersion>\n'
    '  <groupId>org.example.story</groupId>\n'
    '  <artifactId>story-lib</artifactId>\n'
    '  <version>1.0</version>\n'
    '</project>\n')
for each in list(base.iterdir()):
    for algorithm in ("sha1", "md5"):
        (base / (each.name + "." + algorithm)).write_text(hashlib.new(algorithm, each.read_bytes()).hexdigest())
PY
JAR="$WORK/repo/repository/story/org/example/story/story-lib/1.0/story-lib-1.0.jar"
JAR_SHA=$(sha256sum <"$JAR" | cut -d' ' -f1)
read_ "story-lib-1.0.jar sha256 $JAR_SHA"
point "$([ -s "$JAR" ] && echo 0 || echo 1)" "" "the stand-in repository holds one jar"

step "Serve it over TLS on 127.0.0.1, answering only the run's Basic credential"
cat >"$WORK/standin.py" <<'PY'
import base64, hmac, http.server, ssl, sys
root, cert, key, credential, portfile, log = sys.argv[1:7]
with open(credential, "rb") as f:
    expected = b"Basic " + base64.b64encode(f.read())

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=root, **kwargs)

    def note(self, verdict):
        # Method, path and verdict only: never a header.
        with open(log, "a") as f:
            f.write("%s %s %s\n" % (self.command, self.path.split("?")[0], verdict))

    def allowed(self):
        given = self.headers.get("Authorization", "").encode()
        if hmac.compare_digest(given, expected):
            self.note("auth-ok")
            return True
        self.note("auth-refused")
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="story"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def do_GET(self):
        if self.allowed():
            super().do_GET()

    def do_HEAD(self):
        if self.allowed():
            super().do_HEAD()

    def refuse(self):
        self.note("method-refused")
        self.send_response(405)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_PUT = do_POST = do_DELETE = refuse

    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
server.socket = context.wrap_socket(server.socket, server_side=True)
with open(portfile, "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
PY
python3 -I "$WORK/standin.py" "$WORK/repo" "$WORK/tls/server.pem" "$WORK/tls/server.key" \
    "$WORK/credential" "$WORK/port" "$WORK/standin.log" >"$WORK/standin.out" 2>&1 &
SERVER_PID=$!
for _ in $(seq 50); do
    [ -s "$WORK/port" ] && break
    sleep 0.2
done
PORT=$(cat "$WORK/port" 2>/dev/null)
PREFIX="https://127.0.0.1:${PORT:-0}/repository/story/"
JAR_PATH="org/example/story/story-lib/1.0/story-lib-1.0.jar"
read_ "listening at $PREFIX"
# curl's user comes from a file of its own, so the pair is on no command line.
printf 'user = "%s"\n' "$(cat "$WORK/credential")" >"$WORK/curl-with-credential"
without=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$WORK/tls/ca.pem" "$PREFIX$JAR_PATH")
with=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$WORK/tls/ca.pem" -K "$WORK/curl-with-credential" \
    "$PREFIX$JAR_PATH")
read_ "without the credential: $without; with it: $with"
point "$([ "$without" = 401 ] && [ "$with" = 200 ] && echo 0 || echo 1)" "" \
    "the stand-in demands Basic auth over TLS and serves the jar to its credential"
[ "$with" = 200 ] || {
    redact <"$WORK/standin.out"
    finish
}

step "Add the test authority to the account's ca-certificates.pem, which Porelo's clients on the host trust"
mkdir -p "$(dirname "$AUTHORITIES")"
if [ -e "$AUTHORITIES" ]; then
    AUTHORITIES_KEPT="$WORK/ca-certificates.pem.kept"
    cp -p -- "$AUTHORITIES" "$AUTHORITIES_KEPT"
    read_ "the account has a ca-certificates.pem; kept a copy to put back"
fi
AUTHORITIES_TOUCHED=yes
cat "$WORK/tls/ca.pem" >>"$AUTHORITIES"
chmod 600 "$AUTHORITIES"
read_ "$(grep -c 'BEGIN CERTIFICATE' "$AUTHORITIES") certificate(s) in $AUTHORITIES"
point 0 "" "the stand-in's authority is added without root"

# ---------------------------------------------------------------- the credential and the project

step "Make a vault of this run's own and store the repository's credential with 'porelo vault put'"
export PORELO_VAULT="$WORK/vault/vault.bin"
mkdir "$WORK/vault"
passphrase=$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')
VAULT_MADE=yes
{
    printf '%s\n' "$passphrase" | porelo vault init --passphrase-stdin
    printf '%s\n' "$passphrase" | porelo vault unlock --passphrase-stdin
    printf '%s' 'sk-porelo-story-not-a-real-key' | porelo vault put anthropic --type api-key
} >"$WORK/vault.out" 2>&1
unset passphrase
porelo vault put "$ENTRY" --type basic <"$WORK/credential" >>"$WORK/vault.out" 2>&1
code=$?
porelo vault list >>"$WORK/vault.out" 2>&1
redact <"$WORK/vault.out"
read_ "vault put exit $code; $(count <"$WORK/vault.out") line(s) of what the vault said carry the credential"
point "$([ "$code" = 0 ] && porelo vault list 2>/dev/null | grep -q -F -- "$ENTRY" && echo 0 || echo 1)" "" \
    "the credential is stored in the vault as $ENTRY, of type basic"
point "$([ "$(count <"$WORK/vault.out")" = 0 ] && echo 0 || echo 1)" "" \
    "storing and listing the credential never prints it"

step "Find the Maven adapter the project names"
porelo ecosystems --json >"$WORK/ecosystems.json" 2>&1
ECOSYSTEM=$(python3 -I -c '
import json, sys
try:
    found = json.load(open(sys.argv[1]))
except Exception:
    found = []
if isinstance(found, dict):
    found = found.get("ecosystems", [])
names = [e.get("name") for e in found if isinstance(e, dict) and e.get("refusal") is None]
print("maven" if "maven" in names else ("stub-ecosystem" if "stub-ecosystem" in names else ""))
' "$WORK/ecosystems.json")
redact <"$WORK/ecosystems.json" | head -n 20
read_ "the project names ecosystem '${ECOSYSTEM:-none}'"
point "$([ "$ECOSYSTEM" = maven ] && echo 0 || echo 1)" "" \
    "the Maven ecosystem adapter (porelo-ecosystems' EC16) is installed and usable"
[ -n "$ECOSYSTEM" ] || finish

step "Make a guarded project '$PROJECT' that declares the repository with its credential"
if make_project "$PROJECT" guarded "image:
  from: \"ubuntu:24.04\"
  run: |
    RUN apt-get update && apt-get install -y --no-install-recommends maven openjdk-21-jdk-headless \\
        && rm -rf /var/lib/apt/lists/*
egress:
  sets: [maven]
tool_repositories:
  - ecosystem: $ECOSYSTEM
    name: corp
    url: \"$PREFIX\"
    credential: $ENTRY"; then
    redact <"$HOME/$PROJECT/project.yml"
    point 0 "" "the project declares the repository by name, URL and vault entry, and holds no credential"
else
    point 1 "" "the project declares the repository by name, URL and vault entry, and holds no credential"
    finish
fi

# ---------------------------------------------------------------- the task

step "Start a task in the project"
CONTAINERS+=("porelo-$PROJECT-build")
: >"$WORK/standin.log"
timeout 1800 porelo task start build --project "$PROJECT" --repository "$PROJECT" --agent stub --detach \
    --clearance deny >"$WORK/start.out" 2>&1
code=$?
redact <"$WORK/start.out" | tail -n 40
read_ "exit $code"
CONTAINER=$(container_of "$WORK/start.out")
[ -n "$CONTAINER" ] || CONTAINER="porelo-$PROJECT-build"
point "$code" "" "the task starts with its repository declared"
[ "$code" = 0 ] || finish
STATE=$(sed -n 's/^sidecar[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' "$WORK/start.out" | head -1)
STATE=${STATE%/*}
[ -n "$STATE" ] || STATE="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/$CONTAINER"
read_ "the task's state directory: $STATE"

step "Read the Maven settings the start wrote into the task, and the broker's address"
in_task 'f="$HOME/.m2/settings.xml"; ls -l "$f"; grep -E "<(id|url|mirrorOf)>" "$f"; \
    echo "server sections: $(grep -c "<server>" "$f")"; echo "password elements: $(grep -c "<password>" "$f")"; \
    echo "PORELO_REPOSITORY_CORP_URL=$PORELO_REPOSITORY_CORP_URL"' >"$WORK/settings.out" 2>&1
redact <"$WORK/settings.out"
point "$(grep -q 'http://127.0.0.1:9421/corp/' "$WORK/settings.out" && grep -q '^password elements: 0' "$WORK/settings.out" &&
    echo 0 || echo 1)" "" "settings.xml is written at the start, its mirror the broker's address and no password"
point "$(grep -q '^PORELO_REPOSITORY_CORP_URL=http://127.0.0.1:9421/corp/$' "$WORK/settings.out" && echo 0 || echo 1)" "" \
    "the task's environment names the broker's address for the repository"

step "Fetch the jar with curl inside the task, sending a credential of the task's own"
in_task 'curl -s -u someone:task-guess -o /tmp/story-lib.jar -w "status %{http_code}\n" \
    "${PORELO_REPOSITORY_CORP_URL}org/example/story/story-lib/1.0/story-lib-1.0.jar"; \
    echo "sha256 $(sha256sum < /tmp/story-lib.jar | cut -d" " -f1)"; \
    curl -s -o /dev/null -w "put status %{http_code}\n" -X PUT --data-binary x \
    "${PORELO_REPOSITORY_CORP_URL}org/example/story/story-lib/1.0/put-from-task.txt"' >"$WORK/curl.out" 2>&1
redact <"$WORK/curl.out"
point "$(grep -q '^status 200$' "$WORK/curl.out" && grep -q "^sha256 $JAR_SHA$" "$WORK/curl.out" && echo 0 || echo 1)" "" \
    "the task fetches the jar through the broker, byte for byte"
point "$(grep -q '^put status 405$' "$WORK/curl.out" && ! grep -q '^PUT ' "$WORK/standin.log" && echo 0 || echo 1)" "" \
    "a PUT from the task is refused and never reaches the repository"

step "Build a Maven project against the repository inside the task"
in_task_reading 'mkdir -p "$HOME/story-build/src/main/java/story" && cd "$HOME/story-build" && cat > pom.xml && \
    printf "%s\n" "package story;" "public class App { public static void main(String[] a) { } }" \
        > src/main/java/story/App.java' >"$WORK/write.out" 2>&1 <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example.story</groupId>
  <artifactId>story-build</artifactId>
  <version>1.0</version>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
          <release>17</release>
        </configuration>
      </plugin>
    </plugins>
  </build>
  <repositories>
    <repository>
      <id>corp</id>
      <url>https://corp.invalid/repository/story/</url>
    </repository>
  </repositories>
  <dependencies>
    <dependency>
      <groupId>org.example.story</groupId>
      <artifactId>story-lib</artifactId>
      <version>1.0</version>
    </dependency>
  </dependencies>
</project>
POM
in_task 'cd "$HOME/story-build" && mvn -B -ntp package > /tmp/story-maven.log 2>&1; echo "maven exit $?"; \
    tail -n 25 /tmp/story-maven.log; \
    f="$HOME/.m2/repository/org/example/story/story-lib/1.0/story-lib-1.0.jar"; \
    [ -f "$f" ] && echo "resolved sha256 $(sha256sum < "$f" | cut -d" " -f1)"; \
    ls target/*.jar 2>/dev/null' >"$WORK/maven.out" 2>&1
redact <"$WORK/maven.out"
point "$(grep -q 'BUILD SUCCESS' "$WORK/maven.out" && grep -q "^resolved sha256 $JAR_SHA$" "$WORK/maven.out" &&
    echo 0 || echo 1)" "" "a Maven build in the task resolves the jar from the repository and succeeds"
refused=$(grep -c ' auth-refused$' "$WORK/standin.log")
allowed=$(grep -c ' auth-ok$' "$WORK/standin.log")
read_ "the stand-in answered $allowed request(s) with the credential and refused $refused without it"
grep -v ' auth-ok$' "$WORK/standin.log" | head -n 10 | redact
point "$([ "$allowed" -gt 0 ] && [ "$refused" = 0 ] && echo 0 || echo 1)" "" \
    "every request from the task reached the repository with the vault's credential, added on the host"

# ---------------------------------------------------------------- the search

step "Search the task's environment for the credential"
found=$(podman exec "$CONTAINER" env 2>/dev/null | count)
read_ "$found line(s) of the environment a command in the task gets"
point "$([ "$found" = 0 ] && echo 0 || echo 1)" "" "no credential in the task's environment"
found=$(podman container inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CONTAINER" 2>/dev/null | count)
read_ "$found line(s) of the environment the container was made with"
point "$([ "$found" = 0 ] && echo 0 || echo 1)" "" "no credential in the container's own environment"

step "Search the task's processes, their command lines and environments, for the credential"
podman exec "$CONTAINER" sh -c 'for p in /proc/[0-9]*; do tr "\0" "\n" < "$p/cmdline"; echo; \
    tr "\0" "\n" < "$p/environ"; echo; done 2>/dev/null' >"$WORK/processes" 2>/dev/null
seen=$(grep -c . "$WORK/processes")
found=$(count <"$WORK/processes")
read_ "$found of $seen line(s) of the processes the task's user sees"
point "$([ "$seen" -gt 0 ] && [ "$found" = 0 ] && echo 0 || echo 1)" "" "no credential in the task's processes"
: >"$WORK/helpers"
for pid in $(pgrep -u "$(id -u)" -f -- "porelo/$CONTAINER/"); do
    {
        tr '\0' '\n' <"/proc/$pid/cmdline"
        echo
        tr '\0' '\n' <"/proc/$pid/environ"
        echo
    } \
        >>"$WORK/helpers" 2>/dev/null
done
helpers=$(pgrep -u "$(id -u)" -f -- "porelo/$CONTAINER/" | wc -l)
found=$(count <"$WORK/helpers")
read_ "$found line(s) of the $helpers helper process(es) on the host that name the task"
point "$([ "$found" = 0 ] && echo 0 || echo 1)" "" \
    "no credential on the command line or in the environment of the task's helpers"

step "Search the task's files, the whole container as podman exports it, for the credential"
found=$(timeout 900 podman export "$CONTAINER" 2>/dev/null | count)
read_ "$found line(s) of the container's files"
point "$([ "$found" = 0 ] && echo 0 || echo 1)" "" "no credential in the task's files"

step "Search what is mounted into the task, on the host side, for the credential"
podman container inspect --format '{{range .Mounts}}{{println .Source}}{{end}}' "$CONTAINER" \
    >"$WORK/mounts" 2>/dev/null
found=0
while read -r source; do
    [ -n "$source" ] && [ -e "$source" ] || continue
    hits=$(grep -r -l -F -f "$WORK/needles" -- "$source" 2>/dev/null | wc -l)
    read_ "$hits file(s) under $source"
    found=$((found + hits))
done <"$WORK/mounts"
point "$([ "$found" = 0 ] && echo 0 || echo 1)" "" "no credential in what is mounted into the task"
hits=$(grep -r -l -F -f "$WORK/needles" -- "$STATE" 2>/dev/null | wc -l)
read_ "$hits file(s) in the task's state directory on the host, its helpers' logs among them"
point "$([ "$hits" = 0 ] && echo 0 || echo 1)" "" "no credential in the task's state directory or its logs"

step "Read that the credential stayed in the vault"
porelo vault list >"$WORK/list.out" 2>&1
redact <"$WORK/list.out"
point "$(grep -q -F -- "$ENTRY" "$WORK/list.out" && echo 0 || echo 1)" "" "the credential stays in the vault"

finish
