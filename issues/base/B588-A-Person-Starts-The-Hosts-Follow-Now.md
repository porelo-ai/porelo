# B588 — A Person Starts The Host's Follow Now

```json
{
  "$schema": "issue-1.json",
  "id": "B588",
  "title": "A Person Starts The Host's Follow Now",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 9,
  "blockedBy": ["B550"],
  "covers": "An admitted person's `porelo host follow` starts or joins one host follow pass through one sudo verb and answers its completion status."
}
```

**Why this size.** one authorized host verb and its person-side command, shared-pass waiting, documentation and machine acceptance.

Filed 2026-11-10 under ST290, from the fourth move.

**What must be true.** An admitted person can ask the host to read the organization now, wait for one follow pass and
receive its completion status. A second request while that pass runs waits for the same pass; a task's account or
anyone not admitted is refused before anything starts.

## Plan

Read in the code: `host/`'s `PoreloHost.OrgChange` and `HostFollow.root` refuse `SUDO_USER` for `org follow`,
`refresh` and `renew-pin`. `HostFollow.run` holds the exclusive `HostConfigurationLock` on `forge.conf` for a pass;
merely calling it again after acquiring that lock would run a second pass. `HostPerson.from` checks `SUDO_USER` and
`SUDO_UID` against the account database; `HostRecords.admitted` and `revoked` supply the admission checks.

- Add `porelo-host org follow-now`, through one exact sudo rule, with no login, service or token argument. Capture any
  running follow invocation before waiting for the configuration lock to check the sudo person's current admission,
  including revocation. Release that lock before asking the fixed `porelo-host-follow.service` to start or waiting for
  it. The root service continues to run `org follow`; no caller environment or credential enters it.
- Join an existing service invocation and wait for that invocation's result. Use systemd's start job and invocation
  identity rather than queuing another in-process follow behind `forge.conf`; retain the result by invocation so a
  pass observed running at entry remains this request's target even if it ends while authorization waits. Return its
  result, including failure, without mistaking an earlier status for this request. Bound the wait and report a failure
  to obtain a result distinctly from a completed pass.
- Add `porelo host follow` to `apps/app-vault`'s `HostCommand`, invoking the checked `/usr/sbin/porelo-host` through
  `sudo -n` and rendering its answer and exit status. This person's command is what the window runs through the host's
  login shell; porelod does not invoke the privileged verb. Document the exact sudo rule, answer and exit statuses in
  `host/src/main/man/porelo-host.8` and the person-side command in `doc/hosts.md`.
- Red first: a host command test admits a person and holds the service pass open; two calls observe one start and the
  same failed completion. A task login, unknown, locked, pending or revoked person, and mismatching sudo uid cause no
  service request. A person-side command test preserves a refused or failed result.

The read-only plan review added these constraints before implementation:

- Unit tests cover the admission, start/join and receipt flow through fake program responses; filesystem integration
  tests use private synthetic host files and Linux flock. Systemd and forge behavior is machine acceptance only.
- A separate short, private coordinator lock protects an entry snapshot and start selection. Retain the most recently
  selected job as well as a running invocation, so an idle caller joins work begun during its authorization. Never
  hold either lock while waiting for a pass. A checked `busctl` requests only the fixed unit's `StartUnit` with mode
  `fail`; its returned job number is matched to the root follow's own job and validated `INVOCATION_ID`.
- Root's `org follow` publishes a private immutable job-to-invocation receipt before its configuration lock and an
  immutable completion by invocation on return, including a caught failure. A missing receipt, killed process or
  missing result is unavailable, never an earlier completion. The answer contains only invocation, completed and
  exitCode; root's organization status and journal remain private.
- Bound this new command's account lookup, configuration/coordinator locks, program calls and receipt wait. An
  650-second monotonic operation budget leaves ten seconds for a bounded account lookup that can finish after
  that budget expires, bounding the command at 660 seconds. It allows a preceding pass and the selected pass,
  each within the unit's 300 seconds, with 50 seconds of operation margin; machine measurement must confirm it.

## Acceptance

All machine checks below are **not run** here and are for the supervisor. The sandbox runs unit and private-file
integration tests only; no systemd service, real account, forge or installed sudo rule was exercised.

- On a machine, an admitted person's command starts a pass immediately, waits and reports its status; a simultaneous
  second call and a timer-started pass cause no extra pass. Verify the invocation in the journal and count passes.
- A deliberately failing pass answers failure to both waiters. An unadmitted or task account starts nothing, even
  when it can invoke the binary; a host without the verb answers unavailable so a client can explain that state.
- Install the exact additional sudo rule documented in the man page and confirm the fixed root oneshot's ExecStart
  is `/usr/sbin/porelo-host org follow`, with empty stdin and no caller credential. The older host collection's
  helper cannot publish the new receipts; do not treat a unit still running it as a successful test.
- Count start jobs and invocation IDs for a very short pass, two initially idle requests, a timer pass during
  admission-lock contention, and a running pass that ends before admission completes. Start a later successful
  pass and verify the earlier waiters still receive the original failure, with no organization data or journal
  disclosed to them.
- Exercise a service dependency failure, canceled start, timeout and SIGKILL: no receipt means unavailable with
  no completed answer. Interrupt one waiter and verify the pass continues for the other. Confirm the ten-second
  account/systemd call bounds and the 660-second overall budget, including configuration-lock contention; an
  expired waiter must not cancel the shared service.

## As built

- Added the exact no-argument `org follow-now` sudo verb, authenticated against the account database and current
  admission/revocation records. It releases `forge.conf` before choosing, starting or waiting for a pass.
- A separate private coordinator captures the running job before authorization and coalesces initially idle
  requests with work begun during authorization. Checked `busctl` starts only the fixed service; checked
  `systemctl show --all` reads its identity. Root's `org follow` binds that job to its own validated invocation
  before taking the configuration lock, and publishes immutable completion by invocation, including failure.
- The person's `porelo host follow` uses checked sudo and the fixed host binary, an empty stdin and a chosen
  environment from `/`. It preserves completion/refusal output and exit status, with an unavailable explanation
  for an older host's unknown verb. The host returns only invocation, completed and exitCode.
- Unit tests use fake program responses; integration tests use synthetic root-owned attributes, private files,
  hard-link publication and real Linux flock. The first command tests were red for the missing verbs; deliberate
  behavior regressions also made the admission, coalescing, retained-result, trust and bounded-wait guards red.
- A completion-check race was reproduced before its fix: a pass ending between the receipt read and service
  read looked unavailable. The receipt is now read after observing job termination.
- A second race was reproduced with a blocked account lookup: it could miss a running pass that ended during
  the lookup and start another one. The entry snapshot now precedes the account lookup; missing sudo identity
  is still refused first, and all identity and admission checks precede any start request.
- Plan review used the read-only `gpt-6-astra` model. The final code reading used `gpt-6.1-sol`, including a narrow
  continuation for the account-lookup correction, and returned no findings. All 35 new tests and the targeted
  command, identity, lock, environment and man-page checks pass on the final code. The broader host run had
  1,006 tests with three sandbox errors; the app selection had 455 tests with seven socket/ownership errors and
  one pipe-timeout error that passed when rerun alone. The report names every refused test and separates runs.
  The repository's remote skill catalog could not be loaded because
  the sandbox could not resolve its host; no review claims to have loaded those packages.

## To be checked

- Measure the installed oneshot service's start-job and invocation behavior for both a running and a failing pass;
  settle the wait bound against the follow's 300-second work budget and configuration-lock waits.
