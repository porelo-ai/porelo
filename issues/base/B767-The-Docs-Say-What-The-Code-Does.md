# B767 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "B767",
  "title": "The Docs Say What The Code Does",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": [],
  "covers": "Every sentence of porelo's user-facing docs (README.md, doc/ but decisions.md, the changelog) that the code on main does not bear out is corrected, or filed as a bug where the code is wrong",
  "openQuestions": 0
}
```

**Why this size.** S: the reading is done by sub-agents, then each finding is checked in the code by hand and fixed in
one commit. Estimate: 38 minutes (Core's median for S).

**What must be true.** A command, flag, path, default, state, message or behavior a page names is the one the code
has. Where the page is right and the code wrong, the bug is filed, not the page changed. No behavior changes here.

Filed 2026-11-14 on the operator's order, under ST361.

## Plan

- Sub-agents on Sonnet read the pages against main (196b13fb0), split into commands.md, credentials.md with
  project-file.md and running.md, and the rest. They run no test suite and no VM.
- Each finding is checked in the code. Only a confirmed one is changed.
- Left to B766, which is held unpushed on another branch: `doc/how-it-works.md`'s gate section and
  its section on `default`.
