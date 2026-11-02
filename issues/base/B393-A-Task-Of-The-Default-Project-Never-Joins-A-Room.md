# B393 — A Task Of The Default Project Never Joins A Room

**Status:** Review.
**Size:** S - a guard where a task enrolls, a filter where the project's file is written, and tests.

**What must be true.** A task of the project `default` talks to nobody, whatever this machine or host has: a homeserver
on the host, an account's settings, a room peer left over. It never enrolls at a transport, never joins a room and
never starts a homeserver. `default` has no room as its normal case, so nothing about this is an error to the person.

## What holds today

Read in the code. `default` has no conversation only by how its file is written, and nothing guards that:

- `ProjectSource.resolve` answers `default` first, from `DefaultProject.file()`, which writes the file anew at each
  read with no `mail` section. No followed project may take the name (`FollowedProjects`), and a recorded file is never
  reached for it.
- Every way into a room asks `project.mail().conversations()`, which is empty without a `mail` section: `TaskLaunch`
  through `TaskConversations.enroll`, `talk join`, `talk room`, `MessageWatch.withSiblings`.
- Nothing from the machine or host is added to a project's `mail`.

**The gap, measured on the native binary in a throwaway state:** `DefaultProject.fileHeld` writes a repository's name
into the YAML as it stands, and `entries()` takes any string from `repositories.json`, with no check on read. A name
with line ends in that file put a `mail` section with a room peer into the written `project.yml`, and the reader read
it. Both tries were refused with exit 70, each on the line that follows the name, so no working room was reached and
none is claimed. The file is the account's own; no task or forge writes it.

No test says that `default` has no conversation (`DefaultProjectTest` names none).

## Acceptance

- A test fails when `default`'s project has a conversation or a peer. No setting of this machine or host reaches a project's
  `mail`, so a file without one is that case.
- A test fails when a task of `default` runs a transport: at its start with a transport installed, and at a message pass
  with a queue left in its mailbox.
- A name in `repositories.json` that is no repository name, or `default`, an upstream the reader refuses, or a name a second
  time never reaches the written file; a test shows it with the name that put `mail` into the file. It is left out rather
  than refused, since this project is read for every project a machine lists: one bad entry must stop neither those nor the
  daemon's watches. `project default list` marks it, it can be removed, and an address found under it can be added under a
  good name. An older name ending in a hyphen is left out the same way. The daemon's `DefaultRepositories` answers only what
  a task can use.
- **A change for other projects:** a project with no peer and no conversation no longer sends what is left in a queue. It
  stays there, and goes out if the project gains a peer again.

## Checked

- A start resolves its project through `ProjectSource.require`, and a resumed task through `GateSupport.byName`, so a
  `project.yml` in the directory is not read for `default`. `TaskConversations.enroll` asks by the project's name, so a
  file named so joins nothing whatever it holds.
- `talk pass --project other` cannot reach a task of `default`: the mailbox's name is made under the project given.
