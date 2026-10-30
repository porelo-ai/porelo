# B341 — A New Commit Is Never Accepted By A Retired Key

**Status:** Accepted.
**Size:** S - estimated 136 minutes from the median of S builds: the follow's signer list for new commits, the history's
list and the revocation list.

**What must be true.** A key that leaves `signers` is kept in Git's allowed signers with `valid-before`, so the commits
it signed while it was in force still verify when history is read. Git checks `valid-before` against the commit's time,
which its signer sets: a retired or stolen key could sign a new commit dated before it. The follow therefore checks
every new commit against the signers in force without any key that carries `valid-before`, and against the revocation
list (`gpg.ssh.revocationFile`). `valid-before` serves only for reading history, never for accepting a new commit.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- An old commit signed by a retired key verifies when history is read.
- A new commit signed by that retired key and dated before its `valid-before` is refused and named.
- A new commit signed by a key in the revocation list is refused and named.
- Seen to fail first: today a retired key is dropped from the pinned list, so its old commits no longer verify.
