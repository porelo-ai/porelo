Feature: The credentials commands

  Six commands that decide which secret a destination gets and where it lives. What matters is that
  each says what it did, refuses what it cannot do, and never leaves a record behind that nobody
  asked for. Every address here is under .invalid, so nothing is ever reached or presented to.

  Scenario Outline: every credentials command answers for itself
    When a script runs "sokar credentials <command> --help"
    Then it exits zero

    Examples:
      | command    |
      | list       |
      | declare    |
      | forget     |
      | check      |
      | keys       |
      | trust-host |

  Scenario: a declared credential is listed, checked and forgotten
    # Cleared first, so a run that failed half-way does not decide this one.
    Given a script runs "sokar credentials forget https://sokar-acceptance.invalid/declared/"
    When a script runs "sokar credentials declare https://sokar-acceptance.invalid/declared/ --kind token --env SOKAR_ACCEPTANCE_NEVER_SET"
    Then it exits zero
    And its output contains "sokar-acceptance.invalid/declared"
    And its output contains "this machine does not protect it"
    When a script runs "sokar credentials list"
    Then its output contains "sokar-acceptance.invalid/declared"
    And its output contains "its value is not there"
    When a script runs "sokar credentials check https://sokar-acceptance.invalid/declared/repo.git"
    Then it exits non-zero
    And its output contains "missing_value"
    When a script runs "sokar credentials forget https://sokar-acceptance.invalid/declared/"
    Then it exits zero
    And its output contains "forgot"
    When a script runs "sokar credentials list"
    Then its output does not contain "sokar-acceptance.invalid/declared"

  Scenario: forgetting what was never declared says so
    When a script runs "sokar credentials forget https://sokar-acceptance.invalid/never/"
    Then it exits non-zero
    And its output contains "nothing is declared for"

  Scenario: a declaration that does not say where the value lives is refused and records nothing
    When a script runs "sokar credentials declare https://sokar-acceptance.invalid/nowhere/ --kind token"
    Then it exits non-zero
    And its output contains "say exactly one of --vault, --file, --env or --agent"
    When a script runs "sokar credentials list"
    Then its output does not contain "sokar-acceptance.invalid/nowhere"

  Scenario: a kind that is not one is refused rather than guessed
    When a script runs "sokar credentials declare https://sokar-acceptance.invalid/kind/ --kind password --env SOKAR_ACCEPTANCE_NEVER_SET"
    Then it exits non-zero
    And its output contains "'password' is not a kind"
    When a script runs "sokar credentials list"
    Then its output does not contain "sokar-acceptance.invalid/kind"

  Scenario: the account's ssh keys are named without being read
    When a script runs "sokar credentials keys"
    Then it exits zero
    And its output mentions one of "No ssh keys, .ssh/"
    And its output does not contain "PRIVATE KEY"

  Scenario: a host's keys are shown, and nothing is recorded without a fingerprint
    # The machine's own sshd: every machine this runs on has one, and no other host is reached.
    When a script runs "sokar credentials trust-host localhost"
    Then it exits zero
    And its output contains "localhost offers:"
    And its output contains "sokar credentials trust-host localhost --fingerprint"

  Scenario: a fingerprint the host does not offer records nothing
    When a script runs "sokar credentials trust-host localhost --fingerprint SHA256:sokar-acceptance-not-a-key"
    Then it exits non-zero
    And its output contains "offers no key with that fingerprint right now"
    And its output contains "Nothing was recorded"

  Scenario: git gets the token for an https remote out of the vault
    # Asked through git itself, which runs the helper chain the way a fetch does, rather than
    # through an imitation of its protocol. The token is public by construction.
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs:
      """
      printf 'a-token-for-the-acceptance-run' | sokar vault put git.token.forge.invalid --type token >/dev/null
      printf 'protocol=https\nhost=forge.invalid\n\n' \
          | git -c credential.helper="$(command -v sokar) vault credential --entry git.token.forge.invalid" credential fill
      """
    Then it exits zero
    And its output contains "password=a-token-for-the-acceptance-run"

  Scenario: a vault entry a declaration reads is said to be used there, not to be found by nothing
    # A name of the person's own, neither a provider's nor one git looks for: only the declaration asks for it.
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs:
      """
      sokar credentials forget https://forge.invalid/declared/ >/dev/null 2>&1
      sokar credentials declare https://forge.invalid/declared/ --kind token --vault forge-declared >/dev/null
      printf 'a-token-for-the-acceptance-run' | sokar vault put forge-declared --type token 2>&1 | sed 's/^/first: /'
      sokar credentials forget https://forge.invalid/declared/ >/dev/null
      printf 'a-token-for-the-acceptance-run' | sokar vault put forge-declared --type token 2>&1 | sed 's/^/again: /'
      """
    Then it exits zero
    And its output contains "first: used for  https://forge.invalid/declared/"
    And its output does not contain "first: sokar: no provider 'forge-declared'"
    And its output contains "again: sokar: no provider 'forge-declared' is declared"

  Scenario: a credential declared against the vault is ready, and forgetting it leaves the value
    # The record half, with no value involved at any point - the property that lets an interface do
    # the same over a socket no secret may cross.
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs:
      """
      printf 'a-token-for-the-acceptance-run' | sokar vault put git.token.forge.invalid --type token >/dev/null
      sokar credentials forget https://forge.invalid/acme/ >/dev/null 2>&1
      sokar credentials declare https://forge.invalid/acme/ --kind token --vault git.token.forge.invalid \
          && sokar credentials check --no-connect https://forge.invalid/acme/x.git \
          && sokar credentials forget https://forge.invalid/acme/
      """
    Then it exits zero
    And its output contains "ready"
    And its output contains "still in the vault"
    And its output does not contain "a-token-for-the-acceptance-run"
