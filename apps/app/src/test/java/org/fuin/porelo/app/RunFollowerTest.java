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

    @Test
    void noThreadIsInterruptedSoNeitherTheAgentsNorTheCallersSocketIsClosed(@TempDir final Path dir)
            throws Exception {
        // An interrupted thread entering a socket channel's write closes that channel: the agent's,
        // which formats, or the caller's, which still waits for the final reply.
        final Path log = dir.resolve("task.log");
        final List<String> interrupted = new CopyOnWriteArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();

        final RunFollower follower =
                RunFollower.start(
                        log,
                        lines -> {
                            if (Thread.currentThread().isInterrupted()) {
                                interrupted.add("format");
                            }
                            return formatted(lines);
                        },
                        line -> {
                            if (Thread.currentThread().isInterrupted()) {
                                interrupted.add("send " + line);
                            }
                            sent.add(line);
                        },
                        Duration.ofMillis(200));
        Thread.sleep(50);
        // Written while the reader sleeps, so only the read after the end finds it.
        append(log, "last\n");
        follower.close();

        assertThat(interrupted).isEmpty();
        assertThat(sent).containsExactly("> last");
    }

    @Test
    void nothingIsSentOnceItClosed(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("task.log");
        final List<String> sent = new CopyOnWriteArrayList<>();
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            many.append("line ").append(i).append('\n');
        }
        append(log, many.toString());

        final RunFollower follower =
                RunFollower.start(
                        log,
                        RunFollowerTest::formatted,
                        line -> {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            sent.add(line);
                        },
                        Duration.ofMillis(10));
        follower.close();
        final int atClose = sent.size();
        Thread.sleep(200);

        assertThat(sent).as("the start writes alone after the close").hasSize(atClose);
        assertThat(atClose + follower.dropped()).isEqualTo(200);
    }

    @Test
    void aLongLineIsCutAtACodePoint(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("task.log");
        final List<String> sent = new CopyOnWriteArrayList<>();

        try (RunFollower follower =
                RunFollower.start(log, ArrayList::new, sent::add, Duration.ofMillis(10))) {
            // The three bytes of the last character straddle the bound.
            append(log, "a".repeat(RunFollower.MOST_LINE - 1) + "語b\n");
        }

        assertThat(String.join("", sent)).isEqualTo("a".repeat(RunFollower.MOST_LINE - 1) + "語b");
        assertThat(sent).noneMatch(line -> line.contains("\uFFFD"));
    }
}
