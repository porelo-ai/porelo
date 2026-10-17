@slow
Feature: An unattended run, started at the terminal and through the daemon

    The two ways a person or an interface starts an agent with a prompt: 'task start -P', and the
    daemon's Start. They share one method, and "they share a method" is an argument, not a
    measurement - the daemon's half once answered "started" for a run nobody performed. The agent
    authenticates with a fake credential and fails; what is measured is that it ran and its output was
    kept.

    The daemon is this scenario's own, reading this scenario's vault: the account's daemon would answer
    about the account's credential. The account's daemon is started again afterwards.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "unattended" of class "guarded" with a file in it

    Scenario: a prompt given at the terminal runs the agent and keeps what it said
        When a script runs:
            """
            porelo task start headless --project unattended --repository unattended --agent stub --clearance deny -P "say hello and stop" >/dev/null 2>&1
            log="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/porelo-unattended-headless/task.log"
            test -s "$log" && echo "the agent's output was kept" || echo "nothing was kept at $log"
            porelo task remove porelo-unattended-headless --force >/dev/null 2>&1
            """
        Then its output contains "the agent's output was kept"

    Scenario: the daemon says whether work can start, and why not, before anything is started
        Given a daemon of this scenario's own
        When a script runs:
            """
            ask() { printf '%s\0' "$1" | timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
            # Asked without a repository, it has to answer about everything else first: being told to choose
            # one means nothing else is in the way.
            ask '{"method":"org.fuin.porelo.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub"}}' \
                | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/without a repository: /'
            # The name it looked for is the provider's: a credential belongs to whoever issued it.
            ask '{"method":"org.fuin.porelo.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub","repository":"unattended"}}' \
                | grep -oE '"(outcome|credential)":"[^"]*"' | sed 's/^/with one: /'
            ask '{"method":"org.fuin.porelo.Tasks1.CanStart","parameters":{"agent":"not-installed"}}' \
                | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/an agent nobody installed: /'
            # A locked vault is not a missing credential: unlocking is the action, storing one is not.
            porelo vault lock >/dev/null
            ask '{"method":"org.fuin.porelo.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub","repository":"unattended"}}' \
                | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/locked: /'
            """
        Then it exits zero
        And its output contains 'without a repository: "outcome":"NO_REPOSITORY_CHOSEN"'
        And its output contains 'with one: "outcome":"READY"'
        And its output contains 'with one: "credential":"anthropic"'
        And its output contains 'an agent nobody installed: "outcome":"UNKNOWN_AGENT"'
        And its output contains 'locked: "outcome":"VAULT_LOCKED"'

    Scenario: the daemon says what an agent was refused, and runs one with a prompt
        Given a daemon of this scenario's own
        When a script runs:
            """
            ask() { printf '%s\0' "$1" | timeout 600 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
            # The stub declares example.net and is deliberately not given it - the name the resolver refuses.
            ask '{"method":"org.fuin.porelo.Tasks1.Agents","parameters":{}}' | grep -oE '"refusedDomains":\["example.net"\]'
            reply=$(ask '{"method":"org.fuin.porelo.Tasks1.Start","parameters":{"task":"viadaemon","project":"unattended","agent":"stub","repository":"unattended","prompt":"say hello and stop","clearance":"deny","keep":true}}')
            container=$(printf '%s' "$reply" | grep -oE '"container":"[^"]*"' | cut -d'"' -f4)
            echo "started: ${container:-nothing}"
            test -s "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/$container/task.log" && echo "the daemon ran the agent, not only the container"
            [ -n "$container" ] && porelo task remove "$container" --force >/dev/null 2>&1
            true
            """
        Then its output contains '"refusedDomains":["example.net"]'
        And its output contains "started: porelo-unattended-viadaemon"
        And its output contains "the daemon ran the agent, not only the container"

    Scenario: a task's logs are kept in one archive readable by the account only, without the agent's words unasked
        When a script runs:
            """
            porelo task start archived --project unattended --repository unattended --agent stub --clearance deny -P "say hello and stop" >/dev/null 2>&1
            base=$(mktemp -d); cd "$base"
            porelo task logs porelo-unattended-archived --zip > "$base/said" 2>&1; echo "zip exit $?"
            archive=$(ls porelo-logs-unattended-archived-*.zip)
            stat -c '%a' "$archive" | sed 's/^/mode: /'
            unzip -Z1 "$archive" | sed 's/^/entry: /'
            mkdir out && (cd out && unzip -q "../$archive" && cd unattended-archived && sha256sum -c --quiet SHA256SUMS && echo "sums hold")
            porelo task logs porelo-unattended-archived --zip "$archive" > /dev/null 2>&1; echo "again exit $?"
            porelo task logs porelo-unattended-archived --zip with-agent.zip --with-agent-output > /dev/null 2>&1
            unzip -Z1 with-agent.zip | grep -c 'task.log$' | sed 's/^/agent output when asked: /'
            porelo task remove porelo-unattended-archived --force >/dev/null 2>&1
            """
        Then it exits zero
        And its output contains "zip exit 0"
        And its output contains "mode: 600"
        And its output contains "entry: unattended-archived/status.txt"
        And its output contains "entry: unattended-archived/SHA256SUMS"
        And its output does not contain "entry: unattended-archived/task.log"
        And its output contains "sums hold"
        And its output contains "again exit 70"
        And its output contains "agent output when asked: 1"
