package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.supervisor.DeviceGrant;
import org.fuin.porelo.supervisor.Grants;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for an entry that names only its service: completed from the service's metadata, with a
 * client registered there once, and that registration deleted there again on removal - against a
 * stand-in authorization server on loopback.
 */
class ServiceRegistrationTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private HttpServer server;

    private String base;

    private final List<String> registrations = new CopyOnWriteArrayList<>();

    private final List<String> deviceRequests = new CopyOnWriteArrayList<>();

    private final List<String> revocations = new CopyOnWriteArrayList<>();

    private final List<String> deletions = new CopyOnWriteArrayList<>();

    private int deleteStatus = 204;

    private PoreloContext context;

    private KernelKeyring keyring;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @BeforeEach
    void start(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        document(
                "/.well-known/oauth-protected-resource/mcp",
                "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
        document(
                "/.well-known/oauth-authorization-server",
                "{\"issuer\":\""
                        + base
                        + "\",\"registration_endpoint\":\""
                        + base
                        + "/register\",\"device_authorization_endpoint\":\""
                        + base
                        + "/device\",\"token_endpoint\":\""
                        + base
                        + "/token\",\"revocation_endpoint\":\""
                        + base
                        + "/revoke\"}");
        server.createContext(
                "/register",
                exchange -> {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        deletions.add(
                                exchange.getRequestURI().getPath()
                                        + " "
                                        + exchange.getRequestHeaders().getFirst("Authorization"));
                        exchange.sendResponseHeaders(deleteStatus, -1);
                        exchange.close();
                        return;
                    }
                    registrations.add(body(exchange));
                    answer(
                            exchange,
                            201,
                            "{\"client_id\":\"dyn-"
                                    + registrations.size()
                                    + "\",\"registration_access_token\":\"rat-"
                                    + registrations.size()
                                    + "\",\"registration_client_uri\":\""
                                    + base
                                    + "/register/dyn-"
                                    + registrations.size()
                                    + "\"}");
                });
        server.createContext(
                "/device",
                exchange -> {
                    deviceRequests.add(body(exchange));
                    answer(
                            exchange,
                            200,
                            "{\"device_code\":\"dc-1\",\"user_code\":\"WDJB-MJHT\","
                                    + "\"verification_uri\":\"https://example.com/device\",\"expires_in\":900,\"interval\":5}");
                });
        server.createContext(
                "/revoke",
                exchange -> {
                    revocations.add(body(exchange));
                    answer(exchange, 200, "{}");
                });
        server.start();

        context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        context.vault()
                .write(
                        Map.of(
                                "forge",
                                new VaultEntry(
                                        GrantEntry.NO_SECRET,
                                        DeviceGrant.KIND,
                                        Map.of(
                                                ServiceRegistration.RESOURCE,
                                                base + "/mcp",
                                                "scopes",
                                                "repo offline_access"))),
                        PASSPHRASE);
        keyring.store(PASSPHRASE);
    }

    @AfterEach
    void stop() {
        if (keyring != null) {
            keyring.forget();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private void document(String path, String json) {
        server.createContext(path, exchange -> answer(exchange, 200, json));
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(payload);
        }
    }

    private int execute(String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void anEntryNamingOnlyItsServiceIsAuthorizedWithAClientRegisteredOnceAndKeptForEveryTask()
            throws Exception {
        // Harm: a client registered per authorization or per task, which services rate-limit and
        // which leaves registrations at the service nobody here can see.
        final VaultAuthorizeCommand.Shown first =
                VaultAuthorizeCommand.flowFor(context, "forge").start();
        final VaultAuthorizeCommand.Shown second =
                VaultAuthorizeCommand.flowFor(context, "forge").start();

        assertThat(registrations).as("one registration, however often it is authorized").hasSize(1);
        assertThat(first.code()).isEqualTo("WDJB-MJHT");
        assertThat(second.code()).isEqualTo("WDJB-MJHT");
        assertThat(deviceRequests)
                .as("both asked as the client the service registered")
                .hasSize(2)
                .allSatisfy(asked -> assertThat(asked).contains("client_id=dyn-1"));

        final VaultEntry entry = context.credentials().get("forge");
        final Grants.Service spent = Grants.service(entry.type(), entry.value(), entry.settings());
        assertThat(spent)
                .as("what the broker spends a grant with, for every task")
                .isEqualTo(new Grants.Service(base + "/token", "dyn-1", null, base + "/revoke"));
        assertThat(context.credentials())
                .as("the registration access token is not a credential to list or choose")
                .containsOnlyKeys("forge");
        assertThat(entry.settings().values())
                .as("no secret of the registration among the printable settings")
                .doesNotContain("rat-1");
        assertThat(
                        context.vault()
                                .read(PASSPHRASE)
                                .get(VaultNames.REGISTRATION_PREFIX + "forge")
                                .value())
                .as("kept hidden beside the entry, to delete the registration again")
                .isEqualTo("rat-1");
    }

    @Test
    void removingTheEntryRevokesTheGrantThenDeletesTheRegistrationAtTheService() throws Exception {
        // Harm: an entry removed here while its registration lives on at the service.
        VaultAuthorizeCommand.flowFor(context, "forge");
        VaultAuthorizeCommand.keep(context, "forge", "rt-1");

        assertThat(execute("vault", "remove", "forge")).as(err.toString()).isZero();

        assertThat(revocations)
                .as("the grant revoked as the registered client")
                .singleElement()
                .satisfies(asked -> assertThat(asked).contains("token=rt-1", "client_id=dyn-1"));
        assertThat(deletions)
                .as("the registration deleted with its own token")
                .containsExactly("/register/dyn-1 Bearer rat-1");
        assertThat(context.vault().read(PASSPHRASE))
                .as("the entry, its grant and its registration gone together")
                .isEmpty();
        assertThat(out.toString() + err.toString()).doesNotContain("rat-1").doesNotContain("rt-1");
    }

    @Test
    void aRegistrationTheServiceWillNotDeleteKeepsTheEntryUntilThePersonSaysToGoOn()
            throws Exception {
        // Harm: a registration deleted here that the service still holds, which nobody can then
        // delete there.
        VaultAuthorizeCommand.flowFor(context, "forge");
        deleteStatus = 500;

        assertThat(execute("vault", "remove", "forge")).isEqualTo(1);
        assertThat(err.toString()).contains("--without-revoking").doesNotContain("rat-1");
        assertThat(context.vault().read(PASSPHRASE))
                .as("kept, with what deletes it there")
                .containsKeys("forge", VaultNames.REGISTRATION_PREFIX + "forge");

        assertThat(execute("vault", "remove", "forge", "--without-revoking")).isZero();
        assertThat(out.toString()).contains("not deleted at the service");
        assertThat(context.vault().read(PASSPHRASE)).isEmpty();
    }
}
