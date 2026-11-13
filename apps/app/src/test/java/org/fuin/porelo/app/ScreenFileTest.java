package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
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
        assertThat(ScreenFile.read(state, clock)).isEqualTo("Trust this folder? [y/N]\n");

        Files.delete(ScreenFile.directory(state).resolve(ScreenFile.SCREEN));
        assertThat(ScreenFile.read(state, clock)).as("no session drawn").isEmpty();

        alive(NOW.minus(ScreenFile.FRESH).minusSeconds(1));
        assertThat(ScreenFile.read(state, clock))
                .as("a writer that stopped: ask the task")
                .isNull();
        assertThat(ScreenFile.read(state.resolve("never-made"), clock)).isNull();
    }

    @Test
    void aLinkWhereTheScreenShouldBeIsNotFollowed() throws Exception {
        alive(NOW);
        final Path elsewhere =
                Files.writeString(state.resolve("elsewhere.txt"), "a file of the host's\n");
        Files.createSymbolicLink(ScreenFile.directory(state).resolve(ScreenFile.SCREEN), elsewhere);

        assertThat(ScreenFile.read(state, clock)).isEmpty();
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
        final Process writer = start(ScreenFile.writerScript(directory.toString()), bin);
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
