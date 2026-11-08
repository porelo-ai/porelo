# [B501](README.md#b501) — Matrix In Doctor

**Status:** Backlog.
**Size:** S - a doctor probe, its line in `Doctor`, and its test.

**What must be true.** `porelo doctor`, and the daemon's `Doctor`, say whether this machine can carry its projects'
messages: the installed transport, and for a local homeserver whether it answers. A transport that is not installed
where a project names one, or a homeserver that does not answer, is a state with its next action; a machine whose
projects name no transport says it needs none.

## Plan

- A `Probe` asked through the context (`context.runner()`), never a process of its own, reading what the projects
  name and asking the transport's `describe`; `DoctorCommand.ready` decides as before.
- Red first: a unit test with a faked transport that does not answer.

## Acceptance

- Answering, not answering and not needed are three states, each with its next action.
