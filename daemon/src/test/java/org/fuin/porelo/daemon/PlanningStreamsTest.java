package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.app.PlanningChanges;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the daemon's continuation and final-reply contract without sockets, processes or waits.
 */
class PlanningStreamsTest {
    private static final class Source implements PlanningChanges.Source {
        Map<String, Object> board = Map.of("stories", List.of(), "revision", "initial");
        VarlinkException refusal;
        int closes;
        int opens;
        int reads;
        int refreshes;

        public Map<String, Object> board() {
            reads++;
            return board;
        }

        public boolean hasWatch() {
            return true;
        }

        public void watch(
                Consumer<String> changed, Consumer<RuntimeException> failed, Runnable ended) {
            opens++;
        }

        public String refresh() {
            refreshes++;
            if (refusal != null) throw refusal;
            return "fetched";
        }

        public void close() {
            closes++;
        }
    }

    private static final class Replies implements VarlinkServer.Replies {
        final List<Map<String, Object>> more = new ArrayList<>();
        final List<Map<String, Object>> last = new ArrayList<>();
        boolean streaming = true;
        volatile boolean open = true;
        boolean asked;
        Runnable afterReply = () -> open = false;

        public void more(Map<String, Object> reply) {
            more.add(reply);
            afterReply.run();
        }

        public void last(Map<String, Object> reply) {
            last.add(reply);
        }

        public boolean streaming() {
            return streaming;
        }

        public boolean open() {
            if (!asked) {
                asked = true;
                return true;
            }
            return open;
        }
    }

    private final Source source = new Source();
    private final PlanningChanges changes =
            new PlanningChanges(
                    project -> source,
                    (delay, action) -> {
                        throw new AssertionError("No timers needed in method tests");
                    });
    private final Replies replies = new Replies();

    @org.junit.jupiter.api.AfterEach
    void closeTheOwner() {
        changes.close();
    }

    @Test
    void theFirstReplyIsTheBoardAndASignalProducesTheNextReply() throws Exception {
        // A missing continuation leaves a connected window on an obsolete snapshot.
        var initial = source.board;
        var next = Map.<String, Object>of("stories", List.of("changed"), "revision", "initial");
        replies.afterReply =
                () -> {
                    if (replies.more.size() == 1) {
                        source.board = next;
                        changes.signal("shop");
                    } else replies.open = false;
                };
        PlanningMethods.watch(changes, "shop", replies);
        assertThat(replies.more)
                .as("whole initial board then the changed local snapshot")
                .containsExactly(initial, next);
        assertThat(replies.last)
                .as("a disconnected stream claims no successful final reply")
                .isEmpty();
        assertThat(source.closes).as("disconnect releases the source").isEqualTo(1);
    }

    @Test
    void aClientThatLeavesAfterTheFirstReplyEndsItsStream() throws Exception {
        // A quiet abandoned stream must not hold its adapter open forever.
        PlanningMethods.watch(changes, "shop", replies);
        assertThat(replies.more)
                .as("initial snapshot sent before disconnect")
                .containsExactly(source.board);
        assertThat(source.opens).as("the stream was subscribed").isEqualTo(1);
        assertThat(source.closes).as("the departed client's stream ends").isEqualTo(1);
    }

    @Test
    void aHalfClosedClientReceivesExactlyTheCurrentBoard() throws Exception {
        // Ending a request must not truncate the promised initial board reply.
        replies.open = false;
        PlanningMethods.watch(changes, "shop", replies);
        assertThat(replies.more)
                .as("a half-closed client still receives the current board once")
                .containsExactly(source.board);
        assertThat(source.closes).as("the half-closed stream releases its adapter").isEqualTo(1);
    }

    @Test
    void withoutMoreTheWatchAnswersExactlyOnceAndNeverSubscribes() throws Exception {
        // Nonstreaming callers must receive a final answer rather than a continuation or hang.
        replies.streaming = false;
        PlanningMethods.watch(changes, "shop", replies);
        assertThat(replies.last).as("one final board response").containsExactly(source.board);
        assertThat(replies.more).as("no continuation without more").isEmpty();
        assertThat(source.opens).as("one-shot calls do not subscribe").isZero();
        assertThat(source.closes).as("one-shot calls release the adapter").isEqualTo(1);
    }

    @Test
    void refreshAnswersTheAdaptersRevisionAsAFinalReply() throws Exception {
        // The window must receive the revision returned by its explicit fetch.
        PlanningMethods.refresh(changes, "shop", replies);
        assertThat(replies.last)
                .as("the adapter's fetched revision")
                .containsExactly(Map.of("revision", "fetched"));
        assertThat(source.refreshes).as("the explicit fetch reaches the adapter once").isEqualTo(1);
        assertThat(source.closes).as("a one-shot refresh releases the adapter").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PlanningCredentialRefused", "RateLimited", "Unreachable"})
    void refreshKeepsTheNamedRefusalAndItsWait(String name) {
        // Folding credential and transient store failures prevents clients from taking the right
        // action.
        source.refusal =
                new VarlinkException(
                        TasksContract.INTERFACE + "." + name,
                        Map.of("detail", "Safe detail", "retryAfter", 37));
        assertThatThrownBy(() -> PlanningMethods.refresh(changes, "shop", replies))
                .as("the read source's named error is preserved")
                .isSameAs(source.refusal);
        assertThat(replies.last).as("refusal never claims a successful refresh").isEmpty();
        assertThat(source.closes).as("a refused refresh releases its adapter").isEqualTo(1);
    }

    @Test
    void aQuietDisconnectClosesTheAdapterBeforeTheDaemonOwnerCloses() throws Exception {
        // An idle disconnected stream must not retain its adapter for the daemon's lifetime.
        var delivered = new java.util.concurrent.CountDownLatch(1);
        replies.afterReply = delivered::countDown;
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var serving =
                    executor.submit(
                            () -> {
                                PlanningMethods.watch(changes, "shop", replies);
                                return true;
                            });
            try {
                assertThat(delivered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the client is still streaming after its first board")
                        .isTrue();
                replies.open = false;
                try {
                    serving.get(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException ex) {
                    throw new AssertionError(
                            "disconnect must end the adapter stream while the daemon still serves",
                            ex);
                }
                assertThat(source.closes)
                        .as("disconnect releases the adapter before owner shutdown")
                        .isEqualTo(1);
            } finally {
                changes.close();
            }
        }
    }
}
