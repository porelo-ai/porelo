package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.HostPullRequestReader;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

class PullRequestMethodsTest {
    @Test
    void anEmptyWatchAnswersOnceAndStopsWhenTheCallerLeaves() throws Exception {
        // A silent empty watch leaves a client unable to distinguish no work from a dead service.
        final var replies = new Captured(true, 1);
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        GateMethods.watchPullRequests(
                previous -> {
                    assertThat(reads.incrementAndGet())
                            .as("an empty watch stops when its caller leaves")
                            .isEqualTo(1);
                    return new HostPullRequestReader.Round(List.of(), List.of());
                },
                replies,
                () -> {});
        assertThat(replies.events)
                .as("an initial empty snapshot")
                .containsExactly(Map.of("pullRequests", List.of(), "ended", List.of()));
        assertThat(reads.get()).as("disconnect stops subsequent polls").isEqualTo(1);
        assertThat(replies.last).as("a row event does not end the stream").isFalse();
    }

    @Test
    void withoutMoreTheWatchAnswersOnceWithoutWaiting() throws Exception {
        // A non-streaming caller must receive a final reply rather than hang in a polling loop.
        final var replies = new Captured(false, 1);
        GateMethods.watchPullRequests(
                previous -> new HostPullRequestReader.Round(List.of(), List.of()),
                replies,
                () -> {
                    throw new AssertionError("a one-shot call never waits");
                });
        assertThat(replies.last).as("one-shot calls receive a final reply").isTrue();
        assertThat(replies.events).as("one snapshot").hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"merged", "closed"})
    void aTerminalRowDoesNotEndTheProjectWatch(String state) throws Exception {
        // Ending one review must not drop the watch before the project's next review arrives.
        final var replies = new Captured(true, 3);
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        final var open =
                new HostPullRequestReader.Pull(
                        "demo", 7, "Review", "b".repeat(40), "Writer", "Signer", List.of(), "open");
        final var ended =
                new HostPullRequestReader.Pull(
                        "demo", 7, "Review", "b".repeat(40), "Writer", "Signer", List.of(), state);
        GateMethods.watchPullRequests(
                previous -> {
                    final int round = reads.getAndIncrement();
                    assertThat(round)
                            .as("the watch must send each changed snapshot without looping")
                            .isLessThan(3);
                    assertThat(previous)
                            .as("only open rows are carried to the next observation")
                            .isEqualTo(round == 1 ? List.of(open) : List.of());
                    return switch (round) {
                        case 0 -> new HostPullRequestReader.Round(List.of(open), List.of());
                        case 1 -> new HostPullRequestReader.Round(List.of(), List.of(ended));
                        default -> new HostPullRequestReader.Round(List.of(), List.of());
                    };
                },
                replies,
                () -> {});
        assertThat(replies.events.get(1))
                .as("the confirmed terminal row is sent")
                .containsEntry("ended", List.of(ended.asMap()));
        assertThat(replies.events.get(2))
                .as("the stream continues without repeating that event")
                .containsEntry("ended", List.of());
        assertThat(replies.last).as("terminal rows do not finish a project watch").isFalse();
    }

    private static final class Captured
            implements org.fuin.porelo.wire.varlink.VarlinkServer.Replies {
        private final boolean streaming;
        private final int limit;
        private final java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
        private boolean last;

        Captured(boolean streaming, int limit) {
            this.streaming = streaming;
            this.limit = limit;
        }

        @Override
        public void more(Map<String, Object> reply) {
            events.add(reply);
        }

        @Override
        public void last(Map<String, Object> reply) {
            last = true;
            events.add(reply);
        }

        @Override
        public boolean streaming() {
            return streaming;
        }

        @Override
        public boolean open() {
            return events.size() < limit;
        }
    }

    @Test
    void aMissingPersonTokenIsANamedWireErrorWithTheStorageCommand() {
        // A client must be able to repair a missing token without parsing a diagnostic sentence.
        assertThatThrownBy(
                        () ->
                                GateMethods.pullRequestReply(
                                        () -> {
                                            throw new HostPullRequestReader.MissingToken(
                                                    "forge.example.person");
                                        }))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .as("a typed missing-token error")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".MissingHostForgeToken");
                            assertThat(error.getParameters())
                                    .as("the exact entry and available command")
                                    .isEqualTo(
                                            Map.of(
                                                    "credential",
                                                    "forge.example.person",
                                                    "storeCommand",
                                                    List.of("porelo", "host", "forge-token")));
                        });
    }

    @Test
    void aTaskCannotSpendThePersonsIdentityToReadTheirReviewQueue() {
        // A task's MCP configuration must not grant access to the person's forge identity.
        assertThat(McpMethods.NEVER)
                .as("these methods stay unavailable even from the person's local writing list")
                .contains("PullRequests", "WatchPullRequests");
    }
}
