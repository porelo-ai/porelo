# [B283](README.md#b283) — A Retargeted Symbolic Link Ranked Dangerous

**Status:** Idea.

**What must be true.** Creating, retargeting, replacing or removing a symbolic link is always ranked dangerous,
never generated or reformatting. The same mode evidence feeds C3 classification, with no link traversal or actionable
suggestion targeting a link.

## Proposed

- Blocked by: none.
- Blocks: [B323](README.md#b323), [B293](README.md#b293).
- `ReviewRanking.danger` recognizes only a new mode `120000` whose old mode differs. Treat a changed entry with
  `120000` on either side as dangerous before path, generated or whitespace classification. `GitGate.rankedReview`
  already supplies both modes from NUL-delimited raw changes and treats renames as removal/addition.
- Cover new and retargeted links, link-to-file and file-to-link changes, removal and rename, including targets that
  look like generated or whitespace-only text. Read the target blob only as data; never follow it.
- [B323](README.md#b323) consumes this evidence for C3 and emits the daemon-owned SARIF mode diagnostic. Its two-person
  approval and isolated execution requirements are enforced by [B145](README.md#b145) and [B95](README.md#b95).
  The profile forbids traversal and link-targeted fixes; a diagnostic does not authorize reading the target.

## To be checked

- Reproduce the misranking with `ReviewRankingTest` and a real two-commit `GitGateTest` fixture; verify both modes
  reach the predicate for deletion, type change and rename. Keep the full policy/projection work in its own issue.
