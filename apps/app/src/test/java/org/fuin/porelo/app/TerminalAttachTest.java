package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for returning a person's terminal after a task session. */
class TerminalAttachTest {

    static final String RESETS =
            "\033[?1000l\033[?1002l\033[?1003l\033[?1006l\033[?1015l"
                    + "\033[?1004l\033[?2004l\033[>4;0m\033[?1049l\033[?25h";

    private static final String SAVED = "500:5:bf:8a3b:3:1c:7f:15:4:0:1:0:11:13";

    private static final List<String> ATTACH =
            List.of("podman", "exec", "--interactive", "--tty", "box", "tmux");

    private final FakeCommandRunner runner =
            new FakeCommandRunner().answering("stty -g", SAVED + "\n");

    private final List<String> events = new ArrayList<>();

    private final FlushedWriter out = new FlushedWriter();

    private final StringWriter err = new StringWriter();

    private int exit;

    private @Nullable RuntimeException failure;

    private boolean interrupted;

    private @Nullable Process process;

    /** Builds a terminal whose processes and output are all owned by the test. */
    private PoreloContext context(Path dir) {
        return new PoreloContext(
                command -> {
                    events.add(command.arguments().contains(SAVED) ? "restore" : "save");
                    assertThat(Thread.currentThread().isInterrupted())
                            .as("stty can finish even after the attach was interrupted")
                            .isFalse();
                    return runner.run(command);
                },
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                arguments -> {
                    assertThat(arguments)
                            .as("the attach's arguments are unchanged")
                            .isEqualTo(ATTACH);
                    events.add("attach");
                    out.write("session output");
                    if (process != null) {
                        return PoreloContext.waitInTerminal(process);
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (failure != null) {
                        throw failure;
                    }
                    return exit;
                });
    }

    /** Attaches with the test's terminal and output streams. */
    private int attach(Path dir, boolean terminal) {
        return TerminalAttach.run(
                context(dir), ATTACH, terminal, new PrintWriter(out), new PrintWriter(err));
    }

    /** Checks the saved value, terminal device, cleanup order and flushed terminal modes. */
    private void restored() {
        assertThat(events)
                .as("settings surround the attach")
                .containsExactly("save", "attach", "restore");
        assertThat(runner.invocations().getFirst().arguments())
                .as("stty reads the controlling terminal")
                .contains("stty -g < /dev/tty");
        assertThat(runner.invocations().getLast().arguments())
                .as("the exact saved settings are passed as one argument, never shell text")
                .contains("stty \"$1\" < /dev/tty")
                .endsWith(SAVED);
        assertThat(out.toString())
                .as("all task terminal modes are reset after its output")
                .isEqualTo("session output" + RESETS);
        assertThat(out.flushed)
                .as("the reset reaches the terminal before returning")
                .isEqualTo(out.toString());
    }

    /** Both a normal detach and a failed podman restore the saved terminal. */
    @ParameterizedTest
    @ValueSource(ints = {0, 125})
    void restoresAfterEitherExitCode(int code, @TempDir Path dir) {
        exit = code;

        assertThat(attach(dir, true)).as("cleanup preserves podman's exit code").isEqualTo(code);

        restored();
        assertThat(err.toString()).as("successful cleanup says nothing").isEmpty();
    }

    /** An exception still returns terminal settings and modes to the caller. */
    @Test
    void restoresAfterAnException(@TempDir Path dir) {
        failure = new IllegalStateException("the session died");

        assertThatThrownBy(() -> attach(dir, true))
                .as("cleanup preserves the attach failure")
                .isSameAs(failure);

        restored();
    }

    /** Cleanup can wait for stty without consuming the caller's interrupt. */
    @Test
    void restoresAfterInterruptionWithoutLosingTheInterrupt(@TempDir Path dir) {
        interrupted = true;
        exit = 130;

        try {
            assertThat(attach(dir, true))
                    .as("the interrupted attach keeps its exit code")
                    .isEqualTo(130);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("cleanup leaves the interrupt for the caller")
                    .isTrue();
            restored();
        } finally {
            Thread.interrupted();
        }
    }

    /** The inherited-I/O child must finish before cleanup writes back to its terminal. */
    @Test
    void waitsForTheInterruptedChildToExitBeforeRestoring(@TempDir Path dir) {
        process = new InterruptedProcess(events);

        try {
            assertThat(attach(dir, true))
                    .as("an interrupted wait returns the signal exit code")
                    .isEqualTo(130);
            assertThat(events)
                    .as("the child cannot write terminal modes after the restore")
                    .containsExactly(
                            "save", "attach", "wait", "destroy", "wait", "exited", "restore");
            assertThat(out.flushed)
                    .as("the reset is flushed after the child has exited")
                    .isEqualTo("session output" + RESETS);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("waiting for the child preserves the interruption")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** A script gets neither stty invocations nor terminal control output. */
    @Test
    void runsNoTerminalCommandsAndWritesNoResetsWithoutATerminal(@TempDir Path dir) {
        assertThat(attach(dir, false)).as("the command still returns its exit code").isZero();

        assertThat(runner.invocations()).as("no stty is run for a script").isEmpty();
        assertThat(events).as("only the requested command runs").containsExactly("attach");
        assertThat(out.toString())
                .as("no terminal controls are added to a script's output")
                .isEqualTo("session output");
        assertThat(out.flushed).as("no cleanup writes or flushes a script's output").isEmpty();
        assertThat(err.toString()).as("no terminal diagnostic is written").isEmpty();
    }

    /** A failing restore still resets modes and keeps the attach's exit status. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRestoreFailureKeepsTheExitCodeAndStillResets(boolean throwsException, @TempDir Path dir) {
        if (throwsException) {
            runner.timingOut("stty \"$1\"");
        } else {
            runner.failing("stty \"$1\"", 1, "restore failed\nprivate detail\n");
        }
        exit = 125;

        assertThat(attach(dir, true))
                .as("a restore failure is not an attach failure")
                .isEqualTo(125);

        restored();
        assertThat(err.toString())
                .as("cleanup reports at most one line, with no trace or child stderr")
                .isEqualTo("porelo: could not restore terminal settings" + System.lineSeparator());
    }

    /** A cleanup exception cannot hide the original attach exception. */
    @Test
    void aRestoreExceptionNeverReplacesTheAttachException(@TempDir Path dir) {
        failure = new IllegalStateException("the session died");
        runner.timingOut("stty \"$1\"");

        assertThatThrownBy(() -> attach(dir, true))
                .as("the original exception is the attach's outcome")
                .isSameAs(failure);

        restored();
    }

    /** Settings that cannot be saved do not keep a person out of the task. */
    @ParameterizedTest
    @ValueSource(strings = {"nonzero", "exception", "empty"})
    void attachesWithoutRestoringWhenSettingsCannotBeSaved(String ending, @TempDir Path dir) {
        if ("exception".equals(ending)) {
            runner.timingOut("stty -g");
        } else if ("nonzero".equals(ending)) {
            runner.failing("stty -g", 1, "no terminal");
        } else {
            runner.answering("stty -g", "\n");
        }
        exit = 3;

        assertThat(attach(dir, true)).as("the attach runs and keeps its exit code").isEqualTo(3);

        assertThat(events)
                .as("unknown settings are never restored")
                .containsExactly("save", "attach");
        assertThat(out.flushed)
                .as("the modes are still reset after the session")
                .isEqualTo("session output" + RESETS);
        assertThat(err.toString())
                .as("one line says so, without the child's own output")
                .isEqualTo(
                        "porelo: could not save terminal settings; they are not restored afterwards"
                                + System.lineSeparator());
    }

    /** Records what reached the terminal through an explicit flush. */
    private static final class FlushedWriter extends StringWriter {
        private String flushed = "";

        /** Records the output visible to a person. */
        @Override
        public void flush() {
            flushed = toString();
        }
    }

    /** Models a terminal child whose first wait is interrupted before it has exited. */
    private static final class InterruptedProcess extends Process {
        private final List<String> events;

        private boolean destroyed;

        /** Uses the same event log as the attach and its terminal settings. */
        private InterruptedProcess(List<String> events) {
            this.events = events;
        }

        /** No process input is needed for a lifecycle test. */
        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        /** No process output is needed for a lifecycle test. */
        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        /** No process error output is needed for a lifecycle test. */
        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        /** The child exits only after destruction and a second wait. */
        @Override
        public int waitFor() throws InterruptedException {
            events.add("wait");
            if (!destroyed) {
                throw new InterruptedException("the terminal wait was interrupted");
            }
            events.add("exited");
            return 143;
        }

        /** Returns the child's status only after it exited. */
        @Override
        public int exitValue() {
            if (!events.contains("exited")) {
                throw new IllegalThreadStateException("the child still has the terminal");
            }
            return 143;
        }

        /** Destruction requests termination; it does not replace waiting for the child. */
        @Override
        public void destroy() {
            events.add("destroy");
            destroyed = true;
        }
    }
}
