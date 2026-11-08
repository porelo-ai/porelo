# [B504](README.md#b504) — An Unknown Project Removed From The Window

**Status:** Review.
**Size:** XS - two tests; the code already does it.

**What must be true.** A project the machine lists but has no file for can be previewed and removed over the socket:
`RemoveProject` with `dryRun` answers `PREVIEWED` with what it removes, keeps and what of it is unreviewed, and the
refusals any other project gets, instead of `NoSuchProject`.

## Plan

- Read in the code on 2026-11-08: `RemoveProject` (`ProjectMethods`) and `projects delete` both go through
  `ProjectDeletion`, which finds a project in `ProjectInventory`, the list `Projects` answers. A project listed from
  its mirror and registry entry with its file gone is therefore previewed as `PREVIEWED` today. The `NoSuchProject`
  the window met was the `Unfollow` of an earlier version.
- Two tests hold it: `ProjectDeletionTest.aProjectListedWhoseFileIsGoneIsPreviewedAsAnyOther` and
  `PoreloDaemonTest.aListedProjectWhoseFileIsGoneIsPreviewedOverTheSocket`. The daemon test was seen red with the
  lookup made to skip a project whose file is gone.

## Acceptance

- Preview and removal of such a project match the CLI's, and unreviewed work is named before anything goes.
