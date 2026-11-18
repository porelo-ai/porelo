# B801 — A Host Taking Repositories Says So

```json
{
  "$schema": "issue-1.json",
  "id": "B801",
  "title": "A Host Taking Repositories Says So",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 78,
  "blockedBy": [],
  "covers": "While a follow run with a token takes a project's repositories, the host's progress says taking, with how many of how many are done and since when, written as it goes; waiting-for-token only while a token is really still needed"
}
```

**Why this size.** M: the host's follow (`host`), the machine's reader of the host's answer (`app-model`), the daemon
contract's project progress (`wire`, `20-project.varlink`) and `doc/commands.md`/`doc/hosts.md`; the contract's new
value and fields are agreed with the interface's owner. Its plan is read by another model before it is built.

**What must be true.** A person who has just handed a host the forge token sees, while the host takes the project's
repositories, that it is doing so and how far it is, and is not asked for the token again. A project says it waits
for a token only while the host really still needs one.

## Found

`FollowProjects.reconcile` sets the item's `state` to `waiting-for-token` before it looks at the token (line 382),
and `FollowProjects.takeNamed` (line 858), called from `repositories` (line 959), adds a read key at the central
forge and makes the first copy for each named repository in one loop, with `save(entries)` writing
`host-projects.json` only once the project's whole loop is done (line 988). For a
project with nineteen repositories that took about a minute, and the progress a machine read meanwhile still said
`waiting-for-token`, so the interface asked for the token again.

## Plan

- Host (`host/.../FollowProjects.java`):
  - with a token in hand, `reconcile` and `repositories` set the project's state to `taking` instead of
    `waiting-for-token`, with `taking_since` (UTC, read from `c.clock`), `taking_done` and `taking_total` (the
    repositories this run must take);
  - `takeNamed` saves `host-projects.json` after each repository it takes, through the same `save`, so a reader
    sees the count grow; the end of the run sets `carried`, or `waiting-for-token` only where a repository still waits
    for a token, or `held`/`refused` as today, and removes the `taking_*` fields;
  - a run that is stopped midway leaves `taking` behind: the next follow run, or a reader that finds `taking` with no
    follow run holding the host's follow lock, says the state the items show, never `taking` from a dead run.
- The host's state lists that validate the file: `HostFollow.progress` (line 110) and `HostProjects` take `taking`.
- Machine (`apps/app-model/.../HostCentral.java`): `PROJECT_STATES` gains `taking`, and the answer's `taking_done`,
  `taking_total` (non-negative, done at most total, bounded like `MOST_COMMITS`) and `taking_since` (an instant) are
  validated and carried; anything else stays refused as today.
- Contract (`20-project.varlink`): `hostState` documents `taking`, and the project row gains `hostTakingDone: ?int`,
  `hostTakingTotal: ?int` and `hostTakingSince: ?string`, absent in every other state; an added enum value and added
  optional fields are not breaking within `Tasks1`. The shape is posted to Agent Frontend in the room and agreed
  before it is built; `ProjectInventory` carries the fields, and the record crossing varlink is two edits as the rules
  say.
- A machine on an older version reads `taking` as a state it does not know and says `cannot-say` for the minute the
  run lasts, as it does for any unknown state; that is said in the hand-over to the interface's owner.
- Docs: `doc/commands.md` (project states) and `doc/hosts.md` say what `taking` means and that `waiting-for-token` now
  means only that a token is still needed.
- Changelog entry under *Added*.
- Unit tests, red first, at the host's follow over its fakes: a run with a token over three repositories writes
  `taking` with `taking_done` 0, 1, 2 as each is taken (the fake forge records the file at each call), and ends
  `carried` without `taking_*`; a run without a token still says `waiting-for-token`; a stale `taking` from a stopped
  run is not answered as `taking`. At the machine: `HostCentralTest` takes a `taking` answer with its counts and
  refuses done above total, negative counts and a malformed instant. The harm guarded: a person asked for a token the
  host already holds, or told a project is being taken when no run is taking it.

## Acceptance

- During a follow run with a token, `porelo project list --json` on a machine following the host answers `hostState:
  taking` with a count that grows, and `carried` at the end; without a token, `waiting-for-token` as before.
- The host-side tests above, each seen red on today's code, and the `HostCentralTest` refusals.

## To be checked

- How the host's follow lock is held, so a reader can tell a live run's `taking` from a dead one's.
- The shape of the three new fields with Agent Frontend, in the room, before the build.
