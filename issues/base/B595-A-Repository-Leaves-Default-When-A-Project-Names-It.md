# B595 — A Repository Leaves Default When A Project Names It

```json
{
  "$schema": "issue-1.json",
  "id": "B595",
  "title": "A Repository Leaves Default When A Project Names It",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository of `default` that an organization's project names is offered for removal from `default`, at a start and over the daemon."
}
```

**Why this size.** one check at the start through `WorkOrigin`, one daemon method and record for the window, the removal itself
and its tests; the window's part is porelo-frontend's.

Implementing since 2026-11-10. Filed 2026-11-10 in `porelo-project` ST30 (Work without a project, with plain refusals), porelo's part of its PJ14.

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
