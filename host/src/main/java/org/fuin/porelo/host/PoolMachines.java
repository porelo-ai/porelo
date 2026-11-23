package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import org.fuin.porelo.wire.LimitFactors;

/** Imports a pool VM from a prepared seed; seed construction remains the build tool's job. */
final class PoolMachines {
    /** The pause between looks for a pool VM's IPv4 lease. */
    private static final Duration LEASE_POLL = Duration.ofSeconds(3);

    /** One virsh or qemu pool command, and one query of the address wait. */
    private static final Duration VIRSH = Duration.ofMinutes(10);

    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final LongSupplier ticks;
    private final HostPool.Pause pause;

    PoolMachines(LimitFactors limits) {
        this(new ProjectFiles(), limits);
    }

    private PoolMachines(ProjectFiles files, LimitFactors limits) {
        this(files, new ProjectRuntime(files, limits));
    }

    PoolMachines(ProjectFiles files, ProjectRuntime runtime) {
        this(files, runtime, System::nanoTime, () -> Thread.sleep(leasePoll(runtime.limits())));
    }

    /**
     * Returns the pause between looks for a pool VM's IPv4 lease.
     *
     * @param limits The factors this run of porelo-host read from its host configuration.
     * @return The default pause times the polling factor.
     */
    static Duration leasePoll(LimitFactors limits) {
        return limits.polling(LEASE_POLL);
    }

    PoolMachines(
            ProjectFiles files, ProjectRuntime runtime, LongSupplier ticks, HostPool.Pause pause) {
        this.files = files;
        this.runtime = runtime;
        this.ticks = ticks;
        this.pause = pause;
    }

    private static void name(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,62}"))
            throw new HostRefusal(1, "FAILED: '" + name + "' is no VM name");
    }

    private HostPrograms.Result run(String... arguments) throws IOException {
        return runtime.run(
                List.of(arguments),
                new byte[0],
                HostPool.ENVIRONMENT,
                runtime.limits().machines(VIRSH));
    }

    private void require(String... arguments) throws IOException {
        if (run(arguments).exitCode() != 0) throw new IOException("Pool VM command failed");
    }

    void make(String name, String volume, Path seed, int memory, int vcpus, String osinfo)
            throws IOException {
        name(name);
        if (!volume.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")
                || memory < 1
                || vcpus < 1
                || vcpus > 1024
                || !osinfo.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
            throw new HostRefusal(2, "invalid pool VM parameters");
        files.directory(seed, true, false);
        files.trusted(seed.resolve("user-data"), true);
        files.trusted(seed.resolve("meta-data"), false);
        if (run("virsh", "dominfo", name).exitCode() == 0)
            throw new HostRefusal(1, "a VM '" + name + "' is already there");
        try {
            require(
                    "xorriso",
                    "-as",
                    "mkisofs",
                    "-quiet",
                    "-o",
                    seed.resolve("seed.iso").toString(),
                    "-V",
                    "cidata",
                    "-J",
                    "-r",
                    seed.resolve("user-data").toString(),
                    seed.resolve("meta-data").toString());
        } catch (IOException | RuntimeException ex) {
            files.delete(seed.resolve("seed.iso"));
            throw ex;
        }
        try {
            require(
                    "virsh",
                    "vol-create-as",
                    "images",
                    name + ".qcow2",
                    "20G",
                    "--format",
                    "qcow2",
                    "--backing-vol",
                    volume,
                    "--backing-vol-format",
                    "qcow2");
            require(
                    "virsh",
                    "vol-create-as",
                    "images",
                    name + "-seed.iso",
                    Long.toString(Files.size(seed.resolve("seed.iso"))),
                    "--format",
                    "raw");
            require(
                    "virsh",
                    "vol-upload",
                    name + "-seed.iso",
                    seed.resolve("seed.iso").toString(),
                    "--pool",
                    "images");
            require(
                    "virt-install",
                    "--name",
                    name,
                    "--memory",
                    Integer.toString(memory),
                    "--vcpus",
                    Integer.toString(vcpus),
                    "--cpu",
                    "host-passthrough",
                    "--import",
                    "--disk",
                    "vol=images/" + name + ".qcow2,bus=virtio",
                    "--disk",
                    "vol=images/" + name + "-seed.iso,device=cdrom",
                    "--network",
                    "network=default",
                    "--osinfo",
                    osinfo,
                    "--graphics",
                    "none",
                    "--noautoconsole",
                    "--channel",
                    "unix,target.type=virtio,target.name=org.qemu.guest_agent.0");
        } catch (IOException | RuntimeException ex) {
            files.delete(seed.resolve("seed.iso"));
            delete(name);
            throw new HostRefusal(
                    1,
                    "FAILED: could not make "
                            + name
                            + " ("
                            + name
                            + ".qcow2, "
                            + name
                            + "-seed.iso)");
        }
    }

    String address(String name, long seconds) throws IOException {
        name(name);
        if (seconds < 0)
            throw new HostRefusal(1, "FAILED: '" + seconds + "' is no number of seconds");
        final long started = ticks.getAsLong();
        while (true) {
            final Duration remaining =
                    Duration.ofSeconds(seconds)
                            .minus(Duration.ofNanos(ticks.getAsLong() - started));
            if (remaining.isNegative() || remaining.isZero())
                throw new HostRefusal(
                        1, "FAILED: " + name + " got no address within " + seconds + " s");
            final var state = query(remaining, "domstate", name);
            if (state.exitCode() != 0 || !state.output().strip().equals("running"))
                throw new HostRefusal(
                        1, "FAILED: " + name + " is not running: " + state.output().strip());
            final Duration left =
                    Duration.ofSeconds(seconds)
                            .minus(Duration.ofNanos(ticks.getAsLong() - started));
            if (left.isNegative() || left.isZero()) continue;
            final var addresses = query(left, "domifaddr", name, "--source", "lease");
            if (addresses.exitCode() == 0) {
                for (String line : addresses.output().lines().toList()) {
                    final String[] fields = line.strip().split("\\s+");
                    if (fields.length >= 4 && fields[2].equals("ipv4"))
                        return fields[3].split("/", 2)[0];
                }
            }
            try {
                pause.sleep();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Pool address wait interrupted", ex);
            }
        }
    }

    private HostPrograms.Result query(Duration remaining, String... arguments) throws IOException {
        final var command = new java.util.ArrayList<>(List.of("virsh"));
        command.addAll(List.of(arguments));
        return runtime.run(
                command,
                new byte[0],
                HostPool.ENVIRONMENT,
                remaining.compareTo(runtime.limits().machines(VIRSH)) < 0
                        ? remaining
                        : runtime.limits().machines(VIRSH));
    }

    void delete(String name) throws IOException {
        name(name);
        for (var command :
                List.of(
                        List.of("virsh", "destroy", name),
                        List.of("virsh", "undefine", name, "--snapshots-metadata"),
                        List.of("virsh", "vol-delete", name + ".qcow2", "--pool", "images"),
                        List.of("virsh", "vol-delete", name + "-seed.iso", "--pool", "images"))) {
            try {
                run(command.toArray(String[]::new));
            } catch (IOException ex) {
                // A failed client does not say what libvirt kept; finish cleanup and ask below.
            }
        }
        final var domains = run("virsh", "list", "--all", "--name");
        final var volumes = run("virsh", "vol-list", "images");
        if (domains.exitCode() != 0
                || volumes.exitCode() != 0
                || domains.output().lines().anyMatch(name::equals)
                || volumes.output()
                        .lines()
                        .map(line -> line.strip().split("\\s+", 2)[0])
                        .anyMatch(
                                volume ->
                                        volume.equals(name + ".qcow2")
                                                || volume.equals(name + "-seed.iso")))
            throw new HostRefusal(1, "FAILED: LEFT BEHIND, delete by hand: " + name);
    }
}
