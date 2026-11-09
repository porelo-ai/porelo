package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.agent.api.AgentDefinitionReader;

/**
 * The repository's agent fixture, found from its definition under {@code agents/} rather than named
 * in code, so a test can start a real agent without writing an agent's name as a literal.
 */
final class FixtureAgent {

    private static final Path AGENTS = Path.of("..", "..", "agents");

    private FixtureAgent() {}

    /** The fixture's definition file in the source tree; there must be exactly one agent. */
    static Path definition() {
        try (Stream<Path> modules = Files.list(AGENTS)) {
            final List<Path> definitions =
                    modules.map(module -> module.resolve("src/main/resources/agent"))
                            .filter(Files::isDirectory)
                            .flatMap(FixtureAgent::yamlFiles)
                            .toList();
            if (definitions.size() != 1) {
                throw new IllegalStateException(
                        "expected one agent definition under " + AGENTS + ", found " + definitions);
            }
            return definitions.getFirst();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The fixture's name as its definition declares it. */
    static String name() {
        final Path file = definition();
        try (var reader = Files.newBufferedReader(file)) {
            return AgentDefinitionReader.read(reader, file.toString()).name();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Stream<Path> yamlFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(file -> file.getFileName().toString().endsWith(".yaml"))
                    .toList()
                    .stream();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
