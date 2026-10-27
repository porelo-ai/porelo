Feature: A repository names the agent it is worked with

    A project file may name, for each repository, the agent it is worked with and a one-word nickname.
    A start on that repository without --agent takes that agent and says so; the project's listing
    carries both with the name the agent goes by, and a task's commits are authored by that name.

    Background:
        Given the suite runs as an unprivileged user
        And a project called "named" of class "guarded" whose project file also says:
            """
            egress:
              sets: []
            """

    Scenario: a start without --agent takes the repository's agent, and the listing says whom it goes by
        When a script runs:
            """
            set -e
            source=~/named/.git/tested-source
            rm -rf "$source" && git init -q -b main "$source"
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q --allow-empty -m first
            printf '%s\n' 'repositories:' '  tested:' "    upstream: \"file://$source\"" \
                '    agent: "stub"' '    nickname: "Tester"' '  plain:' '    agent: "stub"' >> ~/named/project.yml
            git -C ~/named add project.yml && git -C ~/named commit -q -m named
            porelo project follow named ~/named --unverified > /dev/null
            porelo task start --project named --repository tested --dry-run --clearance deny
            porelo project list --json | python3 -c 'import json, sys
            for project in json.load(sys.stdin)["projects"]:
                if project["name"] == "named":
                    for repository in project["repositories"]:
                        print("goes by", repository["name"], repository["agent"], repository["goesBy"])'
            """
        Then it exits zero
        And its output has a line matching "agent +stub \(named for the repository\)"
        And its output contains "goes by tested stub Agent Tester"
        And its output contains "goes by plain stub Agent plain"
        And its output contains "goes by named  Agent named"

    @slow
    Scenario: a task's commit is authored by the name its agent goes by
        When a script runs:
            """
            set -e
            source=~/named/.git/tested-source
            rm -rf "$source" && git init -q -b main "$source"
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q --allow-empty -m first
            printf '%s\n' 'repositories:' '  tested:' "    upstream: \"file://$source\"" \
                '    agent: "stub"' '    nickname: "Tester"' '  plain:' '    agent: "stub"' >> ~/named/project.yml
            git -C ~/named add project.yml && git -C ~/named commit -q -m named
            porelo project follow named ~/named --unverified > /dev/null
            timeout 600 porelo task start authored --project named --repository tested \
                --attach shell --detach --clearance deny > /dev/null
            podman exec porelo-named-authored sh -c 'cd /workspace && echo work > work.txt \
                && git add work.txt && git commit -q -m work && git log -1 --format="author %an <%ae>"'
            porelo task remove porelo-named-authored --force > /dev/null
            """
        Then it exits zero
        And its output contains "author Agent Tester <agent@localhost>"
