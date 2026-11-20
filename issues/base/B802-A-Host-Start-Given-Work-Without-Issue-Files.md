# B802 — A Host Start Given Work Without Issue Files

```json
{ "$schema": "issue-1.json", "id": "B802", "title": "A Host Start Given Work Without Issue Files", "state": "Review",
  "size": "S", "priority": "High", "rank": 1, "blockedBy": [],
  "covers": "A host daemon's `Start` given work and `given` files, through a forwarded socket or `porelo daemon connect`, is proven on a host; the refusal of a copy behind central says what stays true once no repository holds an issue file" }
```

**Why this size.** One module (`apps/app-task`) for the reworded refusal, one acceptance step on a host, no contract
change.

**What must be true.** When a project's issues live outside its repositories, a person's window starts a task on a host
by calling the host daemon's own `Start` with `work` and `given` (file name to text), and the task reads its issue at
`/run/porelo/given/<name>`, whether the window reaches the host's socket forwarded over SSH or through
`porelo daemon connect`. Nothing tells the person that a start was refused for an issue file the planner saved.

## Plan

- **What holds today, read in the code:** the daemon's `Start` takes `given` (`PoreloDaemon.givenFiles`,
  `TaskMethods`), checks it with `TaskGiven.refusal`, keeps the files owner-only and mounts them read only at
  `/run/porelo/given/`; a host's daemon is the same daemon. `porelo task run --host` refuses `--given`, but no
  window path uses it: the window calls the host daemon's `Start`. So no new call is needed.
- **The refusal of a copy behind central** (`CentralFreshness`, `TaskLaunch.centralRefusal`) stays: a start given
  work on a host whose copy is behind central is still refused, also once no repository holds an issue file, so a
  task never works on old code. Only its words change: they name the issue the planner saved to central, and say
  instead that a task given work starts on central's newest main.
- **Tests:** no unit test is written for the wording, which only reads wrong; the refusal itself stays pinned by
  `CentralFreshnessTest` as it is.
- **Acceptance step on a host:** a `Start` through `porelo daemon connect` to the host's daemon, with `work` and a
  `given` file near the 1 MiB limit, shows the text at `/run/porelo/given/<name>` in the task; one given file the
  rules refuse is refused with exit 64 before anything is made.
- **Docs:** `doc/running.md` says that a host's `Start` takes `given` as a local one does; `doc/decisions.md`, in
  "A host's copy follows central's main by itself", says the refusal stays without issue files.

## Acceptance

- The acceptance step passes on the VM through `porelo daemon connect`, the relay a window uses where no socket
  opens; a forwarded socket reaches the same daemon and the same `Start`, so it is not run apart.
- A host start given work on a copy behind central is still refused, and its reason no longer names an issue.

## Built

- The reworded refusal, the decision and the docs; the scenario "a start through the relay is given its issue as
  a file" in `task-unattended.feature` reads the given file's mount with `podman inspect` (source, read only) and its
  digest, and a refused name's exit 64 with nothing made.
