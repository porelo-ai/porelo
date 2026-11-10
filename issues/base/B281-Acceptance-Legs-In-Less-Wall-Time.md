# B281 — Acceptance Legs In Less Wall Time

```json
{
  "$schema": "issue-1.json",
  "id": "B281",
  "title": "Acceptance Legs In Less Wall Time",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 72,
  "blockedBy": [],
  "covers": "The acceptance legs take ~5–6 min less wall time with the same scenarios and guards: split `task-guarded.feature`'s ~3 min single-thread tail across accounts, pass `-Dsurefire.skip=true` in `Leg.java`'s two suite passes (~1:45 min), and parallelize or batch `TerminalSteps.java`'s `@AfterAll` project and container removal (~2 min)"
}
```

**What must be true.** The acceptance legs take less wall time, with the same scenarios and the same guards.

## Why

Measured across the Fedora and Ubuntu legs: a leg takes about 26 min in total, with about 18 min in the acceptance
suite itself (pass 1 without restarts about 14 min across four accounts, pass 2 with restarts about 3.5 min on one
account). Fixed sleeps add only about 38 s of thread time per leg (inferred from step counts); container and image
work, repeated per-scenario setup and a single-thread tail dominate.

Three levers together save an estimated 5 to 6 min of wall time per leg without weakening any guard:

1. **The serial feature tail.** `acceptance/suite/pom.xml` runs each feature on `same_thread` so a feature keeps its
   account. `task-guarded.feature` takes about 5 min (measured: 4m39s on Fedora, 4m56s on Ubuntu) and runs alone on one
   thread for about 3 min after the other three feature threads finish. Splitting it into smaller features that each
   keep their own account lets the threads share that tail.
2. **Redundant unit tests in both suite passes.** `acceptance/legs/src/main/java/org/fuin/porelo/legs/Leg.java` runs
   both acceptance suite passes without
   `-Dsurefire.skip=true`, so each pass runs `wire`'s and the kit's unit tests again, taking about 1:45 min per leg
   (measured: 1m45s on Fedora, 1m48s on Ubuntu).
3. **Serial removal of projects and containers at the end of a pass.** The `@AfterAll` teardown in
   `acceptance/kit/src/main/java/org/fuin/porelo/acceptance/TerminalSteps.java` (`removeTheProjectsTheKitMade`)
   runs `porelo project remove --force` for every project and removes its directory one by one,
   taking about 2 min serially per leg (measured: 2m10s on Fedora, 1m59s on Ubuntu). Running that removal in parallel
   across accounts or batched per account cuts most of that wait.

## Not in scope

- The bounds that `agent-ready.feature` (`ReadySteps.java`, `Repeated.java`) and the blocked-egress checks in
  `task-guarded.feature` wait out on purpose, since they prove something does not come.
