package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Joins the fixed root service without carrying a person's environment or credentials into it. */
final class HostFollowNow {
    private static final String UNIT = "porelo-host-follow.service";
    private static final String STATE = "/run/porelo-host/follow-now/";
    private final HostFiles files;
    private final ProjectRuntime runtime;
    private final LongSupplier nanos;
    private final long deadline;

    HostFollowNow(HostFiles files, ProjectRuntime runtime) {
        this(files, runtime, System::nanoTime, Duration.ofSeconds(650));
    }

    HostFollowNow(HostFiles files, ProjectRuntime runtime, LongSupplier nanos, Duration budget) {
        this.files = files;
        this.runtime = runtime;
        this.nanos = nanos;
        deadline = nanos.getAsLong() + budget.toNanos();
    }

    record Target(String job, @Nullable String invocation) {}

    record Entry(@Nullable Target active, @Nullable String latest) {}

    record Completion(String invocation, int exitCode) {
        Map<String, @Nullable Object> answer() {
            return HostJson.fields(
                    "invocation", invocation, "completed", true, "exitCode", exitCode);
        }
    }

    Entry capture() throws IOException {
        try (var lock = coordinator()) {
            return new Entry(observe(), latest());
        }
    }

    Completion follow(HostPerson person, Entry entry) throws IOException {
        final Path conf = files.path("/etc/porelo-host/forge.conf");
        files.check(conf, 69, true, false, -1);
        try (var lock = HostConfigurationLock.acquire(conf, true, remaining())) {
            final HostRecords records = new HostRecords(files);
            records.admitted(person.login());
            if (records.revoked(person.login())) {
                throw new HostRefusal(
                        65, person.login() + " is revoked by the host's organization");
            }
        }
        final Target target = select(entry);
        while (true) {
            remaining();
            final Target active = observe();
            if (active == null || !active.job().equals(target.job())) {
                final Completion completion = completion(target);
                if (completion == null)
                    throw unavailable("the selected pass left no completion receipt");
                return completion;
            }
            try {
                Thread.sleep(Math.min(50, Math.max(1, remaining().toMillis())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw unavailable("waiting was interrupted; the shared pass continues");
            }
        }
    }

    private Target select(Entry entry) throws IOException {
        if (entry.active() != null) return entry.active();
        try (var lock = coordinator()) {
            final String latest = latest();
            if (latest != null && !latest.equals(entry.latest())) return new Target(latest, null);
            final Target active = observe();
            if (active != null) {
                replaceLatest(active.job());
                return active;
            }
            final String reply =
                    command(
                            List.of(
                                    "busctl",
                                    "--system",
                                    "call",
                                    "org.freedesktop.systemd1",
                                    "/org/freedesktop/systemd1",
                                    "org.freedesktop.systemd1.Manager",
                                    "StartUnit",
                                    "ss",
                                    UNIT,
                                    "fail"));
            final var match =
                    java.util.regex.Pattern.compile(
                                    "o \"/org/freedesktop/systemd1/job/([1-9][0-9]{0,9})\"\\n?")
                            .matcher(reply);
            if (!match.matches()) throw unavailable("systemd answered no start job");
            final String job = job(match.group(1));
            replaceLatest(job);
            return new Target(job, null);
        }
    }

    /** Root records the service's job association before waiting for forge.conf. */
    @Nullable Target begin(Map<String, String> environment) throws IOException {
        final String invocation = environment.get("INVOCATION_ID");
        if (invocation == null) return null;
        invocation(invocation);
        try (var lock = coordinator()) {
            final Target active = observe();
            if (active == null || !invocation.equals(active.invocation())) {
                throw unavailable("the root follow does not match the service invocation");
            }
            publish("jobs/" + active.job(), invocation + "\n", false);
            replaceLatest(active.job());
            return active;
        }
    }

    void finish(Target target, int exitCode) throws IOException {
        final String invocation =
                Objects.requireNonNull(target.invocation(), "Root's pass has an invocation id");
        if (exitCode < 0 || exitCode > 255)
            throw unavailable("the follow answered an invalid exit status");
        publish("invocations/" + invocation, exitCode + "\n", false);
    }

    private @Nullable Completion completion(Target target) {
        final String name = "jobs/" + target.job();
        final String value = read(name);
        if (value == null) return null;
        final String invocation = invocation(value.strip());
        if (target.invocation() != null && !target.invocation().equals(invocation)) {
            throw unavailable("the job receipt names another invocation");
        }
        final String result = read("invocations/" + invocation);
        if (result == null) return null;
        if (!result.matches("(0|[1-9][0-9]{0,2})\\n") || Integer.parseInt(result.strip()) > 255) {
            throw unavailable("the completion receipt has an invalid exit status");
        }
        return new Completion(invocation, Integer.parseInt(result.strip()));
    }

    private @Nullable Target observe() throws IOException {
        final String reply =
                command(
                        List.of(
                                "systemctl",
                                "--system",
                                "show",
                                "--all",
                                "--property=LoadState,Type,ActiveState,InvocationID,Job",
                                UNIT));
        final Map<String, String> fields = new HashMap<>();
        for (String line : reply.lines().toList()) {
            final int split = line.indexOf('=');
            if (split < 1
                    || fields.putIfAbsent(line.substring(0, split), line.substring(split + 1))
                            != null) {
                throw unavailable("systemd answered an invalid service state");
            }
        }
        if (fields.size() != 5
                || !"loaded".equals(fields.get("LoadState"))
                || !"oneshot".equals(fields.get("Type"))) {
            throw unavailable("the fixed oneshot service is not installed; update the host");
        }
        final String state = fields.getOrDefault("ActiveState", "");
        final String job = fields.getOrDefault("Job", "");
        if (job.equals("0") && List.of("inactive", "failed").contains(state)) return null;
        final String invocation = fields.getOrDefault("InvocationID", "");
        if (!List.of("inactive", "failed", "activating", "active", "deactivating")
                .contains(state)) {
            throw unavailable("systemd answered an invalid activation state");
        }
        return new Target(
                job(job),
                invocation.isEmpty() || state.equals("inactive") || state.equals("failed")
                        ? null
                        : invocation(invocation));
    }

    private String command(List<String> argv) throws IOException {
        final Duration timeout =
                remaining().compareTo(Duration.ofSeconds(10)) < 0
                        ? remaining()
                        : Duration.ofSeconds(10);
        final HostPrograms.Result result;
        try {
            result = runtime.run(argv, new byte[0], ProjectRuntime.SAFE_ENV, timeout);
        } catch (IOException ex) {
            throw unavailable("systemd could not answer; check the fixed follow service");
        }
        if (result.exitCode() != 0)
            throw unavailable(
                    "systemd refused the fixed follow service; check its installation and journal");
        return result.output();
    }

    private HostConfigurationLock coordinator() throws IOException {
        remaining();
        files.directories(files.path(STATE));
        files.directories(files.path(STATE + "jobs"));
        files.directories(files.path(STATE + "invocations"));
        final Path mutex = files.path(STATE + "lock");
        if (!files.check(mutex, 73, false, false, 0600)) {
            try {
                Files.createFile(
                        mutex,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException ex) {
                files.check(mutex, 73, true, false, 0600);
            }
        }
        return HostConfigurationLock.acquire(mutex, false, remaining());
    }

    private @Nullable String latest() {
        final String value = read("latest");
        return value == null ? null : job(value.strip());
    }

    private void replaceLatest(String job) throws IOException {
        publish("latest", job + "\n", true);
    }

    private @Nullable String read(String name) {
        final Path path = files.path(STATE + name);
        return files.check(path, 69, false, false, 0600) ? files.read(path) : null;
    }

    private void publish(String name, String content, boolean replace) throws IOException {
        final Path destination = files.path(STATE + name);
        files.check(destination, 73, false, false, 0600);
        final Path temporary = ProjectFiles.stagePrivate(destination, ".receipt-", content, null);
        try {
            if (replace) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                try {
                    Files.createLink(destination, temporary);
                } catch (FileAlreadyExistsException ex) {
                    if (!content.equals(read(name)))
                        throw unavailable("a retained receipt cannot be replaced");
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Duration remaining() {
        final long remaining = deadline - nanos.getAsLong();
        if (remaining <= 0)
            throw unavailable(
                    "the wait budget expired; inspect the service journal before retrying");
        return Duration.ofNanos(remaining);
    }

    private static String job(String job) {
        if (!job.matches("[1-9][0-9]{0,9}") || Long.parseLong(job) > 4294967295L) {
            throw unavailable("systemd answered no valid start job");
        }
        return job;
    }

    private static String invocation(String invocation) {
        if (!invocation.matches("[0-9a-f]{32}") || invocation.equals("0".repeat(32))) {
            throw unavailable("the service answered no valid invocation identity");
        }
        return invocation;
    }

    private static HostRefusal unavailable(String reason) {
        return new HostRefusal(69, "porelo-host: follow completion unavailable: " + reason);
    }
}
