package org.fuin.porelo.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.HelperPid;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The one {@code porelo-mcp} a task gets when its project allows it an MCP endpoint: started,
 * resumed and stopped as the task's gate is, as the same user and in a scope with {@link
 * GateWiring#LIMITS}.
 *
 * <p>Whether a task gets one is the daemon's, from the project's signed file, asked through {@link
 * McpDaemonSockets}: an empty answer starts nothing and changes nothing. What it is told follows
 * porelo-mcp's {@code doc/binding.md}: its arguments name the task, the socket it binds and the
 * daemon socket opened for it; its token comes as one line on standard input, never in an argument,
 * the environment or a log; and the task counts as started with it once it printed {@code ready}.
 *
 * <p>The gateway reaches it as the destination {@link #DESTINATION}, {@code unix:<socket>}, adding
 * the token as {@code Authorization: Bearer <token>}; the agent in the task holds only its own
 * task-scoped token for that route.
 */
final class McpWiring {

    /** Its helper name: its pid file, its log, and its entry in the task's resume record. */
    static final String HELPER = "mcp";

    /** The program, as its package installs it. */
    static final String BINARY = "porelo-mcp";

    /** The package that installs it, which a refusal names. */
    static final String PACKAGE = "porelo-mcp";

    /** The route the gateway reaches it on, which is also the variables' suffix in the task. */
    static final String DESTINATION = "porelo-mcp";

    /** The path it speaks MCP on, behind its socket. */
    static final String PATH = "/mcp";

    /** The system property that names the program outright, for a test. */
    static final String BINARY_PROPERTY = "porelo.mcp.binary";

    /** Where its standard output goes, which the start reads {@code ready} from. */
    static final String OUT = "mcp.out";

    /** What it logs, on its standard error. */
    static final String LOG = "mcp.log";

    /** What became of it, for {@code task status}. */
    static final String RECORD = "mcp.json";

    /** How long a start waits for {@code ready}. */
    static final Duration READY = Duration.ofSeconds(20);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why a task's start with an endpoint was refused, as a value an interface renders. */
    enum Refusal {
        /** The project allows the task an endpoint, and this machine has no porelo-mcp. */
        NOT_INSTALLED,
        /** The daemon socket for it could not be opened. */
        NOT_OPENED,
        /** It exited 2: wrong arguments, a refused token, or a refused socket path. */
        REFUSED_AT_START,
        /** It exited 3: its daemon socket was not reachable, or its interface not readable. */
        DAEMON_UNREACHABLE,
        /** It ended otherwise before {@code ready}, or could not be started. */
        FAILED,
        /** It printed no {@code ready} in time. */
        NOT_READY,
        /** Its key could not be kept where the gateway reads it. */
        KEY_NOT_KEPT,
        /** The gateway could not take it as a destination. */
        NO_GATEWAY
    }

    /** A start refused for its endpoint, carrying the named refusal beside the sentence. */
    static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Refusal refusal;

        Refused(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        /**
         * Returns which refusal this is.
         *
         * @return The refusal.
         */
        Refusal refusal() {
            return refusal;
        }

        /**
         * Returns the exit code a start refused for it ends with.
         *
         * @return 69 when something on this machine is missing, 70 otherwise.
         */
        int code() {
            return refusal == Refusal.NOT_INSTALLED || refusal == Refusal.DAEMON_UNREACHABLE
                    ? 69
                    : 70;
        }
    }

    /**
     * What one task's start gives its porelo-mcp.
     *
     * @param container The task.
     * @param binary The program.
     * @param socket The socket it binds and the gateway connects to.
     * @param daemonSocket The daemon socket opened for it.
     * @param token Its token, 64 hex characters.
     */
    record Endpoint(String container, Path binary, Path socket, Path daemonSocket, String token) {
        /** Without the token, as a line must never carry it. */
        @Override
        public String toString() {
            return "Endpoint[" + container + ", " + socket + "]";
        }
    }

    private McpWiring() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Asks whether the task gets an endpoint, and opens its daemon socket when it does.
     *
     * @param context Where the paths come from.
     * @param container The task.
     * @param project Its project.
     * @return What to start it with, or empty when the project allows the task none.
     * @throws Refused When it is allowed one and this machine cannot give it.
     */
    static Optional<Endpoint> open(PoreloContext context, String container, String project) {
        final Optional<Path> daemonSocket;
        try {
            daemonSocket = McpDaemonSockets.current().open(container, project);
        } catch (IOException ex) {
            throw new Refused(
                    Refusal.NOT_OPENED,
                    "the daemon socket for this task's MCP endpoint could not be opened: "
                            + ex.getMessage());
        }
        if (daemonSocket.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(endpoint(context, container, daemonSocket.get(), token()));
    }

    private static Endpoint endpoint(
            PoreloContext context, String container, Path daemonSocket, String token) {
        final Path binary = binary().orElse(null);
        if (binary == null) {
            McpDaemonSockets.current().close(container);
            throw new Refused(
                    Refusal.NOT_INSTALLED,
                    "this task's project allows it an MCP endpoint, and this machine has no "
                            + BINARY
                            + ": install the "
                            + PACKAGE
                            + " package");
        }
        return new Endpoint(container, binary, socket(context, container), daemonSocket, token);
    }

    /**
     * Returns a new token: 32 bytes from a secure random source, as 64 hex characters.
     *
     * @return The token.
     */
    static String token() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /**
     * Returns the directory the endpoints' sockets are in, {@code $XDG_RUNTIME_DIR/porelo/mcp}.
     *
     * @param context Where the paths come from.
     * @return The directory.
     */
    static Path directory(PoreloContext context) {
        return context.paths().xdg().runtime().resolve("mcp");
    }

    /**
     * Returns the socket a task's porelo-mcp binds.
     *
     * @param context Where the paths come from.
     * @param container The task.
     * @return The socket.
     */
    static Path socket(PoreloContext context, String container) {
        return directory(context).resolve(container + ".sock");
    }

    /**
     * Finds porelo-mcp as porelo finds its own helpers: named by a test, beside the running
     * program, then on the path.
     *
     * @return The program, or empty when this machine has none.
     */
    static Optional<Path> binary() {
        final String named = System.getProperty(BINARY_PROPERTY);
        if (named != null && !named.isBlank()) {
            final Path path = Path.of(named);
            return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
        }
        final Optional<String> self = ProcessHandle.current().info().command();
        if (self.isPresent()) {
            final Path parent = Path.of(self.get()).toAbsolutePath().getParent();
            if (parent != null && Files.isExecutable(parent.resolve(BINARY))) {
                return Optional.of(parent.resolve(BINARY));
            }
        }
        if (org.fuin.porelo.core.config.RealAccount.refused()) {
            // In a test's JVM the one on the path is the person's own.
            return Optional.empty();
        }
        final String path = System.getenv("PATH");
        for (final String directory : (path == null ? "/usr/bin:/bin" : path).split(":")) {
            if (!directory.isBlank() && Files.isExecutable(Path.of(directory, BINARY))) {
                return Optional.of(Path.of(directory, BINARY));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the command that serves a task's endpoint, which holds no token.
     *
     * @param endpoint What it is started with.
     * @return The command.
     */
    static List<String> serving(Endpoint endpoint) {
        return List.of(
                endpoint.binary().toString(),
                "serve",
                "--task",
                endpoint.container(),
                "--socket",
                endpoint.socket().toString(),
                "--daemon-socket",
                endpoint.daemonSocket().toString());
    }

    /**
     * Returns the destination the gateway reaches the endpoint as, keyed with its token.
     *
     * <p>{@code unix:<socket>}, with the agent's requests going to {@link #PATH} on it. Refused by
     * {@link Destination} until it takes the {@code unix:} kind.
     *
     * @param socket The endpoint's socket.
     * @return The destination.
     * @throws Refused While the gateway takes no {@code unix:} destination.
     */
    static Destination destination(Path socket) {
        try {
            return new Destination(
                    DESTINATION,
                    "Porelo's own API for this task",
                    "unix:" + socket,
                    "Authorization",
                    "Bearer ",
                    null);
        } catch (IllegalArgumentException ex) {
            throw new Refused(
                    Refusal.NO_GATEWAY,
                    "the gateway cannot reach this task's MCP endpoint: " + ex.getMessage());
        }
    }

    /**
     * Starts the task's porelo-mcp and waits for its {@code ready}.
     *
     * @param context Where the paths come from.
     * @param endpoint What it is started with.
     * @return The command it was started with, to be recorded for a resume.
     * @throws Refused When it ended or stayed silent before {@code ready}.
     */
    static List<String> start(PoreloContext context, Endpoint endpoint) {
        final Path state = context.paths().tasks().containerState(endpoint.container());
        final List<String> command = serving(endpoint);
        final Process process;
        try {
            final Path directory =
                    Directories.create(
                            directory(context),
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            // A socket a previous run left behind: porelo-mcp refuses a path that exists.
            Files.deleteIfExists(endpoint.socket());
            Files.deleteIfExists(state.resolve(OUT));
            process =
                    new ProcessBuilder(
                                    org.fuin.porelo.core.process.Scope.around(
                                            "porelo " + endpoint.container() + " " + HELPER,
                                            command,
                                            GateWiring.LIMITS))
                            .redirectOutput(state.resolve(OUT).toFile())
                            .redirectError(
                                    ProcessBuilder.Redirect.appendTo(state.resolve(LOG).toFile()))
                            .start();
        } catch (IOException ex) {
            write(state, endpoint.socket(), "failed", -1);
            throw new Refused(
                    Refusal.FAILED,
                    "could not start " + BINARY + " for this task: " + ex.getMessage());
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write((endpoint.token() + "\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ex) {
            // One that ended before it read its token says why by its exit, read below.
        }
        awaitReady(process, state, endpoint.socket());
        try {
            HelperPid.record(state.resolve(HELPER + ".pid"), process.toHandle());
        } catch (IOException ex) {
            process.toHandle().destroy();
            write(state, endpoint.socket(), "failed", -1);
            throw new Refused(
                    Refusal.FAILED,
                    "could not record "
                            + BINARY
                            + "'s process, so nothing could stop it: "
                            + ex.getMessage());
        }
        write(state, endpoint.socket(), "running", 0);
        final long pid = process.pid();
        process.onExit()
                .thenAccept(
                        ended -> {
                            // Only while the record is still this process's: a stop or a later
                            // start has said what became of it since.
                            if (pid == recordedPid(state)) {
                                final int code = ended.exitValue();
                                write(
                                        state,
                                        endpoint.socket(),
                                        code == 0 ? "stopped" : stateOf(code),
                                        code);
                            }
                        });
        return command;
    }

    private static void awaitReady(Process process, Path state, Path socket) {
        final long deadline = System.nanoTime() + READY.toNanos();
        while (System.nanoTime() < deadline) {
            if (ready(state)) {
                return;
            }
            if (!process.isAlive()) {
                if (ready(state)) {
                    return;
                }
                final int code = process.exitValue();
                write(state, socket, stateOf(code), code);
                throw refusedFor(code, state);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.toHandle().descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        write(state, socket, "failed", -1);
        throw new Refused(
                Refusal.NOT_READY,
                BINARY
                        + " said no 'ready' within "
                        + READY.toSeconds()
                        + " seconds; see "
                        + state.resolve(LOG));
    }

    private static boolean ready(Path state) {
        try {
            return Files.readAllLines(state.resolve(OUT), StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> "ready".equals(line.strip()));
        } catch (IOException ex) {
            return false;
        }
    }

    private static Refused refusedFor(int code, Path state) {
        final String log = "; see " + state.resolve(LOG);
        return switch (code) {
            case 2 ->
                    new Refused(
                            Refusal.REFUSED_AT_START, BINARY + " refused to start (exit 2)" + log);
            case 3 ->
                    new Refused(
                            Refusal.DAEMON_UNREACHABLE,
                            BINARY + " could not reach its daemon socket (exit 3)" + log);
            default ->
                    new Refused(
                            Refusal.FAILED,
                            BINARY + " ended before it was ready (exit " + code + ")" + log);
        };
    }

    private static String stateOf(int code) {
        return code == 3 ? "unreachable" : "failed";
    }

    /**
     * Starts a resumed task's porelo-mcp again, on a daemon socket opened again, with the key the
     * gateway holds for it.
     *
     * @param context Where the paths come from.
     * @param container The task.
     * @param project Its project, or {@code null} when its records name none.
     * @param key The key kept for it, or empty when it cannot be read.
     * @return {@code null} when it runs, otherwise why it does not.
     */
    static @Nullable String resume(
            PoreloContext context,
            String container,
            @Nullable String project,
            Supplier<Optional<String>> key) {
        final Path state = context.paths().tasks().containerState(container);
        final Path socket = socket(context, container);
        if (project == null) {
            write(state, socket, "absent", -1);
            return "its MCP endpoint was not started: its records name no project";
        }
        try {
            final Optional<Path> daemonSocket;
            try {
                daemonSocket = McpDaemonSockets.current().open(container, project);
            } catch (IOException ex) {
                throw new Refused(
                        Refusal.NOT_OPENED,
                        "the daemon socket for its MCP endpoint could not be opened: "
                                + ex.getMessage());
            }
            if (daemonSocket.isEmpty()) {
                write(state, socket, "absent", -1);
                return "its MCP endpoint was not started: its project allows it none now";
            }
            final Optional<String> kept = key.get();
            if (kept.isEmpty()) {
                McpDaemonSockets.current().close(container);
                write(state, socket, "absent", -1);
                return "its MCP endpoint was not started: its key is not in the vault, which"
                        + " may be locked; 'porelo vault unlock' and start it again";
            }
            start(context, endpoint(context, container, daemonSocket.get(), kept.get()));
            return null;
        } catch (Refused ex) {
            McpDaemonSockets.current().close(container);
            return ex.getMessage();
        }
    }

    /**
     * Stops a task's porelo-mcp, closes the daemon socket opened for it and removes its own socket.
     * Doing it twice, or for a task that had none, does nothing.
     *
     * @param context Where the paths come from.
     * @param container The task.
     */
    static void stop(PoreloContext context, String container) {
        final Path state = context.paths().tasks().containerState(container);
        final Path pidFile = state.resolve(HELPER + ".pid");
        final Optional<ProcessHandle> process = HelperPid.verified(pidFile);
        process.ifPresent(HelperPid::stop);
        process.ifPresent(
                handle -> {
                    try {
                        handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException
                            | java.util.concurrent.TimeoutException ex) {
                        // Said by the stop that counts what survived.
                    }
                });
        try {
            Files.deleteIfExists(pidFile);
        } catch (IOException ex) {
            // A pid file left names a process that is stopped; it is verified before any signal.
        }
        McpDaemonSockets.current().close(container);
        final Path socket = socket(context, container);
        try {
            // porelo-mcp removes the path it bound when it stops; this is what one that could not
            // left, which the next start would otherwise be refused for.
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The next start removes it.
        }
        if (Files.isRegularFile(state.resolve(RECORD), LinkOption.NOFOLLOW_LINKS)) {
            write(state, socket, "stopped", 0);
        }
    }

    /**
     * Returns what {@code task status} says of a task's porelo-mcp, as it says the gate's.
     *
     * @param context Where the paths come from.
     * @param container The task.
     * @return The line, or {@code null} for a task that never had one.
     */
    static @Nullable String statusLine(PoreloContext context, String container) {
        final Path state = context.paths().tasks().containerState(container);
        final Map<?, ?> record = record(state);
        if (record == null) {
            return null;
        }
        final String said = String.valueOf(record.get("state"));
        final int code = record.get("code") instanceof Number number ? number.intValue() : -1;
        return switch (said) {
            case "running" ->
                    TaskLifecycle.alive(state.resolve(HELPER + ".pid"))
                            ? "running, " + record.get("socket")
                            : "absent - its process is gone";
            case "stopped" -> "stopped";
            case "unreachable" -> "could not reach its daemon socket, exit " + code;
            case "failed" -> code < 0 ? "failed to start" : "failed, exit " + code;
            default -> "absent - " + record.get("why");
        };
    }

    private static @Nullable Map<?, ?> record(Path state) {
        try {
            return Json.parse(Files.readString(state.resolve(RECORD), StandardCharsets.UTF_8))
                            instanceof Map<?, ?> map
                    ? map
                    : null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Returns the pid the record names, read as it is written and not verified: asked after the
     * process ended, when verifying it would always fail.
     */
    private static long recordedPid(Path state) {
        try {
            final String[] parts =
                    Files.readString(state.resolve(HELPER + ".pid"), StandardCharsets.UTF_8)
                            .strip()
                            .split("\\s+");
            return Long.parseLong(parts[0]);
        } catch (IOException | RuntimeException ex) {
            return -1L;
        }
    }

    private static void write(Path state, Path socket, String said, int code) {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("state", said);
        record.put("socket", socket.toString());
        record.put("code", code);
        try {
            Files.writeString(state.resolve(RECORD), Json.write(record), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Status then says nothing of it, which is what it says of a task without one.
        }
    }
}
