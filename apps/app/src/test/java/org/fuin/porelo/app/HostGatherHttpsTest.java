package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class HostGatherHttpsTest {
    @TempDir Path dir;
    private static final String TOKEN = "https-central-test-credential";
    private static final byte[] ADVERTISEMENT =
            "001f# service=git-receive-pack\n0000".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void authenticatedReceiveRefusalPreventsAllPushesEvenWhenAnonymousReadsWork(int refusal)
            throws Exception {
        // An anonymous public read must never hide a refused write credential or allow a partial
        // release.
        final var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try {
            final Path ca = configure(server);
            server.createContext(
                    "/",
                    exchange -> {
                        final String route = exchange.getRequestURI().toString();
                        requests.add(exchange.getRequestMethod() + " " + route);
                        final boolean receive =
                                route.endsWith("/info/refs?service=git-receive-pack");
                        final boolean first = route.contains("/first.git/");
                        final boolean denied = receive && (refusal == 1 ? first : !first);
                        if (receive)
                            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                    .as("the receive endpoint sees the same credential as Git")
                                    .isEqualTo("Basic " + basic());
                        final byte[] bytes =
                                denied
                                        ? (TOKEN + " " + basic()).getBytes(StandardCharsets.UTF_8)
                                        : receive
                                                ? ADVERTISEMENT
                                                : "001e# service=git-upload-pack\n00000000"
                                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders()
                                .add(
                                        "Content-Type",
                                        "application/x-"
                                                + (receive ? "git-receive-pack" : "git-upload-pack")
                                                + "-advertisement");
                        exchange.sendResponseHeaders(
                                denied ? 401 : 200,
                                bytes.length + (denied && refusal == 3 ? 100 : 0));
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    });
            server.start();
            final String origin = "https://127.0.0.1:" + server.getAddress().getPort();
            try (var processes =
                    new HostGatherRunner(java.time.Duration.ofSeconds(20), () -> true)) {
                final var anonymous =
                        processes.run(
                                Command.of(
                                                "/usr/bin/curl",
                                                "--disable",
                                                "--silent",
                                                "--noproxy",
                                                "*",
                                                "--cacert",
                                                ca.toString(),
                                                "--max-time",
                                                "5",
                                                "--write-out",
                                                "\n%{http_code}",
                                                origin
                                                        + "/acme/first.git/info/refs?service=git-upload-pack")
                                        .withChosenEnvironment(Map.of()));
                assertThat(anonymous.standardOutput())
                        .as("this fixture permits a public read")
                        .endsWith("\n200");
                final var config =
                        new HostGatherConfig(
                                "http://127.0.0.1:3001",
                                "acme",
                                ca,
                                List.of(
                                        new HostGatherConfig.Repository(
                                                "first", origin + "/acme/first.git"),
                                        new HostGatherConfig.Repository(
                                                "second", origin + "/acme/second.git")));
                final var gitCalls = new ArrayList<Command>();
                final var flow =
                        new HostGatherFlow(
                                command -> {
                                    if (command.program().endsWith("/curl"))
                                        return processes.run(command);
                                    gitCalls.add(command);
                                    return new CommandResult(command, 0, "approved\n", "");
                                },
                                Path.of("/usr/bin/git"),
                                dir);
                final var error =
                        assertThrows(
                                HostGatherRefusal.class,
                                () ->
                                        flow.transfer(
                                                false,
                                                config,
                                                List.of(),
                                                "local-fixture".toCharArray(),
                                                TOKEN.toCharArray(),
                                                new PrintWriter(new StringWriter()),
                                                new PrintWriter(new StringWriter()),
                                                (r, s) -> {}));
                assertThat(error.kind())
                        .as("real HTTPS status reaches the shared typed result")
                        .isEqualTo(HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED);
                assertThat(error.details())
                        .containsEntry("status", 401)
                        .containsEntry("forge", "127.0.0.1:" + server.getAddress().getPort())
                        .containsEntry("pushed", List.of());
                assertThat(error.toString() + error.details()).doesNotContain(TOKEN, basic());
                assertThat(gitCalls).as("no transfer or credential prompt runs").isEmpty();
                assertThat(requests)
                        .as("the authentication probe sends no push request")
                        .allMatch(request -> request.startsWith("GET "));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRedirectCannotCarryAuthorizationToAnotherEndpoint() throws Exception {
        // A central redirect must not lend the person's token or begin any push.
        final var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var visited = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try {
            final Path ca = configure(server);
            server.createContext(
                    "/",
                    exchange -> {
                        visited.add(exchange.getRequestURI().getPath());
                        exchange.getResponseHeaders().add("Location", "/stolen");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            server.start();
            final String origin = "https://127.0.0.1:" + server.getAddress().getPort();
            try (var runner = new HostGatherRunner(java.time.Duration.ofSeconds(20), () -> true)) {
                final int code =
                        new HostGatherFlow(runner, Path.of("/usr/bin/git"), dir)
                                .run(
                                        false,
                                        new HostGatherConfig(
                                                "http://127.0.0.1:3001",
                                                "acme",
                                                ca,
                                                List.of(
                                                        new HostGatherConfig.Repository(
                                                                "first",
                                                                origin + "/acme/first.git"))),
                                        List.of(),
                                        "local-fixture".toCharArray(),
                                        TOKEN.toCharArray(),
                                        new PrintWriter(new StringWriter()),
                                        new PrintWriter(new StringWriter()));
                assertThat(code).as("a redirect cannot authorize a transfer").isNotZero();
                assertThat(visited)
                        .as("only the configured endpoint receives a request")
                        .containsExactly("/acme/first.git/info/refs");
            }
        } finally {
            server.stop(0);
        }
    }

    private Path configure(HttpsServer server) throws Exception {
        final Path store = dir.resolve("fixture.p12");
        final Path ca = dir.resolve("fixture.pem");
        keytool(
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "EC",
                "-dname",
                "CN=127.0.0.1",
                "-ext",
                "san=ip:127.0.0.1",
                "-validity",
                "2",
                "-keystore",
                store.toString());
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                "fixture",
                "-keystore",
                store.toString(),
                "-file",
                ca.toString());
        final var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "fixture".toCharArray());
        }
        final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture".toCharArray());
        final var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        return ca;
    }

    private void keytool(String... arguments) {
        final var argv =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin/keytool")
                                        .toString()));
        argv.addAll(List.of(arguments));
        argv.addAll(List.of("-storepass", "fixture"));
        new ProcessCommandRunner()
                .run(
                        Command.of(argv)
                                .in(dir)
                                .withChosenEnvironment(
                                        Map.of(
                                                "PATH",
                                                "/usr/bin:/bin",
                                                "JAVA_TOOL_OPTIONS",
                                                "-XX:-UsePerfData -Djava.io.tmpdir=" + dir)))
                .orFail();
    }

    private static String basic() {
        return Base64.getEncoder()
                .encodeToString(("x-access-token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    }
}
