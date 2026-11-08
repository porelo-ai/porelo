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
                .as("the published contract declares its four operations")
                .containsExactlyInAnyOrder(
                        EcosystemProtocol.DESCRIBE,
                        EcosystemProtocol.DETECT,
                        EcosystemProtocol.RESOLVE,
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
                assertThat(described)
                        .as("Describe over the socket says the plugin's own version too")
                        .containsEntry("version", FakeEcosystem.VERSION);

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

    @Test
    void handsTheProjectsExtensionToTheAdapterUnchanged() throws IOException {

        // A tool's peculiarity, such as one repository standing in for all, has no field of its
        // own in the contract: it reaches the adapter in the block the project keeps for it.
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                final List<File> files =
                        File.of(
                                client.call(
                                        EcosystemProtocol.CONFIGURE,
                                        Map.of(
                                                "repositories",
                                                List.of(Map.of("name", "corp", "url", CORP)),
                                                "extension",
                                                Map.of("mirrorOf", Map.of("corp", "*")))));

                assertThat(files)
                        .as("the block arrived as it was sent")
                        .contains(
                                new File(
                                        ".fake/extension",
                                        "{\"mirrorOf\":{\"corp\":\"*\"}}",
                                        false));
            }
        }
    }

    @Test
    void anAdapterBuiltBeforeTheExtensionAnswersACallThatCarriesOne() throws IOException {
        try (EcosystemServer server =
                new EcosystemServer(new EarlierEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                final List<File> files =
                        File.of(
                                client.call(
                                        EcosystemProtocol.CONFIGURE,
                                        Map.of(
                                                "repositories",
                                                List.of(Map.of("name", "corp", "url", CORP)),
                                                "extension",
                                                Map.of("mirrorOf", Map.of("corp", "*")))));

                assertThat(files)
                        .as("it answers as it always did")
                        .containsExactly(new File(".earlier/corp", CORP, false));
            }
        }
    }

    @Test
    void refusesAnExtensionThatIsNoObject() throws IOException {
        try (EcosystemServer server =
                new EcosystemServer(new FakeEcosystem(), directory.resolve("p.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertThatThrownBy(
                                () ->
                                        client.call(
                                                EcosystemProtocol.CONFIGURE,
                                                Map.of(
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name", "corp", "url",
                                                                        CORP)),
                                                        "extension",
                                                        "mirrorOf=*")))
                        .as("a block of the wrong type is never read as none")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                said ->
                                        assertThat(said.getParameters())
                                                .containsEntry("parameter", "extension"));
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                EcosystemProtocol.CONFIGURE,
                                                Map.of(
                                                        "repositories",
                                                        List.of(),
                                                        "extension",
                                                        Map.of(" ", "v"))))
                        .as("nor one the contract does not carry")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                said ->
                                        assertThat(said.getParameters())
                                                .containsEntry("parameter", "extension"));
            }
        }
    }

    private static final String CORP = "http://127.0.0.1:1/corp/";

    /** An adapter as one was built before the extension: it implements only the first method. */
    private static final class EarlierEcosystem implements Ecosystem {

        @Override
        public String ecosystem() {
            return "earlier";
        }

        @Override
        public Detection detect(final Path project, final Path home) {
            return new Detection(List.of(), List.of());
        }

        @Override
        public List<File> configure(final List<Repository> repositories) {
            return repositories.stream()
                    .map(each -> new File(".earlier/" + each.name(), each.url(), false))
                    .toList();
        }
    }
}
