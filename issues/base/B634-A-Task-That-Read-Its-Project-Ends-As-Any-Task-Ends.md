# B634 — A Task That Read Its Project Ends As Any Task Ends

```json
{
  "$schema": "issue-1.json",
  "id": "B634",
  "title": "A Task That Read Its Project Ends As Any Task Ends",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "A task that read its whole project is kept at its end as any task is, stopped and with its Result, unless its start asked for --rm, so a discussion's idea can be handed in after it ends; only the question about unhanded work stays unasked."
}
```

**Why this size.** One condition and one stop in `TaskLaunch.cleanUpOrFail`, the attach's words, the three tests
that pin the removal, and `doc/commands.md`.

Filed 2026-11-12 under ST314 step 3 (porelo-frontend F492 run 21). The window hands a discussion's idea in once List
shows its agent ended, and reads it with Tasks1 Result, which is "kept until the task's next start or its removal". A
discussion reads its whole project, and porelo removed such a task at its end, `--rm` or not. So no discussion's idea
could reach the planner. Agent Frontend asked whether porelo should keep it (a) or keep its record and Result after
removing it (b). The coordinator took (a): once the window has handed the idea in, it removes the ended task, as a
person would.

**What must be true.** A task that read its whole project and was started without `--rm` (`rm` on the wire) is kept
at its end, whichever way it ends: a prompt run, an attended session left, a shell left. It is stopped, never left
running, and its Result stays readable until it is removed or started again. Started with `--rm`, it is removed as
before. Its end never asks about unhanded work: it can hand nothing on. This takes back [B619](README.md#b619)'s removal at the end; a
reading task's trees now stay with its stopped container until it is removed.

## Plan

1. `TaskLaunch.cleanUpOrFail`: a reading task follows its start's keep. Kept, it is stopped, and the end says it is
   kept and how to remove it. Not kept, it is removed as today, without the unhanded-work question.
2. The attach's words for a reading task follow `--rm` as any task's do.
3. Red first: `TaskRunCommandTest`'s three removal tests become "kept and stopped without `--rm`", and one with
   `--rm` is still removed.
4. `doc/commands.md` on `--all-repositories`; the changelog.

## As built

- A reading task's end follows its start's keep on every path: `TaskLaunch.cleanUpOrFail` (an attached session at its
  start), `TaskLaunch.promptEnded` (a prompt run, at a terminal and through the daemon), and
  `TaskAttachCommand.leftAReader` (a session gone into again). Kept, it is stopped, never left running, and says
  `kept ... it only read, and is stopped` with how to remove it. With `--rm` (`rm` on the wire) it is removed. No path
  asks about unhanded work.
- `--rm` on going into a running task is refused before its session (exit 64), as for any task. A stopped reading task
  started again with `--rm` is brought back by the launch, whose end removes it.
- The attach's words, the `--all-repositories` help, `doc/commands.md` and the `allRepositories` contract say the same.
- An attended session writes no `task.log`, so List shows its end as a stopped task with its `result`, not as
  `agentEnded`. Agent Frontend was told, for the window's hand-in.

## After the code reading

Read by agy (gemini-4-argon-high).

- Taken (yellow): the `--all-repositories` help, the `allRepositories` contract and three comments still said the task
  is removed at its end.
- Not reproduced (yellow): `porelo task start <reader> --rm` going into a running task cannot ignore `--rm`, since
  that start is refused with 64 first, and a stopped one comes back through the launch's own end.
- Not reproduced (yellow): Tasks1 Result on a stopped attended task asked before any listing answers the file;
  `TaskStopTakesResultTest` keeps it so.
- Noted for the window (yellow): an attended session shows no `agentEnded`; its `result` is on the stopped row.
