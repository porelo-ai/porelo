package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoolMachinesTest {
    @TempDir Path root;
    private final List<List<String>> calls = new ArrayList<>();
    private String failing = "";
    private boolean leftBehind;
    private boolean existing;
    private boolean isoTimesOut;
    private String state = "running";
    private String lease = "";
    private boolean deletionTimesOut;
    private boolean censusFails;
    private long nanos;

    private PoolMachines machine() {
        final var files = new ProjectFiles(root, owner());
        final var runtime =
                new ProjectRuntime(
                        files,
                        (argv, input, environment, timeout) -> {
                            calls.add(argv);
                            if (argv.contains("domstate"))
                                return new HostPrograms.Result(0, state + "\n");
                            if (argv.contains("domifaddr")) {
                                assertThat(timeout)
                                        .isLessThanOrEqualTo(java.time.Duration.ofSeconds(300));
                                return new HostPrograms.Result(0, lease);
                            }
                            if (deletionTimesOut && argv.contains("destroy"))
                                throw new IOException("destroy timed out");
                            if (censusFails && argv.contains("vol-list"))
                                return new HostPrograms.Result(1, "");
                            assertThat(environment)
                                    .as(
                                            "import uses the system libvirt URI and a fixed environment")
                                    .isEqualTo(HostPool.ENVIRONMENT);
                            if (argv.getFirst().equals("xorriso")) {
                                Files.writeString(Path.of(argv.get(5)), "fixture iso");
                                if (isoTimesOut) throw new IOException("xorriso timed out");
                            }
                            if (argv.contains("dominfo"))
                                return new HostPrograms.Result(existing ? 0 : 1, "");
                            if (argv.contains("--name") && argv.contains("--all"))
                                return new HostPrograms.Result(0, leftBehind ? "pool-1\n" : "");
                            return new HostPrograms.Result(argv.contains(failing) ? 1 : 0, "");
                        },
                        Path::of);
        return new PoolMachines(files, runtime, () -> nanos, () -> nanos += 3_000_000_000L);
    }

    private int owner() {
        try {
            return (int) Files.getAttribute(root, "unix:uid");
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private Path seed() throws IOException {
        final Path seed = Files.createDirectory(root.resolve("seed"));
        Files.setAttribute(seed, "unix:mode", 0700);
        for (String name : List.of("user-data", "meta-data")) {
            Files.writeString(seed.resolve(name), "fixture");
            Files.setAttribute(seed.resolve(name), "unix:mode", 0600);
        }
        return seed;
    }

    @Test
    void addressWaitsForTheIpv4LeaseWithinThreeHundredSeconds() throws Exception {
        lease = " vnet0 52:54:00:aa:bb:cc ipv4 192.0.2.10/24\n";
        assertThat(machine().address("pool-1", 300)).isEqualTo("192.0.2.10");
        assertThat(calls)
                .containsExactly(
                        List.of("virsh", "domstate", "pool-1"),
                        List.of("virsh", "domifaddr", "pool-1", "--source", "lease"));
    }

    @Test
    void anAbsentAddressFailsAtTheThreeHundredSecondDeadline() {
        assertThatThrownBy(() -> machine().address("pool-1", 300))
                .isInstanceOf(HostRefusal.class)
                .hasMessage("FAILED: pool-1 got no address within 300 s");
        assertThat(nanos).isEqualTo(300_000_000_000L);
        assertThat(calls.stream().filter(argv -> argv.contains("domifaddr"))).hasSize(100);
    }

    @Test
    void aStoppedVmIsNotAskedForAnAddress() {
        state = "shut off";
        assertThatThrownBy(() -> machine().address("pool-1", 300))
                .hasMessage("FAILED: pool-1 is not running: shut off");
        assertThat(calls).containsExactly(List.of("virsh", "domstate", "pool-1"));
    }

    @Test
    void deleteFinishesCleanupAfterATimeoutAndBelievesTheCensus() throws Exception {
        deletionTimesOut = true;
        machine().delete("pool-1");
        assertThat(calls)
                .containsExactly(
                        List.of("virsh", "destroy", "pool-1"),
                        List.of("virsh", "undefine", "pool-1", "--snapshots-metadata"),
                        List.of("virsh", "vol-delete", "pool-1.qcow2", "--pool", "images"),
                        List.of("virsh", "vol-delete", "pool-1-seed.iso", "--pool", "images"),
                        List.of("virsh", "list", "--all", "--name"),
                        List.of("virsh", "vol-list", "images"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deleteRefusesAnUnreadableCensusOrAStillListedDomain(boolean unreadable) {
        censusFails = unreadable;
        leftBehind = !unreadable;
        assertThatThrownBy(() -> machine().delete("pool-1"))
                .hasMessage("FAILED: LEFT BEHIND, delete by hand: pool-1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aFailedIsoBuildRemovesThePartialCredentialBearingSeed(boolean timedOut) throws Exception {
        final Path seed = seed();
        failing = "xorriso";
        isoTimesOut = timedOut;
        assertThatThrownBy(
                        () -> machine().make("pool-1", "base.qcow2", seed, 2048, 2, "ubuntu25.10"))
                .isInstanceOf(Exception.class);
        assertThat(seed.resolve("seed.iso")).doesNotExist();
        assertThat(calls).noneMatch(argv -> argv.contains("destroy"));
    }

    @Test
    void aPoolVmGetsThePreparedSeedAndAGuestAgentChannel() throws Exception {
        machine().make("pool-1", "base.qcow2", seed(), 2048, 2, "ubuntu25.10");
        assertThat(calls.getLast())
                .as("virt-install gets fixed storage and guest-agent wiring")
                .containsSubsequence(
                        "virt-install",
                        "--name",
                        "pool-1",
                        "--memory",
                        "2048",
                        "--vcpus",
                        "2",
                        "--cpu",
                        "host-passthrough",
                        "--import",
                        "--disk",
                        "vol=images/pool-1.qcow2,bus=virtio",
                        "--disk",
                        "vol=images/pool-1-seed.iso,device=cdrom",
                        "--network",
                        "network=default",
                        "--osinfo",
                        "ubuntu25.10")
                .endsWith(
                        "--channel", "unix,target.type=virtio,target.name=org.qemu.guest_agent.0");
    }

    @Test
    void aFailedImportRemovesItsSeedAndChecksWhatCleanupLeft() throws Exception {
        final Path seed = seed();
        failing = "virt-install";
        assertThatThrownBy(
                        () -> machine().make("pool-1", "base.qcow2", seed, 2048, 2, "ubuntu25.10"))
                .as("a failed import cannot look completed")
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("could not make pool-1");
        assertThat(seed.resolve("seed.iso"))
                .as("the local seed can carry a guest credential")
                .doesNotExist();
        assertThat(calls)
                .as("destruction precedes undefinition and observed cleanup")
                .containsSubsequence(
                        List.of("virsh", "destroy", "pool-1"),
                        List.of("virsh", "undefine", "pool-1", "--snapshots-metadata"),
                        List.of("virsh", "vol-delete", "pool-1.qcow2", "--pool", "images"),
                        List.of("virsh", "vol-delete", "pool-1-seed.iso", "--pool", "images"),
                        List.of("virsh", "list", "--all", "--name"),
                        List.of("virsh", "vol-list", "images"));
    }

    @Test
    void aVmLeftByCleanupIsSaidEvenWhenDeletionReturnedSuccess() throws Exception {
        final Path seed = seed();
        failing = "virt-install";
        leftBehind = true;
        assertThatThrownBy(
                        () -> machine().make("pool-1", "base.qcow2", seed, 2048, 2, "ubuntu25.10"))
                .as("the census decides whether cleanup completed")
                .hasMessageContaining("LEFT BEHIND");
    }

    @Test
    void anExistingDomainIsNeverOverwritten() throws Exception {
        final Path seed = seed();
        existing = true;
        assertThatThrownBy(
                        () -> machine().make("pool-1", "base.qcow2", seed, 2048, 2, "ubuntu25.10"))
                .as("an existing rental is not this import's to replace")
                .hasMessageContaining("already there");
        assertThat(calls)
                .as("no mutation follows the existing-domain refusal")
                .containsExactly(List.of("virsh", "dominfo", "pool-1"));
    }
}
