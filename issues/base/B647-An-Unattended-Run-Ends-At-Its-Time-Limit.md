# B647 — An Unattended Run Ends At Its Time Limit

```json
{
  "$schema": "issue-1.json",
  "id": "B647",
  "title": "An Unattended Run Ends At Its Time Limit",
  "state": "Review",
  "size": "M",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "An unattended run has a time limit, set at its start with a default, after which porelo ends it, records it as ended by its limit and keeps its log; a model that never ends its turn no longer holds a task forever.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 26, step 9. The reader `porelo-foo-project_foo-1-2`
(pi, glm-5.3-flash) never ended its turn. Every message stopped at a tool call, and it started foreground poll loops
of about 10 minutes again and again until a person stopped the task after 30 minutes. Agent Smith read the same log:
pi and its adapter behaved as built, since pi has no cap of its own and a shell call's timeout is the model's argument.
The run's overall time is porelo's to bound. (Why the reader polled at all is [B646](README.md#b646).)

How it is, read in the code on 2026-11-12 (this corrects the filing): an unattended run already has a limit.
- `task run --minutes` defaults to 30 (`TaskLaunch.DEFAULT_MINUTES`), and Tasks1 Start takes `minutes`
  (`PoreloDaemon.java:873`).
- `TaskLaunch.runAgentSteps` passes it to `TaskRunner.runAgent`, which runs the agent with `podman exec` and waits.
- At the deadline, `Podman.execute` calls `process.destroyForcibly()` on the `podman exec` client and throws
  (`Podman.java`, `execute`); the launch then records exit 124.
- Killing the `podman exec` client does not end the process it started in the container. The agent goes on, unbounded
  and unrecorded. That fits run 26: the reader went on past its 30 minutes until a person stopped it.

**What must be true.**
- An unattended run (`--prompt`, Tasks1 Start with a prompt) has a time limit: a default, and a value given at its
  start on the CLI and in Tasks1 Start, so the planner can set one per phase.
- When the limit passes, porelo ends the agent as a stop does, records the run as ended by its limit (List's and
  Watch's `agentEnded` say so), and keeps its log and its result file as at any end.
- An attended session has no such limit.

**Size.** M: the runtime's execute, the launch's end of a run, the ended record that List and Watch read, and the docs.

## Plan

1. **Red first.** A `PodmanTest` case whose `podman exec` outlives its deadline: the agent's process in the container
   is ended, not only the client. The fake runner sees the stop of the exec'd process, and `podman exec` gets no other
   answer. Then a `TaskLaunch` case: a run past its minutes records its end as by its limit.
2. **Ending the agent.** At the deadline, porelo ends the agent as a stop does: the task's container is stopped with
   its grace period (`TaskControl`'s stop path), its helpers as at any stop, and its log, workspace and result file
   kept. The client is still destroyed after that, as today.
3. **The record.** The launch writes `ended-by-limit` (the minutes and the time) into the task's state.
   `AgentEnding` answers it as `agentEnded` with `finished: false`, `source: "porelo"`,
   `error: "its time limit of N minutes passed"` and `limit: true`, before it asks the agent about its log.
4. **Visible.** List's and Watch's row carries the run's `minutes`, so the planner sees what it set. The default stays
   30; the planner sets one per phase through Tasks1 Start's `minutes`, as today.
5. An attended session has no limit, as today: `runAgentSteps` is only the unattended path.

## Built (2026-11-12)

- At the deadline, `Podman.execute` throws `RunTimedOut` (it threw a plain `ContainerException` before). The launch
  then writes `ended-by-limit.json` (the minutes and the time) into the task's state, and stops the task through
  `TaskControl.stop`, as `porelo task stop` does. The exit code stays 124.
- Each run writes `run-limit.json` with its minutes as it starts, and drops an earlier `ended-by-limit.json`.
- `AgentEnding` answers the record first: `agentEnded` with `finished: false`, `source: "porelo"`,
  `error: "its time limit of N minutes passed"` and `limit: true`. Every other end says `limit: false`.
- List's and Watch's row carries `minutes`: what the last run was given, 0 for an attended session and for a task
  from before.
- The daemon's Start runs the same launch, so it ends the same way. A daemon restart during a run is not covered
  (see To be checked).
- Both records live in the task's state directory under the runtime directory. A machine restart drops them, as it
  drops the sidecar: the row then says `minutes` 0 and `agentEnded` as the agent's log tells it.
- A second reading found five gaps, fixed:
  - A task that reads its whole project, started with `--rm`, was removed at its limit, with its log. A run its
    limit ended (exit 124 and the record) is now kept and stopped, as a failed run is.
  - The stop at the limit says what it left running, as `task stop` does.
  - An agent whose own end is in its log finished, though the limit passed as it ended: `agentEnded` answers its
    end, not the limit.
  - The record is written under the task's reservation, just before the container is stopped, and only when this
    stop is the one that stops it running. A person's stop that came first stays a stop.
  - `minutes` of 0 or less is refused on both surfaces in the same words, with exit 64. Tasks1 Start refuses a
    fraction, a number past the int range, a string or a boolean the same way, where it clamped, cut or took 30.
    Only an absent `minutes` takes the default 30.

## Acceptance

- A run started with `minutes: 1` whose agent never ends is stopped after about one minute. `podman ps` shows its
  container stopped, List says `agentEnded.limit: true`, and its log is kept. Seen red first with today's code, where
  the agent's process survives.
- An attended session runs past 30 minutes untouched.

## To be checked

- A start through porelod: whether the daemon waits in `runAgentSteps` as the CLI does, and what a daemon restart
  during a run does to the deadline. If the wait is lost with the daemon, the deadline is written into the task's
  state and enforced again on the daemon's start.

## Decided by the operator (2026-11-12)

- Accepted, priority Highest, with Estimated and Backlog given in advance for its analysis (ST314, run 26).

## Read by a second model (2026-11-12)

- Red, fixed: an `--all-repositories --rm` run its limit ended was removed with its log; such a run is now kept as a
  failed one is. `endAtLimit` reports what its stop left running, as a stop does. An agent that ended its run as the
  limit passed is reported as finished. A stop that came first is never recorded as the limit: the record is written
  under the task's reservation, only by the stop that stops it running. `minutes` of 0 or less is refused on both
  surfaces, and on Tasks1 Start a fraction, a number past the int range, a string or a boolean too, in the same
  words, exit code 64.
- Not changed: a daemon restart during a run loses its deadline (To be checked). The records live under the runtime
  directory by design, as written above.
- Not changed: List and Watch may ask git in the container while a limit's stop is still going; it ends with the stop.
- Read again (a Sonnet sub-agent): a log was read without the quiet wait while the limit's stop still ran, so a line
  the agent wrote meanwhile read as its end; the wait now holds while the container runs. `--minutes` on the
  command line is read by Porelo, not picocli, so a fraction, a word or a number past the int range is refused with
  64 in the words Tasks1 Start uses. A failure to write the limit's record no longer stops the stop.
- Not changed: when the reservation is held by another operation, the limit's stop is refused and the task is
  stopped by the run's clean-up, without the record.
