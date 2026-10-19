package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the setup script to the channel the machine's Porelo came from.
 *
 * <p><strong>A second run must not move a machine to another channel.</strong> The script is
 * shipped inside the {@code porelo} package, so a host runs it again after Porelo is installed. It
 * used to default to {@code releases} whatever was there, so a snapshot machine prepared again was
 * switched to releases without anyone asking. The script runs here with {@code --show}, which runs
 * nothing, and with stand-ins for the two package queries it asks.
 */
class SetupScriptChannelTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    private static final String SNAPSHOTS = "distribution 'snapshots'";
    private static final String RELEASES = "distribution 'releases'";

    @TempDir Path dir;

    @Test
    void aSnapshotPoreloKeepsTheSnapshotChannel() throws Exception {
        assertThat(shown("ubuntu", "0.5.1~snapshot.312"))
                .contains(SNAPSHOTS)
                .doesNotContain(RELEASES);
    }

    @Test
    void aReleasedPoreloKeepsTheReleaseChannel() throws Exception {
        assertThat(shown("ubuntu", "0.5.1")).contains(RELEASES);
    }

    @Test
    void aMachineWithoutPoreloTakesReleases() throws Exception {
        assertThat(shown("ubuntu", null)).contains(RELEASES + ", since no porelo is installed");
    }

    @Test
    void aDistributionAskedForWinsOverWhatIsInstalled() throws Exception {
        assertThat(shown("ubuntu", "0.5.1~snapshot.312", "--distribution", "releases"))
                .contains(RELEASES + ", as asked")
                .doesNotContain(SNAPSHOTS);
    }

    @Test
    void fedoraKeepsTheSnapshotChannelToo() throws Exception {
        assertThat(shown("fedora", "0.5.1~snapshot.312"))
                .contains(SNAPSHOTS + ", as the installed porelo 0.5.1~snapshot.312")
                .doesNotContain(RELEASES);
    }

    /**
     * Runs the script with {@code --show} on a system of the given id, where {@code porelo} is
     * installed at the given version, or not at all for {@code null}.
     */
    private String shown(final String id, final @Nullable String installed, final String... more)
            throws IOException, InterruptedException {
        final Path osRelease = dir.resolve("os-release");
        Files.writeString(osRelease, "ID=" + id + "\nPRETTY_NAME=\"" + id + "\"\n");
        final Path stand = Files.createDirectories(dir.resolve("bin"));
        // Both queries answer for porelo alone, as the real ones would on a machine holding only
        // it.
        final String answer =
                installed == null
                        ? "exit 1\n"
                        : "case \" $* \" in *\" porelo \"*) printf '%s' '"
                                + installed
                                + "' ;; *) exit 1 ;; esac\n";
        standIn(stand.resolve("dpkg-query"), answer);
        standIn(stand.resolve("rpm"), answer);

        final List<String> command = new ArrayList<>();
        command.add("sh");
        command.add(ROOT.resolve("dist/dist-setup/porelo-setup.sh").toString());
        command.addAll(List.of("--show", "--os-release", osRelease.toString(), "--user", "x"));
        command.addAll(List.of(more));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PATH", stand + ":" + System.getenv("PATH"));
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script ended").isTrue();
        assertThat(process.exitValue()).as("the script's exit, after:%n%s", out).isZero();
        return out;
    }

    private static void standIn(final Path file, final String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }
}
