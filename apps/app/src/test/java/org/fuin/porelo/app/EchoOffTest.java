package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for holding a terminal's echo off across many reads. */
class EchoOffTest {

    private static final String SAVED = "500:5:bf:8a3b:3:1c:7f:15:4:0:1:0:11:13";

    /** Names a stty call by what it does. */
    private static String said(final org.fuin.porelo.core.process.Command command) {
        final String line = command.describe();
        if (line.contains("stty -g")) {
            return "save";
        }
        if (line.contains("stty -echo")) {
            return "echo off";
        }
        return line.contains("porelo-terminal") && command.arguments().contains(SAVED)
                ? "restore"
                : line;
    }

    /** A terminal recording what was asked of it, the save answering {@link #SAVED}. */
    private static CommandRunner recording(
            final List<String> events, final FakeCommandRunner fake) {
        return command -> {
            events.add(said(command));
            return fake.run(command);
        };
    }

    @Test
    void holdsEchoOffUntilClosedAndGivesTheSavedSettingsBack() {

        final List<String> events = new ArrayList<>();
        try (EchoOff held =
                EchoOff.hold(
                        recording(events, new FakeCommandRunner().answering("stty -g", SAVED)))) {
            events.add("read");
        }

        assertThat(events).containsExactly("save", "echo off", "read", "restore");
    }

    @Test
    void aTerminalThatCannotBeSavedIsLeftAlone() {

        // Changing what cannot be put back would leave a terminal without echo for good.
        final List<String> events = new ArrayList<>();
        try (EchoOff held =
                EchoOff.hold(
                        recording(
                                events,
                                new FakeCommandRunner().failing("stty -g", 1, "not a tty")))) {
            events.add("read");
        }

        assertThat(events).containsExactly("save", "read");
    }

    @Test
    void aFailedSwitchGivesTheSavedSettingsBackAtOnce() {

        final List<String> events = new ArrayList<>();
        try (EchoOff held =
                EchoOff.hold(
                        recording(
                                events,
                                new FakeCommandRunner()
                                        .answering("stty -g", SAVED)
                                        .failing("stty -echo", 1, "refused")))) {
            events.add("read");
        }

        assertThat(events).containsExactly("save", "echo off", "restore", "read");
    }

    /**
     * Holds echo off and waits to be stopped, writing each stty call it is asked for to the file
     * named first: what a {@code vault put} waiting on a paste is when Ctrl-C reaches it.
     */
    static final class Waiting {

        private Waiting() {}

        public static void main(final String[] arguments) throws Exception {
            final Path log = Path.of(arguments[0]);
            final CommandRunner runner =
                    command -> {
                        write(log, said(command));
                        return new CommandResult(command, 0, SAVED + "\n", "");
                    };
            EchoOff.hold(runner);
            write(log, "holding");
            Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        }

        private static void write(final Path log, final String line) {
            try {
                Files.writeString(
                        log,
                        line + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (final IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    @Test
    void ctrlCGivesTheTerminalBackItsEcho(@TempDir final Path dir) throws Exception {

        // Ctrl-C ends the process without the finally that closes the hold; a terminal left
        // without echo looks broken to whoever types next. Measured with a real SIGINT.
        final Path log = dir.resolve("stty.log");
        final Process child =
                new ProcessBuilder(
                                ProcessHandle.current().info().command().orElse("java"),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Waiting.class.getName(),
                                log.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(dir.resolve("child.out").toFile())
                        .start();
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!(Files.exists(log) && Files.readString(log).contains("holding"))) {
                assertThat(child.isAlive()).as("the child is still holding").isTrue();
                assertThat(System.nanoTime() < deadline).as("the child held within 30 s").isTrue();
                Thread.sleep(50);
            }
            final Process kill =
                    new ProcessBuilder("kill", "-INT", Long.toString(child.pid())).start();
            assertThat(kill.waitFor()).as("kill -INT").isZero();
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).as("the child ended").isTrue();
        } finally {
            child.destroyForcibly();
        }

        assertThat(Files.readAllLines(log))
                .containsExactly("save", "echo off", "holding", "restore");
    }
}
