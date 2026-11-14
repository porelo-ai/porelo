# B772 — A Token Handed to the Host Takes Effect at Once

```json
{
  "$schema": "issue-1.json",
  "id": "B772",
  "title": "A Token Handed to the Host Takes Effect at Once",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 129,
  "blockedBy": [],
  "covers": "After `porelo org host refresh` hands the forge token over, the projects that waited for it are carried in that same call, or its answer says when they will be, and the window stops asking for the token",
  "openQuestions": 0
}
```

**Why this size.** M: the cause lies between `HostFollow.run("refresh")`, `addReadKey` and the follow it runs with the
token, and is found first in the host's journal; then the fix, its tests, and the window's answer. Estimate: 63
minutes (Core's median for M), p80 229.

**Not a bug on the host (2026-11-14).** Read again with the host's data: the host carried the project from the
follow after the hand-over on, and its publication said so; the running window kept the old "waits for your token"
state until it restarted. The window's half is Agent Frontend's F570 "A Token Wait Gives Way To A Carried Answer".
Left in Backlog for the operator to decide.

**What must be true.** Once the window has handed the token to the host, a project that waited for it is carried
before the hand-over answers. If that cannot be, the answer says the token was taken and when the projects follow,
and the window does not offer "Give token" again meanwhile.

Filed 2026-11-14 from the operator's live test on a rented host (porelo 0.5.1~snapshot.543, ubuntu 26.04), under ST361.

## Why

Seen: the operator gave the GitHub token in the window. The timer's follows at 15:15Z and 15:20Z still logged project
porelo `waiting-for-token` with `repositories []`. At about 15:25Z, `host-projects.json` showed `carried`,
`migrated true`, its room and its taken agents. Meanwhile the window offered "Give token" again, as if nothing had
happened.

Read in today's code: the window's `porelo org host refresh` (`OrgHostRefreshCommand`) hands the token over pinned SSH
to `porelo-host org refresh`. `HostFollow.run("refresh")` reads it, and either adds the organization's read key with
it (`addReadKey`, when none was added) or refuses when no project waits. In both cases it then runs `follow(bounded,
token)`, whose `projects.reconcile(..., token)` should migrate a waiting project in that call (`FollowProjects`,
`waiting-for-token` until `migrated`). A timer follow passes an empty token. So either the hand-over's own follow did
not migrate (refused, or out of its 300 s bound), or it migrated on a path the next timer follows did not report.

## Plan

1. Read the host's journal of the hand-over (`porelo-host org refresh`) and of the follows at 15:15Z, 15:20Z and
   15:25Z: what the refresh answered, whether `addReadKey` ran, and what `reconcile` said for project porelo. The
   operator's paste is in the coordinator's room post of 15:25Z.
2. Fix the cause so the hand-over's follow carries the waiting project, and pin it with a `HostFollow` test of a
   project waiting for its token: one refresh with the token leaves it `carried`.
3. The refresh's JSON answer says per project whether it was carried. Where it was not, it says `taken`, with the
   next follow's time. `OrgHostRefreshCommand` passes that on, so the window can stop offering the token. The
   window's own change is filed for the window's repository when this is built.

## Red-first test

The `HostFollow` test of step 2: a refresh with a token for a project in `waiting-for-token`, as the timer left it,
answers it `carried`; today it is expected to stay waiting (to be confirmed by step 1).

## Risks

- The cause may lie in the forge (a read key not yet usable right after it was added). Then the answer says
  `taken` with the next follow, and the host asks again sooner, not after five minutes.

## Blockers

None.

## Acceptance

- On a host with a project waiting for its token, handing it over from the window carries the project, or the
  window says when it will be and asks no more.

## Open questions

None.
