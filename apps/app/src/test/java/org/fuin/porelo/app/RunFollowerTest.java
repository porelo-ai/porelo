package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunFollowerTest {

    private static List<@Nullable String> formatted(final List<String> lines) {
        final List<@Nullable String> shown = new ArrayList<>();
        for (final String line : lines) {
            shown.add(line.startsWith("hidden") ? null : "> " + line);
        }
        return shown;
    }

    private static void append(final Path log, final String text) throws Exception {
        Files.writeString(
                log,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Test
    void itSendsEachLineTheRunWritesFormattedAndTheRestAfterItsEnd(@TempDir final Path dir)
            throws Exception {
        final Path log = dir.resolve("task.log");
        final List<String> sent = new CopyOnWriteArrayList<>();

        try (RunFollower follower =
                RunFollower.start(
                        log, RunFollowerTest::formatted, sent::add, Duration.ofMillis(20))) {
            append(log, "one\nhidden\n");
            Thread.sleep(200);
            assertThat(sent).as("sent while the run lasts").containsExactly("> one");
            // Written last, with no line end: the run was killed mid-line.
            append(log, "two\nthree");
        }

        assertThat(sent).containsExactly("> one", "> two", "> three");
    }

    @Test
    void aCallerThatStopsReadingNeverHoldsTheRunAndIsToldWhatItMissed(@TempDir final Path dir)
            throws Exception {
        final Path log = dir.resolve("task.log");
        final CountDownLatch never = new CountDownLatch(1);
        final List<String> sent = new CopyOnWriteArrayList<>();
        final RunFollower follower =
                RunFollower.start(
                        log,
                        RunFollowerTest::formatted,
                        line -> {
                            sent.add(line);
                            if (sent.size() == 1) {
                                try {
                                    // A client that stops reading: its socket write blocks.
                                    never.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        },
                        Duration.ofMillis(10));
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < RunFollower.MOST_WAITING * 3; i++) {
            many.append("line ").append(i).append('\n');
        }
        append(log, many.toString());

        final long before = System.nanoTime();
        follower.close();
        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        never.countDown();

        assertThat(took)
                .as("closing waits a bounded time, not for the caller")
                .isLessThan(RunFollower.LAST_WAIT.toMillis() + 1_000);
        assertThat(follower.dropped()).isPositive();
    }

    @Test
    void aLineLongerThanTheBoundIsSentCutNotHeldWhole(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("task.log");
        final List<String> sent = new CopyOnWriteArrayList<>();

        try (RunFollower follower =
                RunFollower.start(
                        log, lines -> new ArrayList<>(lines), sent::add, Duration.ofMillis(10))) {
            append(log, "y".repeat(RunFollower.MOST_LINE * 3) + "\nend\n");
        }

        assertThat(sent).last().isEqualTo("end");
        assertThat(sent)
                .allSatisfy(
                        line ->
                                assertThat(line.length())
                                        .isLessThanOrEqualTo(RunFollower.MOST_LINE));
    }

    @Test
    void aLogThatIsALinkIsNotFollowed(@TempDir final Path dir) throws Exception {
        final Path elsewhere = Files.writeString(dir.resolve("secret"), "the secret\n");
        final Path log = Files.createSymbolicLink(dir.resolve("task.log"), elsewhere);
        final List<String> sent = new CopyOnWriteArrayList<>();

        try (RunFollower follower =
                RunFollower.start(
                        log, RunFollowerTest::formatted, sent::add, Duration.ofMillis(10))) {
            Thread.sleep(100);
        }

        assertThat(sent).isEmpty();
    }
}
