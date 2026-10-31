# B372 — A Test Leaves No Watcher Running

**Status:** Idea.
**Size:** S, proposed - the tests that start a task with clearance on get a watcher that is never a real process, or
stop the one they start, with one test that fails while a watcher outlives its test.

**What must be true.** A run of the suite leaves no process of its own behind. Today a test whose stand-in podman
answers a container pid (`4711`) with clearance on reaches `ClearanceWiring.startClearance`, which starts a real
`porelo shield watch --pid 4711` through `systemd-run --user --scope`. The scope puts it outside the test JVM, so
it outlives the run; nothing names it once the test's temp directory is gone, and it never exits.

On 2026-10-31 the host had 72 such watchers from one evening of Core's worktrees, about 110 MB each, about 8 GB in
all, when memory ran low for every agent.

- No test starts a real watcher unless it stops it, the same way `PoreloBinary.NAMED` already names a stand-in for
  other helpers.
- A test fails when a watcher of its own still runs after it, so a new test cannot bring the leak back.

Open: whether a watcher should also end on its own once the pid it was given is no longer a process, which would
bound the same leak outside the tests too.
