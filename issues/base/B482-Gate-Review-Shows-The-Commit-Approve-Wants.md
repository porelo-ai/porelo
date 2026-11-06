# B482 — Gate Review Shows The Commit Approve Wants

**Status:** Backlog, a finding of the operator's rehearsal (2026-11-06).
**Size:** XS - one format and a refusal's wording; their tests. Estimate 15 minutes.

**What must be true.** The commit `porelo gate review` shows is the one `porelo gate approve --commit` takes: in full.
A refusal of a shortened id names where the full one is.

## Found

- `gate review` heads the review with the commit as `%h`, seven characters (`GitGate`, the summary line read with
  `log -1 --format=%h %s`).
- `gate approve --commit` takes only a full id, on purpose: seven characters can be met by another commit an agent
  grinds out. Its refusal says "name the reviewed commit in full, as 'gate review' shows it", which review never does;
  only `gate pending` shows it in full.

## Plan

- Review's summary line with `%H`; the refusal names `gate review` and `gate pending` as where the full id is.
- Prefixes stay refused: echoing a resolved prefix would let a commit made after the review match it.
- Red test: review's output holds the waiting commit's full id, and approve with that line's id passes.
