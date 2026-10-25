@slow
Feature: A task trusts only the authority explicitly handed to it

    The TLS fixture listens on loopback inside the task, so no host service mapping is needed.
    Only a leaf certificate and its disposable server key enter the task; the authority's key stays outside.

    Scenario: curl on ubuntu:24.04 trusts the host authority without a per-command CA option
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "tasktrust" of class "guarded" with a file in it
        When a script runs:
            """
            set -eu
            umask 077
            config="${XDG_CONFIG_HOME:-$HOME/.config}/porelo"
            authority="$config/task-ca-certificates.pem"
            if [ -e "$authority" ] || [ -L "$authority" ]; then
                echo "refusing to replace the account's task authority: $authority" >&2
                exit 1
            fi
            mkdir -p "$config"
            fixture=$(mktemp -d "$config/task-ca-test.XXXXXX")
            task=
            installed=0
            cleanup() {
                result=$?
                trap - EXIT HUP INT TERM
                if [ -n "$task" ]; then
                    porelo task remove "$task" --force || { echo "left task $task" >&2; result=1; }
                fi
                if [ "$installed" = 1 ]; then
                    rm "$authority" || { echo "left authority $authority" >&2; result=1; }
                fi
                rm -rf "$fixture" || { echo "left fixture $fixture" >&2; result=1; }
                exit "$result"
            }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=TaskFixtureAuthority \
                -addext basicConstraints=critical,CA:TRUE \
                -keyout "$fixture/authority.key" -out "$fixture/authority.pem" 2>"$fixture/openssl.log"
            openssl req -newkey rsa:2048 -nodes -subj /CN=localhost \
                -keyout "$fixture/server.key" -out "$fixture/server.csr" 2>>"$fixture/openssl.log"
            printf 'subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > "$fixture/extensions"
            openssl x509 -req -days 1 -in "$fixture/server.csr" -CA "$fixture/authority.pem" \
                -CAkey "$fixture/authority.key" -CAcreateserial -extfile "$fixture/extensions" \
                -out "$fixture/server.pem" 2>>"$fixture/openssl.log"
            cat > "$fixture/serve.py" <<'PY'
            import http.server, ssl
            server = http.server.HTTPServer(('127.0.0.1', 8443), http.server.SimpleHTTPRequestHandler)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain('/tmp/task-server.pem', '/tmp/task-server.key')
            server.socket = context.wrap_socket(server.socket, server_side=True)
            server.serve_forever()
            PY
            for trust in without with; do
                if [ "$trust" = with ]; then
                    mkdir -p "$config"
                    ln "$fixture/authority.pem" "$authority"
                    installed=1
                    chmod 600 "$authority"
                fi
                started=0
                porelo task start --project tasktrust --repository tasktrust --agent stub \
                    --detach --clearance deny > "$fixture/start.log" 2>&1 || started=$?
                task=$(awk '$1 == "container" { print $2 }' "$fixture/start.log")
                if [ "$started" != 0 ]; then cat "$fixture/start.log"; exit 1; fi
                test -n "$task"
                id=$(podman inspect --format '{{.Id}}' "$task")
                podman cp "$fixture/server.pem" "$id:/tmp/task-server.pem"
                podman cp "$fixture/server.key" "$id:/tmp/task-server.key"
                podman cp "$fixture/serve.py" "$id:/tmp/task-serve.py"
                echo "step $trust: start server" >&2
                podman exec --detach "$id" sh -c 'python3 /tmp/task-serve.py > /tmp/task-serve.log 2>&1'
                ready=0
                for attempt in $(seq 1 50); do
                    if podman exec "$id" python3 -c "import socket; socket.create_connection(('127.0.0.1', 8443), 1).close()" 2>/dev/null; then
                        ready=1
                        break
                    fi
                    sleep 0.1
                done
                if [ "$ready" != 1 ]; then echo "step $trust: server not ready" >&2; podman exec "$id" cat /tmp/task-serve.log >&2; exit 1; fi
                echo "step $trust: curl" >&2
                code=0
                podman exec "$id" curl --noproxy '*' --max-time 10 -sS -o /dev/null -w '%{http_code}' \
                    https://127.0.0.1:8443/ > "$fixture/http" 2> "$fixture/curl-error" || code=$?
                if [ "$trust" = without ]; then
                    test "$code" = 60 || { cat "$fixture/curl-error"; exit 1; }
                    echo 'without task authority: curl exit 60'
                else
                    if [ "$code" != 0 ] || [ "$(cat "$fixture/http")" != 200 ]; then echo "step with: curl $code $(cat "$fixture/http")" >&2; cat "$fixture/curl-error" >&2; exit 1; fi
                    echo 'with task authority: HTTP 200'
                fi
                porelo task remove "$task" --force
                task=
            done
            """
        Then it exits zero
        And its output contains "without task authority: curl exit 60"
        And its output contains "with task authority: HTTP 200"
