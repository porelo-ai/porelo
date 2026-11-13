# B718 — The Drafts Served Over The Daemon

```json
{
  "$schema": "issue-1.json",
  "id": "B718",
  "title": "The Drafts Served Over The Daemon",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 6,
  "blockedBy": [
    "B220"
  ],
  "covers": "The daemon serves `ProposeProject`, streamed with one reply per build-tool plugin, and `TakeProposal`; each repository in `default` records its proposal as UNASKED or ASKED, CONFIGURED computed, and an older daemon's MethodNotFound reads as \"cannot propose\".",
  "openQuestions": 0
}
```

**Why this size.** Two daemon methods, one of them streamed, a recorded state per repository and its wire rows, and their tests; the merge and the plugins' contract stand from [B220](README.md#b220) and [B221](README.md#b221).

Split from PJ52 on 2026-11-13. [B221](README.md#b221) built its smallest form and left out steps 1, 5, 6 and 7: the daemon's methods, streaming and the recorded state. The interface's F142 needs them. Matching a checkout to a `repositories` entry, [B221](README.md#b221)'s own To be checked, is settled here. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `ProposeProject` (streamed, one reply per adapter) and `TakeProposal` in the wire contract, announced before the commit.
- `DefaultRepository.proposal` recorded as UNASKED or ASKED; CONFIGURED computed from the files present.
- The CLI's client reads an older daemon's MethodNotFound as "cannot propose".

## Acceptance

- Red first: a daemon test with a stub plugin streams one reply per plugin and `TakeProposal` records ASKED.
- An older daemon answering MethodNotFound gives "cannot propose", never an error.
- No credential appears in any reply.
