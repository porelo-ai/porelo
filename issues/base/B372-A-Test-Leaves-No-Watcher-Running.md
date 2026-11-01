# B372 — A Test Leaves No Watcher Running

**Status:** Review.
**Size:** S, proposed (M with the product change) - the tests that start a task with clearance on get a watcher that
is never a real process, or stop the one they start, with one test that fails while a watcher outlives its test; and
a watcher that ends with its container, with its test.

**What must be true.** A run of the suite leaves no process of its own behind. Today a test whose stand-in podman
answers a container pid (`4711`) with clearance on reaches `ClearanceWiring.startClearance`, which starts a real
`porelo shield watch --pid 4711` through `systemd-run --user --scope`. The scope puts it outside the test JVM, so
it outlives the run; nothing names it once the test's temp directory is gone, and it never exits.

On 2026-10-31 the host had 72 such watchers from one evening of the core repository's worktrees, about 110 MB each,
about 8 GB in all, when memory ran low for every agent.

- No test starts a real watcher unless it stops it, the same way `PoreloBinary.NAMED` already names a stand-in for
  other helpers.
- A test fails when a watcher of its own still runs after it, so a new test cannot bring the leak back.

- Outside the tests too, a watcher ends once the task's container is gone: today nothing but a signal from whoever
  holds its pid file (the poststop hook, `task stop`, `task remove`) ends it, so a watcher whose hook never ran or
  whose pid file was lost follows its events file for good. Placed in sprint 6m.

## Done

- Measured, class by class with every `shield watch` this worktree's JVMs started listed: of the candidates only
  `TaskRunCommandTest` starts a watcher, one in each of four tests whose stand-in podman answers the pid `4711`.
  `TaskLifecycleCommandsTest`, `RunningClearanceTest`, `ResumeVaultOfferTest` and `RegistryReadCommandTest` start
  none. Three of the four ended within about a second; the one in
  `aMarkerFromTheWindowsShellIsTheWorkBeingOver` outlived its test in about one run in three. The unit tests turn
  task scopes off, so the watcher was a plain child of the test JVM, reparented to the user manager when that JVM
  exited; with its temporary directory gone it followed an events file that never appears.
- The launcher `OwnPorelo` writes for a class's helpers now answers `shield watch` with a stand-in that makes the
  `--socket` the start waits for and exits; every other helper is still this build's own `porelo`. No product code
  changed.
- `WatcherLeakGuard`, registered for every test in `apps/app`, fails a test that leaves a watcher it started still
  running ten seconds after it ends, names it by pid and command line, and stops it. It was red on the unchanged
  launcher, naming that test, and is green with the stand-in; `WatcherLeakGuardTest` holds that it names and stops
  a watcher, lets one that exits in time pass, and leaves a watcher that ran before the test and any other process
  alone, each seen to fail with the guard broken.
- A watcher following its task's events now ends on its own once the container process it was given with `--pid`
  has ended, or a later process with another start time took that id; what the container wrote before is still
  handled. `ShieldWatchEndsWithItsContainerTest` follows a real short-lived process: the watcher runs while it runs
  and ends within a second after it; a reused id and an id no process has count as gone. Each check was seen to fail
  with it taken out of the loop or the start time not compared.
