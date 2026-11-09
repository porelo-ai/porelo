package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoolGiveBackTest {
    @TempDir Path root;
    private final List<List<String>> calls = new ArrayList<>();
    private boolean unsafeSystemctl;

    private HostPool pool() throws Exception {
        final var files = new ProjectFiles(root, (int) Files.getAttribute(root, "unix:uid"));
        final var runtime =
                new ProjectRuntime(
                        files,
                        (argv, input, environment, timeout) -> {
                            calls.add(argv);
                            assertThat(Files.readString(root.resolve("var/lib/porelo-pool/run")))
                                    .as("resetting is durable before shutdown is scheduled")
                                    .isEqualTo("resetting\n");
                            return new HostPrograms.Result(0, "");
                        },
                        name -> {
                            if (unsafeSystemctl && name.equals("/usr/bin/systemctl"))
                                throw new java.io.UncheckedIOException(
                                        new java.io.IOException("unsafe executable"));
                            return Path.of(name);
                        });
        return new HostPool(
                files,
                runtime,
                () -> {},
                () -> () -> {},
                (started, lines) -> {},
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()));
    }

    @Test
    void onlyAnExistingSudoRunAccountCanGiveBack() throws Exception {
        final var pool = pool();
        for (var environment :
                List.of(
                        Map.<String, String>of(),
                        Map.of("SUDO_USER", "other"),
                        Map.of("SUDO_USER", "../other")))
            assertThatThrownBy(() -> pool.giveBack(environment))
                    .as("a caller cannot target another state path")
                    .isInstanceOf(HostRefusal.class);
        assertThat(calls).as("refusal schedules no shutdown").isEmpty();
    }

    @Test
    void aGiveBackWritesResettingThenSchedulesPoweroffTwoSecondsLater() throws Exception {
        final Path directory = ready();
        assertThat(pool().giveBack(Map.of("SUDO_USER", "run")))
                .as("the caller receives the scheduling result")
                .isZero();
        assertThat((int) Files.getAttribute(directory.resolve("run"), "unix:mode") & 0777)
                .as("the run account must still be able to read its resetting state")
                .isEqualTo(0644);
        assertThat(calls)
                .as("poweroff uses systemd's delayed service, never a shell")
                .containsExactly(
                        List.of(
                                "systemd-run",
                                "--quiet",
                                "--collect",
                                "--unit",
                                "porelo-pool-give-back",
                                "--on-active=2",
                                "/usr/bin/systemctl",
                                "poweroff"));
    }

    private Path ready() throws Exception {
        final Path directory = root.resolve("var/lib/porelo-pool");
        Files.createDirectories(directory);
        for (Path path = directory; !path.equals(root); path = path.getParent())
            Files.setAttribute(path, "unix:mode", 0700);
        Files.writeString(directory.resolve("run"), "ready\n");
        Files.setAttribute(directory.resolve("run"), "unix:mode", 0644);
        return directory;
    }

    @Test
    void theDelayedPrivilegedProgramIsCheckedBeforeChangingState() throws Exception {
        final Path directory = ready();
        unsafeSystemctl = true;
        assertThatThrownBy(() -> pool().giveBack(Map.of("SUDO_USER", "run")))
                .as("systemd must not execute an unchecked second program")
                .isInstanceOf(HostRefusal.class);
        assertThat(Files.readString(directory.resolve("run")))
                .as("a refused shutdown does not claim the account is resetting")
                .isEqualTo("ready\n");
        assertThat(calls).as("no delayed command is submitted").isEmpty();
    }
}
