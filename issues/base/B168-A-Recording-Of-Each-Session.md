# B168 — A Recording Of Each Session

**Status:** Analyzed.
**Size:** M - not small: tmux runs inside the task as the agent, so the recording needs a reader on the host the
agent cannot steer, a format, a replay command, keeping and removing, docs and an acceptance step.

**What must be true.** What a task's terminal showed is kept on the host as a recording, can be played back, and is
linked from the task's journal.

## Why

After an unattended run, the journal says what was reached and refused, but not what the agent did and said on its
screen. A recording makes that traceable, and it fits "nothing leaves unseen". None of the comparable tools surveyed
keeps one.

## The shape

- The output of the task's tmux session, with its timing, written on the host outside the task's reach; the task
  cannot change or delete it.
- `sokar task replay TASK` plays it back in a terminal; it stays after the task is removed until the person removes it.
- The task's journal entry ([B154](README.md#b154)) names the recording and its checksum.

## Plan

The session (`Containerfile.SESSION`) is the agent's own tmux server, inside the container. `tmux pipe-pane` would be
the agent's to stop or to feed, so the host reads instead: a tmux client in control mode, read-only
(`tmux -C attach -r -t sokar`), started with `podman exec` as the agent's user. Control mode sends each pane's output
as `%output` lines and takes no size, so it changes nothing on the agent's screen. The host process that started it
writes the file: the agent can end the client, never change what was written, and an ended client is started again
and the gap is recorded.

1. **Red first:** a unit test in `apps/app` that `%output %0 ...` lines with tmux's octal escapes decode to the bytes
   the pane printed, and that a recording written from them replays in order with its delays.
2. **`apps/app-base/.../SessionRecording.java`:** decodes control-mode lines and writes the recording as
   newline-separated JSON events (`[seconds, "o", text]`, a header with the task, the agent and the size), with a gap
   event where the client was gone. The path is in `SokarPaths`, outside the task's state directory, so
   `task remove` leaves it: `recordings/<task>/<started>.cast`, `0600`.
3. **Started and stopped with the session:** `TaskRunner` starts the reader once the session is up (where it starts
   `ScreenFile.writer()`), the daemon's task watch restarts it when it ended while the task runs, and the task's stop
   closes it and writes its SHA-256 beside it.
4. **`apps/app-task/.../TaskReplayCommand.java`** (`sokar task replay TASK [--speed N] [--list]`), added to
   `TaskCommand`'s subcommands; it finds recordings of removed tasks by name. `sokar prune` gains `--recordings` to
   remove them; nothing removes one by itself in this step.
5. **The journal:** the task's record names the recording and its checksum now; [B154](README.md#b154)'s entry carries both once it is
   built.
6. **Docs:** `doc/commands.md` says the command and what a recording may hold: anything the agent printed, secrets
   included where it printed them, which is why it is `0600` and stays on the machine.
7. **Acceptance:** a scenario in `acceptance/suite/.../task/task-session.feature`: the stub prints its lines attached,
   the task is removed, and `sokar task replay` prints them in order.

## Acceptance

- Seen to fail first, then green: what a stub agent printed plays back in order after the task is removed.
- `doc/commands.md` says it, and what it may hold (anything the agent printed, secrets included where it printed them).

## Open questions

- How long recordings are kept by default, and their size limit: kept until removed and capped at 64 MiB per session
  in this plan, the rest cut and said (the operator's).

## To be checked

- Whether an unattended run is recorded the same way, when its output is already in its log (Agent Core's).
