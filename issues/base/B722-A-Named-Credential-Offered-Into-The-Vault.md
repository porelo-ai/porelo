# B722 — A Named Credential Offered Into The Vault

```json
{
  "$schema": "issue-1.json",
  "id": "B722",
  "title": "A Named Credential Offered Into The Vault",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 6,
  "blockedBy": [
    "B718"
  ],
  "covers": "Each credential id a draft names is offered for `vault put`, its value read without echo; the value appears in no draft, answer or log.",
  "openQuestions": 0
}
```

**Why this size.** One offer per named credential after a proposal, reading as `vault put` reads, and tests.

Split from PJ52 on 2026-11-13. Plugins name the credential a private repository needs but never hold it. PJ52 asks that the person can store it right then. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- After a proposal, each credential id named and not yet in the vault is offered once.
- Accepted: the value is read without echo and stored as `vault put` stores it.
- The value never appears in a draft, a daemon answer, a log line or a command line.

## Acceptance

- Red first: an offered credential accepted lands in the vault; the draft and the answers hold only its id.
- A declined offer stores nothing and asks again only on the next proposal.
