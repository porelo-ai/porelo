# B789 — A Task's Pull Request on a Host Is Opened by the Task

```json
{
  "$schema": "issue-1.json",
  "id": "B789",
  "title": "A Task's Pull Request on a Host Is Opened by the Task",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "On a host, the pull request a task's build watch opens for its pushed branch is opened with the task's own forge token, as the gate opens one, never with the person's, so the person can approve it",
  "openQuestions": 0
}
```

**Why this size.** S: the token the build watch opens a pull request with, on a host, taken from the task's own
forge token as `HostPullRequests` takes it, with a guard test. Estimate: 38 minutes (Core's median for S).

**What must be true.** Every pull request Porelo opens on a host's forge for a task names the task's account
(`porelo-<login>`) as its author, so the person, who alone approves, is never its author and their approval counts.

Filed 2026-11-15 from the operator's live test on a rented host (porelo 0.5.1~snapshot.552, Forgejo 17.0.0), as a
bug: a blocker for every unattended task on a host. Seen on the rehearsal of 2026-11-06 too.

## Why

An unattended task of porelo-pi pushed its branch, and pull request #1 to `main` was opened with the person as its
author (Forgejo names the person's account as the one who wants to merge). Forgejo refuses an approval by a pull request's own
author, and `main` wants one, so it cannot be merged.

Read in the code (not measured on the host): the gate of a guarded task opens its pull request with the task's own
token (`GateSupport` hands `HostPullRequests` `TaskSecrets.forgeToken(project, task)`, which the host made for
`porelo-<login>` at the start, `HostTasks.recordAndToken`), but only on an approval (`GitGate.completeApproval`). An
unattended task's push goes by the host push (`GateWiring`: `--pass-on --host-push`), which opens none. `BuildsWiring`
adds `--builds-only` for a guarded task alone, so for every other task - unattended and gatekeeping -
`BuildWatch.pullRequest` opens the pull request itself through the build reader (`TaskWatchBuildsCommand.target()`,
`BuildTarget.of`), with the builds credential. On a host that credential is `<forge>.person`, the
person's own token (`FollowProjects.publication` names it for the host's builds). So the person opens it, which the
watch's own comment says must never happen. [HO137](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho137)
lets the task's token open a pull request on the host for this reason.

## Plan

1. `TaskWatchBuildsCommand`: on a host (`--from-host`), the forge the watch opens a pull request with carries the
   task's own forge token (`TaskSecrets.forgeToken` of its container), read from the vault for each attempt as the
   builds credential is, through a target of its own: the builds and their logs are still read with the builds
   credential by `target()`, whose signature stays (`BuildTargetTest` reaches it). The choice of token sits in a
   seam a unit test reaches, not only in the command.
2. With no task token kept (a vault shut at the start, an uncertified start the host gave none), no pull request is
   opened and the person's token is never used instead. `BuildWatch.pullRequest` catches that refusal (`NotNow`)
   beside the ones it catches today: it is told as the pull request's reason through `builds.pullRequest` and asked
   again after `REFUSED`, so it never escapes into the round and the builds are still told.
3. Off a host nothing changes: the person's own forge and credential, as today.
4. `BuildsWiring`'s line that names "the forge account of '<credential>'" says the pull request is opened by the
   task's account on a host.
5. `doc/hosts.md` says whose token opens a task's pull request on a host, for each kind of task.

## Red-first test

- `BuildWatchTest` / `TaskWatchBuildsCommandTest`: a watch from the host whose task pushed opens its pull request with
  the task's token, the builds read with the builds credential. Fails today: both with the builds credential.
- The same with no task token kept: no pull request opened, the reason names the missing task token, the person's
  token is not used, and the round still tells the builds. Fails today: the round reads none (the refusal escapes).

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, an unattended task's pull request names the task's account as author, and the person approves
  and merges it.

## As built

- `BuildTarget.pullRequest` chooses what opening a task's pull request carries, beside `BuildTarget.of` for the
  builds: off a host the named builds entry, as before; on a host (`--from-host`) the task's own forge token
  (`TaskSecrets.forgeToken` of its container) and nothing else. A shut vault or no task token throws `NotNow`,
  whose words say the pull request is not opened as the person.
- `TaskWatchBuildsCommand.openPullRequest` asks `pullRequestTarget()`; `head` and `build` still ask `target()`.
- `BuildWatch.pullRequest` catches `NotNow` beside the refusals it caught: told as the pull request's problem, asked
  again after `REFUSED`, the builds told all the same.
- `BuildsWiring`'s start line says the pull request is opened as the task's own forge account on a host.
- `doc/build-readers.md`, `doc/way-of-working.md`, a changelog entry under *fixed*.
- Red first: `BuildTargetTest.aHostsPullRequestIsOpenedWithTheTasksOwnTokenAndItsBuildsReadWithTheNamedOne` (the
  builds token, not the task's), `aHostsPullRequestWithoutTheTasksTokenIsNotOpenedWithThePersons` (nothing thrown),
  `BuildWatchTest.aPullRequestWhoseTokenIsNotThereLeavesTheBuildsToldAndIsAskedAgainLater` (the refusal reached
  the round's troubles). `offAHostThePullRequestIsOpenedWithTheNamedEntryAsBefore` keeps today's behavior.
