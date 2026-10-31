# B378 — A Failed Start Touches Only What It Made

**Status:** Idea.
**Size:** L, proposed - the failed start's cleanup bound to what it recorded, an existing task found before hooks are
repaired, helpers awaited and their failure a failed start, the host's refusals before the resume record, a kept
container resumable with its helpers and removable, one daemon reply and one test's helper, each with its test.

**What must be true.** A task start that fails stops and removes exactly what it made, and nothing it did not; what
it keeps can be resumed or removed as the message it prints says. A second reading of the fix of
[B367](README.md#b367) on 2026-10-31 found nine places where this does not hold yet:

- A start that fails before its container is recorded removes a container of the same name by name, so a container
  Porelo did not make is stopped and removed.
- With stale or missing OCI hooks, a start of a task that already runs skips the existing task, repairs the hooks and
  goes into the create path: a second vault serve overwrites the running task's `vault.pid` and `vault.token`, and the
  failed create stops the running task.
- The gate and the relay are not awaited until they record their pid, and `vault serve` writes its token before its
  pid, so a start that fails in that window leaves a helper running in its scope.
- A start and a resume answer 0 when a helper (`vault`, `relay`, `gate`, `watcher`) fails to come up.
- On a host, the kept signing key and the person's forge token are refused after the resume record is saved, so a
  second `task start` resumes the refused task without either and answers 0.
- A container kept after its agent's or workspace's files failed has no helpers recorded, so `task attach` or
  `task start` resumes it without its watcher, gate or vault.
- A container stopped before its workspace was a git checkout has no `unhanded-work` note, so `task remove`, which
  the failed start names, refuses without `--force`.
- The daemon's early refusal of a `subagents` that is no whole number leaves out `action` from its final reply.
- `TaskRunCommandTest`'s test of an agent's file placed after the checkout starts a real `gate serve` it never stops.

Each is answered by a test that fails today.
