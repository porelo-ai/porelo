package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeGateLoginTest {
    @TempDir Path root;

    private String login(long uid, String shell, boolean member, boolean primary, boolean exists)
            throws Exception {
        final var files = new ProjectFiles(root, 1000);
        final var runtime =
                new ProjectRuntime(
                        files,
                        (argv, input, environment, timeout) -> {
                            assertThat(argv.subList(0, 2))
                                    .as("NSS goes through the shared checked runner")
                                    .isIn(
                                            List.of("/usr/bin/getent", "passwd"),
                                            List.of("/usr/bin/getent", "group"));
                            if (argv.get(1).equals("passwd"))
                                return new HostPrograms.Result(
                                        exists ? 0 : 2,
                                        exists
                                                ? "person:x:"
                                                        + uid
                                                        + ":1000::/home/person:"
                                                        + shell
                                                        + "\n"
                                                : "");
                            return new HostPrograms.Result(
                                    0,
                                    "porelo-people:x:"
                                            + (primary ? 1000 : 2000)
                                            + ":"
                                            + (member ? "person" : "")
                                            + "\n");
                        },
                        Path::of);
        return runtime.gateLogin("person");
    }

    @Test
    void webAdmissionIsResolvedBeforeReadingTheConnection() throws Exception {
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        final var gate =
                new ForgeGate(
                        "web",
                        () -> {
                            calls.incrementAndGet();
                            return "person";
                        },
                        java.util.Set::of,
                        () -> {
                            throw new AssertionError("no request");
                        });
        final var input =
                new java.io.InputStream() {
                    @Override
                    public int read() {
                        assertThat(calls.get())
                                .as("one admission lookup belongs to the connection")
                                .isOne();
                        return -1;
                    }
                };
        gate.serve(input, java.io.OutputStream.nullOutputStream());
        assertThat(calls.get()).isOne();
    }

    // py: test_unknown_peer_is_refused
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"-1", "1005", "4294967294"})
    void numericPeerNamesReceiveForbiddenInsteadOfCrashing(String peer) throws Exception {
        final var runtime =
                new ProjectRuntime(
                        new ProjectFiles(root, 1000),
                        (argv, input, environment, timeout) ->
                                new HostPrograms.Result(64, "invalid option"),
                        Path::of);
        final var gate =
                new ForgeGate(
                        "web",
                        () -> runtime.gateLogin(peer),
                        java.util.Set::of,
                        () -> {
                            throw new AssertionError("unknown peer");
                        });
        final var output = new java.io.ByteArrayOutputStream();
        gate.serve(
                new java.io.ByteArrayInputStream(
                        "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n\r\n"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.US_ASCII))
                .startsWith("HTTP/1.1 403 Forbidden\r\n");
    }

    // py: test_root_service_and_unadmitted_callers_are_refused
    @Test
    void rootServiceAndUnadmittedCallersAreRefused() throws Exception {
        assertThat(login(0, "/bin/bash", true, false, true))
                .as("root cannot impersonate an admitted person")
                .isNull();
        assertThat(login(999, "/bin/bash", true, false, true))
                .as("a service uid cannot sign in")
                .isNull();
        assertThat(login(1000, "/usr/sbin/nologin", true, false, true))
                .as("a locked shell cannot sign in")
                .isNull();
        assertThat(login(1000, "/bin/bash", false, false, true))
                .as("an unadmitted person cannot sign in")
                .isNull();
    }

    // py: test_unknown_peer_is_refused
    @Test
    void anUnknownPeerIsRefused() throws Exception {
        assertThat(login(1000, "/bin/bash", true, false, false))
                .as("an unresolved peer cannot sign in")
                .isNull();
    }

    // py: test_primary_group_membership_is_admitted
    @Test
    void primaryGroupMembershipIsAdmitted() throws Exception {
        assertThat(login(1000, "/bin/bash", false, true, true))
                .as("primary membership counts as admission")
                .isEqualTo("person");
    }
}
