# B729 — A Running Task's Egress Is Changed By The Name The Task List Shows

```json
{
  "$schema": "issue-1.json",
  "id": "B729",
  "title": "A Running Task's Egress Is Changed By The Name The Task List Shows",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 56,
  "blockedBy": [],
  "covers": "`porelo shield egress --task NAME` takes the name `porelo task list` shows, as `task status` and `task remove` do, besides the container's name."
}
```

**Why this size.** `RunningEgress.widen` and its narrowing match `--task` against `TaskInventory`'s container names
alone; resolving it as `TaskTarget` does, a test each way, and the help text.

Filed from a VM run on ubuntu of a name granted live through a CNAME.

**What must be true.** A person who sees a task as `cnt-2` in `porelo task list` can grant it a name with
`porelo shield egress --task cnt-2 --add-domain ...`. Today that answers "no running task called cnt-2", although
`task status cnt-2` answers it; only `--task porelo-cnt_cnt-2`, the container's name, works.

## Acceptance

- Red first: `--task` with the listed name widens and narrows the task as its container's name does; a name that is
  no running task is refused as now.

## Estimate

S, est=27 (p80 63) by the median velocity. There are no open questions.

## Built

- `RunningEgress.widen` and `narrow` take the task as `porelo task list` shows it, or as its container's name, and
  turn it into the container through `TaskTarget.spelled`. That goes through the task's verified identity, never the
  shape of the name. A name that is no running task's, or that two tasks share with no checkout to settle it, is
  refused as "no running task called <name>", as before. The daemon's `WidenTask` and `NarrowTask` take the same
  path.
- `shield egress --task` says it takes either name; `doc/commands.md` says the same.
- Tests in `RunningEgressTest`:
  - a task widened, and one narrowed, by its listed name
  - one widened by its container's name, as before
  - a name no running task has, refused both ways, with nothing granted

  The listed-name cases failed on the build box before the change with "no running task called cnt-2", the bug as
  filed (`buildbox-porelo-60662e7fea47-20261113T175012Z.log`). All pass after it
  (`buildbox-porelo-63b492ea371a-20261113T175420Z.log`): RunningEgressTest, ShieldEgressCommandTest, TaskTargetTest,
  ReachTest and the daemon's PoreloDaemonTest and McpMethodsTest, 204 tests, 0 skipped.
