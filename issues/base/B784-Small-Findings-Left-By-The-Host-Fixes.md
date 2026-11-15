# B784 — Small Findings Left by the Host Fixes

```json
{
  "$schema": "issue-1.json",
  "id": "B784",
  "title": "Small Findings Left by the Host Fixes",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "Five small findings the readings of the host status, carried project, checkout start and refused start fixes left: an exit code, two untested paths and two acceptance gaps",
  "openQuestions": 0
}
```

**What must be true.** What the readings of those fixes noted as left is either fixed, tested, or written down as
accepted, so nothing of it lives only in a deleted issue.

- `porelo-host org status`: a caller that is not root but carries a login session's variables now gets 77 (root's
  alone) where it got 78; the read's fixture runs as root only, so that path has no test.
- A taken project whose placement record is malformed resolves as unknown and is taken again, which fetches before
  it refuses; a take through to the conversation's setup is shown on a machine only, not by a unit test.
- With no upstream, any checkout whose `project.yml` names the project counts as its checkout, not only the one it
  was made from; only commits reachable from the branch come over, forward only. No unit test drives the start with a
  checkout that gained a commit.
- The key scenario still removes the gate's mirror by path before its start; that goes once the start from the
  checkout is on the test machine, with the acceptance kit's fixture change.
- The test of a start refused before any container drives a credential refusal (exit 69), not the secret scan's
  (65), which refuses on the same side; a start refused after enrollment still leaves an unfinished start.
