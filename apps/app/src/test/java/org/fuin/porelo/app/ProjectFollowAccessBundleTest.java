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
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A follow that takes its access file from a bundle, for a host that cannot fetch the private
 * repository. Harm guarded: an access file that arrived beside the project without the check a
 * fetched one gets, whose persons and settings would then be applied unverified.
 */
class ProjectFollowAccessBundleTest {

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

    @TempDir Path dir;

    private PoreloContext context() {
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
        return new PoreloContext(
                command ->
                        command.arguments().getFirst().equals("logger")
                                ? new CommandResult(command, 0, "", "")
                                : runner.run(command),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int porelo(final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private static final String ACCESS_URL = "git@forge.example.org:demo/access.git";

    private static final String ACCESS =
            "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n";

    @Test
    void anAccessBundleIsCheckedAndAppliedAndItsUrlNeverFetched() throws Exception {
        final Path key = key("operator");
        final Path access = bundle(access(key), "access.bundle");
        final Path project = bundle(project(ACCESS_URL, key), "project.bundle");

        final int code =
                porelo(
                        "project",
                        "follow",
                        "demo",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString());

        assertThat(code).as("followed from both bundles: " + err).isZero();
        final FollowedProjects.Followed followed =
                new FollowedProjects(context().paths().projects().followed()).require("demo");
        assertThat(followed.accessOutcome())
                .as("the access file is in force, checked as a fetched one")
                .isEqualTo("APPLIED");
        assertThat(followed.accessCommit())
                .as("the bundle's commit")
                .isEqualTo(git(dir.resolve("access"), "rev-parse", "HEAD"));
    }

    @Test
    void anAccessBundleTheProjectsKeyDidNotSignIsNotApplied() throws Exception {
        final Path key = key("operator");
        final Path access = bundle(access(key("stranger")), "access.bundle");
        final Path project = bundle(project(ACCESS_URL, key), "project.bundle");

        final int code =
                porelo(
                        "project",
                        "follow",
                        "demo",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString());

        assertThat(code).as("an access refusal refuses the whole follow").isNotZero();
        assertThat(new FollowedProjects(context().paths().projects().followed()).find("demo"))
                .as("no partially verified follow is published")
                .isNull();
        assertThat(context().paths().projects().configurationSigners())
                .as("the refused follow pins no key")
                .doesNotExist();
    }

    @Test
    void aDryRunChecksTheAccessBundleAndRecordsNothing() throws Exception {
        final Path key = key("operator");
        final Path access = bundle(access(key("stranger")), "access.bundle");
        final Path project = bundle(project(ACCESS_URL, key), "project.bundle");

        final int code =
                porelo(
                        "project",
                        "follow",
                        "demo",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString(),
                        "--dry-run");

        assertThat(code).as("a refused access preview cannot authorize a follow").isEqualTo(70);
        assertThat(out.toString() + err)
                .as("the bundle's access file was checked, not the URL fetched")
                .contains("(access.yml) changes access.yml: signed by a key that is not pinned");
        assertThat(context().paths().projects().followed()).as("nothing recorded").doesNotExist();
    }

    @Test
    void anAccessBundleIsRefusedWhereItWouldBeUnusedOrFetchedAgain() throws Exception {
        final Path key = key("operator");
        final Path access = bundle(access(key), "access.bundle");
        final Path plain = bundle(project(null, key), "plain.bundle");

        assertThat(
                        porelo(
                                "project",
                                "follow",
                                "demo",
                                plain.toString(),
                                "--signed-by",
                                publicKey(key),
                                "--access-repository",
                                access.toString()))
                .as("a project naming no access repository has no use for it")
                .isEqualTo(65);
        assertThat(err.toString()).as("says so").contains("names no access repository");

        assertThat(
                        porelo(
                                "project",
                                "follow",
                                "demo",
                                "git@forge.example.org:demo/p.git",
                                "--signed-by",
                                publicKey(key),
                                "--access-repository",
                                access.toString()))
                .as("a project followed from an address is fetched again, its access file too")
                .isEqualTo(64);
        assertThat(err.toString()).as("says why").contains("followed from a file");

        assertThat(
                        porelo(
                                "project",
                                "follow",
                                "demo",
                                plain.toString(),
                                "--signed-by",
                                publicKey(key),
                                "--access-repository",
                                dir.resolve("missing").toString()))
                .as("a bundle that is not there")
                .isEqualTo(66);
        assertThat(context().paths().projects().followed())
                .as("nothing was recorded by any of them")
                .doesNotExist();
    }

    private Path bundle(final Path repo, final String file) {
        final Path bundle = dir.resolve(file);
        git(repo, "bundle", "create", "-q", bundle.toString(), "HEAD", "main");
        return bundle;
    }

    private Path access(final Path key) throws IOException {
        final Path access = repository("access");
        Files.writeString(access.resolve("access.yml"), ACCESS);
        commit(access, key);
        return access;
    }

    private Path project(final String accessUrl, final Path key) throws IOException {
        return project(accessUrl == null ? "plain" : "project", accessUrl, key);
    }

    private Path project(final String where, final String accessUrl, final Path key)
            throws IOException {
        final Path repo = repository(where);
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: "demo"
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                %s
                """
                        .formatted(
                                accessUrl == null
                                        ? ""
                                        : "access: {repository: \"" + accessUrl + "\"}"));
        commit(repo, key);
        return repo;
    }

    private Path repository(final String name) throws IOException {
        final Path repo = Files.createDirectories(dir.resolve(name));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "user.email", "operator@example.org");
        return repo;
    }

    private void commit(final Path repo, final Path signingKey) {
        git(repo, "add", "--all");
        git(
                repo,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signingKey,
                "commit",
                "-q",
                "-S",
                "-m",
                "configuration");
    }

    private Path key(final String name) {
        final Path key = dir.resolve(name);
        final CommandResult made =
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
                                key.toString()));
        assertThat(made.exitCode()).as("create a test key: " + made.standardError()).isZero();
        return key;
    }

    private String publicKey(final Path key) throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }

    private String git(final Path repo, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repo.toString()));
        all.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
        return result.standardOutput().strip();
    }
}
