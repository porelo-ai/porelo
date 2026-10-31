# B346 — A Backup Key For A Single Admin

**Status:** Review (Accepted, operator, 2026-10-31: the whole L in sprint 6m, the project part included).
**Size:** L - grown from S: the registry part alone needed owners keyed by key rather than by login, a rule for what a
backup-signed commit may change in `owners.yml`, a new `registry file owners` path that signs with a key file instead of
the agent, warnings in `registry read` and `identity show`, and the verb's two halves; the project part adds a backup
signer to the follow's walk. Plan: (1) registry and host: `owners.yml` entries with `backup: true`, the history's rule,
`registry file owners [--signing-key-file]`, warnings; (2) `identity backup-key --host|--registry` make and use;
(3) project: a backup signer among the signers, the walk's rule, `--project`, `verify`'s warning; (4) docs.

**What must be true.** A project with a single admin, a host with a single host admin and a registry with a single owner
each keep a second, offline backup key, until a main key with certified working keys exists: among the project's
signers, or in `owners.yml` for the latter two. `porelo identity backup-key --project P | --host H | --registry` makes
it once as an OpenSSH private key file for the person to keep offline, never in their daily agent, and forgets it at
once; a signed commit adds it with `backup: true` - by a project admin to the signers, by a registry owner to
`owners.yml`, for a host admin too. A commit signed by a backup key may change only the one entry it backs - its admin's
key among the signers, or its owner's or host admin's entry in `owners.yml` - and itself. It is no certifier, so it
certifies no task's key. `verify --json` and `registry read --json` name it with `backup: true` and its date. `verify`
and `registry read` warn on standard error, and answer `"warnings"`, when a single admin or owner has no backup key; a
second backup key per admin is refused.

`porelo identity show --json` ([B338](README.md#b338)) answers `sole_without_backup`: `[{role:
"project-admin|host-admin|registry-owner", scope}]`, each project, host or registry where the person is the single admin
or owner without a backup key, `scope` naming it, so the interface offers to make one. Until this is built, `identity
show` leaves the field out of the answer (Coordinator's decision, 2026-10-30, operator confirms 2026-10-31).

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- `identity backup-key --project P` writes a private key file once and keeps no copy; its commit is answered `backup:
  true` with its date.
- A commit signed by the backup key that changes only the signers is applied; one that changes anything else is refused
  and named.
- A task certificate issued by a backup key is refused.
- A project with one admin and no backup key answers a warning; a second backup key for the same admin is refused.
- `identity show --json` names each scope where the person is the single admin or owner without a backup key, and none
  once the backup key is filed.
- `--host H` and `--registry` add the key to `owners.yml` by a registry owner's commit; a host admin's backup key that
  changes anything but that admin's entry is refused and named.
- Seen to fail first: today any key in `signers` may change anything.

## Done

- Registry and host: `owners.yml` entries with `backup: true`, the history's rule that a backup-signed commit changes
  only the entries it backs, `registry file owners [--signing-key-file]`, the warnings of `registry read` and
  `identity show`, and `identity backup-key --registry|--host` to make and use the key.
- Project: a backup key is an entry of `project.signers`, `{key, backup: true, backs: <fingerprint>, since: <date>}`,
  read by the signers reader; `backs` must name a key of the list, and a second backup key for the same key, or a
  backup key that is also a signer, refuses the file. The reader's keys leave backup keys out, so a backup key is never
  pinned, signs no access file and is no signer of an ordinary change.
- The follow's walk takes a commit signed by a backup key in force before it only when it changes `project.yml` alone
  and there replaces the key it backs by one new key and points `backs` at that one; anything else is refused as
  `UNKNOWN_KEY` with the commit and `project.yml` named. The old key is retired as at any handover.
- A task's certifiers leave out the project's backup keys, so a certificate a backup key issued is refused.
- `identity backup-key --project P` makes (only P's one signer: `not-sole`, `backup-exists`, `file-exists`,
  `no-terminal`, `not-signer`, `base-moved`) and uses (`not-the-backup`, `wrong-passphrase`, `card-login`, `revoked`,
  `backup-only-own`) the key; it fetches P's repository head as `project access` fetches the access repository,
  commits `project.yml` signed through the agent, or with the file through `ssh-keygen`, checks the commit as the
  follow would and pushes it leased onto the head read: `pushed: true`. 66 for a project not followed.
- `project verify --json` answers `backups` and `warnings`, and warns on stderr; `identity show --json` names
  `project-admin` for each followed project whose one signer is the person's key without a backup key.
- After a reading: a backup key counts only when the commit in force names it, so a first follow or a `verify`
  without `--since` takes none from the unchecked commit before the tip; the follow refuses a backup key a registry
  read here revokes, as the revocation list does; `verify --json` leaves backup keys out of `certifiers`. In
  `owners.yml` a backup-signed commit must keep the backup key's entry as it was and put exactly one new key, not
  revoked, in place of the login's own: it can no longer make itself the owner, remove the owner, swap backup keys
  or bring back a revoked key. `identity backup-key --registry|--host` judges the checkout as it is, after its
  `HEAD` is checked against `--base` or the commit read last, and `--use` refuses a key `revoked.krl` names. An
  `--id` answers its earlier commit before and after a read or follow, without a second key, and only from a commit
  that changed `project.yml` alone, which the walk checked, and filed a backup key; an unchecked commit's trailers
  answer nothing. A make refused after `ssh-keygen` removes the file; a malformed `--base` is 64, and only the
  agent's refusals are `not-signer`.

Not claimed: "single admin" of a project is read as the one key of `project.signers`, not the admins of
`access.yml`; the project's backup key goes into the project's own repository, where `project.yml` is, not the access
repository; the replaced key is retired by the follow rather than listed under a `revoked` the project file does not
have; no acceptance scenario on a machine.
