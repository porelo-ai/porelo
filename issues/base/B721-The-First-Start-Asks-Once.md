# B721 — The First Start Asks Once

```json
{
  "$schema": "issue-1.json",
  "id": "B721",
  "title": "The First Start Asks Once",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [
    "B718",
    "B719"
  ],
  "covers": "`porelo task start` in `default` on a repository whose proposal is UNASKED asks once at a terminal whether to propose its tools, never answered by `--yes`; without a terminal it starts as today and names `porelo project init` in one line.",
  "openQuestions": 0
}
```

**Why this size.** One question in the task start on the recorded state, and its tests.

Split from PJ52 on 2026-11-13. [B221](README.md#b221) left out step 8: the question at `task start`. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `TaskLaunch` reads the repository's proposal state through [B718](README.md#b718).
- UNASKED at a terminal: one question; the answer recorded as ASKED either way; yes writes the draft through [B719](README.md#b719).
- `--yes` never answers this question; without a terminal one line names `porelo project init` and the start goes on.

## Acceptance

- Red first: an UNASKED start at a terminal asks once, the second start does not.
- A start with `--yes` and no terminal does not ask and names `porelo project init`.
