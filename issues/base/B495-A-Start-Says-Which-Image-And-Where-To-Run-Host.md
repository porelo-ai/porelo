# [B495](README.md#b495) — A Start Says Which Image It Runs And Where --host Runs

**Status:** Implementing.
**Size:** XS - three lines of wording in `TaskLaunch` and `TaskRunCommand`.

**What must be true.** A start's protocol does not read as if the task ran on two base images: the line printed before
the fetch says it is replaced when the fetched settings name another image, and the line about settings that changed
with the fetch names what it keeps - egress and the other settings, not the image. `task start --host` with no
ssh-agent running says that `--host` is run on the person's own computer, where their card's key is in their agent.

## Plan

- `TaskLaunch`: the first `base image` line says "before the fetch"; `settingsMovedOn` names egress and the other
  settings. `TaskRunCommand.onHost`: the no-agent refusal names the person's own computer.
- Wording only: no test, as the rules say for what only reads wrong.

## Acceptance

- The three lines read as above in a start's output.
