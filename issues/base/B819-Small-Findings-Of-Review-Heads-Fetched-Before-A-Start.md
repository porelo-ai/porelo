# B819 — Small Findings of Review Heads Fetched Before a Start

```json
{ "$schema": "issue-1.json", "id": "B819", "title": "Small Findings of Review Heads Fetched Before a Start",
  "state": "Idea", "size": "S", "priority": "Low", "rank": 142, "blockedBy": [],
  "covers": "What the reading of review heads fetched before a start left: no bound on a round's fetches, review stores no deletion or prune clears, and two first reads taking a project twice" }
```

**Why this size.** S: `HostPullRequestReader`, `HostReviewHeads`, `ProjectDeletion` and `Prune` in two modules, and
`ProjectTaking`'s lock; no contract.

**What must be true.** A person's review reads end in bounded time and leave nothing behind a project's deletion.

## Found

Left by the reading of [B816](README.md#b816), each read in the code:

- A round may fetch a head for each of up to 500 rows, each fetch three git steps of 20 s and a maker read of 10 s;
  nothing bounds the round's whole time, so a stalling forge holds a daemon connection for a long time.
- `$XDG_STATE_HOME/porelo/reviews/<project>` is cleared by neither a project's deletion nor `prune`, and a store keeps
  every pack it fetched; a head is fetched only once, so it grows with the heads a project's reviews have had.
- `ProjectTaking.takenToAsk` checks for a placed file outside the project's clone lock, so two first reads (a watch
  and a read, or a read and `CanStart`) both take the project, each lending a token.
