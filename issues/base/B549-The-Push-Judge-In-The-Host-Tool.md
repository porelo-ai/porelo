# B549 — The Push Judge In The Host Tool

```json
{
  "$schema": "issue-1.json",
  "id": "B549",
  "title": "The Push Judge In The Host Tool",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 35,
  "blockedBy": ["B547"],
  "covers": "`porelo-host judge-push` behind the judge socket, with every refusal test; built, in review."
}
```

Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.

**What must be true.** `porelo-host judge-push`, called by the socket's service from Forgejo's pre-receive hook, judges signatures, task
branches and the pusher's own tasks as `verify-push` does, using porelo's `HostArrivals`/`CommitVerifyCommand` code as
a library where the shapes allow.

## Why

Step 6. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## Plan

Keep the hook's HTTP protocol and the confined `porelo commit verify` worker, which uses `HostArrivals`.
Separate pure request parsing, task-account authorization and worker-answer judgment from the installed paths,
peer credentials, staging and arrival ledger. Add stdin socket activation without changing the forge gates' handoff.
Document the verb and its exit codes in the man page.

Test pyramid, planned before code: 24 pure push-policy cases and 30 pure HTTP cases; 8 narrow cases for
the installed adapters, real git/signature verification and inherited peer credentials; one pipe integration
case allowing a signed push and refusing an unsigned one. Pure cases use byte arrays and fakes, with no
sockets, processes, git or sleeps. Deliberately break and restore three policy/parser guards to observe red.
Run host and apps/app whole, root validate and the host FFM check offline with the pinned JDK. No VM run.

## As built

`porelo-host judge-push` reads and answers on the accepted Unix socket on stdin (fd 0), without `LISTEN_FDS`.
It requires root, refuses `SUDO_USER`, locks and reads `forge.conf` before checking the numeric
`SO_PEERCRED` uid against its `account=` setting, and holds that shared configuration lock. `PushRequest` parses the hook's bounded HTTP request; `PushJudgement` applies
task-account ownership and validates the verifier's complete answer before any arrival is recorded.
`HostPush` checks registration and every forge-owned quarantine directory, stages private trust files,
runs the existing confined `porelo commit verify`, and appends/fsyncs accepted arrivals through a pinned
descriptor. The man page documents the synopsis, protocol, confinement and codes by verb. One changelog
entry covers the new verb. The collection's service command still needs to select this verb; that
repository was read only.

Signature policy remains in `HostArrivals`: its framing and plain-key attribution now call the pure
`ArrivalKeyJudgement`. Git, SSH signature verification, revocation and recorded-certificate checks remain
the existing adapters. The privileged host does not parse untrusted git objects itself or link an app
area into its executable. A pusher's task-account restriction is about its person's recorded refs in this project;
eligible signers remain the project's people and task certificates, as in the predecessor.

### Test pyramid and measured results

The plan above was written before code: 54 pure cases, 8 narrow cases and one integration case.
The additional cases cover each malformed worker-answer field, filesystem refusal, and the extracted
signature policy. Final counts and JUnit class times, excluding Maven startup:

| Level | Cases | Result and time |
|---|---:|---|
| Pure policy, signature framing/attribution and HTTP | 97 | All passed: `PushJudgementTest` 49 in 0.353 s, `PushRequestTest` 36 in 0.041 s, `ArrivalKeyJudgementTest` 12 in 0.016 s; no files, sockets, processes, git or sleeps in these cases. |
| Narrow host files and confinement | 23 | All passed in 0.341 s without tracing; includes quarantine links, subordinate uid ownership, certificate dates/project filtering, KRL bounds and ledger publication. |
| Activation and invocation boundary | 5 | Four passed; `HostPushActivationTest.standardInputSocketActivationReadsTheKernelsNumericUidWithoutListenVariables` could not bind its socket in the sandbox. The invalid-descriptor downcall, root, sudo and ordinary-pipe guards ran. |
| Reused real git/signature adapters | 41 | All `CommitVerifyCommandTest` cases passed in 7.509 s, including its existing quarantined-hook case. |
| New integration | 1 | `HostPushIntegrationTest` passed in 4.711 s: the actual verb over process pipes, real temporary git objects and SSH signatures, exact hook status/body, and the arrival ledger. Only root/activation and the systemd launcher are replaced. |

Every added case passed except the socket activation case the sandbox prevented: 125 passed, one error.
The complete host run used `-Pwhole clean test` without a test selector: 1,151 cases, zero assertion
failures, 28 errors, no skips, 2 min 40 s. The complete `apps/app` run, where the gate tests live,
used the same whole profile and no selector: 3,676 cases, 92 assertion failures, 325 errors, one skip,
3 min 4 s. All three changed/new app test classes passed in that run. Most other failures arise from
denied sockets, unavailable test SSH agents, and their missing test adapters. Two additional path
failures were observed: `DefaultProjectTest.aCheckoutWithNoOriginIsItsOwnSourceAndNoCheckoutAtAllIsRefused`
finds this checkout above its fixture, and `ProjectVerifyCommandTest.aCopyNamedRelativeToWhereThisRunsIsRead`
cannot read its relative git directory beneath the configured scratch root. These were not changed.
The skipped app case is `DaemonHardeningTest.aDaemonWhoseEntriesBelongToAnotherUserIsNotDumpable`.
The local report links the complete failed/skipped method inventory, including the initial wire preflight.

The final adapter rerun used `-Pwhole,native,ffm-check -Dagent=true`; 27 of 28 cases passed, with only
the same socket activation error. `exec:java@ffm-check` passed with four registered and four observed
downcall shapes, including the new `getsockopt` shape. Root `-N validate` is recorded in the local report.
All Maven calls were offline with the pinned JDK and this worktree's installation prefix. No VM,
real systemd service, native executable, package or independent model review was run.

### Tests deliberately seen red

- `PushJudgementTest.aTaskAccountCannotWriteAnyRefOutsideItsPersonsRecordedTasks`: changed the task
  refusal's HTTP 403 to 200 in `PushJudgement.java` (line 76 at mutation time, current status at line 79).
  All six forbidden-ref cases failed their status assertion.
- `PushRequestTest.emptyOrMalformedPushesNeverGetTheDeletionShortcut`: changed `count == 0` to
  `count < 0` in `PushRequest.java:147`; the empty push failed because parsing no longer refused it.
- `ArrivalKeyJudgementTest.aBadSignatureByAKnownPersonsKeyIsRefusedByItsOwnReason`: inverted
  `principal != null` in `ArrivalKeyJudgement.java:56`; it failed because the bad signature was accepted.

All three files were restored byte for byte from copies taken immediately before mutation, and the
before/after diff statistics matched. The final whole runs passed those cases. Additional red-first
checks fixed plain-pipe activation's exit 73 and refusal of calendar years outside Python's 1–9999 range.

### Compatibility limits and test-environment correction

- Normal hook answers, HTTP framing, signature reasons, task ref rules, deletions, creations, force
  pushes, multi-ref pushes and arrival fields retain the predecessor's behavior. Root returns 69,
  sudo returns 65, ordinary non-socket input returns 73, and a served HTTP refusal still exits 0.
- The existing host frame additionally rejects unapproved environment variables and a non-host user
  namespace with 78/77. The accepted connection is explicitly Unix-only; arbitrary TCP socket input
  is refused before HTTP rather than being offered the predecessor's peer check.
- Programs and locale use `HostPrograms`' fixed PATH and environment from [B547](README.md#b547): no
  `/usr/local` programs. This can change dependency availability and low-level diagnostics.
- Unsafe or unreadable host data uses existing `HostFiles` diagnostics, including its 16 MiB read bound;
  those diagnostic bodies are not promised byte-identical to Python's. Worker stdout is bounded by
  the serialized verdict budget described below and stderr by 1 MiB. `HostPrograms` replaces malformed UTF-8 output, whereas Python's
  text subprocess reader can classify a decoding error as a worker failure. Actual verifier output is UTF-8.
- Credential staging writes/fsyncs new 0600 files inside each private push directory, instead of
  renaming temporary files into that new directory. The worker sees complete files in both cases.
  Cleanup remains best effort, as in the predecessor; ledger writes complete the entire append.
- Early test launches wrote JUnit temporary directories under `/tmp`, despite Maven's forwarded
  `java.io.tmpdir` property. A later app run was interrupted when this was observed again. Surefire's
  late `@{argLine}` ignores a command-line `argLine` override here; the added `porelo.test.jvm.args`
  property supplies actual startup options. The corrected whole app run and final adapter rerun use
  `target/tmp`, disable JVM performance files, and use a launcher under `target/` that delegates to the
  pinned JDK for child JVMs. The pipe integration also asserts its fixture uses the configured scratch root.

### Independent reading corrections

Shared-lock pushes can create the same push or arrivals directory concurrently. A creation that loses
to another creator now checks the winner's ownership, type and permissions before using it, without
changing the winner's permissions. A configuration-lock, ledger or other disk failure says the host
could not judge or record the push; only a failure reading the request blames the request. Every
HTTP 500 writes its diagnostic to standard error, including invalid verifier output and missing
judgment dependencies. Verifier refusals have one `porelo: ` prefix.

A verifier answer can record only refs in the hook's updates and at most 10,000 distinct commit ids.
The signer has exactly `kind`, `fingerprint`, `principal`, `certificate` and `signed_by`: strings
bounded as the verifier writes them, with null allowed only for the last three. Kinds, certificate
forms and fingerprints retain the verifier's shapes. The whole answer is validated before recording.
The ledger's exclusive append lock covers append, fsync and complete snapshot publication. Follow
streams ledger lines with a 128 KiB bound per line, so history has no total size cap that stops a push
or follow. A refused publication restores the previous ledger and removes a newly empty file.

One monotonic 30-second deadline bounds reading the entire HTTP request, including header and body;
progress cannot renew it. The socket reader uses the same kind of whole-request deadline, while an
answer has its own write budget. Existing forge proxy connections keep their per-read budgets.
The verifier serializer's largest schema verdict measures 100,627 UTF-8 bytes: a 64-character id,
a maximum escaped hook ref, two 16 KiB Unicode text fields, and the task signer's fixed fields.
With commas, framing and the final newline, that would allow `10000 * 100628 + 79` bytes, about
1 GB, which root would hold in memory. Stdout is therefore bounded at the smaller of that and 64 MiB
(`ProjectRuntime.MOST_PUSH_ANSWER`): an accepted verdict carries no reasons, and a push whose answer
does not fit is not judged (500, nothing recorded). A one-verdict budget exercises the calculation in
the process adapter test. Stderr has its independent 1 MiB ceiling, and crossing
either ceiling stops the worker even when its other stream has closed.

A person's task account can use only task records naming this project. A `porelo-<login>` pusher
whose person was purged is refused even for deletions; the host's own `porelo-admin` and
`porelo-publisher-<project>` accounts are exempt. The predecessor's treatment of ended task records
is kept: the record still names its task's branch, because the task's token ends with the task.
The activation test removes a stale socket before binding and uses the short relative
`target/pp.sock` path, below the Unix socket pathname limit even in a long checkout path.

Regression tests were added before the behavioral fixes. The local fix report records the observed
assertion failures, subsequent results, exact commands and sandbox limitations; the historical test
counts above describe the original implementation, not these corrections.

The corrections' final selected run passed 209 host tests and 63 app tests, including the real
signed/unsigned hook integration and all 42 `CommitVerifyCommandTest` cases. Those counts include
`HostProgramsTest`, `ProjectRuntimeTest` and `HostManPageTest` as extra checks. Two socket methods
were excluded only after an attempted run failed to create their sockets with `Operation not permitted`:
`GateConnectionTest.progressDoesNotRenewThePushConnectionsReadDeadline` and
`HostPushActivationTest.standardInputSocketActivationReadsTheKernelsNumericUidWithoutListenVariables`.
The requested command without an exec-check override stopped before tests because this checkout lacks
`issues/workflow.yml`, required by `check-citations`; the test runs used `-Dexec.skip=true` and offline
Maven. `host` was cleaned before the final compile, which passed NullAway. No VM or acceptance run
was made for these corrections.
