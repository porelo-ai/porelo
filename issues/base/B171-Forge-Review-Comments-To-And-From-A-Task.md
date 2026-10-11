# B171 — Forge Review Comments To And From A Task

**Status:** Analyzed; blocked by sokar [B166](README.md#b166). Blocks the forge readers' `Review1` (`sokar-build-github` [GH02](https://github.com/sokar-ai/sokar-build-github/blob/main/issues/README.md#gh02)).
**Size:** L - crosses repositories: a new reader contract (`Review1`) every forge reader implements, the project
file, a release flow in the CLI, the daemon, the interface and the IDE plugin, a second token, docs and acceptance.

**What must be true.** Review comments on a forge's pull request reach the task that made it (way C), and the agent's
replies reach the pull request (way B on the forge), through the forge adapter on the host. The task never reaches the
forge itself. In, only the accounts the project lists are delivered; out, the person releases each comment unless the
project switched that off.

## Why

Reviews of an agent's work often happen where the pull request is, on a forge. Today those comments reach the agent only
if a person copies them over, and its answers go back the same way. [B166](README.md#b166)'s model carries them as messages, through the
same checks.

## The shape

- **The forge adapter:** the build readers become forge readers, with an interface `Review1` beside `Build1`. They fetch
  a pull request's review comments, threads and suggestions, and write comments and replies.
- **In (way C):** the adapter fetches comments and delivers them into the task's inbox as [B166](README.md#b166)'s messages, as build
  results are delivered today, after the sluice as an inbound filter. **Only accounts on the project's list** - the
  maintainers, named in the project file - are delivered; comments of other accounts are held and shown to the person,
  who may release single ones.
- **Out (way B on the forge):** a reply passes the sluice, the anchor check, the size limit and the rate limits - at
  most 10 comments a task an hour and 60 a project an hour - and is **released
  by the person by default**, as `talk release` releases a message, in the CLI, the interface and the IDE plugin. A
  project may switch the release off explicitly (for a private repository, say); then a comment goes out after the
  sluice. Every one is journaled.
- **The token:** writing uses a token of its own from the vault, with write access to pull requests only, separate from
  the read token for builds. The task never holds either.

## Plan

Built on what the builds already do: `apps/app-task/.../BuildWatch.java` follows an `online` task's pushes on the
host and hands verdicts into the task; the reader is a separate executable answering `org.fuin.sokar.Build1`
(`builds/api`, `BuildReader`, `BuildMain`), and the token is handed per call from the vault.

1. **The contract:** `builds/api/.../ReviewReader.java` and `org.fuin.sokar.Review1.varlink` beside `Build1`:
   `pullRequest(target, branch)` (the open pull request whose head is the branch, or none), `comments(target, number,
   since)` ([B166](README.md#b166)'s model, each with the forge account as its origin) and `write(target, number, comment)` with the
   write token in `target`. `BuildMain` serves `Review1` when the reader implements it, and `describe` names it.
   `builds/stub/StubReader` implements it from files, for the acceptance suite.
2. **Red first:** a test in `apps/app` against the stub reader: a listed account's comment is delivered, an unlisted
   one's is held.
3. **Matching a pull request to its task:** the branch the task's work went to - its own branch for an `online` task,
   the branch `gate approve` named otherwise - recorded by the gate with the task and the commit when it passes work
   on (`GitGate.passOn`, `approve`). The watch asks `pullRequest` for that branch.
4. **The project file:** `builds.reviewers` (forge accounts delivered), `builds.release_out` (default `true`) and
   `builds.write_credential` (a vault entry of its own) in `core/.../project/Builds.java` and `ProjectSchema`.
5. **In:** a `ReviewWatch` beside `BuildWatch`, asking every `POLL` while the pull request is open; a comment passes
   the sluice as an inbound filter (`InboundCheck`), then is delivered as a `comment` message or held by account in
   the mailbox's held list, which `talk held` and `talk release` already show and release one by one.
6. **Out:** a reply with `to: review` on a task whose work has a pull request goes into a release queue after the
   sluice and [B166](README.md#b166)'s anchor check; `talk release` sends exactly one, through `write`; with `release_out: false` it
   goes at once. At most 10 comments a task an hour and 60 a project an hour, counted over the last hour when a
   comment would go out, released or not; one beyond either is held with the limit it reached and goes once the
   hour allows it, never dropped.
7. **The daemon:** `MessagingMethods` lists and releases outgoing and held comments, in the shapes [B166](README.md#b166) gives
   `Comment` and `Thread`, so the interface ([F107](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f107)) and the IDE plugin ([IJ04](https://github.com/sokar-ai/sokar-intellij/blob/main/issues/README.md#ij04)) show the same.
8. **The journal:** each delivered, held, released and written comment, with the account or the task and who
   released it, in [B166](README.md#b166)'s comment journal, and in [B154](README.md#b154)'s log once that is built.
9. **Docs:** `doc/project-file.md` (the list, the switch, the write token), `doc/build-readers.md` (`Review1`).

## Acceptance

- Seen to fail first, then green, against a stand-in forge reader: a listed account's comment arrives in the task; an
  unlisted one's is held and shown, and arrives once released; the agent's reply waits for release and goes out once
  released; with the release switched off it goes out after the sluice; the journal names each.
- An eleventh comment of one task within an hour, and a sixty-first of one project, is held with the limit it reached,
  and goes out once the hour allows it.
- `doc/project-file.md` says the account list, the switch and the limits; `doc/build-readers.md` says `Review1`.

## To be checked

- Whether a review's top-level body, anchored to no line, is delivered as a comment of its own, which [B166](README.md#b166)'s model
  has no anchor for (Agent Core's, with [GH02](https://github.com/sokar-ai/sokar-build-github/blob/main/issues/README.md#gh02)'s agent).
