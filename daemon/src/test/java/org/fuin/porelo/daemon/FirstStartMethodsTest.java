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
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The window's way to a first start: the same question the terminal asks, over the socket. */
class FirstStartMethodsTest {

    private static final String FILE =
            "project:\n  name: strangers\n  security_class: guarded\n"
                    + "image:\n  base_image: ubuntu:24.04\n"
                    + "egress:\n  domains:\n    - \"nexus.evil.example\"\n";

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
        public Detection detect(final Path directory, final Path home) {
            return new Detection(
                    List.of(
                            new Finding(
                                    Finding.Part.TOOLS,
                                    "package.maven",
                                    "",
                                    "pom.xml",
                                    1,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    ""),
                            new Finding(
                                    Finding.Part.RULES,
                                    "host.nexus.evil.example",
                                    "yes",
                                    "pom.xml",
                                    1,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    "")),
                    List.of());
        }

        @Override
        public List<File> configure(final List<Repository> repositories) {
            return List.of();
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
    }

    private void plugin() throws Exception {
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

    private Map<String, Object> firstStart(final VarlinkClient client) throws Exception {
        return client.call(
                PoreloDaemon.INTERFACE + ".FirstStart", Map.of("checkout", checkout.toString()));
    }

    private Map<String, Object> confirm(final VarlinkClient client, final String hash)
            throws Exception {
        return client.call(
                PoreloDaemon.INTERFACE + ".ConfirmFirstStart",
                Map.of("checkout", checkout.toString(), "hash", hash));
    }

    @Test
    void aNewRepositoryIsAskedAboutAndWrittenOnlyAtTheConfirmation() throws Exception {
        plugin();
        serving(
                client -> {
                    final var asked = firstStart(client);
                    assertThat(asked.get("outcome")).isEqualTo("ASK_NEW");
                    assertThat(asked.get("project")).isEqualTo("shop-project");
                    assertThat(asked.get("hosts")).isEqualTo(List.of("nexus.evil.example"));
                    assertThat(asked.get("marked")).isEqualTo(List.of("nexus.evil.example"));
                    assertThat(asked.get("found"))
                            .isEqualTo(
                                    List.of(
                                            Map.of(
                                                    "what",
                                                    "package.maven",
                                                    "value",
                                                    "",
                                                    "source",
                                                    "pom.xml")));
                    assertThat(checkout.resolve("project.yml"))
                            .as("a look writes nothing")
                            .doesNotExist();

                    final var confirmed = confirm(client, (String) asked.get("hash"));

                    assertThat(confirmed.get("project")).isEqualTo("shop-project");
                    assertThat(Files.readString(checkout.resolve("project.yml")))
                            .isEqualTo(asked.get("file"));
                    assertThat(firstStart(client).get("outcome")).isEqualTo("KNOWN");
                });
    }

    @Test
    void anUnknownFileIsAskedAboutAndAStaleHashIsRefusedByName() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE);
        serving(
                client -> {
                    final var asked = firstStart(client);
                    assertThat(asked.get("outcome")).isEqualTo("ASK_UNKNOWN_FILE");
                    assertThat(asked.get("file")).isEqualTo(FILE);

                    assertThatThrownBy(() -> confirm(client, "feed"))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex -> {
                                        assertThat(ex.getErrorName())
                                                .isEqualTo(
                                                        PoreloDaemon.INTERFACE
                                                                + ".FirstStartRefused");
                                        assertThat(ex.getParameters().get("reason"))
                                                .isEqualTo("MOVED_ON");
                                    });
                    assertThat(firstStart(client).get("outcome")).isEqualTo("ASK_UNKNOWN_FILE");

                    assertThat(confirm(client, (String) asked.get("hash")).get("project"))
                            .isEqualTo("strangers");
                    assertThat(firstStart(client).get("outcome")).isEqualTo("KNOWN");
                });
    }

    @Test
    void noPluginIsAnOutcome() throws Exception {
        serving(client -> assertThat(firstStart(client).get("outcome")).isEqualTo("NO_PLUGIN"));
    }

    @Test
    void aRelativeCheckoutIsRefusedByName() throws Exception {
        serving(
                client ->
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".FirstStart",
                                                        Map.of("checkout", "work/shop")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getParameters().get("reason"))
                                                        .isEqualTo("NOT_A_CHECKOUT")));
    }

    @Test
    void theContractDescribesBothMethods() {
        assertThat(PoreloDaemon.description())
                .contains(
                        "method FirstStart(checkout: string) -> (outcome: string, found: []Finding,"
                                + " hosts: []string, marked: []string, file: string, project: string,"
                                + " hash: string)",
                        "method ConfirmFirstStart(checkout: string, hash: string) ->"
                                + " (project: string)",
                        "type Finding (",
                        "error FirstStartRefused(reason: string, detail: string)");
    }
}
