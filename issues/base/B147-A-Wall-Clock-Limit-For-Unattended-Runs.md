# B147 — A Wall-Clock Limit For Unattended Runs

**Status:** Analyzed.
**Size:** M - a project key, a stop that really ends the run, and a reason in the status across `core`, `runtime`,
`app-task` and the daemon's contract; one measurement first.

**What must be true.** An unattended run ends at a time limit its project or its start sets, and says so.

## Why

An agent left to itself can run for as long as nobody looks.

**Guideline points it answers:** AISVS 9.1.1. From a review of security guidelines, 2026-10-09.

## The shape

A limit per project and per start; the run stopped as `task stop` stops it, its work kept; the reason in the task's status.

## A likely fault in today's limit, unmeasured

`task start --minutes` (default 30) ends an unattended run by killing the host's `podman exec` client at the deadline
(`Podman.execute`, `destroyForcibly`). Killing an exec client does not end the process it started in the container, and
without `--rm` - the default - nothing stops the task afterwards. So the agent most likely goes on working past its limit,
unseen, with its gate, proxy and firewall up. To be measured on a test machine first: after the limit, is the agent's
process still alive in the container?

## Plan

**Today (read 2026-10-11).** `--minutes` (`TaskRunCommand`, default 30) reaches `TaskLaunch.runAgent`, which calls
`runner.runAgent(..., Duration.ofMinutes(minutes))`; `Podman.execute` (`runtime/.../Podman.java`) waits for the host's
`podman exec` client, calls `destroyForcibly()` on it at the deadline and throws `ContainerException`; `TaskLaunch`
prints it and returns 124. The process inside the container is not ended; the task stays up with its gate, broker and
firewall; nothing records why the run ended. The deadline also lives only as long as the waiting CLI process. The
project file has no time limit: `Limits` (`core/.../project/Limits.java`) holds memory, cpus, pids and hand-in size.

**Steps:**

1. Measure on a test machine, without restart scenarios: after the limit, `podman top <task>` still shows the agent.
2. `limits.minutes` in `project.yml`: `Limits` gains `minutes` (default 30); `ProjectReader`, `ProjectSchema`,
   `doc/project-file.md`, `DocumentedProjectFileTest`. `--minutes` defaults to the project's value.
3. The run ends inside the container: the exec wraps the agent's command in
   `timeout --kill-after=30s <seconds>` (`Podman.executeArguments`), so the deadline holds even when the host's client
   dies; the host waits with a margin over it.
4. At the deadline the task is stopped as `task stop` stops it (`TaskControl`), its work kept, and the reason is
   written to the task record (`TaskProfile`, e.g. `ended: "time limit of 30 minutes reached at <time>"`).
   `task status`, `task list` and the daemon's task status (`15-task.varlink`) show it - a contract change.

Red first: a `Podman` test that `executeArguments` with a timeout wraps the command in `timeout`; then a
`TaskLaunch` test with a stand-in runner whose run outlives the limit - the task is stopped and its status names the
limit.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## Open questions

- The limit applies to the run a start with a prompt makes. Whether a person attached to the task holds the stop
  back (the operator's).
- Whether `--minutes` may go above the project's limit (the operator's).

## To be checked

- Whether every image Sokar builds carries `timeout`, or Sokar brings its own into the container (Agent Core's).
