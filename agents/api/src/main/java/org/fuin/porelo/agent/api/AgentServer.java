package org.fuin.porelo.agent.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * Serves one agent over varlink.
 *
 * <p>Linked into the agent's own binary, not into Porelo. An agent's {@code main} is therefore
 * three lines, and every agent answers the same four calls in the same way - the differences live
 * in the {@link Agent} it was given, which is where they belong.
 */
public class AgentServer implements AutoCloseable {

    private final Agent agent;

    private final VarlinkServer server;

    /**
     * Binds the socket and registers the protocol.
     *
     * @param agent The agent to serve.
     * @param socket Where to create the socket.
     */
    public AgentServer(Agent agent, Path socket) {

        this.agent = agent;
        this.server = new VarlinkServer(socket, AgentProtocol.INTERFACE);

        server.method(
                "Describe",
                (parameters, replies) -> {
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("protocolVersion", Integer.valueOf(AgentProtocol.VERSION));
                    answer.put("definition", AgentDefinitionJson.write(agent.definition()));
                    replies.last(answer);
                });

        server.method(
                "ExtractCredential",
                (parameters, replies) -> {
                    final Object directory = parameters.get("configDirectory");
                    if (!(directory instanceof String path)) {
                        throw new AgentException("ExtractCredential needs a configDirectory");
                    }
                    final Optional<Credential> credential =
                            agent.credentialExtractor()
                                    .extract(Path.of(path), optional(parameters, "provider"));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("found", Boolean.valueOf(credential.isPresent()));
                    credential.ifPresent(
                            value -> {
                                answer.put("type", value.type());
                                answer.put("secret", value.secret());
                                answer.put("attributes", value.attributes());
                                if (value.provider() != null) {
                                    answer.put("provider", value.provider());
                                }
                            });
                    replies.last(answer);
                });

        server.method(
                "Find",
                (parameters, replies) -> {
                    if (!(parameters.get("configDirectory") instanceof String path)) {
                        throw new AgentException("Find needs a configDirectory");
                    }
                    final List<Finding> found;
                    try {
                        found = agent.finder().find(Path.of(path));
                    } catch (java.io.IOException ex) {
                        // An error answered, not the connection closed, which serves the whole
                        // start. Without its words: a path it failed on may hold anything.
                        throw new AgentException("Find could not read what it looks at");
                    }
                    final List<Map<String, Object>> findings = new ArrayList<>();
                    for (final Finding finding : found) {
                        findings.add(finding.asMap());
                    }
                    replies.last(Map.of("findings", findings));
                });

        server.method(
                "ContainerSetup",
                (parameters, replies) -> {
                    final List<Map<String, Object>> files = new java.util.ArrayList<>();
                    final SetupContext context =
                            new SetupContext(
                                    String.valueOf(parameters.get("token")),
                                    String.valueOf(parameters.get("credentialType")),
                                    String.valueOf(parameters.get("workspace")),
                                    text(parameters.get("endpoint")),
                                    text(parameters.get("provider")),
                                    subagents(parameters));
                    for (final ContainerFile file : agent.containerSetup().files(context)) {
                        final Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", file.path());
                        entry.put("content", file.content());
                        entry.put("ownerOnly", Boolean.valueOf(file.ownerOnly()));
                        files.add(entry);
                    }
                    replies.last(Map.of("files", files));
                });

        server.method(
                "BuildCommand",
                (parameters, replies) -> {
                    final Object prompt = parameters.get("prompt");
                    if (!(prompt instanceof String text)) {
                        throw new AgentException("BuildCommand needs a prompt");
                    }
                    final RunRequest request =
                            new RunRequest(
                                    text,
                                    optional(parameters, "model"),
                                    integer(parameters, "maxTurns"),
                                    optional(parameters, "resumeSession"),
                                    Boolean.TRUE.equals(parameters.get("verbose")),
                                    Boolean.TRUE.equals(parameters.get("machineReadable")),
                                    subagents(parameters));
                    replies.last(Map.of("command", agent.headlessCommand(request)));
                });

        server.method(
                "Format",
                (parameters, replies) -> {
                    // Line by line, in place: the whole file was read for every update of a console
                    // that shows its end.
                    final List<@org.jspecify.annotations.Nullable String> formatted =
                            new ArrayList<>();
                    if (parameters.get("lines") instanceof List<?> lines) {
                        for (final Object line : lines) {
                            formatted.add(
                                    line instanceof String text
                                            ? agent.logFormatter().format(text)
                                            : null);
                        }
                    }
                    replies.last(Map.of("lines", formatted));
                });

        server.method(
                "Ended",
                (parameters, replies) -> {
                    final AgentEnd ended =
                            parameters.get("line") instanceof String line
                                    ? agent.ended(line)
                                    : null;
                    replies.last(ended == null ? Map.of() : Map.of("ended", ended.asMap()));
                });

        server.method(
                "FormatLog",
                (parameters, replies) -> {
                    final Object source = parameters.get("source");
                    if (!(source instanceof String path)) {
                        throw new AgentException("FormatLog needs a source");
                    }
                    if (!replies.streaming()) {
                        // Answering a non-streaming FormatLog with one line would look like it
                        // worked and then deliver nothing.
                        throw new AgentException("FormatLog requires more=true");
                    }
                    final List<String> formatted = new ArrayList<>();
                    for (final String line : java.nio.file.Files.readAllLines(Path.of(path))) {
                        final String shown = agent.logFormatter().format(line);
                        if (shown != null) {
                            formatted.add(shown);
                        }
                    }
                    for (int i = 0; i < formatted.size() - 1; i++) {
                        replies.more(Map.of("line", formatted.get(i)));
                    }
                    replies.last(
                            formatted.isEmpty()
                                    ? Map.of()
                                    : Map.of("line", formatted.get(formatted.size() - 1)));
                });
    }

    /**
     * Returns the agent being served.
     *
     * @return The agent.
     */
    public Agent agent() {
        return agent;
    }

    /**
     * Returns the socket path.
     *
     * @return Path Porelo connects to.
     */
    public Path socketPath() {
        return server.socketPath();
    }

    /** Serves until the process is stopped. */
    public void serve() {
        server.run();
    }

    @Override
    public void close() {
        server.close();
    }

    private static @Nullable String optional(Map<String, Object> parameters, String key) {
        return parameters.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    /**
     * Returns how many subagents the task allows, 0 when an older Porelo sent none.
     *
     * @param parameters The call's parameters.
     * @return The number.
     * @throws AgentException If one was sent and it is no whole number from 0 up: read as a lower
     *     one, it would bound the agent to something nobody asked for.
     */
    private static int subagents(Map<String, Object> parameters) {
        final Object given = parameters.get("subagents");
        if (given == null) {
            return 0;
        }
        final Integer subagents = SubagentLimit.wholeNumber(given);
        if (subagents == null) {
            throw new AgentException(
                    "subagents takes a whole number from 0 to "
                            + SubagentLimit.MOST
                            + ", not "
                            + (given instanceof String text ? "'" + text + "'" : given));
        }
        return subagents;
    }

    private static @Nullable Integer integer(Map<String, Object> parameters, String key) {
        return parameters.get(key) instanceof Number value
                ? Integer.valueOf(org.fuin.porelo.wire.varlink.VarlinkNumbers.intOf(value))
                : null;
    }

    /**
     * Returns an optional varlink string parameter, never {@code null}.
     *
     * @param value Raw value, possibly absent.
     * @return The value, or an empty string.
     */
    private static String text(@org.jspecify.annotations.Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
