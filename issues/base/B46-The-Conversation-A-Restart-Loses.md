# B46 — The Conversation A Restart Loses

**Status:** Implementing.
**Size:** M - the mechanism is built; left are a scenario with a real reboot on a rented machine, a change to the stub,
and one decision.

**What must be true.** After a real reboot, `sokar task start --restarted` brings back a task that continues
the conversation its agent was having - in an unattended run and in a task somebody drove at a terminal - and a
task whose conversation cannot continue says so plainly instead of presenting itself as resumed.

## Plan

Built and on main:

- `SessionIds` (`agents/api`) is one declaration with both routes: `record` and `key` for an unattended run,
  `directory` and `suffix` for a driven one. One declaration serves both modes, which answers the old question.
- `TaskSession` (`apps/app-model`) keeps the id under the state directory, which a reboot does not reach. `TaskLaunch`
  takes it from a run's records; `TaskControl` takes it from the session files when an attached task stops. Starting
  a task continues it and says `session continuing <id>`, or that a fresh one starts because the agent does not say
  how to continue one.
- `sokar task start --restarted` (`TaskRunCommand.restarted`) brings every task back through the same start, and
  opens the vault once for all of them. The gate token is in the vault, so a reboot always asks for it once.
- `agent-session.feature` proves continuation across a stop and a start, unattended and attached;
  `task-reboot-reproduced.feature` reproduces what a reboot leaves; `task-restart.feature` (`@restart`) reboots, but
  with a shell task, and continues no conversation.

Left, in order:

1. Red first: a new `@restart` feature beside `task-restart.feature`, with a vault of its own whose tokens are kept.
   An unattended task and an attached task on the stub; the machine reboots; `sokar task start --restarted`; each
   is asked for something only its earlier turn holds. It fails today, because the stub has nothing to remember.
2. The stub (`agents/stub/src/main/resources/agent/stub.yaml`) remembers a word: the first run writes it into its
   session file under `.sokar-stub-sessions`, and a resumed run prints it back. Today `stub: continued` is printed
   for any `--resume`, which a fresh session handed a stale id would print too.
3. In the same feature, an agent definition without `session_id`, staged by the scenario, reports a fresh session
   after the reboot.
4. It runs on a rented machine only, never on the shared VM.
5. Once the question below is decided, `--restarted` treats a task started with a prompt and left to run that way.

## Acceptance

- A `@restart` scenario on a rented machine (never the shared VM): a task does some work, the machine is
  rebooted, and `start` continues the same conversation - the agent is asked something only the earlier turns
  answer, and answers it - asserted for both an unattended run and a driven one. Seen to fail: a fresh
  session, a start refused for want of the task's saved state, or tokens missing from the vault.
- In the same scenario, an agent whose definition declares no session id reports a fresh session after the
  reboot, and nothing claims a continuation.

## Open questions

- **What a task started with a prompt and left to run should be after a reboot** (the operator's). Its agent's work
  is gone; bringing the container back does not bring the work back, and presenting it as resumed is worse than
  leaving it down. `--restarted` brings it back like any other today. One answer is to bring it back stopped, with
  its session kept, so that the next run continues the conversation and says so.
