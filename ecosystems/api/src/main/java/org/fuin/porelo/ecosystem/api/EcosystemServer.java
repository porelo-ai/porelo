package org.fuin.porelo.ecosystem.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * The plugin's side of {@link EcosystemProtocol#INTERFACE}: binds the socket and answers Porelo
 * with an {@link Ecosystem}.
 */
public class EcosystemServer implements AutoCloseable {

    private final VarlinkServer server;

    /**
     * Binds the socket and registers the protocol.
     *
     * @param ecosystem The plugin to serve.
     * @param socket Where to create the socket.
     */
    public EcosystemServer(final Ecosystem ecosystem, final Path socket) {

        this.server = new VarlinkServer(socket, EcosystemProtocol.INTERFACE).describedBy(idl());

        server.method(
                "Describe",
                (parameters, replies) -> {
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("protocolVersion", Integer.valueOf(EcosystemProtocol.VERSION));
                    answer.put("ecosystem", ecosystem.ecosystem());
                    replies.last(answer);
                });

        server.method(
                "Detect",
                (parameters, replies) -> {
                    final Path directory = path(parameters, "directory");
                    final Path home = path(parameters, "home");
                    replies.last(answering(() -> ecosystem.detect(directory, home)).asMap());
                });

        server.method(
                "Resolve",
                (parameters, replies) -> {
                    final Path directory = path(parameters, "directory");
                    final Path home = path(parameters, "home");
                    final List<Finding> findings = findings(parameters);
                    final List<Finding> resolved =
                            answering(() -> ecosystem.resolve(directory, home, findings))
                                    .orElseThrow(
                                            // The answer of a plugin built before Resolve: it
                                            // cannot say, which is never "nothing found".
                                            () ->
                                                    new VarlinkException(
                                                            "org.varlink.service.MethodNotFound",
                                                            Map.of(
                                                                    "method",
                                                                    EcosystemProtocol.RESOLVE)));
                    replies.last(
                            Map.of("findings", resolved.stream().map(Finding::asMap).toList()));
                });

        server.method(
                "Configure",
                (parameters, replies) -> {
                    final List<Repository> repositories = repositories(parameters);
                    final List<File> files = answering(() -> ecosystem.configure(repositories));
                    replies.last(Map.of("files", files.stream().map(File::asMap).toList()));
                });
    }

    /**
     * Returns the socket path.
     *
     * @return Path Porelo connects to.
     */
    public Path socketPath() {
        return server.socketPath();
    }

    /**
     * Returns the registered methods for comparison with the published interface.
     *
     * @return Fully qualified names, including the standard service methods.
     */
    Set<String> methodNames() {
        return server.methodNames();
    }

    /** Serves until the process is stopped. */
    public void serve() {
        server.run();
    }

    @Override
    public void close() {
        server.close();
    }

    /**
     * Returns the interface description, as the jar carries it.
     *
     * @return The IDL.
     */
    static String idl() {
        try (InputStream in =
                EcosystemServer.class.getResourceAsStream(
                        EcosystemProtocol.INTERFACE + ".varlink")) {
            if (in == null) {
                throw new IllegalStateException(
                        "The jar has no " + EcosystemProtocol.INTERFACE + ".varlink");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Cannot read " + EcosystemProtocol.INTERFACE + ".varlink", ex);
        }
    }

    /** A refusal travels as the contract's error of that name, never as an empty answer. */
    private static <T> T answering(final Supplier<T> call) {
        try {
            return call.get();
        } catch (EcosystemRefused ex) {
            throw new VarlinkException(
                    EcosystemProtocol.error(ex.reason()),
                    ex.reason() == EcosystemRefused.Reason.UNREADABLE
                            ? Map.of("detail", ex.detail())
                            : Map.of());
        }
    }

    private static Path path(final Map<String, Object> parameters, final String name) {
        try {
            return Path.of(Wire.required(parameters, name));
        } catch (IllegalArgumentException ex) {
            // InvalidPathException is one too: a name no path can hold.
            throw invalid(name);
        }
    }

    private static List<Finding> findings(final Map<String, Object> parameters) {
        try {
            return Wire.list(parameters, "findings", Finding::of);
        } catch (IllegalArgumentException ex) {
            throw invalid("findings");
        }
    }

    private static List<Repository> repositories(final Map<String, Object> parameters) {
        try {
            return Wire.list(parameters, "repositories", Repository::of);
        } catch (IllegalArgumentException ex) {
            throw invalid("repositories");
        }
    }

    private static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }
}
