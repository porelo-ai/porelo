package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostServicesTest {
    private final List<String> events = new ArrayList<>();

    @Test
    void preparesBeforeAdvertisingTheListenerAndCleansUpAfterwards() throws IOException {
        // Advertising an unprepared listener silently skips the host egress checks.
        HostServices.around(
                events::add,
                environment -> {
                    assertThat(environment)
                            .as("only a prepared runner gets the capability")
                            .containsEntry("PORELO_ACCEPTANCE_HOST_SERVICES", "1");
                    events.add("suite");
                });
        assertThat(events)
                .as("root preparation surrounds the runner")
                .containsExactly(up(), "suite", down());
    }

    @Test
    void aFailedUpNeverRunsTheSuiteOrStopsSomeoneElsesUnit() {
        // A refused occupied port must neither advertise readiness nor tear down its owner.
        assertThatThrownBy(
                        () ->
                                HostServices.around(
                                        command -> {
                                            events.add(command);
                                            throw new IOException("occupied");
                                        },
                                        environment -> events.add("suite")))
                .as("preparation failure reaches the leg")
                .isInstanceOf(IOException.class)
                .hasMessage("occupied");
        assertThat(events)
                .as("failed preparation cannot launch or clean another unit")
                .containsExactly(up());
    }

    @Test
    void aFailedSuiteStillStopsTheListener() {
        // A red suite must not leave a root listener and signing material on a kept VM.
        assertThatThrownBy(
                        () ->
                                HostServices.around(
                                        events::add,
                                        environment -> {
                                            events.add("suite");
                                            throw new IOException("suite failed");
                                        }))
                .as("suite failure is preserved")
                .isInstanceOf(IOException.class)
                .hasMessage("suite failed");
        assertThat(events)
                .as("cleanup runs after a failure")
                .containsExactly(up(), "suite", down());
    }

    @Test
    void aCleanupFailureIsReportedWithoutHidingTheSuiteFailure() {
        // Losing either failure conceals the reason for a red run or a listener left running.
        assertThatThrownBy(
                        () ->
                                HostServices.around(
                                        command -> {
                                            events.add(command);
                                            if (command.equals(down()))
                                                throw new IOException("stop failed");
                                        },
                                        environment -> {
                                            throw new IOException("suite failed");
                                        }))
                .as("both failures reach the caller")
                .isInstanceOf(IOException.class)
                .hasMessage("suite failed")
                .satisfies(
                        failure ->
                                assertThat(failure.getSuppressed())
                                        .as("cleanup failure is retained")
                                        .extracting(Throwable::getMessage)
                                        .containsExactly("stop failed"));
    }

    @Test
    void everyAccountCountRunsHostServicesBeforeRestarts() {
        // A reboot before the feature removes its transient listener and invalidates readiness.
        for (List<String> users : List.of(List.of("build"), List.of("build", "accept2"))) {
            final List<List<String>> passes = Leg.suitePasses(users);
            assertThat(passes).as("two passes for " + users).hasSize(2);
            assertThat(passes.getFirst())
                    .as("ordinary features first")
                    .contains("-Dcucumber.filter.tags=not @restart");
            assertThat(passes.getLast())
                    .as("restart scenarios last with a separate report")
                    .contains(
                            "-Dcucumber.filter.tags=@restart",
                            "-Dporelo.acceptance.report=" + Leg.RESTART_REPORT);
        }
    }

    @Test
    void readinessReachesOnlyTheSuiteProcess() throws IOException {
        // Setting readiness in the wrong process silently skips the host egress scenarios.
        HostServices.around(
                events::add,
                environment -> {
                    final ProcessBuilder runner =
                            Leg.suiteRunner(
                                    java.nio.file.Path.of("."), List.of("./mvnw"), environment);
                    assertThat(runner.environment())
                            .as("the launched runner inherits readiness")
                            .containsEntry("PORELO_ACCEPTANCE_HOST_SERVICES", "1");
                });
    }

    private static String up() {
        return "bash '/home/build/porelo/" + HostServices.SCRIPT + "' up";
    }

    private static String down() {
        return "bash '/home/build/porelo/" + HostServices.SCRIPT + "' down";
    }
}
