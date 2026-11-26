package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One round's runtime exception ends that round only, never the thread that makes the rounds. */
class MessageWatchRoundsTest {

    private static final long BOUND_MILLIS = 5_000;

    @TempDir Path dir;

    @Test
    void aRoundThatThrowsARuntimeExceptionIsFollowedByTheNextRound() throws InterruptedException {
        final PoreloContext context =
                new PoreloContext(
                                new TransportTestRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .withLimits(LimitFactors.parse("polling=0.1"));
        final AtomicInteger looks = new AtomicInteger();
        final MessageWatch watch =
                new MessageWatch(
                        context,
                        Duration.ofMillis(50),
                        () -> {
                            if (looks.incrementAndGet() == 1) {
                                throw new IllegalStateException("one round fails");
                            }
                            return false;
                        });
        try {
            watch.start();
            final long end = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
            while (looks.get() < 2 && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertThat(looks.get())
                    .as("the watch looked again after the round that threw")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            watch.close();
        }
    }

    private PoreloContext context() {
        return new PoreloContext(
                        new TransportTestRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0)
                .withLimits(LimitFactors.parse("polling=0.1"));
    }

    /**
     * Runs a timed watch until the supplier was asked {@code looks} times; returns what it said.
     */
    private String saidWhileLooking(
            final BooleanSupplier supplier, final AtomicInteger asked, final int looks)
            throws InterruptedException {
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try (MessageWatch watch = new MessageWatch(context(), Duration.ofMillis(50), supplier)) {
            watch.start();
            final long end = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
            while (asked.get() < looks && System.nanoTime() < end) {
                Thread.sleep(20);
            }
        } finally {
            System.setErr(original);
        }
        return captured.toString();
    }

    private static long roundLines(final String said) {
        return said.lines().filter(line -> line.contains("a round failed")).count();
    }

    @Test
    void aFaultThatRepeatsOverSeveralRoundsIsLoggedOnce() throws InterruptedException {
        final AtomicInteger asked = new AtomicInteger();
        final String said =
                saidWhileLooking(
                        () -> {
                            asked.incrementAndGet();
                            throw new IllegalStateException("persistent");
                        },
                        asked,
                        6);

        assertThat(asked.get()).as("the watch looked several times").isGreaterThanOrEqualTo(6);
        assertThat(roundLines(said)).as("the same fault is said once").isEqualTo(1);
    }

    @Test
    void aFaultThatClearedAndCameBackIsLoggedAgain() throws InterruptedException {
        final AtomicInteger asked = new AtomicInteger();
        final String said =
                saidWhileLooking(
                        () -> {
                            final int n = asked.incrementAndGet();
                            if (n == 1 || n == 3 || n == 4) {
                                throw new IllegalStateException("again");
                            }
                            return false;
                        },
                        asked,
                        6);

        assertThat(roundLines(said)).as("one line for each stretch of failing rounds").isEqualTo(2);
    }

    @Test
    void theLineNamesTheClassAndTheFrameAndNeverTheMessage() throws InterruptedException {
        final AtomicInteger asked = new AtomicInteger();
        final String said =
                saidWhileLooking(
                        () -> {
                            asked.incrementAndGet();
                            throw new IllegalStateException("sekrit-value");
                        },
                        asked,
                        2);

        assertThat(said)
                .as("the exception's class")
                .contains("java.lang.IllegalStateException")
                .as("where it was thrown")
                .contains("MessageWatchRoundsTest")
                .as("no message, which could hold a credential")
                .doesNotContain("sekrit-value");
        assertThat(said.lines().filter(l -> l.contains("a round failed")).findFirst().orElse(""))
                .as("one line")
                .doesNotContain("\tat ");
    }

    @Test
    void aMailboxThatCannotBeWatchedDoesNotEndTheNoticeThread()
            throws IOException, InterruptedException {
        final PoreloContext context = context();
        final Mailbox box = new Mailbox(context.paths().messaging().mailbox("porelo-p-t"));
        box.create();
        final AtomicInteger found = new AtomicInteger();
        final PrintStream original = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            watch.onNewMailbox(
                    path -> {
                        found.incrementAndGet();
                        throw new IllegalStateException("cannot watch");
                    });
            assertThat(watch.startNotices()).as("noticing started").isTrue();
            Files.createDirectories(context.paths().messaging().mailboxes().resolve("porelo-p-x"));
            final long end = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
            while (found.get() < 1 && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            Files.writeString(box.inbound().resolve("m-9.json"), "{\"messageId\":\"m-9\"}");
            final Path held = box.hold().resolve("m-9.json");
            while (!Files.exists(held) && System.nanoTime() < end) {
                Thread.sleep(20);
            }

            assertThat(found.get()).as("the failing step ran").isEqualTo(1);
            assertThat(Files.exists(held)).as("the next round still moved a message").isTrue();
            assertThat(watch.noticing()).as("the notice thread is alive").isTrue();
        } finally {
            System.setErr(original);
        }
    }
}
