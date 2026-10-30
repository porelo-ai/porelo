# B337 — A Person Card Signed By Its Own Key

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** S - estimated 136 minutes from the median of S builds: the card's format, its check and the chain of keys, in
one module with its tests.

**What must be true.** A person card is a small YAML file - `login`, `name`, optional `email`, `key` (`ssh-ed25519`),
`created`, `expires`, `predecessor` - signed by its own key as an SSH signature in the `porelo` namespace. It names no
machine. A card that changes the key names the old key as `predecessor` and is signed by both, the new key and the old
one: a stranger's key cannot claim somebody's key as its predecessor, and the old key cannot hand the identity to a key
nobody holds.

`porelo card verify <card> --pinned <fingerprint> [--krl <file>] --json` answers `{"login", "name", "email", "ssh",
"expires", "revoked", "chain"}`. It accepts the pinned key, or a chain of such changes back to it. It exits 65 on a bad
signature, on a chain that does not reach the pinned key, and on a chain that passes through a key the revocation list
names, so a stolen key that an admin revoked can no longer hand the identity on. `revoked` is true when the card's own
key is named there; `ssh` is then still given. It needs no network, no home and no vault.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Plan

The card file is its YAML body followed by one or two armored SSH signatures (`-----BEGIN SSH SIGNATURE-----`); the
signed bytes are the body exactly as written, everything before the first signature, so `ssh-keygen -Y verify` checks
it too. The first signature is the card's own key, the second its predecessor's.

1. `vault`: `PersonCard` - the record, `read(text, origin)` refusing an unknown or missing field, a key that is not
   `ssh-ed25519`, a login that is not a lower-case name; `write(...)` signing with one or two `SigningKey`s through
   `SshSignature` in the `porelo` namespace; `fingerprint()` as `ssh-keygen` prints it. `PersonCardChain.check(card,
   earlier, pinned)` walks from the card back through the earlier cards to the pinned fingerprint, each change signed by
   both keys. `snakeyaml` declared in `vault/pom.xml`, as `core` does.
2. `apps/app-vault`: `CardCommand` with `CardVerifyCommand` - `porelo card verify <card> --pinned <fingerprint>
   [--earlier <card>]... [--krl <file>] --json`; the revocation list read by `ssh-keygen -Q -f <krl>` per key of the
   chain, each key written to a file in a directory of the command's own. Exit 0 or 65; the answer as above.
   Registered in `PoreloCli`.
3. Docs: `doc/commands.md` (`card verify`, the card's format), `doc/credentials.md` (what a card is, its two
   signatures).
4. Tests, red first: `PersonCardTest` in `vault` (read, write, a forged body, one signature missing on a change),
   `CardVerifyCommandTest` in `apps/app` (the four acceptance cases against a real `ssh-keygen -k` list).

## To be checked

- Whether `ssh-keygen -Q` is on every machine that runs `card verify` (host and laptop: OpenSSH client is a dependency
  already).

## Done

- `PersonCard` reads a card from the parser's events, as one flat mapping of text to text. A merge key, an anchor, an
  alias, a tag, a nested value, a control character, a key line that is not exactly `ssh-ed25519 AAAA...`, and a day
  that is not strictly `YYYY-MM-DD` are each refused. It signs with the card's key, then with the predecessor's.
- `PersonCardChain` walks back to the pinned key, following each key once, and leaves revoked keys out while it seeks
  the chain; neither the order of the earlier cards nor many copies of them change the answer or its cost.
- `porelo card verify` answers the card for a program; a revocation list that is empty, missing or unreadable is exit
  66, never skipped.
- A read-only security reading by agy Gemini found seven faults, all fixed with tests seen red: the merge keys, a key
  with a line break, a name with a control character, a rolled-over day, the order of earlier cards, an empty `--krl`
  skipping the list, and a missing test for a change signed by the old key alone.
- The narrow reading of those fixes found two more, also fixed with tests seen red: a walk exponential in copies of the
  earlier cards, and a revoked key found only after a chain was chosen. It also asked for tests of a list `ssh-keygen`
  cannot read, a card signed twice by one key, a field given twice, an anchor alone and a circle, now there.

## Acceptance

- A card signed by its own key is accepted with its key pinned, and its fields are answered as above, `email` included
  when given.
- A change of key signed by both keys is accepted against the old key's pin, and `chain` lists both fingerprints.
- A change signed by the new key alone, or by the old key alone, is refused with 65.
- A chain through a key in the revocation list is refused with 65; a card whose own key is in it answers `revoked:
  true`.
- Seen to fail first: there is no card and no `card verify` today.
