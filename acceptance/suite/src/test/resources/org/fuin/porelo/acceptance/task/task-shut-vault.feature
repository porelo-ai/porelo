@slow
Feature: A task started on a shut vault at a terminal

    At a terminal, a start that needs a shut vault asks for the passphrase itself and carries on; nobody is sent off
    to type 'porelo vault unlock' and then the start again. The answer unlocks the vault as 'porelo vault unlock'
    does, because the broker spends the task's tokens for the task's life. Without a terminal the same start still
    refuses and names 'porelo vault unlock' (task-refused.feature).

    # Guards a command doing the wrong thing: a start that refuses where it can ask, or that opens the vault for
    # itself only and leaves the task's broker without its credential.
    Scenario: a start on a shut vault asks for the passphrase, starts the task and leaves the vault open
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "shut" of class "guarded" with a file in it
        And a terminal on the machine
        When I run "porelo vault lock"
        And I run "porelo task start shell --project shut --repository shut --agent stub --attach shell --detach --clearance deny"
        Then within 60 seconds the terminal shows "Vault passphrase"
        When I type "acceptance"
        Then within 600 seconds this script exits zero:
            """
            podman ps --format '{{.Names}}' | grep -q '^porelo-shut-shell$'
            """
        # Asked by a script, with no terminal to ask at: only a vault left open answers, which the task's broker needs.
        When a script runs "porelo vault list"
        Then it exits zero
        And its output contains "anthropic"
        And a script runs "porelo task remove porelo-shut-shell --force"
