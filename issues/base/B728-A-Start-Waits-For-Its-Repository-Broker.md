# B728 — A Start Waits For Its Repository Broker

```json
{
  "$schema": "issue-1.json",
  "id": "B728",
  "title": "A Start Waits For Its Repository Broker",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 55,
  "blockedBy": [],
  "covers": "The repository broker is held to what a start asks of its other helpers: the start waits for its pid, a broker that does not come up fails the start with 70 and is ended by its own handle, never by the pid files of the name.",
  "openQuestions": 0
}
```

**Why this size.** The same wait and end the vault broker has, applied to one more helper, with tests. Estimate: 45 minutes (Core's median for S).

Found while stacking [B384](README.md#b384) on main: the repository broker that came in beside it (`CredentialWiring.startRepositories`) waits only for its socket, is not told to the start's owned handles, and a broker that does not come up is said and the start goes on without it. [B384](README.md#b384)'s wait for the relays now covers the repository relay as well; the broker itself is left as it was.

## Plan

- Hand the broker's handle to the wiring's owned handles, as the vault broker does.
- Wait for its pid file within the helpers' patience; one that does not come up is ended within the grace and fails the start with 70.

## Acceptance

- Red first: a stand-in broker that never writes its pid fails the start with 70, and its process is gone when the start answers.
- A broker that comes up starts the task as today.
