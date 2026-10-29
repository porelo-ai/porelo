# [B141](README.md#b141) — New Dependencies Checked Before Review

**Status:** Analyzed.
**Blocked by:** [B136](README.md#b136), [B291](README.md#b291), [B292](README.md#b292).
**Blocks:** [B323](README.md#b323), [B293](README.md#b293), [B163](README.md#b163).
**Size:** XL - two manifest formats, registry metadata and commit-bound approval holds; splits into M, L and S parts below.

**What must be true.** Added or changed dependencies appear in the review with existence, age and look-alike-name
findings as SARIF evidence. A finding holds approval until a person confirms that dependency for the exact reviewed
commit, and dependency changes classify the work at least C2.

## Plan

`ReviewRanking` ranks build/package manifests and lockfiles dangerous; `GitGate.rankedReview` reads file changes but
extracts no dependency coordinates. `GateMethods` and `35-gate.varlink` have no dependency findings or confirmation
parameter. `pom.xml` and `package.json` are the initial manifest scope; more formats follow separately.

Split into three separately buildable issues, in this order; assign their own numbers before implementation:

1. **Names and changes in review (M).** Add `gate/.../NewDependencies` to compare pinned old/new manifest blobs without
   executing wrappers, plugins, scripts or dependency resolution. Extract XML group/artifact/version coordinates and
   JSON dependency/dev/optional/peer sections, including changed versions. Red first: a real two-commit fixture adds
   one name and changes another version; both appear, an unchanged dependency does not. Emit a daemon-generated SARIF
   run through [B291](README.md#b291)/[B292](README.md#b292), pinned to the head/base and manifest lines. Do not add a
   second `NewDependency` review wire format. Lockfile/manifest changes still raise risk even when their transitive
   names are outside this initial extraction scope.
2. **Registry check and approval hold (L).** A host metadata reader asks only the project's declared registries within
   `Egress`; it does not run the submitted build. Distinguish exists, first publication, not found, unavailable and
   unchecked offline, with explicit completeness and cached evidence scoped by registry/name/version. Emit
   `porelo.dependency.not-found`, `.unreached`, `.unchecked-offline`, `.young` or `.slopsquatting` findings as applicable,
   with `kind: "fail"` and explicit warning/error level. Red first: a loopback registry says not found or times out;
   the review names that outcome and cannot appear complete and clean.

   `GateApproveCommand` asks for each flagged dependency, default no; without a terminal or with `--no-input`, refuse
   exit 70 unless `--confirm-dependency <ecosystem>:<name>` covers each. `Approve` gains additive `confirmed` and
   `DependencyNotConfirmed` in `35-gate.varlink`. Central gate checking covers both ordinary and signed approval so a
   second call path cannot skip the hold. Bind confirmation to principal, exact commit and finding and journal it.
   Only the daemon may project that authenticated act as an external accepted suppression with bounded justification;
   task/import suppressions are refused. Confirmation satisfies only that dependency hold, never approval or clean
   review state. Red first: approving a not-found dependency without confirmation forwards no ref.
3. **Look-alike names (S).** Ship a bounded known-name dataset for these two ecosystems and compare new names for one
   or two edits or swapped separators. Red first: a deliberately near name is flagged against the fixed fixture.
   Read these data as trusted shipped policy, never from the waiting change.

Feed dependency additions, changes and incomplete extraction into [B323](README.md#b323)'s deterministic risk rules,
not a model score or the reported SARIF severity. Compute over the whole pinned change before any display cut.
Update `doc/how-it-works.md` and `doc/reach.md`; CLI and daemon use the same findings and holds.

## Acceptance

- Seen red first: a finding, unavailable registry or offline check bypasses confirmation; task-supplied suppression
  clears a hold; a confirmation for one commit applies after a push. Prevent each on ordinary and signed approval.
- A new dependency without a finding is visible and does not add a dependency hold. Partial/failed evidence is shown;
  a person's exception is recorded without relabeling the scan clean.
- Changed versions are checked; all dependency/lockfile changes reach the C2 classifier even where detailed extraction
  is unsupported. No manifest content executes on the host and no undeclared registry is contacted.

## Open questions

- What minimum publication age should produce a finding, and may a project only raise that threshold?

## To be checked

- Choose and verify the shipped known-name dataset and refresh process; measure comparison bounds and cache expiry.
- Verify manifest line anchoring for inherited/indirect version declarations without executing a project build;
  unknown coordinates remain explicit rather than guessed.
