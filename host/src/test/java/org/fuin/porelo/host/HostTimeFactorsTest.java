package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** porelo-host's limits are their defaults times the factors its host configuration names. */
class HostTimeFactorsTest {
    @TempDir Path root;

    private ProjectFiles files() throws IOException {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        return new ProjectFiles(root, (int) Files.getAttribute(root, "unix:uid"));
    }

    private void privateFile(String name) throws IOException {
        final Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        for (Path parent = path.getParent();
                parent.startsWith(root);
                parent = java.util.Objects.requireNonNull(parent.getParent())) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            if (parent.equals(root)) break;
        }
        Files.writeString(path, "");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT, 10", "services=2.0, 20", "machines=2.0, 10"})
    void theSshConnectToCentralFollowsTheServicesFactor(String line, int seconds)
            throws IOException {
        final ProjectFiles files = files();
        privateFile("etc/porelo-host/organization/known_hosts");
        privateFile("keys/alpha");
        final ProjectRuntime runtime =
                new ProjectRuntime(
                                files,
                                (argv, input, environment, timeout) -> {
                                    throw new AssertionError("ssh options start no program");
                                },
                                Path::of)
                        .limited(factors(line));
        final Map<String, @Nullable Object> item = Map.of("name", "alpha");
        assertThat(new ProjectReadKeys(files, runtime).ssh(root.resolve("keys"), item))
                .contains("ConnectTimeout=" + seconds + " ");
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT, 60", "machines=2.0, 120", "services=2.0, 60"})
    void aMaintenanceCommandFollowsTheMachinesFactor(String line, int seconds) throws IOException {
        final List<Duration> timeouts = new ArrayList<>();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                                files(),
                                (argv, input, environment, timeout) -> {
                                    timeouts.add(timeout);
                                    return new HostPrograms.Result(0, "");
                                },
                                Path::of)
                        .limited(factors(line));
        runtime.command(List.of("/usr/bin/true"));
        assertThat(timeouts).containsExactly(Duration.ofSeconds(seconds));
    }

    @Test
    void theUnbudgetedFormKeepsTheFactors() throws IOException {
        final List<Duration> timeouts = new ArrayList<>();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                                files(),
                                (argv, input, environment, timeout) -> {
                                    timeouts.add(timeout);
                                    return new HostPrograms.Result(0, "");
                                },
                                Path::of)
                        .limited(LimitFactors.parse("machines=2.0"));
        final var clock = java.time.Clock.systemUTC();
        runtime.until(clock, clock.instant().plusSeconds(3600))
                .unbudgeted()
                .command(List.of("/usr/bin/true"));
        assertThat(timeouts).containsExactly(Duration.ofSeconds(120));
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT, 3000", "polling=2.0, 6000", "machines=2.0, 3000"})
    void theLeaseWaitLooksAgainAfterThePollingFactor(String line, long millis) {
        assertThat(PoolMachines.leasePoll(factors(line))).isEqualTo(Duration.ofMillis(millis));
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT, 10", "machines=2.0, 20", "polling=2.0, 10"})
    void aHostProgramWithoutItsOwnLimitFollowsTheMachinesFactor(String line, int seconds) {
        assertThat(HostPrograms.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        final var limits = factors(line);
        assertThat(limits.machines(HostPrograms.TIMEOUT)).isEqualTo(Duration.ofSeconds(seconds));
        final HostPrograms programs = new HostPrograms(limits);
        assertThat(programs).extracting("timeout").isEqualTo(Duration.ofSeconds(seconds));
    }

    // The defaults are written DEFAULT and handed as their line, since Porelo never hands an
    // empty one.
    private static LimitFactors factors(String line) {
        return LimitFactors.parse(line.equals("DEFAULT") ? LimitFactors.DEFAULT.line() : line);
    }
}
