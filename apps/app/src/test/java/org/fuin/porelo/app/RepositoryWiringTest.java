package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

    private static XdgPaths xdg(final Path dir) {
        return XdgPaths.of(
                name ->
                        switch (name) {
                            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                            default -> null;
                        },
                dir);
    }

    private CredentialWiring wiring(final Path dir) {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg(dir), dir.resolve("bin")),
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

    @Test
    void aRepositoryBrokerThatNeverRecordsItsPidIsNotUpAndIsNotLeftRunning(@TempDir final Path dir)
            throws Exception {

        // Taken as up once its socket was there, a repository broker that never recorded its pid
        // was one nothing that cleans up by the pid files found; one that did not come up at all
        // was said, and the start went on without it.
        brokerStarts(dir.resolve("silent"), false);
        brokerStarts(dir.resolve("up"), true);
    }

    private void brokerStarts(final Path dir, final boolean recordsItsPid) throws Exception {
        recorded.clear();
        final PoreloContext context = vaulted(dir);
        final CredentialWiring held =
                new CredentialWiring(
                        context,
                        new CredentialChoice(context, null, null, null),
                        (name, command, environment, phase) -> recorded.add(name),
                        "porelo-uc-work",
                        8,
                        null);
        final List<ProcessHandle> owned = new ArrayList<>();
        held.owned = owned::add;
        final String container = "porelo-uc-work";
        Files.createDirectories(context.paths().tasks().containerState(container));
        final Path helpers = dir.resolve("helpers");
        final Path program = helpers.resolve("porelo");
        Files.createDirectories(helpers);
        Files.writeString(
                program,
                """
                #!/bin/sh
                previous=
                for argument; do
                  [ "$previous" = --socket ] && : > "$argument"
                  previous=$argument
                done
                """
                        + HelperStandIn.lines(HelperStandIn.registry(helpers), recordsItsPid),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        final String before = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, program.toString());
        final StringWriter err = new StringWriter();
        try {
            final List<ToolRepository> declared =
                    List.of(
                            new ToolRepository(
                                    "maven",
                                    "corp",
                                    "https://nexus.corp.example/repository/maven/",
                                    "corp-nexus"));
            if (recordsItsPid) {
                final CredentialWiring.RepositoryPlumbing plumbing =
                        held.startRepositories(
                                declared,
                                container,
                                new PrintWriter(new StringWriter(), true),
                                new PrintWriter(err, true));
                assertThat(plumbing).as("it came up: %s", err).isNotNull();
                assertThat(HelperStandIn.anyRunning(helpers)).as("and runs").isTrue();
            } else {
                assertThatThrownBy(
                                () ->
                                        held.startRepositories(
                                                declared,
                                                container,
                                                new PrintWriter(new StringWriter(), true),
                                                new PrintWriter(err, true)))
                        .as("a broker that does not come up fails the start")
                        .isInstanceOf(CredentialWiring.BrokerDown.class);
                assertThat(err.toString()).contains("the repository broker did not come up");
                assertThat(HelperStandIn.anyRunning(helpers))
                        .as("and is ended by its own handle, though it ignores SIGTERM")
                        .isFalse();
            }
            assertThat(HelperStandIn.started(helpers)).as("it was started").isEqualTo(1);
            assertThat(owned).as("told to the start's owned handles").hasSize(1);
            assertThat(recorded).as("recorded for a resume").containsExactly("repository");
        } finally {
            if (before == null) {
                System.clearProperty(PoreloBinary.NAMED);
            } else {
                System.setProperty(PoreloBinary.NAMED, before);
            }
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    /** A context whose vault holds the repository's credential, opened by its passphrase. */
    private static PoreloContext vaulted(final Path dir) throws Exception {
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg(dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(shown -> "test-passphrase".toCharArray());
        context.vault()
                .write(
                        java.util.Map.of(
                                "corp-nexus",
                                new org.fuin.porelo.vault.VaultEntry("test-secret", "api-key")),
                        "test-passphrase".toCharArray());
        // Opened for this command only, as a start's own asking opens it.
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter(), true)))
                .as("the vault opens")
                .isPresent();
        return context;
    }
}
