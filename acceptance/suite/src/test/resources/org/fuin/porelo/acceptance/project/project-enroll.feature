Feature: A machine joins a project in one step a person can follow

    Its message key's public half goes into the project's machine-signers as a change waiting in the project's
    gate; a person reads it - a signer list first - and merges it signed with their own key. No secret leaves
    the machine and no key is copied by hand.

    Scenario: making a deploy key is no longer a command
        # More write-capable keys leave access the forge no longer expects.
        When a script runs:
            """
            porelo credentials deploy-key; echo "deploy-key exit $?"
            """
        Then its output contains "has no command called 'deploy-key'"
        And its output contains "deploy-key exit 2"

    @slow
    Scenario: a machine's enrolment is reviewed as dangerous and merged as a signed commit
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        # Asked of the daemon below, as an interface asks it.
        And a daemon of this scenario's own
        When a script runs:
            """
            base=$(mktemp -d)
            ssh-keygen -q -t ed25519 -N '' -C project -f "$base/key"
            git init -q --bare --initial-branch=main "$base/upstream.git"
            git init -q --initial-branch=main "$base/seed"
            printf '%s\n' 'project:' '  name: "enrolling"' '  security_class: "guarded"' "  upstream: \"$base/upstream.git\"" \
                'image:' '  base_image: "ubuntu:24.04"' > "$base/seed/project.yml"
            git -C "$base/seed" add project.yml
            git -C "$base/seed" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
                -c user.signingkey="$base/key.pub" commit -q -S -m 'the project'
            git -C "$base/seed" push -q "$base/upstream.git" HEAD:main
            porelo project follow enrolling "$base/upstream.git" --signed-by "$(cut -d' ' -f1,2 "$base/key.pub")"; echo "follow exit $?"
            porelo project enroll enrolling --as porelo@acceptance; echo "enroll exit $?"
            porelo gate pending -p enrolling
            porelo gate review enroll-porelo-acceptance -p enrolling | head -12
            # The interface's way: the pending work leaves as a bundle, for a merge signed on the person's computer.
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.PendingBundle","parameters":{"project":"enrolling","name":"enroll-porelo-acceptance"}}' \
                | timeout 20 porelo daemon connect | tr '\0' '\n' | head -1 \
                | python3 -c 'import json,sys,base64; r=json.load(sys.stdin); open(sys.argv[1],"wb").write(base64.b64decode(r["parameters"]["bundle"]))' "$base/pending.bundle"
            git -C "$base/seed" fetch -q "$base/pending.bundle" refs/porelo/incoming/enroll-porelo-acceptance && echo "bundle fetched"
            printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.Landed","parameters":{"project":"enrolling","name":"enroll-porelo-acceptance","branch":"main"}}' \
                | timeout 20 porelo daemon connect | tr '\0' '\n' | head -1 | grep -o '"landed":false\|not on the upstream.s main yet' | sed 's/^/before the push: /' 
            printf '[user]\n\tname = Person\n\temail = person@example.org\n\tsigningkey = %s\n[gpg]\n\tformat = ssh\n' \
                "$base/key.pub" > "$base/gitconfig"
            GIT_CONFIG_GLOBAL="$base/gitconfig" porelo gate approve enroll-porelo-acceptance -p enrolling --signed; echo "approve exit $?"
            git --git-dir "$base/upstream.git" show main:machine-signers | cut -d' ' -f1,2 | sed 's/^/upstream signer: /'
            git --git-dir "$base/upstream.git" cat-file -p main | grep -c '^gpgsig ' | sed 's/^/signed merges: /'
            porelo project unfollow enrolling --force >/dev/null 2>&1
            rm -rf "$base"
            """
        Then its output contains "follow exit 0"
        And its output contains "enrolment enroll-porelo-acceptance waits for a person in enrolling's gate"
        And its output contains "this machine porelo@acceptance  SHA256:"
        And its output contains "configuration signed by  SHA256:"
        And its output contains "enroll exit 0"
        And its output contains "enroll-porelo-acceptance"
        And its output contains "signer list"
        And its output contains "bundle fetched"
        And its output contains 'before the push: "landed":false'
        And its output contains "before the push: not on the upstream's main yet"
        And its output contains "approve exit 0"
        And its output contains "upstream signer: porelo@acceptance ssh-ed25519"
        And its output contains "signed merges: 1"
