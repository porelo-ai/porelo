Feature: Signing a repository's file from the checkouts a person keeps

    A person names once the checkouts they keep of the organization's repository and of a project's
    repository. Signing a repository's porelo.yml from its checkout then reads those checkouts where
    they stand, with no project taken on the machine.

    Scenario: the checkouts are named once and kept, and a directory that is no checkout is refused
        When a script runs:
            """
            set -e
            work=$(mktemp -d)
            for name in org demo; do
                git init -q -b main "$work/$name"
                git -C "$work/$name" -c user.name=Person -c user.email=person@example.org \
                    commit -q --allow-empty -m first
            done
            porelo setup --organization-checkout "$work/org" --project-checkout "demo=$work/demo" --json
            cat "${XDG_CONFIG_HOME:-$HOME/.config}/porelo/checkouts.yml"
            mkdir "$work/plain"
            if porelo setup --organization-checkout "$work/plain" --json; then
                echo "a plain directory was taken"
            else
                echo "plain refused: $?"
            fi
            grep -q "$work/org" "${XDG_CONFIG_HOME:-$HOME/.config}/porelo/checkouts.yml" && echo "organization kept"
            rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/porelo/checkouts.yml"
            rm -rf "$work"
            """
        Then it exits zero
        And its output contains "demo: "
        And its output contains "no git checkout"
        And its output contains "plain refused: 64"
        And its output contains "organization kept"

    Scenario: signing from a checkout says what is missing before it reads anything
        When a script runs:
            """
            work=$(mktemp -d)
            git init -q -b main "$work/web"
            git -C "$work/web" -c user.name=Person -c user.email=person@example.org \
                commit -q --allow-empty -m first
            rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/porelo/checkouts.yml"
            porelo project sign-repository-file -C "$work/web" --json
            echo "exit: $?"
            porelo project sign-repository-file -C "$work/web" --yes --base "$(git -C "$work/web" rev-parse HEAD)" --json
            echo "exit: $?"
            rm -rf "$work"
            """
        Then it exits zero
        And its output contains "names no checkout of the organization's repository"
        And its output contains "a checkout's main is signed as read"
        And its output contains "exit: 64"
        And its output does not contain "organization-projects"
