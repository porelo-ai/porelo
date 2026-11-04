# B426 — A Person's Projects Listed From The Host's File

**Status:** Implementing.
**Size:** S - one more file read in `OrganizationProjects`, a test, a doc line.

**What must be true.** In a person's host account, `porelo project list` shows every project the host carries for that
person, also before their first start there. porelo-host publishes the file at each follow; this reads it.

## Plan

Agreed in the room with porelo-host's owner on 2026-11-04: `/etc/porelo-host/organization/persons/<account>.json`, holding
`{"projects": ["a", "b"]}`, root's with a read ACL for that account alone, beside the projects' files in a directory that
cannot be listed. On a host the account and the login are one name.

- `OrganizationProjects.hosted` adds the names of that file to the names this account placed. Absent or unreadable names
  nothing; a file that is there and does not read, or a name no project can have, is the error a project's file gives.
- Each name is then opened as today: one whose file this person cannot read, a locked member's or a stale one, stays out.
- `Projects()` and the window stay as they are: the window's list comes from the organization on the person's computer.

## Acceptance

- A project the host's file names, with nothing placed in this account, is listed. Seen to fail: only `default` listed.
- Another person's file names nothing here; a name whose project file cannot be read stays out; a name no project can
  have is refused, never used as a path.
