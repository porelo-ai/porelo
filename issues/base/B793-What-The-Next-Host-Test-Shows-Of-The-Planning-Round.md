# B793 — What the Next Host Test Shows of the Planning Round

```json
{
  "$schema": "issue-1.json",
  "id": "B793",
  "title": "What the Next Host Test Shows of the Planning Round",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 7,
  "blockedBy": [],
  "covers": "The host acceptances of the planning round's four issues, finished on their tests alone, are seen on a rented host, and what they left to check there is answered",
  "openQuestions": 2
}
```

**Why this size.** S: one walk on a rented host through four acceptances already built, and two readings there.
Estimate: 38 minutes (Core's median for S).

**What must be true.** Each acceptance the round named for a rented host is seen there once, or becomes an issue of
its own.

Filed 2026-11-15 when the operator set the four issues of ST367 and ST368 to Finished on their tests: these are what
those issues still held. Findings of the next host test become new stories and issues.

## Why

The four were finished with their tests green and their whole runs passed, but no rented host has run them yet:

- The review read (only what the host carries): on the host, "Needs you" holds no refused review read for a repository
  not carried there.
- A task's pull request opened by the task: an unattended task's pull request names the task's account as its author,
  and the person approves and merges it.
- A planned start on central's newest main: a planned start right after the planner saved to central works on the
  planner's file; on a diverged copy it is refused with the way back named.
- A stage task that reads what the planner gives: an In-analysis task started by the window reads the issue it was
  given, writes `analysis.json`, ends, and no commit or pull request appears on any forge (the window's walk on walk1
  went over this path; its report says whether it is seen).

## Plan

1. One walk on a rented host through the four, with the project's own repository and one other.
2. A step that fails is filed as a bug of its own.

## Acceptance

- Each of the four is seen on the host, or names the issue filed for it.

## Open questions

1. From the planned start on central's newest main: the PI68 task still showed "1 waiting at the gate" after its pull
   request was merged. Whether that push still waits in the gate's incoming refs, whether the gate's measurement sees it
   landed (`alreadyUpstream`), and whether the task's end removes it; if it stays, it is a bug of its own.
2. From the reading of the task's own pull request: on a host, a pull request is opened with the task's own forge token,
   but the builds are still read with the credential the project names for its builds, and that one could be the
   person's own; the code cannot tell. Whether a host's builds credential must be the host's own, and how that is said.
