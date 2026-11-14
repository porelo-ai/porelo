# B670 — A Task Is Not Given Its Issue Prefixes

```json
{
  "$schema": "issue-1.json",
  "id": "B670",
  "title": "A Task Is Not Given Its Issue Prefixes",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 23,
  "blockedBy": [],
  "covers": "A task is no longer given PORELO_ISSUE_PREFIXES; the shared rule in each AGENTS.md says how issues are named instead.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST313, part 3 of [PJ108](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj108). Analyzed by Agent Core.

**What must be true.** A task is no longer given `PORELO_ISSUE_PREFIXES`. It lands only after the shared rule of PJ108
is in every repository's AGENTS.md. That waits on Frontend's part 5 (ideas to the
planner), and the coordinator puts the rule in place.

**Size.** XS: one variable less, its test and its line in the docs.

## Plan

Read in the code on 2026-11-12: `TaskLaunch.ISSUE_PREFIXES` is set from the repository's `issues` prefixes
(`TaskLaunch.java`, the start's environment), and `TaskRunCommandTest` pins it.
1. **Red first.** The `TaskRunCommandTest` case that pins the variable is turned around: a start sets no
   `PORELO_ISSUE_PREFIXES`.
2. The variable and its constant go. The repository's `issues` prefixes stay in the project file, because the index and
   the checks read them.
3. **Docs.** Its line in `doc/project-file.md` and `doc/running.md` goes.

## Acceptance

- A started task's environment has no `PORELO_ISSUE_PREFIXES`. Seen red first.
