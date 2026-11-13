# B704 — A Run Follower Test That Waits For What It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "B704",
  "title": "A Run Follower Test That Waits For What It Reads",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 58,
  "blockedBy": [],
  "covers": "`RunFollowerTest.noThreadIsInterruptedSoNeitherTheAgentsNorTheCallersSocketIsClosed` does not depend on a 50 ms sleep to place its line between two reads.",
  "openQuestions": 0
}
```

**Why this size.** One test's timing, made explicit.

Filed 2026-11-13 from a whole build of Core's batch stack. The test failed once (`sent` empty where `> last` was
expected) on a loaded host, and then passed 5 of 5 alone. It sleeps 50 ms after starting a follower that reads every
200 ms, appends a line, and closes, expecting only the read after the end to find it. Under load, the follower's first
read may not have happened yet, or the close may not wait for the last read.

**What must be true.** The test holds on a loaded host, and still fails if the close interrupts a thread or skips
the read after the end.

## Plan

- Read `RunFollower.close` first: if it can end without the final read, that is a product fault, and the test is right
  to fail.
- Otherwise: the test waits for the follower's first read (a latch in the formatter) instead of sleeping.

## Acceptance

- 50 runs in a row under a parallel load pass; the test fails while `close` skips the last read.
