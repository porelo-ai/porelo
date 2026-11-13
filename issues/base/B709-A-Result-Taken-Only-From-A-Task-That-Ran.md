# B709 — A Result Taken Only From A Task That Ran

```json
{
  "$schema": "issue-1.json",
  "id": "B709",
  "title": "A Result Taken Only From A Task That Ran",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 63,
  "blockedBy": [],
  "covers": "A listing takes a task's result only once its container has run and stopped, never while it is created and not yet running, and a result a listing took as missing is taken again when the task ends.",
  "openQuestions": 0
}
```

**Why this size.** One condition in `TaskInventory.resultOf`, one in `TaskResult.takeOnce`, and their tests.

Filed 2026-11-13 from Frontend's walk of an interface story on the test VM, with Core 40b17b120. A listing made while a
discussion's container was created but not yet running took its result as `MISSING`: `resultOf` is asked with
`over = ended != null || !summary.running()`, and a created container is not running. `takeOnce` keeps the first
record it took, so when the container ended half a second later, listings answered `running: false` with the result
`missing` until the stop's own take rewrote it as `handed`. The interface read that half second's "missing" as final
and dropped a valid result. `TaskControl`'s comment near its stop knows this case, but fixes it for an explicit stop
only.

**What must be true.** A listing takes a result only from a container that has run and stopped. A `MISSING` that a
listing took is provisional: the take when the task ends, or the next listing after it, takes again.

## Plan

- `TaskInventory.resultOf`: `over` only when the agent ended or the container stopped after running; a container in
  podman's `created` state is not over.
- `TaskResult.takeOnce`: a kept record that is not `HANDED` is taken again; only `HANDED` is final.
- Red first: a listing of a created, not yet running container with a result file arriving after takes nothing, and
  a later take hands it; a kept `MISSING` with the file now in the box is taken as `HANDED`.

## Acceptance

- Both tests hold; each fails without its change.
