# B631 — A Task That Read Its Project Ends Without Failing

```json
{
  "$schema": "issue-1.json",
  "id": "B631",
  "title": "A Task That Read Its Project Ends Without Failing",
  "state": "Review",
  "size": "XS",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A prompted task that reads every repository of its project is removed at its run's end without the clean-up asking podman about it by name, so a run that went well answers its own exit code, never 70 \"no task Porelo made\"."
}
```

**Why this size.** one condition in `TaskLaunch.reapOrphans`, one test.

Filed 2026-11-12 from `porelo-frontend` F492 runs 12 and 13 (ST314, porelo 0.5.1~snapshot.500): the window's start of
foo-project's discussion, `porelo-foo-project_all`, ran pi and then answered exit 70 with
`porelo: cleaning up after it failed: porelo-foo-project_all is no task Porelo made: it has no record of it, so nothing
is done to it`. A List in the second before the container went answered its `task` as "", since its record was gone.

**What must be true.** A task that read its whole project and is removed at its run's end answers its run's own code.
Nothing after its removal asks podman about it by name.

How it is today, read in the code: `cleanUpOrFail` removes a reading task with `TaskControl.remove`, which takes its
recorded id, then calls `reapOrphans`, whose `pidOf(container)` goes through `Podman.target`. With no recorded id,
`target` throws `NotATaskException`, and `cleanUp` turns that into 70.

## As built

- `TaskLaunch.reapOrphans` asks podman for the container's pid only while the task has a recorded id; with none, its
  container is gone with its removal, and the reaping goes on to the helpers and the MCP endpoint by their state.
- `RecognizedTasksTest.aTaskThatReadItsProjectEndsWithoutFailingOnceItsRemovalTookItsRecord`, seen red with the VM's
  own words, `NotATaskException: porelo-foo-project_all is no task Porelo made`.
- Not seen here: the F492 run on the VM with this change.
