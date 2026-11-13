# B732 — A Task's Socket Lists Only Its Own Project

```json
{
  "$schema": "issue-1.json",
  "id": "B732",
  "title": "A Task's Socket Lists Only Its Own Project",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 9,
  "blockedBy": [],
  "covers": "Over a task's own socket, Projects and WatchProjects answer only the task's project, as List and Watch do; every other account-wide reading method granted there is held or named as an exception.",
  "openQuestions": 0
}
```

**Why this size.** One cut in the socket's dispatch for two methods, a review of the other account-wide reading methods' handlers, and tests. Estimate: 45 minutes (Core's median for S).

Found while building [B573](README.md#b573): `McpMethods.READING` grants `Projects` and `WatchProjects` over a task's socket. Both take no project and list every project of the person, so a task granted them reads every project's names and settings, past B573's hold on project reads. The other reading methods without a project parameter (`Credentials`, `SshKeys`, `Keyslots`, `Authorizations`, `Destinations`, `Prompts`, `Usage`, `HostKeys`) are account-wide by their signatures; their handlers are not read yet.

## Plan

- Over a task's socket, `Projects` and `WatchProjects` answer the caller's project alone, as `List` and `Watch` already do.
- Read the handler of each other account-wide method in `McpMethods.READING`. One that shows another project's data is cut to the caller's project the same way, or held with `ProjectNotItsOwn`. One that is the account's by nature (for example host keys) is named in the code and in doc/project-file.md as an exception, with why.
- Red first: a task granted `Projects` and `WatchProjects` sees its own project and no other.

## Acceptance

- Over a task's socket, `Projects` names only the task's project, and `WatchProjects` sends no change of another project.
- Each account-wide reading method is either cut, held or named as an exception; a test pins each cut or hold.
- The person's own socket answers as before.
