package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A host's check of a project's file, on a real signed copy: the host acts on its answer. */
class ProjectVerifyCommandTest {

    private static final String FILE =
            """
            project:
              name: "demo"
              security_class: "guarded"
              upstream: "git@forge.example:demo/demo.git"
              review: "optional"
            image:
              from: "ubuntu:24.04"
            repositories:
              second:
                upstream: "git@forge.example:demo/second.git"
                central: "git@github.com:acme/second.git"
                review: "optional"
              first:
                upstream: "git@forge.example:demo/first.git"
              local:
            """;

    @TempDir private Path dir;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private Path repo;

    private Path signers;

    private @Nullable String gitDir;

    @BeforeEach
    void repository() throws Exception {
        repo = Files.createDirectory(dir.resolve("demo"));
        signers = dir.resolve("signers");
        for (final String holder : List.of("alice", "mallory")) {
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen",
                            "-q",
                            "-t",
                            "ed25519",
                            "-N",
                            "",
                            "-f",
                            dir.resolve(holder).toString()));
        }
        Files.writeString(signers, line("alice", ""));
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Project");
        git("config", "user.email", "project@example.org");
        git("config", "gpg.format", "ssh");
    }

    private String line(final String holder, final String options) throws Exception {
        return holder + " " + options + Files.readString(dir.resolve(holder + ".pub"));
    }

    private void git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        runner.runOrFail(
                Command.of(all)
                        .in(repo)
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
    }

    private void commit(final String text, final String signer) throws Exception {
        Files.writeString(repo.resolve("project.yml"), text);
        git("add", "-A");
        git("-c", "user.signingkey=" + dir.resolve(signer), "commit", "-q", "-S", "-m", "a change");
    }

    private int verify() {
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
        final CommandLine line =
                PoreloCli.commandLine(
                        new PoreloContext(
                                runner,
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0,
                                name -> null));
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line.execute(
                "project",
                "verify",
                "--repository",
                gitDir == null ? repo.resolve(".git").toString() : gitDir,
                "--signers",
                signers.toString(),
                "--json");
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    @Test
    void aSignedFileIsAnsweredWithItsReviewsAndAddressesInTheOrderWritten() throws Exception {
        commit(FILE, "alice");

        assertThat(verify()).as(() -> out + " " + err).isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("project")).isEqualTo("demo");
        assertThat(answer.get("project_review")).isEqualTo("optional");
        final Map<?, ?> repositories = (Map<?, ?>) answer.get("repositories");
        assertThat(repositories.keySet().stream().map(String::valueOf).toList())
                .containsExactly("second", "first", "local");
        final Map<?, ?> second = (Map<?, ?>) repositories.get("second");
        assertThat(second.get("review")).isEqualTo("optional");
        assertThat(second.get("upstream"))
                .as("a file not yet renamed keeps where its work goes")
                .isEqualTo("git@forge.example:demo/second.git");
        assertThat(second.get("central")).isEqualTo("git@github.com:acme/second.git");
        final Map<?, ?> first = (Map<?, ?>) repositories.get("first");
        assertThat(first.get("review")).as("left out").isEqualTo("required");
        assertThat(first.containsKey("central")).as("named, and null").isTrue();
        assertThat(first.get("central")).as("an old 'upstream' names no central").isNull();
        final Map<?, ?> local = (Map<?, ?>) repositories.get("local");
        assertThat(local.containsKey("upstream")).isTrue();
        assertThat(local.get("upstream")).isNull();
        assertThat(answer.get("renames"))
                .as("each key under its old name, said and not refused")
                .isEqualTo(
                        List.of(
                                "project.upstream",
                                "repositories.second.upstream",
                                "repositories.first.upstream"));
    }

    @Test
    void aFileThatSaysCentralHasNothingToRename() throws Exception {
        commit(
                FILE.replace("upstream:", "central:")
                        .replace("    central: \"git@forge.example:demo/second.git\"\n", ""),
                "alice");

        assertThat(verify()).as(() -> out + " " + err).isZero();

        assertThat(answer().get("renames")).isEqualTo(List.of());
    }

    @Test
    void aFileSignedByNoSignerAnswersNothingOfIt() throws Exception {
        commit(FILE, "mallory");

        assertThat(verify()).isEqualTo(65);

        assertThat(out.toString()).doesNotContain("optional").doesNotContain("second");
    }

    @Test
    void aBadValueIsRefusedNamingTheKey() throws Exception {
        commit(FILE.replace("    review: \"optional\"", "    review: \"maybe\""), "alice");

        assertThat(verify()).isEqualTo(65);

        assertThat(out + " " + err).contains("repositories.second.review");
    }

    @Test
    void aSignerNotYetInForceSignsNothing() throws Exception {
        commit(FILE, "alice");
        Files.writeString(signers, line("alice", "valid-after=\"29000101\" "));

        assertThat(verify()).isEqualTo(65);
    }

    @Test
    void signersThatAreNoSignersAreUsageAndNotPrinted() throws Exception {
        commit(FILE, "alice");
        Files.writeString(signers, "a line naming a secret\n");

        assertThat(verify()).isEqualTo(64);

        assertThat(out + " " + err).doesNotContain("secret");
    }

    @Test
    void aSignerWithATimeInItsDayIsUsageForTheDaysAloneAreJudged() throws Exception {
        commit(FILE, "alice");
        Files.writeString(signers, line("alice", "valid-after=\"20261103120000Z\" "));

        assertThat(verify()).isEqualTo(64);
    }

    @Test
    void signersThatAreNoTextAreUsage() throws Exception {
        commit(FILE, "alice");
        Files.write(signers, new byte[] {(byte) 0xFF, '\n'});

        assertThat(verify()).isEqualTo(64);
    }

    @Test
    void aCopyNamedRelativeToWhereThisRunsIsRead() throws Exception {
        commit(FILE, "alice");
        gitDir = Path.of("").toAbsolutePath().relativize(repo.resolve(".git")).toString();

        assertThat(verify()).as(err.toString()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "https://user:secret-fixture@forge.example/demo/first.git",
                "https://secret-fixture@forge.example/demo/first.git",
                "https://forge.example/demo/first.git?token=secret-fixture"
            })
    void anAddressHoldingASecretIsRefusedAndNotPrinted(final String address) throws Exception {
        commit(FILE.replace("git@forge.example:demo/first.git", address), "alice");

        assertThat(verify()).isEqualTo(65);

        assertThat(out + " " + err).doesNotContain("secret-fixture");
    }
}
