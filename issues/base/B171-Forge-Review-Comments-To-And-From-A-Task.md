# B171 — Forge Review Comments To And From A Task

**Status:** soon, after B166. Blocks the forge readers' `Review1` (`sokar-build-github` GH02).

**What must be true.** Review comments on a forge's pull request reach the task that made it (way C), and the agent's
replies reach the pull request (way B on the forge), through the forge adapter on the host. The task never reaches the
forge itself. In, only the accounts the project lists are delivered; out, the person releases each comment unless the
project switched that off.

## Why

Reviews of an agent's work often happen where the pull request is, on a forge. Today those comments reach the agent only
if a person copies them over, and its answers go back the same way. B166's model carries them as messages, through the
same checks.

## The shape

- **The forge adapter:** the build readers become forge readers, with an interface `Review1` beside `Build1`. They fetch
  a pull request's review comments, threads and suggestions, and write comments and replies.
- **In (way C):** the adapter fetches comments and delivers them into the task's inbox as B166's messages, as build
  results are delivered today, after the sluice as an inbound filter. **Only accounts on the project's list** - the
  maintainers, named in the project file - are delivered; comments of other accounts are held and shown to the person,
  who may release single ones.
- **Out (way B on the forge):** a reply passes the sluice, the anchor check, and size and rate limits, and is **released
  by the person by default**, as `talk release` releases a message, in the CLI, the interface and the IDE plugin. A
  project may switch the release off explicitly (for a private repository, say); then a comment goes out after the
  sluice. Every one is journaled.
- **The token:** writing uses a token of its own from the vault, with write access to pull requests only, separate from
  the read token for builds. The task never holds either.
- Size: large.

## Acceptance

- Seen to fail first, then green, against a stand-in forge reader: a listed account's comment arrives in the task; an
  unlisted one's is held and shown, and arrives once released; the agent's reply waits for release and goes out once
  released; with the release switched off it goes out after the sluice; the journal names each.
- `doc/project-file.md` says the account list and the switch; `doc/build-readers.md` says `Review1`.

## To be checked

- How a pull request is matched to its task: the branch the gate pushed, or a record of the push.
- The rate limits out.
