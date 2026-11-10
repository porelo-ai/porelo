# B153 — Unused Slots And Keys Reported

```json
{
  "$schema": "issue-1.json",
  "id": "B153",
  "title": "Unused Slots And Keys Reported",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 22,
  "blockedBy": [],
  "covers": "Doctor reports left deploy keys, and device slots and lent credentials unused for 45 days (a setting in a new account-wide `config.yml`); last use recorded where credentials are lent; only what Porelo lends or opens with locked, where the settings ask."
}
```

**Why this size.** a new account-wide settings file and its reader, a last-use record for deploy keys, a lock flag on a
keyslot, a doctor probe, one contract field, docs.

**What must be true.** `porelo doctor` reports every deploy key an earlier version left and a device slot or credential not
used for a set period; of these, only what Porelo lends or opens with may be locked. Decided 2026-11-04.

## Why

Access that nobody uses stays open until somebody notices.

**Guideline points it answers:** ACSC (privileged access after inactivity). From a review of security guidelines, 2026-10-09.

## The shape

Last use recorded per slot and key; `porelo doctor` names those unused for longer than a period, 45 days by default,
set in a new account-wide settings file. By default they are only reported; a slot or key is locked only where the
settings ask for it.

## Plan

Read in the code on 2026-11-04:

- A device slot already records its last use: `Keyslot.lastUsed` (`vault/src/main/java/org/fuin/porelo/vault/
  Keyslot.java`), written by `VaultFile.used(opener, id)` when a device opens the vault, readable while the vault is
  shut, and said by the daemon's `Keyslots` (`30-vault.varlink`). The `passphrase` slot is no device and stays
  out of this.
- Deploy keys remain only for cleanup in `LeftDeployKeys.Key`
  (`apps/app-model/src/main/java/org/fuin/porelo/app/LeftDeployKeys.java`); Porelo no longer makes or lends them.
  `VaultGitCredentials.forUrl` (`apps/app-model/src/main/java/org/fuin/porelo/app/VaultGitCredentials.java`) lends
  the credential chosen by `FollowCredential`, not a project deploy key: a left key is never used and still opens the
  forge, so it is reported whatever its age, and it is not locked, there being nothing to refuse. A key a person keeps
  under `git.ssh.<host>` is lent, and counts as a credential. Unused-access reporting and locking are not built.

There is no account-wide settings file today: `XdgPaths.config`
(`core/src/main/java/org/fuin/porelo/core/config/XdgPaths.java`) names `$XDG_CONFIG_HOME/porelo`; nothing reads a
`config.yml` there.

Steps:

1. Red first, in `apps/app`: a vault with a device slot last used 100 days ago and a left deploy key; `porelo
   doctor` names neither today.
2. The account's settings: `AccountSettings` in `core` reads `config.yml` from `XdgPaths.config`, a missing file
   being every default; its schema refuses an unknown key by name, unlike `ProjectSchema`, which warns on other
   unknown keys. This issue's keys:

       unused_access:
         days: 45      # reported after this many days without use
         lock: false   # true: a slot or lent credential unused that long is locked, not only reported

   A page `doc/settings.md` (in `mkdocs.yml`) lists every key with its default, and a documents test holds the page
   and the schema together, as `DocumentedProjectFileTest` does for the project file. Other account-wide settings
   ([B155](README.md#b155), [B160](README.md#b160)) go into the same file.
3. `CredentialUse` in `app-model`: a plain file beside the vault (`VaultPaths`), entry name to an RFC 3339 instant,
   no secret in it, so `doctor` reads it while the vault is shut. `VaultGitCredentials.forUrl` writes it on a lease,
   at most once an hour per entry so a fetch loop does not rewrite it. [B156](README.md#b156) reads the same file for every
   credential.
4. `DoctorCommand.probesFor` gets an "unused access" probe: `DEGRADED` naming each left deploy key and each device slot or
   lent credential unused for longer than `unused_access.days`, with when each was last used (or "never") and the
   remedy that takes it away (`RevokeKeyslot` / `porelo vault devices`, removing a key at the forge and
   `LeftDeployKeys.forget`). `OK` when none is; a shut vault says the left keys cannot be read. The daemon's `Doctor`
   carries it as it carries every probe; `--json` too.
5. Locking, only where `unused_access.lock` is true: `Keyslot` gains `locked` (read as false where the header has none);
   `Keyslots` refuses to open with a locked slot and says why; the passphrase lifts the lock. A lent credential is
   locked by `VaultGitCredentials` refusing to lend it, by name, until a person lends it again; revoking it at the
   forge remains required. A left deploy key is only reported.
   The lock is applied when the slot or key is next presented, so nothing runs in the background.
6. `Keyslot` in `30-vault.varlink` gains `locked`; `InterfaceDescriptionTest` holds it.
7. `doc/credentials.md` says what is reported, after how long, that it is only reported unless the settings ask for
   a lock, and what a lock does.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- With no `config.yml`, a device slot unused for 46 days is reported and still opens the vault; with
  `unused_access: {lock: true}`, it is refused with the reason until the passphrase lifts the lock.
- A left deploy key is reported whatever `unused_access.days` says, and no setting locks it.
- `unused_access.days` changes when a slot or lent credential is reported; an unknown key in `config.yml` is refused by name.
- The documentation page that describes the area says it.

## To be checked

- [B117](README.md#b117) reads the vault's left keys at a clearing: this probe takes the same read.
