# B337 — A Person Card Signed By Its Own Key

**Status:** Accepted.
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

## Acceptance

- A card signed by its own key is accepted with its key pinned, and its fields are answered as above, `email` included
  when given.
- A change of key signed by both keys is accepted against the old key's pin, and `chain` lists both fingerprints.
- A change signed by the new key alone, or by the old key alone, is refused with 65.
- A chain through a key in the revocation list is refused with 65; a card whose own key is in it answers `revoked:
  true`.
- Seen to fail first: there is no card and no `card verify` today.
