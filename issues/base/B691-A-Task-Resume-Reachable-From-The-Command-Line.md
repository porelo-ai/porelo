# B691 — A Task Resume Reachable From The Command Line

```json
{
  "$schema": "issue-1.json",
  "id": "B691",
  "title": "A Task Resume Reachable From The Command Line",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 45,
  "blockedBy": [],
  "covers": "`porelo task resume <task>` runs the resume command that `task list` and the changelog name, instead of being refused as an unknown command.",
  "openQuestions": 0
}
```

**Why this size.** One registration in `TaskCommand`'s subcommands and one test that runs the command by its name.

Filed 2026-11-13 from [B203](README.md#b203): walking the command tree for every task command showed `TaskResumeCommand`
in no group. `task attach` calls its `resume` directly, but no command line reaches it.

**What must be true.** `porelo task resume <task>` starts a stopped task again, as its description says. The hint in
`task list` for a task from before a restart ("... or 'porelo task resume <name>' at a terminal offers both") names a
command that runs.

## Plan

- `TaskCommand`: register `TaskResumeCommand` among its subcommands.
- Red first: a test that parses `task resume <name>` through the root command line and reaches `TaskResumeCommand`
  fails today with an unknown subcommand.
- [B203](README.md#b203)'s command-tree test then covers resume's two spellings without an exception for it.

## Acceptance

- `porelo task resume <task>` resumes a stopped task; a test fails while the command is unregistered.
