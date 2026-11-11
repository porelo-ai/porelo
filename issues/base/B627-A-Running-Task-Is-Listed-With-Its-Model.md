# B627 — A Running Task Is Listed With Its Model

```json
{
  "$schema": "issue-1.json",
  "id": "B627",
  "title": "A Running Task Is Listed With Its Model",
  "state": "Review",
  "size": "XS",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "A task's record - agent, mode, prompt, model and where it came from, branch, name - is written before its container starts, so `Tasks1.List` never shows a running task on the agent's own default model while its start is still placing files."
}
```

**Why this size.** one call moved in `TaskLaunch`, the parent's `started` event split off it, one test and a hook in
the test's runner.

Filed 2026-11-11 from a joint VM round of a move: the window started work with a model, the host's start said
`model z-ai/glm-5.3-flash (from start)`, and the window's step read `model ""` from the host's `Tasks1.List`.

**What must be true.** A client that lists tasks while a start is under way sees each running task with the model
its start chose, never "" for one that has a model.

## As built

- **The record was written last.** `TaskLaunch` started the container, then the helpers, the gate and the workspace
  files, and wrote `task.json` at the very end. On the host the task's listing had it running from 18:45:07Z, its
  record was written at 18:45:16Z, and a listing in between read no record: `model` "" and `modelFrom` "", which a
  client takes for the agent's own default.
- **It is now written just before `runner.start`**, when the agent, mode, prompt, model, the task's ref and its name
  are all settled. The event log's `started` event for a task another task started stays where it was, after the
  container runs, so the log does not claim a start that then failed.
- Test: `TaskRunCommandTest.aTaskIsDescribedBeforeItsContainerRuns` reads the record as podman is asked to start the
  container, through a hook on the test's runner. Red on the old order (no record), green on the new.

## Acceptance

- The test above, seen red first.
- The window's step "work started from the project on the host runs there, named and with its model" passes on a
  host with this build.
