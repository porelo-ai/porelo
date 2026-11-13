# B666 — The Push Judge's Arming Stops On A Link

```json
{
  "$schema": "issue-1.json",
  "id": "B666",
  "title": "The Push Judge's Arming Stops On A Link",
  "state": "Review",
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

## Done

- **The arming stops on a link.** `FollowPushes.script` stops at a linked `git` directory of the forge (exit 6) and
  at a linked repository, where it passed over it before (exit 5). It checks the forge's shared hook and the judge as
  plain executable files that are no link, not with `-f` and `-x`, which follow one (exit 4 and 5). A repository name
  outside `[a-z0-9._-]` stops it before any link is made.
- **A stop leaves no link.** Every link the run made is removed when it stops, so a stopped arming leaves each
  repository as it was.
- **Removal decides once.** `ProjectFiles.directory` says whether the directory is there, by its one look that
  follows no link. `ProjectArrivals.removeProject` and the registration's removal take that answer and never look
  again. A linked arrivals directory is refused, and what it leads to stays.
- **The null object.** A verdict naming Git's null object name, the new side of a deletion in a push that also
  updates, is refused like any other bad worker answer and records nothing.
- **Red first.** `FollowPushesTest` runs the script itself with `sh` over a forge laid out in a directory: five
  cases failed before (the linked `git` directory, the linked repository, the linked hook, the linked judge, the
  link left after a stop). `PushJudgementTest.aVerdictNamingGitsNullObjectRecordsNothing` failed before.
  `ProjectArrivalsRemovalTest` pins the removal: a linked arrivals directory was already refused by the second look,
  so it passed before and after.
- Changelog under *Fixed*.
- **Locally.** host (1357, with the slow tag) passed whole, and so did the root's `validate` and `-Pdocuments test`.
