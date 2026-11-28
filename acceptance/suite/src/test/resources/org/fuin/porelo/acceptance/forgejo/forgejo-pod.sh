#!/usr/bin/env bash
set -euo pipefail
# Do not inherit shell tracing: token generation must never be echoed.
set +x
umask 077

fail() {
    printf 'forgejo-pod: %s\n' "$1" >&2
    exit 1
}

usage() {
    fail 'usage: forgejo-pod.sh [--address <IPv4>] <new-directory> | forgejo-pod.sh --remove <directory>'
}

remove=false
# The address Forgejo listens on and its certificate names: loopback unless the suite runs on another host
# that reaches this one at the address given.
address=127.0.0.1
if [[ $# == 3 && $1 == --address ]]; then
    address=$2
    shift 2
    [[ $address =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || fail 'the address is an IPv4 address'
fi
if [[ $# == 2 && $1 == --remove ]]; then
    remove=true
    directory=$2
elif [[ $# == 1 && $1 != --* ]]; then
    directory=$1
else
    usage
fi
[[ -n $directory && ! -L $directory ]] || fail 'the directory must not be empty or a symbolic link'
directory=$(realpath -m -- "$directory")
[[ $directory != / && $directory != "$HOME" ]] || fail 'a dedicated fixture directory is required'

if $remove; then
    [[ -d $directory && -O $directory && -f $directory/container && ! -L $directory/container ]] ||
        fail 'the directory is not an owned Forgejo fixture'
    IFS= read -r container <"$directory/container"
    [[ $container =~ ^porelo-forgejo-[a-f0-9]{12}$ ]] || fail 'invalid fixture container name'
    if podman container exists "$container"; then
        label=$(podman inspect --format '{{ index .Config.Labels "porelo.forgejo-fixture" }}' "$container")
        [[ $label == "$directory" ]] || fail 'the container belongs to another fixture'
        podman rm --force "$container" >/dev/null
    else
        status=$?
        [[ $status == 1 ]] || fail 'cannot check whether the fixture container exists'
    fi
    rm -rf -- "$directory"
    exit 0
fi

# mkdir refuses reuse, including an earlier partial setup. Keep failures for explicit --remove.
mkdir -m 0700 -- "$directory" || fail 'cannot create a new fixture directory'
container="porelo-forgejo-$(openssl rand -hex 6)"
printf '%s\n' "$container" >"$directory/container"
trap 'printf "forgejo-pod: setup failed; use --remove with the fixture directory after inspection\n" >&2' ERR
image=codeberg.org/forgejo/forgejo:17.0.0
if podman image exists "$image"; then
    :
else
    status=$?
    [[ $status == 1 ]] || fail 'cannot check the Forgejo image'
    podman load -i /opt/images/forgejo-17.0.0.tar >/dev/null
fi
podman image exists "$image" || fail 'the archive does not contain the expected Forgejo 17.0.0 image'

# An IP SAN needs neither a hosts-file change nor root privileges.
port=3443
url="https://$address:$port/"
owner=porelo-test
mkdir -m 0700 "$directory/tls" "$directory/data"
mkdir -p "$directory/data/gitea/conf"
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 2 \
    -subj '/CN=Porelo acceptance CA' \
    -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' \
    -keyout "$directory/ca.key" -out "$directory/ca.pem" >/dev/null 2>&1
openssl req -new -newkey rsa:3072 -sha256 -nodes \
    -subj "/CN=$address" -keyout "$directory/tls/server.key" \
    -out "$directory/server.csr" >/dev/null 2>&1
cat >"$directory/server.ext" <<EXT
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:$address
EXT
openssl x509 -req -sha256 -days 2 -in "$directory/server.csr" \
    -CA "$directory/ca.pem" -CAkey "$directory/ca.key" -CAcreateserial \
    -extfile "$directory/server.ext" -out "$directory/tls/server.pem" >/dev/null 2>&1
rm -- "$directory/ca.key" "$directory/server.csr" "$directory/server.ext" "$directory/ca.srl"

cat >"$directory/data/gitea/conf/app.ini" <<INI
RUN_USER = git
RUN_MODE = prod
WORK_PATH = /data/gitea
[database]
DB_TYPE = sqlite3
PATH = /data/gitea/forgejo.db
[repository]
ROOT = /data/git/repositories
DEFAULT_BRANCH = main
[server]
PROTOCOL = https
HTTP_ADDR = 0.0.0.0
HTTP_PORT = 3000
DOMAIN = $address
ROOT_URL = $url
CERT_FILE = /tls/server.pem
KEY_FILE = /tls/server.key
DISABLE_SSH = true
APP_DATA_PATH = /data/gitea
[security]
INSTALL_LOCK = true
[service]
DISABLE_REGISTRATION = true
[log]
MODE = console
LEVEL = Warn
INI

# Keep the invoking account's ownership on bind mounts while running as the image's git user.
podman run --detach --pull=never --name "$container" \
    --label "porelo.forgejo-fixture=$directory" \
    --userns=keep-id:uid=1000,gid=1000 --user 1000:1000 \
    --publish "$address:$port:3000" \
    --volume "$directory/data:/data:Z" --volume "$directory/tls:/tls:ro,Z" \
    --entrypoint /usr/local/bin/forgejo "$image" \
    --work-path /data/gitea --config /data/gitea/conf/app.ini web >/dev/null

ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if curl --silent --fail --max-time 2 --cacert "$directory/ca.pem" \
        "${url}api/v1/version" >/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail 'Forgejo did not become ready with its CA and IP SAN'

# The CLI generates the password itself; neither it nor the token is an argument or a log line.
podman exec --user 1000:1000 "$container" /usr/local/bin/forgejo \
    --work-path /data/gitea --config /data/gitea/conf/app.ini \
    admin user create --username "$owner" --email acceptance@example.invalid \
    --admin --random-password --must-change-password=false >/dev/null 2>&1 ||
    fail 'cannot create the Forgejo test user'
podman exec --user 1000:1000 "$container" /usr/local/bin/forgejo \
    --work-path /data/gitea --config /data/gitea/conf/app.ini \
    admin user generate-access-token --username "$owner" --token-name porelo-acceptance \
    --scopes write:repository,write:issue,write:user --raw >"$directory/token" 2>/dev/null ||
    fail 'cannot generate the Forgejo test token'
[[ -s $directory/token ]] || fail 'the token generator returned no token'
printf '%s\n' "$url" >"$directory/url"
printf '%s\n' "$owner" >"$directory/owner"
chmod 0600 "$directory/token" "$directory/url" "$directory/owner" "$directory/ca.pem"

printf 'PORELO_FORGEJO_URL: read from %q\n' "$directory/url"
printf 'PORELO_FORGEJO_TEST_TOKEN: read from %q\n' "$directory/token"
printf 'PORELO_FORGEJO_TEST_OWNER: read from %q\n' "$directory/owner"
printf 'PORELO_FORGEJO_CA: use the path %q\n' "$directory/ca.pem"
