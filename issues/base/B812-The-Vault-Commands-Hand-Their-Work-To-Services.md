# B812 — The Vault Commands Hand Their Work To Services

```json
{ "$schema": "issue-1.json", "id": "B812", "title": "The Vault Commands Hand Their Work To Services",
  "state": "Accepted", "size": "M", "priority": "High", "rank": 10, "blockedBy": [],
  "covers": "VaultPutCommand, VaultAuthorizeCommand's keep and HostStopSecretsCommand move their use cases into services, and CredentialChange calls those services instead of the commands" }
```

**Why this size.** Three commands in `app-vault` and `CredentialChange`, which calls into the commands the wrong way round today.

**What must be true.** An entry point of porelo (a picocli command, a daemon method, the gate's HTTP handler) only
checks its input, maps it to a record, calls one service method and maps the answer back; the service holds the use
case (locks, revisions, rules, storing, events) without knowing the transport and is tested without it. Behavior
stays as it is. Read in porelo at f0de69ecc, by reading only.

## Plan

- `VaultPutCommand`: the reserved names, the `.pub` refusal, the key read back before it is stored, `vault.update`.
  These go into one service. `CredentialChange` today uses `VaultPutCommand.replace` and `fileValue`, and
  `VaultRemoveCommand.needsTheService` and `removeFrom`, and repeats the key check; it calls the service instead.
- `VaultAuthorizeCommand` keep: `vault.update` plus `AuthorizationsNeeded.clear`.
- `HostStopSecretsCommand`: `vault.write` and the keyring store.
- Each service has a unit test over fakes; a secret never reaches a line of its test output. The commands keep their
  options and their output.
