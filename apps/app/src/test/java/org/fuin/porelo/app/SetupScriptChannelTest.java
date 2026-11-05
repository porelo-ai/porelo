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

    @Test
    void aShownRunOnAPreparedMachineEndsSayingNothingWouldChange() throws Exception {
        final String said = prepared("ubuntu", true, false);

        assertThat(said).as("nothing to install, what it says").doesNotContain("apt-get install");
        assertThat(said.strip().lines().toList().getLast()).isEqualTo("nothing would change");
    }

    @Test
    void aShownRunNamesWhatWouldChange() throws Exception {
        final String said = prepared("ubuntu", false, false);

        assertThat(said)
                .contains(
                        "apt-get install -y -qq ca-certificates curl",
                        "apt-get install -y -qq porelo");
        assertThat(said.strip().lines().toList().getLast())
                .isEqualTo("would change: packages key source porelo filter account linger subids");
    }

    @Test
    void anInstalledPackageOlderThanOfferedIsSaidAndNotUpgraded() throws Exception {
        final String said = prepared("ubuntu", true, true);

        assertThat(said)
                .contains("porelo 1.0 is installed and the repository offers 2.0")
                .doesNotContain("apt-get install");
        assertThat(said.strip().lines().toList().getLast()).isEqualTo("nothing would change");
    }

    @Test
    void jsonAnswersWhatChangedAloneOnStandardOutput() throws Exception {
        prepared("ubuntu", true, false, "--json");
        assertThat(standardOutput).isEqualTo("{\"changed\":false,\"parts\":[]}\n");

        prepared("ubuntu", false, false, "--json");
        assertThat(standardOutput)
                .isEqualTo(
                        "{\"changed\":true,\"parts\":[\"packages\",\"key\",\"source\",\"porelo\","
                                + "\"filter\",\"account\",\"linger\",\"subids\"]}\n");
    }

    @Test
    void fedoraAsksRpmWhatIsThere() throws Exception {
        final String said = prepared("fedora", true, false);

        assertThat(said).doesNotContain("dnf install");
        assertThat(said.strip().lines().toList().getLast()).isEqualTo("nothing would change");
    }

    private String standardOutput = "";

    /**
     * Runs a copy of the script with {@code --show} on a machine stood in for: its fixed paths
     * point here, in the copy alone, so root reads no variable that could move them.
     *
     * @param id The system.
     * @param ready Whether the machine is prepared.
     * @param older Whether the repository offers newer than what is installed.
     * @param more More arguments.
     * @return What it said, standard error after standard output.
     */
    private String prepared(
            final String id, final boolean ready, final boolean older, final String... more)
            throws IOException, InterruptedException {
        final Path root = Files.createDirectories(dir.resolve("machine"));
        final Path osRelease = root.resolve("os-release");
        Files.writeString(osRelease, "ID=" + id + "\nPRETTY_NAME=\"" + id + "\"\n");
        final Path keyring = root.resolve("porelo.gpg");
        final Path list = root.resolve("porelo.list");
        final Path repo = root.resolve("porelo.repo");
        final Path subuid = root.resolve("subuid");
        final Path subgid = root.resolve("subgid");
        Files.deleteIfExists(keyring);
        Files.deleteIfExists(list);
        Files.deleteIfExists(repo);
        Files.deleteIfExists(subuid);
        Files.deleteIfExists(subgid);
        if (ready) {
            Files.writeString(keyring, "");
            Files.writeString(
                    list,
                    "deb [signed-by="
                            + keyring
                            + "] https://fuinorg.jfrog.io/artifactory/porelo-dist-deb releases"
                            + " main\n");
            Files.writeString(
                    repo,
                    "[porelo]\nname=Porelo\nbaseurl=https://fuinorg.jfrog.io/artifactory/"
                            + "porelo-dist-rpm/releases\nenabled=1\ngpgcheck=0\n");
            Files.writeString(subuid, "x:100000:65536\n");
            Files.writeString(subgid, "x:100000:65536\n");
        }
        final Path copy = root.resolve("porelo-setup.sh");
        Files.writeString(
                copy,
                Files.readString(ROOT.resolve("dist/dist-setup/porelo-setup.sh"))
                        .replace("/usr/share/keyrings/porelo.gpg", keyring.toString())
                        .replace("/etc/apt/sources.list.d/porelo.list", list.toString())
                        .replace("/etc/yum.repos.d/porelo.repo", repo.toString())
                        .replace("/etc/subuid", subuid.toString())
                        .replace("/etc/subgid", subgid.toString()));
        final Path stand = Files.createDirectories(dir.resolve("standing"));
        standIn(stand.resolve("id"), ready ? "echo 1000\n" : "exit 1\n");
        standIn(stand.resolve("loginctl"), ready ? "echo Linger=yes\n" : "exit 1\n");
        standIn(
                stand.resolve("dpkg-query"),
                ready
                        ? "case \"$*\" in *Status*) printf 'ii ' ;; *) printf '1.0' ;; esac\n"
                        : "exit 1\n");
        standIn(
                stand.resolve("apt-cache"),
                "echo '  Candidate: " + (older ? "2.0" : "1.0") + "'\n");
        standIn(stand.resolve("dpkg"), older ? "exit 0\n" : "exit 1\n");
        standIn(stand.resolve("rpm"), ready ? "printf '1.0'\n" : "exit 1\n");
        standIn(stand.resolve("dnf"), "exit 0\n");

        final List<String> command = new ArrayList<>();
        command.add("sh");
        command.add(copy.toString());
        command.addAll(
                List.of(
                        "--show",
                        "--os-release",
                        osRelease.toString(),
                        "--user",
                        "x",
                        "--distribution",
                        "releases"));
        command.addAll(List.of(more));
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PATH", stand + ":" + System.getenv("PATH"));
        final Path out = root.resolve("out");
        final Path err = root.resolve("err");
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        final Process process = builder.start();
        assertThat(process.waitFor()).as(Files.readString(err)).isZero();
        standardOutput = Files.readString(out);
        return standardOutput + Files.readString(err);
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
