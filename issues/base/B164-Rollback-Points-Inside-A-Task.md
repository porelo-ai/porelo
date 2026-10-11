# B164 — Rollback Points Inside A Task

**Status:** Analyzed.
**Size:** L - a helper per task, a host-side store, a destructive command, the agents' session files and the contract.

**What must be true.** After each step of the agent, the task's workspace is kept as a state on the host; going back to
one restores its files and, where the agent can name its session, its conversation from that point.

## Why

An unattended run that took a wrong turn early is today either thrown away whole or mended by hand. The gate sees only
what the agent pushes, not the steps in between. Comparable tools let a person go back a step, files and conversation
together, and that is much asked for.

## The shape

- A state per step: a commit on a ref of the host's own in the task's workspace, never pushed and never seen by the gate
  as work; what makes a step is the agent's turn ending, as the activity reading of [B120](README.md#b120) already knows it.
- `sokar task rewind TASK [--to <n>]` lists the states, and restores one after a question with what is lost (asked
  as a destructive step: no by default).
- The conversation goes back where the agent names a session to resume from ([B46](README.md#b46)); otherwise only the files, and it
  says so.
- The resumption of a conversation by `session_id` and `resume_flag` is built (`agents/README.md`).

## Plan

Read in today's code:

- The workspace is a clone inside the container (`WorkspaceSetup`), never a host directory; the host reaches it only
  through `podman exec` and `podman cp`. A ref written inside the container is the agent's to change, so a state is
  only kept once it is on the host.
- A step's end is already read from outside: `AgentWaiting` (an attached task's screen, the agent's declared
  `at_rest` as [B120](README.md#b120) uses it) and `AgentEnding` (an unattended run's records).
- `TaskSession` records the session an agent ran, and a start again passes `resume_flag` with its id; the session's
  content lives in the agent's own files in the container.

Steps:

1. Red first, in `apps/app` with the stub agent: three turns make three states listed by `task rewind TASK`;
   rewinding to the first leaves the workspace's tracked files exactly as they were after it, and the later states
   still listed. Today `rewind` is an unknown command.
2. `TaskSteps` in `app-model`: at a turn's end, `podman exec` as root in the workspace builds a commit of all files
   with a throw-away index (`git add -A`, `write-tree`, `commit-tree` on `HEAD`), touching neither the agent's index
   nor its refs, and streams `git bundle create -` of it to the host into a bare repository per task under
   `TaskPaths.containerState(container)/steps.git`, ref `refs/steps/<n>`. That repository is the host's alone and
   never the gate's. Where the agent declares session files, a copy of them goes beside it as `steps/<n>/session`.
3. Who notices the turn's end: a helper per task beside the gate and the clearance watcher (`TaskHelpers`), polling
   `AgentWaiting`/`AgentEnding` as the listing does; a turn that ends while the helper is down is not a state.
4. `TaskRewindCommand` (`sokar task rewind TASK [--to <n>]`): without `--to`, lists the states with time and
   files changed; with it, names what is lost - the files changed since, the later turns of the conversation - and
   asks as a destructive step, default no, never taken by `--yes`. Then it stops the agent's session, copies the
   bundle in, resets tracked files and removes files the state did not have (ignored files are left and said), puts
   the session copy back and starts the agent with `resume_flag`; without a declared session only the files, and it
   says so.
5. Daemon: `Steps(task)` and `Rewind(task, to)` in `15-task.varlink`, `InterfaceDescriptionTest`.
6. `doc/commands.md`: `task rewind`, and what is not restored (ignored files, anything outside the workspace,
   what already reached the gate).

## Acceptance

- Seen to fail first, then green: three steps of a stub agent make three states; rewinding to the first restores its
  files exactly and leaves the later states listed.
- `doc/commands.md` says it, and what is not restored.

## Open questions

- How many states are kept, and what they cost on disk for a large workspace (the operator's).
- Whether a rewind may stop and restart the agent's session itself, or the person stops it first (the operator's).
