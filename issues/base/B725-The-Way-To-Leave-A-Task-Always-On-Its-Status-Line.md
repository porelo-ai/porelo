# B725 — The Way To Leave A Task Always On Its Status Line

```json
{
  "$schema": "issue-1.json",
  "id": "B725",
  "title": "The Way To Leave A Task Always On Its Status Line",
  "state": "Review",
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

## Built

- `Containerfile`'s required tmux settings, read after the person's own file as `history-limit` is, now set
  `status on`, bind `d` to `detach-client`, raise `status-right-length` to 200 and append
  ` #{s/C-/Ctrl+/:prefix} d: leave, the task goes on` to `status-right` with `set -ga`, so the person's own right side
  stays in front of it. The golden Containerfile and its recipe label follow; task images build once anew.
- `ContainerfileTest.theStatusLineAlwaysSaysHowToLeaveWithoutEndingTheTask` reads the four lines in the part after the
  person's file; seen red on the old `Containerfile.java`.
- doc/running.md: "Leaving without ending it" names the status line and why Ctrl+z is no way out; "Your own settings"
  names the hint among what Porelo sets again.
- Checked in tmux 3.4 (ubuntu:24.04): no own file shows `Ctrl+b d: leave, the task goes on`; an own file setting
  `prefix C-a`, `status off` and `status-right MINE` shows `MINE Ctrl+a d: leave, the task goes on`, with the line on
  and `d` detaching. At 80 columns the hint is shown whole, tmux shortens the window list instead.
- Limits, left as they are: sourcing the settings again inside a task appends the hint twice; a person's
  `status-format` rewrite or `prefix None` can still hide or misname it; `#{s/…}` needs tmux 2.6 or newer.
