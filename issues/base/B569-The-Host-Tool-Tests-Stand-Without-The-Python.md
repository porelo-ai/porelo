# B569 — The Host Tool's Tests Stand Without The Python

```json
{
  "$schema": "issue-1.json",
  "id": "B569",
  "title": "The Host Tool's Tests Stand Without The Python",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 13,
  "blockedBy": ["B552"],
  "covers": "Once the host runs the `porelo-host` package, `host/`'s tests say what it does: no `// py:` citation, predecessor script or \"the Python\" in a test, and a test that only proved the port is removed."
}
```

**Why this size.** comments and assertion wording across about twenty test classes of `host/`, a few tests that only
restate the predecessor, and the module's README.

Filed 2026-11-09 as porelo's part of `porelo-project` ST291, by the operator's request of
2026-11-09. Implementing since 2026-11-10, on the coordinator's order of 13:56Z (ST291 is Highest; built
with [B552](README.md#b552) in Review, on main as it is). Built by an Antigravity sub-agent (Claude Opus 5.5)
under Core's supervision; in Review since 2026-11-10, until the operator's test.

**What must be true.** `host/`'s tests say what `porelo-host` does, not what the Python helpers did. No test, comment
or assertion message names a Python test (`// py: test_...`), the predecessor scripts (`porelo-host-person`,
`porelo-host-project`, `porelo-host-push`, `porelo-forge-gate`, the pool scripts) or "the Python". A test that only
proved the port matches the predecessor, and holds nothing `porelo-host`'s own contract needs, is removed. Every
behaviour the man page's "Codes by verb" and the module README promise keeps a test. The host's own tests in
`porelo-host` are where the shell around the package is held; this issue does not move tests there.

## Why

[B540](README.md#b540) to [B552](README.md#b552) ported the host's Python and shell helpers to Java and paired each
Python test with a Java one, so a reader could check that nothing was lost: 345 `// py:` citations in 20 test classes,
and assertion messages such as "the Python sysexits code is preserved". That pairing was the proof of a port. Once
the package has replaced the scripts on every host, the predecessor is gone from the collection, and its names in
porelo's tests point at code nobody can run or read beside them. Where the port kept the Python's choices on purpose,
the reason belongs in the test in its own words: the exit codes the host's shell maps, the 120-second shared
measurement budget, the per-connection peer lookup.

## Plan

- In `host/src/test/java`, replace each `// py: test_...` comment by nothing, or by one line of the behaviour's own
  reason where the test name does not already say it.
- Reword assertion messages that cite "the Python", "the predecessor" or a script name into what is asserted.
- Remove the tests that compared only output shape with the predecessor and assert nothing the man page or README
  states; list each removed test and why in the As built.
- Keep, and name in the As built, the deliberate departures from the predecessor ([B546](README.md#b546)'s refusal of a
  missing blob, [B552](README.md#b552)'s stripping of every `Connection` header, [B550](README.md#b550)'s purge that
  never records a running account as locked), since they are now plain behaviour.
- `host/README.md` stops describing the module as a port.

**The first test that goes red:** a check in `host`'s tests (beside `NullMarkedPackagesTest`'s kind) that fails while
any file under `host/src/test/java` contains `// py:` or the name of a predecessor script.

## As built

- **The check, red first:** `HostTestsNameNoPredecessorTest` reads the Java tokens of every file under
  `host/src/test/java` and names file and line for a `// py:` comment, the language's name (as a word or inside a
  name, not the tool `python3` or `ProjectRecords.pythonJson`), "predecessor", and a script name in a comment, a name
  or an assertion message. It was red with 459 lines (the 422 citations and 37 other mentions), and is green now. Its
  patterns are built from pieces; a sample file pins exactly which lines it reports.
- **Citations:** 424 lines deleted in 25 classes, 8 lines of the behaviour's own reason in their place: the codes by
  verb the host's shell maps (`HostProjectTest`, `HostTaskReviewTest`), the 120-second budget one pass or one
  measurement shares (`HostFollowBudgetTest`, `HostFollowReviewTest`), the fetch's 300 seconds against 60 for every other
  isolated operation (`HostFollowTest`), the Matrix account kept for readmission (`HostFollowPeopleTest`), why a purge
  never records a running account as locked. The per-connection peer lookup's test name says it already.
- **Words:** 31 assertion messages and one Javadoc say what is asserted; 11 test methods that named the predecessor are
  renamed. No assertion is weakened.
- **No test removed:** each shape check found is a promise of the man page (`org status --json`'s field order, `task
  show`'s whole certificate, `pool-key`'s compact JSON) or guards data another program reads.
- **Departures kept as plain behaviour:** `HostProjectAdvanceTest.aDeletedProjectFileCannotAdvanceMain`,
  `ForgeGateSocketTest.aHeaderNamedByTheSecondConnectionLineIsNotForwarded`,
  `ForgeGatePolicyTest.authorizationNamedInAnyConnectionHeaderIsRefused`,
  `HostFollowReviewTest.failedSafetyRestorationNeverClaimsTheRunningAccountIsLocked`.
- **README:** two sentences that read as a port now say what the module does.
- **Left, apart from this issue:** `porelo-host` itself still prints `porelo-host-person: `, `porelo-host-project: `,
  `porelo-host-push: ` and `porelo-forge-gate: ` as its diagnostics' prefix, and keeps the old names in paths and units
  (`/run/porelo-host-project-copy`, `/var/lib/porelo-pool/`, `porelo-pool-give-back`) and in the person file's
  `# Written by` header; two production comments cite the scripts. The tests assert that output in string literals,
  which the check leaves alone. Renaming them is a change of the host's contract and its collection's.
