package org.fuin.porelo.agent.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.jspecify.annotations.Nullable;

/**
 * An agent binary installed on this machine.
 *
 * <p>Porelo never links an agent, so everything it knows comes from asking one. The process is
 * started on demand and stopped when the work is done: agents are used a handful of times per task,
 * and a resident process per installed agent would be cost with no matching benefit.
 *
 * <p><strong>The protocol version is checked before anything else.</strong> Porelo and the agents
 * ship as separate packages, so an untested combination is a matter of time; refusing it with a
 * clear message beats reading a field that means something else now.
 */
public class InstalledAgent implements AutoCloseable {

    /** How long to wait for a spawned agent to create its socket. */
    static final long READY_TIMEOUT_SECONDS = 10;

    private final Path executable;

    private final Path socket;

    private final Process process;

    private static final String METHOD_NOT_FOUND = "org.varlink.service.MethodNotFound";

    private final VarlinkClient client;

    private final AgentDefinition definition;

    /** What the agent wrote after its ready line, the last few thousand characters of it. */
    private final StringBuffer afterReady = new StringBuffer();

    /**
     * Returns a socket path no other lookup uses, in this process or another.
     *
     * <p>Named after the pid alone, two lookups at once shared it: the second adapter found the
     * first one's socket live and exited, the lookup listed no agent, and one finishing deleted the
     * path from under one still starting.
     *
     * @param executable The agent binary.
     * @param socketDirectory Directory the socket is created in.
     * @return The path.
     */
    static Path socketFor(final Path executable, final Path socketDirectory) {
        return socketDirectory.resolve(
                executable.getFileName()
                        + "-"
                        + ProcessHandle.current().pid()
                        + "-"
                        + LOOKUPS.incrementAndGet()
                        + ".sock");
    }

    /** Counts the lookups of this process, so each has a socket of its own. */
    private static final java.util.concurrent.atomic.AtomicLong LOOKUPS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Starts an agent binary and completes its handshake.
     *
     * @param executable The agent binary.
     * @param socketDirectory Directory to create the socket in.
     * @throws AgentException If the agent cannot be started, or speaks a protocol this Porelo does
     *     not understand.
     */
    public InstalledAgent(Path executable, Path socketDirectory) {

        this.executable = executable;
        this.socket = socketFor(executable, socketDirectory);

        try {
            Directories.create(socketDirectory);
            Files.deleteIfExists(socket);
            this.process =
                    new ProcessBuilder(executable.toString(), "serve", socket.toString())
                            .redirectErrorStream(true)
                            .start();
        } catch (IOException ex) {
            throw new AgentException("Cannot start " + executable, ex);
        }

        VarlinkClient opened = null;
        try {
            awaitReady();
            opened = new VarlinkClient(socket);
            this.client = opened;
            this.definition = bounded("describe itself", this::handshake);
        } catch (RuntimeException ex) {
            // Everything it left goes, and the failure is the agent's: as a VarlinkException it
            // escaped the list, which threw the whole lookup away and left the agents started
            // before it running.
            process.destroyForcibly();
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closing) {
                    // Closing a connection to a process just ended.
                }
            }
            try {
                Files.deleteIfExists(socket);
            } catch (IOException deleting) {
                // A socket left in the runtime directory is named for this lookup alone.
            }
            throw ex instanceof AgentException agent
                    ? agent
                    : new AgentException(executable + " failed to start: " + ex.getMessage(), ex);
        }
    }

    /**
     * Keeps reading what the agent writes after its ready line, on a thread of its own, until it
     * ends.
     *
     * <p>Read rather than closed: closed, an agent that wrote one more line met a broken pipe, and
     * what it wrote is what says why it did not answer. The last few thousand characters are kept
     * for that.
     */
    private void drain(BufferedReader reader) {
        Thread.ofPlatform()
                .daemon()
                .name("porelo-agent-output")
                .start(
                        () -> {
                            try (reader) {
                                final char[] chunk = new char[1024];
                                int read;
                                while ((read = reader.read(chunk)) >= 0) {
                                    synchronized (afterReady) {
                                        afterReady.append(chunk, 0, read);
                                        if (afterReady.length() > 4096) {
                                            afterReady.delete(0, afterReady.length() - 4096);
                                        }
                                    }
                                }
                            } catch (IOException ex) {
                                // The agent ended, or was ended.
                            }
                        });
    }

    private void awaitReady() {

        // Waiting for the line the agent prints rather than polling for the socket file: the file
        // appears before bind() returns, so polling can connect to a socket nobody is listening on.
        final StringBuilder said = new StringBuilder();
        boolean exited = false;

        final BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        boolean draining = false;
        try {
            final long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
            // Character by character, only what is there: a whole line was read once anything was
            // ready, and an adapter that printed a word with no line end and hung held every task
            // start for ever.
            final StringBuilder line = new StringBuilder();
            while (System.nanoTime() < deadline) {
                while (reader.ready()) {
                    final int c = reader.read();
                    if (c < 0) {
                        exited = true;
                        break;
                    }
                    if (c != '\n') {
                        if (line.length() < 4096) {
                            line.append((char) c);
                        }
                        continue;
                    }
                    if (line.toString().startsWith("ready ")) {
                        drain(reader);
                        draining = true;
                        return;
                    }
                    // Whatever it said instead is the diagnostic worth reporting.
                    said.append(said.isEmpty() ? "" : "; ").append(line.toString().strip());
                    line.setLength(0);
                }
                if (exited) {
                    break;
                }
                if (!process.isAlive()) {
                    exited = true;
                    break;
                }
                // Sleeping, not spinning. This was 'Thread.onSpinWait()', which burns a core for as
                // long as the agent takes to answer - up to the whole timeout for one that hangs,
                // on every launch and for every agent asked. A millisecond costs nothing against a
                // process that is starting and bounds the waste at a thousand wakeups.
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    exited = true;
                    break;
                }
            }
        } catch (IOException ex) {
            throw new AgentException("Cannot read from " + executable, ex);
        } finally {
            if (!draining) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    // Closing the pipe of a process being given up on.
                }
            }
        }

        process.destroyForcibly();

        // A binary that died immediately and one that hung are different problems, and saying "did
        // not become ready within 10 seconds" about the first sends an operator looking for a slow
        // agent when they have a crashing one.
        if (exited) {
            throw new AgentException(
                    executable
                            + " exited without becoming ready"
                            + (said.isEmpty() ? "" : ", saying: " + said));
        }
        throw new AgentException(
                executable + " did not become ready within " + READY_TIMEOUT_SECONDS + " seconds");
    }

    /**
     * Runs a step that talks to the agent within the limit a start has: the client waits without
     * one, and an agent that accepted the connection and never answered held every task start.
     */
    private <T> T bounded(final String asked, final java.util.function.Supplier<T> step) {
        try {
            return bounded(executable, asked, step, READY_TIMEOUT_SECONDS);
        } catch (AgentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains(" within ")) {
                throw new AgentException(
                        ex.getMessage() + diagnosis(process.isAlive(), afterReady.toString()));
            }
            throw ex;
        }
    }

    /**
     * Runs a step that talks to an agent within a limit, on a thread of its own that ends with it.
     *
     * <p>A platform thread, not a virtual one: on a virtual thread the one step of a lookup that
     * waits on the agent waited on the scheduler too, and on a machine of two processors under load
     * an agent that answered in a tenth of a second was given up on. Closed after each use, and
     * interrupted at the limit, so a step that never ends leaves no thread behind.
     *
     * @param executable The agent, for the message.
     * @param asked What it was asked to do, for the message.
     * @param step What to run.
     * @param seconds The limit.
     * @return What the step returned.
     */
    static <T> T bounded(
            Path executable, String asked, java.util.function.Supplier<T> step, long seconds) {
        final java.util.concurrent.ExecutorService runner =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        task -> {
                            final Thread thread = new Thread(task, "porelo-agent-describe");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            return runner.submit(step::get).get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new AgentException(
                    executable + " did not " + asked + " within " + seconds + " seconds");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted while asking " + executable, ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException failed
                    ? failed
                    : new AgentException(executable + " failed to " + asked, ex);
        } finally {
            runner.shutdownNow();
        }
    }

    /**
     * Says what is known of an agent that did not answer in time: whether it still runs, and what
     * it wrote after it said it was ready - what tells a slow agent from a stuck one from a dead
     * one.
     *
     * @param alive Whether its process still runs.
     * @param said What it wrote since its ready line.
     * @return The words to add to the refusal.
     */
    static String diagnosis(boolean alive, String said) {
        final String last = said.strip();
        return (alive ? " (still running" : " (it had exited")
                + "; since it was ready it wrote "
                + (last.isEmpty() ? "nothing" : "'" + last.replaceAll("\\p{Cntrl}+", " ") + "'")
                + ")";
    }

    /**
     * Says why an agent reporting a version is refused.
     *
     * @param executable The agent binary.
     * @param version What it reported.
     * @return The refusal, or {@code null} for a version this Porelo can work with, a newer one
     *     included, since it calls only methods it knows.
     */
    static @Nullable String refusal(final Path executable, final int version) {
        return version >= AgentProtocol.LOWEST
                ? null
                : executable
                        + " speaks agent protocol "
                        + version
                        + ", and this Porelo needs at least "
                        + AgentProtocol.LOWEST
                        + ". Upgrade the agent.";
    }

    /** What an agent answered to a method it may not have. */
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
     * Calls a method an agent built against an older contract may lack.
     *
     * @param client The connection to the agent.
     * @param method The method.
     * @param parameters Its parameters.
     * @return The answer, or {@link Answer.CannotSay} when the agent has no such method.
     * @throws org.fuin.porelo.wire.varlink.VarlinkException On any other error: a real failure is
     *     not read as an old agent.
     */
    static Answer optional(
            final VarlinkClient client, final String method, final Map<String, Object> parameters) {
        try {
            return new Answer.Answered(client.call(method, parameters));
        } catch (org.fuin.porelo.wire.varlink.VarlinkException ex) {
            if (METHOD_NOT_FOUND.equals(ex.getErrorName())) {
                return new Answer.CannotSay(method);
            }
            throw ex;
        }
    }

    /**
     * Calls a method this agent may lack.
     *
     * @param method The method.
     * @param parameters Its parameters.
     * @return The answer, or {@link Answer.CannotSay} when the agent has no such method.
     */
    public Answer optional(final String method, final Map<String, Object> parameters) {
        return optional(client, method, parameters);
    }

    private AgentDefinition handshake() {
        return handshake(client, executable);
    }

    /**
     * Asks an agent what it is, and refuses one this Porelo cannot work with.
     *
     * @param client The connection to the agent.
     * @param executable The agent binary, named in a refusal.
     * @return Its definition.
     * @throws AgentException When it is refused.
     */
    static AgentDefinition handshake(final VarlinkClient client, final Path executable) {

        final Map<String, Object> answer = client.call(AgentProtocol.DESCRIBE, Map.of());

        if (!(answer.get("protocolVersion") instanceof Number version)) {
            throw new AgentException(
                    executable
                            + " reported no agent protocol version, so it is broken rather than"
                            + " old. Reinstall the agent.");
        }
        final String refused =
                refusal(executable, org.fuin.porelo.wire.varlink.VarlinkNumbers.intOf(version));
        if (refused != null) {
            throw new AgentException(refused);
        }
        if (!(answer.get("definition") instanceof Map<?, ?> definition)) {
            throw new AgentException(executable + " returned no definition");
        }
        return AgentDefinitionJson.read(definition);
    }

    /**
     * Returns the definition this agent reported.
     *
     * @return The definition.
     */
    public AgentDefinition definition() {
        return definition;
    }

    /**
     * Returns the agent's name.
     *
     * @return Name.
     */
    public String name() {
        return definition.name();
    }

    /**
     * Returns the binary this agent was loaded from.
     *
     * @return Executable path.
     */
    public Path executable() {
        return executable;
    }

    /**
     * Asks the agent to read its credential.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @return The credential, or empty if it has not been logged in.
     */
    public Optional<Credential> extractCredential(Path configDirectory) {
        return extractCredential(configDirectory, null);
    }

    /**
     * Asks the agent to read its credential for one provider.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @param provider The provider asked for, sent only when given; or {@code null}.
     * @return The credential, naming its provider where the agent does, or empty if it holds none.
     */
    public Optional<Credential> extractCredential(Path configDirectory, @Nullable String provider) {
        return credentialOf(
                client.call(
                        AgentProtocol.EXTRACT_CREDENTIAL,
                        extractParameters(configDirectory, provider)));
    }

    /**
     * Returns what is asked of an agent for its credential.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @param provider The provider asked for, or {@code null}.
     * @return The parameters.
     */
    static Map<String, Object> extractParameters(Path configDirectory, @Nullable String provider) {
        return provider == null
                ? Map.of("configDirectory", configDirectory.toString())
                : Map.of("configDirectory", configDirectory.toString(), "provider", provider);
    }

    /** An agent's answer named a provider in a form no provider's name can have. */
    public static final class UnusableProvider extends AgentException {

        private static final long serialVersionUID = 1L;

        UnusableProvider() {
            super("this agent named its credential's provider in a form no name can have");
        }
    }

    /** Never echoed: what an agent sent is printed and returned only once it reads as a name. */
    private static String usable(final String named) {
        if (named.getBytes(StandardCharsets.UTF_8).length > 4096
                || named.chars().anyMatch(Character::isISOControl)) {
            throw new UnusableProvider();
        }
        return named;
    }

    /**
     * Asks the agent what it finds of itself on this machine, within the limit a start has.
     *
     * <p>Over a connection of its own: the limit interrupts the read, which closes the channel it
     * reads, and the start that goes on needs the agent's connection.
     *
     * @param configDirectory The directory the definition declares, expanded.
     * @return The findings, in the tool's order; one that cannot say when the agent has no such
     *     method.
     * @throws RuntimeException If it failed some other way, which is not read as an old agent.
     */
    public List<Finding> find(final Path configDirectory) {
        return bounded(
                "say what it finds",
                () -> {
                    try (VarlinkClient own = new VarlinkClient(socket)) {
                        return switch (optional(
                                own,
                                AgentProtocol.FIND,
                                Map.of("configDirectory", configDirectory.toString()))) {
                            case Answer.Answered answered -> findings(answered.reply());
                            case Answer.CannotSay cannotSay -> List.of(Finding.cannotSay());
                        };
                    } catch (IOException closing) {
                        throw new AgentException(executable + " could not be asked what it finds");
                    }
                });
    }

    /**
     * Reads an agent's answer to {@code Find}.
     *
     * @param answer The answer.
     * @return The findings it holds that are usable, in its order.
     */
    static List<Finding> findings(final Map<String, Object> answer) {
        if (!(answer.get("findings") instanceof List<?> raw)) {
            return List.of();
        }
        final List<Finding> kept = new java.util.ArrayList<>();
        for (final Object entry : raw) {
            final Finding finding = entry == null ? null : Finding.read(entry);
            if (finding != null) {
                kept.add(finding);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Reads an agent's answer to {@code ExtractCredential}.
     *
     * @param answer The answer.
     * @return The credential, or empty.
     */
    static Optional<Credential> credentialOf(Map<String, Object> answer) {
        if (!Boolean.TRUE.equals(answer.get("found"))) {
            return Optional.empty();
        }
        return Optional.of(
                new Credential(
                        String.valueOf(answer.get("type")),
                        String.valueOf(answer.get("secret")),
                        attributes(answer.get("attributes")),
                        answer.get("provider") instanceof String named ? usable(named) : null));
    }

    /**
     * Asks the agent what it needs placed in a container before it starts.
     *
     * @param context What the agent is told about this task.
     * @return Files to write, possibly empty.
     */
    public List<ContainerFile> containerSetup(SetupContext context) {

        final Map<String, Object> answer =
                client.call(AgentProtocol.CONTAINER_SETUP, context.parameters());

        final List<ContainerFile> files = new java.util.ArrayList<>();
        if (answer.get("files") instanceof List<?> list) {
            for (final Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    files.add(
                            new ContainerFile(
                                    String.valueOf(entry.get("path")),
                                    String.valueOf(entry.get("content")),
                                    Boolean.TRUE.equals(entry.get("ownerOnly"))));
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * Asks the agent to build a command line.
     *
     * @param request What to run.
     * @return Command and arguments.
     */
    public List<String> buildCommand(RunRequest request) {

        final Object command =
                client.call(AgentProtocol.BUILD_COMMAND, parameters(request)).get("command");
        if (!(command instanceof List<?> list)) {
            throw new AgentException(name() + " returned no command");
        }
        final List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    /**
     * Returns a request as the parameters of a {@code BuildCommand} call.
     *
     * @param request What to run.
     * @return Parameters, an absent value left out.
     */
    static Map<String, Object> parameters(RunRequest request) {
        final Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("prompt", request.prompt());
        if (request.model() != null) {
            parameters.put("model", request.model());
        }
        if (request.maxTurns() != null) {
            parameters.put("maxTurns", request.maxTurns());
        }
        if (request.resumeSession() != null) {
            parameters.put("resumeSession", request.resumeSession());
        }
        parameters.put("verbose", Boolean.valueOf(request.verbose()));
        parameters.put("machineReadable", Boolean.valueOf(request.machineReadable()));
        // Always sent, 0 too, so an adapter acts on the task's number and never on a default.
        parameters.put("subagents", Integer.valueOf(request.subagents()));
        return parameters;
    }

    /**
     * Asks the agent to format a log file.
     *
     * @param source File holding the agent's raw output.
     * @param line Receives each formatted line.
     */
    public void formatLog(Path source, java.util.function.Consumer<String> line) {
        client.callMore(
                AgentProtocol.FORMAT_LOG,
                Map.of("source", source.toString()),
                reply -> {
                    if (reply.get("line") instanceof String text) {
                        line.accept(text);
                    }
                    return true;
                });
    }

    /**
     * Asks the agent to format lines of its output in place.
     *
     * @param lines Lines of its raw output.
     * @return Each line as the agent shows it, or {@code null} for a line it hides; the lines as
     *     they are from an agent that does not format them.
     */
    public List<@Nullable String> format(List<String> lines) {
        return format(client, lines);
    }

    static List<@Nullable String> format(final VarlinkClient client, final List<String> lines) {
        // An agent built before the method shows its lines as they are.
        if (optional(client, AgentProtocol.FORMAT, Map.of("lines", lines))
                        instanceof Answer.Answered(Map<String, Object> reply)
                && reply.get("lines") instanceof List<?> shown
                && shown.size() == lines.size()) {
            final List<@Nullable String> result = new ArrayList<>();
            shown.forEach(line -> result.add(line instanceof String text ? text : null));
            return result;
        }
        return new ArrayList<>(lines);
    }

    /**
     * Asks the agent whether a line of its output is the end of its run.
     *
     * @param line One line of its raw output.
     * @return How the run ended, or {@code null} for a line that is not its end, or for an agent
     *     that does not say.
     */
    public @Nullable AgentEnd ended(String line) {
        return ended(client, line);
    }

    static @Nullable AgentEnd ended(final VarlinkClient client, final String line) {
        // An agent built before the method says nothing about its end, as before.
        return optional(client, AgentProtocol.ENDED, Map.of("line", line))
                                instanceof Answer.Answered(Map<String, Object> reply)
                        && reply.get("ended") instanceof Map<?, ?> ended
                ? AgentEnd.of(ended)
                : null;
    }

    private static Map<String, String> attributes(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        final Map<String, String> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ex) {
            // Shutting down.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The next start unlinks first.
        }
    }
}
