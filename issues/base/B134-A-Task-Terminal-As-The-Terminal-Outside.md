# B134 — A Task Terminal As The Terminal Outside

**Status:** Review. Green in porelo's Build 39083121366 on `842ff109`, on both rented legs, 2026-10-18. What is left is the joint test with the operator.
**Size:** M - left are the per-key scenario's run on the VM and the joint test; the whole was L.

**What must be true.** An agent in a task behaves at the keyboard and on the screen as it does in a plain terminal on
the same machine. Scrolling, Esc, modified keys, colours, the clipboard, links, focus and images work as they do
outside. Where something cannot, it is written down, with why.

## Why

On 2026-10-09 it was found that PageUp and PageDown do not scroll in Oh My Pi inside a task, though they do in
a plain console. It holds for every agent, since each runs in the one tmux session the task image sets up.
`/etc/porelo/tmux.conf` (`runtime/.../Containerfile.java`) holds `history-limit 10000`, `extended-keys on`,
`terminal-features 'xterm*:extkeys'` and `default-terminal tmux-256color`, and nothing for scrolling, Esc, colours,
the clipboard or passthrough.

## What is suspected, to be measured, not assumed

    behaviour                       plain terminal         in the task's tmux today          what would close it
    scrolling (PageUp, Shift+PgUp,  the terminal's own     tmux's history, reached only      mouse on; PageUp into copy mode
      mouse wheel)                    scrollback             through the prefix and [          unless the pane is in the
                                                                                               alternate screen
    Esc                             at once                after escape-time, 500 ms in      escape-time 0 or 10
                                                           tmux 3.4; agents interrupt on Esc
    Shift+Enter, Ctrl+arrows, ...   the terminal's codes   translated by tmux                extended-keys always;
                                                                                               extended-keys-format csi-u
                                                                                               (tmux 3.5 and later)
    colours                         truecolor              256 unless told                   terminal-features RGB
    clipboard (OSC 52), links,      passed                 filtered                          set-clipboard on; terminal-features
      focus events                                                                             clipboard, hyperlinks, focus;
                                                                                               focus-events on
    images, other passthrough       passed                 blocked                           allow-passthrough on

Two terminals are involved. One is **outside**: whatever the person uses, or the interface. tmux uses its
capabilities only where `terminal-features` names them. The other is **inside**, the one the agent sees
(`tmux-256color`). tmux's version comes from the project's base image: `ubuntu:24.04`, the default, has 3.4,
without `extended-keys-format`.

## The shape

1. **Measured first, on the Ubuntu VM:** every row above for Claude Code, Pi and Oh My Pi, once in a plain terminal
   and once in a task, with the same outside terminal. Also whether the agent handles PageUp itself: a full-screen
   agent in the alternate screen may scroll its own view, and then tmux must pass the key, not take it. The result is
   a table in this issue: what differs, per agent.
2. **Then the configuration** that closes what differs, written into `tmux.conf` by `Containerfile`, fitted to the
   image's tmux. An option a tmux lacks is set with `-q`, as `extended-keys` is today, and the measurement says
   what that costs on 3.4.
3. **A person's own tmux settings, per account:** Porelo's `tmux.conf` ends by loading an optional file of the
   account, `~/.config/porelo/tmux.conf`, given to the task read-only at its start and read with `source-file -q`
   after Porelo's settings. Key bindings, the prefix, the mouse and colours can then be set once for all of an
   account's tasks, with no image rebuilt; without the file nothing changes. What Porelo itself depends on - the
   screen it reads for `at_rest`, the lines it types to wake an agent - is set again after the person's file, so a
   setting there cannot break it unawares; which settings those are comes out of the measurement and is written down.
   Decided on 2026-10-09.
4. **tmux stays.** It carries reattaching, the `at_rest` screen reading and the wake lines. A relay that passes raw
   bytes without a terminal of its own comes up only if configuration cannot get there, and then as a proposal to
   the operator, with what would be lost.

## Acceptance

- A test per key and capability, in the acceptance kit: send the key or sequence into a task's session and read what
  the program inside received, against what a plain terminal delivers. A difference is caught when it comes back.
- `ContainerfileTest` pins the configuration lines, as it pins today's.
- A binding set in the account's `~/.config/porelo/tmux.conf` works in a task started after it, and a setting
  there that Porelo depends on is overridden, each seen in a test.
- Seen to fail first: PageUp in a task today, against the measured plain-terminal behaviour.

## Measured, 2026-10-09

tmux 3.4 with `/etc/porelo/tmux.conf`, behind `podman exec -it` in `ubuntu:24.04`, against the same path without tmux.
A program in the container logged every byte it received, with the monotonic time the host shares, and wrote
the sequences an agent writes; a script played the outside terminal (`TERM=xterm-256color`).

    what                               without tmux        today's tmux        as built
    Esc                                0 ms                501 ms              10 ms
    PageUp, Ctrl+Right, Alt+x, paste   as sent             as sent             as sent
    Shift+Enter, CSI u form            as sent             nothing             as sent
    Shift+Enter, xterm's form          as sent             nothing             in the CSI u form
    focus out, focus in                as sent             nothing             as sent
    the wheel, the agent asked         as sent             as sent             as sent
    Shift+PageUp                       the terminal's      to the agent        tmux's history (no alternate
                                       scrollback                              screen), else to the agent
    Shift+PageDown                     the terminal's      to the agent        down tmux's history, leaving it
                                       scrollback                              at the bottom; else to the agent
    truecolour, OSC 52                 passed              dropped             passed
    links (OSC 8), title               passed             dropped             passed (a link gains an id)
    images (kitty protocol)            passed              dropped             only wrapped for tmux

Claude Code runs without the alternate screen and asks for no mouse; Oh My Pi runs in the alternate screen and asks
for the mouse. With `mouse off`, tmux never asked the outside terminal for it, so the wheel reached Oh My Pi as
whatever that terminal sends in an alternate screen, usually arrow keys, not as scrolling.

**Not closed, written down in `doc/running.md`:** the terminal's own scrollback holds nothing of the session; while
scrolled back in tmux, keys go to tmux; a mouse selection is tmux's; xterm's Shift+Enter arrives as CSI u; images
need wrapping. **What Porelo depends on**, set again after the account's file: `history-limit` and `default-terminal`.

**As built:** the settings in `Containerfile`, pinned by `ContainerfileTest`; `~/.config/porelo/tmux.conf` copied into
a task as root after it starts (`TaskRunner`, `AccountTmuxTest`); the order checked in a built image, with a binding
and `mouse off` from the account's file taking effect and its `history-limit` and `default-terminal` set back.
**Open:** Pi, and each agent in a real task with a person's own terminal; the acceptance kit's per-key test.

**Found in the joint test, 2026-10-10:** scrolled back with Shift+PageUp, Shift+PageDown did nothing - it was bound
neither outside copy mode nor in it. Bound since: a page down in copy mode, which `-e` leaves at the bottom, and the
key to the program outside it. Checked in tmux 3.4 with the keys typed through an attached client: 21, 42, 21, out of
copy mode, then to the program.

## Plan

Built and on main (`e55e8f0d`, `2d669ab1` for Shift+PageDown): the settings in
`runtime/src/main/java/org/fuin/porelo/runtime/Containerfile.java`, pinned by `ContainerfileTest`; the account's
`~/.config/porelo/tmux.conf` copied in by `TaskRunner` (`AccountTmuxTest`); `doc/running.md` says what is not closed.
Left, in this order:

1. **Pi measured,** on the Ubuntu VM with the logging program and the script of 2026-10-09, a column beside Claude Code
   and Oh My Pi: alternate screen or not, the mouse asked for or not, and whether it scrolls its own view on PageUp.
   A difference the settings do not cover is a new row under "Not closed" and in `doc/running.md`.
2. **A step to send a key as bytes.** `acceptance/kit` `TerminalSteps` gains `I press the key {string}`, a table of
   names to the bytes an `xterm-256color` sends (Esc, PageUp, Shift+PageUp, Shift+Enter in both forms, Ctrl+Right,
   Alt+x, focus in and out), written raw through `Terminal` as `I type` writes text.
3. **A scenario per key**, `acceptance/suite/.../task/task-terminal.feature`: a task with `--agent stub --attach
   shell`; in the session `stty raw -echo; od -An -tx1 -v` stands in for the agent; each key is pressed and the
   terminal shows the hex the table says arrives (CSI u for xterm's Shift+Enter, as measured). Esc is pressed and must
   show within one second, which today's 500 ms would still pass, so its timing stays with `ContainerfileTest`.
4. **The joint test** covers the rest: each agent in a real task with a person's own terminal, scrolling, links,
   images and the clipboard, which the kit's terminal does not render.

Red first: the scenario for Shift+Enter in the CSI u form against the image of the commit before `e55e8f0d`, where
tmux delivered nothing.

**Built, 2026-10-17:** steps 2 and 3.
- `Keys` in `acceptance/kit` names eleven keys as the bytes an `xterm-256color` sends - Esc, PageUp, PageDown,
  Shift+PageUp, Shift+PageDown, Shift+Enter in both forms, Ctrl+Right, Alt+x, focus in and out - and refuses any other
  name, listing them. The step `I press the key {string}` writes them raw through `Terminal.press`.
  `KeysTest` was seen red against an empty table first.
- `task/task-keys.feature`: one scenario, one task, each key in turn. The program behind tmux is
  `head -c <n> | od -An -tx1`, started after a READY mark built at run time, and the scenario waits for the hex it
  prints. Shift+PageUp and Shift+PageDown are left out: they scroll tmux's history and reach no program outside the
  alternate screen. **Run on the ubuntu VM, 2026-10-17: every key arrived as measured** - Esc, PageUp, Ctrl+Right,
  Alt+x, and Shift+Enter in both forms, xterm's arriving in the CSI u form. **Focus in and out did not**, though the
  program asked for them; measured passing on 2026-10-09 with a script as the outside terminal, so it is the kit's
  ssh terminal, not tmux. Focus does no harm when it is lost, so it is left out of the scenario, not chased.
- **Pi measured** on the ubuntu VM (step 1), 2026-10-17: Pi 0.85.0 in a task's image, in a tmux session started with
  `/etc/porelo/tmux.conf` and `TERM=tmux-256color`, read through tmux's own flags. Pi runs **without the alternate
  screen and asks for no mouse** (`alternate_on 0`, `mouse_any_flag 0`), as Claude Code does. **PageUp reaches Pi and
  it scrolls nothing of its own**: the screen before and after is the same. Pi shortens a long output itself
  ("... 281 more lines (ctrl+o to expand)"), so its own view, not tmux's history, is where a person reads back. No
  difference the settings do not cover, so nothing new under "Not closed". Not measured: Shift+PageUp, since
  `tmux send-keys` bypasses tmux's key bindings; `task-keys.feature` leaves it out for the same reason.
- **Left:** the scenario run on the VM with the others, and seen red against an image from before `e55e8f0d`; the
  joint test (step 4).
