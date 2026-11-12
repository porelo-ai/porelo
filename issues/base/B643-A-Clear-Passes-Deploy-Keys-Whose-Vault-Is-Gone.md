# B643 — A Clear Passes Deploy Keys Whose Vault Is Gone

```json
{
  "$schema": "issue-1.json",
  "id": "B643",
  "title": "A Clear Passes Deploy Keys Whose Vault Is Gone",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 83,
  "blockedBy": [],
  "covers": "porelo project clear does not stop at 'the vault is locked' for deploy keys that lived in a vault which no longer exists; it says what it could not read and clears the rest."
}
```

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 24's clean-up. The run's vault, which held the run's deploy
keys, had been deleted with the run's copy. porelod then went back to the account's default vault, which was locked.
`porelo project clear --yes --force foo-project` answered "not removed deploy key: this machine's deploy keys for
foo-project - the vault is locked, so they can be neither read nor forgotten", and the project stayed listed. A
throwaway vault made with `porelo vault init` let the clear through: "nothing to clear".

Read in the code: `Clearing.keys` refuses whenever a vault exists and is not open, whichever vault holds the keys. The
locked default vault never held this project's keys.

**What must be true.** A clear whose vault cannot be opened says which keys it could not read, and does not leave the
project half cleared because of keys that no open vault holds. Keys a vault does hold are still forgotten only with it
open. The keys on the forge stay for a person to remove, as now.
