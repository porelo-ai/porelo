# B464 — A Review Counts Lines Without Their Endings

**Status:** Review.
**Size:** M - a daemon contract change, gate computation, and a wire regression test.

**What must be true.** A file whose line endings changed shows how many lines really changed, so a CRLF to LF file with three real lines
reads as three, not as +1042 -1043. Raw counts remain available beside the counts ignoring line endings;
real content changes remain visible in the filtered patch.

## What Is Asked

- `ReviewFile` in `org.fuin.porelo.Tasks1` gets two optional fields, `addedIgnoringLineEndings: ?int` and
  `removedIgnoringLineEndings: ?int`: git's `--numstat --ignore-space-at-eol` over the same range as `added` and `removed`, and `-1` for a
  binary file, as `added` and `removed` have. An older daemon leaves them absent.
- `Review` gets an optional input `ignoreLineEndings: ?bool`; when true, the diff it gives is git's with `--ignore-space-at-eol`.
- The window says "mostly line endings" when the two counts differ and offers the switch; the desktop repository implements that presentation.

## Plan

- Extend `ReviewRanking.File` and its input with both counts, calculated in `GitGate.rankedReview`
  using `--numstat -z --ignore-space-at-eol` over the existing range with `--no-renames`.
- Keep raw counts, ranking, and the default patch unchanged. An overload selects the filtered patch;
  the existing caller keeps the default behavior. `GateMethods` forwards the optional flag and both counts.
- Add the optional fields and input to `35-gate.varlink` with the exact names and types above.
- Reproduce incorrect counts and the ineffective switch with real Git in `GitGateTest`, then fix them.
  Keep the socket-level regression in `PoreloDaemonTest` for a normal host.
- Update existing `ReviewTextTest` and `ReviewRankingTest` fixtures for the expanded record.

## Decisions

- Review computation lives in `gate`; serialization and the contract live in `daemon`. No app production change is needed.
- Follow Git's specified `--ignore-space-at-eol`, including trailing spaces, rather than introducing a
  narrower CRLF-only interpretation. Pure line-ending changes remain in the file list with zero filtered counts.
- Preserve ranking and rename handling. This change adds measurements and an optional view only.

## Acceptance

- The wire test checks raw and filtered counts for mixed edits, pure CRLF conversion, ordinary edits,
  and binary files; omitted and false keep the default patch, true removes only end-of-line noise.
- Run unit tests with reactor dependencies, formatting, and citation checks with the pinned JDK.
- The desktop repository reads the optional counts, tolerates their absence from older daemons,
  labels differing counts "mostly line endings", and sends `ignoreLineEndings: true` for the switch.
- VM coverage belongs in `acceptance/suite`'s `gate/gate-review.feature`, scenario
  "a push waits for review, names what is dangerous first, opens where nothing in it runs, and its mirror is backed up";
  extend its review step with CRLF conversion and daemon requests for both switch states. Not run here.

## Verification

- The real-Git regression was seen failing on mixed counts (3/3 instead of 1/1), pure line-ending
  counts (1/1 instead of 0/0), and a filtered patch still showing unchanged lines.
- Read-only review found four app test fixtures still using the old constructor; reactor compilation
  reproduced that failure, and those fixtures now supply the new counts. No other findings.
- The daemon socket regression cannot reach its assertions in this sandbox: ancestor directories
  are mapped to uid 65534 and the production socket ownership check refuses them. An owned temporary
  directory does not change the ancestor ownership. The check remains intact.
- A clean focused reactor run passes all 84 tests in `GitGateTest`, `ReviewRankingTest`, and
  `ReviewTextTest` and compiles the daemon. No focused tests are skipped.
- The full clean unit reactor stops in `porelo-wire` with 4 failures and 25 errors in the socket
  ownership checks, before dependent modules can run. Its 3 skipped tests are
  `VarlinkServerSocketTest.anotherNumericOwnerIsRefusedBeforePermissionsChangeOrBind`,
  `SocketContextTest.leavesNothingSetBehind`, and
  `SocketContextTest.keepsTheProcessSelinuxUserAndReplacesOnlyTheType`.
