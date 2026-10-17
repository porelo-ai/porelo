@slow
Feature: Work waiting at the gate, reviewed, backed up and restored

    What an agent pushed exists in the gate's mirror and nowhere else until somebody approves it, so
    the mirror is the one copy of that work. This hands the gate a real push from a real task and then
    does to it what a reviewer does. One scenario, because a second push to the same task's ref would
    be refused as not a fast-forward.

    Scenario: a push waits for review, names what is dangerous first, opens where nothing in it runs, and its mirror is backed up
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "gated" of class "guarded" with a file in it
        When a task called "reviewed" is started in "gated" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec {task} sh -c 'cd /workspace && echo change >> README.md && mkdir -p .github/workflows && echo "on: push" > .github/workflows/ci.yml && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: a change to review" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
            """
        Then it exits zero
        When a script runs "porelo gate pending --project gated"
        Then its output has a line matching "reviewed +\S+ +[0-9a-f]+ +acceptance: a change to review"
        # A one-line workflow beside an ordinary change: the workflow is what a reviewer must meet first,
        # however small, and the patch follows the same order.
        When a script runs "porelo gate review reviewed --project gated"
        Then it exits zero
        And its output contains "asked: "
        And its output contains "READ FIRST - dangerous by kind, however small:"
        And its output has a line matching "A  \.github/workflows/ci\.yml  \+1 -0  a CI definition.*"
        And its output has a line matching "M  README\.md  \+1 -0"
        When a script runs:
            """
            dir=$(mktemp -d)
            porelo gate checkout nope --project gated --into "$dir/nope"; echo "exit $?"
            test -e "$dir/nope" && echo "a directory was made for nothing"
            porelo gate checkout reviewed --project gated --into "$dir/reviewed"
            grep -c change "$dir/reviewed/README.md" | sed 's/^/lines changed: /'
            rm -rf "$dir"
            """
        Then its output contains "There is no pending push named 'nope'"
        And its output does not contain "a directory was made for nothing"
        And its output contains "opened"
        And its output contains "hooks     off in this copy"
        And its output contains "lines changed: 1"
        # A restore that wrote over the mirror would discard whatever was pushed since the backup.
        When a script runs:
            """
            dir=$(mktemp -d)
            porelo gate backup "$dir/gated.bundle" --project gated
            porelo gate restore "$dir/gated.bundle" --project gated; echo "exit $?"
            porelo gate restore "$dir/missing.bundle" --project gated; echo "exit $?"
            rm -rf "$dir"
            """
        Then its output contains "backed up"
        And its output contains "verified  true"
        And its output contains "A mirror already exists"
        And its output contains "No bundle at"
        And its output does not contain "exit 0"
        And a script runs "porelo task remove {task} --force" about the task

    # Guards a reviewer throwing away, as unreviewed, work somebody already carried to the upstream by hand: past the
    # gate, the push still waits there, and a reject would record work the upstream has as discarded. The upstream is
    # a bare repository beside the project; the hand push is plain git from the gate's mirror.
    Scenario: work pushed to the upstream by hand is marked already upstream, and is rejected only with --force
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a daemon of this scenario's own
        # Guarded, so its work waits at the gate: an online task's push is passed on at once and never waits. Its
        # upstream is a bare repository beside it, named in its project file and followed again.
        And a project called "carried" of class "guarded" with a file in it
        And a script runs:
            """
            set -e
            git init -q --bare -b main ~/carried/.git/upstream.git
            git -C ~/carried push -q ~/carried/.git/upstream.git HEAD:main
            sed -i "/^project:/a\  upstream: \"file://$HOME/carried/.git/upstream.git\"" ~/carried/project.yml
            git -C ~/carried commit -qam "name the upstream"
            porelo project follow carried ~/carried --unverified >/dev/null
            """
        Then it exits zero
        When a task called "past" is started in "carried" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec {task} sh -c 'cd /workspace && echo carried >> README.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work carried past the gate" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
            """
        Then it exits zero
        When a script runs:
            """
            mirror="${XDG_DATA_HOME:-$HOME/.local/share}/porelo/mirrors/carried.git"
            git --git-dir "$mirror" push -q "$HOME/carried/.git/upstream.git" refs/porelo/incoming/past:refs/heads/main
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.SyncUpstream","parameters":{"project":"carried"}}' \
                | timeout 120 porelo daemon connect | tr -d '\0'
            """
        Then its output contains "MEASURED"
        When a script runs "porelo gate pending --project carried"
        Then its output has a line matching "past .*already upstream.*"
        When a script runs "porelo gate reject past --project carried"
        Then it exits non-zero
        And its output contains "--force"
        When a script runs "porelo gate pending --project carried"
        Then its output has a line matching "past .*already upstream.*"
        And a script runs "porelo task remove {task} --force" about the task
