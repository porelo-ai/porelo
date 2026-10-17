package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests how the package check runs the package managers' tools. */
class CommandsTest {

    @Test
    void aCommandThatDoesNotEndIsEndedAtItsLimitAndSaysSoSoTheChecksAfterItRun() {

        // Without a limit a hung image pull or install held the step until the job's own limit, and
        // the report of every check after it was lost with the job.
        final List<List<String>> hung = List.of(List.of("sh", "-c", "echo started; sleep 60"));
        final Commands.Result result =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(20),
                        () -> Commands.pipe(hung, true, Duration.ofSeconds(1)));

        assertThat(result.ok()).isFalse();
        assertThat(result.exit()).isEqualTo(Commands.TIMED_OUT);
        assertThat(result.output()).contains("started").contains("did not end within 1 s");
    }

    @Test
    void aPipeThatEndsInTimeAnswersAsBefore() {
        final Commands.Result result =
                Commands.pipe(
                        List.of(List.of("printf", "a\\nb\\n"), List.of("grep", "b")),
                        false,
                        Duration.ofSeconds(10));

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("b\n");
    }

    @Test
    void theLimitIsWellAboveWhatTheWholeCheckTakes() {
        // The whole step took 109 to 133 seconds in four green builds (2026-10-17), four installs
        // among its commands.
        assertThat(Commands.LIMIT).isGreaterThanOrEqualTo(Duration.ofMinutes(5));
    }
}
