package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests the time limit every command the kit runs on a machine is given, with a local shell standing in for the
 * machine's.
 */
class MachineLimitTest {

    @Test
    void aCommandThatHangsIsStoppedOnTheMachineAtItsLimit() {

        // A remote command had no limit: one hung 'podman exec' waited for the job's limit, and the job lost every
        // report of the scenarios that had already run.
        final String limited = Machine.limited("sleep 60", Duration.ofSeconds(1));

        final int exit = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            final Process shell = new ProcessBuilder("sh", "-c", limited).redirectErrorStream(true).start();
            return shell.waitFor();
        });

        assertThat(exit).isEqualTo(124);
    }

    @Test
    void theLimitIsLongerThanAnyScenarioStepTakes() {
        assertThat(Machine.COMMAND_LIMIT).isGreaterThanOrEqualTo(Duration.ofMinutes(20));
        assertThat(Machine.limited("true", Machine.COMMAND_LIMIT)).startsWith("timeout -k 10 ");
    }
}
