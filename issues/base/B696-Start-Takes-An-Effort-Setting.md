# B696 — Start Takes An Effort Setting

```json
{
  "$schema": "issue-1.json",
  "id": "B696",
  "title": "Start Takes An Effort Setting",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "Tasks1's Start takes an optional reasoning effort, an open string each agent maps by its own declaration, and the task's record keeps it in `TaskRecord.effort`.",
  "openQuestions": 0
}
```

**Why this size.** An optional contract field, its way through the start to the agent's definition, a declaration in
each agent that has an effort, the CLI option, and the record; like the model's way, which exists.

Filed 2026-11-13 for the Coordinator, as the follow-up for the Planner's start dialog offering a rule's effort and
the interface's start hook. `TaskRecord` already has `effort: ?string`, "absent until Start has an effort setting".

**What must be true.** A Start with `effort` runs the agent at that effort, given by the agent's own declared flag,
as the model is (`AgentDefinition`, the model "by the same declared flag as in an unattended run"). The value is an
open string, since each agent names its efforts differently. An agent that declares no effort refuses a Start that
asks for one, naming the agent, rather than dropping it. The task's record answers the effort it ran at, and nothing
when none was asked for.

## Plan

- `15-task.varlink`: Start gains `effort: ?string`, said as mapped per agent; `TaskRecord.effort`'s comment says it
  is what the start was given.
- The agent definition: an optional declared effort flag, beside the model's; each shipped agent with an effort
  declares it.
- `TaskMethods.Start` and `task start --effort <value>` carry it to the agent's command line, attended and
  unattended alike; the task record writes it.
- Red first: a Start with an effort runs the stub with its declared flag and that value; an agent without the
  declaration refuses it; the record answers the effort.

## Acceptance

- A start at an effort reaches the agent's command line and the record; a test fails while either drops it.
