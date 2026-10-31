# B370 — A Backup Key That Can Do Nothing Else

**Status:** Review.
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

## Done

- The follow's walk takes backup keys only from the commit in force on this machine's clone, so a follow with
  `--accept-rewrite`, like a first follow, takes none: a rewritten history is taken only when an ordinary signer
  signs its tip.
- A backup-signed commit is refused when the key it puts in place is revoked by a registry read here or by
  `revoked.krl`, so the key it backs is not retired for one that can sign nothing; and every signer the walk verifies
  is asked of the registries read here, not only a backup key, as `project access` asks of a card's key.
- An `--id` repeated for `--registry` or `--host` answers its earlier commit only when that commit is its parent's
  `owners.yml` with exactly the change asked for, in the scope asked for: the person's one backup key added, or the
  key it backs replaced by the new card's key, with that backup key and never by the same key; the commit's own file
  is filed again with `--base`, so a commit made on another base is refused.
- `identity backup-key --registry|--host` refuses a checkout whose `origin` is no longer the address the registry was
  read from (`origin-moved`) or that is no Git directory of its own (`checkout-moved`) before anything is read, so no
  trust record is written for another address; a read the history refuses is a refusal (65) instead of an unhandled
  failure (70, its trace in `failures.log` rather than on the terminal).
- A project's repeated `--id` answers an earlier commit only when it verifies with the person's own key (making) or
  the backup key (using) and is exactly the change asked for on its parent, so an unchecked commit before the first
  follow answers nothing.
- Making a registry or host backup key refuses `revoked` when the caller's own card key is revoked by the registry or
  by `revoked.krl`.
- `--out` refuses `file-exists` when `FILE.pub` exists too, so ssh-keygen never overwrites it and the command never
  removes it.
- When filing fails after the checkout's `HEAD` moved, the private key file is kept, since the commit files its
  public half.

Each has a test that failed on the code before and a mutant of its fix that turned that test red; existing follows in
the tests were unchanged by the wider registry check.
