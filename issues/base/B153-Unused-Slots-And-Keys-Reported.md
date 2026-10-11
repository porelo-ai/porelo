# B153 — Unused Slots And Keys Reported

**Status:** Analyzed.
**Size:** M - a last-use record for deploy keys, a lock flag on a keyslot, a doctor probe, one contract field, docs.

**What must be true.** A device slot of the vault or a deploy key not used for a set period is reported, and may be locked.

## Why

Access that nobody uses stays open until somebody notices.

**Guideline points it answers:** ACSC (privileged access after inactivity). From a review of security guidelines, 2026-10-09.

## The shape

Last use recorded per slot and key; `sokar doctor` names the unused; locking per setting.

## Plan

What is there today:

- A device slot already records its last use: `Keyslot.lastUsed` (`vault/src/main/java/org/fuin/sokar/vault/
  Keyslot.java`), written by `VaultFile.used(opener, id)` when a device opens the vault, readable while the vault is
  shut, and said by the daemon's `Keyslots` (`30-vault.varlink`). Keyslot 0, the passphrase, is no device and stays
  out of this.
- A deploy key records no use at all. `DeployKeys.Key` (`apps/app-model/.../DeployKeys.java`) holds what was made;
  every use goes through `VaultGitCredentials.forUrl` (`apps/app-model/.../VaultGitCredentials.java`), the one place
  a git credential is lent to the gate's fetches and pushes.

Steps:

1. Red first, in `apps/app`: a vault with a device slot last used 100 days ago and a deploy key never lent; `sokar
   doctor` names neither today.
2. `CredentialUse` in `app-model`: a plain file beside the vault (`VaultPaths`), entry name to an RFC 3339 instant,
   no secret in it, so `doctor` reads it while the vault is shut. `VaultGitCredentials.forUrl` writes it on a lease,
   at most once an hour per entry so a fetch loop does not rewrite it. [B156](README.md#b156) reads the same file for every
   credential.
3. `DoctorCommand.probesFor` gets an "unused access" probe: `DEGRADED` naming each device slot and deploy key unused
   for longer than the period, with when each was last used (or "never") and the remedy that takes it away
   (`RevokeKeyslot` / `sokar vault devices`, `DeployKeys.forget`). `OK` when none is. The daemon's `Doctor` carries
   it as it carries every probe; `--json` too.
4. Locking, where the setting asks for it: `Keyslot` gains `locked` (read as false where the header has none);
   `Keyslots` refuses to open with a locked slot and says why; the passphrase lifts the lock. A deploy key is locked
   by `VaultGitCredentials` refusing to lend it, by name, until a person lends it again; revoking it at the forge
   stays `DeployKeys.forget`'s. The lock is applied when the slot or key is next presented, so nothing runs in the
   background.
5. `Keyslot` in `30-vault.varlink` gains `locked`; `InterfaceDescriptionTest` holds it.
6. `doc/credentials.md` says what is reported, after how long, and what a lock does.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- The period and its default (45 days is what the guideline names), and where it is set: there is no account-wide
  setting file today (the operator's).
- Whether locking is on by default or only reported (the operator's).
- [B117](README.md#b117) lists every deploy key at a clearing; whether its list shows the last use from the same record
  (Agent Core's).
