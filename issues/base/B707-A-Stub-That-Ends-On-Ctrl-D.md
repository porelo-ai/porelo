# B707 — A Stub That Ends On Ctrl-D

```json
{
  "$schema": "issue-1.json",
  "id": "B707",
  "title": "A Stub That Ends On Ctrl-D",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 61,
  "blockedBy": [],
  "covers": "The stub agent, attended, ends when its terminal sends Ctrl-D on an empty line, as its script's read loop says, and the task then shows it ended.",
  "openQuestions": 0
}
```

**Why this size.** The cause is not read yet; a reproduction in the task's terminal, and a fix in the stub's script or
in how a key reaches the task's terminal.

Filed 2026-11-13 from Frontend's run of an interface story on the test VM with Core's packages from 40b17b120: Ctrl-D
on an empty line in the stub task's terminal left the stub running for 60 s. Typing, a paste and Ctrl-U reached the
same terminal in the steps before, so keys reach it.

The stub's attended script reads lines in `while IFS= read -r line; ... done; exit 0`, which ends on end of input.
So either Ctrl-D does not arrive as end of input (the terminal or its tmux session), or the stub that runs is in
another of its branches (the `.porelo-stub-works` loop, which reads nothing), or what ends is the loop but not the
process the story watches.

## Plan

- Reproduce in a test account on the VM: an attended stub task, `task attach`, Ctrl-D on an empty line; read the
  process tree in the container before and after.
- Fix where it breaks; red first: a kit scenario that sends Ctrl-D and sees the stub end.

## Acceptance

- Ctrl-D on an empty line ends the attended stub within seconds; a scenario fails while it keeps running.
