# B383 — A Build That Fits Its Old Time

**Status:** Backlog.
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
