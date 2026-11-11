package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An install takes the package lists of the last update that worked when its mirror fails, and
 * keeps the newest lists and packages for the next check (two Publish runs on 2026-11-11 failed on
 * an Ubuntu mirror during apt-get update, and no snapshot came out).
 */
class InstallCacheTest {

    private static final String ARCHIVE = "http://archive.ubuntu.com/ubuntu/";

    @TempDir Path dir;

    /** apt as the check meets it: an update that fails when asked to, and installs that fetch. */
    private static final String APT =
            """
            #!/bin/sh
            case "$1" in
                update)
                    if [ -n "$FAIL_UPDATE" ]; then
                        echo "E: Failed to fetch http://archive.ubuntu.com/ubuntu/dists/resolute/InRelease"
                        exit 100
                    fi
                    mkdir -p "$LISTS/partial" && echo fresh > "$LISTS/fresh_InRelease"
                    ;;
                install)
                    echo dependency > "$ARCHIVES/dependency_1.0_amd64.deb"
                    ;;
                autoclean)
                    rm -f "$ARCHIVES/"old_*.deb
                    ;;
            esac
            exit 0
            """;

    /** Runs the Ubuntu script's apt part here, its paths moved under the test's directory. */
    private Commands.Result aptPart(final boolean failUpdate) throws IOException {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path apt = Files.writeString(bin.resolve("apt-get"), APT);
        apt.toFile().setExecutable(true);
        final Path lists = Files.createDirectories(dir.resolve("lists"));
        final Path archives = Files.createDirectories(dir.resolve("archives"));
        Files.createDirectories(dir.resolve("conf"));
        final String script =
                new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/s.deb"))
                        .ubuntu(ARCHIVE);
        final String steps =
                script.substring(0, script.indexOf("echo \"STEP: porelo --version\""))
                        .replace("/tmp/step.", dir.resolve("step.").toString())
                        .replace("cache=/cache", "cache=" + dir.resolve("cache"))
                        .replace("lists=/var/lib/apt/lists", "lists=" + lists)
                        .replace("archives=/var/cache/apt/archives", "archives=" + archives)
                        .replace("/etc/apt/apt.conf.d/", dir.resolve("conf") + "/")
                        .replace("/etc/apt/sources.list.d/", dir.resolve("sources") + "/");
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "env",
                                "PATH=" + bin + ":/usr/bin:/bin",
                                "LISTS=" + lists,
                                "ARCHIVES=" + archives));
        if (failUpdate) {
            command.add("FAIL_UPDATE=1");
        }
        command.addAll(List.of("sh", "-c", steps));
        return Commands.runMerged(command);
    }

    private static List<String> names(final Path directory) throws IOException {
        try (Stream<Path> each = Files.list(directory)) {
            return each.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aFailedUpdateInstallsFromTheListsOfTheLastUpdateThatWorked() throws IOException {
        Files.createDirectories(dir.resolve("cache").resolve("lists"));
        Files.writeString(
                dir.resolve("cache").resolve("lists").resolve("kept_InRelease"), "kept\n");

        final Commands.Result result = aptPart(true);

        assertThat(result.output())
                .as("the install goes on, and says from what")
                .contains("CACHED: apt-get update failed")
                .contains("AGENT-OK")
                .doesNotContain("FAILED: apt-get update");
        assertThat(names(dir.resolve("lists")))
                .as("apt installs from the kept lists")
                .containsExactly("kept_InRelease");
        assertThat(names(dir.resolve("cache").resolve("lists")))
                .as("a failed update keeps nothing in their place")
                .containsExactly("kept_InRelease");
    }

    @Test
    void anUpdateThatWorkedIsKeptAndOnlyTheNewestPackages() throws IOException {
        final Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.createDirectories(cache.resolve("archives"));
        Files.writeString(cache.resolve("archives").resolve("old_0.9_amd64.deb"), "old\n");

        final Commands.Result result = aptPart(false);

        assertThat(result.output()).contains("AGENT-OK").doesNotContain("FAILED:", "CACHED:");
        assertThat(names(cache.resolve("lists")))
                .as("the lists of this update, without apt's own work files")
                .containsExactly("fresh_InRelease");
        assertThat(names(cache.resolve("archives")))
                .as("only what this install could fetch now; an older version is dropped")
                .containsExactly("dependency_1.0_amd64.deb");
    }

    @Test
    void withNoCacheAFailedUpdateFailsAsBefore() throws IOException {
        final Commands.Result result = aptPart(true);

        assertThat(result.output())
                .as("nothing to fall back on")
                .contains("FAILED: apt-get update")
                .doesNotContain("CACHED:");
        assertThat(dir.resolve("cache")).doesNotExist();
    }

    @Test
    void eachImageAndMirrorHasACacheOfItsOwnMountedWhereTheScriptReadsIt() {
        final List<List<String>> commands = new ArrayList<>();
        final Path cache = dir.resolve("kept");
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            commands.add(command);
                            return new Commands.Result(0, "");
                        },
                        cache);

        install.checkUbuntu("Ubuntu 26.04", "1", new Reports().report);
        install.check("Debian 13", Main.DEBIAN, install.debian(), "1", new Reports().report);

        assertThat(commands.get(0))
                .containsSequence(
                        "-v", cache.resolve("ubuntu-26.04-azure.archive.ubuntu.com") + ":/cache:Z");
        assertThat(commands.get(1))
                .containsSequence("-v", cache.resolve("debian-13") + ":/cache:Z");
        assertThat(cache.resolve("debian-13")).isDirectory();
        assertThat(
                        new Install(
                                        Path.of("/d/porelo.deb"),
                                        Path.of("/r/porelo.rpm"),
                                        Path.of("/a/stub.deb"),
                                        (command, limit) -> {
                                            commands.add(command);
                                            return new Commands.Result(0, "");
                                        })
                                .cache())
                .as("a check with no cache named mounts none")
                .isNull();
        assertThat(Install.cacheFrom(Map.<String, String>of().get(Install.CACHE_VARIABLE)))
                .isNull();
    }

    @Test
    void dnfInstallsFromWhatItKeptWhenOnlineFailsAndKeepsOnlyWhatIsInstalled() throws IOException {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        // Online it fails; from its cache alone it installs.
        final Path dnf =
                Files.writeString(
                        bin.resolve("dnf"),
                        "#!/bin/sh\nfor a; do [ \"$a\" = -C ] && exit 0; done\n"
                                + "echo 'Curl error (28): Timeout was reached'\nexit 1\n");
        dnf.toFile().setExecutable(true);
        final Path rpm =
                Files.writeString(
                        bin.resolve("rpm"), "#!/bin/sh\necho dependency-1.0-1.fc44.x86_64.rpm\n");
        rpm.toFile().setExecutable(true);
        final Path kept =
                Files.createDirectories(dir.resolve("cache").resolve("dnf").resolve("pkgs"));
        Files.writeString(kept.resolve("dependency-1.0-1.fc44.x86_64.rpm"), "now\n");
        Files.writeString(kept.resolve("dependency-0.9-1.fc44.x86_64.rpm"), "old\n");
        final String script =
                new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/s.deb"))
                        .fedora();
        final String steps =
                script.substring(0, script.indexOf("echo \"STEP: porelo --version\""))
                        .replace("/tmp/step.", dir.resolve("step.").toString())
                        .replace("/tmp/installed.", dir.resolve("installed.").toString())
                        .replace("cache=/cache", "cache=" + dir.resolve("cache"));

        final Commands.Result result =
                Commands.runMerged(
                        List.of("env", "PATH=" + bin + ":/usr/bin:/bin", "sh", "-c", steps));

        assertThat(result.output())
                .contains("CACHED: dnf could not install online")
                .contains("AGENT-OK")
                .doesNotContain("FAILED:");
        assertThat(names(kept))
                .as("an older package than the one installed is dropped")
                .containsExactly("dependency-1.0-1.fc44.x86_64.rpm");
    }
}
