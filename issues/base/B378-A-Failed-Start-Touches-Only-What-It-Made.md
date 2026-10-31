# B378 — A Failed Start Touches Only What It Made

**Status:** Review.
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

## Done

- A start that fails before its container is recorded touches no container of the task's name: only a container whose
  id it recorded is its own, so one of that name it never made is neither stopped nor removed, and what it made for it
  - its helpers, found by their pid files whatever runs under that name, its conversation, mailbox, guide and state -
  is ended all the same.
- A start of a task that exists finds it whatever state its hooks are in. Hooks a start repairs, missing or left behind
  by an upgrade, are repaired once the task is found, before it is answered as running or brought back, so a running
  task is answered as running and never made again beside itself; hooks that cannot be repaired are refused by the
  launch before the runtime is asked anything, and for a task that does not exist the launch repairs them as before.
- A container kept after a start that failed before it was handed on records the helpers started for it, so starting
  it again brings them back. What had not been started yet, such as the clearance watcher when the agent's files
  failed, is not recorded and does not come back; its profile is still not written, so it comes back as a shell.
- Such a container holds nothing by its record where its stop could not ask its workspace, since nobody had worked in
  it, so `porelo task remove`, which the failed start names, removes it without `--force`. A note the stop could read,
  such as one counting an agent's file placed in a checked-out workspace, stays as it was read.
- The daemon's early refusal of a `subagents` that is no whole number sets `action` to `CREATE` on its final reply, as
  every refusal before a create does.
- `TaskRunCommandTest`'s test of an agent's file placed after the checkout starts its helpers as a program that exits
  at once, so no `gate serve` of this build is left serving whatever project of that name the account has.

Left, each still true as the list above says:

- The gate and the relay awaited until they record their pid, `vault serve` recording its pid before its token, and a
  helper that does not come up failing the start, and the resume, with 70. Every gated start in the unit tests runs
  this build's own `gate serve` against the account's own directories, where it fails or serves whatever project of
  that name the account has; awaiting it would turn those starts into failures or 20-second waits, so the helpers
  those tests start must first be stand-ins, a change across the test classes. A resume's count needs the same: its
  tests record helpers whose commands never write a pid file.
- The kept signing key and the forge token refused before the resume record is saved. Both refusals happen only on a
  host, whose helper is at a fixed root-owned path, and in a certified start, which needs a certifier's key from a
  verified access file; no unit test reaches them, so they wait for a seam for the helper's path or the machine test.
