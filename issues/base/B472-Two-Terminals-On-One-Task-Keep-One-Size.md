# B472 — Two Terminals On One Task Keep One Size

**Status:** Review.
**Size:** S - one tmux option in the image's `tmux.conf`, its test, and a check with two clients on the VM. Estimate 45
minutes.

**What must be true.** A task attached from two terminals at once, of different sizes, keeps one size while both are
there: neither terminal's keystrokes resize the other's, and the agent's screen is drawn once, not again on every switch.
Its history holds what the agent wrote, not a copy of its banner per switch.

## Found

The operator's test (2026-11-06), a task on a repository in WSL attached at once in a Windows Terminal and in the
window's terminal; the same on Linux:

- **The smaller client shows a field of `·`** over the part of the window it does not cover, and **Claude Code's banner
  appears twice** in both, drawn again on each resize.
- **Scrolling up** (tmux's copy mode, `[3/3]`) shows the old lines repeated: the banner drawn twice is in the history.

Read in the code: the image's `/etc/porelo/tmux.conf` (`runtime/.../Containerfile.java`, the `printf` after "Then a
person's own file") sets no `window-size` and no `aggressive-resize`, so tmux's default applies: `window-size latest`
(tmux 3.1 and later). The window takes the size of the client that was active last, so a keystroke in one terminal
resizes the window to that terminal's size, and back on a keystroke in the other. Each resize sends the agent
`SIGWINCH`; Claude Code draws on the main screen, not the alternate one, so each full redraw scrolls the previous one
into tmux's history. The `·` field is tmux's mark for the part of a client the window does not reach.

**D is B's consequence**, not a fault of its own: with one size kept, nothing is redrawn and nothing repeats. What stays,
and is not Porelo's: a person who resizes a single terminal still gets one redraw per resize in the history, as with
Claude Code outside a task.

Inferred from tmux's documented default and Claude Code's drawing, not yet measured on the VM.

## Built

On 2026-11-06 by a Codex task, reviewed line by line: `set -g window-size smallest` in the image's `tmux.conf` before
the person's own file, its reason in the comment above the list; `ContainerfileTest` asserts the line and its place
(red before), and the golden `Containerfile.uc` carries the line and the new recipe hash. Not yet measured with two
clients on the VM.

## Plan

- `set -g window-size smallest` in the image's `tmux.conf`, before the person's own file, so a person may choose
  `latest` again. While two terminals are attached the window keeps the smaller one's size; the larger shows `·`
  beyond it, steadily, instead of the size changing hands.
- `ContainerfileTest` asserts the line and its place before `source-file -q` of the person's file.
- **Measured on the VM:** a task attached by `porelo task attach` from two `tmux`-less terminals of different sizes
  (two `script` sessions with set sizes), keys typed in each in turn: the pane's size, as `tmux display -p
  '#{window_width}x#{window_height}'` says, stays the smaller one, and `capture-pane -S -` holds the banner once.

## Open questions

- Whether the window's terminal should follow the smaller size instead of showing `·` is the window's (Agent Frontend).
