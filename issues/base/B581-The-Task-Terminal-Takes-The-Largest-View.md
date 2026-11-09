# [B581](README.md#b581) — The Task Terminal Takes The Largest View

**Status:** Implementing. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** XS - one tmux setting in the task image and its test.

**What must be true.** A task's terminal grows with the view the person is looking at, instead of staying at the
size of a smaller client still attached.

## Plan

Read in the code: `Containerfile` writes `set -g window-size smallest` into the task's tmux configuration;
`ContainerfileTest` pins it.

- `window-size latest`, so the client that last acted sets the size; the test pins the new line.
- Red first: the test asserting `latest` against today's image text.
