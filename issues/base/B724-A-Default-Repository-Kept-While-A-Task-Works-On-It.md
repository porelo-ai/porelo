# B724 — A Default Repository Kept While A Task Works On It

```json
{
  "$schema": "issue-1.json",
  "id": "B724",
  "title": "A Default Repository Kept While A Task Works On It",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 53,
  "blockedBy": [],
  "covers": "`porelo projects default remove <name>` refuses while a task of `default` works on that repository, naming the tasks, as the daemon's LeaveDefault already does; today the command line removes it anyway.",
  "openQuestions": 0
}
```

**Why this size.** The command line takes the rule the daemon already has: one call changed in one command, its
refusal line and code, and tests. Estimate: 45 minutes (Core's median for S).

Decided by the operator on 2026-11-13. The window's LeaveDefault refuses a repository a task of `default` still works
on (`ProjectMethods.leaveDefault`, through `DefaultNamed.remove(context, inventory.tasks(), name)`, answering
`DefaultRepositoryInUse` with the tasks). `ProjectDefaultCommand`'s `remove` calls `project.remove(argument)` directly
and removes it while the task runs. Frontend's F541 follows the same rule.

## Plan

- `ProjectDefaultCommand` `remove`: go through `DefaultNamed.remove` with the account's task inventory, as the daemon
  does. `InUse` refuses with one line naming the repository and each task working on it, and changes nothing;
  `NotThere` keeps today's answer; `Removed` keeps today's output.
- `doc/commands.md`: one sentence on the refusal.

## Acceptance

- Red first: with a task of `default` on the repository, `projects default remove` refuses, names the task, and the
  repository, its mirror and its deploy keys stay.
- Once the task is removed, the same command removes it as today.
- The command line and LeaveDefault refuse the same cases (one shared rule, no copy).
