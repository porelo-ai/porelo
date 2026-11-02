Feature: The project commands

    A hand-written project is listed before any task starts in it. Removing a local project says
    what goes before it goes, and refuses a name this machine does not have.

    Scenario Outline: every project command answers for itself
        When a script runs "porelo project <command> --help"
        Then it exits zero

        Examples:
            | command |
            | remove  |
            | list    |

    Scenario: a set this machine does not have is refused when checking a draft
        Given a daemon of this scenario's own
        When a script runs:
            """
            printf '%s\n' 'project:' '  name: "misspelled"' '  security_class: "guarded"' 'image:' '  base_image: "ubuntu:24.04"' \
                'egress:' '  sets: [mvn]' \
                | python3 -c 'import json,sys; print(json.dumps({"method":"org.fuin.porelo.Tasks1.CheckProjectFile","parameters":{"text":sys.stdin.read()}}), end="\0")' \
                | timeout 60 porelo daemon connect | tr '\0' '\n' | head -1 \
                | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)["parameters"]["refusedHere"]))'
            """
        Then it exits zero
        And its output contains "an egress set this machine does not have"

    Scenario: a local project is listed before anything ran in it
        Given a project called "listed" of class "guarded" with a file in it
        When a script runs "porelo project list --json"
        Then it exits zero
        And its output contains "listed"
        And a script runs:
            """
            porelo project list --json | python3 -c 'import json,sys
            project = next(p for p in json.load(sys.stdin)["projects"] if p["name"] == "listed")
            print("source:", project["source"])'
            """
        Then it exits zero
        And its output contains "source: local"

    Scenario: a name this machine does not have is refused, with the names it has
        Given a project called "listed" of class "guarded" with a file in it
        When a script runs "porelo project remove not-a-project-here --dry-run"
        Then it exits non-zero
        And its output contains "no project called 'not-a-project-here'"
        And its output contains "listed"

    Scenario: removing a local project says what goes before it goes, and then the project is gone
        Given a project called "dropped" of class "guarded" with a file in it
        When a script runs "porelo project remove dropped --dry-run"
        Then it exits zero
        And its output contains "would remove dropped"
        When a script runs "porelo project list"
        Then its output contains "dropped"
        When a script runs "porelo project remove dropped"
        Then it exits zero
        When a script runs "porelo project list"
        Then its output does not contain "dropped"

    Scenario: making a deploy key is no longer a command
        # More write-capable keys leave access the forge no longer expects.
        When a script runs:
            """
            porelo credentials deploy-key; echo "deploy-key exit $?"
            """
        Then its output contains "has no command called 'deploy-key'"
        And its output contains "deploy-key exit 2"
