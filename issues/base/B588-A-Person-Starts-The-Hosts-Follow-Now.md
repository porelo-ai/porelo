# [B588](README.md#b588) — A Person Starts The Host's Follow Now

**Status:** Analyzed. Filed 2026-11-10 under ST290, from the fourth move.
**Size:** M - one authorized host verb and its person-side command, shared-pass waiting, documentation and machine acceptance.
**Blocked by:** [B550](README.md#b550).

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

## Acceptance

- On a machine, an admitted person's command starts a pass immediately, waits and reports its status; a simultaneous
  second call and a timer-started pass cause no extra pass. Verify the invocation in the journal and count passes.
- A deliberately failing pass answers failure to both waiters. An unadmitted or task account starts nothing, even
  when it can invoke the binary; a host without the verb answers unavailable so a client can explain that state.

## To be checked

- Measure the installed oneshot service's start-job and invocation behavior for both a running and a failing pass;
  settle the wait bound against the follow's 300-second work budget and configuration-lock waits.
