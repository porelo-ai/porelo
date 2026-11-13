@slow
Feature: An offline project takes its code from a bundle and carries its work out in one

    A hand-written offline project has its code seeded into the gate from a bundle a person carried to
    the machine. What its task pushes must be in the bundle that carries the work out.

    # Guards work being lost when its only copy leaves the machine in a bundle.
    Scenario: a local offline project seeded from a bundle starts a task, and its work is backed up
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "carried" of class "offline" with a file in it
        When a script runs:
            """
            set -e
            rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/mirrors/carried.git"
            cd ~/carried
            echo 'the code' > code.txt
            git add code.txt && git commit -q -m code
            git bundle create .git/code.bundle HEAD main
            porelo gate restore "$HOME/carried/.git/code.bundle" --project carried
            """
        Then it exits zero
        When a task called "carried" is started in "carried" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'cd /workspace && cat code.txt && echo work > WORK.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work carried out" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
            """
        Then it exits zero
        And its output contains "the code"
        When a script runs about the task:
            """
            carried="$HOME/carried/.git"
            commit=$(podman exec --user agent {task} git -C /workspace rev-parse HEAD)
            porelo gate backup "$carried/out.bundle" --project carried
            git bundle list-heads "$carried/out.bundle" | grep -q "^$commit " && echo "the backup holds the task's commit"
            """
        Then its output contains "verified  true"
        And its output contains "the backup holds the task's commit"
        And a script runs about the task:
            """
            porelo task remove {task} --force
            porelo project remove carried --force
            """
        Then it exits zero
