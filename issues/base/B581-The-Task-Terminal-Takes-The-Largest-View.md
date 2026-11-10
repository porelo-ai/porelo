# B581 — The Task Terminal Takes The Largest View

```json
{
  "$schema": "issue-1.json",
  "id": "B581",
  "title": "The Task Terminal Takes The Largest View",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 11,
  "blockedBy": [],
  "covers": "The task terminal sizes to the latest client (`window-size latest`)."
}
```

**Why this size.** one tmux setting in the task image and its test.

Filed 2026-11-09 under ST290, from the fourth move.

**What must be true.** A task's terminal grows with the view the person is looking at, instead of staying at the
size of a smaller client still attached.

## Plan

Read in the code: `Containerfile` writes `set -g window-size smallest` into the task's tmux configuration;
`ContainerfileTest` pins it.

- `window-size latest`, so the client that last acted sets the size; the test pins the new line.
- Red first: the test asserting `latest` against today's image text.

## As built

- `Containerfile` writes `set -g window-size latest` (was `smallest`) into the image's `tmux.conf`, still before
  `source-file -q` of the account's file, so a person's own tmux file still overrides it; the comment above says why.
- Red first: `theTerminalTakesTheSizeOfTheViewLastUsedUnlessAPersonChoosesOtherwise` failed on its assertion that the
  image holds `set -g window-size latest`; the golden `Containerfile.uc` and its recipe label changed with the text.
- The cost the old comment named stays: with two terminals of different sizes attached, each hand-over of the size
  makes the agent redraw, and an agent drawing on the main screen leaves a copy of its screen in the history each time.
- Measured with tmux 3.6 on ubuntu26.04 (two fixed-size terminals, one session): the terminal that attaches, is
  resized or is typed in takes the size; when it detaches, the size goes back to the one left. With `smallest` the
  larger terminal never got more than the smaller one had.
