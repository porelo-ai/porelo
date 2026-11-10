# B401 — What The Write Contract Does Not Yet Hold

```json
{
  "$schema": "issue-1.json",
  "id": "B401",
  "title": "What The Write Contract Does Not Yet Hold",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 36,
  "blockedBy": [],
  "covers": "What a store could do and still pass the write contract: five operations with role checks to extend, the source's silence, the last admin, backups and reasons never asserted"
}
```

**What must be true.** A store that takes what the organization's rules refuse fails `WritesContract`. Today a store
has gaps below that can still pass `organization/contract`; the five operations already have role checks. No fault in
the Git adapter is known from them; they were found by reading the checks, with nothing run.

## Plan

Each point is a check to add or change in `organization/contract`, seen to fail first against a broken stand-in.

- **Five operations with role checks.** `unlockProjectMember`, `removeHost`, `changeHost`, `removeHostAdmin` and
  `removeProjectFromHost` are implemented in `WritesStandIn` and called by `WritesContract`. Check the remaining
  stranger and developer refusals with `aDeveloperUnlocksNobody`,
  `aStrangerRemovesNoHost`, `aStrangerChangesNoHostsCard`, `aStrangerRemovesNoHostAdmin` and
  `aStrangerTakesNoProjectOffAHost`.
- **The source's silence.** `WritesFixture` can only say yes or no, and `Refused.CANNOT_SAY`, which `GitTreeWrites`
  throws, is never asserted. Add `sourceCannotSay(login)`, then checks for a founding and a first card under it. Read in
  `TreeWalk`, not run: a founding is not open under a silent source, since nothing is accepted and the answer is
  `cannot-say`; on a host the pin stands for the founder on purpose.
- **The last admin and host admin.** `memberChange` refuses a demotion or a lock that leaves no acting admin, and
  `GitTreeWrites` the host's last admin, yet only removal is checked. Add `theLastAdminIsNotLocked`,
  `theLastAdminIsNotDemoted` and `theLastHostAdminStays`. "An owner may remove a project's last admin" has no check.
- **What a change says it made.** A fresh reader's revision is compared with `Written.revision` only in
  `aFirstCardIsTrustedByAFreshReader`. Have every successful change's answer equal a fresh read, with `again` false.
- **Words in `doc/decisions.md` with no check.** "Stands only where the source lists the new key alone; otherwise it is
  refused": the source listing old and new together is not played. "A reader that trusted the state before keeps them":
  `read()` is always fresh, so no write check can hold it. Either a check or narrower words.
- **Success and the backup.** An owner revoking someone else's card, and that admin then acting, is not played.
  `aBackupKeyTakesItsOwnersPlace` cannot see the backup through its `filteredOn`, asserts no refusals and does not try
  the old key afterwards. The `host` form of both backup operations is never played.
- **Reasons never asserted in a write check.** `RESERVED_NAME`, `USAGE` for a role, a name or an address, `NO_CARD`,
  `NOT_FOUND`, `ON_ANOTHER_HOST`.
- **The stand-ins themselves.** `LOSES_THE_CHANGE` restores neither `hosts`, `hostOf` nor `backups`, which is why
  `anAddedHostHasItsAdminAndItsProject` is not in its list. `MOVES_ON_THOUGH_REFUSED` wraps only `step.run()`, so a
  `MOVED_ON` or an id's `USAGE` is never seen to leave something behind.

## Acceptance

- Each point's check passes against the Git adapter and fails against the stand-in that breaks it, or a finding in the
  Git adapter is reproduced by it and fixed.
