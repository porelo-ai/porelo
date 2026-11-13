# B720 — A Repository With No Checkout Proposed From Its Mirror

```json
{
  "$schema": "issue-1.json",
  "id": "B720",
  "title": "A Repository With No Checkout Proposed From Its Mirror",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [
    "B220"
  ],
  "covers": "`MirrorTree.export` exports the gate mirror's default branch read-only into a fresh 0700 runtime directory, removed after, so the plugins can read a repository added by its address; `porelo/*` is never read.",
  "openQuestions": 0
}
```

**Why this size.** One export from the bare mirror and its removal, with tests.

Split from PJ52 on 2026-11-13. [B221](README.md#b221) left out step 2: a repository in `default` added by its address has only the gate's bare mirror. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `MirrorTree.export(repository)` into a fresh 0700 directory under the runtime directory, files read-only.
- Only the default branch; `porelo/*` refs never read.
- The directory removed after the proposal, also on failure.

## Acceptance

- Red first: an export of a test mirror holds the default branch's tree, read-only, 0700, and is gone after.
- A `porelo/*` ref's content never appears in the export.

## Built

- `MirrorTree.export` reads the gate mirror of a repository of `default` with git plumbing alone: the branch HEAD
  names (never one under `porelo/`), its commit's tree listed and each blob written by Java into a fresh 0700
  directory under `$XDG_RUNTIME_DIR/porelo/mirror-trees/`, files read-only. No other ref is read; symlinks,
  submodules and any path with an empty, `.`, `..` or `.git` part are left out, and no attribute, filter or
  line-ending conversion applies. The mirror is named by `--git-dir`, with no hooks, no global or system config and
  no inherited `GIT_*` variable.
- The export is removed when the proposal is done, also when it fails part way.
- `DefaultProposal` proposes a repository added by address from its export; one whose mirror has no default branch
  yet is still refused as `no-checkout`, whose wording in the interface and `doc/commands.md` says so.
- Taking the tools of a repository added by address reads the export once and writes the draft under the
  configuration directory, as for a checkout.
- Red first in `MirrorTreeTest` (the default branch only, read-only, 0700, gone after, also when the reader throws;
  nothing of `porelo/*`) and `ProposalMethodsTest` (a repository added by address proposed from its mirror, and its tools taken into the configuration directory).
- The export lives in the runtime directory, usually memory; no size bound is set.
