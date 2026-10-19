package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine;

/**
 * Tests for {@link ProjectInventoryCommand}, against real git and ssh-keygen: the check of a
 * project file's signature is the point, and a fake would only prove that this agrees with itself.
 */
class ProjectInventoryCommandTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(final PoreloContext context, final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private void git(final Path where, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", where.toString()));
        all.addAll(List.of(arguments));
        final var result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
    }

    private Path key(final Path dir, final String name) {
        runner.run(
                Command.of(
                        "ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-C",
                        name,
                        "-f",
                        dir.resolve(name).toString()));
        return dir.resolve(name);
    }

    /** A project repository with one commit naming the repository 'core', signed or not. */
    private Path published(final Path dir, final Path signingKey) throws IOException {
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  description: A project a host brings up
                  security_class: guarded
                repositories:
                  core:
                    upstream: "git@example.org:demo/core.git"
                    description: The core
                image:
                  base_image: ubuntu:24.04
                """);
        git(repo, "add", "project.yml");
        if (signingKey == null) {
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            git(repo, "config", "gpg.format", "ssh");
            git(repo, "config", "user.signingkey", signingKey.toString());
            git(repo, "commit", "-q", "-S", "-m", "configuration");
        }
        return repo;
    }

    private static String fingerprint(final Path key) throws IOException {
        return SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip());
    }

    private Path machineFile(
            final Path dir, final Path repo, final String signedBy, final String rest)
            throws IOException {
        final Path drive = dir.resolve("drive");
        Files.createDirectories(drive);
        final Path file = drive.resolve("machine.yml");
        Files.writeString(
                file,
                "provider: hetzner\n"
                        + "project:\n"
                        + "    name: demo\n"
                        + "    url: \""
                        + repo
                        + "\"\n"
                        + "    signed_by: \""
                        + signedBy
                        + "\"\n"
                        + rest);
        return file;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> host(final String yaml) {
        final Map<String, Object> root =
                new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        final Map<String, Object> all = (Map<String, Object>) root.get("all");
        return (Map<String, Object>) ((Map<String, Object>) all.get("hosts")).get("localhost");
    }

    @Test
    void writesTheSecondPlaysInventoryFromASignedProjectFile(@TempDir final Path dir)
            throws IOException {

        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key);
        final Path file =
                machineFile(
                        dir,
                        repo,
                        fingerprint(key),
                        """
                        tasks:
                            - { name: core-1, repository: core }
                        keys:
                            - { name: github-deploy, path: keys/github-deploy }
                        """);

        assertThat(execute(context, "project", "inventory", "--machine-file", file.toString()))
                .as("exit code; " + err)
                .isZero();

        final Map<String, Object> host = host(out.toString());
        assertThat(host)
                .as("the second play's variables")
                .containsEntry("ansible_connection", "local")
                .containsEntry("porelo_account", System.getProperty("user.name"))
                .containsEntry(
                        "porelo_project",
                        Map.of(
                                "name",
                                "demo",
                                "url",
                                repo.toString(),
                                "signed_by",
                                fingerprint(key)))
                .containsEntry(
                        "porelo_tasks",
                        List.of(
                                Map.of(
                                        "name",
                                        "core-1",
                                        "project",
                                        "demo",
                                        "repository",
                                        "core",
                                        "container",
                                        "porelo-demo-core-1")))
                .containsEntry(
                        "porelo_vault_keys",
                        List.of(
                                Map.of(
                                        "name",
                                        "github-deploy",
                                        "path",
                                        dir.resolve("drive/keys/github-deploy").toString())));
        assertThat(out.toString())
                .as("the version the role checks")
                .contains("porelo_inventory_version: 1");
        assertThat(context.paths().projects().configurationSigners())
                .as("nothing was pinned or followed")
                .doesNotExist();
    }

    @Test
    void refusesAProjectFileNobodySigned(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, null);
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context, "project", "inventory", "--machine-file", file.toString()))
                .as("exit code")
                .isEqualTo(65);
        assertThat(out.toString()).as("no inventory").isEmpty();
        assertThat(err.toString()).as("the refusal").contains("No inventory was written");
    }

    @Test
    void refusesAProjectFileSignedByAnotherKey(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        final Path signer = key(dir, "someone");
        final Path named = key(dir, "operator");
        final Path repo = published(dir, signer);
        final Path file = machineFile(dir, repo, fingerprint(named), "");

        assertThat(execute(context, "project", "inventory", "--machine-file", file.toString()))
                .as("exit code")
                .isEqualTo(65);
        assertThat(out.toString()).as("no inventory").isEmpty();
        assertThat(err.toString()).as("the refusal names the signer").contains("not by");
    }

    @Test
    void refusesATaskOnARepositoryTheProjectDoesNotHave(@TempDir final Path dir)
            throws IOException {

        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key);
        final Path file =
                machineFile(
                        dir,
                        repo,
                        fingerprint(key),
                        "tasks:\n    - { name: site-1, repository: site }\n");

        assertThat(execute(context, "project", "inventory", "--machine-file", file.toString()))
                .as("exit code")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("both names")
                .contains("'site-1'")
                .contains("'site'")
                .contains("core");
    }

    @Test
    void refusesAMachineFileThatIsNotThere(@TempDir final Path dir) {

        assertThat(
                        execute(
                                context(dir),
                                "project",
                                "inventory",
                                "--machine-file",
                                dir.resolve("none.yml").toString()))
                .as("exit code")
                .isEqualTo(66);
    }
}
