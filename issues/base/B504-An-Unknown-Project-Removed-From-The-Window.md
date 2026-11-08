# [B504](README.md#b504) — An Unknown Project Removed From The Window

**Status:** Backlog.
**Size:** M - `RemoveProject` in the project contract part (`20-project`), `ProjectMethods`, and their tests.

**What must be true.** A project the machine lists but has no file for can be previewed and removed over the socket:
`RemoveProject` with `dryRun` answers `PREVIEWED` with what it removes, keeps and what of it is unreviewed, and the
refusals any other project gets, instead of `NoSuchProject`.

## Plan

- `RemoveProject` resolves a listed name without a file as the CLI's removal does, on the one implementation both
  render.
- Red first: a unit test with a listed project whose file is gone, which answers `NoSuchProject` today.

## Acceptance

- Preview and removal of such a project match the CLI's, and unreviewed work is named before anything goes.
