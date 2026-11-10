# B412 — A Prompted Start That Failed Says So In Its Exit

```json
{
  "$schema": "issue-1.json",
  "id": "B412",
  "title": "A Prompted Start That Failed Says So In Its Exit",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 25,
  "blockedBy": [],
  "covers": "`task start -P` whose agent ended with an error exits non-zero, where it exits 0 today"
}
```

**Severity.** medium: a script takes a run that failed for one that worked.

**What must be true.** `task start -P` whose agent ended with an error exits non-zero, so a script can tell it from a run that
finished.

**What holds today.** Measured in a walk: with Pi, a start whose log ended in `-- ended with an error` exited 0 after a provider's
400. The walk's own script had to search the output for that line. Other agents not measured.

Read in the code: `TaskRunCommand` returns 0 when `TaskLaunch.runAgent` answers 0, and that is the exit of the process in the
task. The line comes from the agent's formatter, and `Agent1` also tells how a run ended (`AgentEnding`, which `task status`
reads for "with an error at").

**To be checked.** Whether `AgentEnding` answers in time at the end of a prompted start, what an agent without it gives ("cannot
say" must keep today's exit), which exit it is, and the same for `runAgentInExisting`.
