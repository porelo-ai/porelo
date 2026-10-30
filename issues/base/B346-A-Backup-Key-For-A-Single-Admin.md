# B346 — A Backup Key For A Single Admin

**Status:** Accepted.
**Size:** S - estimated 136 minutes from the median of S builds: one verb, two lists and one limit in the follow's
check.

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
or owner without a backup key, `scope` naming it, so the interface offers to make one. Until this is built, `identity show` leaves
the field out of the answer (Coordinator's decision, 2026-10-30, operator confirms 2026-10-31).

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
