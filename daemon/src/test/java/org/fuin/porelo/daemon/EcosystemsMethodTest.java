package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Ecosystem;
import org.fuin.porelo.ecosystem.api.EcosystemMain;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.Repository;
import org.fuin.porelo.ecosystem.api.Unplaced;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A window's way to the ecosystems a machine has and what each answers for a checkout. */
class EcosystemsMethodTest {

    /** What the plugin's settings file holds, which never crosses the socket. */
    static final String SECRET = "<password>hunter2</password>";

    @TempDir Path dir;

    private PoreloContext context;

    private Path checkout;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    /** A plugin over the real protocol, as the daemon asks one. */
    public static final class Plugin implements Ecosystem {

        /**
         * Serves the fixture.
         *
         * @param args What the protocol passes.
         */
        public static void main(final String[] args) {
            EcosystemMain.run(new Plugin(), args);
        }

        @Override
        public String ecosystem() {
            return "maven";
        }

        @Override
        public String version() {
            return "1.4.0";
        }

        @Override
        public Detection detect(final Path directory, final Path home) {
            return new Detection(
                    List.of(
                            new Finding(
                                    Finding.Part.TOOLS,
                                    "java.version",
                                    "25",
                                    "pom.xml",
                                    14,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    directory.toString())),
                    List.of(new Unplaced("Dockerfile", 3, "a container is built here")));
        }

        @Override
        public List<File> configure(final List<Repository> repositories) {
            return List.of(new File(".m2/settings.xml", SECRET, true));
        }
    }

    @BeforeEach
    void machine() throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        checkout = Files.createDirectories(dir.resolve("work/shop")).toRealPath();
        runner.answering("--show-toplevel", checkout.toString());
        runner.answering("get-url origin", "https://forge.example/acme/shop.git");
        final Path plugin =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve("maven");
        Files.writeString(
                plugin,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin/java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + Plugin.class.getName()
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
    }

    private interface Calls {
        void with(VarlinkClient client) throws Exception;
    }

    private void serving(final Calls calls) throws Exception {
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                calls.with(client);
            }
        }
    }

    @Test
    void listsTheInstalledPluginsWithTheirVersions() throws Exception {
        serving(
                client -> {
                    final var reply = client.call(PoreloDaemon.INTERFACE + ".Ecosystems", Map.of());
                    assertThat((List<?>) reply.get("ecosystems"))
                            .as("the plugin by name, with its own and its contract's version")
                            .singleElement()
                            .asInstanceOf(
                                    org.assertj.core.api.InstanceOfAssertFactories.map(
                                            String.class, Object.class))
                            .containsEntry("name", "maven")
                            .containsEntry("version", "1.4.0")
                            .containsEntry(
                                    "protocolVersion",
                                    Double.valueOf(
                                            org.fuin.porelo.ecosystem.api.EcosystemProtocol
                                                    .VERSION))
                            .doesNotContainKey("refusal");
                    assertThat(reply).as("no checkout, nothing asked").doesNotContainKey("placing");
                });
    }

    @Test
    void answersForACheckoutWhatThePluginFindsAndNeverWhatAFileHolds() throws Exception {
        serving(
                client -> {
                    final var reply =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".Ecosystems",
                                    Map.of("checkout", checkout.toString()));
                    assertThat((List<?>) reply.get("placing"))
                            .as("the plugin, asked about the checkout itself")
                            .singleElement()
                            .asInstanceOf(
                                    org.assertj.core.api.InstanceOfAssertFactories.map(
                                            String.class, Object.class))
                            .containsEntry("ecosystem", "maven")
                            .containsEntry("outcome", "DETECTED")
                            .containsEntry(
                                    "unplaced",
                                    List.of(
                                            Map.of(
                                                    "file",
                                                    "Dockerfile",
                                                    "line",
                                                    3.0,
                                                    "note",
                                                    "a container is built here")))
                            .containsEntry("files", List.of());
                    assertThat(String.valueOf(reply.get("placing")))
                            .as("its finding, read in the checkout a first start reads")
                            .contains("java.version", checkout.toString());
                    assertThat(String.valueOf(reply))
                            .as("a settings file's content never crosses the socket")
                            .doesNotContain("hunter2");
                });
    }

    @Test
    void refusesARelativeCheckoutAsAFirstStartDoes() throws Exception {
        serving(
                client ->
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Ecosystems",
                                                        Map.of("checkout", "work/shop")))
                                .as("a daemon's own directory is never the person's")
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused ->
                                                assertThat(refused.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".FirstStartRefused")));
    }
}
