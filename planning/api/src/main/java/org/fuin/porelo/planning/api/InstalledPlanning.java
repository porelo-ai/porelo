package org.fuin.porelo.planning.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * A running installed adapter. Ordinary calls are serialized; each watch has its own connection.
 * The executable inherits the caller's environment, as a build reader does. Closing cancels
 * watches.
 */
public final class InstalledPlanning implements AutoCloseable {
    static final long READY_SECONDS = 10;
    static final long CALL_SECONDS = 120;
    static final int MESSAGE = 2048;
    static final int VERSION_LENGTH = 128;
    private static final byte[] READY = "ready ".getBytes(StandardCharsets.US_ASCII);
    private final Path executable;
    private final long readySeconds;
    private final long callSeconds;
    private final Path own;
    private final Path socket;
    private final Path directory;
    private final java.util.concurrent.ConcurrentMap<VarlinkClient, Process> watchers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Object calls = new Object();
    private volatile boolean closed;
    private Running running;

    private record Running(Process process, VarlinkClient client, Description description) {}

    /** The description both discovery and the socket handshake validate. */
    public record Description(
            int protocolVersion,
            String name,
            String version,
            List<String> capabilities,
            List<String> credentials) {
        /** Copies the capability and credential lists. */
        public Description {
            capabilities = List.copyOf(capabilities);
            credentials = List.copyOf(credentials);
        }
    }

    /**
     * Starts the executable, awaits ready and checks Describe against its filename and LOWEST.
     * socketDirectory is caller-owned; this client makes and removes only its own subdirectory.
     * directory is the adapter's durable project directory, already made by the caller.
     */
    public InstalledPlanning(Path executable, Path socketDirectory, Path directory) {
        this(executable, socketDirectory, directory, READY_SECONDS, CALL_SECONDS);
    }

    /** Uses short bounds for lifecycle tests; production uses the public constructor's limits. */
    InstalledPlanning(
            Path executable,
            Path socketDirectory,
            Path directory,
            long readySeconds,
            long callSeconds) {
        this.readySeconds = readySeconds;
        this.callSeconds = callSeconds;
        this.executable = executable;
        this.directory = directory;
        try {
            own = Files.createTempDirectory(socketDirectory, "p-");
        } catch (IOException ex) {
            throw new PlanningException("Cannot make a socket directory for " + executable, ex);
        }
        socket = own.resolve("s");
        try {
            running = start();
        } catch (RuntimeException ex) {
            removeOwn();
            throw ex;
        }
    }

    /** Finds an executable by plain name, with the first directory winning. */
    public static Path find(String name, List<Path> directories) {
        if (!name.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new PlanningException("Invalid planning adapter name");
        }
        for (Path directory : directories) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new PlanningException(
                "No planning adapter '" + name + "' in " + directories + "; install its package");
    }

    /** Returns the running adapter's checked description. */
    public synchronized Description describe() {
        ensureRunning();
        return running.description();
    }

    /** Returns the workflow and its snapshot revision. */
    public PlanningAdapter.Result<Workflow> getWorkflow(
            Map<String, Object> settings, String token) {
        return result(
                PlanningProtocol.GET_WORKFLOW,
                parameters(settings, token),
                "workflow",
                Workflow::of);
    }

    /**
     * Returns stories filtered by stored state, with issue snapshots; omit the filter to compute
     * board states.
     */
    public PlanningAdapter.Result<List<StorySummary>> listStories(
            Map<String, Object> settings, String token, @Nullable List<String> states) {
        final var p = parameters(settings, token);
        if (states != null) {
            p.put("states", states);
        }
        return listed(PlanningProtocol.LIST_STORIES, p, "stories", StorySummary::of);
    }

    /** Returns a story and its revision. */
    public PlanningAdapter.Result<Story> getStory(
            Map<String, Object> settings, String token, String story) {
        final var p = parameters(settings, token);
        p.put("story", story);
        return result(PlanningProtocol.GET_STORY, p, "story", Story::of);
    }

    /** Returns the issues of a story and their revision. */
    public PlanningAdapter.Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story) {
        final var p = parameters(settings, token);
        p.put("story", story);
        return listed(PlanningProtocol.LIST_STORY_ISSUES, p, "issues", IssueSummary::of);
    }

    /** Returns an issue including whatMustBeTrue and proposed sub-agents, with its revision. */
    public PlanningAdapter.Result<Issue> getIssue(
            Map<String, Object> settings, String token, String issue) {
        final var p = parameters(settings, token);
        p.put("issue", issue);
        return result(PlanningProtocol.GET_ISSUE, p, "issue", Issue::of);
    }

    /** Sets story and issue states together or none, returning one new revision. */
    public String setStates(
            Map<String, Object> settings,
            String token,
            List<StateChange> changes,
            String revision) {
        final var p = parameters(settings, token);
        p.put("changes", changes.stream().map(StateChange::asMap).toList());
        p.put("revision", revision);
        return revision(PlanningProtocol.SET_STATES, p);
    }

    /** Fetches at once and returns the adapter's revision. */
    public String refresh(Map<String, Object> settings, String token) {
        return revision(PlanningProtocol.REFRESH, parameters(settings, token));
    }

    /**
     * Blocks while changes arrive on an independent connection. Returning false closes the watch. A
     * watch has no overall call timeout; close this client to cancel from another thread. Compare
     * the initial revision with the preceding read and reread if different; never ignore it.
     */
    public void watchChanges(
            Map<String, Object> settings, String token, Predicate<Change> consumer) {
        final Running asked;
        synchronized (this) {
            ensureRunning();
            asked = running;
        }
        try (VarlinkClient client =
                bounded(asked.process(), () -> new VarlinkClient(socket), readySeconds)) {
            synchronized (this) {
                if (closed || running != asked || !asked.process().isAlive()) {
                    throw fault(executable + " stopped before its watch opened");
                }
                watchers.put(client, asked.process());
            }
            try {
                client.callMore(
                        PlanningProtocol.WATCH_CHANGES,
                        parameters(settings, token),
                        answer ->
                                consumer.test(
                                        decode(() -> Change.of(Wire.object(answer, "change")))));
            } finally {
                watchers.remove(client);
            }
        } catch (VarlinkException ex) {
            throw heard(executable, ex);
        } catch (IOException ex) {
            throw fault(executable + " failed to close its watch");
        }
    }

    private <T> PlanningAdapter.Result<T> result(
            String method, Map<String, Object> p, String field, Function<Map<?, ?>, T> read) {
        final var answer = call(method, p);
        return decode(
                () ->
                        new PlanningAdapter.Result<>(
                                read.apply(Wire.object(answer, field)),
                                Wire.text(answer, "revision")));
    }

    private <T> PlanningAdapter.Result<List<T>> listed(
            String method, Map<String, Object> p, String field, Function<Map<?, ?>, T> read) {
        final var answer = call(method, p);
        return decode(
                () ->
                        new PlanningAdapter.Result<>(
                                Wire.list(answer, field, read), Wire.text(answer, "revision")));
    }

    private String revision(String method, Map<String, Object> p) {
        final var answer = call(method, p);
        return decode(() -> Wire.text(answer, "revision"));
    }

    private <T> T decode(Supplier<T> read) {
        try {
            return read.get();
        } catch (IllegalArgumentException ex) {
            throw fault(executable + " answered malformed planning data");
        }
    }

    private static Map<String, Object> parameters(Map<String, Object> settings, String token) {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("settings", settings);
        p.put("token", token);
        return p;
    }

    private void ensureRunning() {
        if (closed) {
            throw fault(executable + " is closed");
        }
        if (!running.process().isAlive()) {
            stop(running);
            running = start();
        }
    }

    /** Closes connections, reaps the process and removes this client's private socket directory. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stop(running);
        removeOwn();
    }

    private void removeOwn() {
        try {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(own);
        } catch (IOException ex) {
            /* The caller removes the containing runtime directory. */
        }
    }

    static Description handshake(Path executable, Map<?, ?> answer) {
        try {
            final long spoken = Wire.whole(answer.get("protocolVersion")).orElse(-1);
            if (spoken < PlanningProtocol.LOWEST) {
                throw fault(
                        executable
                                + " speaks Planning1 version "
                                + spoken
                                + "; upgrade to at least "
                                + PlanningProtocol.LOWEST);
            }
            final String name = Wire.text(answer, "name");
            if (!executable.getFileName().toString().equals(name)) {
                throw fault(executable + " calls itself '" + Wire.plain(name, 128) + "'");
            }
            return new Description(
                    (int) Math.min(spoken, Integer.MAX_VALUE),
                    name,
                    Wire.plain(Wire.text(answer, "version"), VERSION_LENGTH),
                    Wire.strings(answer, "capabilities").stream()
                            .map(value -> Wire.plain(value, VERSION_LENGTH))
                            .toList(),
                    Wire.strings(answer, "credentials").stream()
                            .map(value -> Wire.plain(value, VERSION_LENGTH))
                            .toList());
        } catch (IllegalArgumentException ex) {
            throw fault(executable + " supplied an invalid description");
        }
    }

    private static PlanningException fault(String message) {
        return new PlanningException(Wire.plain(message, MESSAGE));
    }

    private Map<String, Object> call(String method, Map<String, Object> parameters) {
        synchronized (calls) {
            final Running asked;
            synchronized (this) {
                ensureRunning();
                asked = running;
            }
            try {
                return bounded(
                        asked.process(),
                        () -> asked.client().call(method, parameters),
                        callSeconds);
            } catch (VarlinkException ex) {
                if (ex.getErrorName() == null) stop(asked);
                throw heard(executable, ex);
            } catch (PlanningException ex) {
                stop(asked);
                throw ex;
            }
        }
    }

    private synchronized void stop(Running stopped) {
        closeWatchers(stopped.process());
        closeQuietly(stopped.client());
        ended(stopped.process());
    }

    private void closeWatchers(Process process) {
        watchers.forEach(
                (watcher, owner) -> {
                    if (owner == process) {
                        closeQuietly(watcher);
                        watchers.remove(watcher);
                    }
                });
    }

    static RuntimeException heard(Path executable, VarlinkException ex) {
        final PlanningRefused.Reason reason = PlanningRefused.Reason.of(ex.getErrorName());
        if (reason != null && ex.getParameters() != null) {
            try {
                return new PlanningRefused(reason, ex.getParameters());
            } catch (IllegalArgumentException malformed) {
                return new PlanningException(
                        Wire.plain(executable + " supplied a malformed refusal", MESSAGE),
                        ex.getErrorName(),
                        ex.getParameters());
            }
        }
        return new PlanningException(
                Wire.plain(
                        executable
                                + " failed to answer "
                                + (ex.getErrorName() == null
                                        ? "over its socket"
                                        : Wire.plain(ex.getErrorName(), 128)),
                        MESSAGE),
                ex.getErrorName(),
                ex.getParameters() == null ? Map.of() : ex.getParameters());
    }

    /** Starts the adapter, waits for it to listen, and completes the handshake. */
    private Running start() {
        final Process process;
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(
                                    List.of(
                                            executable.toString(),
                                            "serve",
                                            socket.toString(),
                                            directory.toString()))
                            .redirectErrorStream(true);
            // The contract grants the same environment a build reader receives.
            process = builder.start();
        } catch (IOException ex) {
            throw new PlanningException("Cannot start " + executable + ": " + ex.getMessage(), ex);
        }
        final AtomicReference<@Nullable VarlinkClient> opened = new AtomicReference<>();
        try {
            awaitReady(process);
            // Inside the limit: an adapter that says it is ready and accepts nobody blocks a
            // connect.
            final VarlinkClient client =
                    bounded(
                            process,
                            () -> {
                                final VarlinkClient connected = new VarlinkClient(socket);
                                opened.set(connected);
                                return connected;
                            },
                            readySeconds);
            final Description description =
                    handshake(
                            executable,
                            bounded(
                                    process,
                                    () -> client.call(PlanningProtocol.DESCRIBE, Map.of()),
                                    readySeconds));
            return new Running(process, client, description);
        } catch (RuntimeException ex) {
            ended(process);
            closeQuietly(opened.get());
            try {
                Files.deleteIfExists(socket);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex instanceof PlanningException refused
                    ? refused
                    : fault(executable + " failed to start");
        }
    }

    /** Waits for ready without echoing process output, which may contain inherited credentials. */
    private void awaitReady(final Process process) {
        final InputStream output = process.getInputStream();
        if (!bounded(process, () -> becameReady(output), readySeconds)) {
            throw fault(executable + " exited without becoming ready");
        }
        drain(output);
    }

    /**
     * Reads until a line starts with {@code ready}, by the byte, since a line need not ever end.
     *
     * @return Whether it became ready; startup output is never kept or echoed.
     */
    private static boolean becameReady(final InputStream output) {
        try {
            int matched = 0;
            int each;
            while ((each = output.read()) != -1) {
                if (matched >= 0 && each == READY[matched]) {
                    matched++;
                    if (matched == READY.length) {
                        return true;
                    }
                } else {
                    matched = each == '\n' ? 0 : -1;
                }
            }
        } catch (IOException ex) {
            // The adapter ended without becoming ready.
        }
        return false;
    }

    /**
     * Keeps discarding what the adapter writes after it is ready, so a line more never meets a full
     * pipe and none is kept.
     */
    private static void drain(final InputStream output) {
        Thread.ofPlatform()
                .daemon()
                .name("porelo-planning-output")
                .start(
                        () -> {
                            try (output) {
                                output.transferTo(OutputStream.nullOutputStream());
                            } catch (IOException ex) {
                                // The adapter ended.
                            }
                        });
    }

    /** Runs a step against the adapter within a limit: the varlink client waits without one. */
    private <T> T bounded(final Process process, final Supplier<T> step, final long seconds) {
        final ExecutorService runner =
                Executors.newSingleThreadExecutor(
                        task -> {
                            final Thread thread = new Thread(task, "porelo-planning-call");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            return runner.submit(step::get).get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            closeWatchers(process);
            ended(process);
            throw fault(executable + " did not answer within " + seconds + " seconds");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw fault(executable + " failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PlanningException("Interrupted while asking " + executable, ex);
        } finally {
            runner.shutdownNow();
        }
    }

    /** Ends an adapter's process and waits for it, so that it reads as ended at once. */
    private static void ended(final Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(final @Nullable VarlinkClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (IOException ex) {
                // Closing a connection to an adapter that is being ended.
            }
        }
    }
}
