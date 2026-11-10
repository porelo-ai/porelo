package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.DefaultProject;
import org.fuin.porelo.app.OrganizationProjects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The window's offer to take a repository out of 'default' once the organization names it. */
@SuppressWarnings("unchecked")
class DefaultNamedMethodsTest {

    private static final String ORIGIN = "https://forge.example/acme/shop.git";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context;

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
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "acme",
                                "c0",
                                "https://forge.example/acme.git",
                                null,
                                List.of(),
                                null,
                                null,
                                null));
        final Path clone =
                Files.createDirectories(context.paths().projects().organizationClone("acme"));
        Files.writeString(
                clone.resolve("project.yml"),
                "project:\n  name: acme\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  storefront:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");
        final DefaultProject fallback = new DefaultProject(context);
        fallback.add(ORIGIN, "shop", "");
        fallback.add("https://forge.example/someone/notes.git", "notes", "");
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

    private List<String> names() {
        return new DefaultProject(context)
                .entries().stream().map(DefaultProject.Entry::name).toList();
    }

    // Harm: the window cannot offer the removal, so work keeps going into 'default' unasked.
    @Test
    void theWindowReadsTheRepositoriesTheOrganizationNames() throws Exception {
        serving(
                client -> {
                    final Map<String, Object> reply =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".NamedDefaultRepositories", Map.of());

                    assertThat((List<Object>) reply.get("repositories"))
                            .containsExactly(
                                    Map.of(
                                            "name",
                                            "shop",
                                            "upstream",
                                            ORIGIN,
                                            "project",
                                            "acme",
                                            "projectRepository",
                                            "storefront",
                                            "removal",
                                            "porelo project default remove shop"));
                });
        assertThat(names()).containsExactly("shop", "notes");
    }

    // Harm: the person's yes in the window leaves the repository in 'default' or takes another.
    @Test
    void aRemovalTakesOutTheOneNamedAndAnswersIt() throws Exception {
        serving(
                client -> {
                    final Map<String, Object> reply =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".LeaveDefault",
                                    Map.of("name", "shop"));

                    assertThat(((Map<?, ?>) reply.get("removed")).get("name")).isEqualTo("shop");
                    assertThat(((Map<?, ?>) reply.get("removed")).get("upstream"))
                            .isEqualTo(ORIGIN);
                });
        assertThat(names()).containsExactly("notes");
    }

    // Harm: a task of 'default' loses the repository it was started on while it still works.
    @Test
    void aRepositoryATaskOfDefaultStillWorksOnIsRefusedNamingTheTask() throws Exception {
        runner.answering(
                "ps",
                "porelo-default-shop\tUp 4 minutes\t1700000000\t0\tdefault\tguarded\tshop\t\n");

        serving(
                client -> {
                    final VarlinkException refused =
                            catchThrowableOfType(
                                    VarlinkException.class,
                                    () ->
                                            client.call(
                                                    PoreloDaemon.INTERFACE + ".LeaveDefault",
                                                    Map.of("name", "shop")));

                    assertThat(refused.getErrorName())
                            .isEqualTo(PoreloDaemon.INTERFACE + ".DefaultRepositoryInUse");
                    assertThat(refused.getParameters())
                            .containsEntry("name", "shop")
                            .containsEntry("tasks", List.of("porelo-default-shop"));
                });
        assertThat(names()).containsExactly("shop", "notes");
    }

    // Harm: a name 'default' does not have reads as done, and the window says it was removed.
    @Test
    void aRepositoryNotInDefaultIsRefusedByName() throws Exception {
        serving(
                client -> {
                    final VarlinkException refused =
                            catchThrowableOfType(
                                    VarlinkException.class,
                                    () ->
                                            client.call(
                                                    PoreloDaemon.INTERFACE + ".LeaveDefault",
                                                    Map.of("name", "storefront")));

                    assertThat(refused.getErrorName())
                            .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchDefaultRepository");
                    assertThat(refused.getParameters()).containsEntry("name", "storefront");
                });
        assertThat(names()).containsExactly("shop", "notes");
    }
}
