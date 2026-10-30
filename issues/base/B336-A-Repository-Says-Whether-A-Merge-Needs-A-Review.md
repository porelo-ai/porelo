# B336 — A Repository Says Whether A Merge Needs A Review

**Status:** Accepted.
**Size:** S - a guess before analysis: one key in the project file's schema and reader, its documentation, and the
value handed on where the host reads it.

**What must be true.** The project file says for each repository whether a pull request on the host's forge needs
another person's approval before it is merged: `repositories.<name>.review` is `required`, the default when the key
is left out, or `optional`, where the author merges their own. Any other value is refused when the file is read,
naming the key. Porelo only reads and checks the value; the host's forge enforces it.

## Acceptance

- `required`, `optional` and a missing key are read; a missing key reads as `required`.
- Any other value is refused, naming `repositories.<name>.review`.
- The value reaches the host from the verified project, never from an unverified copy: `porelo project verify --json`
  names each repository's `review`.
- Being in the project file, a change to it needs a signature by a key in `signers`.
- Seen to fail first: today `review` is an unknown key, read with a warning and handed on nowhere.

## Open questions

- Does the project's own repository, which has no entry under `repositories`, take the setting too, and where:
  `project.review`, or always `required`?

## To be checked

- How porelo-host reads the value, `verify --json` or another answer, agreed with its agent before the field is
  added.
