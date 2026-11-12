# B625 — A Unit Test Never Reads The Machine's Host Helper

```json
{
  "$schema": "issue-1.json",
  "id": "B625",
  "title": "A Unit Test Never Reads The Machine's Host Helper",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "No unit test's task start asks the real machine whether it is a host: on a machine with the host package installed, `apps/app`'s start tests are as green as on one without."
}
```

**Why this size.** the host's fixed paths moved behind the context: the helper and the host directory with its
`forge.conf` and `organization/projects`, read by a task's start and an approval, and the test context pointing them at
its own directory; no contract.

**What must be true.** A unit test runs on a developer's machine that has the host package installed and passes as
it does on a build runner. Today `TaskLaunch`'s default constructor makes `new HostPerson(context)`, and
`HostPerson.present()` reads the real `/usr/sbin/porelo-host` and `/etc/porelo-host`. On a machine with the host
package, every unit test that starts a task through `TaskRunCommand` takes the machine for a host and is refused
("this host starts a task only with a key of its own that you certify", exit 65 where the test expects its own exit).
Measured on a machine with the host package: `TaskRunCommandTest` 41 failures and 8 errors, on `main` and on a
branch alike. A build runner has no host package, so CI stays green and the leak shows only on the machine.

## Plan

- `HostPerson(PoreloContext)` takes the helper's path and the host directory from the context, not as literals; the
  real context gives `/usr/sbin/porelo-host` and `/etc/porelo-host`, and a test context gives paths under its own
  directory, where nothing is unless a test puts it there.
- The callers that make one with the context alone (`TaskLaunch`, `StartCheck`, `BuildStatus`,
  `TaskCertificateCommand`, `MessageSigners`) keep their calls.
- Red first: a test whose context has the helper present under the real path cannot be built on a runner, so the
  test asks the opposite. With the build's `porelo.test.real.account.refused` set, the context's host paths lie under
  the test's directory. A test that plants a helper there sees `present()` true, and a start in it is refused as
  on a host. Without the plant, a start goes on.

## As built

Built by Codex CLI on Agent Core's brief, reviewed and extended by Agent Core.

- `PoreloPaths` carries `hostProgram` and `hostDirectory`. A context built where the build set
  `porelo.test.real.account.refused` puts them under its data directory (`host/usr/sbin/porelo-host`,
  `host/etc/porelo-host`); any other context names `/usr/sbin/porelo-host` and `/etc/porelo-host`, so sudo still runs
  the helper by that path alone.
- What a task's start and an approval read now comes from the context: `HostPerson`, `HostForgeLending` (the helper
  and `forge.conf`), `HostForgeAccess`, `HostForgeAddress`, `OrganizationProjects.of`, `GateSupport`, `StartCheck`,
  `TaskLaunch`, `GateApproveCommand`, `ApprovalBranch` and `HostPush`.
- Tests: `PoreloPathsTest` (a test context's host paths stay under its directory; the real ones keep the package's
  places), `TaskRunCommandTest` (a helper planted in the context refuses the start as on a host; without it the start
  goes on), `HostPersonTest`, `ApprovalBranchTest` (an approval reads the host's forge from the context). Seen red:
  the test context pointed at `/` again, and `ApprovalBranch` back on `/etc/porelo-host/forge.conf`. Tests that held
  the helper's literal path in sudo's arguments now hold the context's.
- Not measured: a run of `apps/app`'s tests on a machine with the host package installed, since this one has none.
  Left on the fixed paths, outside a task's start and an approval: `TransportLifecycle`, `TransportConversations`,
  `HostPullRequestReader`, `ProjectInventory`, `UpstreamWatch`, `ProjectListCommand`, `HostCentral`,
  `HostArrivalLedger` and the `host` commands; a test of one of them on such a machine can still read it.

## After the code reading

agy (gemini) read the change. Fixed: `GateApproveCommand`, `ApprovalBranch` and `HostPush` still named
`/etc/porelo-host/forge.conf` with a context at hand (red first, `ApprovalBranchTest`); two assertions gained their
reason. Left: `PoreloPaths`' canonical constructor takes any host path, as a record's does; nothing outside it calls
that constructor and a shipped binary refuses to run at all with the test property set. Older snake-case test names
in `HostForgeAccessTest` and `ProjectTakingTest` stay as they are.

## Acceptance

- On a machine with the host package installed, `./mvnw -pl apps/app -am test` passes as it does without it.
- The test above, seen red first.
