package org.fuin.porelo.ecosystem.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Porelo's side of {@link EcosystemProtocol#INTERFACE}: one plugin executable, started, handshaken,
 * and asked.
 *
 * <p>A plugin that will not start, speaks a version below {@link EcosystemProtocol#LOWEST}, or
 * calls itself anything but its file's name is refused here, with its own name in the message,
 * before anything is asked of it. A plugin whose process has ended is started again at the next
 * question.
 *
 * <p>One caller at a time: a question and its answer share one connection.
 */
public final class InstalledEcosystem implements AutoCloseable {

    /** How long a plugin may take to start and to describe itself. */
    static final long READY_SECONDS = 10;

    /** How long one question may take. */
    static final long CALL_SECONDS = 120;

    /** The longest diagnostic that holds a plugin's words. */
    static final int MESSAGE = 2048;

    /** The longest version of its own a plugin is shown with. */
    static final int VERSION_LENGTH = 128;

    private static final byte[] READY = "ready ".getBytes(StandardCharsets.US_ASCII);

    private static final String METHOD_NOT_FOUND = "org.varlink.service.MethodNotFound";

    private final Path executable;

    private final Path own;

    private final Path socket;

    private final String ecosystem;

    private final Map<String, String> environment;

    private Running running;

    /** The plugin's process and the connection to it. */
    private record Running(Process process, VarlinkClient client) {}

    /** What a plugin answered to a method it may not have. */
    public sealed interface Answer {

        /**
         * It answered.
         *
         * @param reply The reply's parameters.
         */
        record Answered(Map<String, Object> reply) implements Answer {}

        /**
         * It has no such method, so it cannot say. Never "nothing found".
         *
         * @param method The method asked.
         */
        record CannotSay(String method) implements Answer {}
    }

    /**
     * Starts a plugin and completes its handshake.
     *
     * @param executable The plugin; its file's name is the ecosystem it must call itself.
     * @param directory A directory of the caller's, in which it makes an owner-only one for this
     *     plugin's socket, so that it never touches a path it did not make.
     * @throws EcosystemException When it does not start, speaks too old a version, or calls itself
     *     another ecosystem.
     */
    public InstalledEcosystem(final Path executable, final Path directory) {
        this(executable, directory, Map.of());
    }

    /**
     * Starts a plugin for {@link #resolve} alone: with the person's proxy settings, the one thing
     * of the caller's environment it may have, since the vendors are reached through them.
     *
     * @param executable The plugin.
     * @param directory A directory of the caller's, as for {@link #InstalledEcosystem(Path, Path)}.
     * @param proxies The caller's values; only {@link EcosystemProtocol#PROXY_VARIABLES} are taken,
     *     a blank one is left out.
     * @return The started plugin.
     * @throws EcosystemException As {@link #InstalledEcosystem(Path, Path)}.
     */
    public static InstalledEcosystem forResolve(
            final Path executable, final Path directory, final Map<String, String> proxies) {
        final Map<String, String> taken = new LinkedHashMap<>();
        for (final String name : EcosystemProtocol.PROXY_VARIABLES) {
            final String value = proxies.get(name);
            if (value != null && !value.isBlank()) {
                taken.put(name, value);
            }
        }
        return new InstalledEcosystem(executable, directory, taken);
    }

    private InstalledEcosystem(
            final Path executable, final Path directory, final Map<String, String> environment) {
        this.executable = executable;
        this.environment = Map.copyOf(environment);
        this.ecosystem = String.valueOf(executable.getFileName());
        try {
            this.own = Files.createTempDirectory(directory, "ecosystem-");
        } catch (IOException ex) {
            throw new EcosystemException(
                    "Cannot make a directory for " + executable + " in " + directory, ex);
        }
        this.socket = own.resolve("p.sock");
        try {
            this.running = start();
        } catch (RuntimeException ex) {
            removeOwn();
            throw ex;
        }
    }

    /**
     * Returns the command that starts a plugin: the one place it is said.
     *
     * @param executable The plugin.
     * @param socket Its socket.
     * @return The command.
     */
    static List<String> command(final Path executable, final Path socket) {
        return List.of(executable.toString(), "serve", socket.toString());
    }

    /**
     * Says why a plugin reporting a version is refused.
     *
     * @param executable The plugin.
     * @param version What it reported.
     * @param lowest The lowest this Porelo needs.
     * @return The refusal, or {@code null} for a version this Porelo can work with, a newer one
     *     included, since it calls only methods it knows.
     */
    static @Nullable String refusal(final Path executable, final long version, final int lowest) {
        return version >= lowest
                ? null
                : executable
                        + " speaks "
                        + EcosystemProtocol.INTERFACE
                        + " version "
                        + version
                        + ", and this Porelo needs at least "
                        + lowest
                        + ". Upgrade the ecosystem adapter.";
    }

    /**
     * Returns where this plugin's socket is.
     *
     * @return The path.
     */
    Path socket() {
        return socket;
    }

    /**
     * Returns the ecosystem the plugin serves.
     *
     * @return Its name.
     */
    public String ecosystem() {
        return ecosystem;
    }

    /**
     * Asks what the plugin recognizes of its tool in a directory.
     *
     * @param directory The directory to read.
     * @param home The home whose files hold the person's own settings.
     * @return What it recognized.
     * @throws EcosystemRefused When the directory holds nothing of its tool, or a file it needs
     *     cannot be read.
     * @throws EcosystemException When the plugin failed, lacks the method, or answered something
     *     that is not a detection.
     */
    public Detection detect(final Path directory, final Path home) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("directory", directory.toString());
        parameters.put("home", home.toString());
        final Map<String, Object> answer = call(EcosystemProtocol.DETECT, parameters);
        try {
            return Detection.of(answer);
        } catch (IllegalArgumentException ex) {
            throw fault(
                    executable + " answered something that is not a detection: " + ex.getMessage());
        }
    }

    /**
     * Asks the plugin to look up what its own {@link #detect} read, within {@link
     * EcosystemProtocol#RESOLVE_CALL_SECONDS}.
     *
     * @param directory The directory it read.
     * @param home The home it read.
     * @param findings What its {@link #detect} answered, and no other plugin's.
     * @return The findings that replace those, or empty when the plugin cannot say.
     * @throws EcosystemRefused When it refuses.
     * @throws EcosystemException When it failed, took longer, or answered something that is not
     *     findings.
     */
    public Optional<List<Finding>> resolve(
            final Path directory, final Path home, final List<Finding> findings) {
        return resolve(directory, home, findings, EcosystemProtocol.RESOLVE_CALL_SECONDS);
    }

    /** As {@link #resolve(Path, Path, List)}, within a bound a test can wait out. */
    Optional<List<Finding>> resolve(
            final Path directory,
            final Path home,
            final List<Finding> findings,
            final long seconds) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("directory", directory.toString());
        parameters.put("home", home.toString());
        parameters.put("findings", findings.stream().map(Finding::asMap).toList());
        final Map<String, Object> answer;
        try {
            answer = ask(EcosystemProtocol.RESOLVE, parameters, seconds);
        } catch (VarlinkException ex) {
            if (METHOD_NOT_FOUND.equals(ex.getErrorName())) {
                return Optional.empty();
            }
            throw heard(ex);
        }
        try {
            return Optional.of(Wire.list(answer, "findings", Finding::of));
        } catch (IllegalArgumentException ex) {
            throw fault(
                    executable + " answered something that is not findings: " + ex.getMessage());
        }
    }

    /**
     * Asks which files the tool's settings need so that it reaches these repositories.
     *
     * @param repositories The repositories a task declares.
     * @param extension What the project keeps for this adapter, sent only when it holds something,
     *     so a call to a plugin built before it reads as it always did.
     * @return The files.
     * @throws EcosystemRefused When it cannot answer.
     * @throws EcosystemException When the plugin failed, lacks the method, or answered a file that
     *     is no plain path inside the home.
     */
    public List<File> configure(final List<Repository> repositories, final Extension extension) {
        final Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("repositories", repositories.stream().map(Repository::asMap).toList());
        if (!extension.isEmpty()) {
            parameters.put("extension", extension.values());
        }
        final Map<String, Object> answer = call(EcosystemProtocol.CONFIGURE, parameters);
        try {
            return File.of(answer);
        } catch (IllegalArgumentException ex) {
            throw fault(executable + " answered files that cannot be placed: " + ex.getMessage());
        }
    }

    /**
     * Asks which files the tool's settings need, for a project that keeps nothing for this adapter.
     *
     * @param repositories The repositories a task declares.
     * @return The files.
     * @throws EcosystemRefused When it cannot answer.
     * @throws EcosystemException As {@link #configure(List, Extension)}.
     */
    public List<File> configure(final List<Repository> repositories) {
        return configure(repositories, Extension.NONE);
    }

    /**
     * Calls a method a plugin built against an older contract may lack.
     *
     * @param method The method.
     * @param parameters Its parameters.
     * @return The answer, or {@link Answer.CannotSay} when the plugin has no such method.
     * @throws EcosystemException On any other failure: a real one is not read as an old plugin.
     */
    public Answer optional(final String method, final Map<String, Object> parameters) {
        try {
            return new Answer.Answered(ask(method, parameters, CALL_SECONDS));
        } catch (VarlinkException ex) {
            if (METHOD_NOT_FOUND.equals(ex.getErrorName())) {
                return new Answer.CannotSay(method);
            }
            throw heard(ex);
        }
    }

    @Override
    public void close() {
        final Process process = running.process();
        closeQuietly(running.client());
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        removeOwn();
    }

    /** Removes what this client made: its directory, and in it the socket its plugin bound. */
    private void removeOwn() {
        try {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(own);
        } catch (IOException ex) {
            // Left for whoever removes the caller's directory.
        }
    }

    /** Starts the plugin, waits for it to listen, and completes the handshake. */
    private Running start() {
        final Process process;
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(command(executable, socket)).redirectErrorStream(true);
            // Nothing of Porelo's, whose environment may hold a secret. A native executable needs
            // none of it, a non-ASCII path included. A plugin for Resolve gets the proxy settings.
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException ex) {
            throw new EcosystemException("Cannot start " + executable + ": " + ex.getMessage(), ex);
        }
        final AtomicReference<@Nullable VarlinkClient> opened = new AtomicReference<>();
        try {
            awaitReady(process);
            // Inside the limit: a plugin that says it is ready and accepts nobody blocks a connect.
            final VarlinkClient client =
                    bounded(
                            process,
                            () -> {
                                final VarlinkClient connected = new VarlinkClient(socket);
                                opened.set(connected);
                                return connected;
                            },
                            READY_SECONDS);
            handshake(
                    executable,
                    bounded(
                            process,
                            () -> client.call(EcosystemProtocol.DESCRIBE, Map.of()),
                            READY_SECONDS));
            return new Running(process, client);
        } catch (RuntimeException ex) {
            ended(process);
            closeQuietly(opened.get());
            try {
                Files.deleteIfExists(socket);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex instanceof EcosystemException refused
                    ? refused
                    : fault(executable + " failed to start: " + ex.getMessage());
        }
    }

    /**
     * What an accepted plugin said of itself.
     *
     * @param protocolVersion The version of the contract it speaks.
     * @param version Its own version, made plain, or null when it says none.
     */
    record Described(int protocolVersion, @Nullable String version) {}

    static Described handshake(final Path executable, final Map<?, ?> answer) {
        final long spoken = Wire.whole(answer.get("protocolVersion")).orElse(-1);
        final String refused = refusal(executable, spoken, EcosystemProtocol.LOWEST);
        if (refused != null) {
            throw fault(refused);
        }
        final Object said = answer.get("version");
        if (said != null && !(said instanceof String)) {
            throw fault(executable + " describes a version that is no string");
        }
        if (!(answer.get("ecosystem") instanceof String says)) {
            throw fault(executable + " describes no string ecosystem");
        }
        final String ecosystem = executable.getFileName().toString();
        if (!ecosystem.equals(says)) {
            throw fault(
                    executable
                            + " calls itself '"
                            + says
                            + "', and its file is named '"
                            + ecosystem
                            + "'");
        }
        return new Described(
                // A newer plugin than any int names still speaks every version this one knows.
                (int) Math.min(spoken, Integer.MAX_VALUE),
                said instanceof String text && !text.isBlank()
                        ? Wire.plain(text, VERSION_LENGTH)
                        : null);
    }

    /**
     * Makes a failure of what a plugin said: its words made plain, and no cause, which would carry
     * them unmade into whatever prints a stack trace.
     */
    private static EcosystemException fault(final String message) {
        return new EcosystemException(Wire.plain(message, MESSAGE));
    }

    private Map<String, Object> call(final String method, final Map<String, Object> parameters) {
        try {
            return ask(method, parameters, CALL_SECONDS);
        } catch (VarlinkException ex) {
            throw heard(ex);
        }
    }

    private Map<String, Object> ask(
            final String method, final Map<String, Object> parameters, final long seconds) {
        if (!running.process().isAlive()) {
            closeQuietly(running.client());
            running = start();
        }
        final Running asked = running;
        try {
            return bounded(asked.process(), () -> asked.client().call(method, parameters), seconds);
        } catch (VarlinkException ex) {
            if (ex.getErrorName() == null) {
                // The connection failed, not the question. The process is ended and waited for
                // here, so the next question finds it ended and starts it again.
                ended(asked.process());
            }
            throw ex;
        }
    }

    /** Says what an error a plugin answered, or a failed connection to it, stands for. */
    private RuntimeException heard(final VarlinkException ex) {
        final Map<String, Object> said = ex.getParameters() == null ? Map.of() : ex.getParameters();
        final EcosystemRefused.Reason reason = EcosystemRefused.Reason.of(ex.getErrorName());
        if (reason != null) {
            return new EcosystemRefused(
                    reason, Wire.plain(String.valueOf(said.getOrDefault("detail", "")), MESSAGE));
        }
        return fault(
                executable
                        + " failed: "
                        + (ex.getErrorName() == null
                                ? ex.getMessage()
                                : ex.getErrorName() + " " + said));
    }

    /**
     * Waits for the ready line the plugin prints once its socket listens; whatever it said instead
     * is the reason.
     */
    private void awaitReady(final Process process) {
        final InputStream output = process.getInputStream();
        final String said = bounded(process, () -> saidBeforeReady(output), READY_SECONDS);
        if (said != null) {
            throw fault(
                    executable
                            + " exited without becoming ready"
                            + (said.isBlank() ? "" : ", saying: " + said));
        }
        drain(output);
    }

    /**
     * Reads until a line starts with {@code ready}, by the byte, since a line need not ever end.
     *
     * @return {@code null} once ready; else the first of what it wrote before it ended.
     */
    private static @Nullable String saidBeforeReady(final InputStream output) {
        final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        try {
            int matched = 0;
            int each;
            while ((each = output.read()) != -1) {
                if (matched >= 0 && each == READY[matched]) {
                    matched++;
                    if (matched == READY.length) {
                        return null;
                    }
                } else {
                    matched = each == '\n' ? 0 : -1;
                }
                if (kept.size() < MESSAGE) {
                    kept.write(each);
                }
            }
        } catch (IOException ex) {
            // The plugin ended; what it said is all there is.
        }
        return kept.toString(StandardCharsets.UTF_8);
    }

    /**
     * Keeps discarding what the plugin writes after it is ready, so a line more never meets a full
     * pipe and none is kept.
     */
    private static void drain(final InputStream output) {
        Thread.ofPlatform()
                .daemon()
                .name("porelo-ecosystem-output")
                .start(
                        () -> {
                            try (output) {
                                output.transferTo(OutputStream.nullOutputStream());
                            } catch (IOException ex) {
                                // The plugin ended.
                            }
                        });
    }

    /** Runs a step against the plugin within a limit: the varlink client waits without one. */
    private <T> T bounded(final Process process, final Supplier<T> step, final long seconds) {
        final ExecutorService runner =
                Executors.newSingleThreadExecutor(
                        task -> {
                            final Thread thread = new Thread(task, "porelo-ecosystem-call");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            return runner.submit(step::get).get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            process.destroyForcibly();
            throw fault(executable + " did not answer within " + seconds + " seconds");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw fault(executable + " failed: " + ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EcosystemException("Interrupted while asking " + executable, ex);
        } finally {
            runner.shutdownNow();
        }
    }

    /** Ends a plugin's process and waits for it, so that it reads as ended at once. */
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
                // Closing a connection to a plugin that is being ended.
            }
        }
    }
}
