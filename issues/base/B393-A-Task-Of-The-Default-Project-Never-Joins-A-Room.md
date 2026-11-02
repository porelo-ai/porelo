# B393 — A Task Of The Default Project Never Joins A Room

**Status:** Idea.
**Size:** S - a check where the project's file is written, a read-side refusal and tests.

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

- A test fails when `default`'s project has a conversation or a peer, with a homeserver configured for the account and
  a transport installed, and when a task of `default` asks a transport for anything.
- A name in `repositories.json` that does not match the repository name pattern is refused where it is read, and never
  reaches the written file; a test shows it red first with the name that put `mail` into the file.
- `upstream` is written as one quoted value whatever it holds.

## To be checked

- A resumed task of `default`: that it reads its project through `ProjectSource`, not a kept copy. Not traced.
