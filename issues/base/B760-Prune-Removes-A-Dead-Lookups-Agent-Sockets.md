# B760 — Prune Removes A Dead Lookup's Agent Sockets

```json
{
  "$schema": "issue-1.json",
  "id": "B760",
  "title": "Prune Removes A Dead Lookup's Agent Sockets",
  "state": "Review",
  "priority": "Medium",
  "size": "S",
  "rank": 1,
  "blockedBy": [],
  "covers": "An agent lookup's socket left in the runtime directory by a process that is gone is listed by prune as such, not as a task, and removed; after the question at a terminal, prune shows what it could not remove",
  "openQuestions": 0
}
```

**Severity:** low - `doctor` reports a leftover that `prune` cannot remove, and the answer `y` prints an empty
`Removed:` while nothing went; nothing is lost and nothing leaks.

**Why this size.** S: `Prune` and `PruneCommand` in one module, a name parser beside the one that makes the name, and
their unit tests. Estimate: 38 minutes (Core's median for S).

**What must be true.** A socket an agent lookup left behind is removed by `porelo prune`, and `prune` never answers
`Removed:` with nothing while what it listed is still there.

Seen on a developer machine: `doctor` said `leftovers DEGRADED - 5 left over`, `prune` listed
`task porelo-agent-agy-1951100-1.sock` and four more, the answer `y` printed `Removed:` and nothing else, and
`doctor` still counted five. `prune --yes` says `not removed: container porelo-agent-pi-1-1.sock is occupied or
unrecognized`.

## Plan

- An agent lookup (`InstalledAgent`) binds `<agent binary>-<pid>-<n>.sock` in the runtime directory and deletes it in
  `close()`; a lookup that is killed leaves it. The runtime directory is also where each task's runtime state
  directory lives (`TaskPaths.containerState`), and `Prune.goneTasks` takes every entry there whose name starts with
  `porelo-` as a task. Its removal then fails the container name check, and the confirmed path in `PruneCommand`
  prints only `done.removes()`, dropping what could not be removed.
- `InstalledAgent.lookupOf(String)` (agent API, additive) reads the pid back out of a name `socketFor` makes, so the
  form stays in one place.
- `Prune.goneTasks` takes only directories from the runtime directory, since a task's runtime state is one.
- `Prune.run` lists, as kind `SOCKET`, every non-directory in the runtime directory that `lookupOf` reads and whose
  process no longer runs, and removes it through the existing `remove`; a socket whose process runs is never listed.
- `PruneCommand` prints one report for both paths, so the answer at the terminal shows what went, what was kept and
  what could not be removed.
- Red first, in `PruneTest`: a dead lookup's socket is listed as `SOCKET` and removed while a live one's stays and no
  task is named after either; the terminal answer `y` over an entry that cannot be removed says `Could not be
  removed`. In the agent API's tests: `lookupOf` reads what `socketFor` made and refuses other names.

## Acceptance

- `PruneTest` and the agent API's test are seen red before the change and green after.
- On a machine with such a socket, `porelo prune` lists it as `socket ...`, `y` removes it, and `doctor` reports no
  leftover.

## Built

- `InstalledAgent.socketFor` always names a lookup's socket `porelo-agent-<binary>-<pid>-<n>.sock`, a described
  agent's binary (`stub`, `pi`) included, and `InstalledAgent.lookupOf` reads the pid back; a pid or count of zero
  or with a leading zero is no lookup's.
- `Prune` takes only directories of the runtime directory for a task's runtime state, so no file there is taken for a
  task or has its name retired. It lists as `SOCKET` and removes a lookup's socket untouched for 15 minutes whose pid
  no process holds, or one that started after the socket was made; it unlinks the entry itself, a dangling link too,
  and keeps one it cannot remove as `not removed: ...`.
- After the answer at a terminal, `PruneCommand` says what went and what could not be removed, or that nothing was
  left to remove; what is kept stood in the list just answered.
- Seen red then green: `InstalledAgentTest` (a described agent's socket read back, zeros refused) and in `PruneTest`
  a dead lookup's socket removed and a live one's kept, a file in the runtime directory never a task, a dangling link
  removed, a pid held by a newer process, a fresh socket kept, and the terminal answer naming what could not be
  removed.
- The reading (agy, gemini-4-argon-high) found six points, each checked in the code: a described agent's socket not
  recognized; files in the runtime directory still taken for tasks; the kept part repeated after the answer; the
  terminal test passing on the preview; a dangling link reported removed and left; pid reuse. All six are fixed above.
  Left as it was: the answer runs the prune again, so what became left over while the question waited goes too,
  as before this change.
