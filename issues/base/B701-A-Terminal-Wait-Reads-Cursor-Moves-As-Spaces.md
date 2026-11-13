# B701 — A Terminal Wait Reads Cursor Moves As Spaces

```json
{
  "$schema": "issue-1.json",
  "id": "B701",
  "title": "A Terminal Wait Reads Cursor Moves As Spaces",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 55,
  "blockedBy": [],
  "covers": "The acceptance kit's `Terminal.await` matches a phrase on a screen that draws the spaces between words as cursor-forward moves, as the kit's own dump of the terminal shows it.",
  "openQuestions": 0
}
```

**Why this size.** One normalized view of what was read, used by the wait's match, and tests with recorded escapes.

Filed 2026-11-13 for the Coordinator, from Agent Smith's run of an agent's attended scenarios: Claude Code 2.1.311
draws the spaces between words as cursor-forward moves (`ESC [ n C`), so a phrase wait such as "bypass permissions
on" never matches, although the screen and the kit's dump of it show the phrase.

**What must be true.** `Terminal.await` matches text against what was read with each cursor-forward move read as that
many spaces, as the dump shows it. The raw text is still what the wait returns and what a failure prints through the
terminal's redaction.

## Plan

- `Terminal`: match against a view of `seen` in which `ESC [ n C` (n absent meaning 1) becomes n spaces, built
  incrementally as chunks arrive, so a move split across two reads still counts once complete.
- The dump and the match share that one rule.
- Red first: a terminal fed "bypass\e[1Cpermissions\e[1Con" satisfies `await("bypass permissions on")`; a move split
  across two chunks too; a screen without the phrase still waits out its patience.

## Acceptance

- The three tests hold; a test fails while the wait matches the raw text only.
