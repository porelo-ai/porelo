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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine;

/**
 * Tests for {@link ProjectInventoryCommand}, against real git and ssh-keygen: the check of a
 * project file's signature is the point, and a fake would only prove that this agrees with itself.
 */
class ProjectInventoryCommandTest {

    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

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
        return published(dir, signingKey, "");
    }

    private Path published(final Path dir, final Path signingKey, final String mail)
            throws IOException {
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
                """
                        + mail);
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
                        keys:
                            - { name: github-deploy, path: keys/github-deploy }
                        """);

        assertThat(execute(context, "project", "inventory", "--machine-file", file.toString()))
                .as("exit code; " + err)
                .isZero();

        final Map<String, Object> host = host(out.toString());
        assertThat(host).as("a host play starts no tasks").doesNotContainKey("porelo_tasks");
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
                        "porelo_vault_keys",
                        List.of(
                                Map.of(
                                        "name",
                                        "github-deploy",
                                        "path",
                                        dir.resolve("drive/keys/github-deploy").toString())));
        assertThat(out.toString())
                .as("the version the role checks")
                .contains("porelo_inventory_version: 2");
        assertThat(context.paths().projects().configurationSigners())
                .as("nothing was pinned or followed")
                .doesNotExist();
    }

    @Test
    void writesEachTransportTheProjectUsesWithItsPackage(@TempDir final Path dir)
            throws IOException {

        final Path key = key(dir, "operator");
        final Path repo =
                published(
                        dir,
                        key,
                        """
                        mail:
                          transports:
                            matrix: {}
                            room: {}
                          peers:
                            conversation: { address: "chat:", trust: external }
                            reviewer: { address: "matrix:review", trust: external }
                            operator: { address: "ssh:operator", trust: vouched }
                            backup: { address: "ssh:backup", trust: vouched }
                        """);
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("exit code; " + err)
                .isZero();

        assertThat(host(out.toString()).get("porelo_transports"))
                .as("the play installs each conversation and peer transport once in file order")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "scheme",
                                        "matrix",
                                        "package",
                                        "porelo-message-transport-matrix"),
                                Map.of(
                                        "scheme",
                                        "room",
                                        "package",
                                        "porelo-message-transport-room"),
                                Map.of(
                                        "scheme",
                                        "chat",
                                        "package",
                                        "porelo-message-transport-chat"),
                                Map.of(
                                        "scheme",
                                        "ssh",
                                        "package",
                                        "porelo-message-transport-ssh")));
    }

    @Test
    void namesTheFilesATransportExpectsAndNothingElseOfItsSettings(@TempDir final Path dir)
            throws IOException {

        // A play writes the file a transport reads, such as a homeserver's registration token, and
        // has no other way to know where; a value never goes into the inventory, only a path.
        final Path key = key(dir, "operator");
        final Path repo =
                published(
                        dir,
                        key,
                        """
                        mail:
                          transports:
                            matrix:
                              homeserver: "https://matrix.example.internal:8448"
                              room: "#porelo-demo:matrix.example.internal"
                              registration_token_file: "~/.config/porelo/matrix/host-registration-token"
                              ca_file: "/etc/porelo/ca.pem"
                            room: {}
                          peers:
                            conversation: { address: "chat:", trust: external }
                        """);
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("exit code; " + err)
                .isZero();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> transports =
                (List<Map<String, Object>>) host(out.toString()).get("porelo_transports");
        assertThat(transports.get(0))
                .containsEntry("scheme", "matrix")
                .containsEntry(
                        "files",
                        Map.of(
                                "registration_token_file",
                                "~/.config/porelo/matrix/host-registration-token",
                                "ca_file",
                                "/etc/porelo/ca.pem"))
                .doesNotContainKeys("homeserver", "room");
        assertThat(transports.get(1))
                .as("a transport naming no file has no files key")
                .doesNotContainKey("files");
        assertThat(out.toString()).doesNotContain("matrix.example.internal");
    }

    /** An access repository, signed by the key given, as the bundle a person carries. */
    private Path accessBundle(final Path dir, final Path signingKey, final String accessFile)
            throws IOException {
        final Path repo = Files.createDirectories(dir.resolve("access"));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", signingKey.toString());
        Files.writeString(repo.resolve("access.yml"), accessFile);
        git(repo, "add", "access.yml");
        git(repo, "commit", "-q", "-S", "-m", "access");
        final Path bundle = Files.createDirectories(dir.resolve("drive")).resolve("access.bundle");
        git(repo, "bundle", "create", "-q", bundle.toString(), "HEAD", "main");
        return bundle;
    }

    private static final String ACCESS_PROJECT =
            """
            access:
              repository: "git@forge.example.org:demo/access.git"
            mail:
              peers:
                conversation: { address: "matrix:", trust: external }
            """;

    private static final String MATRIX_ACCESS =
            """
            transports:
              matrix:
                homeserver: "https://matrix.example.internal:8448"
                registration_token_file: "~/.config/porelo/matrix/host-registration-token"
            """;

    @Test
    void takesTheFilesATransportExpectsFromTheVerifiedAccessFile(@TempDir final Path dir)
            throws IOException {

        // The setting lives only in the private access file now; the play must still find where
        // to write the homeserver's registration token.
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, ACCESS_PROJECT);
        accessBundle(dir, key, MATRIX_ACCESS);
        final Path file =
                machineFile(dir, repo, fingerprint(key), "access:\n    bundle: access.bundle\n");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("an inventory from both bundles; " + err)
                .isZero();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> transports =
                (List<Map<String, Object>>) host(out.toString()).get("porelo_transports");
        assertThat(transports)
                .as("the matrix transport with the file its access settings name")
                .containsExactly(
                        Map.of(
                                "scheme",
                                "matrix",
                                "package",
                                TransportDirectory.PREFIX + "matrix",
                                "files",
                                Map.of(
                                        "registration_token_file",
                                        "~/.config/porelo/matrix/host-registration-token")));
        assertThat(out.toString())
                .as("still version 2, and no setting's value")
                .contains("porelo_inventory_version: 2")
                .doesNotContain("matrix.example.internal");
    }

    @Test
    void refusesAnAccessBundleTheProjectsKeyDidNotSign(@TempDir final Path dir) throws IOException {

        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, ACCESS_PROJECT);
        accessBundle(dir, key(dir, "stranger"), MATRIX_ACCESS);
        final Path file =
                machineFile(dir, repo, fingerprint(key), "access:\n    bundle: access.bundle\n");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("refused as a follow refuses it; " + err)
                .isEqualTo(65);
        assertThat(err.toString())
                .as("names the access file's outcome")
                .contains("the access file of demo did not pass its check (unknown_key")
                .contains("No inventory was written.");
        assertThat(out.toString()).as("no inventory").isEmpty();
    }

    @Test
    void refusesAProjectWhoseAccessRepositoryTheMachineFileDoesNotCarry(@TempDir final Path dir)
            throws IOException {

        // Its URL is never fetched: the host holds no credential for the private repository, and
        // one it did hold would make the inventory depend on whatever that host serves.
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, ACCESS_PROJECT);
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("refused; " + err)
                .isEqualTo(65);
        assertThat(err.toString())
                .as("says what to name")
                .contains("no copy of that repository was given to check");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "registration_token_file: \"syt-a-secret-written-by-mistake\"",
                "registration_token_file: \"\"",
                "registration_token_file: \"~/../../etc/cron.d/porelo\"",
                "registration_token_file: \"/tmp/a\\n/tmp/b\"",
                "registration_token_file: [\"~/.config/porelo/token\"]",
                "ca_file: 123",
                "_file: \"/etc/porelo/ca.pem\""
            })
    void refusesAFileSettingThatHoldsNoPathAndNeverPrintsItsValue(
            final String setting, @TempDir final Path dir) throws IOException {

        // A value under a *_file name may be a secret written under the wrong name, and the play
        // writes a file wherever the path points: the inventory refuses it, naming only the
        // setting.
        final Path key = key(dir, "operator");
        final Path repo =
                published(
                        dir,
                        key,
                        """
                        mail:
                          transports:
                            matrix:
                              %s
                          peers:
                            reviewer: { address: "matrix:review", trust: external }
                        """
                                .formatted(setting));
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .isEqualTo(65);
        assertThat(out.toString()).as("no inventory").isEmpty();
        assertThat(err.toString())
                .contains("mail.transports.matrix.")
                .doesNotContain("syt-a-secret")
                .doesNotContain("cron.d")
                .doesNotContain("/tmp/a");
    }

    @Test
    void writesNoTransportsForAProjectWithoutMail(@TempDir final Path dir) throws IOException {

        final Path key = key(dir, "operator");
        final Path repo = published(dir, key);
        final Path file = machineFile(dir, repo, fingerprint(key), "");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("exit code; " + err)
                .isZero();

        assertThat(host(out.toString()).get("porelo_transports"))
                .as("a project without mail requires no transport packages")
                .isEqualTo(List.of());
        assertThat(out.toString())
                .as("the play checks the current inventory contract version")
                .contains("porelo_inventory_version: 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tasks", "agents"})
    void refusesMachineInstructionsWithoutWritingInventory(
            final String section, @TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key);
        final Path file = machineFile(dir, repo, fingerprint(key), section + ": []\n");

        assertThat(execute(context(dir), "project", "inventory", "--machine-file", file.toString()))
                .as("unsupported machine instructions are a data error")
                .isEqualTo(65);
        assertThat(out.toString()).as("no partial inventory reaches the play").isEmpty();
        assertThat(err.toString())
                .as("the person is told how tasks start")
                .contains("every agent")
                .contains("porelo task start");
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
