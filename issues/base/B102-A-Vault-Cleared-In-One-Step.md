# B102 — A Vault Cleared In One Step

**Status:** Review. Green in porelo's Build 39083121366 on `842ff109`, on both rented legs; walked on a rented machine
with the account's own homeserver, 2026-10-18.
**Size:** S - built and on main; walked.

**What must be true.** `porelo vault clear --yes` on a real machine leaves it as a new one: the vault, its
cached passphrase and device shares, and the accounts a transport keeps in it are gone, and `porelo vault init`
starts afresh - as the unit tests already show against temporary paths and a fake runner.

## Plan

Built, on `origin/main` since `10dcaaf0`: `VaultClearing` (`apps/app-project/`), called by `porelo vault clear`
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

## Walked, 2026-10-18

On a rented `ccx13` from the ubuntu CI image, prepared by porelo's leg, with `porelo-message-transport-matrix` and
`porelo-matrix-homeserver` `0.5.1~snapshot.65` installed (Tuwunel 1.9.4). In the leg's account: a vault, a project
`talk` with `matrix: {}` (the account's own homeserver), a stub task in it, one message told to it, and a person
joined with `porelo talk join talk tester1`, whose password login answered 200.

- **Listed without `--yes`**, and nothing removed:
  - the vault file and its lock, the credential;
  - the transport's accounts `matrix/account`, `matrix/project/talk` and `matrix/task/porelo-talk-one`, and the task's
    token;
  - the cached passphrase, and what the transport keeps for the account.
  - There was no backup and no grant to name.
- **A running task:** `vault clear --yes --force` refused, naming `porelo-talk-one` ("it runs with a token from the
  vault; stop it ... first - --force does not clear past a running task"), and the vault file was still there.
- **Stopped, then `--yes`:** all of the above removed. For the account's own homeserver, the clear removes:
  - its unit, stopped and disabled;
  - its database volume, its port and its registration token;
  - every poll position.
  No vault file, backup or lock was left, and no porelo key was in the user keyring.
- **The homeserver after it:** gone, so it knows no account. The person's login found nothing listening.
- **A new vault and a start in `talk`** succeeded: the task ran, on a homeserver set up afresh.

**Not walked: a homeserver elsewhere.** A second project `far` named the same Tuwunel as
`homeserver: http://127.0.0.1:<port>`. Its start was refused: "the matrix transport could not setup (exit 78):
PORELO_MATRIX_REGISTRATION_TOKEN is not set". The variable was in the shell's environment and in the daemon's, but
porelo did not pass it to the transport, so how a person gives a transport that token is the open point; see "To be
checked".

**Found in the try beforehand on `fedora`:** `vault clear --yes` on a vault named by `PORELO_VAULT` also removed the
account's transport state, `~/.local/state/porelo/transport/<scheme>`, which belongs to no vault.

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
- How a person gives a transport `PORELO_MATRIX_REGISTRATION_TOKEN` for a homeserver elsewhere: porelo runs the
  transport without the caller's environment, so a variable set in the shell or the daemon does not reach it. Then
  the clear of a project on a homeserver elsewhere is walked.
- Whether `vault clear` on a vault named by `PORELO_VAULT` should leave the account's transport state, which belongs
  to no one vault.
