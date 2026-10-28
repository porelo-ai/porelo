# B310 — A Project Names A Private, Signed Access File

**Status:** Estimated, from
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78).
**Size:** M - a reader for `access.yml`, a second follow state beside the project's in
`follow`, the watch and `Reconcile`, the guard before every transport verb, an event per applied access commit, docs
and an acceptance scenario with a private repository.

**What must be true.** A project whose signed public `project.yml` names `access: { repository: <url> }` takes its host,
its
developers and its transports' settings only from `access.yml` in that repository, verified against the project's own
signers and applied as the project file is. Until that file is applied, no transport verb runs for the project.

## Why

[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) (option b): the project file is
public on GitHub. With
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) it would
carry the host's address and WireGuard endpoint, every developer's account name, laptops and ssh keys, and the
conversation server's name, room and CA. Those go into a second signed file in a private repository.

## Plan

Read in the code on 2026-10-28.

**What is there.** A followed project is one repository (`FollowedProjects.Followed`: name, url, commit in force,
outcome, `unverified`). `Reconcile.run` fetches it, verifies the commit against the pinned signers (`SignedBy`,
`MachineSigners`, the handover rules `ReconcileTest` holds), requires descent from the commit in force, and
applies `project.yml`. `ConfigurationWatch` runs that every 5 minutes; `FollowSignedBy` at a follow. `ProjectReader`
(`core`) reads the file into `Project`; a transport's settings are `Mail.transports()`, handed to the transport by
`MachineTransports.settings(project, scheme)` and `TransportLifecycle`. A transport handed empty settings sets up the
account's own homeserver.

**The plan:**

1. **The public file:** `access: { repository: <url> }` in `project.yml`. Once it is named, `ProjectReader`
   refuses transport settings in `project.yml`; a project without it is read as today.
2. **`access.yml`** (a reader in `core`, `AccessFile`): `host:` (`address`, `endpoint`, `wireguard_public_key`,
   `network`, `ssh_host_key`, `ca_sha256`); `developers:` keyed by account (the account pattern), each with
   `laptops: [{ssh_public_key, wireguard_public_key, vpn_address}]` and an optional `forge_login`; `transports:`, each
   transport's settings. It refuses `signers`, unknown keys, a key not of its form, a `vpn_address` outside `network`,
   its `.1`, or one used twice. Public keys and addresses only.
3. **Following:** `FollowedProjects.Followed` gains the access repository's commit in force and outcome.
   `Reconcile` fetches it with the person's own git credential for its host (as for the project), verifies each commit
   against the signers the project's chain has in force, and requires descent from the access commit in force (else
   `--accept-rewrite`). A machine that cannot fetch keeps what it applied and says so.
4. **The guard:** for a project naming `access:`, no transport lifecycle verb (`setup`, `enroll`, `join`, `admit`,
   ...) and no `send`/`poll` runs until its access file is applied: not readable, not verified, waiting for a rewrite,
   or naming no settings for a transport the project uses each refuse with the reason, and a task's start says the
   same. Empty settings never reach a transport for such a project.
5. **Merged settings:** `MachineTransports.settings` and `talk settings` take the transport's settings from the
   applied access file.
6. **The event log:** an applied access commit is a `project-applied` line naming the access repository
   (`Reconcile.recordApplied`).
7. **Docs:** `doc/project-file.md` (the two files, who reads which, what each reveals, how a project moves its
   transport settings into an access file), `doc/messages.md`.
8. **Red first:** a reader test per refusal; `ReconcileTest` cases for an access commit not signed by a signer in
   force, not descending, and a signer removed in the project's chain; a test that a transport is never run with
   empty settings for a project naming `access:`.

## Acceptance

- Seen to fail first, then green: each refusal above in a test of the module that carries it.
- An acceptance scenario follows a project with an access file in a second local repository: its transport settings
  come from it; an access commit by a key the project does not hold is not applied; while the access file is not
  applied, a task's start and `talk` refuse naming why.
- `doc/project-file.md` says what each file reveals and who needs read access to which.

## To be checked

- Whether a project's own `project.yml` in this repository moves its `mail.transports` into an access file at once,
  or only when [PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78)'s host is set up.
