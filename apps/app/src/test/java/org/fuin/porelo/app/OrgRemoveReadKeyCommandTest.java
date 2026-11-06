package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.gittree.ForgeReadKey;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Guards against deleting another host's key and leaking a token through a forge refusal. */
class OrgRemoveReadKeyCommandTest {

    private static final String TOKEN = "invented-admin-token-3f9a";
    private static final String REVISION = "b".repeat(40);
    private static final String KEY = "ssh-ed25519 " + base64();

    @TempDir private Path dir;
    private HttpServer server;
    private final List<String> deleted = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private List<String> pages = List.of();
    private int listStatus = 200;
    private int deleteStatus = 204;
    private String refusal = "{}";
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private static String base64() {
        final byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        final byte[] bytes = new byte[4 + name.length + 8];
        bytes[3] = (byte) name.length;
        System.arraycopy(name, 0, bytes, 4, name.length);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void forge() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example-team/organization/keys", this::keys);
        server.createContext(
                "/repos/example-team/organization/commits",
                exchange -> send(exchange, 200, "[{\"sha\":\"" + REVISION + "\"}]"));
        server.start();
        Files.writeString(
                dir.resolve("settings.yml"),
                "adapter: git\nsettings:\n  repository: git@forge.example.org:example-team/organization.git\n");
        Files.writeString(dir.resolve("key.pub"), KEY + " root@example-host\n");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void keys(final HttpExchange exchange) throws IOException {
        tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deleted.add(exchange.getRequestURI().getPath());
            send(exchange, deleteStatus, refusal);
        } else {
            final String query = exchange.getRequestURI().getQuery();
            final int page = Integer.parseInt(query.replaceAll(".*[?&]?page=([0-9]+).*", "$1"));
            send(
                    exchange,
                    listStatus,
                    listStatus == 200
                            ? page <= pages.size() ? pages.get(page - 1) : "[]"
                            : refusal);
        }
    }

    private static void send(final HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private int run(final String input) throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                                new ProcessCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0)
                        .reading(
                                new StandardInput(
                                        new ByteArrayInputStream(
                                                input.getBytes(StandardCharsets.UTF_8)),
                                        false));
        final CommandLine line = PoreloCli.commandLine(context);
        final CommandLine command =
                line.getSubcommands().get("org").getSubcommands().get("remove-read-key");
        assertThat(command).as("org registers remove-read-key").isNotNull();
        final URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ((OrgRemoveReadKeyCommand) command.getCommand()).forge =
                (address, token, client) -> new ForgeReadKey(api, address, token, client);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line.execute(
                "org",
                "remove-read-key",
                "--json",
                "--settings",
                dir.resolve("settings.yml").toString(),
                "--key",
                dir.resolve("key.pub").toString());
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    @Test
    void removesOnlyTheMatchingKeyIgnoringItsCommentEvenOnALaterPage() throws Exception {
        pages =
                List.of(
                        "[{\"id\":11,\"key\":\"ssh-ed25519 AAAAother\",\"read_only\":true}]",
                        "[{\"id\":33,\"key\":\"ssh-rsa "
                                + base64()
                                + "\",\"read_only\":true},"
                                + "{\"id\":22,\"key\":\""
                                + KEY
                                + " another-comment\",\"read_only\":true}]");

        assertThat(run(TOKEN + "\n")).as("what it said: %s", err).isZero();

        assertThat(answer()).isEqualTo(Map.of("revision", REVISION, "removed", true));
        assertThat(deleted).containsExactly("/repos/example-team/organization/keys/22");
        assertThat(tokens).isNotEmpty().allMatch(("token " + TOKEN)::equals);
        assertThat(out + err.toString()).doesNotContain(TOKEN);
    }

    @Test
    void aKeyNotThereAnswersFalseAndDeletesNothing() throws Exception {
        pages = List.of("[{\"id\":11,\"key\":\"ssh-ed25519 AAAAother\",\"read_only\":true}]");

        assertThat(run(TOKEN)).as("what it said: %s", err).isZero();

        assertThat(answer().get("removed")).isEqualTo(false);
        assertThat(deleted).isEmpty();
    }

    @Test
    void aForgeRefusingDeletionNamesItsStatusWithoutEchoingTheToken() throws Exception {
        pages = List.of("[{\"id\":22,\"key\":\"" + KEY + "\",\"read_only\":true}]");
        deleteStatus = 500;
        refusal = "{\"message\":\"cannot delete " + TOKEN + "\"}";

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(err.toString()).contains("did not remove the key", "answering 500");
        assertThat(out + err.toString()).doesNotContain(TOKEN);
    }

    @Test
    void aForgeRefusingTheTokenDeletesNothingAndNamesTheStatus() throws Exception {
        listStatus = 403;
        refusal = "{\"message\":\"refused " + TOKEN + "\"}";

        assertThat(run(TOKEN)).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(err.toString()).contains("answering 403");
        assertThat(out + err.toString()).doesNotContain(TOKEN);
        assertThat(deleted).isEmpty();
    }

    @Test
    void anEmptyTokenIsRefusedBeforeAnyRequest() throws Exception {
        assertThat(run(" \n")).isEqualTo(64);
        assertThat(tokens).isEmpty();
        assertThat(deleted).isEmpty();
    }

    @Test
    void anOversizedTokenIsRefusedBeforeAnyRequest() throws Exception {
        assertThat(run("x".repeat(8 * 1024 + 1))).isEqualTo(64);
        assertThat(tokens).isEmpty();
        assertThat(deleted).isEmpty();
    }

    @Test
    void anOversizedKeyListIsRefusedRatherThanCalledAbsent() throws Exception {
        pages = List.of("[" + " ".repeat(4 * 1024 * 1024) + "]");

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(deleted).isEmpty();
    }

    @Test
    void aMatchingKeyWithoutAnIntegerIdDeletesNothing() throws Exception {
        pages = List.of("[{\"id\":22.5,\"key\":\"" + KEY + "\",\"read_only\":true}]");

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(deleted).isEmpty();
    }

    @Test
    void anIncompleteKeyListIsRefusedRatherThanCalledAbsent() throws Exception {
        pages = List.of("[{\"id\":22}]");

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(deleted).isEmpty();
    }

    @Test
    void aPrivateKeyFileIsRefusedUnprintedAndUnsent() throws Exception {
        Files.writeString(
                dir.resolve("key.pub"),
                "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-bytes\n-----END-----\n");

        assertThat(run(TOKEN)).isEqualTo(64);

        assertThat(out + err.toString()).doesNotContain("private-bytes");
        assertThat(tokens).isEmpty();
        assertThat(deleted).isEmpty();
    }
}
