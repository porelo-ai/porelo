# B551 — The Host Purges A Person And Restores Runtimes In Java

```json
{
  "$schema": "issue-1.json",
  "id": "B551",
  "title": "The Host Purges A Person And Restores Runtimes In Java",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": ["B550"],
  "covers": "`porelo-host person purge <login> | restore-runtime`."
}
```

Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.

**What must be true.** Purging a locked person (account, keys, Matrix account) and restarting each admitted person's user manager at up,
as `porelo-host-person purge` and `restore-runtime`.

## Why

Step 7, persons. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## Plan

- Keep `PoreloHost`'s grouped verbs: add `person purge <login>` and `person restore-runtime`. The former is an explicit
  root operation, as its predecessor; neither accepts an identity override for a caller-scoped operation. Check the real
  uid before parsing (69 for these verbs), keep `HostEnvironment`, and use existing checked programs and file access.
- Add a small `HostPeople` service holding the exclusive `forge.conf` lock for either verb. Validate the whole people
  inventory before effects. Purge refuses malformed names (64), root/system/unrecorded accounts (65), unlocked people,
  people without a due seven-day deadline, and anyone still listed in the applied organization's projects or host admins
  (65). Missing or malformed applied state refuses rather than guessing about membership. This intentionally tightens
  the predecessor's explicit purge, which did not require locking, absence or a deadline.
- Reuse `FollowPeople.purge` and `HostRevocations`; do not duplicate the already implemented follow's grace period,
  lock, Matrix expulsion, Forgejo token/account deletion, task stop/vault clear, account removal or subordinate-ID cleanup.
  Before purge effects, validate both subordinate-ID files and required teardown programs, including the account-absent
  retry. Keep the durable pending record and safety restorations on failure. Correct any shared-path defect only with
  a test seen failing first.
- Reuse `FollowPeople.runtime` for restore, returning whether its directory, daemon link or boot rule changed. Validate
  root's boot rule before effects, repair safe modes, preserve the person's existing daemon entry, and touch only records
  with memberships, unlocked and without a pending lock. Restart each admitted person's `user@<uid>.service`, as required
  by this brief, with checked `systemctl`; this adds the manager restart to the predecessor's file-only restoration.
- Tests are unit tests with `FollowFixture`'s injected file/account/program/clock boundaries and CLI calls through
  `PoreloHost.run`; no real account, systemd, vault or server access. First red: the CLI cannot restore an admitted runtime.
  Then observe every added test fail on behavior, including protected membership, unlocked/not-due refusal, predecessor
  refusal counterparts, teardown ordering/failures, account-absent retry, restore filtering and file trust. Each test has
  one line naming its harm. Run the requested offline host reactor tests; record sandbox refusals by test name.
- Have another model read this plan and the final code read-only, against the repository's seven named skills. Update
  this issue's As built and Acceptance, `doc/hosts.md`, the man page and one unreleased changelog entry. Keep the behavior
  inventory, existing implementation, observed reds, changed files and unrun checks in the untracked root report.

## Acceptance

- Unit tests must show that a listed person is never purged (including a locked project member), that unlocked/not-yet-due
  people are refused without effects, and that restore touches only admitted records. Every predecessor refusal must
  remain represented at the CLI, shared file/program boundaries or shared purge logic.
- Supervisor on a disposable real host: invoke both packaged verbs as root and refuse a non-root real uid before parsing;
  verify root-owned configuration/program trust and exclusive locking against the follow. Run restore through `up`,
  observe admitted managers restart and private runtime directories/links/tmpfiles rules restored, with locked, pending
  and membership-free people untouched. Confirm the collection's units/timers invoke the packaged grouped command.
- Supervisor with disposable accounts and services: lock a person who left all projects, refuse purge until seven days,
  then verify SSH/group/session revocation, Matrix expulsion/revocation, Forgejo token/account deletion, task stop before
  vault clear as that person, manager stop before `userdel --remove`, subordinate ranges removed with modes preserved,
  and the private purged list reserving the login. A project-listed person must survive even after seven days.
- Supervisor: fail Matrix expulsion, task stop, vault clear, manager stop and account removal individually; confirm a
  nonzero exit, safe account expiry and durable pending record, then retry successfully, including after account deletion.
  No real machine, root operation, Matrix server, package or native-image validation is claimed by the local unit run.

## As built

- `PoreloHost` adds the grouped `person purge <login>` and `person restore-runtime` verbs. Both require the real root
  uid before parsing (69), use the existing environment and namespace checks, and hold the exclusive configuration lock
  through `HostPeople`. No sudo rule or privilege is added. Purge explicitly names its target, as its predecessor did;
  the caller-scoped verbs still obtain their person only from sudo.
- Reused the follow's existing lock, seven-day scheduling, Matrix expulsion, Forgejo token/identity deletion, task stop,
  vault cleanup, account deletion, subordinate-ID release and purged-login reservation. The follow already canceled
  deadlines for project-listed people and host admins. It was not reimplemented.
- The shared purge now checks account ownership by the host's UID range, locked/due status, remaining recorded
  memberships and the applied project's/admin's login entries before effects. Missing/malformed membership inventory
  or a refused project prevents deletion. Required programs and both subordinate-ID inventories are checked before
  revocation. A pending cleanup may retry its lock, including one left temporarily marked unlocked after failed safety
  restoration; deletion proceeds only after that lock completes.
- Restore repairs only admitted records and restarts their user managers. Runtime creation is shared with admission;
  it explicitly sets 0700 despite a restrictive umask, checks the boot rule before creating the delivery directory,
  preserves a person's daemon entry, repairs safe boot-rule modes to 0644, and reports the count of changed persons.
  Manager restarts do not increment that count. `ProjectRuntime` exposes its existing file boundary to the command's
  injected context; no second file, program or account implementation was added.
- Intentional differences from the predecessor: explicit purge refuses an active or not-yet-due person and anyone
  still listed; restore also restarts each admitted manager. Both follow the build brief's stricter requirements.
- The plan was read by a different model, read-only. Its membership-validation caution is implemented: a shallowly
  readable project object is not taken as evidence that a person left. Tests observed red on missing command behavior,
  on a rejected project permitting the follow purge, on unusable runtime creation under umask 0777, and on deliberately
  bypassed name validation. The deliberately broken sources were restored from exact pre-mutation copies.
- The final code was read by the other model, read-only, with no harmful findings, including completion on the final
  checked-parent mode-setting correction. A clean host compile passed NullAway. The final selected run passed 1,047 host
  tests, including all 70 new invocations, plus 14 core and 43 vault tests. The manual's five document tests passed.
  Root validation passed the changelog, shared rules, citations, README, documentation-site and action checks.
- The full requested selection was attempted. The sandbox refused Unix socket binding in
  `HostVaultShareTest.an_agent_that_hangs_up_is_a_vault_error`,
  `HostVaultShareTest.derives_the_same_share_through_the_agent`,
  `HostProjectUnixTest.addRegistersThroughTheUnixSocketAndNeverUsesCentral` and
  `HostPushActivationTest.standardInputSocketActivationReadsTheKernelsNumericUidWithoutListenVariables`;
  `HostUidTest.thisTestRunsInTheHostsOwnNamespaceWhereTheMapIsReadable` refused the sandbox's user namespace.
  Those five methods were explicitly excluded from the final passing run, without changing their code. Run them in
  the host namespace with Unix sockets permitted. The real-machine acceptance above remains for the supervisor;
  no root account, systemd, real Forgejo/Matrix, package or native-image acceptance is claimed.
