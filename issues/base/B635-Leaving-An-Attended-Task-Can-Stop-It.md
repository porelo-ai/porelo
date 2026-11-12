# B635 — Leaving An Attended Task Can Stop It

```json
{
  "$schema": "issue-1.json",
  "id": "B635",
  "title": "Leaving An Attended Task Can Stop It",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 33,
  "blockedBy": [],
  "covers": "Two choices made at a task's start, on the CLI and in Tasks1 Start so the window can offer them, neither the default: the session and the task end when the agent ends, and the task stops when the person leaves its shell; without them a task behaves as today."
}
```

**Why this size.** The attended command a session runs, how its end is told to whoever attached, the stop that follows, and the choice of where the person asks for it.

Filed 2026-11-12 from the operator's decision on ST314 (porelo-frontend F492 run 18). A discussion in the window ran pi
attended; the person quit pi and was left in a shell, which they had to leave with `exit` before the session ended.
The window said nothing in between.

**What must be true.** Two choices, each made when the task is started, on `porelo task start` and on Tasks1 Start, so
the window can offer them. Neither is the default; a task started without them behaves as today.

1. The session and the task end when the agent ends: the person's `/exit` in the agent ends the session and stops
   the task, with no shell left and no `porelo task stop`.
2. The task stops when the person leaves its shell: `exit` in the task's shell stops it in the same step.

Each is kept with the task, so a session gone into again ends as its start chose. The window says when a session
ended. Read first: `TaskSession.attachedAgent` and the session command it builds, the "you get a shell when it exits"
path in `TaskRunCommand`, and `TaskAttachCommand`'s end of a session.

Widened the same day by the operator: today a person ends an attended task in three steps, `/exit` in the agent,
`exit` in the task's shell, then `porelo task stop` (one more step in the window). It is to be one: leaving the agent
ends the session and stops the task. The issue weighs how a task says so (a start option, a project setting that a
task stops when its agent's session ends) and what the window offers for it. Not part of ST314.

Narrowed by the operator the same day: ending the session when the agent ends is an opt-in, never the default, since a
person often wants the task's shell after leaving the agent. What matters is that leaving the shell can stop the task
in the same step.

Sharpened by the operator the same day: both are optional and chosen at the task's start (CLI and Tasks1 Start),
neither is the default, and without them a task behaves as today.
