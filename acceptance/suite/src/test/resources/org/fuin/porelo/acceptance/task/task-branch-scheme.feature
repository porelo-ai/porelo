@slow
Feature: A task's branch named by the project's scheme

    A project file may say how a task's branch at the forge is named and which names the forge accepts. An online task's
    push then arrives at its upstream under that name, settled at the start and kept with the task; a branch the pattern
    refuses is refused before anything is made. The upstream here is a repository on the machine.

    Scenario: an online task's push arrives at the upstream under the project's branch name
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And an online project called "scheme" pushing to a repository of its own, whose project file also says:
            """
            branch:
              template: "fix/porelo/{task}"
              pattern: "^fix/porelo/[a-z0-9-]+$"
            """
        When a task is started in "scheme" for the "stub" agent and left running
        And a script runs about the task:
            """
            porelo task status {task} | awk '$1 == "forge" { print "forge " $2 }'
            podman exec {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "acceptance: scheme" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"' \
                && echo "pushed"
            sleep 2
            git --git-dir ~/scheme/.git/upstream.git for-each-ref --format='upstream %(refname)' refs/heads/
            """
        Then its output has a line matching "forge fix/porelo/[a-z0-9-]+"
        And its output contains "pushed"
        And its output has a line matching "upstream refs/heads/fix/porelo/[a-z0-9-]+"
        And its output does not contain "refs/heads/porelo/"

    Scenario: a branch the project's pattern refuses is refused before anything is made
        Given the suite runs as an unprivileged user
        And an online project called "narrow" pushing to a repository of its own, whose project file also says:
            """
            branch:
              template: "fix/porelo/{task}"
              pattern: "^fix/porelo/[a-z0-9-]+$"
            """
        When a script runs "porelo task start wide -p narrow -r narrow --agent stub --branch main --attach shell --detach --clearance deny"
        Then it exits non-zero
        And its output contains "is not one the project's branch.pattern"
        And its output contains "nothing was created"
