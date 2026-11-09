# [B563](README.md#b563) — The Board Told Of Every Change

**Status:** Review, since 2026-11-09. Filed 2026-11-09 as porelo's part of `porelo-project` ST294.
**Size:** S - one stream more, fed by the adapter's stream and by porelod's own writes; no new reading.
**Blocked by:** [B562](README.md#b562).

**What must be true.** `WatchStories(project)` answers the whole board first, the stories with their computed states
and the revision, then again on every change: one the adapter reports from elsewhere, one porelod wrote itself (a
move, a work started, a done state), and a work that started or ended. It never starts work. Called without `more` it
answers once, as `Watch` does. The window can ask the adapter to look at once.

## Plan

Read in `ProjectMethods`' `WatchProjects` (its `replies.streaming()` and the check that the client is still there)
and `TaskMethods`' `Watch`.

- `apps/app-planning`: `PlanningChanges`, one subscriber per project on the adapter's `WatchChanges`, opened with
  the first watcher and closed with the last; porelod's own writes and work records signal it too. An adapter without
  the `watch` capability is read again every few minutes.
- `60-planning.varlink`: `WatchStories(project)`, the same shape as `ListStories`, and `RefreshStories(project)`,
  which asks the adapter's `Refresh` and returns its revision.
- `PlanningMethods`: both, each stream ending when its client is gone.
- Red first: a test client on `WatchStories` with `StubPlanning` receives a second reply after the stub changes an
  issue's state from outside.

## Acceptance

- A change from outside the machine, a move and a started work each reach a watching client within one reply; a
  closed client's stream ends; the adapter's stream is closed with the last watcher.

## As built

`WatchStories` sends the same enriched board and computed story states as `ListStories`, then whole-board
updates from the adapter's `WatchChanges` or `PlanningChanges.signal(project)`. Without `more` it answers
once. `RefreshStories` calls the adapter's `Refresh` and returns its revision, using the reads' named
refusal mapping. MCP keeps the watch in READING and refresh in WRITING_ONLY_FROM_THE_LIST.

One subscription per project is shared across the daemon and its task MCP sockets. The first watcher
opens it; the last closes the adapter and cancels its timers. The adapter's initial revision is compared
with the board read, including a change during subscription. Repeated revisions do not repeat the board.
Each client keeps its initial board and a bounded newest update, so a slow client cannot block another.
A refused board read ends affected streams with the named error. Daemon shutdown also ends them by name.

The fallback period is the named three-minute `POLL_INTERVAL`; it reads without fetching. An ended or
failed adapter stream reopens after a one-second backoff, doubling to three minutes and resetting when
it receives a revision. `signal` rereads even at the same adapter revision and obtains a fresh immutable
local work snapshot. Its producers belong to [B564](README.md#b564) and [B565](README.md#b565): only tests
call it here; work starts and ends use that same entry point when their lifecycle is built.

The test plan was set before implementation, by level:

| Level | Coverage and harm guarded | Cases | Measured JUnit time | Result |
|---|---|---:|---:|---|
| Unit, bulk | `PlanningChangesTest` (before the reading's fixes): fake pushed adapter stream and manual scheduler; shared ownership, fanout, duplicate revisions, local signals, exact poll boundary, reconnect/backoff, initial revision race, disconnected and slow watchers, named read refusals and shutdown; no sockets, processes or sleeps | 20 | 93 ms | Passed |
| Narrow real pieces | `PlanningStreamsTest`: fake replies for first/later board, disconnect, no-more and refresh success/refusals; four `PlanningReadsTest` refresh cases hold the real adapter-error mapping | 12 | 119 ms + 14 ms | Passed |
| Integration | `PlanningMethodsTest.anOutsideChangeReachesTheRealBoardStreamWithinTheDeadline`: real stub process and daemon method, a separate adapter client changes an issue; bounded reply queues, no sleep | 1 | 284 ms | green outside a sandbox |

The integration test was written first; its initial red attempt also stopped at socket setup, so it is
not claimed as a behavioral red. Three unit mutations compiled and failed their assertions, then the
saved source was restored byte for byte and the tests passed: limiting fanout to one watcher failed
`aChangeReachesEveryWatcherExactlyOnce`; removing the signal reread failed
`aLocalWorkSignalReachesWatchersWithoutAnAdapterRevisionChange`; delaying polling by one millisecond
failed `anAdapterWithoutWatchIsReadOnThePeriodAndNotBefore`. The diff stat matched before and after restoration.

Clean compilation ran with the pinned JDK and NullAway, offline, using this worktree's install prefix.
Whole runs outside a sandbox (`-Pwhole`, no test selector): `wire` 126 tests (2 skipped), `apps/app` 3686 and
`daemon` 348, all green, the integration test included.

No VM or native build ran; none is needed for this issue. The code
was checked against `graal` 2026.910.3 and `java-code-review`, `test-quality`, `security-audit`,
`concurrency-review`, `clean-code`, `solid-principles` 2026.906.3.

**After the code reading (another model), fixed by Codex CLI on Agent Core's brief and reviewed by Agent Core:**
- A task's board reads, `WatchStories` among them, are held to the task's own project by the task socket (fixed below
  this issue, for every method that takes a project).
- A client that has ended its half of the connection (`porelo daemon connect`) gets the board as it is now, once,
  where it got nothing.
- A named refusal from the adapter's stream or from a reopen reaches every watcher and ends that project's watch;
  only a stream that merely ended is retried. A reopen reads the board again before streaming.
- No adapter call (start, read, refresh, stream, close) runs under the one daemon-wide monitor: one project's slow
  refresh no longer holds another project's watch, a client's disconnect or a signal, and shutdown closes every
  adapter at once. Stale callbacks and reads are told apart by generation.
- A refresh whose revision differs from the watched board reads it again for every watcher, and a client joining
  while the stream reconnects reads the board itself.
- An unexpected failure of a read ends the watch with `Failed` and releases the adapter, instead of leaving watchers
  waiting and the adapter running.
- The last watcher leaving no longer closes the adapter under another client's running read or refresh; the read's
  end closes it (found by Agent Core while reviewing the fix).
- No `PlanningChanges` is made without an owner who closes it.
- Each fix has a test seen red first, against the old code or a compiling mutation, then green; the disconnect test
  (`anOutsideChangeReachesTheRealBoardStreamWithinTheDeadline`) was seen red with the disconnect ignored. Two tests
  the fix brought were left out: one guarded a sentence of `doc/running.md`, one a method's parameter list.
