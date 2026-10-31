# B370 — A Backup Key That Can Do Nothing Else

**Status:** Idea.
**Size:** M, proposed - the follow's walk on a rewrite, the replacement key's revocation, the repeat of an `--id` built
from its parent, the registry checkout's origin, and the key file's two halves, each with its test.

**What must be true.** A backup key replaces the one key it backs and nothing else, on every path, and the command
that makes or uses it never answers a change it did not make nor removes a file it did not write. A second reading
of [B346](README.md#b346) on 2026-10-31 found eight places where this does not hold yet:

- On a follow with `--accept-rewrite`, the backup keys of the commit in force before are loaded, but the backed-only
  comparison is made against the tip's unchecked parent, so a rewritten parent may change anything and add signers.
- A backup-signed commit of a project may put in place a key that a registry read here or `revoked.krl` revokes, and
  a signer is checked against the registry's revocations only when it is a backup key.
- Repeating an `--id` for the registry or a host compares the commit's `owners.yml` with itself and drops `--base`,
  so another scope's or another mode's change is answered as this one, and a replacement may answer `replaced` equal
  to `by`.
- The live registry read does not check that the checkout's `origin` is still the one read before, so a repointed
  checkout gives a second trust record, and a card or argument error of that read ends in a stack trace.
- A project's repeated `--id` trusts commits at or before the first follow, which were never checked.
- Making a registry or host backup key does not check the caller's own key against `revoked.krl`.
- An existing `<out>.pub` is overwritten and then deleted, though only `<out>` was checked to be new.
- When filing fails after the commit is already made, the private key file is deleted although its public half is
  filed.

Each is answered by a test that fails today.
