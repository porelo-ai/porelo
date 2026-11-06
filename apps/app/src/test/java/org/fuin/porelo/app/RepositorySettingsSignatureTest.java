package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An organization's repository file counts at a start only when every change to it is signed. */
class RepositorySettingsSignatureTest {

    private static final String KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPoreloTestKeyThatSignedNothingHereAtAll0";

    @TempDir Path dir;

    @Test
    void anUnsignedChangeIsRefusedNamingTheFileAndItsCommit() throws Exception {
        final PoreloContext context = context();
        place(context);
        final String commit = mirror(context, "uc", "web", "egress:\n  domains: []\n");

        assertThatThrownBy(() -> check(context))
                .isInstanceOfSatisfying(
                        RepositorySettingsSignature.Unsigned.class,
                        unsigned -> {
                            assertThat(unsigned.file()).isEqualTo("porelo.yml");
                            assertThat(unsigned.commit()).isEqualTo(commit);
                        })
                .hasMessageContaining("without a signature")
                .hasMessageContaining("uc");
    }

    @Test
    void aProjectThisMachineDidNotTakeFromAnOrganizationIsNotJudgedHere() throws Exception {
        final PoreloContext context = context();
        mirror(context, "uc", "web", "egress:\n  domains: []\n");

        assertThatNoException().isThrownBy(() -> check(context));
    }

    @Test
    void theStartsCheckAnswersSettingsUnsignedWithTheFileAndCommit() throws Exception {
        final PoreloContext context = context();
        place(context);
        final String commit = mirror(context, "uc", "web", "egress:\n  domains: []\n");
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n"
                        + "repositories:\n  web: {}\n");

        final StartCheck.Result answer =
                StartCheck.withSettings(
                        context,
                        new StartCheck.Result(StartCheck.Outcome.READY, "", "", "", ""),
                        projectFile,
                        "web");

        assertThat(answer.outcome()).isEqualTo(StartCheck.Outcome.SETTINGS_UNSIGNED);
        assertThat(answer.file()).isEqualTo("porelo.yml");
        assertThat(answer.commit()).isEqualTo(commit);
        assertThat(answer.asMap()).containsEntry("commit", commit);
    }

    private void check(final PoreloContext context) {
        final Path mirror = GateSupport.mirror(context, "uc", "web");
        RepositorySettingsSignature.check(context, "uc", "web", mirror, "refs/heads/main");
    }

    /** Takes the project as a follow would, with one admin's key that signed nothing here. */
    private static void place(final PoreloContext context) throws IOException {
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "uc",
                                "0".repeat(40),
                                "https://repo.example.org/uc/settings",
                                null,
                                List.of(
                                        new OrganizationProjects.Key(
                                                "admin-person",
                                                "admin",
                                                KEY,
                                                LocalDate.of(2026, 1, 1),
                                                LocalDate.of(2027, 1, 1),
                                                null)),
                                null,
                                null,
                                null));
    }

    /** A bare mirror whose main branch changed porelo.yml in one unsigned commit. */
    private String mirror(
            final PoreloContext context,
            final String project,
            final String repository,
            final String porelo)
            throws Exception {
        final Path work = dir.resolve("work-" + repository);
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", "main");
        Files.writeString(work.resolve("porelo.yml"), porelo, StandardCharsets.UTF_8);
        git(work, "add", "porelo.yml");
        git(work, "commit", "-q", "-m", "Settings");
        final String commit = git(work, "rev-parse", "HEAD").strip();
        final Path mirror = GateSupport.mirror(context, project, repository);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", work.toString(), mirror.toString());
        return commit;
    }

    private static String git(final Path in, final String... arguments) throws Exception {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=Someone",
                                "-c",
                                "user.email=someone@repo.example.org",
                                "-c",
                                "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        final Process process =
                new ProcessBuilder(command)
                        .directory(in.toFile())
                        .redirectErrorStream(true)
                        .start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

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
                new org.fuin.porelo.core.process.ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }
}
