# B740 — An Offline Transport Naming No Reach Is Refused

```json
{
  "$schema": "issue-1.json",
  "id": "B740",
  "title": "An Offline Transport Naming No Reach Is Refused",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 50,
  "blockedBy": [],
  "covers": "An offline project's transport whose setup reports no reaches at all is refused as one that reaches beyond loopback, never passed as reaching nothing.",
  "openQuestions": 0
}
```

**Why this size.** XS: one check in two places that read `reaches` and their tests. Estimate: under an hour, below
Core's median for S (45 minutes).

**What must be true.** For an offline project, Porelo passes a transport only when the transport says what it reaches
and all of it is loopback. A transport that says nothing is refused.

Found while estimating [B199](README.md#b199). When `setup` prints no `reaches`, the field becomes an empty list
(`TransportLifecycle`, where setup's answer is read). The empty list passes the loopback filter in
`TransportLifecycle.setupHeld` and in `TaskConversations` before a task enrolls. `--loopback-only` still goes to every
verb, so the transport is still told; but Porelo's own check does not hold.

## Plan

- `TransportLifecycle`: an absent `reaches` stays distinct from an empty one.
- For an offline project, both checks refuse a transport that names no reach, with the same refusal and the
  same remedy as a non-loopback reach.
- First red: a fake transport whose `setup` prints no `reaches`, in an offline project, is refused in both places.

## Acceptance

- An offline project with a transport that reports no `reaches` is refused at setup and before a task enrolls.
- A transport that reports only loopback addresses still passes.
