@slow
Feature: A repository says what its tasks are built in

    A repository's own porelo.yml chooses the image a task on that repository is built from. It is
    read from the repository's mirror at its default branch, which holds reviewed work only, and it
    may name nothing but the image: a file that tried to name a rule of the project is refused before
    anything is built. The start says where each part of the image came from, and a repository's own
    image is named apart from the project's.

    Background:
        Given the suite runs as an unprivileged user
        And a project called "imaged" of class "guarded" whose project file also says:
            """
            egress:
              sets: []
            """

    Scenario: a repository's own file shapes the image of a task on it, and the start names it
        When a script runs:
            """
            set -e
            source=~/imaged/.git/tooled-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  snippet: |' '    RUN echo from-the-repository > /etc/porelo-repository-image' \
                '  env:' '    REPOSITORY_IMAGE: "said by the repository"' > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m image
            printf '%s\n' 'repositories:' '  tooled:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m tooled
            porelo project follow imaged ~/imaged --unverified > /dev/null
            timeout 600 porelo task start shaped --project imaged --repository tooled --agent stub \
                --attach shell --detach --clearance deny
            """
        Then it exits zero
        And its output has a line matching "image lines +from porelo.yml at refs/heads/main of tooled.*"
        And its output has a line matching "image env +REPOSITORY_IMAGE from porelo.yml at refs/heads/main of tooled.*"
        And its output has a line matching "task image +porelo/imaged_tooled"
        When a script runs:
            """
            podman exec porelo-imaged-shaped sh -c 'cat /etc/porelo-repository-image; echo "env: $REPOSITORY_IMAGE"'
            porelo task remove porelo-imaged-shaped --force > /dev/null
            podman rmi porelo/imaged_tooled > /dev/null 2>&1 || true
            """
        Then it exits zero
        And its output contains "from-the-repository"
        And its output contains "env: said by the repository"

    Scenario: a repository's file that names a rule of the project is refused before anything is built
        When a script runs:
            """
            set -e
            source=~/imaged/.git/widened-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  base_image: "ubuntu:24.04"' 'egress:' '  domains: ["example.com"]' > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m widen
            printf '%s\n' 'repositories:' '  widened:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m widened
            porelo project follow imaged ~/imaged --unverified > /dev/null
            set +e
            timeout 600 porelo task start widening --project imaged --repository widened --agent stub \
                --attach shell --detach --clearance deny
            echo "exit $?"
            podman image exists porelo/imaged_widened && echo "an image was built" || echo "no image was built"
            porelo task remove porelo-imaged-widening --force > /dev/null 2>&1 || true
            """
        Then its output contains "'egress' cannot be set here; only image may be set here"
        And its output has a line matching "exit [1-9][0-9]*"
        And its output contains "no image was built"
