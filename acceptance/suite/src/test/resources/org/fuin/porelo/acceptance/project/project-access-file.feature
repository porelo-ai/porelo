Feature: A project takes its transports' settings only from an access file signed like its project file

    A project file naming an access file takes the host, the persons and the transports' settings from it,
    in a repository of its own or beside project.yml, checked against the project's signers in both places.
    Until it is applied, nothing reaches a transport for the project.

    Scenario: an access file in a second repository is applied only once a key the project holds signs it
        When a script runs:
            """
            base=$(mktemp -d)
            name="access-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            for k in project stranger; do ssh-keygen -q -t ed25519 -N '' -C "$k" -f "$base/key-$k"; done
            key=$(cut -d' ' -f1,2 "$base/key-project.pub")
            signed() {
                git -C "$1" add -A
                git -C "$1" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                    -c user.signingkey="$base/key-$2.pub" commit -q -S --allow-empty -m "$3"
            }
            git init -q --initial-branch=main "$base/access"
            printf '%s\n' 'transports:' '  room: {homeserver: "https://chat.example.org"}' > "$base/access/access.yml"
            signed "$base/access" stranger 'access by a key the project does not hold'
            stranger=$(git -C "$base/access" rev-parse HEAD)
            git init -q --initial-branch=main "$base/project"
            printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' \
                'image:' '  base_image: "ubuntu:24.04"' \
                'mail:' '  peers:' '    reviewer: {address: "room:", trust: "external"}' \
                'access:' "  repository: \"$base/access\"" > "$base/project/project.yml"
            signed "$base/project" project 'the project, naming its access file'
            porelo project follow "$name" "$base/project" --signed-by "$key" > "$base/before.out" 2>&1
            echo "follow before exit $?"
            grep -q "$stranger" "$base/before.out" && grep -q 'access.yml' "$base/before.out" \
                && echo 'the access change is refused by commit and path'
            porelo talk admit "$name" host2-core --into "$base/admission.json" 2>&1 | sed 's/^/admit before: /'
            echo "admission written before: $(test -e "$base/admission.json" && echo yes || echo no)"
            # The project's key signs the tree its commit holds, the access file in it included.
            signed "$base/access" project 'access by the project key'
            porelo project follow "$name" "$base/project" --signed-by "$key" >/dev/null 2>&1; echo "follow after exit $?"
            porelo project following | sed -n "/^$name /,/^[^ ]/p" | grep 'access ' | sed 's/^ */after: /'
            porelo talk admit "$name" host2-core --into "$base/admission2.json" 2>&1 | sed 's/^/admit after: /'
            porelo project unfollow "$name" >/dev/null 2>&1
            """
        Then its output contains "follow before exit 70"
        And its output contains "the access change is refused by commit and path"
        And its output contains "admit before: porelo: no project '"
        And its output contains "admission written before: no"
        And its output contains "follow after exit 0"
        And its output has a line matching "after: access     [0-9a-f]{12}  applied"
        And its output contains "admit after: porelo: no transport for 'room' is installed"

    Scenario: an access file beside project.yml is read from the verified commit, and naming both places is refused
        When a script runs:
            """
            base=$(mktemp -d)
            name="beside-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            ssh-keygen -q -t ed25519 -N '' -C project -f "$base/project"
            key=$(cut -d' ' -f1,2 "$base/project.pub")
            git init -q --initial-branch=main "$base/repo"
            write() {
                printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' \
                    'image:' '  base_image: "ubuntu:24.04"' \
                    'mail:' '  peers:' '    reviewer: {address: "room:", trust: "external"}' \
                    'access:' "$@" > "$base/repo/project.yml"
                printf '%s\n' 'transports:' '  room: {homeserver: "https://chat.example.org"}' > "$base/repo/access.yml"
                git -C "$base/repo" add -A
                git -C "$base/repo" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                    -c user.signingkey="$base/project.pub" commit -q -S -m 'the project'
            }
            write '  file: "access.yml"'
            porelo project follow "$name" "$base/repo" --signed-by "$key" >/dev/null 2>&1; echo "beside exit $?"
            porelo project following | sed -n "/^$name /,/^[^ ]/p" | grep 'access ' | sed 's/^ */beside: /'
            porelo project unfollow "$name" >/dev/null 2>&1
            write '  file: "access.yml"' "  repository: \"$base/elsewhere\""
            porelo project follow "$name" "$base/repo" --signed-by "$key" 2>&1 | grep -o "names both 'repository' and 'file'" \
                | sed 's/^/both: /'
            porelo project unfollow "$name" >/dev/null 2>&1
            """
        Then its output contains "beside exit 0"
        And its output has a line matching "beside: access     [0-9a-f]{12}  applied"
        And its output contains "both: names both 'repository' and 'file'"
