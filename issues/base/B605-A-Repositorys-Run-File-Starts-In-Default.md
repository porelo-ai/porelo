# [B605](README.md#b605) — A Repository's Run File Starts In Default

**Status:** Idea. Filed 2026-11-10, found by Agent Ecosystems while testing [B449](README.md#b449).
**Size:** S - the merge of a checkout's file with the default project's, and its tests.

**What must be true.** In the project `default`, a repository whose `porelo.yml` or `.porelo.yml` names its image lines
in `image.run_file` starts, built with those lines, as one naming them in `image.run` does.

## Found

- The merge checks the checkout's file beside the default project's file, and there `run` and `run_file` of different
  layers count as mutually exclusive (`ProjectFileCheck`, "'….run' and '….run_file' are mutually exclusive"), so the
  launch refuses with a `ProjectException` and nothing is built.
- `StartCheck` (CanStart) answers READY for the same repository ("Start's to report"), so a daemon start is promised
  and then refused.

## What Is Asked

- `run` and `run_file` are exclusive within one file only; across layers, each layer's lines follow the earlier ones,
  as `ImageChoice.merge` already joins them.
- CanStart and the launch give the same answer for such a repository.
- A test for each, red before the change.
