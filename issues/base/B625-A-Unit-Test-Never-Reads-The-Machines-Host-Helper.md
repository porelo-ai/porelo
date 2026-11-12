# B625 — A Unit Test Never Reads The Machine's Host Helper

```json
{
  "$schema": "issue-1.json",
  "id": "B625",
  "title": "A Unit Test Never Reads The Machine's Host Helper",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "No unit test's task start asks the real machine whether it is a host: on a machine with the host package installed, `apps/app`'s start tests are as green as on one without."
}
```

**Why this size.** the host's two fixed paths moved behind the context in `HostPerson` and its five callers in
`app-messaging` and `app-task`, and the test context pointing them at its own directory; no contract.

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

## Acceptance

- On a machine with the host package installed, `./mvnw -pl apps/app -am test` passes as it does without it.
- The test above, seen red first.
