# B505 — Where Delivered Content Came From, In Review

```json
{
  "$schema": "issue-1.json",
  "id": "B505",
  "title": "Where Delivered Content Came From, In Review",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": ["B467"],
  "covers": "`Review` names where each file Porelo delivered into the task came from."
}
```

**Why this size.** a field in `Review` (`35-gate`), `GateMethods`, read from the hand-in records, then a record of what a
first start placed; part of [B38](README.md#b38)'s acceptance ("content Porelo delivered into a task carries its
origin, and the review shows that origin").

**What must be true.** Per changed file of a pending push, `Review` says which delivery it came in through: a
hand-in (who, when, name, sha256) or a file an ecosystem plugin placed at a first start (the plugin's name and
version). A file came from a delivery when its content at the push has the delivered content's sha256, at any path;
it is marked changed since delivered when its path is the delivered one and its content differs. Every other file is
the agent's own and carries no mark. Build logs, verdicts and room messages are read, not pushed, and are not named.

## Plan

1. Hand-ins (built): a hand-in's record line also keeps the ids git gives its content in both object formats
   (`blobs`), computed from the bytes as they are placed, so a review matches without reading a blob back.
   `GitGate.blobs` answers the pending push's ids with `ls-tree -z`, and `DeliveredOrigins` names, per path, the
   latest delivery with that id. A record line written before `blobs` existed matches nothing. Before:
   `HandIns.record` already keeps name and sha256 per task. `Review` reads the record of the task that
   pushed and the blob of each changed file at the push (`git ls-tree`/`cat-file` in the mirror), and answers an
   `origin` per `ReviewFile`; additive under `Tasks1`.
2. Placed files: a first start records what each plugin placed (path, sha256, plugin name, version) beside the task,
   as hand-ins are recorded; the version comes with [B503](README.md#b503). `Review` matches them the same way.
   Nothing places such files yet: no code calls an adapter's `Configure` until [B467](README.md#b467), so this step
   waits on it.
- Red first: a unit test where a pushed file equals a handed-in file's content at another path, and one where the
  handed-in path changed.

## Acceptance

- A pushed file equal to a delivery names that delivery; a delivered path whose content changed is marked so; a task
  given nothing answers no origin.
