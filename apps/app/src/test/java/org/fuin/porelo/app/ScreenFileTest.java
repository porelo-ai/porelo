package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ScreenFile}: an agent's screen read from a file the task writes, not from a
 * process in it.
 */
class ScreenFileTest {

    @TempDir Path state;

    private static final Instant NOW = Instant.parse("2026-10-07T17:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private void alive(final Instant at) throws Exception {
        final Path alive = ScreenFile.prepare(state).resolve(ScreenFile.ALIVE);
        Files.writeString(alive, "");
        Files.setLastModifiedTime(alive, FileTime.from(at));
    }

    @Test
    void aWriterThatLooksIsReadAndOneThatStoppedIsNot() throws Exception {
        alive(NOW.minusSeconds(1));
        Files.writeString(
                ScreenFile.directory(state).resolve(ScreenFile.SCREEN),
                "Trust this folder? [y/N]\n");
        assertThat(ScreenFile.read(state, clock, LimitFactors.DEFAULT))
                .isEqualTo("Trust this folder? [y/N]\n");

        Files.delete(ScreenFile.directory(state).resolve(ScreenFile.SCREEN));
        assertThat(ScreenFile.read(state, clock, LimitFactors.DEFAULT))
                .as("no session drawn")
                .isEmpty();

        alive(NOW.minus(ScreenFile.FRESH).minusSeconds(1));
        assertThat(ScreenFile.read(state, clock, LimitFactors.DEFAULT))
                .as("a writer that stopped: ask the task")
                .isNull();
        assertThat(ScreenFile.read(state.resolve("never-made"), clock, LimitFactors.DEFAULT))
                .isNull();
    }

    @Test
    void aWriterLookingHalfAsOftenCountsAsStoppedOnlyAfterTwiceTheAge() throws Exception {
        // Seven seconds since its last look: past the five seconds at 1.0, within the ten at 2.0.
        alive(NOW.minusSeconds(7));
        Files.writeString(ScreenFile.directory(state).resolve(ScreenFile.SCREEN), "at work\n");

        assertThat(ScreenFile.read(state, clock, LimitFactors.DEFAULT))
                .as("stopped at the factor 1.0")
                .isNull();
        assertThat(ScreenFile.read(state, clock, LimitFactors.parse("polling=2")))
                .as("still looking at 2.0")
                .isEqualTo("at work\n");
        assertThat(ScreenFile.writer(LimitFactors.parse("polling=2")).getLast())
                .as("the writer it is judged by looks every two seconds")
                .contains("sleep 2;");
        assertThat(ScreenFile.writer(LimitFactors.DEFAULT).getLast())
                .as("and every second at 1.0")
                .contains("sleep 1;");
    }

    @Test
    void aLinkWhereTheScreenShouldBeIsNotFollowed() throws Exception {
        alive(NOW);
        final Path elsewhere =
                Files.writeString(state.resolve("elsewhere.txt"), "a file of the host's\n");
        Files.createSymbolicLink(ScreenFile.directory(state).resolve(ScreenFile.SCREEN), elsewhere);

        assertThat(ScreenFile.read(state, clock, LimitFactors.DEFAULT)).isEmpty();
    }

    @Test
    void theScreenIsCopiedFromPorelosServerWhenItChangesAndNoSessionIsSaid() throws Exception {

        // The root's writer, against a tmux that draws what a file says - and only when it is
        // asked on Porelo's own socket.
        final Path directory = ScreenFile.prepare(state);
        final Path bin = Files.createDirectories(state.resolve("bin"));
        final Path drawn = state.resolve("drawn.txt");
        Files.writeString(
                bin.resolve("tmux"),
                "#!/bin/sh\ncase \"$*\" in '-S /run/porelo-tmux/tmux capture-pane'*) ;; *) exit 1 ;;"
                        + " esac\n[ -s "
                        + drawn
                        + " ] || exit 1\ncat "
                        + drawn
                        + "\n");
        bin.resolve("tmux").toFile().setExecutable(true);
        Files.writeString(drawn, "stub: at work, nothing asked\n");
        final Process writer =
                start(ScreenFile.writerScript(directory.toString(), ScreenFile.LOOK), bin);
        try {
            final Path screen = directory.resolve(ScreenFile.SCREEN);
            waitFor(() -> Files.isRegularFile(screen));
            assertThat(Files.readString(screen)).isEqualTo("stub: at work, nothing asked\n");
            assertThat(directory.resolve(ScreenFile.ALIVE)).exists();

            Files.writeString(drawn, "Trust this folder? [y/N]\n");
            waitFor(() -> Files.readString(screen).startsWith("Trust"));

            Files.writeString(drawn, "");
            waitFor(() -> !Files.exists(screen));
        } finally {
            writer.destroy();
            writer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A tmux on Porelo's socket with a session whose output is what a file holds: its pipe runs the
     * command it is given over that output, as the server would, and says it is open from then on
     * until the test says the session is new.
     */
    private Path sessionTmux(final Path output, final Path piped) throws Exception {
        final Path bin = Files.createDirectories(state.resolve("bin"));
        Files.writeString(
                bin.resolve("tmux"),
                "#!/bin/sh\n[ \"$1 $2\" = '-S /run/porelo-tmux/tmux' ] || exit 1\ncase \"$3\" in\n"
                        + "  display-message) cat "
                        + piped
                        + " 2>/dev/null || echo 0 ;;\n"
                        + "  pipe-pane) echo 1 > "
                        + piped
                        + "; sh -c \"$6\" < "
                        + output
                        + " ;;\n"
                        + "  *) exit 1 ;;\nesac\n");
        bin.resolve("tmux").toFile().setExecutable(true);
        return bin;
    }

    /** The recording's file names, none while the writer makes it anew. */
    private static java.util.List<String> recorded(final Path recording) {
        try (java.util.stream.Stream<Path> files = Files.list(recording)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException ex) {
            return java.util.List.of();
        }
    }

    private static String content(final Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException ex) {
            return "";
        }
    }

    @Test
    void theSessionsOutputIsRecordedByTheRootAndCutAtItsBound() throws Exception {

        // A session that prints without end held the host's memory without end: the recording
        // keeps its newest files only, each named for where its bytes lie in the whole.
        final Path directory = ScreenFile.prepare(state);
        final Path output = state.resolve("output.bin");
        final Path piped = state.resolve("piped");
        final byte[] written = new byte[10 * ScreenFile.SEGMENT + 1000];
        for (int i = 0; i < written.length; i++) {
            written[i] = (byte) (i % 61 == 60 ? '\n' : 'a' + i % 26);
        }
        Files.write(output, written);
        final Process writer =
                start(
                        ScreenFile.writerScript(directory.toString(), ScreenFile.LOOK),
                        sessionTmux(output, piped));
        try {
            final Path recording = directory.resolve(ScreenFile.RECORDING);
            waitFor(
                    () ->
                            recorded(recording).size() == ScreenFile.KEPT
                                    && recorded(recording).getFirst().equals("aaaaaaad"));
            assertThat(recorded(recording))
                    .as("the oldest three of eleven are gone")
                    .containsExactly(
                            "aaaaaaad",
                            "aaaaaaae",
                            "aaaaaaaf",
                            "aaaaaaag",
                            "aaaaaaah",
                            "aaaaaaai",
                            "aaaaaaaj",
                            "aaaaaaak");
            final java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
            for (final String name : recorded(recording)) {
                kept.write(Files.readAllBytes(recording.resolve(name)));
            }
            assertThat(kept.toByteArray())
                    .isEqualTo(
                            java.util.Arrays.copyOfRange(
                                    written, 3 * ScreenFile.SEGMENT, written.length));

            // A new session's output is a recording of its own, never appended to the old one.
            Files.writeString(output, "new session\n");
            Files.writeString(piped, "0");
            waitFor(
                    () ->
                            recorded(recording).equals(java.util.List.of("aaaaaaaa"))
                                    && content(recording.resolve("aaaaaaaa"))
                                            .equals("new session\n"));
        } finally {
            writer.destroy();
            writer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static Process start(final String script, final Path bin) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", script)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (bin != null) {
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        }
        return builder.start();
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }

    private static void waitFor(final Check check) throws Exception {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.holds()) {
            assertThat(System.nanoTime()).as("waited 10 s").isLessThan(until);
            Thread.sleep(100);
        }
    }
}
