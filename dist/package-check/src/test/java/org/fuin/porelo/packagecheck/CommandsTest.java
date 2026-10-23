package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests how the package check runs the package managers' tools. */
class CommandsTest {

    @Test
    void aTimeoutKeepsTheCommandsLastLinesAtTheEndOfMergedOutput() {
        final Commands.Result result =
                Commands.runMerged(
                        List.of("sh", "-c", "printf 'last archive line\\n'; exec sleep 60"),
                        Duration.ofSeconds(1));

        assertThat(result.exit()).as("the bounded process was ended").isEqualTo(Commands.TIMED_OUT);
        assertThat(result.output())
                .as("timeout diagnostics cannot bury the last apt output under the whole script")
                .endsWith("last archive line\n");
    }

    @Test
    void aCommandThatDoesNotEndIsEndedAtItsLimitAndSaysSoSoTheChecksAfterItRun() {

        // Without a limit a hung image pull or install held the step until the job's own limit, and
        // the report of every check after it was lost with the job.
        final List<List<String>> hung = List.of(List.of("sh", "-c", "echo started; sleep 60"));
        final Commands.Result result =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(20),
                        () -> Commands.pipe(hung, true, Duration.ofSeconds(1)));

        assertThat(result.ok()).as("a timed-out command fails").isFalse();
        assertThat(result.exit())
                .as("a timeout has a distinct outcome")
                .isEqualTo(Commands.TIMED_OUT);
        assertThat(result.output())
                .as("a timeout retains output and explains its limit")
                .contains("started")
                .contains("did not end within 1 s");
    }

    @Test
    void aPipeThatEndsInTimeAnswersAsBefore() {
        final Commands.Result result =
                Commands.pipe(
                        List.of(List.of("printf", "a\\nb\\n"), List.of("grep", "b")),
                        false,
                        Duration.ofSeconds(10));

        assertThat(result.ok()).as("a completed pipeline succeeds").isTrue();
        assertThat(result.output()).as("the last command supplies the output").isEqualTo("b\n");
    }

    @Test
    void theLimitIsWellAboveWhatTheWholeCheckTakes() {
        // The whole step took 109 to 133 seconds in four green builds (2026-10-17), four installs
        // among its commands.
        assertThat(Commands.LIMIT)
                .as("ordinary commands retain their five minute allowance")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(5));
    }
}
