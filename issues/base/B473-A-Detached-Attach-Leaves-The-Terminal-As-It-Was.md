# B473 — A Detached Attach Leaves The Terminal As It Was

```json
{
  "$schema": "issue-1.json",
  "id": "B473",
  "title": "A Detached Attach Leaves The Terminal As It Was",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 85,
  "blockedBy": [],
  "covers": "However an attach ends, the CLI restores the terminal's settings and resets the modes tmux turns on: no mouse reports typed at the prompt, typing works"
}
```

**Why this size.** the CLI saves the terminal's settings before an attach and restores them, with the modes tmux turns on
reset, however the attach ends; its tests, and a check on the VM. Estimate 60 minutes.

**What must be true.** However `porelo task start` or `porelo task attach` leaves a task's session - the person detaches,
another client detaches this one, the session ends, the connection breaks - the terminal is as it was before: typing
echoes and works, and no mouse report, focus event or bracketed-paste mark is typed into the shell.

## Found

The operator's test (2026-11-06): a task on a repository in WSL attached in a Windows Terminal and in the window's
terminal; ending the terminal from the window detached the other client. The Windows Terminal showed Porelo's "detached
... is still running", and then at the shell prompt mouse reports were typed as text (`0;29;19M0;29;19m`) and typing no
longer worked.

Read in the code: the attach runs `podman exec -it ... tmux -f /etc/porelo/tmux.conf new-session -A -s porelo`
(`Podman.sessionArguments`, `TaskAttachCommand.sessionCommand`), on the person's terminal; the image's `tmux.conf` sets
`mouse on`, `focus-events on` and extended keys. When the session ends or is left, `TaskRunCommand` prints the
"detached ... is still running" lines and returns; nothing in Porelo restores the terminal. It relies on tmux's client
writing its reset on the way out and on `podman exec` putting the terminal back from raw mode. A client detached from
outside, or a `podman exec` that ends before the client's last bytes are through, leaves both undone: mouse tracking
on (the reports seen), and the terminal raw (typing that does nothing visible).

Which of the two happened here is not measured; the fix is the same for both.

## Built

On 2026-11-06 by a Codex task, reviewed line by line: one helper, `TerminalAttach`, around every attach of the CLI
(`task attach`, a start that attaches, a start that goes into a running task). With a terminal it saves `stty -g` from
`/dev/tty`, and on every way out restores it (the saved value passed as an argument, never as shell text) and writes
the mode resets, flushed before the detached-task lines. Without a terminal it adds nothing. An interrupted wait ends
the attach's child and waits for it before the restore. Changed in review: settings that cannot be saved no longer
refuse the attach; it runs, says so in one line, and still resets the modes. Tests were red before (compile) and the
changed one red against the refusing save. Not yet measured on the VM with `tmux detach-client` from outside.

## Plan

- Before an attach with a terminal, the CLI saves the terminal's settings (`stty -g` on it); after the attach returns,
  in every way out including an exception, it restores them and writes the resets for what tmux turns on: mouse
  tracking (`?1000`, `?1002`, `?1003`, `?1006`, `?1015` off), focus events (`?1004` off), bracketed paste (`?2004`
  off), extended keys (`CSI > 4 ; 0 m`), the alternate screen left (`?1049l`) and the cursor shown (`?25h`). Without a
  terminal nothing is written.
- Tests: the reset is written after a normal return, after a failed `podman exec` and after an exception, and never
  without a terminal; the saved settings are restored.
- **Measured on the VM:** an attach detached from outside (`tmux detach-client -s porelo` from a second `podman exec`),
  then the terminal's mode as `stty -a` reports it and a mouse click at the prompt, before and after the fix.

## Open questions

- The window's own terminal resets itself when its channel closes (Agent Frontend's to confirm); this issue is the CLI's.
