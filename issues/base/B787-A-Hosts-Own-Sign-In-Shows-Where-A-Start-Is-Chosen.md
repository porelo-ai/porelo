# B787 — A Host's Own Sign-In Shows Where a Start Is Chosen

```json
{
  "$schema": "issue-1.json",
  "id": "B787",
  "title": "A Host's Own Sign-In Shows Where a Start Is Chosen",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 4,
  "blockedBy": [],
  "covers": "Agents(), AgentSignIns() and the start's check tell a sign-in made on this host from one carried here before, show the first as signed in on the host, and say of the second that it is not renewed here and to sign in on the host",
  "openQuestions": 1
}
```

**Why this size.** M: what `Agents()`, `AgentSignIns()` and `StartCheck` answer for the two kinds of vault sign-in,
one added value on the contract, the start's words, with their tests. Estimate: 63 minutes (Core's median for M).

**What must be true.** Where a start is chosen on a host, a person sees whether the agent is signed in by a sign-in
made on that host, which the host renews, or by one carried there before variant C, which is never renewed there
and ends; for the second, the start says when it ends and to sign in on the host.

Filed 2026-11-15 under ST364, on the operator's decision of the two-machines question (variant C).

## Why

`Agents()` answers an agent signed in through the vault (`foundVia: vault`) the same whether the host's own login
made the entry - renewable, its refresh token hidden beside it - or a carry put a copy there. Hosts carry such
copies from before this decision; they end at their expiry and must not be renewed ([B785](README.md#b785)'s reason), so a person
must learn to sign in on the host before a start fails.

## Plan

1. One reading of a vault sign-in: *made here* (its refresh-token companion is there, as `LoginCredentials` keeps a
   login) or *carried* (an expiring copy without one).
2. `Agents()`: `foundVia` gains the value `login` for a sign-in made here; `vault` stays for one carried. An added
   value, not breaking; the contract's comment says what each means.
3. `AgentSignIns()`: a carried sign-in on a host reads as `SIGNED_IN` until it ends, then `NOT_SIGNED_IN`, as the
   start reads it.
4. `StartCheck`: a start with a carried rotating sign-in says when it ends and names the host sign-in ([B786](README.md#b786)); after
   its end it is refused with that remedy, as an ended credential is refused today.

## Red-first test

- A vault entry with its refresh companion: `Agents()` answers `foundVia: login`. Fails today: `vault`.
- A carried copy without one: `foundVia: vault`, and the start's line names its end and the host sign-in. Fails
  today: no such line.
- The same copy past its end: the start is refused with the host sign-in as remedy.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, an agent signed in there shows as signed in on the host; a carried copy says it ends.

## Open questions

1. The window's sentence for `login` beside the `vault` of an agent signed in through the vault (asked of Agent Frontend).
