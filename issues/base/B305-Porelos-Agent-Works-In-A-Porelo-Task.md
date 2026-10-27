# B305 — Porelo's Agent Works In A Porelo Task

**Status:** Idea.

**What must be true.** porelo's own agent works in a Porelo task of the project `porelo` on the move's host, as
porelo-project [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) lays out for sprint
8a, the last builder beside porelo-host. The task follows this repository through the gate and pushes to the host's
Forgejo, whose build is read through the `forgejo` reader. The agent is reached in the project's room as `Agent porelo`
or by the nickname `project.yml` gives it ([B298](README.md#b298)). This issue is taken from start to Review inside the
task, with the forge's build green. A way back to this machine stays open at any time, since a fault in Porelo stops
the agent's own work.

## What moves, and what to check

- **The repository:** `porelo` alone. `porelo-parent` has no task of its own and moves with it: its changes are made
  from porelo's task, as today from this machine. `porelo-adapters` and `porelo-ai.github.io`, kept by the same agent
  today, move on their own in sprint 8, by issues of their own repositories.
- **The task's image:** this repository's `porelo.yml`, Ubuntu by digest with the pinned GraalVM and `shfmt`, for the
  unit tests, `-Pnative,dist` and the package check. Its comment still calls it a draft nothing reads; Porelo reads a
  repository's own image now, so the comment changes with the move.
- **What a task cannot run:** the acceptance suite needs podman and a machine of its own, and the native build's
  checks need `~/.m2` of its own: the suite runs on the forge's legs or a VM of the host's pool, never in the task
  ([PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49)).
- **Forgejo's build:** `build.yml` asks for rented legs today; it runs on the local forge only after
  [B306](README.md#b306).
- **The way back:** the task's open work is fetched from the host's forge into this machine's checkout, the
  repository's upstream switches back in `project.yml`, and the local session resumes with its watcher, as the move's
  step 5 says.
