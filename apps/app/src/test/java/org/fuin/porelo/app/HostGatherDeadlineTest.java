package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.fuin.porelo.core.process.Command;
import org.junit.jupiter.api.Test;

class HostGatherDeadlineTest {
    @Test
    void disconnectedWindowStopsTheOwnedProcess() {
        // A disconnected caller must not leave an owned process running in the daemon indefinitely.
        try (var runner = new HostGatherRunner(Duration.ofSeconds(2), () -> false)) {
            final var error =
                    assertThrows(
                            HostGatherRefusal.class,
                            () ->
                                    runner.run(
                                            Command.of("/usr/bin/sleep", "0.2")
                                                    .withChosenEnvironment(java.util.Map.of())));
            assertThat(error.kind()).isEqualTo(HostGatherRefusal.Kind.CANCELLED);
        }
    }

    @Test
    void deadlineStopsAProcessThatNeverAnswers() {
        // A silent remote endpoint must not hold a daemon connection and its credentials forever.
        try (var runner = new HostGatherRunner(Duration.ofMillis(50), () -> true)) {
            assertThrows(
                    HostGatherRefusal.class,
                    () ->
                            runner.run(
                                    Command.of("/usr/bin/sleep", "0.2")
                                            .withChosenEnvironment(java.util.Map.of())));
        }
    }
}
