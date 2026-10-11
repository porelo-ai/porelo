# B102 — A Vault Cleared In One Step

**Status:** Implementing.
**Size:** S - built and on main; what is left is the walk on a rented machine and what it finds.

**What must be true.** `sokar vault clear --yes` on a real machine leaves it as a new one: the vault, its
cached passphrase and device shares, and the accounts a transport keeps in it are gone, and `sokar vault init`
starts afresh - as the unit tests already show against temporary paths and a fake runner.

## Plan

Built, on `origin/main` since `10dcaaf0`: `VaultClearing` (`apps/app-project/`), called by `sokar vault clear`
(`VaultClearCommand`) and the daemon alike, held by `VaultClearingTest` (`apps/app/src/test/`). It lists without
`--yes`, clears the transports' accounts elsewhere while the vault can still be read, removes the vault, its
`.old` backup and `.lock`, drops what the kernel keyring caches, and refuses with `--force` too while a running
task holds a token from the vault. No acceptance scenario runs `vault clear`.

Left:

1. **A scenario in `vault-commands.feature`** for what a machine can show without a homeserver: the listing
   without `--yes` removes nothing, `--yes` leaves no file and no keyring entry (`keyctl search @u user ...`),
   `vault init` and a task start succeed afterwards, and a running task with a token makes `--yes --force` refuse
   and name it. Red first: the scenario run before its steps exist, then against a build with the keyring
   step taken out.
2. **The homeserver part walked** on a rented machine with a project that has messages: the account gone from the
   homeserver after `--yes`, and a start in that project afterwards. Its log is kept here; a scenario follows if
   the kit can set up a homeserver for one.
3. What the walk finds is fixed with a test watched to fail, or written here.

## Acceptance

- Walked on a rented machine with a project that has messages on a homeserver: a listing without `--yes`
  names the vault file, its backup and lock, the cached passphrase, a grant, the transport's account and a
  stopped task's token, and removes nothing; with `--yes` the three files are gone, `keyctl` finds neither the
  passphrase nor a device share, the homeserver no longer knows the account, and `vault init` followed by a
  task start in that project succeeds. Seen to fail: a file or a keyring entry still there, or a start refused
  because the homeserver knows an account the new vault holds no token for.
- On the same machine, a running task holding a token from the vault makes `vault clear --yes --force` refuse
  and name that task, and the vault file is still there afterwards.

## To be checked

- Whether the acceptance kit can give a scenario a homeserver, so the transport's part runs in the suite and not
  only by hand (Agent Core's).
