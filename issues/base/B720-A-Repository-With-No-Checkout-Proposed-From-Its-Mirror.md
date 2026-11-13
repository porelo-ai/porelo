# B720 — A Repository With No Checkout Proposed From Its Mirror

```json
{
  "$schema": "issue-1.json",
  "id": "B720",
  "title": "A Repository With No Checkout Proposed From Its Mirror",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [
    "B220"
  ],
  "covers": "`MirrorTree.export` exports the gate mirror's default branch read-only into a fresh 0700 runtime directory, removed after, so the plugins can read a repository added by its address; `porelo/*` is never read.",
  "openQuestions": 0
}
```

**Why this size.** One export from the bare mirror and its removal, with tests.

Split from PJ52 on 2026-11-13. [B221](README.md#b221) left out step 2: a repository in `default` added by its address has only the gate's bare mirror. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `MirrorTree.export(repository)` into a fresh 0700 directory under the runtime directory, files read-only.
- Only the default branch; `porelo/*` refs never read.
- The directory removed after the proposal, also on failure.

## Acceptance

- Red first: an export of a test mirror holds the default branch's tree, read-only, 0700, and is gone after.
- A `porelo/*` ref's content never appears in the export.
