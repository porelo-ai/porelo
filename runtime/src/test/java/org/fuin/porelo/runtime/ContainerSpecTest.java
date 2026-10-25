package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.core.project.Limits;
import org.junit.jupiter.api.Test;

/** Tests for {@link ContainerSpec}, covering the flags that are not negotiable. */
class ContainerSpecTest {

    private List<String> arguments() {
        return new ContainerSpec("box", "ubuntu:24.04").command("sleep", "infinity").toArguments();
    }

    @Test
    void boundsCpuAndWorkerPoolsByDefault() {
        final ContainerSpec specification = new ContainerSpec("box", "ubuntu:24.04");
        assertThat(specification.toArguments())
                .as("a large host must not grow a task's worker pools past its memory and pids")
                .containsSequence("--cpus", "4")
                .containsSequence("--env-merge", "OMP_NUM_THREADS=${OMP_NUM_THREADS:-4}");
    }

    @Test
    void leavesCpuAndWorkerPoolsAloneWhenTheProjectOptsOut() {
        final ContainerSpec specification =
                new ContainerSpec("box", "ubuntu:24.04")
                        .limits(
                                new Limits.Declared(null, "none", null, null)
                                        .over(Limits.defaults()));
        assertThat(specification.toArguments())
                .as("opting out must remove both the quota and the worker hint")
                .doesNotContain("--cpus", "--env-merge", "OMP_NUM_THREADS");
        assertThat(specification.environment())
                .as("no hidden worker hint")
                .doesNotContainKey("OMP_NUM_THREADS");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2.5, 2", "0.5, 1"})
    void roundsWorkerPoolsDownButAlwaysLeavesOneThread(String cpus, String threads) {
        final ContainerSpec specification =
                new ContainerSpec("box", "ubuntu:24.04")
                        .limits(new Limits("8g", cpus, 2048, Limits.DEFAULT_HAND_IN));
        assertThat(specification.toArguments())
                .as("fractional quotas must not round worker pools up or leave zero workers")
                .containsSequence("--cpus", cpus)
                .containsSequence(
                        "--env-merge", "OMP_NUM_THREADS=${OMP_NUM_THREADS:-" + threads + "}");
    }

    @Test
    void keepsTheImagesThreadSettingAsTheDefault() {
        assertThat(arguments())
                .as("Podman must keep the image's nonempty setting instead of overwriting it")
                .containsSequence("--env-merge", "OMP_NUM_THREADS=${OMP_NUM_THREADS:-4}")
                .doesNotContain("OMP_NUM_THREADS=4", "OMP_NUM_THREADS");
    }

    @Test
    void keepsTheProjectsThreadSettingRegardlessOfSetterOrder() {
        for (boolean environmentFirst : List.of(true, false)) {
            final ContainerSpec specification = new ContainerSpec("box", "ubuntu:24.04");
            if (environmentFirst) {
                specification.environment("OMP_NUM_THREADS", "7");
            }
            specification.limits(new Limits("8g", "2.5", 2048, Limits.DEFAULT_HAND_IN));
            if (!environmentFirst) {
                specification.environment("OMP_NUM_THREADS", "7");
            }
            assertThat(specification.toArguments())
                    .as("the project's explicit hint wins")
                    .containsSequence("--env", "OMP_NUM_THREADS")
                    .doesNotContain("--env-merge");
            assertThat(specification.environment())
                    .as("names and values come from the same spec")
                    .containsEntry("OMP_NUM_THREADS", "7");
        }
    }

    @Test
    void alwaysDropsPrivileges() {
        assertThat(arguments())
                .containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--cap-drop", "ALL");
    }

    @Test
    void namesAVariableOnTheCommandLineAndCarriesItsValueElsewhere() {

        // An argument list is world-readable - /proc/<pid>/cmdline is mode 444 and neither
        // supported distribution mounts /proc with hidepid, measured - and an environment is not.
        // Podman copies the value from its own environment when it is given only the name.
        final ContainerSpec specification =
                new ContainerSpec("box", "ubuntu:24.04")
                        .environment("PORELO_TOKEN", "porelo_pt_a_secret_value");

        assertThat(specification.toArguments()).containsSequence("--env", "PORELO_TOKEN");
        assertThat(specification.toArguments())
                .noneMatch(argument -> argument.contains("porelo_pt_a_secret_value"));
        assertThat(specification.environment())
                .containsEntry("PORELO_TOKEN", "porelo_pt_a_secret_value");
    }

    @Test
    void neverPutsAValueBesideAVariableName() {

        // The rule rather than one instance of it: no --env argument may carry a value, whatever is
        // added here later.
        final List<String> arguments =
                new ContainerSpec("box", "ubuntu:24.04")
                        .environment("ONE", "first")
                        .environment("TWO", "second")
                        .toArguments();

        for (int i = 0; i < arguments.size() - 1; i++) {
            if ("--env".equals(arguments.get(i))) {
                assertThat(arguments.get(i + 1)).doesNotContain("=");
            }
        }
        assertThat(arguments).contains("--env");
    }

    @Test
    void alwaysAddsAnInitProcess() {

        // PID 1 is 'sleep infinity', which never calls wait(), so an orphan becomes a zombie
        // holding a PID for the life of the container.
        assertThat(arguments()).contains("--init");
    }

    @Test
    void limitsMemoryAndProcessesWithoutBeingAsked() {
        assertThat(arguments())
                .containsSequence("--memory", Limits.DEFAULT_MEMORY)
                .containsSequence("--pids-limit", String.valueOf(Limits.DEFAULT_PIDS));
    }

    @Test
    void passesNoMemoryFlagWhenTheProjectOptedOut() {

        // The negative case: an empty value would be passed to podman as a limit of nothing.
        final List<String> arguments =
                new ContainerSpec("box", "ubuntu:24.04")
                        .limits(new Limits(null, null, 4096, Limits.DEFAULT_HAND_IN))
                        .toArguments();

        assertThat(arguments).doesNotContain("--memory");
        assertThat(arguments).containsSequence("--pids-limit", "4096");
    }

    @Test
    void carriesTheProjectsOwnLimits() {
        assertThat(
                        new ContainerSpec("box", "ubuntu:24.04")
                                .limits(new Limits("2g", "1.5", 512, Limits.DEFAULT_HAND_IN))
                                .toArguments())
                .containsSequence("--memory", "2g")
                .containsSequence("--cpus", "1.5")
                .containsSequence("--pids-limit", "512");
    }

    @Test
    void givesATaskWithItsOwnResolverNoSearchDomainOfTheHost() {

        // The host's search line was copied into the task: it learned internal domain names, and a
        // refused name was asked once per domain.
        assertThat(new ContainerSpec("box", "ubuntu:24.04").resolver("10.88.0.53").toArguments())
                .containsSequence("--dns", "10.88.0.53")
                .contains("--dns-search=.");
        assertThat(arguments())
                .as("no resolver of its own, nothing about DNS")
                .doesNotContain("--dns-search=.");
    }
}
