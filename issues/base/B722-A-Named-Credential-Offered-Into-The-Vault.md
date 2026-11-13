# B722 — A Named Credential Offered Into The Vault

```json
{
  "$schema": "issue-1.json",
  "id": "B722",
  "title": "A Named Credential Offered Into The Vault",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
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

## Built

- `porelo project init` at a terminal and without `--json`, after printing the proposal, offers once each credential
  the draft names and the vault does not hold: "'<id>' is not in the vault. Store it now? [y/N]".
- A yes runs the store command itself on the terminal, the declared record's own where one names it, else
  `porelo vault put <id>`; it reads the value without echo, so `project init` never holds it. A declared ssh key is
  asked for by its path and stored from that file. A record whose value lives outside the vault is not offered. A
  store that fails says what to run and the offer goes on; a declined one stores nothing and is offered again at the
  next proposal.
- A locked vault is said on one line, and nothing is offered.
- Red first in `ProjectInitCommandTest`: an accepted credential lands in the vault and its value is in no output,
  answer or argument; a declined one stores nothing; a locked vault is said. A held credential and `--json` offer
  nothing.
