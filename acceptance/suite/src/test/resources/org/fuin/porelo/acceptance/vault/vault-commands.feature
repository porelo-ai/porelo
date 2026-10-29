Feature: The vault commands

    Fourteen commands, and the ones that matter most here are the refusals: this is the group where a
    mistake ends with a credential somewhere it should not be.

    Scenario Outline: every vault command answers for itself
        When a script runs "porelo vault <command> --help"
        Then it exits zero

        Examples:
            | command    |
            | init       |
            | devices    |
            | revoke     |
            | put        |
            | import     |
            | list       |
            | remove     |
            | serve      |
            | relay      |
            | agent      |
            | unlock     |
            | lock       |
            | status     |
            | passphrase |
            | login      |

    Scenario: a store that cannot be read says why rather than answering an empty list
        # Three states and only one of them is "there are no credentials": a store that was never
        # created, one that is locked, and one that is open and empty. The first two must say so, or
        # absence reads as "nothing is stored here" - which is this project's most repeated mistake.
        When a script runs "porelo vault list"
        Then its output mentions one of "no vault, passphrase, credential"

    Scenario: putting a credential needs a name
        Given a terminal on the machine
        When I run "porelo vault put"
        Then the terminal shows "missing required parameter"

    Scenario: an agent that does not say how to log in is answered, not guessed at
        When a script runs "porelo vault login no-such-agent"
        Then it exits non-zero

    Scenario: no command prints a credential in its own help
        When a script runs "porelo vault put --help"
        Then its output does not contain "sk-ant"

    # The passphrase below is public by construction, and the vaults are the scenarios' own: the
    # account's vault is never read or replaced.

    Scenario: a vault is made once, and a second one is refused where one exists
        When a script runs:
            """
            dir=$(mktemp -d); export PORELO_VAULT="$dir/vault.bin"
            porelo vault init --passphrase-command 'printf acceptance'; echo "exit $?"
            porelo vault init --passphrase-command 'printf acceptance'; echo "exit $?"
            porelo vault lock >/dev/null; rm -rf "$dir"
            """
        Then its output contains "created"
        And its output contains "exit 0"
        And its output contains "there is already a vault at"

    Scenario: a program reads whether the vault is open, shut or not there by the exit code alone
        When a script runs:
            """
            dir=$(mktemp -d); export PORELO_VAULT="$dir/vault.bin"
            porelo vault status >/dev/null; echo "none $?"
            porelo vault init --passphrase-command 'printf acceptance' >/dev/null
            porelo vault status >/dev/null; echo "open $?"
            porelo vault lock >/dev/null
            porelo vault status >/dev/null; echo "shut $?"
            porelo vault status --json
            rm -rf "$dir"
            """
        Then its output contains "none 4"
        And its output contains "open 0"
        And its output contains "shut 3"
        And its output contains "{\"exists\":true,\"open\":false,"

    Scenario: a program hands the passphrase over on standard input, which a passphrase command cannot read
        When a script runs:
            """
            dir=$(mktemp -d); export PORELO_VAULT="$dir/vault.bin"
            printf '%s\n' acceptance | porelo vault init --passphrase-stdin >/dev/null; echo "init $?"
            porelo vault lock >/dev/null
            printf '%s\n' acceptance | porelo vault unlock --passphrase-command cat >/dev/null 2>&1; echo "command $?"
            printf '%s\n' acceptance | porelo vault unlock --passphrase-stdin >/dev/null; echo "stdin $?"
            porelo vault status >/dev/null; echo "status $?"
            porelo vault lock >/dev/null; rm -rf "$dir"
            """
        Then its output contains "init 0"
        And its output contains "command 70"
        And its output contains "stdin 0"
        And its output contains "status 0"

    Scenario: a passphrase command that produces nothing makes no vault
        When a script runs:
            """
            dir=$(mktemp -d); export PORELO_VAULT="$dir/vault.bin"
            porelo vault init --passphrase-command 'true'; echo "exit $?"
            test -e "$PORELO_VAULT" && echo "a vault file was written" || echo "no vault file"
            rm -rf "$dir"
            """
        Then its output contains "the passphrase command produced nothing"
        And its output contains "no vault file"

    Scenario: the ways into a vault are listed, and the last one cannot be revoked
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        When a script runs "porelo vault devices"
        Then it exits zero
        And its output contains "WHAT IT IS WORTH"
        And its output contains "typed by a person, stored nowhere"
        When a script runs "porelo vault revoke passphrase"
        Then it exits non-zero
        And its output contains "That is the last way into"
        When a script runs "porelo vault revoke no-such-slot"
        Then it exits non-zero
        And its output contains "is called 'no-such-slot'"

    Scenario: a credential is removed by name, and removing it again says there was nothing
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        When a script runs:
            """
            printf 'not-a-real-token' | porelo vault put acceptance.removed --type token
            porelo vault remove acceptance.removed
            porelo vault list
            porelo vault remove acceptance.removed
            """
        Then it exits zero
        And its output contains "removed   acceptance.removed"
        And its output contains "nothing named 'acceptance.removed' was in the vault"
        And its output contains "the vault is empty"

    Scenario: a value the entry already holds is put again, and nothing is written
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        When a script runs:
            """
            set -e
            vault=$(porelo vault status --json | sed 's/.*"path":"\([^"]*\)".*/\1/')
            printf 'not-a-real-token' | porelo vault put acceptance.again --type token
            before=$(sha256sum "$vault" | cut -c1-64)
            printf 'not-a-real-token' | porelo vault put acceptance.again --type token
            # A file an editor saved ends in a newline; it is the same value all the same.
            printf 'not-a-real-token\n' > "$HOME/again.txt"
            porelo vault put acceptance.again --type token --from-file "$HOME/again.txt" --json > "$HOME/again.out" 2> "$HOME/again.err"
            echo "unchanged exit $?, $(wc -l < "$HOME/again.out") line(s) on standard output"
            sed 's/^/json: /' "$HOME/again.out"
            sed 's/^/said: /' "$HOME/again.err"
            [ "$(sha256sum "$vault" | cut -c1-64)" = "$before" ] && echo "the vault file is byte for byte as it was"
            printf 'not-a-real-token' | porelo vault put acceptance.again --type oauth --json > "$HOME/again.out" 2> "$HOME/again.err"
            echo "changed exit $?, $(wc -l < "$HOME/again.out") line(s) on standard output"
            sed 's/^/json: /' "$HOME/again.out"
            sed 's/^/said: /' "$HOME/again.err"
            rm -f "$HOME/again.txt" "$HOME/again.out" "$HOME/again.err"
            porelo vault remove acceptance.again
            """
        Then it exits zero
        And its output contains "unchanged acceptance.again"
        And its output contains "unchanged exit 0, 1 line(s) on standard output"
        And its output contains 'json: {"name":"acceptance.again","stored":false}'
        And its output contains "said: unchanged acceptance.again"
        And its output contains "the vault file is byte for byte as it was"
        And its output contains "changed exit 0, 1 line(s) on standard output"
        And its output contains 'json: {"name":"acceptance.again","stored":true}'
        And its output contains "said: stored    acceptance.again (oauth, 16 characters)"
        And its output does not contain "not-a-real-token"

    Scenario: a locked vault is opened again with its passphrase, and nothing else
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        When a script runs "porelo vault lock"
        Then it exits zero
        And its output contains "locked"
        When a script runs "porelo vault unlock --passphrase-command 'printf wrong'"
        Then it exits non-zero
        And its output contains "that passphrase does not open"
        When a script runs "porelo vault unlock --passphrase-command 'printf acceptance'"
        Then it exits zero
        And its output contains "cached in"
        When a script runs "porelo vault list"
        Then it exits zero

    Scenario: a vault that does not exist is not unlocked, and init is named instead
        When a script runs "PORELO_VAULT=$(mktemp -d)/vault.bin porelo vault unlock --passphrase-command 'printf acceptance'"
        Then it exits non-zero
        And its output contains "porelo vault init"
        And its output contains "nothing was cached"

    Scenario: the passphrase is changed only by somebody who knows the current one
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        When a script runs "porelo vault passphrase --passphrase-command 'printf wrong' --new-passphrase-command 'printf changed'"
        Then it exits non-zero
        And its output contains "does not open"
        And its output contains "nothing was changed"
        When a script runs "porelo vault passphrase --passphrase-command 'printf acceptance' --new-passphrase-command 'printf changed'"
        Then it exits zero
        And its output contains "re-encrypted"
        # The old passphrase opened it until now; it must not be what is cached afterwards.
        When a script runs "porelo vault unlock --passphrase-command 'printf acceptance'"
        Then it exits non-zero
        When a script runs "porelo vault unlock --passphrase-command 'printf changed'"
        Then it exits zero

    Scenario: importing from an agent names what is installed, and what it cannot find
        When a script runs "porelo vault import no-such-agent"
        Then it exits non-zero
        And its output contains "no such agent; installed:"
        When a script runs:
            """
            dir=$(mktemp -d)
            porelo vault import stub --config-dir "$dir"; echo "exit $?"
            rm -rf "$dir"
            """
        Then its output mentions one of "does not say where it keeps its credentials, nothing to import from"
        And its output does not contain "exit 0"

    # Started tasks make it slow. The homeserver's part - an account a transport keeps on another server - is walked
    # by hand; what a machine shows without one is here. Guards data lost (a vault removed under a running task, or by
    # a listing), a secret left behind (a passphrase still cached in the keyring) and a machine that cannot start again.
    @slow
    Scenario: a vault is cleared in one step, never past a running task that holds its token, and starts afresh
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "cleared" of class "guarded" with a file in it
        When a task called "first" is started in "cleared" for the "stub" agent and left running
        # Its proxy holds what it read from the vault: even --force stops at it, and names it.
        And a script runs:
            """
            porelo vault clear --yes --force; echo "exit $?"
            test -f "$PORELO_VAULT" && echo "the vault file is still there"
            """
        Then its output contains "porelo-cleared-first"
        And its output does not contain "exit 0"
        And its output contains "the vault file is still there"
        When a script runs "porelo task remove porelo-cleared-first --force"
        Then it exits zero
        # Listed first, and nothing goes without --yes.
        When a script runs:
            """
            porelo vault clear
            test -f "$PORELO_VAULT" && echo "the vault file is still there"
            """
        Then its output contains "the vault file is still there"
        # The keyring's name for this vault is the hash Porelo derives from the vault's path, as Java's String.hashCode.
        When a script runs:
            """
            porelo vault clear --yes; echo "exit $?"
            for suffix in '' .old .lock; do test -e "$PORELO_VAULT$suffix" && echo "left: $PORELO_VAULT$suffix"; done
            porelo vault clear
            if command -v keyctl >/dev/null; then
              h=0; s="$PORELO_VAULT"
              for ((i = 0; i < ${#s}; i++)); do h=$(( (h * 31 + $(printf '%d' "'${s:i:1}")) & 0xFFFFFFFF )); done
              key="porelo:vault:$(printf '%x' "$h")"
              for each in "$key" "$key:share"; do
                keyctl search @u user "$each" >/dev/null 2>&1 && echo "still in the keyring: $each"
              done
            fi
            """
        Then its output contains "exit 0"
        And its output does not contain "left: "
        # Listed again: neither the file nor the cached passphrase is there to go.
        And its output does not contain "would go    vault"
        And its output does not contain "would go    keyring"
        And its output does not contain "still in the keyring"
        # A new vault where the old one was, and a task started from it.
        When a script runs "porelo vault init --passphrase-command 'printf afresh'"
        Then it exits zero
        Given the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        When a task called "second" is started in "cleared" for the "stub" agent and left running
        Then a script running "porelo task list" mentions "porelo-cleared-second"
        And a script runs "porelo task remove porelo-cleared-second --force"

    # What root's helper runs in a person's account to lock them out: no terminal, standard input closed, the vault
    # open as a running task needs it. Guards a dropped person whose task keeps running, or whose vault or cached
    # passphrase survives, while the helper reads an exit that says it worked.
    @slow
    Scenario: a person is locked out without a terminal: every task stopped, then the vault cleared
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "lockedout" of class "guarded" with a file in it
        When a task called "only" is started in "lockedout" for the "stub" agent and left running
        And a script runs:
            """
            h=0; s="$PORELO_VAULT"
            for ((i = 0; i < ${#s}; i++)); do h=$(( (h * 31 + $(printf '%d' "'${s:i:1}")) & 0xFFFFFFFF )); done
            key="porelo:vault:$(printf '%x' "$h")"
            # Seen cached first, so the check after the clear asks about the key that is really there; /proc/keys
            # lists this account's keys where no keyctl is installed.
            grep -q -F " $key: " /proc/keys && echo "cached before"
            porelo task stop --all </dev/null 2>&1; echo "stop $?"
            porelo vault clear --yes --force </dev/null 2>&1; echo "clear $?"
            podman ps --format '{{.Names}}' | grep -q '^porelo-lockedout-' && echo "a task still runs"
            for suffix in '' .old .lock; do test -e "$PORELO_VAULT$suffix" && echo "left: $PORELO_VAULT$suffix"; done
            for each in "$key" "$key:share"; do
              grep -q -F " $each: " /proc/keys && echo "still in the keyring: $each"
            done
            """
        Then its output contains "cached before"
        And its output contains "stop 0"
        And its output contains "clear 0"
        And its output does not contain "a task still runs"
        And its output does not contain "left: "
        And its output does not contain "still in the keyring"
        And a script runs "porelo task remove porelo-lockedout-only --force"
