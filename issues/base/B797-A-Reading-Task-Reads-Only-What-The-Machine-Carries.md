# B797 — A Reading Task Reads Only What the Machine Carries

```json
{
  "$schema": "issue-1.json",
  "id": "B797",
  "title": "A Reading Task Reads Only What the Machine Carries",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 139,
  "blockedBy": [],
  "covers": "A task that reads all of a project's repositories reads only those the machine carries; the others are named once, in one plain line, and no failed clone of a repository that is not there reaches the agent's output"
}
```

**Why this size.** S: `TaskLaunch` (around line 4090) hands `WorkspaceSetup.readAll` every repository of
`project.allRepositories()`. It takes the carried ones from the host's record of the project instead, which B796 reads
as well. A unit test of the selection and one of the words.

**What must be true.** An agent that discusses an idea on a machine reads the repositories that machine has, and its
output carries no error for a repository that was never put there.

## Found

On 2026-11-17, in a live test on a rented host. A discussion started for project porelo, of whose repositories the
host carried only some. Every other one gave a line in the agent's output: "porelo: repository 'core' is not read, and
the task starts without it: 'git … clone --bare … http://127.0.0.1:3001/porelo/porelo.git …' failed with exit code
128". The agent took these as errors to deal with.

## Plan

- Today: `readAll` (`apps/app-task/.../WorkspaceSetup.java`, around line 324) tries to clone each repository and says
  every failure on the error stream, with the git command.
- Change: the reading task asks for the repositories the machine carries for the project. Those are read as today. The
  rest are said once on the output stream: "not on this machine: core, …". A repository the machine carries and still
  cannot read keeps today's words.
- Red first: a test with a project of three repositories, two carried. Today the third is tried and fails; after the
  change it is named as not on this machine and never cloned.

## Acceptance

- A repository the machine does not carry is not cloned and gives no error line.
- One line names the repositories left out.
- A carried repository that cannot be read is still said as today.
- The test above fails without the change.
