#!/usr/bin/env bash
# A disposable CA keeps the suite from trusting any machine's existing keys.
set -euo pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
umask 077
dir=/var/tmp/porelo-host-services
private=/root/porelo-host-services
unit=porelo-host-services-listener.service
fail() {
    printf 'host-services-listener: %s\n' "$*" >&2
    exit 1
}
[ "$#" = 1 ] || fail "usage: $0 up|down"
case "$1" in up | down) ;; *) fail "usage: $0 up|down" ;; esac
[ "$(id -u)" = 0 ] || fail "run as root"

# Lock a root-owned directory so concurrent up/down calls need no persistent lock file.
exec 9</root
flock --exclusive --nonblock 9 || fail "another preparation or cleanup is running"

stop_unit() {
    local status=0
    systemctl stop "$unit" || status=$?
    case "$status" in
    0 | 5) return 0 ;;
    *)
        printf 'host-services-listener: cannot stop %s (exit %s); files kept at %s and %s\n' \
            "$unit" "$status" "$private" "$dir" >&2
        return "$status"
        ;;
    esac
}

if [ "$1" = down ]; then
    for path in "$private" "$dir"; do
        [ ! -L "$path" ] || fail "refusing symbolic link $path"
        if [ -e "$path" ]; then
            [ -d "$path" ] && [ "$(stat -c %u "$path")" = 0 ] || fail "not a root-owned directory: $path"
        fi
    done
    stop_unit
    rm -rf -- "$private" "$dir"
    printf 'OK    %s stopped; listener files removed\n' "$unit"
    exit 0
fi

state=$(systemctl show --property=LoadState --value "$unit")
[ "$state" = not-found ] || fail "$unit exists ($state); run down first"
listeners=$(ss -Hltn 'sport = :443')
[ -z "$listeners" ] || fail "something listens on port 443 already"
for path in "$private" "$dir"; do
    [ ! -e "$path" ] && [ ! -L "$path" ] || fail "$path exists; run down first"
done

made_private=0
made_public=0
unit_requested=0
prepared=0
interrupted=0
trap 'interrupted=130' INT
trap 'interrupted=143' TERM
checkpoint() { [ "$interrupted" = 0 ] || exit "$interrupted"; }
cleanup() {
    local status=$?
    trap - EXIT
    if [ "$prepared" = 0 ]; then
        if [ "$unit_requested" = 1 ] && ! stop_unit; then
            exit 1
        fi
        if [ "$made_private" = 1 ]; then rm -rf -- "$private"; fi
        if [ "$made_public" = 1 ]; then rm -rf -- "$dir"; fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'printf "host-services-listener: line %s failed (exit %s)\n" "$LINENO" "$?" >&2' ERR
mkdir -m 700 -- "$private"
made_private=1
checkpoint
mkdir -m 755 -- "$dir"
made_public=1
checkpoint
cd "$private"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj /CN=porelo-host-services-ca \
    -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign \
    -keyout ca.key -out ca.pem
checkpoint
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj /CN=forge.host-service.test \
    -keyout server.key -out server.csr
checkpoint
printf 'subjectAltName=DNS:forge.host-service.test\nextendedKeyUsage=serverAuth\n' >server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -extfile server.ext \
    -out server.pem
rm -f -- ca.key ca.srl server.csr server.ext
checkpoint
install -m 644 ca.pem "$dir/ca.pem"
cat >server.py <<'PYTHON'
import http.server
import ssl

class Answer(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/":
            self.send_error(404)
            return
        body = b"host-service-ok\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

server = http.server.ThreadingHTTPServer(("127.0.0.1", 443), Answer)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain("/root/porelo-host-services/server.pem", "/root/porelo-host-services/server.key")
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
PYTHON
checkpoint
unit_requested=1
systemd-run --quiet --collect --unit="$unit" --service-type=exec \
    --property=Restart=no --property=UMask=0077 /usr/bin/python3 -I "$private/server.py"
checkpoint
for ((attempt = 0; attempt < 50; attempt++)); do
    listeners=$(ss -Hltn 'sport = :443')
    addresses=$(printf '%s\n' "$listeners" | awk '{print $4}')
    if [ "$addresses" = 127.0.0.1:443 ]; then
        systemctl is-active --quiet "$unit"
        checkpoint
        prepared=1
        printf 'OK    %s listens on 127.0.0.1:443; CA at %s/ca.pem\n' "$unit" "$dir"
        exit 0
    fi
    [ -z "$listeners" ] || fail "unexpected listener on port 443: $addresses"
    systemctl is-active --quiet "$unit" || fail "$unit stopped before listening"
    sleep 0.1
    checkpoint
done
fail "$unit did not listen on 127.0.0.1:443"
