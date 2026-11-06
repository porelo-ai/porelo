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
                    + "image:\n  from: ubuntu:24.04\n"
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
                                    "read from the pom"),
                            new Finding(
                                    Finding.Part.RULES,
                                    "host.nexus.evil.example",
                                    "yes",
                                    "pom.xml",
                                    1,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    "")),
                    List.of(
                            new org.fuin.porelo.ecosystem.api.Unplaced(
                                    "Dockerfile", 3, "a container is built here")));
        }

        @Override
        public java.util.Optional<List<Finding>> resolve(
                final Path directory, final Path home, final List<Finding> findings) {
            try {
                // Counted where the test reads it: a lookup made twice may answer twice.
                Files.writeString(
                        home.resolve("lookups"),
                        "one\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            final List<Finding> resolved = new java.util.ArrayList<>(findings);
            resolved.add(
                    new Finding(
                            Finding.Part.TOOLS,
                            "run.java",
                            PROXY.equals(System.getenv("HTTPS_PROXY"))
                                    ? "RUN true # looked up through the proxy sent"
                                    : "RUN true # looked up without a proxy",
                            "pom.xml",
                            1,
                            Finding.Origin.CHECKOUT,
                            false,
                            ""));
            return java.util.Optional.of(resolved);
        }

        @Override
        public List<File> configure(final List<Repository> repositories) {
            return List.of();
        }
    }

    /** What a test's proxy setting holds: a credential in its address, as a proxy may have. */
    static final String PROXY = "http://someone:not-a-real-one@proxy.example:3128";

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
                    assertThat((List<?>) asked.get("notes"))
                            .anyMatch(
                                    each -> String.valueOf(each).contains("a container is built"));
                    assertThat(asked.get("hosts")).isEqualTo(List.of("nexus.evil.example"));
                    assertThat(asked.get("marked")).isEqualTo(List.of("nexus.evil.example"));
                    assertThat((List<Object>) asked.get("found"))
                            .containsExactly(
                                    Map.of(
                                            "what",
                                            "package.maven",
                                            "value",
                                            "",
                                            "source",
                                            "pom.xml",
                                            "note",
                                            "read from the pom"),
                                    Map.of(
                                            "what",
                                            "image line",
                                            "value",
                                            "RUN true # looked up without a proxy",
                                            "source",
                                            "pom.xml",
                                            "note",
                                            ""));
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
    void theProxySentReachesThePluginAloneAndTheYesMeetsTheQuestionsLookup() throws Exception {
        plugin();
        serving(
                client -> {
                    final Map<String, Object> proxy = Map.of("HTTPS_PROXY", PROXY);
                    final var asked =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".FirstStart",
                                    Map.of("checkout", checkout.toString(), "proxy", proxy));
                    assertThat(asked.get("found"))
                            .as("the fetched line is shown whole, looked up through the proxy")
                            .asString()
                            .contains("RUN true # looked up through the proxy sent");
                    assertThat(asked.get("file"))
                            .asString()
                            .contains("RUN true # looked up through the proxy sent");
                    assertThat(asked.toString())
                            .as("a proxy's address may hold a credential")
                            .doesNotContain("not-a-real-one");

                    client.call(
                            PoreloDaemon.INTERFACE + ".ConfirmFirstStart",
                            Map.of(
                                    "checkout",
                                    checkout.toString(),
                                    "hash",
                                    asked.get("hash"),
                                    "proxy",
                                    proxy));
                });

        assertThat(Files.readAllLines(context.paths().xdg().home().resolve("lookups")))
                .as("the yes took the lookup its question made")
                .hasSize(1);
        assertThat(Files.readString(checkout.resolve("project.yml")))
                .contains("looked up through the proxy sent")
                .doesNotContain("not-a-real-one");
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
    void theFileAStartByNameReadsIsTheCopyWhateverTheCheckoutNowHolds() throws Exception {
        final Path file = Files.writeString(checkout.resolve("project.yml"), FILE);
        serving(client -> confirm(client, (String) firstStart(client).get("hash")));
        Files.writeString(file, FILE.replace("nexus.evil", "edited.evil"));

        final Path started = PoreloDaemon.projectFile(Map.of("project", "strangers"), context);

        assertThat(started).isEqualTo(context.paths().projects().defaultProjectFile("strangers"));
        assertThat(Files.readString(started)).isEqualTo(FILE);
    }

    @Test
    void theRepliesFileHoldsTheNamedFiles() throws Exception {
        Files.writeString(checkout.resolve("image.inc"), "RUN echo named\n");
        Files.writeString(
                checkout.resolve("project.yml"),
                FILE.replace(
                        "from: ubuntu:24.04\n", "from: ubuntu:24.04\n  run_file: image.inc\n"));
        serving(
                client ->
                        assertThat((String) firstStart(client).get("file"))
                                .contains("image.inc", "RUN echo named"));
    }

    @Test
    void aQuestionOverTheSocketLeavesNoStagedCopy() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE);
        final Path projects = context.paths().xdg().config().resolve("projects");
        serving(
                client -> {
                    firstStart(client);
                    firstStart(client);
                });

        try (var entries = Files.list(projects)) {
            assertThat(entries.map(each -> each.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".staging-"));
        }
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
                        "method FirstStart(checkout: string, proxy: ?[string]string) ->"
                                + " (outcome: string, found: []Finding,"
                                + " hosts: []string, marked: []string, file: string, project: string,"
                                + " hash: string, notes: []string, refusal: string)",
                        "method ConfirmFirstStart(checkout: string, hash: string, proxy:"
                                + " ?[string]string) ->"
                                + " (project: string)",
                        "type Finding (",
                        "error FirstStartRefused(reason: string, detail: string)");
    }
}
