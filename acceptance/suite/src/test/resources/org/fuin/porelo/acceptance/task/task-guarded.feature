@slow
Feature: A task in a guarded project, from its image to what it hands back

    What a guarded task is promised, checked on a real container: the image it runs, the hardening it
    runs under, the credential it never sees, the names and ports it may reach, and the gate its work
    leaves by. Each layer between a project file and a packet has been wrong at least once - the
    package that ships the sets, the binary that reads them, the resolver that answers for them, the
    firewall rule that decides the port - and a unit test sees none of them together.

    The credential is fake and set by this suite's configuration. The provider rejects it, and that
    rejection is the proof that the broker swapped the task's token for it: a refusal from Porelo would
    mean the swap never happened. Every scenario starts a task of its own name in one project, so the
    image is built once.

    Background:
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "guarded" of class "guarded" whose project file also says:
            """
              run: |
                RUN apt-get update && apt-get install -y --no-install-recommends jq \
                    && rm -rf /var/lib/apt/lists/*
            egress:
              sets: [maven, git-hosting]
            """

    Scenario: the image carries the agent's tool at the version it pins, the project's tooling, and no root
        When a task called "image" is started in "guarded" for the "stub" agent and left running
        Then its output has a line matching "agent +stub .*"
        And its output has a line matching "snippet +2 lines from the project's image .guarded."
        When a script runs about the task:
            """
            image=$(podman container inspect --format '{{.ImageName}}' {task})
            pinned=$(porelo agents --supply-chain | awk '/^[^ ]/ { row = ($1 == "stub") } row && $1 == "installs:" { print $2 }')
            podman run --rm "$image" sh -c 'command -v porelo-stub-cli; command -v jq; echo "runs as $(whoami)"'
            running=$(podman run --rm "$image" porelo-stub-cli --version | awk '{ print $1 }')
            [ -n "$pinned" ] && [ "$running" = "$pinned" ] && echo "the tool is the pinned version" || echo "the image runs $running, the agent pins $pinned"
            """
        Then it exits zero
        And its output contains "/usr/local/bin/porelo-stub-cli"
        And its output contains "/usr/bin/jq"
        And its output contains "runs as agent"
        And its output contains "the tool is the pinned version"

    Scenario: the container holds no privilege, and the firewall was in place before it ran
        When a task called "hardened" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'grep -E "^(NoNewPrivs|CapEff):" /proc/self/status' | awk '{ print $1 $2 }'
            grep -q 'nft createRuntime ok' {state}/hooks.log && echo "the firewall hook ran when the container was created"
            # Closed is refused (1) or dropped until the limit (124); a probe that could not run is neither.
            podman exec --user agent {task} bash -c 'timeout 5 bash -c "echo > /dev/tcp/1.1.1.1/443"' 2>/dev/null; rc=$?
            case $rc in
              0) echo "an undeclared address is open" ;;
              1|124) echo "an undeclared address is closed" ;;
              *) echo "the probe could not run (exit $rc)" ;;
            esac
            # One attempt is one line, however often its SYN went out again in those five seconds; it read as two.
            sleep 1
            grep -c '"destination":"1.1.1.1","port":443' {state}/events.jsonl | sed 's/^/lines for the one attempt: /'
            # The upstream resolver is for Porelo's resolver alone: asked directly, it carried data out in query names.
            pid=$(podman inspect --format '{{.State.Pid}}' {task})
            up=$(podman unshare nsenter --target "$pid" --net nft list ruleset | awk '/skuid 0/ && /dport 53/ { print $3; exit }')
            [ -n "$up" ] && echo "the upstream resolver is named in the firewall"
            podman exec --user agent {task} bash -c "timeout 5 bash -c 'echo > /dev/tcp/$up/53'" 2>/dev/null; rc=$?
            case $rc in
              0) echo "the upstream resolver is open to the agent" ;;
              1|124) echo "the upstream resolver is closed to the agent" ;;
              *) echo "the resolver probe could not run (exit $rc)" ;;
            esac
            """
        Then its output contains "NoNewPrivs:1"
        And its output contains "CapEff:0000000000000000"
        And its output contains "the firewall hook ran when the container was created"
        And its output contains "lines for the one attempt: 1"
        And its output contains "the upstream resolver is named in the firewall"
        And its output contains "the upstream resolver is closed to the agent"
        And its output contains "an undeclared address is closed"

    Scenario: the agent is given a token worth nothing outside the task, and its broker redeems it
        When a task called "token" is started in "guarded" for the "stub" agent and left running
        # The variable's name and nothing of its value: a prefix is part of a secret.
        Then its output has a line matching "token +[A-Z0-9_]+"
        And its output does not contain "porelo_pt_"
        And the task's container environment does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"
        # Which service its prompts go to is the task's own record, answerable after the start has scrolled away.
        When a script runs about the task:
            """
            porelo task status {task} | grep -E '^provider +anthropic$' && echo "the task names its provider"
            """
        Then its output contains "the task names its provider"
        # The stub asks the provider through the socket it was pointed at, with the token it was given.
        When the task's container runs:
            """
            test -S "$PORELO_STUB_SOCKET" && echo "the broker's socket is in the container"
            porelo-stub-cli
            """
        Then its output contains "the broker's socket is in the container"
        And its output contains "authentication_error"
        And its output does not contain "porelo:"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

    Scenario: every name the agent asks for is one it declared, and the one it declared refused stays refused
        When a task called "names" is started in "guarded" for the "stub" agent and left running
        And the task's container runs:
            """
            porelo-stub-cli
            """
        Then its output contains "stub: example.com resolved"
        And its output contains "stub: example.net refused"
        # A resolver appends its search domains to an unqualified lookup; the same query must not read as
        # an undeclared name. What the agent declares refused, and what it reaches through its broker,
        # are left out on purpose - both are absent from what it may resolve by design.
        When a script runs about the task:
            """
            sleep 2
            search=$(podman exec --user agent {task} sed -n 's/^search //p' /etc/resolv.conf)
            allowed=$(porelo agents --verbose | awk '/^[^ ]/ { row = ($1 == "stub") }
                row && $1 == "refused:" { $1 = ""; print }
                row && $1 == "provider:" { for (i = 1; i < NF; i++) if ($i == "->") print $(i + 1) }' | tr ', ' '\n\n')
            grep -oE 'config [a-z0-9.-]+ is NXDOMAIN' {state}/dnsmasq.log | awk '{ print $2 }' | sort -u | while read -r name; do
                for suffix in $search; do name=${name%."${suffix%.}"}; done
                case "$name" in *.local|*.localdomain) continue ;; esac
                if printf '%s\n' $allowed | grep -qxF "$name"; then echo "refused on purpose: $name"; else echo "undeclared: $name"; fi
            done
            """
        Then it exits zero
        And its output contains "refused on purpose: example.net"
        And its output does not contain "undeclared:"

    Scenario: the start says which set granted each host, and warns that a forge is reachable
        When a task called "report" is started in "guarded" for the "stub" agent and left running
        Then its output has a line matching "repo\.maven\.apache\.org +set maven"
        And its output has a line matching "github\.com +set git-hosting"
        And its output has a line matching "example\.net +refused on purpose by agent stub"
        And its output contains "the gate now rests on this container holding no credential"

    Scenario: a declared host resolves and answers on 443, and nothing else does
        When a task called "ports" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            for host in repo.maven.apache.org pypi.org; do
                podman exec --user agent {task} getent hosts "$host" >/dev/null && echo "$host resolves" || echo "$host does not resolve"
            done
            # 28 is a timeout, which is what a dropped packet looks like; 35, 52 and 56 mean TCP got through and only TLS failed.
            for port in 443 22; do
                code=$(podman exec --user agent {task} sh -c "curl -s -o /dev/null --max-time 12 https://github.com:$port/; echo \$?")
                case "$code" in 0|35|52|56) echo "github.com:$port is open" ;; *) echo "github.com:$port is closed ($code)" ;; esac
            done
            """
        Then its output contains "repo.maven.apache.org resolves"
        And its output contains "pypi.org does not resolve"
        And its output contains "github.com:443 is open"
        # So no push goes around the gate.
        And its output contains "github.com:22 is closed"

    Scenario: a running task is widened and narrowed without a restart, in its resolver and its firewall
        When a task called "widened" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} getent hosts pypi.org >/dev/null && echo "before: resolves" || echo "before: does not resolve"
            porelo shield egress --task {task} --add-domain pypi.org
            podman exec --user agent {task} getent hosts pypi.org >/dev/null && echo "widened: resolves" || echo "widened: does not resolve"
            # Reached once, so an address really enters the firewall and is recorded against the name.
            podman exec --user agent {task} sh -c 'curl -s -o /dev/null --max-time 12 https://pypi.org/'
            address=$(awk -F '\t' '$1 == "pypi.org" { print $2; exit }' {state}/granted-addresses)
            [ -n "$address" ] && echo "an address is recorded against the name"
            # Read before it is narrowed too: a firewall that cannot be read said "out of the firewall" below.
            pid=$(podman inspect --format '{{.State.Pid}}' {task})
            allowed() { podman unshare nsenter --target "$pid" --net nft list set inet porelo allowed_v4; }
            before=$(allowed) && printf '%s\n' "$before" | grep -qF "$address" && echo "the address entered the firewall"
            # What the watcher decided, shown so that a failure below says why.
            sed 's/^/watcher: /' {state}/clearance.log
            porelo shield egress --task {task} --remove-domain pypi.org --dry-run
            podman exec --user agent {task} getent hosts pypi.org >/dev/null && echo "after a dry run: resolves" || echo "after a dry run: does not resolve"
            porelo shield egress --task {task} --remove-domain pypi.org
            podman exec --user agent {task} getent hosts pypi.org >/dev/null && echo "narrowed: resolves" || echo "narrowed: does not resolve"
            if after=$(allowed); then
              printf '%s\n' "$after" | grep -qF "$address" && echo "the address is still in the firewall" \
                  || echo "the address is out of the firewall"
            else
              echo "the firewall could not be read"
            fi
            """
        Then it exits zero
        And its output contains "before: does not resolve"
        And its output contains "widened: resolves"
        And its output contains "an address is recorded against the name"
        And its output contains "the address entered the firewall"
        And its output contains "after a dry run: resolves"
        And its output contains "narrowed: does not resolve"
        And its output contains "the address is out of the firewall"
        # What narrowing leaves alone, said out loud, because somebody will assume otherwise.
        And its output contains "runs to its end"

    Scenario: the agent's work leaves through the gate, which nothing off this machine can reach
        When a task called "handed" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'cd /workspace && test -d .git && git remote get-url porelo' && echo "the workspace has a remote to the gate"
            podman exec --user agent {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "acceptance: work from the agent" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"' \
                && echo "the push went through"
            porelo gate pending --project guarded
            port=$(podman exec --user agent {task} sh -c 'cd /workspace && git remote get-url porelo' | sed -n 's|.*:\([0-9]*\)/.*|\1|p')
            # Java binds a dual-stack socket, so loopback reads as [::ffff:127.0.0.1] here.
            ss -ltnH "sport = :$port" | grep -qE '(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):'"$port" \
                && echo "the gate listens on loopback only" || ss -ltnH "sport = :$port"
            lan=$(ip -4 -o route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')
            timeout 5 bash -c "echo > /dev/tcp/$lan/$port" 2>/dev/null && echo "the gate answers on $lan" || echo "the gate does not answer on the network"
            """
        Then its output contains "the workspace has a remote to the gate"
        And its output contains "the push went through"
        And its output contains "acceptance: work from the agent"
        And its output contains "the gate listens on loopback only"
        And its output contains "the gate does not answer on the network"

    Scenario: a stopped task holding work nobody pushed is not removed until somebody means it
        When a task called "holding" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "acceptance: never pushed"'
            porelo task stop {task} >/dev/null 2>&1
            podman container exists {task} && echo "stopping kept it"
            porelo task remove {task}
            podman container exists {task} && echo "a plain remove kept it"
            porelo task remove {task} --force >/dev/null 2>&1
            podman container exists {task} || echo "--force removed it"
            """
        Then its output contains "stopping kept it"
        And its output contains "never reached the gate"
        And its output contains "a plain remove kept it"
        And its output contains "--force removed it"

    Scenario: a running task's clearance is previewed, and asking for what it has changes nothing
        When a task called "cleared" is started in "guarded" for the "stub" agent and left running
        And a script runs "porelo task clearance {task} allow --dry-run" about the task
        Then it exits zero
        And its output contains "would change clearance deny -> allow"
        When a script runs "porelo task clearance {task} deny" about the task
        Then it exits zero
        And its output contains "clearance already deny, unchanged"

    Scenario: the resolver is the system's dnsmasq, whatever PATH finds first
        # A stand-in first on this account's PATH: it says it has no nftset, and as a resolver it only
        # sleeps. Before the hooks took dnsmasq by its fixed path, nsenter looked the bare name up.
        When a script runs:
            """
            mkdir -p "$HOME/.local/bin"
            printf '#!/bin/sh\n[ "$1" = --version ] && { echo "Dnsmasq version stand-in"; echo "Compile time options: no-nftset"; exit 0; }\nexec sleep 600\n' > "$HOME/.local/bin/dnsmasq"
            chmod 0755 "$HOME/.local/bin/dnsmasq"
            command -v dnsmasq | sed 's/^/PATH finds: /'
            """
        Then its output contains "PATH finds: /home/"
        When a task called "fixedpath" is started in "guarded" for the "stub" agent and left running
        And a script runs about the task:
            """
            readlink "/proc/$(cut -d' ' -f1 {state}/dnsmasq.pid)/exe" | sed 's/^/resolver: /'
            porelo doctor 2>/dev/null | grep 'dnsmasq nftset' | sed 's/  */ /g'
            rm -f "$HOME/.local/bin/dnsmasq"
            """
        # /usr/sbin on Debian and Ubuntu; Fedora's /usr/sbin is a link to /usr/bin, which the kernel names.
        Then its output has a line matching "resolver: /usr/s?bin/dnsmasq"
        And its output contains "dnsmasq nftset yes"

    Scenario: a start whose tree holds a private key is refused before any container, where the project says refuse
        # The key's header is put together here, so this file is no finding of any scanner.
        When a script runs:
            """
            cd ~/guarded
            sed -i 's/^  security_class: "guarded"$/&\n  secrets: "refuse"/' project.yml
            mkdir -p deploy && printf '%s\n' "-----BEGIN OPENSSH ""PRIVATE KEY-----" "b3BlbnNzaC1rZXkt" > deploy/key
            git add -A && git commit -q -m "a key"
            timeout 600 porelo task start keyed --project guarded --repository guarded --agent stub --detach --clearance deny 2>&1
            echo "start exit $?"
            podman ps -a --format '{{.Names}}' | grep -c 'keyed' | sed 's/^/containers: /'
            """
        Then its output contains "secret         deploy/key:1  looks like a private key; the agent can read it"
        And its output contains "(secrets: refuse)"
        And its output contains "start exit 65"
        And its output contains "containers: 0"
        And its output does not contain "b3BlbnNzaC1rZXkt"
