# B185 — A Bound On What A Task Writes To This Disk

**Status:** Estimated.
**Size:** M - a measured size per task with a limit in `project.yml`, a task paused at the limit with a sentence that
says why, the mailbox's own cap, unit tests and an acceptance scenario that writes past a small limit.

**What must be true.** A task cannot fill the disk of the machine it runs on. What it writes into its mailbox and into
its own container is measured. A task over its limit is paused before the disk is full, and a person is told why and
how to let it go on.

## Why

A security analysis inside a task found no bound on what the container writes into its mailbox. The box lives on the
person's own filesystem, under `$XDG_STATE_HOME/porelo/mail/<container>/box`. A process in the container could fill
the host's disk through it.

The finding is real but wider than the mailbox. The container's own writable layer, under the account's
`~/.local/share/containers`, is on the same disk and has no bound either. So a cap on the mailbox alone would leave the
same door open next to it.

## Plan

Read in today's code:

- The mailbox directories the agent writes are `inbox/new`, `inbox/cur`, `outbox/tmp` and `outbox/new`. Nothing
  measures them.
- The host takes from `outbox/new` regular files only, not followed, without a dot, ending in `.json`, at most 1 MiB
  each (`MessageIntake.MAX_BYTES`), listed one level deep.
  - A FIFO, a device node (impossible without capabilities), a deep tree or a long name is never read by it.
  - A hard link to a host file needs the target to be visible in the container and, with `fs.protected_hardlinks`,
    owned by the agent's user. So it can only link within the box.
  - None of these is a way in. What is open is only size.
- Podman's `--storage-opt size=` bounds a container's layer only on XFS with project quotas or on btrfs, not on the
  ext4 or WSL disks most machines have.

The plan:

- **Measured where it can be measured everywhere:**
  - The daemon's poll, which already lists each task, adds the size of the task's box (`du`-like, not followed, one
    file system).
  - It also adds the container's writable layer: `podman container inspect --size` gives `SizeRw`. Asked every
    minute, not every poll, since `--size` walks the layer.
- **The limits, each in `project.yml` with a default:**
  - `limits.disk`, for the task's layer and box together: 10 GiB;
  - `limits.mailbox`, for the box alone: 256 MiB, since a conversation needs kilobytes;
  - `limits.disk_free`, the free space below which the project's tasks are paused whichever task wrote: 2 GiB.
  Each takes a size like `20G` or `512M`. `0` switches that check off for the project.
- **Over the limit:** the task is paused (`podman pause`), not stopped, so nothing is lost. `task status`, `doctor` and
  the daemon's task answer say why and give the command that goes on with a higher limit
  (`porelo task resume TASK --disk 30G`) and the one that removes the task. A box over its cap is paused the same way.
- **Where the file system can bound it:** on XFS with project quotas or on btrfs, a start also passes
  `--storage-opt size=` with the limit, so the bound holds between two measurements.
- **The free space of the machine:** the second line, for a limit set too high. A task is also paused when its disk
  has less free than its project's `limits.disk_free`, whichever task wrote it.

Steps:

1. **Red first:** `DiskLimitTest`:
   - the size of a box, not following links;
   - the reading of `SizeRw`;
   - the decision to pause at each of the three limits, from the defaults and from a `project.yml` that sets them;
   - the sentence and the commands it names.
2. **Code:** the measurement in the daemon's poll, the three limits in `ProjectReader`, the pause and the resume option,
   `doc/project-file.md` and `doc/commands.md`.
3. **Acceptance:** a stub task with `limits.disk: 50M` writes 60 MB into `/tmp`. It is paused within the minute, and
   `task status` says why.
4. Reactor, documents and the suite on the VM (`not @restart`).

## Open questions

None. The defaults are settled: 10 GiB, 256 MiB and 2 GiB, each overridable in `project.yml`.
