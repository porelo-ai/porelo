package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostTaskReviewTest {
    @TempDir Path directory;

    @Test
    void creatingScratchKeepsThePublicRuntimeParentsTraversable() throws Exception {
        final var tree = new HostReadFixture(directory.resolve("root"));
        final var files = tree.files();
        files.syncDirectory("task");
        assertThat(Files.getPosixFilePermissions(tree.path("/run/porelo-host")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        files.directories(tree.path("/run/porelo-host/people"));
        assertThat(Files.getPosixFilePermissions(tree.path("/run/porelo-host/people")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getPosixFilePermissions(tree.path("/run/porelo-host/sync")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void bobCanReadAlicesTaskInTheirSharedProjectAndRootCanReadWithoutSudo() throws Exception {
        final var host = new HostTaskFixture(directory);
        final var started = host.run(host.certificate(), "task", "start", "t1");
        assertThat(started.code()).as(started.err()).isZero();
        final String fingerprint = host.fingerprint("task");
        final var bob =
                host.run(
                        Map.of("SUDO_USER", "bob", "SUDO_UID", "1001"),
                        "",
                        "task",
                        "show",
                        fingerprint);
        assertThat(bob.code()).as(bob.err()).isZero();
        assertThat(bob.out()).contains("\"login\":\"alice\"");
        assertThat(host.run(Map.of(), "", "task", "show", fingerprint).out()).isEqualTo(bob.out());
        host.tree.person("bob", "member=beta developer\n", "");
        final var outside =
                host.run(
                        Map.of("SUDO_USER", "bob", "SUDO_UID", "1001"),
                        "",
                        "task",
                        "show",
                        fingerprint);
        final var unknown =
                host.run(
                        Map.of("SUDO_USER", "bob", "SUDO_UID", "1001"),
                        "",
                        "task",
                        "show",
                        "SHA256:" + "Z".repeat(43));
        assertThat(outside.code()).isEqualTo(65);
        assertThat(outside.out()).isEqualTo(unknown.out());
        assertThat(outside.err())
                .isEqualTo(unknown.err().replace("Z".repeat(43), fingerprint.substring(7)));
    }

    // The man page's codes by verb, which the host's shell maps: an empty SUDO_USER is none (64),
    // an unknown person or a mismatched uid a refused caller (65).
    @ParameterizedTest
    @ValueSource(strings = {"empty", "unknown", "uid"})
    void taskAndTokenVerbsKeepThePersonsRefusalCodes(String state) throws Exception {
        final var host = new HostTaskFixture(directory);
        final Map<String, String> env =
                switch (state) {
                    case "empty" -> Map.of("SUDO_USER", "", "SUDO_UID", "1000");
                    case "unknown" -> Map.of("SUDO_USER", "carol", "SUDO_UID", "1000");
                    default -> Map.of("SUDO_USER", "alice", "SUDO_UID", "1001");
                };
        for (String command :
                List.of(
                        "task start t1",
                        "task end alpha t1",
                        "forge issue-token",
                        "forge lend-token")) {
            final var answer = host.run(env, "", command.split(" "));
            assertThat(answer.code())
                    .as(command + ": " + answer.err())
                    .isEqualTo(state.equals("empty") ? 64 : 65);
            assertThat(answer.out()).isEmpty();
        }
        assertThat(host.calls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "task start t1",
                "task end alpha t1",
                "task show bad",
                "forge lend-token",
                "forge issue-token"
            })
    void personVerbsRefuseNonrootWith69BeforeParsing(String command) {
        final StringWriter errors = new StringWriter();
        final int code =
                PoreloHost.run(
                        command.split(" "),
                        Map.of(),
                        () -> 1000,
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(errors, true));
        assertThat(code).isEqualTo(69);
        assertThat(errors.toString()).isEqualTo("porelo-host: runs only as root\n");
    }

    @Test
    void aWritableReplacementKrlIsRefusedAfterThePathWasChecked() throws Exception {
        final var host = new HostTaskFixture(directory);
        final Path krl = host.tree.path("/etc/ssh/porelo-revoked.krl");
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        host.tree.afterAttributes =
                path -> {
                    if (path.equals(krl) && reads.incrementAndGet() == 2) {
                        try {
                            Files.move(krl, krl.resolveSibling("old.krl"));
                            Files.writeString(krl, "untrusted KRL");
                            Files.setPosixFilePermissions(
                                    krl, PosixFilePermissions.fromString("rw-rw-rw-"));
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                };
        final var answer = host.run(host.certificate(), "task", "start", "t1");
        assertThat(answer.code()).as(answer.err()).isEqualTo(69);
        assertThat(host.calls).noneMatch(call -> call.getFirst().equals("systemd-run"));
        assertThat(host.record("t1")).doesNotExist();
    }

    @Test
    void anUnwritableTaskRecordNamesTheTaskAndAnswersNoToken() throws Exception {
        final var host = new HostTaskFixture(directory);
        final Path records = host.tree.path("/var/lib/porelo-host/tasks/alice");
        host.tree.files().directories(records);
        Files.setPosixFilePermissions(records, PosixFilePermissions.fromString("r-x------"));
        host.tree.overrides.put(records, Map.of("mode", 040700));
        try {
            final var answer = host.run(host.certificate(), "task", "start", "t1");
            assertThat(answer.code()).isEqualTo(73);
            assertThat(answer.err()).isEqualTo("porelo-host: cannot record task t1\n");
            assertThat(answer.out()).isEmpty();
            assertThat(host.calls).noneMatch(call -> call.contains("generate-access-token"));
        } finally {
            Files.setPosixFilePermissions(records, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aMaintenanceTimeoutKeepsItsFailureKind() {
        final var runtime =
                new ProjectRuntime(
                        new HostFiles(),
                        (argv, input, environment, timeout) -> {
                            throw new java.io.IOException(
                                    "private timeout details",
                                    new java.util.concurrent.TimeoutException());
                        },
                        Path::of,
                        (socket, token) -> {
                            throw new AssertionError("no API request");
                        });
        assertThatThrownBy(() -> runtime.command(List.of("machinectl", "shell", "forge")))
                .isInstanceOf(HostRefusal.class)
                .hasMessage("machinectl failed to complete (TimeoutExpired)");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCheckerCannotEchoACertificateOrItsEncodedPrefix(boolean prefix) throws Exception {
        final var host = new HostTaskFixture(directory);
        final String certificate = host.certificate();
        final String encoded = certificate.split(" ")[1];
        final String echoed = prefix ? encoded.substring(0, 32) : certificate;
        host.judgementError = "porelo: private checker echo " + echoed;
        final var answer = host.run(certificate, "task", "start", "t1");
        assertThat(answer.code()).isEqualTo(65);
        assertThat(answer.err().contains(echoed))
                .as("no certificate bytes reach diagnostics")
                .isFalse();
        assertThat(answer.out()).isEmpty();
    }

    @Test
    void aTaskCheckerTimeoutKeepsItsFailureKindWithoutPrivateDetails() throws Exception {
        final var host = new HostTaskFixture(directory);
        host.timeoutProgram = "systemd-run";
        final var answer = host.run(host.certificate(), "task", "start", "t1");
        assertThat(answer.code()).isEqualTo(69);
        assertThat(answer.err())
                .contains("systemd-run failed to complete (TimeoutExpired)")
                .doesNotContain("private timeout");
    }

    @Test
    void personAccountLookupsHaveNoInventedDeadlineAndChildrenKeepTheFixedHostPath()
            throws Exception {
        final var host = new HostTaskFixture(directory);
        final var answer = host.run("", "forge", "lend-token");
        assertThat(answer.code()).as(answer.err()).isZero();
        for (int i = 0; i < host.calls.size(); i++) {
            if (host.calls.get(i).contains("passwd"))
                assertThat(host.deadlines.get(i)).isEqualTo(java.time.Duration.ZERO);
            assertThat(host.environments.get(i).get("PATH"))
                    .isEqualTo("/usr/sbin:/usr/bin:/sbin:/bin");
        }
    }
}
