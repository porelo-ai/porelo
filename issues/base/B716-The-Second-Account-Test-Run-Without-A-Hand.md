# B716 — The Second Account Test Run Without A Hand

```json
{
  "$schema": "issue-1.json",
  "id": "B716",
  "title": "The Second Account Test Run Without A Hand",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 70,
  "blockedBy": [],
  "covers": "The test that a peer of another account is not taken for this one runs in a CI job or VM role with two accounts, not only by hand.",
  "openQuestions": 0
}
```

**Why this size.** One job or role that makes a second account and runs one tag.

Filed 2026-11-13 from the reading of the tests that pass under any login and umask. The test that a socket peer of another account
is not taken for this one needs a second account on the machine. It is tagged `second-account` and left out of every
default and whole run, so it runs only when somebody runs it by hand on the VM. A test nobody runs guards nothing.

**What must be true.** The test runs in a job or role that has two accounts, and a run that skips it says so.

## Plan

- A CI step on the acceptance legs, or a VM role, that makes a second account and runs
  `-Dgroups=second-account -Dporelo.tests.excluded=` on wire and apps/app.
- Its result is in the run's summary like any other test.

## Acceptance

- The step runs the test and fails when the test fails.
