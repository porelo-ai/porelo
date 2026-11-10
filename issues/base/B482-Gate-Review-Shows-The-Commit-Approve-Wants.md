# B482 — Gate Review Shows The Commit Approve Wants

```json
{
  "$schema": "issue-1.json",
  "id": "B482",
  "title": "Gate Review Shows The Commit Approve Wants",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 80,
  "blockedBy": [],
  "covers": "`gate review` shows the waiting commit in full, the id `gate approve --commit` takes; prefixes stay refused."
}
```

**Why this size.** one format and a refusal's wording; their tests. Estimate 15 minutes.

A finding of the operator's rehearsal (2026-11-06), Accepted on the operator's word the same day.

**What must be true.** The commit `porelo gate review` shows is the one `porelo gate approve --commit` takes: in full.
A refusal of a shortened id names where the full one is.

## Found

- `gate review` lists the waiting commits with `git log --oneline` (`GitGate.log`), seven characters each; the
  window's review and a checkout's approval read the same list.
- `gate approve --commit` takes only a full id, on purpose: seven characters can be met by another commit an agent
  grinds out. Its refusal says "name the reviewed commit in full, as 'gate review' shows it", which review never does;
  only `gate pending` shows it in full.

## Plan

- `GitGate.log` with `%H %s`; the refusal names `gate review` and `gate pending` as where the full id is.
- Prefixes stay refused: echoing a resolved prefix would let a commit made after the review match it.
- Red test: review's output holds the waiting commit's full id, and approve with that line's id passes.

## Done

- `GitGate.log` lists each commit as its full id and subject; `gate review`, the window's review and a checkout's
  approval show what `approve --commit` takes. The refusal of a short id names both commands.
- Test: the review's list starts with the waiting commit's full id; it failed before with the seven characters.
