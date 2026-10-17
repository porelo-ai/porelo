Feature: The commands a machine answers about itself

    Every group's entry point, and the commands that take no group. A command that vanished,
    was renamed, or stopped parsing its own options shows up here before anybody types it.

    Scenario Outline: every command group answers for itself
        When a script runs "porelo <group> --help"
        Then it exits zero
        And its output contains "<mentions>"

        Examples:
            | group  | mentions  |
            | task   | run       |
            | shield | egress    |
            | vault  | login     |
            | gate   | pending   |
            | daemon | connect   |

    Scenario Outline: every top-level command answers for itself
        When a script runs "porelo <command> --help"
        Then it exits zero

        Examples:
            | command    |
            | agents     |
            | providers  |
            | completion |
            | projects   |
            | setup    |
            | doctor   |
            | panic    |

    Scenario: doctor reports whether this machine can run a task
        When a script runs "porelo doctor"
        Then it exits zero
        And its output contains "hooks registered"
        And its output contains "podman"

    Scenario: panic says what it would stop without stopping anything
        When a script runs "porelo panic --dry-run"
        Then it exits zero

    Scenario: agents lists what is installed
        When a script runs "porelo agents"
        Then it exits zero

    Scenario: an unknown command is answered with what does exist
        When a script runs "porelo nonsense"
        Then it exits non-zero
        And its output contains "has no command called"

    Scenario: a command typed in the wrong place is found where it lives
        When a script runs "porelo unlock"
        Then it exits non-zero
        And its output contains "vault unlock"

    Scenario: setup registers the hooks for this account, and says they touch no other container
        # Run on a machine that is already set up, so it rewrites what is there; never --uninstall, which
        # would take the firewall from every task after it.
        When a script runs "porelo setup"
        Then it exits zero
        And its output contains "installed"
        And its output contains "50-porelo.conf"
        And its output contains "only fire for containers carrying Porelo's own annotation"
        # Where the account has the user unit it is started; where it has none - an account running a build from source, as
        # a CI leg's extra accounts do - it says so and how, and the rest goes on.
        And its output has a line matching "daemon +(already running|started, and starts with this account from now on|not started: .+ - start it in this account's own session with 'systemctl --user enable --now porelod')"

    Scenario: setup on an account with no vault leaves it for a terminal and says how, and doctor names it
        # What a wizard meets on a machine just prepared, run the way a script runs it: with no terminal. Only the
        # vault is new here; the hooks and the daemon are this account's, and running setup again changes neither.
        When a script runs:
            """
            export PORELO_VAULT="$(mktemp -d)/vault.bin"
            porelo setup
            porelo doctor | grep -E '^vault '
            """
        Then it exits zero
        And its output has a line matching "vault +none yet; it needs a passphrase typed at a terminal .*ssh -t.*"
        And its output contains "Not ready yet"
        And its output contains "there is no vault here yet"

    Scenario: the providers say whether the credential each needs is stored, without showing it
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        When a script runs "porelo providers"
        Then it exits zero
        And its output contains "stored as 'anthropic' (api-key)"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"
        When a script runs "porelo vault lock"
        And a script runs "porelo providers"
        Then its output contains "unknown - vault locked"

    Scenario Outline: a completion script is given for a shell that has one, and refused for one that has not
        When a script runs "porelo completion <shell>"
        Then its output contains "<says>"

        Examples:
            | shell | says                                |
            | bash  | complete -F _porelo_completions porelo |
            | zsh   | #compdef porelo                      |
            | fish  | no completion script for 'fish'     |

    # Guards the daemon being reachable from the network: whatever can reach its socket can start tasks and spend the
    # vault's credentials, so it listens on its owner-only socket and on no port. Asked of the daemon's own process
    # only: a task's relay binds an address, but inside that task's network namespace.
    Scenario: the daemon listens on its own socket and on no network port
        # A machine where nothing asked for the daemon yet runs none: the scenario starts its own, as a rented leg needs.
        Given a daemon of this scenario's own
        When a script runs:
            """
            sock="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/porelod.sock"
            printf '%s\0' '{"method":"org.varlink.service.GetInfo"}' | timeout 30 porelo daemon connect >/dev/null 2>&1
            pid=$(ss -lxpH | grep -F " $sock " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
            [ -n "$pid" ] || { echo "no process listens on $sock"; ss -lxp | grep porelo; exit 1; }
            echo "network listeners: $(ss -ltunpH | grep -c "pid=$pid,")"
            ss -ltunpH | grep "pid=$pid,"
            """
        Then its output contains "network listeners: 0"

    # Guards the remote use another repository's interface is built on: a daemon on another machine reached through
    # ssh, by the bridge (one session, one connection) and by a forwarded socket, with nothing set up on the far side.
    # The hop is a real one, to this machine's own sshd, with a key made for it and taken out again.
    @slow
    Scenario: a client over ssh watches the tasks through the bridge and lists them through a forwarded socket
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "hop" of class "guarded" with a file in it
        And a daemon of this scenario's own
        When a script runs:
            """
            dir=$(mktemp -d); mark="porelo-acceptance-hop-$$"; watch=""; forward=""; task=""
            sock="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/porelod.sock"
            porelo=$(command -v porelo)
            cleanup() {
              exec 3>&-
              [ -n "$watch" ] && kill "$watch" 2>/dev/null
              [ -n "$forward" ] && kill "$forward" 2>/dev/null
              # Started through a function in the background, $! is the subshell, not the ssh it ran: ended by what it
              # serves, or it holds this step's output open after everything else is done.
              pkill -f -- "$dir/forwarded.sock" 2>/dev/null
              sed -i "/ $mark\$/d" ~/.ssh/authorized_keys
              [ -n "$task" ] && porelo task remove "$task" --force >/dev/null 2>&1
              rm -rf "$dir"
            }
            trap cleanup EXIT
            ssh-keygen -q -t ed25519 -N '' -C "$mark" -f "$dir/key" || exit 1
            mkdir -p ~/.ssh && chmod 700 ~/.ssh
            echo "from=\"127.0.0.1,::1\" $(cat "$dir/key.pub")" >> ~/.ssh/authorized_keys
            hop() {
              ssh -i "$dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no \
                  -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "$@"
            }
            # The bridge: a watch held open over one ssh session while a task comes and goes on this machine.
            mkfifo "$dir/asked"
            hop localhost "$porelo daemon connect --socket '$sock'" < "$dir/asked" > "$dir/watched" 2>&1 &
            watch=$!
            # Held open by this shell, so the bridge's standard input does not end before the watch is done with.
            exec 3> "$dir/asked"
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.Watch","more":true}' >&3
            timeout 600 porelo task start --project hop --repository hop --agent stub --detach --clearance deny > "$dir/started" 2>&1
            task=$(grep -o 'porelo-hop-[A-Za-z0-9_.-]*' "$dir/started" | head -1)
            [ -n "$task" ] || { echo "no task started"; tail -5 "$dir/started"; exit 1; }
            for i in $(seq 60); do grep -qF "\"$task\"" "$dir/watched" && break; sleep 1; done
            grep -qF "\"$task\"" "$dir/watched" && echo "the watch over ssh saw the task start"
            porelo task remove "$task" --force >/dev/null 2>&1
            last() { tr '\0' '\n' < "$dir/watched" | grep . | tail -1; }
            for i in $(seq 60); do last | grep -qF "\"$task\"" || break; sleep 1; done
            last | grep -q '"tasks"' && ! last | grep -qF "\"$task\"" && echo "the watch over ssh saw it go"
            # The forward: the daemon's socket put on a path of the client's own, and asked there.
            hop -N -o ExitOnForwardFailure=yes -L "$dir/forwarded.sock:$sock" localhost </dev/null >"$dir/forward.log" 2>&1 &
            forward=$!
            for i in $(seq 100); do [ -S "$dir/forwarded.sock" ] && break; sleep 0.1; done
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.List"}' \
                | timeout 30 porelo daemon connect --socket "$dir/forwarded.sock" | tr '\0' '\n' | head -1 \
                | grep -q '"tasks"' && echo "the forwarded socket answered the listing"
            """
        Then its output contains "the watch over ssh saw the task start"
        And its output contains "the watch over ssh saw it go"
        And its output contains "the forwarded socket answered the listing"
