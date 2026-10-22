# B238 — An Unfollow Leaves Nothing Of The Project

**Status:** Analyzed.
**Size:** XS - one file, `ProjectDeletion`, and its test; the kinds `BUILD` and `UPSTREAM_RECORD` exist, so no contract.

**What must be true.** `porelo project unfollow` removes what Porelo kept for building and fetching the project, so
`porelo prune` finds nothing of it afterwards.

## Why

Measured on `0.5.1~snapshot.307`: after `project unfollow` of three disposable projects, `porelo prune` listed each
project's `build/<project>.build.lock` and `upstream/<project>.<repository>` as left over, six entries, which had to
be removed by name.

## Plan

Read in `ProjectUnfollowCommand`, `ProjectDeletion`, `TaskRunner`, `GateSupport` and `Prune`.

1. `project unfollow` goes through `ProjectDeletion.delete`, which removes `build/<project>`, the
   `<project>_<repository>` entries and `upstream/<project>`, but not `build/<project>.build.lock` (`TaskRunner`) nor
   `upstream/<project>.<repository>` (`GateSupport.recordKey`). `Prune` knows both shapes, which is why it finds them.
2. `ProjectDeletion.plan` lists both, so `--dry-run` names them, matching `<project>.` as `Prune` does; `delete`
   removes them, and leaves a lock that is held, as `Prune` does with `FileLocks.free`.
3. **Red first:** `ProjectDeletionTest.removesItsBuildLockAndItsRepositoriesUpstreamRecords`, which also keeps
   another project's whose name begins the same way.

## Acceptance

- After `project unfollow` of a project that built an image and recorded an upstream, `porelo prune --dry-run`
  lists nothing of it. Seen to fail first: two entries per project today.
- `project unfollow --dry-run` names the lock and the records; a project named with the same beginning keeps its
  own.

## To be checked

- What an unfollow does while a build of the project holds the lock: read in the daemon's build path.
