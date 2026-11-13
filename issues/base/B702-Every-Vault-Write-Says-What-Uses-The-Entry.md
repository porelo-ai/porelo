# B702 — Every Vault Write Says What Uses The Entry

```json
{
  "$schema": "issue-1.json",
  "id": "B702",
  "title": "Every Vault Write Says What Uses The Entry",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 56,
  "blockedBy": ["B316"],
  "covers": "`host stop-secrets`, `talk join --into-vault`, `vault login`, `vault authorize` and `vault import` say what uses the entry they store, through the one check `vault put` has, instead of writing past it.",
  "openQuestions": 0
}
```

**Why this size.** One shared call after each write, and a test per command.

Filed 2026-11-13 from [B316](README.md#b316): its "To be checked" answered that these commands write the vault
directly with `context.vault().update`, and `host stop-secrets` reuses `VaultPutCommand.replace` and `converted`
without the use check. None of them says what reads the entry, or that nothing does yet.

**What must be true.** Each command that stores an entry answers `used for` as `vault put` does, and storing under an
agent's name is the same warning.

## Plan

- Move the use check out of `VaultPutCommand` into one place the six commands call after their write.
- Red first: `host stop-secrets` storing a project's `builds.credential` name prints `used for` that project.

## Acceptance

- Each command's test sees the `used for` line; one fails while a command writes past the check.

## Built

`VaultEntryUse` (app-model) holds the one check `vault put` had: it names what reads the stored entry,
and it warns when the entry's name or kind serves no use. After each successful write,
`host stop-secrets`, `talk join --into-vault`, `vault login`, `vault authorize`, `vault import` and
`vault put` call it. `vault authorize` reports the logical entry's use, never its hidden grant's name,
and only after the grant is kept.

Tests (apps/app): `CredentialImportTest.importingNamesTheProjectThatUsesTheStoredEntry`,
`keepingASignInFromADirectoryNamesTheProjectThatUsesIt`,
`HostStopSecretsCommandTest.aStoredBuildCredentialNamesTheProjectThatUsesIt`,
`everyStoredEntryGetsTheSharedNameCheck`,
`VaultAuthorizeCommandTest.authorizationReportsTheUseOnlyAfterKeepingAGrant`,
`VaultLoginCommandTest.aSuccessfulLoginReportsTheUseOfTheEntryItStored`, and the join case in
`TransportConversationsTest`. Red on the base with only the tests changed: 9 failures in 211 tests.
Green on the change: 211 tests, 0 failures.
