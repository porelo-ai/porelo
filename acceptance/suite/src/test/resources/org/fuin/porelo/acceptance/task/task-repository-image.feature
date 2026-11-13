@slow
Feature: A repository says what its tasks are built in

    A repository's own porelo.yml chooses the image a task on that repository is built from. It is
    read from the repository's mirror at its default branch, which holds reviewed work only, and it
    may name nothing but the image: a file that tried to name a rule of the project is refused before
    anything is built. The start says where each part of the image came from, and a repository's own
    image is named apart from the project's. Each source is taken whole: the person's override first,
    then the repository's file, then the project's image, and nothing of a later one is mixed in.
    Nobody signs a hand-written project's repository file, and its lines run as root while the image
    is built, so a start builds them only once a person on this machine allowed them: asked at a
    terminal, refused with nobody to answer, and asked again when the file changes. In 'default' the
    checkout's own files are asked for the same way, their image among their settings.

    Background:
        Given the suite runs as an unprivileged user
        And a project called "imaged" of class "guarded" whose project file also says:
            """
            egress:
              sets: []
            """

    Scenario: a repository's own file shapes the image of a task on it once allowed, and the start names it
        When a script runs:
            """
            set -e
            source=~/imaged/.git/tooled-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  run: |' \
                '    RUN echo from-the-repository > /etc/porelo-repository-image' \
                '  env:' '    REPOSITORY_IMAGE: "said by the repository"' > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m image
            printf '%s\n' 'repositories:' '  tooled:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m tooled
            set +e
            timeout 600 porelo task start shaped --project imaged --repository tooled --agent stub \
                --attach shell --detach --clearance deny --no-input
            echo "unanswered exit $?"
            podman image exists porelo/imaged_tooled && echo "an image was built" || echo "no image was built"
            """
        Then its output has a line matching "unanswered exit [1-9][0-9]*"
        And its output contains "+ image.run[1] = RUN echo from-the-repository > /etc/porelo-repository-image"
        And its output contains "no image was built"
        Given a terminal on the machine
        When I run "porelo task start shaped --project imaged --repository tooled --agent stub --attach shell --detach --clearance deny; echo start-exit-$?"
        Then the terminal shows "Allow them for tooled of imaged on this machine?"
        When I type "y"
        Then within 600 seconds the terminal shows "start-exit-0"
        And the terminal shows "from porelo.yml at refs/heads/main of tooled"
        And the terminal shows "porelo/imaged_tooled"
        When a script runs:
            """
            podman exec --user agent porelo-imaged_shaped sh -c 'cat /etc/porelo-repository-image; echo "env: $REPOSITORY_IMAGE"'
            porelo task remove porelo-imaged_shaped --force > /dev/null
            podman rmi porelo/imaged_tooled > /dev/null 2>&1 || true
            """
        Then it exits zero
        And its output contains "from-the-repository"
        And its output contains "env: said by the repository"

    Scenario: a repository's image copies from an image pinned by its digest
        When a script runs:
            """
            set -e
            podman image exists ubuntu:24.04 || podman pull -q ubuntu:24.04 > /dev/null
            pinned=$(podman image inspect ubuntu:24.04 --format '{{index .RepoDigests 0}}')
            case "$pinned" in *@sha256:*) ;; *) echo "no digest for ubuntu:24.04: $pinned"; exit 1 ;; esac
            source=~/imaged/.git/pinned-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  run: |' \
                "    COPY --from=$pinned /etc/os-release /etc/porelo-copied-os-release" > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m image
            printf '%s\n' 'repositories:' '  pinned:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m pinned
            """
        Then it exits zero
        Given a terminal on the machine
        When I run "porelo task start copied --project imaged --repository pinned --agent stub --attach shell --detach --clearance deny; echo start-exit-$?"
        Then the terminal shows "Allow them for pinned of imaged on this machine?"
        When I type "y"
        Then within 600 seconds the terminal shows "start-exit-0"
        And the terminal shows "from porelo.yml at refs/heads/main of pinned"
        When a script runs:
            """
            podman exec --user agent porelo-imaged_copied sh -c 'grep -c "^ID=ubuntu" /etc/porelo-copied-os-release'
            porelo task remove porelo-imaged_copied --force > /dev/null
            podman rmi porelo/imaged_pinned > /dev/null 2>&1 || true
            """
        Then it exits zero
        And its output contains "1"

    Scenario: a repository's file that names a rule of the project is refused before anything is built
        When a script runs:
            """
            set -e
            source=~/imaged/.git/widened-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' 'egress:' '  domains: ["example.com"]' > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m widen
            printf '%s\n' 'repositories:' '  widened:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m widened
            set +e
            timeout 600 porelo task start widening --project imaged --repository widened --agent stub \
                --attach shell --detach --clearance deny
            echo "exit $?"
            podman image exists porelo/imaged_widened && echo "an image was built" || echo "no image was built"
            porelo task remove porelo-imaged_widening --force > /dev/null 2>&1 || true
            """
        Then its output contains "'egress' cannot be set here; only image may be set here"
        And its output has a line matching "exit [1-9][0-9]*"
        And its output contains "no image was built"

    Scenario: a person's local override adds to the repository's image, its own values winning
        When a script runs:
            """
            set -e
            source=~/imaged/.git/tooled-source
            rm -rf "$source" && git init -q -b main "$source"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  run: |' \
                '    RUN echo from-the-repository > /etc/porelo-repository-image' \
                '  env:' '    REPOSITORY_IMAGE: "said by the repository"' '    KEPT: "the repository'"'"'s"' > "$source/porelo.yml"
            git -C "$source" -c user.name=T -c user.email=t@example.com add porelo.yml
            git -C "$source" -c user.name=T -c user.email=t@example.com commit -q -m image
            printf '%s\n' 'repositories:' '  tooled:' "    upstream: \"file://$source\"" >> ~/imaged/project.yml
            git -C ~/imaged add project.yml && git -C ~/imaged commit -q -m tooled
            override="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects/imaged/tooled"
            # Taken back by the script after the start: a leftover override would speak in the next run.
            mkdir -p "$override"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  env:' '    REPOSITORY_IMAGE: "said by the person"' \
                > "$override/.porelo.yml"
            """
        Then it exits zero
        Given a terminal on the machine
        When I run "porelo task start overridden --project imaged --repository tooled --agent stub --attach shell --detach --clearance deny; echo start-exit-$?"
        Then the terminal shows "Allow them for tooled of imaged on this machine?"
        When I type "y"
        Then within 600 seconds the terminal shows "start-exit-0"
        And the terminal shows "projects/imaged/tooled/.porelo.yml (local override)"
        When a script runs:
            """
            override="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/projects/imaged/tooled"
            trap 'rm -rf -- "$override"' EXIT
            podman exec --user agent porelo-imaged_overridden sh -c \
                'echo "env: $REPOSITORY_IMAGE / ${KEPT:-unset}"; cat /etc/porelo-repository-image 2>/dev/null || echo "no repository lines"'
            porelo task remove porelo-imaged_overridden --force > /dev/null
            podman rmi porelo/imaged_tooled > /dev/null 2>&1 || true
            """
        Then it exits zero
        And its output contains "env: said by the person / the repository's"
        And its output contains "from-the-repository"

    Scenario: a task started from a checkout in 'default' takes the checkout's own files once allowed, the override adding to them
        When a script runs:
            """
            set -e
            base=~/default-image-probe
            repo=dimgprobe
            porelo task remove porelo-default_fromco --force > /dev/null 2>&1 || true
            porelo project default remove "$repo" > /dev/null 2>&1 || true
            rm -rf "$base"
            git init -q --bare --initial-branch=main "$base/$repo.git"
            git init -q --initial-branch=main "$base/seed"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  run: |' \
                '    RUN echo from-the-checkout > /etc/porelo-checkout-image' \
                '  env:' '    CHECKOUT_IMAGE: "checked in"' > "$base/seed/porelo.yml"
            git -C "$base/seed" add porelo.yml
            git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q -m 'image'
            git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
            git clone -q "$base/$repo.git" "$base/$repo"
            printf '%s\n' 'image:' '  from: "ubuntu:24.04"' '  env:' '    CHECKOUT_IMAGE: "the person'"'"'s"' \
                > "$base/$repo/.porelo.yml"
            cd "$base/$repo"
            set +e
            timeout 600 porelo task start fromco --agent stub --attach shell --detach --clearance deny --no-input
            echo "unanswered exit $?"
            podman image exists "porelo/default_$repo" && echo "an image was built" || echo "no image was built"
            """
        Then its output has a line matching "unanswered exit [1-9][0-9]*"
        And its output contains "+ image.run[1] = RUN echo from-the-checkout > /etc/porelo-checkout-image"
        And its output contains "no image was built"
        Given a terminal on the machine
        When I run "cd ~/default-image-probe/dimgprobe && porelo task start fromco --agent stub --attach shell --detach --clearance deny; echo start-exit-$?"
        Then the terminal shows "Allow it for dimgprobe on this machine?"
        When I type "y"
        Then within 600 seconds the terminal shows "start-exit-0"
        And the terminal shows "default-image-probe/dimgprobe/.porelo.yml"
        And the terminal shows "porelo/default_dimgprobe"
        When a script runs:
            """
            trap 'porelo task remove porelo-default_fromco --force > /dev/null 2>&1; porelo project default remove dimgprobe > /dev/null 2>&1; podman rmi porelo/default_dimgprobe > /dev/null 2>&1; rm -rf ~/default-image-probe' EXIT
            podman exec --user agent porelo-default_fromco sh -c \
                'echo "env: $CHECKOUT_IMAGE"; cat /etc/porelo-checkout-image 2>/dev/null || echo "no checked-in lines"'
            """
        Then it exits zero
        And its output contains "env: the person's"
        And its output contains "from-the-checkout"
