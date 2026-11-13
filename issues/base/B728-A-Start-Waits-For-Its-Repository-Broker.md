# B728 — A Start Waits For Its Repository Broker

```json
{
  "$schema": "issue-1.json",
  "id": "B728",
  "title": "A Start Waits For Its Repository Broker",
  "state": "Review",
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

## Built

- `CredentialWiring.startRepositories` holds the repository broker to what [B697](README.md#b697) holds the vault
  broker to:
  - Its process handle goes to the start's own helpers.
  - One deadline (`GateWiring.HELPER_PATIENCE`) is taken at its start. Within it the start waits for the socket and
    then for `repository.pid` through `HelperPid.await`.
  - A broker that does not come up is ended by its own handle, never by the pid files of the name. The start says
    "the repository broker did not come up" and throws `BrokerDown`, which `TaskLaunch` already ends with 70 and its
    cleanup.
  - A broker that cannot be launched at all fails the start in the same way, as the vault broker does.
  - A project without a credentialed repository, or one whose credentials the vault cannot give, goes on without the
    broker as before.
- Tests:
  - `TaskRunCommandTest.aStartWhoseRepositoryBrokerNeverRecordsItsPidFailsAndLeavesItNotRunning`: the broker ignores
    TERM; the start gives 70 and the broker's message, and no helper is left running.
  - `RepositoryWiringTest.aRepositoryBrokerThatNeverRecordsItsPidIsNotUpAndIsNotLeftRunning`: a silent broker is
    refused and ended; one that comes up is used.

  Both failed on the build box before the change (`buildbox-porelo-17322001859c-20261113T173305Z.log`,
  `buildbox-porelo-8d6dfd9319f0-20261113T174310Z.log`) and pass after it
  (`buildbox-porelo-e0cc541bf1a3-20261113T173510Z.log`, `buildbox-porelo-d6e861687e40-20261113T174457Z.log`): 305
  tests, 0 skipped. A broker that comes up is covered at the wiring level; a whole start would need a real namespace
  for the repository relay.
