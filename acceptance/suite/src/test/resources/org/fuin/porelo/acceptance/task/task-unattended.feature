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
            log="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/porelo-unattended_headless/task.log"
            test -s "$log" && echo "the agent's output was kept" || echo "nothing was kept at $log"
            porelo task remove porelo-unattended_headless --force >/dev/null 2>&1
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

    Scenario: the daemon says what an agent was refused, and runs one with a prompt and answers with its result
        Given a daemon of this scenario's own
        When a script runs:
            """
            ask() { printf '%s\0' "$1" | timeout 600 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
            # The stub declares example.net and is deliberately not given it - the name the resolver refuses.
            ask '{"method":"org.fuin.porelo.Tasks1.Agents","parameters":{}}' | grep -oE '"refusedDomains":\["example.net"\]'
            reply=$(ask '{"method":"org.fuin.porelo.Tasks1.Start","parameters":{"task":"viadaemon","project":"unattended","agent":"stub","repository":"unattended","prompt":"say hello and stop","clearance":"deny","keep":true}}')
            # The reply names the container once and again in each of its steps: one name is the path.
            container=$(printf '%s' "$reply" | grep -oE '"container":"[^"]*"' | head -1 | cut -d'"' -f4)
            echo "started: ${container:-nothing}"
            printf '%s' "$reply" | grep -oE '"result":"[^"]*"|"resultKind":"[^"]*"' | sed 's/^/answered: /'
            test -s "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/$container/task.log" && echo "the daemon ran the agent, not only the container"
            [ -n "$container" ] && porelo task remove "$container" --force >/dev/null 2>&1
            true
            """
        Then its output contains '"refusedDomains":["example.net"]'
        And its output contains "started: porelo-unattended_viadaemon"
        And its output contains "the daemon ran the agent, not only the container"
        And its output contains 'answered: "result":"stub: finished"'
        And its output contains 'answered: "resultKind":"ANSWER"'

    Scenario: a start through the relay is given its issue as a file, the call a window makes to a host's daemon
        # A window starts a task on a host through that host's own daemon, over the relay; once no
        # repository holds an issue file, the issue reaches the task only as a given file.
        Given a daemon of this scenario's own
        When a script runs:
            """
            work=$(mktemp -d)
            python3 - "$work" <<'PY'
            import json, sys
            work = sys.argv[1]
            issue = "# ISSUE-1 - the issue as the planner holds it\n" + "x" * (1000 * 1024) + "\n"
            open(work + "/issue.md", "w").write(issue)
            start = {"task": "givenwork", "project": "unattended", "agent": "stub", "repository": "unattended",
                     "prompt": "say hello and stop", "clearance": "deny", "keep": True,
                     "work": {"issue": "ISSUE-1", "phase": "Implementing"}, "given": {"issue.md": issue}}
            open(work + "/start.json", "w").write(json.dumps({"method": "org.fuin.porelo.Tasks1.Start", "parameters": start}))
            start["task"] = "givenrefused"
            start["given"] = {"../issue.md": "text"}
            open(work + "/refused.json", "w").write(json.dumps({"method": "org.fuin.porelo.Tasks1.Start", "parameters": start}))
            PY
            ask() { { cat "$1"; printf '\0'; } | timeout 600 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
            reply=$(ask "$work/start.json")
            container=$(printf '%s' "$reply" | grep -oE '"container":"[^"]*"' | head -1 | cut -d'"' -f4)
            echo "started: ${container:-nothing}"
            [ -n "$container" ] || printf 'the reply began: %.200s\n' "$reply"
            # What the task is given is a mount: read where the host keeps it and how it reaches the task,
            # which holds whether or not the agent's run has ended.
            mount=$(podman inspect --format '{{range .Mounts}}{{if eq .Destination "/run/porelo/given"}}{{.Source}} {{.RW}}{{end}}{{end}}' "$container" 2>/dev/null)
            source=${mount% *}
            if [ -n "$container" ] && [ -n "$source" ] && [ "$(sha256sum < "$source/issue.md" | cut -d' ' -f1)" = "$(sha256sum < "$work/issue.md" | cut -d' ' -f1)" ]; then
                echo "the task is given its issue whole"
            fi
            [ -n "$container" ] && [ "${mount##* }" = "false" ] && echo "the task cannot change it"
            refused=$(ask "$work/refused.json")
            if printf '%s' "$refused" | grep -qE '"exitCode":64'; then
                echo 'a name that leaves the directory: "exitCode":64'
                podman container exists porelo-unattended_givenrefused 2>/dev/null || echo "nothing was made for it"
            fi
            [ -n "$container" ] && porelo task remove "$container" --force >/dev/null 2>&1
            rm -rf "$work"
            true
            """
        Then its output contains "started: porelo-unattended_givenwork"
        And its output contains "the task is given its issue whole"
        And its output contains "the task cannot change it"
        And its output contains 'a name that leaves the directory: "exitCode":64'
        And its output contains "nothing was made for it"

    Scenario: a task's logs are kept in one archive readable by the account only, without the agent's words unasked
        When a script runs:
            """
            porelo task start archived --project unattended --repository unattended --agent stub --clearance deny -P "say hello and stop" >/dev/null 2>&1
            base=$(mktemp -d); cd "$base"
            porelo task logs porelo-unattended_archived --zip > "$base/said" 2>&1; echo "zip exit $?"
            archive=$(ls porelo-logs-unattended_archived-*.zip)
            stat -c '%a' "$archive" | sed 's/^/mode: /'
            unzip -Z1 "$archive" | sed 's/^/entry: /'
            mkdir out && (cd out && unzip -q "../$archive" && cd unattended_archived && sha256sum -c --quiet SHA256SUMS && echo "sums hold")
            porelo task logs porelo-unattended_archived --zip "$archive" > /dev/null 2>&1; echo "again exit $?"
            porelo task logs porelo-unattended_archived --zip with-agent.zip --with-agent-output > /dev/null 2>&1
            unzip -Z1 with-agent.zip | grep -c 'task.log$' | sed 's/^/agent output when asked: /'
            porelo task remove porelo-unattended_archived --force >/dev/null 2>&1
            """
        Then it exits zero
        And its output contains "zip exit 0"
        And its output contains "mode: 600"
        And its output contains "entry: unattended_archived/status.txt"
        And its output contains "entry: unattended_archived/SHA256SUMS"
        And its output does not contain "entry: unattended_archived/task.log"
        And its output contains "sums hold"
        And its output contains "again exit 70"
        And its output contains "agent output when asked: 1"

    Scenario: a run's time limit ends it though the daemon that waited on it was lost
        Given a daemon of this scenario's own
        When a script runs:
            """
            # Asked in the background: the run waits in the daemon, and its connection ends with it.
            (printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.Start","parameters":{"task":"limited","project":"unattended","agent":"stub","repository":"unattended","prompt":"stub sleeps 600","minutes":1,"clearance":"deny","keep":true}}' \
                | timeout 600 porelo daemon connect >/dev/null 2>&1 &)
            c=porelo-unattended_limited; state="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/$c"
            for i in $(seq 300); do grep -q '"until"' "$state/run-limit.json" 2>/dev/null && podman top "$c" args 2>/dev/null | grep -q 'sleep 600' && break; sleep 1; done
            grep -q '"until"' "$state/run-limit.json" && echo "the deadline is kept"
            podman top "$c" args | grep -q 'sleep 600' && echo "the run is under way"
            """
        Then its output contains "the deadline is kept"
        And its output contains "the run is under way"
        When the daemon crashes and systemd restarts it
        And a script runs:
            """
            c=porelo-unattended_limited; state="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/porelo/$c"
            podman top "$c" args 2>/dev/null | grep -q 'sleep 600' && echo "the agent outlived the daemon"
            # The deadline is a minute after the start, and the daemon looks once a minute.
            for i in $(seq 240); do podman ps --filter "name=^$c\$" --format '{{.Status}}' | grep -q '^Up' || break; sleep 1; done
            podman ps -a --filter "name=^$c\$" --format '{{.Status}}' | sed 's/^/container: /'
            test -s "$state/ended-by-limit.json" && echo "ended by its limit"
            porelo task status "$c" 2>/dev/null | grep -o 'its time limit of 1 minutes passed'
            porelo task remove "$c" --force >/dev/null 2>&1
            true
            """
        Then its output contains "the agent outlived the daemon"
        And its output contains "container: Exited"
        And its output contains "ended by its limit"
        And its output contains "its time limit of 1 minutes passed"
