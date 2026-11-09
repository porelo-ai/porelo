package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.TestPrograms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class HostProgramsTest {
    @TempDir Path directory;

    private HostPrograms runner() {
        return runner(Duration.ofSeconds(10), 65536);
    }

    private HostPrograms runner(Duration timeout, int maximumBytes) {
        return new HostPrograms(timeout, maximumBytes, TestPrograms::find);
    }

    @Test
    void aCheckedHarmlessProgramRunsByNameOrAbsolutePath() throws Exception {
        assertThat(runner().run(List.of("true")))
                .as("distribution programs are found without the caller's PATH")
                .isEqualTo(new HostPrograms.Result(0, ""));
        assertThat(runner().run(List.of(TestPrograms.find("true").toString())).exitCode())
                .as("an absolute program path is checked too")
                .isZero();
    }

    @Test
    void aChildReceivesExactlyTheFixedEnvironment() throws Exception {
        final HostPrograms.Result result = runner().run(List.of("env"));
        assertThat(result.output().lines())
                .as("no caller variable reaches child programs")
                .containsExactlyInAnyOrder("PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C.UTF-8");
    }

    @Test
    void aWarningOnStandardErrorIsNoPartOfTheAnswer() throws Exception {
        // getent through an NSS module may warn on stderr; the answer is stdout alone.
        assertThat(runner().run(List.of("sh", "-c", "echo answer; echo warning >&2")).output())
                .isEqualTo("answer\n");
    }

    @Test
    void argumentsArePassedLiterallyWithoutAShell() throws Exception {
        final String argument = "$(id); * 'quoted' $HOME";
        assertThat(runner().run(List.of("echo", argument)).output())
                .as("shell metacharacters cannot execute a second program")
                .isEqualTo(argument + "\n");
    }

    @Test
    void theChildDoesNotInheritTheCallersWorkingDirectory() throws Exception {
        assertThat(runner().run(List.of("pwd")).output())
                .as("a caller directory cannot supply relative configuration")
                .isEqualTo("/\n");
    }

    @Test
    void theChildCannotWaitForInputFromTheCaller() throws Exception {
        assertThat(runner().run(List.of("cat")).output())
                .as("stdin is closed instead of inherited")
                .isEmpty();
    }

    @Test
    void aNonzeroExitIsPreserved() throws Exception {
        assertThat(runner().run(List.of("false")).exitCode())
                .as("a failed program must not appear successful")
                .isEqualTo(1);
    }

    @Test
    void aLongRunningProgramIsStoppedAtItsTimeout() {
        // A dependency must not hold the privileged invocation open forever.
        assertThatThrownBy(() -> runner(Duration.ofMillis(100), 100).run(List.of("sleep", "30")))
                .as("the execution deadline bounds a child that prints nothing")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void excessiveOutputIsRefusedRatherThanTruncatedOrRetained() {
        // A noisy program must not exhaust memory or return a partial account record.
        assertThatThrownBy(() -> runner(Duration.ofSeconds(2), 100).run(List.of("yes")))
                .as("output limits apply while the child is still running")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void outputExactlyAtTheLimitIsComplete() throws Exception {
        assertThat(runner(Duration.ofSeconds(2), 4).run(List.of("echo", "abc")).output())
                .as("the limit includes the newline and accepts complete output")
                .isEqualTo("abc\n");
    }

    @Test
    void aCallerOwnedExecutableIsRefused() throws Exception {
        // A path must not bypass root ownership checks.
        final Path file = Files.writeString(directory.resolve("program"), "untrusted");
        assertThatThrownBy(() -> new HostPrograms().run(List.of(file.toString())))
                .as("the runner checks absolute paths through SystemProgram")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Refusing");
    }

    @Test
    void aMissingProgramDoesNotFallBackToTheCallersPath() {
        // A missing tool must not run an attacker-supplied PATH substitute.
        assertThatThrownBy(() -> new HostPrograms().run(List.of("porelo-host-missing-test-tool")))
                .as("missing distribution tools fail closed")
                .isInstanceOf(IOException.class);
    }

    @Test
    void interruptionIsPreservedAndTheChildIsStopped() {
        // Cancellation must not leave the host program waiting on a child.
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> runner().run(List.of("sleep", "30")))
                    .as("interruption ends a running child")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller retains cancellation")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
