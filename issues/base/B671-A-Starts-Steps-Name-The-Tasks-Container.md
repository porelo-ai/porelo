# B671 — A Start's Steps Name The Task's Container

```json
{
  "$schema": "issue-1.json",
  "id": "B671",
  "title": "A Start's Steps Name The Task's Container",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 4,
  "blockedBy": [],
  "covers": "Every step of a Start from the container's creation on names the task's container, so a client that takes the task as started at AGENT STARTED and stops reading the call can follow it.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST314 as a run 27 finding (step 6), straight into the backlog as the bug rule says.

**What was seen.** The window called Start with a prompt and waited for its final reply to report STARTED. A Start
with a prompt gives its final reply when the agent's run ends, and the builder's pi never ended its run. So the start
dialog showed "Waiting for Start" after 600 s, and the next task was never started. The window should take STARTED from
the streamed step `AGENT`/`STARTED`. That step named no container, so the window could not follow the task without
another `List`.

**What must be true.** Every Start step after the container exists (HELPERS and AGENT, in every state) carries
`container`. A client may close Start's stream after AGENT STARTED: the run goes on, and its end is read from List and
Watch as for any task.

**Size.** S: one field in the step, set where the container stands, and its contract line.

## Built (2026-11-12)

- `StartStep` carries `container`, empty before the container exists. `asMap` puts it only once it is set, and
  Tasks1's `StartStep` has `container: ?string`.
- `StartSteps.at(container)` is called where a new task's helpers start, where a brought-back task's helpers start,
  and before the AGENT step of a run in an existing task.
- `Start`'s `now` stays unread by the daemon. A disconnected client already leaves the run alone
  (`PoreloDaemon.StreamingWriter`).

## Acceptance

- The AGENT STARTED step of a Start names the task's container; a step before the container exists does not. Seen red
  first (`StartStepsTest`).
