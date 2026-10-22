# B238 — An Unfollow Leaves Nothing Of The Project

**Status:** Rework. Its main part is published (snapshot 318). The rework stops the follow before an unfollow
removes the project's clone, rebased onto main past the change that confirms a stop by the recorded container id, and
fixes the faults a reading of it found.
**Size:** M - project deletion, reconciliation, the clone's lock and their command and daemon callers, with refusal,
ordering and concurrency tests; the contract documents the added reconciliation outcome.

**What must be true.** `porelo project unfollow` removes what Porelo kept for building and fetching the project, so
`porelo prune` finds nothing of it afterwards.

## Why

Measured on `0.5.1~snapshot.307`: after `project unfollow` of three disposable projects, `porelo prune` listed each
project's `build/<project>.build.lock` and `upstream/<project>.<repository>` as left over, six entries, which had to
be removed by name.

## Plan

Read in `ProjectUnfollowCommand`, `ProjectDeletion`, `TaskRunner`, `GateSupport` and `Prune`.

1. `project unfollow` goes through `ProjectDeletion.unfollow`, which checks refusals and previews before stopping
   following and removing project data. Cleanup includes `build/<project>`, the `<project>_<repository>` entries,
   `upstream/<project>`, `build/<project>.build.lock` (`TaskRunner`), and `upstream/<project>.<repository>`
   (`GateSupport.recordKey`); `Prune` recognizes these shapes too.
2. `ProjectDeletion.plan` lists both, so `--dry-run` names them, matching `<project>.` as `Prune` does; `delete`
   removes them, and leaves a lock that is held, as `Prune` does with `FileLocks.free`.
3. **Red first:** `ProjectDeletionTest.removesItsBuildLockAndItsRepositoriesUpstreamRecords`, which also keeps
   another project's whose name begins the same way.
4. Unfollow lists and removes the followed clone's adjacent `.git.lock` file, deleting the clone under the same
   lock as reconciliation, with the lock path shared by `Reconcile` and `ProjectDeletion` through `ProjectPaths`.
   A refused first follow and an unapplied follow remove both the clone and its lock; tests cover no new follow
   directory for a never-followed project and waiting for a held lock.
5. `FileLocks.holding` reads the path's file identity without following links, creates an absent lock, opens it
   without `CREATE`, locks it, and rereads its identity; a missing or changed file releases the lock and retries.
   `holdingThenRemoving` unlinks only while the lock is held, after the operation and before release, including
   failure paths. `Reconcile` removes an absent follow's lock through it. `ProjectDeletion.unfollow` holds the
   clone lock across record removal, project deletion, and restoration after a late refusal; unlinking is the last
   operation, so a nested hold cannot expose restoration or remaining cleanup to another process. Red-first tests
   use separate processes waiting on the old inode and a reconcile attempted during a late refusal.

## Acceptance

- After `project unfollow` of a project that built an image and recorded an upstream, `porelo prune --dry-run`
  lists nothing of it. Seen to fail first: two entries per project today.
- `project unfollow --dry-run` names the lock and the records; a project named with the same beginning keeps its
  own.

`ProjectUnfollowCommand`, `ProjectMethods` and `Clearing` share `ProjectDeletion.unfollow`: it checks refusals and
previews before changing anything, removes the follow record before deleting project data, and restores the record
if a task refuses at removal time; pinned keys stay through previews and refusals. The clone lock spans removal
and restoration, so a reconcile waits and reads the restored record. `Reconcile.run` rereads the record under the
clone lock and returns `NOT_FOLLOWED` without initializing a clone, unlinking the lock while held when the record
is absent. A waiter on an unlinked inode retries against the path's identity, and separate processes never overlap
critical sections. A refused first follow and an unapplied follow leave neither a clone nor its lock. A failed
deletion says that following has stopped and names the items to check and remove by hand; a second failure retains
the first failure's reason and adds its own. `Clearing` reports the removed follow even after project deletion
fails. `ProjectDeletion` exposes no unused check method, and `Removal` documents the clone and its adjacent lock
as `FOLLOWED_CLONE`.

## To be checked

- What an unfollow does while a build of the project holds the lock: read in the daemon's build path.
