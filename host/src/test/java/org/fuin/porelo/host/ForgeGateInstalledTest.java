package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeGateInstalledTest {
    @TempDir Path root;
    private static final String REQUEST = "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n\r\n";

    private ProjectFiles files() throws Exception {
        final var files = new ProjectFiles(root, (int) Files.getAttribute(root, "unix:uid"));
        Files.createDirectories(files.etc);
        for (Path path = files.etc; !path.equals(root); path = path.getParent())
            Files.setAttribute(path, "unix:mode", 0755);
        return files;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void installedConfigurationIsReadAsStrictUtf8BeforeAdmittingTheBrowser(boolean valid)
            throws Exception {
        final var files = files();
        final var bytes = new ByteArrayOutputStream();
        bytes.write("name=host.localhost\nport=3000\nother=".getBytes(StandardCharsets.UTF_8));
        bytes.write(valid ? new byte[] {'o', 'k'} : new byte[] {(byte) 0xff, (byte) 0xfe});
        bytes.write('\n');
        Files.write(files.etc.resolve("forge.conf"), bytes.toByteArray());
        Files.setAttribute(files.etc.resolve("forge.conf"), "unix:mode", 0644);
        if (valid)
            assertThat(ForgeRoutes.hosts(files.text(files.etc.resolve("forge.conf"), false)))
                    .contains("host.localhost:3000");
        final var gate =
                ForgeGate.installed("web", () -> "person", root.resolve("absent.sock"), files);
        final var out = new ByteArrayOutputStream();
        gate.serve(new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.US_ASCII)), out);
        assertThat(out.toString(StandardCharsets.US_ASCII))
                .startsWith(valid ? "HTTP/1.1 502 Bad Gateway\r\n" : "HTTP/1.1 403 Forbidden\r\n");
    }

    @Test
    void theInstalledGateReadsTheUnixPeersCredentialsAndRefusesAnUnknownAccount() throws Exception {
        final var files = files();
        final Path socket = Path.of("target", "installed-peer.sock");
        final var lookups = new AtomicInteger();
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            try (var client = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                    var peer = listener.accept()) {
                final String credential =
                        peer.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED).user().getName();
                final var runtime =
                        new ProjectRuntime(
                                files,
                                (argv, input, env, timeout) -> {
                                    assertThat(argv)
                                            .isEqualTo(
                                                    List.of(
                                                            "/usr/bin/getent",
                                                            "passwd",
                                                            credential));
                                    lookups.incrementAndGet();
                                    return new HostPrograms.Result(
                                            credential.matches("[a-z][a-z0-9_-]{0,31}") ? 2 : 64,
                                            "");
                                },
                                Path::of);
                final var gate =
                        ForgeGate.installed(
                                "web", peer, root.resolve("absent.sock"), files, runtime);
                final var out = new ByteArrayOutputStream();
                gate.serve(
                        new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.US_ASCII)), out);
                assertThat(out.toString(StandardCharsets.US_ASCII))
                        .startsWith("HTTP/1.1 403 Forbidden\r\n");
                assertThat(lookups.get())
                        .isEqualTo(credential.matches("[a-z][a-z0-9_-]{0,31}") ? 1 : 0);
            }
        } finally {
            Files.deleteIfExists(socket);
        }
    }
}
