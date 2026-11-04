# B364 — A Test Run With Its Own Temp Directory

**Status:** Idea.
**Size:** S, proposed - one surefire setting in the parent build, and the tests that list the temp directory read
it from there.

**What must be true.** Two suites on one machine never see each other's scratch files. Today every test JVM uses the
shared `java.io.tmpdir`. The removed
`ProjectAccessCommandTest.theScratchDirectoryIsGoneAfterSuccessAndAfterRefusal` listed
every `porelo-access-*` entry there before and after its run: when another worktree's suite ran `project access` at
the same time, a scratch directory of that other run appeared or went between the two listings and the test failed. It
was seen on 2026-10-31 with four worktrees' suites running at once, twice in one run and on a rerun of that class,
and passed with `-DargLine=-Djava.io.tmpdir=<a directory of its own>`. A failed suite run under such load is read as
a fault of the change it checks, which costs a rerun or a wrong hunt.

The build still assigns no private temp directory. The acceptance test named below is gone.

The build must give each test JVM a temp directory under its module's `target/` (surefire's `argLine` or
`systemPropertyVariables`), so a clean build removes what a test left; no test or command changes where it writes.

## Acceptance

- Two full suites run at once from two worktrees pass, the test above included.
- A scratch directory a command leaves behind is still seen by the test that looks for it.
- Seen to fail first: the test above, with a `porelo-access-*` directory made in the shared temp directory during
  its run.
