package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/**
 * Prevents stale boards, lost invalidations, hung clients and leaked adapter streams without
 * adapter I/O.
 */
class PlanningChangesTest {
    private final Source source = new Source();
    private final Clock clock = new Clock();
    private final Map<String, Source> sources = new java.util.HashMap<>(Map.of("shop", source));
    private final PlanningChanges changes =
            new PlanningChanges(
                    project -> sources.computeIfAbsent(project, name -> new Source()), clock);

    @org.junit.jupiter.api.AfterEach
    void closeTheOwner() {
        changes.close();
    }

    private static class Source implements PlanningChanges.Source {
        int opens;
        int closes;
        int reads;
        int refreshes;
        boolean watch = true;
        String revision = "zero";
        String local = "idle";
        RuntimeException refusal;
        RuntimeException openFailure;
        Consumer<String> changed;
        Runnable ended;
        Runnable onOpen = () -> {};

        public Map<String, Object> board() {
            reads++;
            if (refusal != null) throw refusal;
            return Map.of("stories", List.of(local), "revision", revision);
        }

        public boolean hasWatch() {
            return watch;
        }

        public void watch(
                Consumer<String> changed, Consumer<RuntimeException> failed, Runnable ended) {
            opens++;
            if (openFailure != null) throw openFailure;
            this.changed = changed;
            this.ended = ended;
            onOpen.run();
        }

        public String refresh() {
            refreshes++;
            return revision;
        }

        public void close() {
            closes++;
        }

        void push(String revision) {
            this.revision = revision;
            changed.accept(revision);
        }
    }

    private static final class Clock implements PlanningChanges.Scheduler {
        int closes;
        private long now;
        private final List<Alarm> alarms = new ArrayList<>();

        private static final class Alarm {
            final long at;
            final Runnable action;
            boolean canceled;

            Alarm(long at, Runnable action) {
                this.at = at;
                this.action = action;
            }
        }

        public PlanningChanges.Cancellation schedule(Duration delay, Runnable action) {
            var alarm = new Alarm(now + delay.toMillis(), action);
            alarms.add(alarm);
            return () -> alarm.canceled = true;
        }

        public void close() {
            closes++;
        }

        void advance(Duration duration) {
            long until = now + duration.toMillis();
            while (true) {
                var next =
                        alarms.stream()
                                .filter(alarm -> !alarm.canceled && alarm.at <= until)
                                .min(java.util.Comparator.comparingLong(alarm -> alarm.at))
                                .orElse(null);
                if (next == null) break;
                alarms.remove(next);
                now = next.at;
                next.action.run();
            }
            now = until;
        }
    }

    @Test
    void theFirstWatcherOpensOneStreamAndTheLastClosesIt() throws Exception {
        // Duplicate subscriptions leak adapter processes and remote connections.
        try (var first = changes.watch("shop");
                var second = changes.watch("shop")) {
            assertThat(first.next(0))
                    .as("the first watcher receives its board")
                    .containsEntry("revision", "zero");
            assertThat(second.next(0))
                    .as("each new watcher receives its board")
                    .containsEntry("revision", "zero");
            assertThat(source.opens).as("one upstream subscription per project").isEqualTo(1);
            first.close();
            assertThat(source.closes).as("the second watcher still needs the source").isZero();
        }
        assertThat(source.closes).as("the last watcher releases the source").isEqualTo(1);
    }

    @Test
    void aChangeReachesEveryWatcherExactlyOnce() throws Exception {
        // A fanout omission leaves one window stale indefinitely.
        try (var first = changes.watch("shop");
                var second = changes.watch("shop")) {
            first.next(0);
            second.next(0);
            source.push("next");
            assertThat(first.next(0))
                    .as("first window observes the changed board")
                    .containsEntry("revision", "next");
            assertThat(second.next(0))
                    .as("second window observes the same board")
                    .containsEntry("revision", "next");
            assertThat(first.next(0)).as("no duplicate first reply").isNull();
            assertThat(second.next(0)).as("no duplicate second reply").isNull();
        }
    }

    @Test
    void aBurstAtOneRevisionDoesNotRepeatTheBoardOrRead() throws Exception {
        // Repeated resource invalidations must not flood the board stream.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.push("next");
            assertThat(watcher.next(0))
                    .as("one revision produces one new board")
                    .containsEntry("revision", "next");
            for (int i = 0; i < 10; i++) source.push("next");
            assertThat(watcher.next(0)).as("duplicate invalidations are suppressed").isNull();
            assertThat(source.reads).as("duplicate revisions do not reread the board").isEqualTo(2);
        }
    }

    @Test
    void aLocalWorkSignalReachesWatchersWithoutAnAdapterRevisionChange() throws Exception {
        // Work outcomes must not remain hidden while the store revision stays unchanged.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.local = "working";
            changes.signal("shop");
            assertThat(watcher.next(0))
                    .as("the local work snapshot changes")
                    .containsEntry("stories", List.of("working"))
                    .containsEntry("revision", "zero");
            changes.signal("shop");
            assertThat(watcher.next(0))
                    .as("an unchanged local snapshot is not sent twice")
                    .isNull();
            source.local = "ended";
            changes.signal("shop");
            assertThat(watcher.next(0))
                    .as("work ending uses the same signal")
                    .containsEntry("stories", List.of("ended"));
        }
    }

    @Test
    void aSignalWithoutWatchersDoesNotStartAnAdapter() {
        // Local work writes must not create unused remote subscriptions.
        changes.signal("shop");
        assertThat(source.reads).as("no adapter access without watchers").isZero();
        assertThat(source.opens).as("no stream without watchers").isZero();
    }

    @Test
    void aSignalForAnotherProjectDoesNotReadThisBoard() throws Exception {
        // Invalidations must remain inside their project.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            changes.signal("elsewhere");
            assertThat(source.reads).as("another project's signal has no effect").isEqualTo(1);
            assertThat(watcher.next(0)).as("no unrelated board update").isNull();
        }
    }

    @Test
    void anAdapterWithoutWatchIsReadOnThePeriodAndNotBefore() throws Exception {
        // A watchless adapter otherwise leaves its board permanently stale.
        source.watch = false;
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.revision = "later";
            clock.advance(PlanningChanges.POLL_INTERVAL.minusMillis(1));
            assertThat(source.reads).as("no premature polling").isEqualTo(1);
            assertThat(watcher.next(0)).as("no update before the deadline").isNull();
            clock.advance(Duration.ofMillis(1));
            assertThat(watcher.next(0))
                    .as("polling delivers the changed board")
                    .containsEntry("revision", "later");
            clock.advance(PlanningChanges.POLL_INTERVAL);
            assertThat(source.reads).as("polling continues on its period").isEqualTo(3);
            assertThat(source.opens).as("watch is never asked of a watchless adapter").isZero();
            assertThat(source.refreshes).as("periodic reads never fetch implicitly").isZero();
        }
    }

    @Test
    void theLastWatcherCancelsPolling() {
        // An abandoned window must not keep asking the store forever.
        source.watch = false;
        changes.watch("shop").close();
        clock.advance(PlanningChanges.POLL_INTERVAL.multipliedBy(2));
        assertThat(source.reads).as("no reads after the last client leaves").isEqualTo(1);
    }

    @Test
    void anEndedStreamReopensAfterBackoffWhileItsWatcherStays() throws Exception {
        // Adapter restarts must not silently strand existing windows.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.ended.run();
            clock.advance(PlanningChanges.RETRY_DELAY.minusMillis(1));
            assertThat(source.opens).as("reconnect respects the backoff").isEqualTo(1);
            clock.advance(Duration.ofMillis(1));
            source.push("reconnected");
            assertThat(source.opens).as("the stream reopens without another client").isEqualTo(2);
            assertThat(watcher.next(0))
                    .as("the same watcher receives the new stream")
                    .containsEntry("revision", "reconnected");
        }
    }

    @Test
    void aFailedOpenRetriesWithIncreasingBackoff() throws Exception {
        // A crashing adapter must neither spin nor terminate the client subscription.
        source.openFailure = new IllegalStateException("stream failed");
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            clock.advance(PlanningChanges.RETRY_DELAY);
            assertThat(source.opens).as("the first retry runs").isEqualTo(2);
            clock.advance(PlanningChanges.RETRY_DELAY);
            assertThat(source.opens).as("the second delay doubles").isEqualTo(2);
            source.openFailure = null;
            clock.advance(PlanningChanges.RETRY_DELAY);
            source.push("recovered");
            assertThat(source.opens).as("the failed stream opens again").isEqualTo(3);
            assertThat(watcher.next(0))
                    .as("the watcher survives adapter failures")
                    .containsEntry("revision", "recovered");
        }
    }

    @Test
    void theLastWatcherCancelsReconnectAndIgnoresLateCallbacks() {
        // A queued retry must not resurrect a closed subscription.
        var watcher = changes.watch("shop");
        var lateChange = source.changed;
        var lateEnd = source.ended;
        lateEnd.run();
        watcher.close();
        lateChange.accept("late");
        lateEnd.run();
        clock.advance(PlanningChanges.POLL_INTERVAL);
        assertThat(source.opens).as("no adapter reopened after disconnect").isEqualTo(1);
        assertThat(source.reads).as("late callbacks cannot read a closed source").isEqualTo(1);
    }

    @Test
    void aChangeBetweenTheBoardReadAndWatchOpeningIsNotLost() throws Exception {
        // Ignoring the adapter's initial revision loses the subscribe/read race.
        source.onOpen = () -> source.push("raced");
        try (var watcher = changes.watch("shop")) {
            assertThat(watcher.next(0))
                    .as("the promised first snapshot is preserved")
                    .containsEntry("revision", "zero");
            assertThat(watcher.next(0))
                    .as("the initial adapter revision is compared")
                    .containsEntry("revision", "raced");
        }
    }

    @Test
    void anEqualInitialAdapterRevisionNeedsNoSecondReply() throws Exception {
        // An adapter's handshake must not duplicate the board.
        source.onOpen = () -> source.push("zero");
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            assertThat(watcher.next(0))
                    .as("matching initial revision is already represented")
                    .isNull();
            assertThat(source.reads).as("no redundant board request").isEqualTo(1);
        }
    }

    @Test
    void aGoneWatcherNeverReceivesChangesOrBlocksOtherWatchers() throws Exception {
        // A disconnected or slow window must not prevent delivery to a healthy one.
        try (var gone = changes.watch("shop");
                var healthy = changes.watch("shop")) {
            gone.close();
            healthy.next(0);
            source.push("next");
            assertThat(gone.next(0)).as("gone watcher is dropped").isNull();
            assertThat(healthy.next(0))
                    .as("another watcher still advances")
                    .containsEntry("revision", "next");
        }
    }

    @Test
    void aSlowWatcherKeepsItsFirstAndLatestBoardWithoutBlockingAnyone() throws Exception {
        // An undrained stream must have bounded memory and preserve a useful latest board.
        try (var slow = changes.watch("shop");
                var healthy = changes.watch("shop")) {
            healthy.next(0);
            for (int i = 1; i <= 100; i++) {
                source.push("change" + i);
                assertThat(healthy.next(0))
                        .as("healthy watcher advances independently")
                        .containsEntry("revision", "change" + i);
            }
            assertThat(slow.next(0))
                    .as("the initial board is never overwritten")
                    .containsEntry("revision", "zero");
            assertThat(slow.next(0))
                    .as("slow watchers coalesce to the latest full board")
                    .containsEntry("revision", "change100");
            assertThat(slow.next(0)).as("no unbounded backlog").isNull();
        }
    }

    @Test
    void aBoardRefusalIsNamedAndEndsEveryAffectedWatcher() throws Exception {
        // A failed read must not be presented as a completed or empty board.
        try (var first = changes.watch("shop");
                var second = changes.watch("shop")) {
            first.next(0);
            second.next(0);
            source.refusal =
                    new VarlinkException(
                            TasksContract.INTERFACE + ".RateLimited",
                            Map.of("detail", "Wait", "retryAfter", 7));
            source.push("unreadable");
            for (var watcher : List.of(first, second)) {
                assertThatThrownBy(() -> watcher.next(0))
                        .as("the refusal keeps its name and fields")
                        .isSameAs(source.refusal);
                assertThat(watcher.next(0)).as("a refused watch terminates").isNull();
            }
            assertThat(source.closes).as("refusal releases the upstream stream").isEqualTo(1);
        }
    }

    @Test
    void anInitialRefusalClosesItsSourceAndDoesNotStartAStream() {
        // Startup refusal must release the just-created adapter.
        source.refusal =
                new VarlinkException(
                        TasksContract.INTERFACE + ".PlanningNotSet", Map.of("project", "shop"));
        assertThatThrownBy(() -> changes.watch("shop"))
                .as("initial named error")
                .isSameAs(source.refusal);
        assertThat(source.opens).as("no subscription after refusal").isZero();
        assertThat(source.closes).as("failed initial read releases its source").isEqualTo(1);
    }

    @Test
    void closingTheOwnerEndsSubscriptionsAndCancelsRetries() throws Exception {
        // Daemon shutdown must release adapters and waiting clients.
        var watcher = changes.watch("shop");
        watcher.next(0);
        source.ended.run();
        changes.close();
        clock.advance(PlanningChanges.POLL_INTERVAL);
        assertThatThrownBy(() -> watcher.next(0))
                .as("owner shutdown wakes its clients with a named refusal")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("shutdown must not look like an idle stream")
                                        .isEqualTo(TasksContract.INTERFACE + ".Unreachable"));
        assertThat(source.opens).as("no retry after shutdown").isEqualTo(1);
        assertThat(source.closes).as("source closes once").isEqualTo(1);
        assertThatThrownBy(() -> changes.watch("shop"))
                .as("closed owners cannot acquire resources")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshUsesTheWatchedAdapterWithoutClosingIt() {
        // Refreshing another process would leave the active board's cache stale.
        try (var watcher = changes.watch("shop")) {
            source.revision = "refreshed";
            assertThat(changes.refresh("shop"))
                    .as("revision from the active adapter")
                    .isEqualTo("refreshed");
            assertThat(source.refreshes).as("explicit fetch was requested once").isEqualTo(1);
            assertThat(source.closes).as("refresh does not interrupt watchers").isZero();
        }
    }

    @Test
    void oneShotReadsAndRefreshesReleaseTheirSourceWithoutSubscribing() {
        // One-shot clients must not leak adapters or implicitly fetch a listing.
        assertThat(changes.board("shop"))
                .as("a single board read")
                .containsEntry("revision", "zero");
        assertThat(changes.refresh("shop")).as("an explicit fetch").isEqualTo("zero");
        assertThat(source.closes).as("each one-shot operation releases its source").isEqualTo(2);
        assertThat(source.opens).as("neither operation subscribes").isZero();
    }

    @Test
    void aWatchRefusalEndsEveryWatcherInsteadOfRetrying() throws Exception {
        // A refused stream must not leave clients trusting an obsolete board.
        try (var first = changes.watch("shop");
                var second = changes.watch("shop")) {
            first.next(0);
            second.next(0);
            source.ended.run();
            var refusal =
                    new VarlinkException(
                            TasksContract.INTERFACE + ".InvalidSettings",
                            Map.of("detail", "Repair settings"));
            source.openFailure = refusal;
            clock.advance(PlanningChanges.RETRY_DELAY);
            for (var watcher : List.of(first, second)) {
                assertThatThrownBy(() -> watcher.next(0))
                        .as("watch refusal reaches every client")
                        .isSameAs(refusal);
            }
            assertThat(source.closes).as("refused stream releases its adapter").isEqualTo(1);
        }
    }

    @Test
    void aRefusedReopenReadEndsTheWatch() throws Exception {
        // A restart refusal must not strand clients during reconnect backoff.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.ended.run();
            source.refusal =
                    new VarlinkException(
                            TasksContract.INTERFACE + ".WorkflowMissing",
                            Map.of("project", "shop", "detail", "Repair workflow"));
            clock.advance(PlanningChanges.RETRY_DELAY);
            assertThatThrownBy(() -> watcher.next(0))
                    .as("reopen rereads and delivers the named refusal")
                    .isSameAs(source.refusal);
        }
    }

    @Test
    void refreshPublishesTheNewBoardToExistingAndJoiningWatchers() throws Exception {
        // An explicit refresh must not leave open windows on stale data.
        source.watch = false;
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.revision = "fetched";
            changes.refresh("shop");
            assertThat(watcher.next(0))
                    .as("refresh advances existing watchers immediately")
                    .containsEntry("revision", "fetched");
            try (var joining = changes.watch("shop")) {
                assertThat(joining.next(0))
                        .as("new watchers receive the refreshed cache")
                        .containsEntry("revision", "fetched");
            }
        }
    }

    @Test
    void aJoiningWatcherRereadsWhileTheStreamIsDisconnected() throws Exception {
        // Reconnecting clients must not inherit a cache that missed outside changes.
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.ended.run();
            source.revision = "outside";
            try (var joining = changes.watch("shop")) {
                assertThat(joining.next(0))
                        .as("joining during backoff reads the current board")
                        .containsEntry("revision", "outside");
            }
        }
    }

    @Test
    void anUnexpectedPollFailureRefusesAndReleasesTheProject() throws Exception {
        // An unchecked read failure must not leak an adapter and strand its watchers.
        source.watch = false;
        try (var watcher = changes.watch("shop")) {
            watcher.next(0);
            source.refusal = new IllegalStateException("broken snapshot");
            assertThatThrownBy(
                            () -> {
                                clock.advance(PlanningChanges.POLL_INTERVAL);
                                watcher.next(0);
                            })
                    .as("unexpected failures cross the stream as a named Failed refusal")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(ex.getErrorName())
                                            .as("unexpected read failure name")
                                            .isEqualTo(TasksContract.INTERFACE + ".Failed"));
            assertThat(source.closes)
                    .as("unexpected read failure releases the adapter")
                    .isEqualTo(1);
        }
    }

    @Test
    void aRefusalStopsOnlyItsProjectAndIdleTimeReleasesTheScheduler() throws Exception {
        // One project's failure must not terminate another project's live board.
        var other = new Source();
        try (var owner = new PlanningChanges(name -> name.equals("shop") ? source : other, clock);
                var shop = owner.watch("shop");
                var healthy = owner.watch("other")) {
            shop.next(0);
            healthy.next(0);
            source.refusal =
                    new VarlinkException(
                            TasksContract.INTERFACE + ".InvalidSettings",
                            Map.of("detail", "Repair settings"));
            source.push("refused");
            assertThat(clock.closes).as("another project still needs the scheduler").isZero();
            other.push("healthy");
            assertThat(healthy.next(0))
                    .as("another project continues after refusal")
                    .containsEntry("revision", "healthy");
            healthy.close();
            assertThat(clock.closes).as("no projects leaves no timer resources").isEqualTo(1);
            assertThat(other.closes)
                    .as("only its last watcher closes the healthy adapter")
                    .isEqualTo(1);
        }
    }

    @Test
    void watchedBoardReadsReuseTheActiveSource() {
        // A second adapter can disagree with the watched cache and leak extra processes.
        var created = new java.util.concurrent.atomic.AtomicInteger();
        try (var owner =
                        new PlanningChanges(
                                name -> {
                                    created.incrementAndGet();
                                    return source;
                                },
                                clock);
                var watcher = owner.watch("shop")) {
            source.revision = "current";
            assertThat(owner.board("shop"))
                    .as("listing reads the active adapter")
                    .containsEntry("revision", "current");
            assertThat(created.get()).as("listing reuses the watched source").isEqualTo(1);
            assertThat(source.closes).as("listing leaves the watched adapter open").isZero();
        }
    }

    @Test
    void reconnectBackoffCapsAndARevisionResetsIt() {
        // Unbounded backoff hides changes indefinitely and missing reset delays recovery.
        source.openFailure = new IllegalStateException("broken stream");
        try (var watcher = changes.watch("shop")) {
            for (int seconds : new int[] {1, 2, 4, 8, 16, 32, 64, 128, 180, 180}) {
                int opens = source.opens;
                clock.advance(Duration.ofSeconds(seconds).minusMillis(1));
                assertThat(source.opens)
                        .as("backoff waits the capped interval %s", seconds)
                        .isEqualTo(opens);
                clock.advance(Duration.ofMillis(1));
                assertThat(source.opens)
                        .as("backoff retries at the capped interval %s", seconds)
                        .isEqualTo(opens + 1);
            }
            source.openFailure = null;
            clock.advance(PlanningChanges.POLL_INTERVAL);
            source.push("healthy");
            source.ended.run();
            int opens = source.opens;
            clock.advance(PlanningChanges.RETRY_DELAY);
            assertThat(source.opens)
                    .as("a received revision resets reconnect backoff")
                    .isEqualTo(opens + 1);
        }
    }

    @Test
    void aBlockedRefreshDoesNotDelayAnotherProjectOrDisconnectOrShutdown() throws Exception {
        // A slow adapter must not freeze other boards or prevent daemon shutdown.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked =
                new Source() {
                    public String refresh() {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return revision;
                    }

                    public void close() {
                        super.close();
                        release.countDown();
                    }
                };
        var other = new Source();
        var owner = new PlanningChanges(name -> name.equals("shop") ? blocked : other, clock);
        var first = owner.watch("shop");
        try (var executor = java.util.concurrent.Executors.newCachedThreadPool()) {
            var refreshing = executor.submit(() -> owner.refresh("shop"));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("refresh is blocked inside the adapter")
                        .isTrue();
                var independent =
                        executor.submit(
                                () -> {
                                    try (var watcher = owner.watch("other")) {
                                        owner.signal("other");
                                        return watcher.next(0);
                                    }
                                });
                assertThat(independent.get(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("another project watches and signals during a slow refresh")
                        .containsEntry("revision", "zero");
                executor.submit(first::close).get(2, java.util.concurrent.TimeUnit.SECONDS);
                executor.submit(owner::close).get(2, java.util.concurrent.TimeUnit.SECONDS);
                refreshing.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(blocked.closes)
                        .as("shutdown closes a source even while refresh is blocked")
                        .isEqualTo(1);
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new AssertionError(
                        "a slow refresh must not hold the daemon-wide monitor", ex);
            } finally {
                release.countDown();
                owner.close();
            }
        }
    }

    @Test
    void theLastWatcherLeavingDoesNotCloseTheAdapterUnderAnotherClientsRefresh() throws Exception {
        // A watcher's disconnect must not fail another client's refresh by closing its adapter.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked =
                new Source() {
                    public String refresh() {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        if (closes > 0) throw new IllegalStateException("the adapter was closed");
                        return revision;
                    }
                };
        var owner = new PlanningChanges(name -> blocked, clock);
        try (var executor = java.util.concurrent.Executors.newCachedThreadPool()) {
            // Released before the executor waits for the refresh, so a failure cannot hang.
            try {
                var watcher = owner.watch("shop");
                var refreshing = executor.submit(() -> owner.refresh("shop"));
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("refresh is inside the adapter")
                        .isTrue();
                watcher.close();
                assertThat(blocked.closes)
                        .as("the adapter stays open while another client's refresh runs on it")
                        .isZero();
                release.countDown();
                assertThat(refreshing.get(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the refresh answers its revision")
                        .isEqualTo("zero");
                assertThat(blocked.closes)
                        .as("the refresh's end releases the adapter nobody watches")
                        .isEqualTo(1);
            } finally {
                release.countDown();
                owner.close();
            }
        }
    }

    @Test
    void shutdownClosesAllSourcesBeforeWaitingForAnyOneOfThem() throws Exception {
        // A blocked close must not leave another adapter running during daemon shutdown.
        var bothClosing = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<Source> factory =
                () ->
                        new Source() {
                            public void close() {
                                bothClosing.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                                super.close();
                            }
                        };
        var one = factory.get();
        var two = factory.get();
        var owner = new PlanningChanges(name -> name.equals("one") ? one : two, clock);
        var first = owner.watch("one");
        var second = owner.watch("two");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var closing = executor.submit(owner::close);
            try {
                assertThat(bothClosing.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("shutdown starts every adapter close before waiting for a slow one")
                        .isTrue();
            } finally {
                release.countDown();
            }
            closing.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one.closes).as("first adapter released").isEqualTo(1);
            assertThat(two.closes).as("second adapter released").isEqualTo(1);
        } finally {
            release.countDown();
            first.close();
            second.close();
            owner.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"start", "board", "watch", "close"})
    void blockingAdapterEdgesNeverHoldTheSharedMonitor(String edge) throws Exception {
        // Any slow adapter edge must leave other projects and their disconnects responsive.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Runnable block =
                () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                };
        var slow =
                new Source() {
                    public Map<String, Object> board() {
                        if (edge.equals("board")) block.run();
                        return super.board();
                    }

                    public void watch(
                            Consumer<String> changed,
                            Consumer<RuntimeException> failed,
                            Runnable ended) {
                        if (edge.equals("watch")) block.run();
                        super.watch(changed, failed, ended);
                    }

                    public void close() {
                        if (edge.equals("close")) block.run();
                        super.close();
                    }
                };
        var other = new Source();
        var owner =
                new PlanningChanges(
                        name -> {
                            if (name.equals("slow")) {
                                if (edge.equals("start")) block.run();
                                return slow;
                            }
                            return other;
                        },
                        clock);
        try (var executor = java.util.concurrent.Executors.newCachedThreadPool()) {
            var blocked =
                    executor.submit(
                            () -> {
                                try (var watcher = owner.watch("slow")) {
                                    watcher.next(0);
                                }
                                return true;
                            });
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the adapter edge is blocked")
                        .isTrue();
                var independent =
                        executor.submit(
                                () -> {
                                    try (var watcher = owner.watch("other")) {
                                        owner.signal("other");
                                        return watcher.next(0);
                                    }
                                });
                assertThat(independent.get(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("other projects watch, signal and disconnect while %s blocks", edge)
                        .containsEntry("revision", "zero");
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new AssertionError("blocking " + edge + " holds the shared monitor", ex);
            } finally {
                release.countDown();
            }
            blocked.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            owner.close();
        }
    }

    @Test
    void anOlderBoardReadCannotOverwriteANewerSignal() throws Exception {
        // A slow previous snapshot must not revert a board that already advanced.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var slow =
                new Source() {
                    public Map<String, Object> board() {
                        var board = super.board();
                        if (count.incrementAndGet() == 2) {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return board;
                    }
                };
        try (var owner = new PlanningChanges(name -> slow, clock);
                var watcher = owner.watch("shop");
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            watcher.next(0);
            slow.revision = "old";
            var reading = executor.submit(() -> owner.signal("shop"));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("old snapshot is in flight")
                        .isTrue();
                slow.revision = "new";
                owner.signal("shop");
                assertThat(watcher.next(0))
                        .as("newer read advances the board")
                        .containsEntry("revision", "new");
            } finally {
                release.countDown();
            }
            reading.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(watcher.next(0)).as("late older reads never revert the cache").isNull();
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentReadersConsumeTheInitialBoardOnlyOnce() throws Exception {
        // Concurrent delivery and disconnect must not duplicate the initial snapshot.
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10000; round++) {
                try (var watcher = changes.watch("shop")) {
                    java.util.concurrent.Callable<Map<String, Object>> consume =
                            () -> {
                                barrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
                                return watcher.next(0);
                            };
                    var first = executor.submit(consume);
                    var second = executor.submit(consume);
                    int delivered =
                            (first.get(2, java.util.concurrent.TimeUnit.SECONDS) == null ? 0 : 1)
                                    + (second.get(2, java.util.concurrent.TimeUnit.SECONDS) == null
                                            ? 0
                                            : 1);
                    assertThat(delivered)
                            .as("the initial board has exactly one consumer in round %s", round)
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    void aSignalDoesNotStopAOneShotSourceThatIsStillStarting() throws Exception {
        // A work signal without watchers must not abort another caller's one-shot read.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var owner =
                        new PlanningChanges(
                                name -> {
                                    entered.countDown();
                                    try {
                                        release.await();
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return source;
                                },
                                clock);
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> owner.board("shop"));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the one-shot source is starting")
                        .isTrue();
                owner.signal("shop");
            } finally {
                release.countDown();
            }
            org.assertj.core.api.Assertions.assertThatCode(
                            () -> reading.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .as("a signal with no watchers leaves the one-shot read intact")
                    .doesNotThrowAnyException();
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownReportsACloseFailureAfterClosingEverySource() {
        // A failed cleanup must neither hide a leaked adapter nor prevent other cleanup.
        var broken =
                new Source() {
                    public void close() {
                        super.close();
                        throw new IllegalStateException("cannot close adapter");
                    }
                };
        var healthy = new Source();
        var owner = new PlanningChanges(name -> name.equals("broken") ? broken : healthy, clock);
        var first = owner.watch("broken");
        var second = owner.watch("healthy");
        try {
            assertThatThrownBy(owner::close)
                    .as("shutdown exposes an adapter cleanup failure")
                    .hasRootCauseMessage("cannot close adapter");
            assertThat(healthy.closes)
                    .as("a failed adapter close does not skip other adapters")
                    .isEqualTo(1);
        } finally {
            first.close();
            second.close();
            owner.close();
        }
    }

    @Test
    void theLastWatcherReleasesTheTimerWhileAnotherProjectHasAOneShotRead() throws Exception {
        // A long one-shot call must not retain idle subscription timer resources.
        source.watch = false;
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var other =
                new Source() {
                    public Map<String, Object> board() {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return super.board();
                    }
                };
        try (var owner = new PlanningChanges(name -> name.equals("shop") ? source : other, clock);
                var watcher = owner.watch("shop");
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> owner.board("other"));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the unrelated one-shot read is in flight")
                        .isTrue();
                watcher.close();
                assertThat(clock.closes)
                        .as(
                                "the last watcher releases its timer without waiting for one-shot calls")
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
            reading.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }
}
