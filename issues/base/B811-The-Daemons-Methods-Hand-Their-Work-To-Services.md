# B811 — The Daemons Methods Hand Their Work To Services

```json
{ "$schema": "issue-1.json", "id": "B811", "title": "The Daemons Methods Hand Their Work To Services",
  "state": "Accepted", "size": "L", "priority": "High", "rank": 9, "blockedBy": [],
  "covers": "ProposeIdea, Done, LogArchive, PendingBundle, DeleteBackup, removeProject, CredentialDeclare and keepHostVaultShare move their work into services, and the daemon stops calling static methods of commands" }
```

**Why this size.** Eight daemon methods and five static calls into command classes, in `daemon`, `app-task`, `app-gate`, `app-organization` and `app-vault`.

**What must be true.** An entry point of porelo (a picocli command, a daemon method, the gate's HTTP handler) only
checks its input, maps it to a record, calls one service method and maps the answer back; the service holds the use
case (locks, revisions, rules, storing, events) without knowing the transport and is tested without it. Behavior
stays as it is. Read in porelo at f0de69ecc, by reading only.

## Plan

- **Daemon methods doing the work inline**:
  - `TaskMethods` ProposeIdea: the ideas limit, the reservation lock, the re-check and `TaskIdeas.propose`.
  - `TaskMethods` Done: the caller check, `TaskDone.record` and `TaskResult.take`.
  - `TaskMethods` LogArchive: the archive directory, then `TaskLogsCommand.archive`.
  - `GateMethods` PendingBundle: the temporary directory, the bundle size limit and the clean-up.
  - `ProjectMethods` DeleteBackup: searches the records, deletes the file, `records.forget`; no service exists for it.
  - `ProjectMethods` removeProject: the refusals, `ProjectDeletion`, `LeftDeployKeys.forget`.
  - `VaultMethods` CredentialDeclare: the dry run's outcome and "replaced".
  - `VaultMethods` keepHostVaultShare.
- **Static calls into commands** move to the services the commands then call too: `VaultLockCommand.forget`,
  `TaskLogsCommand.archive`, `VaultAuthorizeCommand.flowFor` and `keepGranted`, `DoctorCommand.probesFor` and `ready`.
- Each service has a unit test over fakes. Red first is the test of the service; the daemon's tests are reduced to
  the mapping.
