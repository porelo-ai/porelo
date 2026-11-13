# B736 — Host Tests Pass Where A Caller's Group Is Not Its Uid

```json
{
  "$schema": "issue-1.json",
  "id": "B736",
  "title": "Host Tests Pass Where A Caller's Group Is Not Its Uid",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "The host's follow tests pass for a caller whose group number differs from its uid, and a follow that cannot copy a project's main says what stopped it instead of blaming the central forge.",
  "openQuestions": 0
}
```

**Why this size.** S: the test fixture's account table, one catch in the follow, and tests. Estimate: 45 minutes
(Core's median for S).

**What must be true.** `host`'s follow tests pass on any machine, whatever group number the caller's account has; and
when the follow cannot take a project's main, its refusal names what failed, so a fault of the host's own files is
not read as the central forge's.

Found on a build machine whose account had uid 1002 and gid 1003: `HostFollowFilesTest`, `HostFollowProjectsTest`,
`HostFollowSelectionTest` and `HostProjectMeasurementsTest` gave 40 failures and 4 errors; with uid equal to gid they
pass. Read at the stack's head: `FollowFixture` gives the forge account the caller's uid as both its uid and gid (the
account table at its setup and the `getent` stand-in). `ProjectCopies` then hands the copy to that gid through
`ProjectFiles.giveTree`, a chgrp to a group the caller is not in, and the kernel refuses. `FollowProjects` catches
the `IOException` and records "its main could not be read from the central forge", which hid the cause entirely.

## Plan

- `FollowFixture`: take the caller's gid from the root's `unix:gid` beside its uid, and give the forge account that
  gid in the account table and in `getent`'s answer.
- `FollowProjects`: the catch for the project file keeps the cause's kind: a failure to read from the central forge
  says so; a failure of the host's own copy (the chgrp or a write under the host's directories) says that the host
  could not place the project's files, with the failing step and no value from the project.
- First red: a `FollowFixture` run with a gid other than the uid (a fixture switch standing in for the caller's
  group) fails the follow tests as on the build machine; and a copy whose chgrp is refused records the forge words.

## Acceptance

- The host's follow tests pass with the caller's gid different from its uid.
- A refused chgrp in the follow's copy is recorded as the host's own failure, not the central forge's.

## Built

- `FollowFixture` gives the forge account the caller's gid from the root's `unix:gid`, in its account table and its
  `getent` stand-in. A switch, `-Dporelo.test.gidApart=true`, gives it another of the caller's groups whose number
  is not the uid, so the follow tests also run as on a machine where they differ.
- The host's own placing steps (the copies' directories, the chgrp to the forge's account, the follow's scratch
  directory and the staged signers) refuse with 73, "the host could not place the project's files: <step> failed",
  with no path or value from the project; only a read from the central forge keeps the forge's words.
- Red first: with a gid apart from the uid, the old fixture gave 40 failures and 4 errors in the follow tests, as on
  the build machine; `HostFollowFilesTest` shows a refused chgrp recorded as the forge's before and as the host's own
  after.
