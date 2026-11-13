@slow
Feature: The task list that stays open
    # --agent is named because this machine may have more than one installed; the stub stands in for any.

    'porelo task list --watch' draws the list on the alternate screen with the cursor hidden and redraws a row when
    the daemon's Watch says its task changed. Ctrl-C ends it, and the terminal it leaves is the one it found: its
    settings, the main screen and the cursor. Only a real terminal shows that, so it is asserted here.

    The scenario removes its task at the end, as the other scenarios with a task do. An agent's task is
    started only with the agent's credential in the vault, so the scenario puts one in a vault of its own.

    Scenario: a stopped task's row changes in the open list, and Ctrl-C leaves the terminal as it was
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "watched" with a file in it
        When a script runs "timeout 600 porelo task start shell --project watched --repository watched --agent stub --clearance deny --detach"
        Then it exits zero
        Given a terminal on the machine
        When I run "stty -a > stty-before-watch; porelo task list --watch --project watched"
        Then within 30 seconds the terminal shows "HELPERS"
        When a script runs "porelo task stop porelo-watched_shell"
        Then within 30 seconds the terminal shows "Exited ("
        When I press the key "Ctrl+C"
        And I run "stty -a | diff -q - stty-before-watch >/dev/null && printf 'SAME-%s\n' SETTINGS; rm -f stty-before-watch"
        Then the terminal shows "SAME-SETTINGS"
        And the terminal is back on its main screen with its cursor shown
        And a script runs "porelo task remove porelo-watched_shell --force"
