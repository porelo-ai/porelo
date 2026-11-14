# B776 — A Start From the Checkout Starts From What It Holds

```json
{
  "$schema": "issue-1.json",
  "id": "B776",
  "title": "A Start From the Checkout Starts From What It Holds",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 133,
  "blockedBy": [],
  "covers": "A task started from the person's checkout in a project with no upstream starts from the checkout's commit, or its start says plainly that it does not and from which older commit it does",
  "openQuestions": 0
}
```

**Why this size.** M: the gate's refresh for a mirror seeded from the checkout, fast-forward only, its refusal when the
two have diverged, the start's line, and their tests. Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** When a start says it starts from the person's checkout, the agent gets the checkout's commit.
When it cannot, the start says so before anything is made, and names the commit it starts from instead.

Filed 2026-11-14 under ST361, from the red main of that day: the key scenario's start scanned and served a tree an
earlier scenario had left.

## Why

A project with no `upstream` has its mirror cloned once from the person's checkout (`GateSupport.gate` passes the
checkout as the seed, with no upstream to forward to). `GitGate.refresh()` brings a mirror up only to an upstream it
was cloned from, so for such a project it returns at once, and the mirror keeps the commit it was first cloned at.

The start still prints `starts from main (your checkout)`: `TaskBase.requested` takes the checkout's branch name,
and `TaskBase.select` resolves that name in the mirror. A person who committed since gets a task on an older tree,
told it is theirs. The secret scan reads that same older tree, which is right for what the task reads, but a key
committed since is neither found nor served, so nothing warns either way. This is the harm the operator met.

## Plan

1. When the start's base is `CHECKOUT` and the gate has no upstream, the mirror's branch is brought to the
   checkout's commit before the scan, fetched from the checkout fast-forward only (no force: a mirror ahead of the
   checkout holds a task's approved work).
2. When it cannot be (diverged, or the checkout unreadable), the start says so on its own line - "the mirror's main
   is not your checkout's: <mirror commit> here, <checkout commit> there; this task starts from the mirror's" - and
   goes on, as a refresh that fails already does.
3. A base the person names (`--from`), the project's `base_branch` and the mirror's default are untouched.
4. `doc/` where the start's base is described: what "your checkout" means for a project with no upstream.

## Red-first test

- A gate seeded from a checkout, a commit added to the checkout after: a start from the checkout starts from the new
  commit, and the secret scan reads it.
- The mirror ahead of or diverged from the checkout: the start says so, names both commits, and starts from the
  mirror's.

## Risks

- Fetching from the person's checkout reads their files as the gate; the seed is read so already.
- A mirror ahead of the checkout is ordinary (approved work not yet pulled); it must never be moved back.

## Blockers

None.

## Acceptance

- The red-first tests pass.
- On the VM: the key scenario passes without removing the mirror first; its removal (e0a4cdd93) can go.

## Open questions

None.
