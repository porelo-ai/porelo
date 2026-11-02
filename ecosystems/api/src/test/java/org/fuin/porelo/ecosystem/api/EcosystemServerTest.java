package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EcosystemServerTest {

    @TempDir Path directory;

    @Test
    void servesExactlyTheMethodsThePublishedInterfaceDeclares() {
        // A contract consumer must not call a described method the server does not serve.
        final Set<String> declared =
                Pattern.compile("(?m)^method (\\w+)\\(")
                        .matcher(EcosystemServer.idl())
                        .results()
                        .map(match -> EcosystemProtocol.INTERFACE + "." + match.group(1))
                        .collect(Collectors.toSet());
        assertThat(declared)
                .as("the published contract declares its three operations")
                .containsExactlyInAnyOrder(
                        EcosystemProtocol.DESCRIBE,
                        EcosystemProtocol.DETECT,
                        EcosystemProtocol.CONFIGURE);
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            assertThat(
                            server.methodNames().stream()
                                    .filter(
                                            name ->
                                                    name.startsWith(
                                                            EcosystemProtocol.INTERFACE + ".")))
                    .as("registered methods match the published contract")
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    @Test
    void describesItselfAndAnswersDetectAndConfigureOnTheWire() throws IOException {
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                final Map<String, Object> described =
                        client.call(EcosystemProtocol.DESCRIBE, Map.of());
                assertThat(((Number) described.get("protocolVersion")).intValue())
                        .isEqualTo(EcosystemProtocol.VERSION);
                assertThat(described).containsEntry("ecosystem", "fake");

                final Map<String, Object> detected =
                        client.call(
                                EcosystemProtocol.DETECT,
                                Map.of("directory", "/work/project", "home", "/people/one"));
                final Detection detection = Detection.of(detected);
                assertThat(detected.get("findings"))
                        .as("the enums go on the wire by their names in the contract")
                        .asString()
                        .contains("TOOLS", "RULES", "CHECKOUT", "YOURS");
                assertThat(detection.findings())
                        .extracting(Finding::note)
                        .containsExactly("/work/project", "/people/one");

                assertThat(
                                File.of(
                                        client.call(
                                                EcosystemProtocol.CONFIGURE,
                                                Map.of(
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name",
                                                                        "corp",
                                                                        "url",
                                                                        "http://127.0.0.1:1/corp/"))))))
                        .containsExactly(new File(".fake/corp", "http://127.0.0.1:1/corp/", false));
            }
        }
    }

    @Test
    void sendsARefusalAsTheContractsErrorOfThatName() throws IOException {
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertThatThrownBy(
                                () ->
                                        client.call(
                                                EcosystemProtocol.DETECT,
                                                Map.of("directory", "/w/other", "home", "/p")))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                said ->
                                        assertThat(said.getErrorName())
                                                .isEqualTo(
                                                        EcosystemProtocol.INTERFACE
                                                                + ".NotRecognized"));
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                EcosystemProtocol.DETECT,
                                                Map.of("directory", "/w/locked", "home", "/p")))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                said -> {
                                    assertThat(said.getErrorName())
                                            .isEqualTo(EcosystemProtocol.INTERFACE + ".Unreadable");
                                    assertThat(said.getParameters())
                                            .containsEntry("detail", "pom.xml: permission");
                                });
            }
        }
    }

    @Test
    void refusesACallWithoutItsParametersNamingTheOneMissing() throws IOException {
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertInvalid(client, EcosystemProtocol.DETECT, Map.of("home", "/p"), "directory");
                assertInvalid(client, EcosystemProtocol.DETECT, Map.of("directory", "/d"), "home");
                assertInvalid(
                        client,
                        EcosystemProtocol.DETECT,
                        Map.of("directory", "/d\u0000", "home", "/p"),
                        "directory");
                assertInvalid(client, EcosystemProtocol.CONFIGURE, Map.of(), "repositories");
                assertInvalid(
                        client,
                        EcosystemProtocol.CONFIGURE,
                        Map.of("repositories", List.of(Map.of("name", "no-url"))),
                        "repositories");
            }
        }
    }

    private static void assertInvalid(
            final VarlinkClient client,
            final String method,
            final Map<String, Object> parameters,
            final String parameter) {
        assertThatThrownBy(() -> client.call(method, parameters))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        said -> {
                            assertThat(said.getErrorName())
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(said.getParameters()).containsEntry("parameter", parameter);
                        });
    }
}
