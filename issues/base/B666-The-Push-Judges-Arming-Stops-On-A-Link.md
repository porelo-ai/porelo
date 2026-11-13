# B666 — The Push Judge's Arming Stops On A Link

```json
{
  "$schema": "issue-1.json",
  "id": "B666",
  "title": "The Push Judge's Arming Stops On A Link",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 41,
  "blockedBy": [
    "B549"
  ],
  "covers": "The host's push-judge arming stops on a linked repository or git directory, checks the hook and the link target as plain executable files without following links, leaves no link when it stops, and removal decides once",
  "openQuestions": 0
}
```

Filed 2026-11-12 from porelo-host HO86, whose open edges moved here with the push judge ([B549](README.md#b549)).

**What must be true.** Arming the push judge on a host never passes over a link, and removing a project's arrivals never follows one.

## Plan

- `host/src/main/java/org/fuin/porelo/host/FollowPushes.java`: the arming stops on a linked repository or a linked `git` directory instead of `continue`; the mounted hook and the link's target are checked as plain files that are executable, not with `-f`/`-x`, which follow links; a link it made is removed when it stops.
- `ProjectRegistration` and `ProjectArrivals.removeProject`: the arrivals directory is checked root's alone, and whether there is anything to remove is decided once, so a later look cannot remove through a link.
- To be read in `PushJudgementTest`: whether a verdict can record Git's null object name for a push that mixes a deletion with an update.
- First red: a `FollowPushesTest` case with a linked `git` directory, expecting the arming to stop.

## Acceptance

- Each edge seen red first in the `HostPush` and `FollowPushes` tests; the null-object case pinned either way.
