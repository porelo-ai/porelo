# B729 — A Running Task's Egress Is Changed By The Name The Task List Shows

```json
{
  "$schema": "issue-1.json",
  "id": "B729",
  "title": "A Running Task's Egress Is Changed By The Name The Task List Shows",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 56,
  "blockedBy": [],
  "covers": "`porelo shield egress --task NAME` takes the name `porelo task list` shows, as `task status` and `task remove` do, besides the container's name."
}
```

**Why this size.** `RunningEgress.widen` and its narrowing match `--task` against `TaskInventory`'s container names
alone; resolving it as `TaskTarget` does, a test each way, and the help text.

Filed from the VM run of [B237](README.md#b237) on ubuntu, smith2, `porelo` from smith/b237.

**What must be true.** A person who sees a task as `cnt-2` in `porelo task list` can grant it a name with
`porelo shield egress --task cnt-2 --add-domain ...`. Today that answers "no running task called cnt-2", although
`task status cnt-2` answers it; only `--task porelo-cnt_cnt-2`, the container's name, works.

## Acceptance

- Red first: `--task` with the listed name widens and narrows the task as its container's name does; a name that is
  no running task is refused as now.

## Estimate

S, est=27 (p80 63) by the median velocity. There are no open questions.
