package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The arming as the forge's account runs it, over a forge laid out in a directory. */
class FollowPushesTest {

    @TempDir Path dir;

    private Path gitea;

    private Path judge;

    private Path organization;

    @BeforeEach
    void forge() throws Exception {
        gitea = Files.createDirectories(dir.resolve("gitea"));
        organization = Files.createDirectories(gitea.resolve("git/repositories/shop"));
        final Path hooks = Files.createDirectories(gitea.resolve("hooks"));
        executable(
                hooks.resolve("pre-receive"),
                "#!/bin/sh\ncustom_hooks_dir=\"./hooks/${hookname}.d\"\n");
        Files.createDirectories(gitea.resolve("home"));
        Files.writeString(
                gitea.resolve("home/.gitconfig"), "[core]\n\thooksPath = " + hooks + "\n");
        judge =
                executable(
                        Files.createDirectories(dir.resolve("libexec")).resolve("pre-receive"),
                        "#!/bin/sh\n");
    }

    private static Path executable(final Path path, final String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    private Path repository(final String name) throws Exception {
        final Path repository = organization.resolve(name + ".git");
        final Process init =
                new ProcessBuilder("git", "init", "-q", "--bare", repository.toString())
                        .redirectErrorStream(true)
                        .start();
        init.getInputStream().readAllBytes();
        assertThat(init.waitFor()).isZero();
        return repository;
    }

    private Path hook(final String name) {
        return organization.resolve(name + ".git/hooks/pre-receive.d/porelo");
    }

    /** Runs the script for the project 'shop' and returns its exit code. */
    private int arm() throws Exception {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "sh",
                                "-c",
                                FollowPushes.script(gitea.toString(), judge.toString()),
                                "sh",
                                "shop"));
        final Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .directory(dir.toFile())
                        .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void anOrdinaryForgeIsArmedWithTheJudgesLink() throws Exception {
        repository("app");

        assertThat(arm()).isZero();
        assertThat(Files.readSymbolicLink(hook("app"))).isEqualTo(judge);
    }

    @Test
    void aLinkedGitDirectoryStopsTheArming() throws Exception {
        // The forge's whole tree read through a link: what is armed is not the forge's.
        final Path moved = gitea.resolveSibling("elsewhere");
        Files.move(gitea.resolve("git"), moved);
        Files.createSymbolicLink(gitea.resolve("git"), moved);
        repository("app");

        assertThat(arm()).isEqualTo(6);
        assertThat(hook("app")).doesNotExist();
    }

    @Test
    void aLinkedRepositoryStopsTheArmingInsteadOfBeingPassedOver() throws Exception {
        final Path real = repository("real");
        Files.createSymbolicLink(organization.resolve("linked.git"), real);

        assertThat(arm()).isEqualTo(5);
    }

    @Test
    void aForgeHookThatIsALinkStopsTheArming() throws Exception {
        final Path hooks = gitea.resolve("hooks");
        final Path real = hooks.resolve("real-pre-receive");
        Files.move(hooks.resolve("pre-receive"), real);
        Files.createSymbolicLink(hooks.resolve("pre-receive"), real);
        repository("app");

        assertThat(arm()).isEqualTo(4);
        assertThat(hook("app")).doesNotExist();
    }

    @Test
    void aJudgeThatIsALinkStopsTheArming() throws Exception {
        final Path real = judge.resolveSibling("real-judge");
        Files.move(judge, real);
        Files.createSymbolicLink(judge, real);
        repository("app");

        assertThat(arm()).isEqualTo(5);
        assertThat(hook("app")).doesNotExist();
    }

    @Test
    void aStopLeavesNoLinkTheArmingMade() throws Exception {
        repository("app");
        final Path other = repository("other");
        // The second repository names hooks of its own, which the judge would not run under.
        Files.writeString(
                other.resolve("config"),
                "[core]\n\thooksPath = /elsewhere\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(arm()).isEqualTo(4);
        assertThat(Files.exists(hook("app"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("the first repository's link, made before the stop")
                .isFalse();
    }
}
