# B595 — A Repository Leaves Default When A Project Names It

```json
{
  "$schema": "issue-1.json",
  "id": "B595",
  "title": "A Repository Leaves Default When A Project Names It",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository of `default` that an organization's project names is offered for removal from `default`, at a start and over the daemon."
}
```

**Why this size.** one check at the start through `WorkOrigin`, one daemon method and record for the window, the removal itself
and its tests; the window's part is porelo-frontend's.

Review. Filed 2026-11-10 in `porelo-project` ST30 (Work without a project, with plain refusals), porelo's part of its PJ14.

**What must be true.** When a repository kept in `default` is named by a project of the organization, porelo offers
to remove it from `default`: at a start (`WorkOrigin` finds the organization's project for the repository's
upstream) and over the daemon, so the window can offer the same. The removal is the person's choice; nothing is
removed without it, and a task of `default` on that repository keeps working until the person removes it.

## Plan

Read in `WorkOrigin` (`organizationNaming`, `placedNaming`), `DefaultProject` and how a repository is added to and
removed from `default`, the start's steps and `ProjectMethods` in the daemon.

- A query: the repositories of `default` whose upstream an organization's project names, each with that project
  and repository (`WorkOrigin.organizationNaming`).
- At a start in `default` on such a repository: the start says the project that names it and the command that
  removes it from `default`; at a terminal it asks once, unattended it only says so.
- Tasks1: a read for the window (the repositories and the projects that name them) and a removal method answering
  what was removed, with a typed refusal for a repository a task still uses. No MCP.
- Red first: a repository named by an organization's project is offered for removal (unit, with a fixture
  organization); one named by nobody is not; the removal leaves a running task's repository as it is.

## Acceptance

- A start in `default` on a repository an organization's project names says so and offers its removal; the window
  reads the same over the daemon and removes it on the person's word.

## As built

- **The query** is `DefaultNamed` in `apps/app-model`: each usable repository of `default` whose checkout `origin`, else
  its upstream, a project of the organization names through `WorkOrigin.organizationNaming`, with that project, its key
  for the repository and the command that takes it out (`porelo project default remove <name>`). No new organization read.
- **At a start** in `default` on such a repository, `TaskLaunch` says once, on standard output and not as a failure, a
  `named` line with the project, its key and that command; the start goes on in `default`. At a terminal (`task start`
  without `--no-input` or `--dry-run`) it asks once, defaulting to no and not taken by `--yes`; unattended and through
  the daemon it only says so. A yes takes the repository out once the start is through, through the same removal the
  window uses: the start's own repository is still in the project file while it starts, so it cannot go before. A task
  of `default` still on it - a kept task of this start too - keeps it there, and the start says which task and what to
  run once it is removed. A retried start asks no second time.
- **Tasks1** in `20-project.varlink`: `NamedDefaultRepositories()` answers the rows (`name`, `upstream`, `project`,
  `projectRepository`, `removal`); `LeaveDefault(name)` takes one out through `DefaultProject.remove` and answers the
  `DefaultRepository` removed with the deploy keys forgotten, refused with `DefaultRepositoryInUse(name, tasks)` while a
  task of `default` (any container, running or stopped) works on it and `NoSuchDefaultRepository(name)` for a name
  `default` does not have. Neither is offered over MCP (`McpMethods.NEVER`).
- **Tests**: `DefaultNamedTest` (offered and not offered, with a placed fixture project), three in `TaskRunCommandTest`
  (says it and starts in `default`; a yes takes it out after the start; unattended only says it) and
  `DefaultNamedMethodsTest` over the socket (the read, the removal, the refusal for a task still on it and for a name
  not in `default`, each asserting what stayed in `default`).

Not done here: `porelo project default remove` keeps its own removal, which does not ask about tasks; the window's
part is porelo-frontend's. Not measured on a VM.
