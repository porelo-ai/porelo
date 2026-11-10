# B501 — Matrix In Doctor

```json
{
  "$schema": "issue-1.json",
  "id": "B501",
  "title": "Matrix In Doctor",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 67,
  "blockedBy": [],
  "covers": "`doctor` says whether the machine can carry its projects' messages: the transport installed, a local homeserver answering."
}
```

**Why this size.** a doctor probe, its line in `Doctor`, and its test.

**What must be true.** `porelo doctor`, and the daemon's `Doctor`, say whether this machine can carry its projects'
messages: the installed transport, and for a local homeserver whether it answers. A transport that is not installed
where a project names one, or a homeserver that does not answer, is a state with its next action; a machine whose
projects name no transport says it needs none.

## Plan

- A `Probe` named `messages` in `DoctorCommand.machine()`, so `Doctor` carries it as a row with no change to the
  contract: for each project `ProjectInventory` lists, what `Conversations.row` says its conversation is; the
  transport found in `TransportDirectory` and asked `describe` through the context (`context.runner()`), never a
  process of its own; `DoctorCommand.ready` decides as before.
- Porelo knows no transport, so "a local homeserver" is read off what the transport's `setup` recorded: a
  conversation whose `reaches` are loopback alone runs on this machine, and each such address is asked whether it
  accepts a connection (one second); a server elsewhere is not asked.
- Not installed, not answering `describe`, and a server here that does not answer are `DEGRADED`, each with its next
  action (install the transport; run its `describe` by hand; start a task of the project, whose start sets the
  conversation up and so starts the server); a project file that does not read is `UNKNOWN`; no transport named is
  `OK`, "needs none".
- Red first: `DoctorMessagesTest`, with a faked transport that does not answer and with no transport named.

## Acceptance

- Answering, not answering and not needed are three states, each with its next action.
