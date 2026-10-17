@slow @restart
Feature: After the machine restarts, a task goes on with the conversation it was having

    A reboot stops every container and empties the runtime directory, and the conversation an agent was
    having is what a person least wants to lose with it. A task somebody drove at a terminal comes back
    with 'porelo task start --restarted' and continues its agent's session; a task started with a prompt
    and left to run is never started again on its own - it stays stopped, listed as interrupted by the
    restart, its session kept - and its next start continues the conversation. An agent that declares no
    session id begins fresh after the reboot and says so.

    The stub is the agent: it keeps its session's first prompt, or the first line typed in it, and a
    continued session says that back - something only the earlier turn holds, which a fresh session handed
    a stale id cannot know. A copy of it installed as 'porelo-agent-stub-sessionless' is the same stub
    declaring no session id. This reboots the machine, so it runs on a rented one only.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        # In the home directory: a task's own tokens are kept in the vault, and the temporary directory may not
        # survive the reboot.
        And a vault of this scenario's own that a restart keeps, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"

    Scenario: each task continues its conversation after a reboot, and the run nobody watches waits for its next start
        # Whatever a stopped run of this file left, so this one starts from a state somebody chose.
        Given a script runs:
            """
            for task in talk attended fresh; do porelo task remove porelo-recall-$task --force >/dev/null 2>&1; done
            rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/agents/porelo-agent-stub-sessionless"
            """
        And a project called "recall" of class "guarded" with a file in it
        When a script runs:
            """
            from=$(porelo agents | awk '$1 == "stub" { print $NF }')
            into="${XDG_DATA_HOME:-$HOME/.local/share}/porelo/agents"
            mkdir -p "$into" && cp "$from" "$into/porelo-agent-stub-sessionless"
            porelo agents | awk '$1 == "stub-sessionless" { print "staged " $1 }'
            """
        Then its output contains "staged stub-sessionless"
        # The earlier turns: a run nobody watches is given a word, ...
        When a script runs "timeout 600 porelo task start talk --project recall --repository recall --agent stub --prompt lantern"
        Then porelo says task "talk" of "recall" last said "stub: finished"
        And porelo says task "talk" of "recall" has a session to continue
        # ... so is a run of the agent that names no session, ...
        When a script runs "timeout 600 porelo task start fresh --project recall --repository recall --agent stub-sessionless --prompt harbor"
        Then its output contains "session   a fresh one - this agent does not say how to continue one"
        And porelo says task "fresh" of "recall" last said "stub: finished"
        # ... and a person types one to an attached agent.
        Given a terminal on the machine
        When I run "porelo task start attended --project recall --repository recall --agent stub --clearance deny"
        Then the "stub" agent in task "attended" of "recall" reaches work without being asked anything
        When I enter "compass"
        Then the screen of task "attended" of "recall" shows "stub: noted compass"

        When the machine restarts
        And the vault is unlocked with the passphrase "acceptance"
        And a script runs "porelo task start --restarted"
        # The attached task comes back; the two runs nobody watches do not.
        Then its output contains "back      porelo-recall-attended"
        And its output contains "stays     porelo-recall-talk: its run was interrupted by the restart; its session is kept"
        And its output contains "stays     porelo-recall-fresh: its run was interrupted by the restart"
        And its output does not contain "back      porelo-recall-talk"
        And its output does not contain "not back  porelo-recall-attended"
        And a script running "podman inspect --format 'running={{.State.Running}}' porelo-recall-talk" mentions "running=false"
        When a script runs "porelo task list"
        Then its output has a line matching "^interrupted by the machine's restart, staying stopped: .*porelo-recall-talk.*"
        # Not under the line that says '--restarted' brings it back.
        When a script runs "porelo task list | grep '^down because the machine restarted' | grep -c porelo-recall-talk"
        Then its output has a line matching "^0$"
        And porelo says task "talk" of "recall" has a session to continue

        # The attached agent continues the session the person typed into.
        Given a terminal on the machine
        When I run "porelo task start attended --project recall --repository recall --agent stub --clearance deny"
        Then the screen of task "attended" of "recall" shows "remembering compass"
        # The run nobody watches continues at its next start, and knows the word only its first run was given.
        When a task called "talk" nobody is watching is started in "recall" for the "stub" agent and ends within 600 seconds
        Then its output contains "session   continuing "
        And porelo says task "talk" of "recall" last said "stub: continued, remembering lantern"
        # The agent that names no session begins fresh, and nothing claims a continuation.
        When a task called "fresh" nobody is watching is started in "recall" for the "stub-sessionless" agent and ends within 600 seconds
        Then its output contains "session   a fresh one - this agent does not say how to continue one"
        And its output does not contain "continuing"
        And porelo says task "fresh" of "recall" last said "stub: finished"
        And a script runs:
            """
            for task in talk attended fresh; do porelo task remove porelo-recall-$task --force; done
            rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/agents/porelo-agent-stub-sessionless"
            """
