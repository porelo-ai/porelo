package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.DefaultProject;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.ProposalClient;
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

/** A window's proposal for a repository of 'default', streamed per adapter, and its answer. */
class ProposalMethodsTest {

    /** A credential's value an adapter read, which no reply may carry. */
    static final String SECRET = "hunter2-never-sent";

    @TempDir Path dir;

    private PoreloContext context;

    private Path checkout;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    /** A stub adapter over the real protocol, named by the file it is started as. */
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
            return System.getProperty("stub.name", "maven");
        }

        @Override
        public Detection detect(final Path directory, final Path home) {
            final String name = ecosystem();
            return new Detection(
                    List.of(
                            new Finding(
                                    Finding.Part.TOOLS,
                                    name + ".version",
                                    "25",
                                    name + ".txt",
                                    14,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    ""),
                            new Finding(
                                    Finding.Part.TOOLS,
                                    "credential." + name + "-central",
                                    SECRET,
                                    "settings.xml",
                                    5,
                                    Finding.Origin.YOURS,
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
        for (final String name : List.of("maven", "npm")) {
            final Path plugin =
                    Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                            .resolve(name);
            Files.writeString(
                    plugin,
                    "#!/bin/sh\nexec '"
                            + Path.of(System.getProperty("java.home"), "bin/java")
                            + "' -Dstub.name="
                            + name
                            + " -cp '"
                            + System.getProperty("java.class.path")
                            + "' '"
                            + Plugin.class.getName()
                            + "' \"$@\"\n");
            Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
        }
        new DefaultProject(context)
                .add(
                        checkout.toString(),
                        "shop",
                        checkout.toString(),
                        "https://forge.example/acme/shop.git");
        new DefaultProject(context).add("https://forge.example/acme/far.git", "far", "");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(final Object row) {
        return (Map<String, Object>) row;
    }

    private static Map<String, Object> proposal(final String project, final String repository) {
        return Map.of("project", project, "repository", repository);
    }

    private static String proposalOf(final VarlinkClient client, final String repository) {
        final var reply = client.call(PoreloDaemon.INTERFACE + ".DefaultRepositories", Map.of());
        for (final Object each : (List<?>) reply.get("repositories")) {
            final Map<?, ?> row = (Map<?, ?>) each;
            if (repository.equals(row.get("name"))) {
                return String.valueOf(row.get("proposal"));
            }
        }
        throw new AssertionError("no repository " + repository);
    }

    @Test
    void streamsOneReplyPerAdapterAndThenTheProposalWithNoCredentialsValue() throws Exception {
        serving(
                client -> {
                    final List<Map<String, Object>> replies = new ArrayList<>();
                    client.callMore(
                            PoreloDaemon.INTERFACE + ".ProposeProject",
                            proposal("default", "shop"),
                            reply -> replies.add(reply));
                    assertThat(replies).as("one per adapter, then the proposal").hasSize(3);
                    assertThat(
                                    replies.subList(0, 2).stream()
                                            .map(reply -> (Map<?, ?>) reply.get("adapter"))
                                            .map(
                                                    adapter ->
                                                            adapter.get("name")
                                                                    + ":"
                                                                    + adapter.get("state"))
                                            .sorted()
                                            .toList())
                            .containsExactly("maven:recognized", "npm:recognized");
                    final Map<?, ?> last = (Map<?, ?>) replies.get(2).get("proposal");
                    assertThat(last).as("the merged proposal comes last").isNotNull();
                    assertThat(last.get("repository")).isEqualTo("shop");
                    assertThat(String.valueOf(last.get("draft"))).contains("shop");
                    assertThat(String.valueOf(last.get("values")))
                            .contains("maven.version", "npm.version", "TOOLS");
                    assertThat((List<?>) last.get("credentials"))
                            .as("each credential by its id, with what stores it")
                            .hasSize(2)
                            .anySatisfy(
                                    credential ->
                                            assertThat(row(credential))
                                                    .containsEntry("id", "maven-central")
                                                    .containsEntry(
                                                            "storeCommand",
                                                            List.of(
                                                                    "porelo",
                                                                    "vault",
                                                                    "put",
                                                                    "maven-central"))
                                                    .containsEntry("storeStdin", ""));
                    assertThat(String.valueOf(replies))
                            .as("no credential's value in any reply")
                            .doesNotContain(SECRET);
                    assertThat(proposalOf(client, "shop"))
                            .as("a proposal records nothing")
                            .isEqualTo("UNASKED");
                });
    }

    @Test
    void refusesACallThatDoesNotStream() throws Exception {
        serving(
                client ->
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".ProposeProject",
                                                        proposal("default", "shop")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused ->
                                                assertThat(refused.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".StreamRequired")));
    }

    @Test
    void takingTheProposalRecordsAskedAndWritesNoFile() throws Exception {
        serving(
                client -> {
                    assertThat(proposalOf(client, "shop")).isEqualTo("UNASKED");
                    final var reply =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".TakeProposal",
                                    Map.of(
                                            "project",
                                            "default",
                                            "repository",
                                            "shop",
                                            "choice",
                                            "NONE"));
                    assertThat(row(reply.get("repository")))
                            .containsEntry("name", "shop")
                            .containsEntry("proposal", "ASKED");
                    assertThat(reply)
                            .as("no draft but under TOOLS_AND_DRAFT")
                            .doesNotContainKey("draft");
                    assertThat(proposalOf(client, "shop")).isEqualTo("ASKED");
                    assertThat(proposalOf(client, "far"))
                            .as("another repository stays as it was")
                            .isEqualTo("UNASKED");
                    try (var files = Files.list(checkout)) {
                        assertThat(files).as("nothing written into the checkout").isEmpty();
                    }
                });
    }

    @Test
    void takingToolsAndDraftAnswersTheDraftWithNoCredentialsValue() throws Exception {
        serving(
                client -> {
                    final var reply =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".TakeProposal",
                                    Map.of(
                                            "project",
                                            "default",
                                            "repository",
                                            "shop",
                                            "choice",
                                            "TOOLS_AND_DRAFT"),
                                    java.time.Duration.ofMinutes(2));
                    assertThat(String.valueOf(reply.get("draft"))).contains("shop");
                    assertThat(String.valueOf(reply)).doesNotContain(SECRET);
                    assertThat(proposalOf(client, "shop")).isEqualTo("ASKED");
                });
    }

    @Test
    void aRepositoryAddedByAddressOrAnotherProjectIsNotProposableAndStaysUnasked()
            throws Exception {
        serving(
                client -> {
                    for (final var call :
                            List.of(
                                    Map.entry(proposal("default", "far"), "no-checkout"),
                                    Map.entry(proposal("shop-project", "shop"), "not-default"))) {
                        assertThatThrownBy(
                                        () ->
                                                client.callMore(
                                                        PoreloDaemon.INTERFACE + ".ProposeProject",
                                                        call.getKey(),
                                                        reply -> true))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused -> {
                                            assertThat(refused.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".NotProposable");
                                            assertThat(refused.getParameters())
                                                    .containsEntry("reason", call.getValue());
                                        });
                    }
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    PoreloDaemon.INTERFACE + ".TakeProposal",
                                                    Map.of(
                                                            "project",
                                                            "default",
                                                            "repository",
                                                            "far",
                                                            "choice",
                                                            "NONE")))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    refused ->
                                            assertThat(refused.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".NotProposable"));
                    assertThat(proposalOf(client, "far")).isEqualTo("UNASKED");
                });
    }

    @Test
    void aRepositoryWithItsOwnFileIsConfiguredWhichIsNeverRecorded() throws Exception {
        Files.writeString(checkout.resolve("porelo.yml"), "image:\n  from: ubuntu:24.04\n");
        serving(
                client -> {
                    assertThat(proposalOf(client, "shop")).isEqualTo("CONFIGURED");
                    client.call(
                            PoreloDaemon.INTERFACE + ".TakeProposal",
                            Map.of("project", "default", "repository", "shop", "choice", "NONE"));
                    assertThat(proposalOf(client, "shop")).isEqualTo("CONFIGURED");
                });
        assertThat(
                        Files.readString(
                                dir.resolve("state").resolve("porelo/default/repositories.json")))
                .as("recorded as asked, never as configured")
                .contains("ASKED")
                .doesNotContain("CONFIGURED");
    }

    @Test
    void aClientReadsAnOlderDaemonsMethodNotFoundAsCannotPropose() throws Exception {
        try (VarlinkServer older =
                new VarlinkServer(dir.resolve("older.sock"), PoreloDaemon.INTERFACE)) {
            Thread.ofVirtual().start(older);
            try (VarlinkClient client = new VarlinkClient(older.socketPath())) {
                final List<Map<String, Object>> adapters = new ArrayList<>();
                assertThat(ProposalClient.propose(client, "default", "shop", adapters::add))
                        .as("an older daemon cannot propose, and that is no error")
                        .isInstanceOf(ProposalClient.CannotPropose.class);
                assertThat(adapters).isEmpty();
                assertThat(ProposalClient.take(client, "default", "shop", "NONE"))
                        .isInstanceOf(ProposalClient.CannotPropose.class);
            }
        }
    }

    @Test
    void aClientHasTheProposalAndEachAdapterFromThisDaemon() throws Exception {
        serving(
                client -> {
                    final List<Map<String, Object>> adapters = new ArrayList<>();
                    final ProposalClient.Answer answer =
                            ProposalClient.propose(client, "default", "shop", adapters::add);
                    assertThat(adapters).hasSize(2);
                    assertThat(answer)
                            .isInstanceOfSatisfying(
                                    ProposalClient.Proposed.class,
                                    proposed ->
                                            assertThat(proposed.proposal())
                                                    .containsEntry("repository", "shop"));
                    assertThat(ProposalClient.propose(client, "default", "far", adapters::add))
                            .isEqualTo(new ProposalClient.NotProposable("no-checkout"));
                });
    }
}
