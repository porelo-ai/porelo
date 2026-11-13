# B150 — The Whole Diff In Review

```json
{
  "$schema": "issue-1.json",
  "id": "B150",
  "title": "The Whole Diff In Review",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "Bound serialized review replies with explicit file-boundary cuts; retain complete file inventory or refuse by name; full-diff risk and SARIF completeness stay independent.",
  "openQuestions": 0
}
```

**Why this size.** bounded daemon review replies and a growing varlink contract, with full-diff classification and explicit completeness.

**Blocks.** [B166](README.md#b166).

**What must be true.** A review shows the whole diff or says where it was cut, and every changed file remains
available. Presentation limits never hide risk or make incomplete review evidence look complete.

## Plan

`GitGate.rankedReview` returns a full ranked patch and file list; `GateReviewCommand` prints the whole patch.
`GateMethods.Review` puts the patch, log, files and instruction into one reply. `VarlinkConnection.MAX_MESSAGE` is
4 MiB and rejects larger received messages. There is no `Review.cut`; `PendingBundle` separately refuses oversized
bundles by name.

1. Red first: a daemon test creates a large multi-file push and asserts that its serialized reply fits the message
   bound, explicitly identifies omitted patch files and still exposes every changed file.
2. Measure UTF-8 JSON size including escaping, log, instruction, files and framing. Cut the ranked patch only at file
   boundaries within the reply budget. Add optional `Review.cut` to `35-gate.varlink`, with counts, omitted paths and
   a usable CLI command for the complete patch. Do not partially print a file without saying so.
3. Risk and dependency extraction operate on complete pinned head/base objects before projection, as required by
   [B323](README.md#b323), [B283](README.md#b283) and [B141](README.md#b141). Preserve dangerous-first display ordering,
   but do not equate `ReviewRanking.Rank` with C0-C3: build and security files share `DANGEROUS` while having different
   risk classes. A cut cannot reduce class or approval requirements.
4. Keep `Review.files` complete for replies that fit. When metadata alone exceeds the limit, return a named size
   refusal or an explicit paged inventory path; never claim that an omitted inventory is complete. A single-file
   patch above the budget has an explicit cut with zero displayed patch files and the complete-view command.
5. SARIF consumers added by [B166](README.md#b166) and later checks use their own bounded result pages, distinct from
   patch completeness. If a displayed projection is partial it says so; a complete page is not evidence that a check
   or review is complete. Never embed unbounded reports beside `diff` to evade either limit.
6. `doc/how-it-works.md` and `doc/commands.md` describe the cut and full-view path. Share the boundary helper where
   useful without changing the running-task diff issue's scope.

## Acceptance

- Seen red first: escaping-heavy, one-large-file and many-file patches overflow a varlink reply or silently lose
  content. Afterwards each fits with an explicit cut or gives a named refusal, never a false complete response.
- Every changed file is listed or reached through an explicit inventory page path; classifications use the full
  change. Dependency, link and security findings beyond the displayed patch still hold the same controls.
- Truncated SARIF pages show completeness independently of patch cuts, and omitted results never clear findings.

## Built

- **The cut.** `ReviewCut` in the daemon measures the reply as the server frames it: UTF-8 JSON with every escape, the
  log, commits, instruction, files and the `{"parameters":…}` frame, against `VarlinkConnection.MAX_MESSAGE` (4 MiB,
  now public). A reply that fits goes unchanged. Otherwise the patch is cut as a prefix in its dangerous-first order,
  at a file boundary from `PatchFiles` in `gate`, which splits a patch at its `diff --git` lines and reads quoted
  paths. No file is shown in part. `Review.cut` (`ReviewCut` in `35-gate.varlink`) gives `shownFiles`,
  `omittedFiles`, every omitted path and `command`, the `porelo gate review` line that prints the whole review on the
  machine, every name resolved. `gate review` gained `--ignore-line-endings`, so that command shows the same patch.
- **The inventory.** `files`, their ranks and the log are never shortened. When they alone do not fit, the answer is
  the named refusal `ReviewTooLarge(name, bytes, limit, command)`, chosen over pagination: it keeps the contract
  additive, and the command is the complete inventory path. A single file over the budget gives a cut with
  `shownFiles: 0`.
- **Ranking on the whole change.** The cut is made after ranking, so a dangerous file beyond it keeps its rank and
  reason. `GitGate.rankedReview` resolves the incoming head and the base to object ids once, so counts, ranking and
  patch come from the same commits. Still unpinned: `log`, `made` (commits), `blobs` and the arrival verdict read the
  moving incoming ref. The mapping of a rank to the risk classes C0-C3 is not built; it is
  [B323](README.md#b323)'s.
- **Point 5 moved to [B166](README.md#b166).** No SARIF consumer or result page exists yet: [B166](README.md#b166) is
  in Backlog and [B291](README.md#b291)'s profile only accepted, so there was nothing to keep apart from the patch
  cut. [B166](README.md#b166)'s Plan now carries it.
- `doc/how-it-works.md` and `doc/commands.md` describe the cut, the full view and the refusal.
- Tests: `GateReviewCutTest` (daemon, real git): a many-file push, an escaping-heavy one whose raw patch fits, one
  dangerous lockfile over the limit, and commits whose messages alone overflow. Each failed before the change with
  "A varlink message exceeded 4194304 bytes", the refusal case with the generic error
  (`buildbox-porelo-2efad88bcfa7-20261113T181216Z.log`). `GateReviewCommandTest` failed on the unknown
  `--ignore-line-endings` (`buildbox-porelo-d64db5df6a2f-20261113T181035Z.log`). After it, these and `ReviewCutTest`,
  `PatchFilesTest`, `GitGateTest`, `ReviewRankingTest`, `VarlinkDeadlineTest`, `ReviewOutputTest`, `ReviewTextTest`,
  `CommandsDocumentedTest`, `PoreloDaemonTest`, `InterfaceDescriptionTest` and `McpMethodsTest` pass, 309 tests,
  0 skipped (`buildbox-porelo-dbc0e751dcaa-20261113T181429Z.log`).
