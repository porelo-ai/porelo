# [B150](README.md#b150) — The Whole Diff In Review

**Status:** Analyzed.
**Blocks:** [B166](README.md#b166).
**Size:** M - bounded daemon review replies and a growing varlink contract, with full-diff classification and explicit completeness.

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
   [B293](README.md#b293), [B283](README.md#b283) and [B141](README.md#b141). Preserve dangerous-first display ordering,
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

## To be checked

- Measure the safe serialization budget and metadata-only worst case; choose named refusal versus pagination for
  oversized metadata while retaining the additive contract and a complete inventory path.
