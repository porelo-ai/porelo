# B758 — A Resumed Task Brings Back Every Relay It Needs

```json
{
  "$schema": "issue-1.json",
  "id": "B758",
  "title": "A Resumed Task Brings Back Every Relay It Needs",
  "state": "Idea",
  "priority": "Medium",
  "blockedBy": [],
  "covers": "A resume never answers success for a task whose relay is missing or belongs to the run before, and a relay serving two endpoints ends when one of them ends",
  "openQuestions": 0
}
```

**Severity:** medium - a task's agent loses its way to the broker and the start says nothing; no credential leaks
and nothing is lost.

**What must be true.** A resumed task either has every relay its agent needs, started for the container's current
process, or the resume fails and says which one is missing.

- A start that fails after the container ran but before the relay recorded itself keeps the container for a resume.
  Its `resume.json` then names the broker but no relay, and a resume starts the broker and the container and answers
  success, while a socket agent has no socket and a URL agent no endpoint.
- On resume a relay whose pid file still names a live process is counted as started and not given the new
  container's pid. The poststop hook reaps helpers when a container stops, so this needs a relay that outlived its
  container; nothing then checks that it serves the current one.
- `porelo vault relay` with `--listen` and `--bind-in` together waits for each endpoint's thread in turn. If one
  thread ends, the process lives on with the other endpoint alone, and nothing restarts the dead one.
