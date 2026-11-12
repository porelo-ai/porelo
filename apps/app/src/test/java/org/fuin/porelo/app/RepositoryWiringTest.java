package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ToolRepository;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests when a start serves a task its package repositories through a broker of their own. */
class RepositoryWiringTest {

    private final List<String> recorded = new ArrayList<>();

    private CredentialWiring wiring(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        return new CredentialWiring(
                context,
                new CredentialChoice(context, null, null, null),
                (name, command, environment, phase) -> recorded.add(name),
                "porelo-uc-work",
                8,
                null);
    }

    @Test
    void aProjectThatDeclaresNoRepositoryWithACredentialGetsNoBrokerAndNoRelay(
            @TempDir final Path dir) {
        final StringWriter out = new StringWriter();

        final CredentialWiring.RepositoryPlumbing plumbing =
                wiring(dir)
                        .startRepositories(
                                List.of(
                                        new ToolRepository(
                                                "maven", "public", "https://repo.example.org/m/")),
                                "porelo-uc-work",
                                new PrintWriter(out, true),
                                new PrintWriter(new StringWriter(), true));

        assertThat(plumbing).as("no second socket, so no second relay").isNull();
        assertThat(recorded).as("no helper started").isEmpty();
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aRepositoryWhoseCredentialTheVaultLacksIsSaidAndNotServed(@TempDir final Path dir) {
        final StringWriter out = new StringWriter();

        final CredentialWiring.RepositoryPlumbing plumbing =
                wiring(dir)
                        .startRepositories(
                                List.of(
                                        new ToolRepository(
                                                "maven",
                                                "corp",
                                                "https://nexus.corp.example/repository/maven/",
                                                "repository/corp-nexus")),
                                "porelo-uc-work",
                                new PrintWriter(out, true),
                                new PrintWriter(new StringWriter(), true));

        assertThat(plumbing).isNull();
        assertThat(recorded).isEmpty();
        assertThat(out.toString())
                .contains(
                        "repository corp none - the vault holds no credential for"
                                + " 'repository/corp-nexus'");
    }
}
