# B725 — The Way To Leave A Task Always On Its Status Line

```json
{
  "$schema": "issue-1.json",
  "id": "B725",
  "title": "The Way To Leave A Task Always On Its Status Line",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 6,
  "blockedBy": [],
  "covers": "A task's tmux status line always shows how to leave without ending the task (\"Ctrl+b d: leave, the task goes on\"), naming the prefix actually in force when the person's own tmux.conf sets another.",
  "openQuestions": 0
}
```

**Why this size.** One status-line setting in Porelo's tmux settings, set again after the person's own file as `history-limit` is, its text following the prefix in force, and tests. Estimate: 45 minutes (Core's median for S).

Decided by the operator on 2026-11-13. People forget the keys and try Ctrl+z and fg instead, which leave the task stopped in the terminal. doc/running.md's "Leaving without ending it" says the keys, but only there.

## Plan

- Porelo's tmux settings put the hint on the status line, and set it again after `$XDG_CONFIG_HOME/porelo/tmux.conf` is read, as `history-limit` and `default-terminal` are, so a person's file cannot drop it.
- The text follows the prefix in force (tmux's `#{prefix}` or the value read after the person's file), and the detach key bound to `detach-client`; it never names a key that does not detach.
- Room for the person's own status content stays: the hint goes on one side and is short.
- doc/running.md "Leaving without ending it" says the line is there.

## Acceptance

- Red first: a task without a tmux.conf shows "Ctrl+b d: leave, the task goes on".
- With a tmux.conf setting `prefix C-a`, the line says Ctrl+a d.
- A tmux.conf that turns the status line off or rewrites it still leaves the hint.
