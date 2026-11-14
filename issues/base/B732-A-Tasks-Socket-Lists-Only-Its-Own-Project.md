# B732 — A Task's Socket Lists Only Its Own Project

```json
{
  "$schema": "issue-1.json",
  "id": "B732",
  "title": "A Task's Socket Lists Only Its Own Project",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 9,
  "blockedBy": [],
  "covers": "Over a task's own socket, Projects and WatchProjects answer only the task's project, as List and Watch do; every other account-wide reading method granted there is held or named as an exception.",
  "openQuestions": 0
}
```

**Why this size.** One cut in the socket's dispatch for two methods, a review of the other account-wide reading methods' handlers, and tests. Estimate: 45 minutes (Core's median for S).

Found while building the hold of a task's project reads to its own project: `McpMethods.READING` grants `Projects` and `WatchProjects` over a task's socket. Both take no project and list every project of the person, so a task granted them reads every project's names and settings, past that hold on project reads. The other reading methods without a project parameter (`Credentials`, `SshKeys`, `Keyslots`, `Authorizations`, `Destinations`, `Prompts`, `Usage`, `HostKeys`) are account-wide by their signatures; their handlers are not read yet.

## Plan

- Over a task's socket, `Projects` and `WatchProjects` answer the caller's project alone, as `List` and `Watch` already do.
- Read the handler of each other account-wide method in `McpMethods.READING`. One that shows another project's data is cut to the caller's project the same way, or held with `ProjectNotItsOwn`. One that is the account's by nature (for example host keys) is named in the code and in doc/project-file.md as an exception, with why.
- Red first: a task granted `Projects` and `WatchProjects` sees its own project and no other.

## Acceptance

- Over a task's socket, `Projects` names only the task's project, and `WatchProjects` sends no change of another project.
- Each account-wide reading method is either cut, held or named as an exception; a test pins each cut or hold.
- The person's own socket answers as before.

## Done

- **Projects and WatchProjects.** Over a task's socket both answer the task's own project row alone. A watch sends
  nothing when only another project changed, so the task cannot tell that one did.
- **Every other reading method without a project or task is placed once.** `McpTaskSockets` names each in exactly
  one class, and a test fails for a reading method that is in none, so one added later must be placed:
  - Cut by project: `Authorizations` and `Events` keep the rows of the task's project.
  - Cut by task: `Prompts` and `Talk` keep the rows of the project's tasks, known by their identity, not their name.
  - Held with `ProjectNotItsOwn`: `Doctor`, whose findings name projects in their text, and `DefaultRepositories`,
    which names the project that claims each repository; `Ecosystems` only when given a checkout, which it would
    look into wherever it lies.
  - The account's or the machine's by nature, answered whole: `Credentials`, `Keyslots`, `SshKeys`,
    `CredentialCheck`, `HostVaultShare`, `Destinations`, `Destination`, `Sets`, `HostKeys`, `Agents`,
    `AgentSignIns`, `Providers`, `Installable`, `Ecosystems` without a checkout, `CheckProjectFile`,
    `ProjectFileSchema`, `VerifyEvents`, `Process`, `McpMethods`. Named in the code and in doc/project-file.md.
  - `Usage` takes a task and was already held to the caller and its own children.
- **Size.** The plan named eight methods; the survey found more that leak (`Authorizations`, `Events`, `Prompts`,
  `Talk`, `Doctor`, `DefaultRepositories`), so the work ran past S.
- **Red first.** `theProjectsATaskReadsAreItsOwnAlone`, `theEventsATaskReadsAreItsOwnProjectsAlone` and
  `aReadingThatNamesOtherProjectsAndHasNoRowToCutIsHeldFromATask` failed before. The watch's cut, the row cut and the
  task test are pinned on their own.
- **What stays.** `Authorizations` keys its open questions by credential alone, so a question of another project
  for the same credential stands in for the task's own, which the task then does not see; the person's own stream
  is unchanged. A question naming no project is not shown to a task. In `Prompts`, the count of prompts a slow
  reader missed rides on the next prompt, and goes with it when that one is another project's. `Credentials` shows
  the vault's entry names and settings and the declared connections, `CredentialCheck` the connection for a URL, and
  `Destinations` each destination's upstream: never a value, but a name or address there may tell of another
  project. They are named the account's here; holding them would take the vault's names from every task that reads
  them.
- A task's check of its own rows remembers a task found to be its project's, so a stream does not list the
  containers again for each row.
- The person's own socket is unchanged: only the task's socket wraps a method.
- Changelog under *Fixed*.
- **Locally.** daemon (422) passed whole; after the reading's change, `McpTaskSocketsTest` (46) passed again, and so
  did the root's `validate` and `-Pdocuments test`.
