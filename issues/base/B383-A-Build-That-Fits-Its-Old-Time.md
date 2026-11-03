# B383 — A Build That Fits Its Old Time

**Status:** Review.
**Severity:** medium.
**Size:** S - fixtures and waits in several test modules (`apps/app`, `daemon`, `agents/stub`), and a real run of the Build
to measure each step; no contract other repositories read, and `VaultFile` is not touched.

**What must be true.** The Build's `mvnw verify` takes about as long as it used to, not three times
as long. On 2026-10-31 it took 18 minutes (Build 41253673912, 23:43:26 to 00:01:41), where the build job's comment
named 5.5 minutes for a whole run; the job's bound was raised from 20 to 60 minutes so the Build runs at all. Not
measured which tests grew: real `git`, `ssh-agent`, `ssh-keygen` and `sleep` processes, and waits of a second and
more, came with this sprint's tests.

Found when the Build on 5eeb7662 was cancelled on 2026-11-01; not built that night, since no build starts after 22:30Z.

## Plan

Added on 2026-11-03. The real Build on the cut took 21.4 minutes for this step, and the organization's adapter had grown to
4:29 of a 14:52 reactor here. Its tests share nothing, so `organization/git-tree` runs them concurrently, a directory per
check instead of a counter. `apps/app` runs in four JVMs, a class at a time in each, 2:44 against 6:53 on four CPUs and no failure in three runs: a system property a test sets stays its own, which is what its tests need. `vault` and `daemon` then did the same, 60 s against 38 s here in three runs, each run with no failure; `vault` stays in one JVM under the `native` profile, not tried in four under the tracing agent. The rest stay serial. Fixtures are not touched: the time is spread flat over 291 classes, the slowest 47 s of 411.

Read on 2026-11-02 from the per-class times of a full run (19:04 min; Porelo App 14:33 min of it; 504 classes whose
times add up to 18.6 min; tests run serially, no `forkCount` or JUnit parallelism). The twenty slowest classes are
11 min of it, and each is slow for one of four reasons, read in the test: a real Argon2id (64 MiB, 3 iterations,
about 0.15-0.3 s a derive, measured) in every vault write and open of a fixture; a fixture of real processes
(`ssh-agent`, `git`, `ssh-keygen`, a signed commit) rebuilt in `@BeforeEach`; a wait for the clock (`MessageWatch.IDLE`
5 s twice over, `Thread.sleep(3_500)` twice, 1 s); a real process kept only to be stopped.

1. **Measure before cutting.** Run the twenty classes alone under `-Dsurefire` with the per-test report, and count
   Argon2 derives per class (a counter around `VaultFile.deriveKey`, not committed). The plan below is ordered by the
   share each step is found to have; a step that saves under 5 s of a class is dropped.
2. **Waits first, no production risk:** `MessageWatch` takes its idle rest as a constructor argument (it takes the
   interval already, `MessageWatch.java:98`), `MessageWatchLoopsTest.java:116` uses a short rest; `StubAgentTest.java:133,183`
   poll for the awaited screen text or commit, bounded by the old time; `VaultFileTest.java:397` and the `100 ms`
   sleeps in `TaskLifecycleCommandsTest` shrink. About 15 s.
3. **A fixture once per class where its content is the same in every test:** `ProjectAccessCommandTest`
   (`@BeforeEach` 77-111) and the `RegistryCheckout` users (`founded()`), by a template directory built in
   `@BeforeAll` and copied per test; `ssh-agent`, `git` and the signed commits stay real. The vault fixtures of
   `TransportConversationsTest`, `MachineTransportsTest`, `MessagingMethodsTest`, `BuildStatusCommandTest` the same
   way: one vault file written per class, copied per test.
4. **Argon2 keeps its production cost everywhere:** `VaultFile` gets no way to write a cheaper one. A target of about 12
   minutes is what fixtures and waits can reach; 5.5 is not reachable without parallel tests, which these do not allow today.
5. **Do not touch:** the signature, backup-key, access and registry-history tests with real `git`/`ssh-keygen`, the
   second JVM in `VaultFileTest`, the real credential helper in `RegistryJoinableCommandTest`, the `sleep` helper
   process rows, the keyring rows.
6. **Measure after:** the same twenty classes and a full `mvnw verify` on the stack's top commit; the issue records
   before and after per class in its *Acceptance*.
7. Tests that guard the shortening: a test that fails if `MessageWatch` ends its rest later than the one given (so the
   short rest is used), and, if step 4 is built, a test that the cheap cost is refused by `VaultFile.open` of a file
   written by `write` in production (the production path still writes the production cost).

## Acceptance

- A full `mvnw verify` on the top commit of the stack takes at most 12 minutes on the same machine class as the
  19:04 min run of 2026-11-02 (measured, both logs kept); the twenty slowest classes are listed before and after with
  their times.
- No test that guards a secret or a real boundary is weakened: `VaultFileTest`, `VaultClearingTest`, the signature,
  backup-key, access and registry-history tests keep real `git`, real `ssh-keygen`, real `ssh-agent` and the
  production Argon2 cost; the list of tests changed is in the commit message, and each one's reason is the fixture or
  the wait, not the subject.
- Every test that gives up a real wait is seen to fail first against a loop or an agent that never ends/serves.
- The number of tests run is the same (504 classes, same count of tests per class) before and after.

## As built

No fixture and no wait was touched, so no test changed, and every real process and the production Argon2 cost stay: the
Acceptance's list of tests changed is empty. What changed is how many JVMs run the tests of `apps/app`, `vault` and
`daemon`, four instead of one, and that `organization/git-tree` runs its checks concurrently. `vault` stays in one JVM under
the `native` profile, not tried in four under the tracing agent.

**Measured, the step *Build and unit tests*:** 21.4 minutes on the cut, 13.5 with the App in four JVMs, then 10:50, 9:39 and,
with `vault` and `daemon` too, 11:08, the last three under 12. A whole `mvnw verify` here takes about 6:10, against 19:04.

**The runner's spread is wider than the last step's gain.** Between the two logs below `vault` and `daemon` fell from 107 s to
67 s, but the App, unchanged between them, fell by a sixth as well, so about 20 s of that is the step itself. The target
is met; the margin on a slow runner's day is not known.

The twenty slowest classes, in seconds, on the Build, before on `d2fbbacb` (job 125689932834) and after on `19d6e689` (job
125735330020), which ran on a faster runner:

| Class | Before | After |
|---|---|---|
| `TransportConversationsTest` | 158.0 | 134.1 |
| `GitTreeWritesContractTest` | 128.8 | 98.6 |
| `TaskRunCommandTest` | 125.9 | 102.3 |
| `IdentityRotateCommandTest` | 91.2 | 72.2 |
| `ProjectTakingTest` | 79.3 | 61.6 |
| `GitTreeWritesTest` | 66.3 | 53.0 |
| `GitTreeStoreTest` | 62.0 | 54.4 |
| `TransportLentTest` | 57.1 | 48.6 |
| `MachineTransportsTest` | 44.9 | 40.8 |
| `IdentityBackupKeyCommandTest` | 39.6 | 30.9 |
| `AgentChoiceTest` | 36.8 | 34.0 |
| `OrgReadingCommandsTest` | 36.5 | 27.0 |
| `MessagingMethodsTest` | 32.8 | 30.5 |
| `VaultClearingTest` | 31.1 | 26.5 |
| `MessageWatchLoopsTest` | 27.7 | 24.1 |
| `ProjectOpenCommandTest` | 27.3 | 20.3 |
| `GitTreeContractTest` | 26.1 | 20.8 |
| `ProjectOpenCommandTest$CommandTests` | 26.0 | 18.5 |
| `InstalledEcosystemTest` | 24.2 | 30.3 |
| `VaultFileTest` | 23.3 | 19.5 |

**Against the Acceptance:** the count of classes is 502 in both logs, not the 504 written there, as later work took some away;
the tests per class were not compared one by one, and the line between the two logs added tests. Steps 2 and 3 of the plan, the
waits and the fixtures, were not built, as the target was met without them.

## To be checked

- The share of Argon2 in each class (count the derives; 0.15-0.3 s a derive was measured once, not per class), and
  the share of the set-up in each `RegistryCheckout` class: no run was made for this analysis.
- Per-test times of the twenty classes (only per-class times exist in the log): what the 191 and 121 tests of
  `ReconcileTest` and `TaskRunCommandTest` each do; neither was read test by test.
- Whether surefire can run the classes of the App module in two JVMs (`forkCount=2`, `reuseForks=false`): the
  keyring key (`vaultKeyringKey`), `System.setProperty` and fixed names would have to be per fork. Not read yet; it
  would cut more than any step above.
- Whether the CI runner's core count changes the Argon2 share (parallelism 4 on a runner with 2-4 cores).
- The 9 classes ranked 21-30 (`VaultRenewCommandTest` 11.05 s, `ReadyStepsTest` 11.06 s, `LoginRenewalTest` 11.97 s,
  `CommitVerifyCommandTest` 12.84 s, and others) for the same causes, once the first twenty are done.
