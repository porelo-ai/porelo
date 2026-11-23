package org.fuin.porelo.host;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.LimitFactors;

/** The pool's revert state machine; a link is raised only after the host sets the guest's clock. */
final class HostPool {
    static final Map<String, String> ENVIRONMENT =
            Map.of(
                    "PATH",
                    "/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG",
                    "C.UTF-8",
                    "LC_ALL",
                    "C",
                    "LIBVIRT_DEFAULT_URI",
                    "qemu:///system");

    @FunctionalInterface
    interface Pause {
        void sleep() throws InterruptedException;
    }

    @FunctionalInterface
    interface Lock {
        AutoCloseable acquire() throws IOException;
    }

    @FunctionalInterface
    interface Events {
        void watch(Runnable started, Consumer<String> lines) throws IOException;
    }

    /** The pause between clock-correction tries of a revert. */
    private static final Duration CLOCK_PAUSE = Duration.ofMillis(3000);

    /** How often a VM's clock correction is tried. */
    private static final int CLOCK_TRIES = 20;

    /** One {@code virsh domtime} call. */
    private static final Duration DOMTIME = Duration.ofSeconds(10);

    /** The wait for the host tools lock of a revert. */
    private static final Duration TOOLS_LOCK = Duration.ofSeconds(300);

    /** The life of the virsh event watcher. */
    private static final Duration WATCHER = Duration.ofHours(24);

    /** One pass of {@code virsh event}, given to it in whole seconds. */
    private static final Duration EVENT_PASS = Duration.ofSeconds(30);

    /** One virsh call, and the systemd-run give-back call. */
    private static final Duration VIRSH = Duration.ofSeconds(60);

    /** The delay before systemd-run powers a VM off on give-back. */
    private static final Duration GIVE_BACK_DELAY = Duration.ofSeconds(2);

    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final Pause pause;
    private final Lock lock;
    private final Events events;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Path directory;

    HostPool(PrintWriter out, PrintWriter err, LimitFactors limits) {
        this(new ProjectFiles(), out, err, limits);
    }

    private HostPool(ProjectFiles files, PrintWriter out, PrintWriter err, LimitFactors limits) {
        this(
                files,
                new ProjectRuntime(files, limits),
                () -> Thread.sleep(limits.retryPause(CLOCK_PAUSE)),
                () -> {
                    final Path tools = files.at("/usr/local/libexec/porelo-host");
                    files.directory(tools, false, false);
                    return HostConfigurationLock.acquire(
                            tools, false, limits.machines(TOOLS_LOCK), limits);
                },
                (started, lines) ->
                        new HostPrograms(limits.machines(WATCHER), 1024 * 1024)
                                .watch(
                                        List.of(
                                                "virsh",
                                                "event",
                                                "--loop",
                                                "--timeout",
                                                Long.toString(
                                                        HostLimits.seconds(
                                                                limits.machines(EVENT_PASS))),
                                                "--event",
                                                "lifecycle"),
                                        ENVIRONMENT,
                                        started,
                                        lines),
                out,
                err);
    }

    HostPool(
            ProjectFiles files,
            ProjectRuntime runtime,
            Pause pause,
            Lock lock,
            Events events,
            PrintWriter out,
            PrintWriter err) {
        this.files = files;
        this.runtime = runtime;
        this.pause = pause;
        this.lock = lock;
        this.events = events;
        this.out = out;
        this.err = err;
        directory = files.at("/run/porelo-pool-revert");
    }

    static void name(String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
            throw new HostRefusal(2, "invalid pool VM name");
    }

    private HostPrograms.Result virsh(Duration timeout, String... arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("virsh"));
        command.addAll(List.of(arguments));
        try {
            return runtime.run(command, new byte[0], ENVIRONMENT, timeout);
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) throw ex;
            // A bounded-out client is a failed command, including when libvirt acted before it
            // ended.
            return new HostPrograms.Result(1, "");
        }
    }

    private HostPrograms.Result virsh(String... arguments) throws IOException {
        return virsh(runtime.limits().machines(VIRSH), arguments);
    }

    private boolean yes(String... arguments) throws IOException {
        return virsh(arguments).exitCode() == 0;
    }

    private Path marker(String name, String suffix) {
        return directory.resolve(name + suffix);
    }

    private void note(String name, String suffix) throws IOException {
        files.publish(marker(name, suffix), ".pool-", "");
    }

    private void forget(String name, String suffix) throws IOException {
        files.delete(marker(name, suffix));
    }

    private String nic(String name) throws IOException {
        final var result = virsh("domiflist", name);
        if (result.exitCode() != 0) return "";
        for (String line : result.output().lines().toList()) {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length >= 5 && fields[4].contains(":")) return fields[4];
        }
        return "";
    }

    private boolean clock(String name) throws IOException {
        final int most = runtime.limits().tries(CLOCK_TRIES);
        for (int tries = 0; tries < most; tries++) {
            try {
                if (virsh(runtime.limits().machines(DOMTIME), "domtime", name, "--now").exitCode()
                        == 0) return true;
            } catch (IOException ex) {
                if (Thread.currentThread().isInterrupted()) throw ex;
                // A silent agent is a failed clock attempt too, never an unbounded wait.
            }
            try {
                pause.sleep();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Pool revert interrupted", ex);
            }
        }
        return false;
    }

    private boolean revert(String name) throws IOException {
        try {
            note(name, ".midway");
        } catch (IOException | HostRefusal ex) {
            err.println("could not note the revert of " + name + "; it stays as it is");
            return false;
        }
        if (!yes("snapshot-revert", name, "base", "--paused")) {
            err.println("could not revert " + name + "; it stays off and is not handed out");
            return false;
        }
        boolean ready = false;
        try {
            final String mac = nic(name);
            ready =
                    !mac.isEmpty()
                            && yes("domif-setlink", name, mac, "down")
                            && yes("resume", name)
                            && clock(name);
            if (ready) {
                forget(name, ".off");
                ready = yes("domif-setlink", name, mac, "up");
            }
        } catch (IOException | HostRefusal ex) {
            // An incomplete revert still owns the VM and must attempt to stop it below.
            ready = false;
        }
        if (ready) {
            forget(name, ".midway");
            out.println("reverted " + name + " to its base snapshot");
            return true;
        }
        try {
            note(name, ".off");
        } catch (IOException | HostRefusal ex) {
            err.println("could not note that " + name + " stays off");
        }
        if (yes("destroy", name)) {
            forget(name, ".midway");
            err.println(
                    "could not set the clock of "
                            + name
                            + " after its revert; it stays off and is not handed out, until this service restarts; a pool VM made before its guest agent was added needs down and up");
        } else
            err.println(
                    "could not set the clock of "
                            + name
                            + " after its revert, and could not stop "
                            + name
                            + "; its link stays down, so it is not handed out");
        return false;
    }

    private int midway(String name) throws IOException {
        if (!files.exists(marker(name, ".midway"))) return 1;
        final var state = virsh("domstate", name);
        if (state.exitCode() != 0) return 2;
        if (state.output().strip().equals("paused")) return 0;
        if (!state.output().strip().equals("running")) return 1;
        final String mac = nic(name);
        if (mac.isEmpty()) return 2;
        final var link = virsh("domif-getlink", name, mac);
        if (link.exitCode() != 0) return 2;
        return link.output().contains("down") ? 0 : 1;
    }

    boolean settle(String name, boolean force) throws IOException {
        name(name);
        final AutoCloseable acquired;
        try {
            acquired = lock.acquire();
        } catch (IOException ex) {
            err.println("another revert has held the pool for 5 minutes; " + name + " waits");
            return false;
        }
        try (acquired) {
            final int left = midway(name);
            if (left == 2) {
                err.println(
                        "could not read how "
                                + name
                                + " stands, which a revert left midway; asked again at the next look");
                return false;
            }
            if (left == 0 && !yes("destroy", name)) {
                err.println(
                        "could not stop "
                                + name
                                + ", which a revert left midway; it is not handed out");
                return false;
            }
            final var state = virsh("domstate", name);
            if (state.exitCode() != 0) {
                err.println("could not read the state of " + name);
                return false;
            }
            forget(name, ".midway");
            if (!state.output().strip().equals("shut off")
                    || (!force && files.exists(marker(name, ".off")))) return true;
            return revert(name);
        } catch (IOException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Cannot release pool revert lock", ex);
        }
    }

    int one(String name) throws IOException {
        name(name);
        files.mkdir(directory, 0755);
        return settle(name, true) ? 0 : 1;
    }

    private List<String> names(Path list) throws IOException {
        final List<String> names = files.text(list, false).lines().toList();
        names.forEach(HostPool::name);
        return names;
    }

    private void look(Path list, String name) {
        try {
            if (names(list).contains(name)) settle(name, false);
        } catch (IOException | HostRefusal ex) {
            err.println("could not settle " + name + "; asked again at the next look");
        }
    }

    int service(Path list) throws IOException {
        try {
            names(list);
        } catch (IOException | HostRefusal ex) {
            err.println("no list of pool VMs at " + list);
            return 1;
        }
        files.directory(directory, false, false);
        for (Path entry : files.list(directory))
            if (entry.getFileName().toString().endsWith(".off")) files.delete(entry);
        final Pattern stopped = Pattern.compile(".*for domain '([^']+)': Stopped.*");
        while (!Thread.currentThread().isInterrupted()) {
            final boolean[] timedOut = {false};
            try {
                events.watch(
                        () -> {
                            try {
                                for (String name : names(list)) look(list, name);
                            } catch (IOException | HostRefusal ex) {
                                throw new HostRefusal(1, "no list of pool VMs at " + list);
                            }
                        },
                        line -> {
                            if (line.equals("event loop timed out")) timedOut[0] = true;
                            final var match = stopped.matcher(line);
                            if (match.matches()) look(list, match.group(1));
                        });
            } catch (IOException ex) {
                err.println("virsh event ended");
                return 1;
            }
            if (!timedOut[0]) break;
        }
        err.println("virsh event ended");
        return 1;
    }

    int giveBack(Map<String, String> environment) throws IOException {
        final String account = environment.getOrDefault("SUDO_USER", "");
        if (account.isEmpty())
            throw new HostRefusal(
                    2, "porelo-host pool give-back runs through sudo, as the run account");
        if (!account.matches("[a-z][a-z0-9_-]{0,31}"))
            throw new HostRefusal(2, "invalid pool run account");
        final Path state = files.at("/var/lib/porelo-pool/" + account);
        if (!files.exists(state))
            throw new HostRefusal(2, "'" + account + "' is no run account of this pool VM");
        files.trusted(state, false);
        runtime.require("systemd-run", "/usr/bin/systemctl");
        final int mode =
                (int)
                                java.nio.file.Files.getAttribute(
                                        state, "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        & 0777;
        final Path staged = files.stage(state, ".pool-", "resetting\n");
        try {
            java.nio.file.Files.setAttribute(staged, "unix:mode", mode);
            files.replace(staged, state);
        } finally {
            files.delete(staged);
        }
        final var result =
                runtime.run(
                        List.of(
                                "systemd-run",
                                "--quiet",
                                "--collect",
                                "--unit",
                                "porelo-pool-give-back",
                                "--on-active="
                                        + HostLimits.span(
                                                runtime.limits().machines(GIVE_BACK_DELAY)),
                                "/usr/bin/systemctl",
                                "poweroff"),
                        new byte[0],
                        ENVIRONMENT,
                        runtime.limits().machines(VIRSH));
        return result.exitCode();
    }
}
