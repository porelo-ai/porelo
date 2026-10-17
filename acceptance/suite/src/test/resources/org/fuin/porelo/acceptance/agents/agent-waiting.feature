@slow
Feature: Porelo shows an agent waiting for a person, read by the agent's own declaration

    An agent declares what waiting for a person looks like in its own output; Porelo reads the screen tmux
    draws in an attached task, and the records an unattended run wrote, and says what that declaration
    finds - as a reading, never as a fact, and never by a rule of its own. The stub is the agent here: it
    declares its trust question, and with '.porelo-stub-asks' in its workspace it asks it. The steps ask
    Porelo, never the screen: an agent repository proves its own declaration the same way.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"

    Scenario: an attached agent that puts a question to the person is shown waiting
        Given a project called "waits" of class "guarded" with a file in it
        And a script runs:
            """
            cd ~/waits && touch .porelo-stub-asks && git add -A && git commit -q -m 'ask first' \
                && porelo project follow waits ~/waits --unverified >/dev/null
            """
        And a terminal on the machine
        When I run "porelo task start attended --project waits --repository waits --agent stub --clearance deny"
        Then porelo shows the "stub" agent in task "attended" of "waits" waiting for a person
        And a script runs "porelo task remove porelo-waits-attended --force"

    Scenario: an attached agent at work is shown not waiting, and stays so
        Given a project called "works" of class "guarded" with a file in it
        And a terminal on the machine
        When I run "porelo task start attended --project works --repository works --agent stub --clearance deny"
        Then the "stub" agent in task "attended" of "works" reaches work without being asked anything
        And porelo shows the "stub" agent in task "attended" of "works" not waiting for a person
        And a script runs "porelo task remove porelo-works-attended --force"

    Scenario: what a finished unattended run said last is shown without opening its log
        Given a project called "ran" of class "guarded" with a file in it
        When a task called "run" nobody is watching is started in "ran" for the "stub" agent and ends within 600 seconds
        Then porelo says task "run" of "ran" last said "stub: finished"
