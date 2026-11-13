# B324 — A Moved Waiting Push Changes WatchProjects

```json
{
  "$schema": "issue-1.json",
  "id": "B324",
  "title": "A Moved Waiting Push Changes WatchProjects",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 50,
  "blockedBy": [],
  "covers": "A waiting push that moves to another commit changes a `WatchProjects` reply, though no count changes, so an interface sees the new work within seconds"
}
```

**What must be true.** When a task pushes again and its waiting push moves to another commit, a
`WatchProjects` stream sends a reply within its usual delay even though no count changed, so an
interface announces the new work without reading the gate on a timer of its own.

## Acceptance

- A second push of one task, with the same number of waiting pushes, produces a `WatchProjects`
  reply in which a field of the repository differs from the reply before; a push that changes no
  waiting commit produces none.
- The field is added to `Repository` within `Tasks1` (the interface only grows), described in the
  contract, and named in the reply of `Projects()` alike.

## Open questions

- Which field: each waiting push's commit (`pendingCommits: []string`), or one revision that changes
  with any of them (`pendingRevision: string`).
