# B46 — The Conversation A Restart Loses

**Status:** Review. Green in porelo's Build 39083121366 on `842ff109`, on both rented legs, 2026-10-18.
**Size:** M - everything is built, and the real-reboot scenario passed on the `fedora` VM; left is the scenario green in the Build's rented legs.

**What must be true.** After a real reboot, a task continues the conversation its agent was having - in an
unattended run and in a task somebody drove at a terminal - and a task whose conversation cannot continue says so
plainly instead of presenting itself as resumed.

**A task started with a prompt and left to run is brought back stopped, decided 2026-10-11.** Its session is
kept, and it is shown as interrupted by the restart. The next run of it continues the conversation. It is never
restarted on its own and never shown as running. A task somebody drove at a terminal is brought back by
`porelo task start --restarted`, as before.

## Plan

Built and on main:

- `SessionIds` (`agents/api`) is one declaration with both routes: `record` and `key` for an unattended run,
  `directory` and `suffix` for a driven one. One declaration serves both modes, which answers the old question.
- `TaskSession` (`apps/app-model`) keeps the id under the state directory, which a reboot does not reach. `TaskLaunch`
  takes it from a run's records; `TaskControl` takes it from the session files when an attached task stops. Starting
  a task continues it and says `session continuing <id>`, or that a fresh one starts because the agent does not say
  how to continue one.
- `porelo task start --restarted` (`TaskRunCommand.restarted`) brings every task back through the same start, and
  opens the vault once for all of them. The gate token is in the vault, so a reboot always asks for it once.
- `agent-session.feature` proves continuation across a stop and a start, unattended and attached;
  `task-reboot-reproduced.feature` reproduces what a reboot leaves; `task-restart.feature` (`@restart`) reboots, but
  with a shell task, and continues no conversation.

Built, not yet on main:

- The stub (`stub.yaml`) keeps its session's first prompt, or the first line typed in it, in its session file under
  `.porelo-stub-sessions`, and a continued session says it back: `stub: continued, remembering <word>` unattended,
  `stub: continuing <id>, remembering <word>` attached. An id with no file is a fresh session, said as one
  (`stub: no session <id> to continue`), where any `--resume` printed `stub: continued` before. `StubAgentTest`
  runs the installed script both ways and with a stale id. `agent-session.feature` reads the new words.
- A copy of the stub binary installed as `porelo-agent-stub-sessionless` is the agent `stub-sessionless`: the same
  definition without `session_id` (`StubAgent.forExecutable`, by the binary's own file name).
- `--restarted` leaves out a task whose recorded mode is `UNATTENDED`: `TaskInventory` gives it the start detail
  `INTERRUPTED`, not `RESTARTED`; `--restarted` says `stays <name>: its run was interrupted by the restart; its
  session is kept` and starts nothing; `task list` names it under `interrupted by the machine's restart, staying
  stopped`, not under the line `--restarted` brings back. Its session record is untouched, so its next start
  continues it. The contract's comment on `startDetail` names the new text (comment only).
- The kit gains the step `a vault of this scenario's own that a restart keeps`, in the home directory.
- `task/task-restart-conversation.feature` (`@slow @restart`): an unattended task given `lantern`, an attached one
  typed `compass`, and an unattended run of `stub-sessionless`; the machine reboots; `--restarted` brings back only
  the attached one; the unattended one is stopped, listed as interrupted, its session kept; attaching shows
  `remembering compass`; the unattended one's next start says `session continuing` and `remembering lantern`; the
  sessionless one starts fresh and says so.

**Run, 2026-10-17.**

- On a rented `ccx33`, on `4f33f1c3`, the scenario held through the reboot: `--restarted` brought back the attached
  task, and left both unattended runs stopped with their sessions kept. `task list` named them as interrupted.
- It failed at the attached task, whose screen stayed empty for 900 s. `--restarted` starts the task's container and,
  by design, no agent in it. The scenario then ran `task start`, which answers that the task is running and names
  `task attach`. So nothing started the agent again.
- Reproduced on the two-CPU `fedora` VM, which reboots for real. There, `porelo task attach porelo-recall-attended`
  started the stub, continuing its session, and the screen showed `remembering compass`.
- The scenario now goes into the task with `task attach`, as this plan says ("attaching shows"). On `fedora` it then
  passed whole, with the reboot, all 40 steps, without help.

Left:

- The scenario green on both of porelo's rented legs in the Build, after phase 0's push.
- To decide, not blocking: whether `task start` on a running task whose agent has gone should start the agent again,
  instead of pointing to `task attach`.

## Acceptance

- A `@restart` scenario on a rented machine (never the shared VM): a task does some work, the machine is
  rebooted, and `start` continues the same conversation - the agent is asked something only the earlier turns
  answer, and answers it - asserted for both an unattended run and a driven one. The driven one comes back with
  `start --restarted`; the unattended one at its next `start`. Seen to fail: a fresh session, a start refused for
  want of the task's saved state, or tokens missing from the vault.
- In the same scenario, the task started with a prompt and left to run is not started by `--restarted` or by
  anything else on its own: it is listed stopped, as interrupted by the restart, with its session kept. Seen to
  fail: it runs again before somebody starts it, `task list` shows it as running or under "down because the
  machine restarted", or its session id is gone when it is started.
- In the same scenario, an agent whose definition declares no session id reports a fresh session after the
  reboot, and nothing claims a continuation.
