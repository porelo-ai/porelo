package org.fuin.porelo.planning.api;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class InstalledPlanningTest {
    @TempDir Path directory;

    @Test
    void ordinaryCallTimeoutReapsTheAdapterAndTheNextCallRestartsIt() throws Exception {
        try (var client = client("slow-call")) {
            assertThatThrownBy(() -> client.getWorkflow(Map.of(), "synthetic"))
                    .isInstanceOf(PlanningException.class)
                    .hasMessageContaining("within 1 seconds");
            long old = Long.parseLong(Files.readString(directory.resolve("pid")));
            assertThat(ProcessHandle.of(old).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(client.describe().name()).isEqualTo("example");
            assertThat(Files.readString(directory.resolve("pid"))).isNotEqualTo(Long.toString(old));
        }
    }

    @Test
    void startupIsBoundedAndNeverEchoesAdapterOutput() throws Exception {
        assertTimeout("no-ready");
    }

    @Test
    void socketDescribeIsBoundedAndReapsTheAdapter() throws Exception {
        assertTimeout("slow-describe");
    }

    private void assertTimeout(String mode) throws Exception {
        assertThatThrownBy(() -> client(mode))
                .isInstanceOf(PlanningException.class)
                .hasMessageContaining("within 3 seconds")
                .hasMessageNotContaining("untrusted-output");
        long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private InstalledPlanning client(String mode) throws Exception {
        Path executable = directory.resolve("example");
        Files.writeString(
                executable,
                "#!/bin/sh\nexec "
                        + quote(Path.of(System.getProperty("java.home"), "bin/java").toString())
                        + " "
                        + quote("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"))
                        + " -cp "
                        + quote(System.getProperty("java.class.path"))
                        + " "
                        + quote(AdapterMain.class.getName())
                        + " "
                        + quote(mode)
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new InstalledPlanning(
                executable, directory, directory, mode.equals("slow-call") ? 10 : 3, 1);
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    public static final class AdapterMain {
        public static void main(String[] args) throws Exception {
            Files.writeString(
                    Path.of(args[3]).resolve("pid"), Long.toString(ProcessHandle.current().pid()));
            if (args[0].equals("no-ready")) {
                System.out.println("untrusted-output");
                System.out.flush();
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                return;
            }
            try (var server = new VarlinkServer(Path.of(args[2]), PlanningProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (p, replies) -> {
                            if (args[0].equals("slow-describe")) pause();
                            replies.last(
                                    Map.of(
                                            "protocolVersion",
                                            1,
                                            "name",
                                            "example",
                                            "version",
                                            "1",
                                            "capabilities",
                                            java.util.List.of(),
                                            "credentials",
                                            java.util.List.of()));
                        });
                server.method(
                        "GetWorkflow",
                        (p, replies) -> {
                            pause();
                            replies.last(Map.of());
                        });
                System.out.println("ready " + server.socketPath());
                System.out.flush();
                server.run();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
