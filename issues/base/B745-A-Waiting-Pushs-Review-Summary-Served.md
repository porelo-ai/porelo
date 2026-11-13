# B745 — A Waiting Push's Review Summary Served

```json
{
  "$schema": "issue-1.json",
  "id": "B745",
  "title": "A Waiting Push's Review Summary Served",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": ["B291", "B166"],
  "covers": "The daemon answers, per waiting push, the counts of open findings and open review threads, and each check run's name, completeness in its own word and finding count, so an interface can say whether a push's checks finished.",
  "openQuestions": 0
}
```

**Why this size.** M, once the blocking issues are built: one method and its types in `Tasks1`, read from what the
review keeps. The interface's stand-ins and the docs follow. Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** A person deciding a waiting push sees how many findings and threads are still open and whether
each check finished, from the daemon's word, never from a guess of the interface's.

Filed 2026-11-13 for the interface's F107 (ST60), which waits for it, as Frontend's contract gaps name it ("A waiting
push's review summary").

Read in today's code: `Tasks1` serves no finding of a review, no review thread and no check run. Its `Finding` is a
first start's. A check run's completeness comes with the shared SARIF profile, [B291](README.md#b291). Findings and
threads kept by the host come with [B166](README.md#b166), which itself waits on [B291](README.md#b291). So the summary has nothing to
count until those are built.

## Plan

- `Tasks1` (`35-gate.varlink`, beside the waiting push): `ReviewSummary(project, repository, task, commit)`. It
  answers `openFindings` and `openThreads`, and `runs` with each run's `name`, `completeness` (the profile's word,
  passed on as it is), and its `findings`. An older daemon answers no method, and the interface reads "not reported".
- What it counts is what [B166](README.md#b166) keeps and [B291](README.md#b291) profiles, nothing of its own.
- Red first in `daemon`: a waiting push with two runs, one complete and one not, and an open finding and thread,
  answers those counts and words.
- `doc/Backend-API.md` in the interface's repository takes the method first, as the contract gaps ask.

## Acceptance

- A waiting push's summary names its open findings and threads and each run's completeness, in the daemon's word.
- A push with no run answers an empty list, never a made-up one.
