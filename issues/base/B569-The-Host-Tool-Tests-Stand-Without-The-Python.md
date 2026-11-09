# [B569](README.md#b569) — The Host Tool's Tests Stand Without The Python

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST291, by the operator's request of
2026-11-09. Built once `porelo-project` HO156 has replaced the host's Python tool with the `porelo-host` package and
ST291 is accepted.
**Size:** M - comments and assertion wording across about twenty test classes of `host/`, a few tests that only
restate the predecessor, and the module's README.
**Blocked by:** [B552](README.md#b552).

**What must be true.** `host/`'s tests say what `porelo-host` does, not what the Python helpers did. No test, comment
or assertion message names a Python test (`// py: test_...`), the predecessor scripts (`porelo-host-person`,
`porelo-host-project`, `porelo-host-push`, `porelo-forge-gate`, the pool scripts) or "the Python". A test that only
proved the port matches the predecessor, and holds nothing `porelo-host`'s own contract needs, is removed. Every
behaviour the man page's "Codes by verb" and the module README promise keeps a test. The host's own tests in
`porelo-host` (HO156) are where the shell around the package is held; this issue does not move tests there.

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
