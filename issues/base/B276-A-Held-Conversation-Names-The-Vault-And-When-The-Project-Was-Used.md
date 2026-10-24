# B276 — A Held Conversation Names The Vault And When The Project Was Used

**Status:** Analyzed, for sprint 2d.
**Size:** S - two fields in `Tasks1` (one in `ProjectMessages`, one in `Project`), each set at the one place its
reply is made, their tests and the contract's text; no new method.

**What must be true.** When a project's conversation waits on the vault, the daemon says which vault - the file on
this machine it checked - so an interface can say "the vault on this computer"; and every project it lists says
when it was last used here, so an interface can offer to stop following a project nobody uses any more.

## Why

From the operator's rented-host test of sprint 2c (finding 3): a row "the vault is locked ... Held up: sprint2" from
the local daemon read as if the new server's vault were meant, and `sprint2` - followed since an earlier test and no
longer used - was not recognizable as a project. The interface's words, its unlock button and its "stop following"
are porelo-frontend's; what the daemon knows and does not send is this issue.

## Plan

Read in `Conversations.row` (`apps/app-messaging`), `PoreloDaemon.projectRow`, `ProjectInventory`, `VaultPaths`,
`TaskProfile` and `MessageRecord`, and `10-common.varlink` / `20-project.varlink`.

1. **`ProjectMessages.vault: string`** - the path of the vault file the daemon checked (`VaultPaths.vaultFile()`),
   set beside `waitsOn` in `Conversations.row`: the vault's path when `waitsOn` is `"vault"`, `""` otherwise. A path,
   never a host name: `MachineName` would guess, and the daemon only ever checks its own account's vault.
2. **`Project.lastUsed: string`** - the latest of this project's task starts (`TaskProfile.startedAt` in the durable
   `state/tasks/<container>/task.json`) and message events (`record/log.jsonl` of its tasks' mailboxes), as an
   ISO-8601 instant, `""` when nothing is recorded here. Computed in `ProjectInventory` beside `following`, the one
   implementation `Projects()`, `WatchProjects()` and `project list --json` render. It is not a refresh time: a
   follow's `at` moves on every reconcile and says nothing of use.
3. **The contract's text** for both fields says what they are and are not; `Tasks1` only grows (two fields).
4. **`doc/`**: the page describing `Projects()` names the two fields.

**Red first:** `VaultLockedConversationsTest.aShutVaultNamesTheFileItChecked` (no `vault` field today) and
`ProjectInventoryTest.aProjectSaysWhenItsLastTaskStarted` with two tasks' profiles of different `startedAt` and one
mailbox event later than both - today there is no `lastUsed`.

## Acceptance

- A shut vault: `ProjectMessages.vault` is the vault's path, `waitsOn` `"vault"`; an open or absent one: `""`.
- `lastUsed` is the latest task start or message event of that project, `""` for a project with none; a task of
  another project does not move it. `InterfaceDescriptionTest` holds both fields to the contract.
- Agreed with porelo-frontend before building: the field names, and that "no longer used" is the interface's
  reading of `lastUsed`, not a judgement the daemon sends.
