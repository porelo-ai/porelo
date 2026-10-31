Feature: A project is followed with the key a person names, the same over the socket as at the terminal

    The key comes from the person, never out of the repository it verifies. A whole key is pinned as it came;
    a fingerprint pins the key that signed the commit turned away, once its fingerprint is the one named. A dry
    run records nothing, not even the key.

    Scenario: an interface follows a signed project by its fingerprint, and a dry run pins nothing
        Given a daemon of this scenario's own
        When a script runs:
            """
            base=$(mktemp -d)
            # A name of its own: the account outlives the scenario, and one left followed by an earlier run
            # refused this one for a different address rather than for the key.
            name="signed-by-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            signers="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/project-signers"
            ssh-keygen -q -t ed25519 -N '' -C project -f "$base/key"
            key=$(cut -d' ' -f1,2 "$base/key.pub")
            fingerprint=$(ssh-keygen -lf "$base/key.pub" | cut -d' ' -f2)
            git init -q --initial-branch=main "$base/signed"
            printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' \
                'image:' '  base_image: "ubuntu:24.04"' > "$base/signed/project.yml"
            git -C "$base/signed" add project.yml
            git -C "$base/signed" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                -c user.signingkey="$base/key.pub" commit -q -S -m 'the project'
            ask() {
                printf '%s\0' "{\"method\":\"org.fuin.porelo.Tasks1.Follow\",\"parameters\":$1}" \
                    | timeout 60 porelo daemon connect | tr '\0' '\n' | head -1
            }
            pinned() { cat "$signers" 2>/dev/null | grep -c "${key#* }"; }
            porelo project follow "$name" "$base/signed" --signed-by "$key" --dry-run >/dev/null; echo "cli dry run exit $?"
            echo "pinned after the terminal's dry run: $(pinned)"
            ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$key\",\"dryRun\":true}" \
                | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/dry run by key: /'
            ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$fingerprint\",\"dryRun\":true}" \
                | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/dry run by fingerprint: /'
            echo "pinned after the dry runs: $(pinned)"
            ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}" \
                | grep -o 'not by SHA256:A*' | sed 's/^/another key: /'
            porelo project following 2>/dev/null | grep -c "^$name " | sed 's/^/followed after another key: /'
            ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$fingerprint\"}" \
                | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/follow by fingerprint: /'
            echo "pinned after the follow: $(pinned)"
            ask "{\"name\":\"$name\",\"url\":\"$base/signed\"}" | grep -q "\"signer\":\"$fingerprint\"" \
                && echo "an applied follow names its signer"
            porelo project unfollow "$name" >/dev/null 2>&1
            """
        Then its output contains "cli dry run exit 0"
        And its output contains "pinned after the terminal's dry run: 0"
        And its output contains "dry run by key: \"outcome\":\"READY\""
        And its output contains "dry run by fingerprint: \"outcome\":\"READY\""
        And its output contains "pinned after the dry runs: 0"
        And its output contains "another key: not by SHA256:A"
        And its output contains "followed after another key: 0"
        And its output contains "follow by fingerprint: \"outcome\":\"APPLIED\""
        And its output contains "pinned after the follow: 1"
        And its output contains "an applied follow names its signer"

    Scenario: a pinned key hands on to the key its commit names in project.signers, and no key vouches for itself
        Given a daemon of this scenario's own
        When a script runs:
            """
            base=$(mktemp -d)
            name="handed-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            signers="${XDG_CONFIG_HOME:-$HOME/.config}/porelo/project-signers"
            for k in old successor stranger; do ssh-keygen -q -t ed25519 -N '' -C "$k" -f "$base/$k"; done
            key() { cut -d' ' -f1,2 "$base/$1.pub"; }
            git init -q --initial-branch=main "$base/repo"
            write() {
                printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' > "$base/repo/project.yml"
                if [ $# -gt 0 ]; then
                    echo '  signers:' >> "$base/repo/project.yml"
                    for k in "$@"; do echo "    - \"$(key "$k")\"" >> "$base/repo/project.yml"; done
                fi
                printf '%s\n' 'image:' '  base_image: "ubuntu:24.04"' >> "$base/repo/project.yml"
            }
            signed() {
                git -C "$base/repo" add project.yml
                git -C "$base/repo" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                    -c user.signingkey="$base/$1.pub" commit -q -S --allow-empty -m "$2"
            }
            follow() {
                if porelo project follow "$name" "$base/repo" > "$base/follow.out" 2>&1; then result=0; else result=$?; fi
                head -3 "$base/follow.out" | tr '\n' ' '; echo
                return "$result"
            }
            write; signed old 'the project'
            original=$(git -C "$base/repo" rev-parse HEAD)
            porelo project follow "$name" "$base/repo" --signed-by "$(key old)" >/dev/null 2>&1; echo "followed exit $?"
            write old successor; signed old 'the old key hands on'
            echo "hand-on: $(follow)"
            echo "successor pinned: $(grep -c "$(key successor | cut -d' ' -f2)" "$signers")"
            write successor; signed old 'the old key retires itself'
            echo "retired: $(follow)"
            echo "old historical: $(grep -c "valid-before=.*$(key old | cut -d' ' -f2)" "$signers")"
            git -C "$base/repo" -c gpg.format=ssh -c gpg.ssh.allowedSignersFile="$signers" \
                verify-commit "$original" >/dev/null 2>&1 && echo 'retired key verifies history'
            handed=$(git -C "$base/repo" rev-parse HEAD)
            git -C "$base/repo" -c gpg.format=ssh -c gpg.ssh.allowedSignersFile="$signers" \
                verify-commit "$handed" >/dev/null 2>&1 && echo 'handover verifies at retirement cutoff'
            porelo project following --json > "$base/before.json"
            echo '# backdated protected change' >> "$base/repo/project.yml"
            GIT_AUTHOR_DATE=2024-01-01T00:00:00Z GIT_COMMITTER_DATE=2024-01-01T00:00:00Z signed old 'backdated change'
            backdated=$(git -C "$base/repo" rev-parse HEAD)
            if follow > "$base/backdated.out"; then echo 'unexpected backdated success'; else echo "backdated follow exit $?"; fi
            grep -q "$backdated" "$base/backdated.out" && grep -q 'project.yml' "$base/backdated.out" \
                && echo 'backdated retired key refused'
            porelo project following --json > "$base/after.json"
            cmp -s "$base/before.json" "$base/after.json" && echo 'refused backdated follow preserves its record'
            git -C "$base/repo" reset -q --hard "$handed"
            write successor stranger; signed stranger 'a key adds itself'
            if follow > "$base/self-added.out"; then echo 'unexpected self-added success'; else echo "self-added follow exit $?"; fi
            cat "$base/self-added.out"
            porelo project following --json > "$base/after.json"
            cmp -s "$base/before.json" "$base/after.json" && echo 'refused self-added follow preserves its record'
            echo "stranger pinned: $(grep -c "$(key stranger | cut -d' ' -f2)" "$signers")"
            porelo project unfollow "$name" >/dev/null 2>&1
            """
        Then its output contains "followed exit 0"
        And its output contains "successor pinned: 1"
        And its output contains "old historical: 1"
        And its output contains "retired key verifies history"
        And its output contains "handover verifies at retirement cutoff"
        And its output contains "backdated follow exit 70"
        And its output contains "backdated retired key refused"
        And its output contains "refused backdated follow preserves its record"
        And its output contains "self-added follow exit 70"
        And its output contains "refused self-added follow preserves its record"
        And its output contains "changes project.signers:"
        And its output contains "stranger pinned: 0"

    Scenario: unsigned documents apply and a signed document cannot cover an unsigned configuration
        Given a daemon of this scenario's own
        When a script runs:
            """
            base=$(mktemp -d)
            name="protected-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
            ssh-keygen -q -t ed25519 -N '' -f "$base/key"
            key=$(cut -d' ' -f1,2 "$base/key.pub")
            git init -q --initial-branch=main "$base/repo"
            git -C "$base/repo" config user.name Person
            git -C "$base/repo" config user.email person@example.org
            git -C "$base/repo" config gpg.format ssh
            git -C "$base/repo" config user.signingkey "$base/key"
            printf '%s\n' 'project:' "  name: $name" '  security_class: guarded' \
                'image:' '  base_image: ubuntu:24.04' > "$base/repo/project.yml"
            git -C "$base/repo" add project.yml
            git -C "$base/repo" commit -q -S -m configuration
            if porelo project follow "$name" "$base/repo" > "$base/first.out" 2>&1; then
                echo 'unexpected first follow success'
            else
                echo "first refused follow exit $?"
            fi
            grep -q 'nothing was recorded' "$base/first.out" && echo 'first refusal records nothing'
            porelo project following --json > "$base/first.json"
            if ! grep -q "\"name\":\"$name\"" "$base/first.json"; then echo 'first refusal names no project'; fi
            porelo project follow "$name" "$base/repo" --signed-by "$key" >/dev/null 2>&1
            echo notes > "$base/repo/notes.md"
            git -C "$base/repo" add notes.md
            git -C "$base/repo" commit -q --no-gpg-sign -m notes
            porelo project follow "$name" "$base/repo" >/dev/null 2>&1
            doc=$(git -C "$base/repo" rev-parse HEAD)
            porelo project following --json | grep -q "\"commit\":\"$doc\"" && echo 'unsigned document applied'
            porelo project following --json > "$base/before.json"
            echo '# unsigned configuration' >> "$base/repo/project.yml"
            git -C "$base/repo" add project.yml
            git -C "$base/repo" commit -q --no-gpg-sign -m configuration
            refused=$(git -C "$base/repo" rev-parse HEAD)
            echo signed >> "$base/repo/notes.md"
            git -C "$base/repo" add notes.md
            git -C "$base/repo" commit -q -S -m notes
            if porelo project follow "$name" "$base/repo" > "$base/refused.out" 2>&1; then
                echo 'unexpected unsigned configuration success'
            else
                echo "unsigned protected follow exit $?"
            fi
            grep -q "$refused" "$base/refused.out" && grep -q 'project.yml' "$base/refused.out" \
                && echo 'unsigned protected change refused by commit and path'
            porelo project following --json > "$base/after.json"
            cmp -s "$base/before.json" "$base/after.json" && echo 'refused protected follow preserves its record'
            porelo project unfollow "$name" >/dev/null 2>&1
            """
        Then its output contains "first refused follow exit 70"
        And its output contains "first refusal records nothing"
        And its output contains "first refusal names no project"
        And its output contains "unsigned document applied"
        And its output contains "unsigned protected follow exit 70"
        And its output contains "unsigned protected change refused by commit and path"
        And its output contains "refused protected follow preserves its record"

    Scenario: the isolated verifier checks the explicit revocation list
        When a script runs:
            """
            base=$(mktemp -d)
            ssh-keygen -q -t ed25519 -N '' -f "$base/key"
            key=$(cut -d' ' -f1,2 "$base/key.pub")
            git init -q --initial-branch=main "$base/repo"
            printf '%s\n' 'project:' '  name: demo' '  security_class: guarded' \
                'image:' '  base_image: ubuntu:24.04' > "$base/repo/project.yml"
            git -C "$base/repo" add project.yml
            git -C "$base/repo" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                -c user.signingkey="$base/key" commit -q -S -m configuration
            commit=$(git -C "$base/repo" rev-parse HEAD)
            ssh-keygen -q -k -f "$base/revoked.krl" "$base/key.pub"
            porelo project verify --repository "$base/repo" --signed-by "$key" --krl "$base/revoked.krl" \
                --json > "$base/out" 2> "$base/err"
            echo "revoked verifier exit $?"
            test ! -s "$base/out" && echo 'refusal has no success answer'
            grep -q "$commit" "$base/err" && grep -q 'project.yml' "$base/err" && echo 'refusal names commit and path'
            """
        Then its output contains "revoked verifier exit 65"
        And its output contains "refusal has no success answer"
        And its output contains "refusal names commit and path"
