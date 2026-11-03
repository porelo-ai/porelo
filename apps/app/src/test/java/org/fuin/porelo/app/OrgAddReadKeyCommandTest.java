package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
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

/**
 * {@code porelo org add-read-key}, against a forge on the loopback. Harm guarded: a host's key that
 * can write, or a token that ends up anywhere but the header sent to the forge.
 */
class OrgAddReadKeyCommandTest {

    private static final String TOKEN = "token-of-the-admin-3f9a";

    private static final String REVISION = "a".repeat(40);

    private static final String KEY = "ssh-ed25519 " + base64("ssh-ed25519");

    private static final String OLD_TITLE = "porelo host read key";

    @TempDir private Path dir;

    private HttpServer server;

    private final List<String> posted = new ArrayList<>();

    private final List<String> tokens = new ArrayList<>();

    private String listed = "[]";

    private List<String> pages = List.of();

    private int listStatus = 200;

    private int postStatus = 201;

    /** A forge refuses a second key under one title on a repository: Forgejo answers 422. */
    private boolean oldKeyStands;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /** As {@code ssh-keygen -l} prints it, worked out here and not by the code it checks. */
    private static String fingerprint() {
        try {
            final byte[] blob = Base64.getDecoder().decode(base64("ssh-ed25519"));
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(blob));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64(final String type) {
        final byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        final byte[] bytes = new byte[4 + name.length + 8];
        bytes[3] = (byte) name.length;
        System.arraycopy(name, 0, bytes, 4, name.length);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void forge() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/acme/org/keys", this::keys);
        server.createContext(
                "/repos/acme/org/commits",
                exchange -> send(exchange, 200, "[{\"sha\":\"" + REVISION + "\"}]"));
        server.start();
        Files.writeString(
                dir.resolve("settings.yml"),
                "adapter: git\nsettings:\n  repository: git@forge.example:acme/org.git\n");
        Files.writeString(dir.resolve("key.pub"), KEY + " root@machine\n");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void keys(final HttpExchange exchange) throws IOException {
        tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        if ("POST".equals(exchange.getRequestMethod())) {
            final String body =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            posted.add(body);
            final boolean titleTaken =
                    body.contains("\"title\":\"" + OLD_TITLE + "\"") && oldKeyStands;
            send(exchange, titleTaken ? 422 : postStatus, "{}");
        } else {
            final String query = String.valueOf(exchange.getRequestURI().getQuery());
            final int asked = Integer.parseInt(query.replaceAll(".*[?&]?page=([0-9]+).*", "$1"));
            final String page =
                    asked <= pages.size() ? pages.get(asked - 1) : asked == 1 ? listed : "[]";
            send(exchange, listStatus, page);
        }
    }

    private static void send(final HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private int run(final String input) {
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
                line.getSubcommands().get("org").getSubcommands().get("add-read-key");
        final URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ((OrgAddReadKeyCommand) command.getCommand()).forge =
                (address, token, client) -> new ForgeReadKey(api, address, token, client);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line.execute(
                "org",
                "add-read-key",
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
    void theKeyIsAddedReadOnlyWithTheTokenInTheHeaderAlone() {
        assertThat(run(TOKEN + "\n")).as("what it said: %s", err).isZero();

        assertThat(answer().get("added")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(REVISION);
        assertThat(posted).hasSize(1);
        final Map<?, ?> body = (Map<?, ?>) Json.parse(posted.get(0));
        assertThat(body.get("read_only")).as("a host only ever reads").isEqualTo(true);
        assertThat(body.get("key")).as("the machine's comment stays here").isEqualTo(KEY);
        assertThat(tokens).allMatch(("token " + TOKEN)::equals);
        assertThat(out + err.toString()).doesNotContain(TOKEN);
        assertThat(posted.get(0)).doesNotContain(TOKEN);
    }

    @Test
    void askedAgainItAnswersNotAddedAndPostsNothing() {
        listed = "[{\"key\":\"" + KEY + " other\",\"read_only\":true}]";

        assertThat(run(TOKEN)).isZero();

        assertThat(answer().get("added")).isEqualTo(false);
        assertThat(posted).isEmpty();
    }

    @Test
    void theSameKeyWithWriteAccessIsRefusedRatherThanCalledDone() {
        listed = "[{\"key\":\"" + KEY + "\",\"read_only\":false}]";

        assertThat(run(TOKEN)).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(posted).isEmpty();
    }

    @Test
    void aForgeCappingItsPagesBelowWhatWasAskedIsReadToAnEmptyOne() {
        pages =
                List.of(
                        "[{\"key\":\"ssh-ed25519 AAAAother\",\"read_only\":true}]",
                        "[{\"key\":\"" + KEY + "\",\"read_only\":true}]",
                        "[]");

        assertThat(run(TOKEN)).isZero();

        assertThat(answer().get("added"))
                .as("a key on a later page is there already")
                .isEqualTo(false);
        assertThat(posted).isEmpty();
    }

    @Test
    void aClientFollowingRedirectsIsRefusedSinceTheTokenWouldGoWhereverTheyLead() {
        assertThatThrownBy(
                        () ->
                                new ForgeReadKey(
                                        URI.create("https://forge.example"),
                                        "git@forge.example:acme/org.git",
                                        TOKEN,
                                        HttpClient.newBuilder()
                                                .followRedirects(HttpClient.Redirect.NORMAL)
                                                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void anEmptyStandardInputIsUsageAndTheForgeIsNotAsked() {
        assertThat(run(" \n")).isEqualTo(64);

        assertThat(tokens).isEmpty();
    }

    @Test
    void aFileHoldingAnythingElseIsRefusedUnprintedAndUnsent() throws Exception {
        Files.writeString(
                dir.resolve("key.pub"),
                "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-bytes\n-----END-----\n");

        assertThat(run(TOKEN)).isEqualTo(64);

        assertThat(out + err.toString()).doesNotContain("private-bytes");
        assertThat(tokens).as("nothing went to the forge").isEmpty();
    }

    @Test
    void wordsShapedLikeAKeyAreNoKey() throws Exception {
        Files.writeString(dir.resolve("key.pub"), "ssh-ed25519 AAAAnotakey\n");

        assertThat(run(TOKEN)).isEqualTo(64);
    }

    @Test
    void aTokenTheForgeRefusesIsNotAllowed() {
        listStatus = 401;

        assertThat(run(TOKEN)).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(out + err.toString()).doesNotContain(TOKEN);
    }

    @Test
    void aForgeNotAnsweringIsCannotSay() {
        server.stop(0);

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
    }

    @Test
    void anAnswerBeyondItsBoundSaysNothingAndNoKeyIsAdded() {
        listed = "[" + " ".repeat(4 * 1024 * 1024) + "]";

        assertThat(run(TOKEN)).as("an endless list must not read as an empty one").isEqualTo(69);

        assertThat(posted).isEmpty();
    }

    @Test
    void aForgeThatWillNotTakeTheKeyIsCannotSay() {
        postStatus = 422;

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
    }

    @Test
    void anotherHostsKeyUnderTheOldTitleDoesNotStopThisOne() {
        oldKeyStands = true;
        listed =
                "[{\"key\":\"ssh-ed25519 AAAAother\",\"read_only\":true,\"title\":\""
                        + OLD_TITLE
                        + "\"}]";

        assertThat(run(TOKEN)).as(err.toString()).isZero();

        assertThat(posted)
                .singleElement()
                .asString()
                .as("each host's key is told apart by its own fingerprint")
                .contains("\"title\":\"" + OLD_TITLE + " " + fingerprint() + "\"");
    }

    @Test
    void aForgeRefusingTheKeySaysWhereItMayStandAndClaimsNoRuleOfOneRepository() {
        postStatus = 422;

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(err.toString())
                .contains("answering 422", "another repository", "a person's account")
                .doesNotContain("one repository only");
    }

    @Test
    void anotherAnswerFromTheForgeNamesOnlyItsStatus() {
        postStatus = 500;

        assertThat(run(TOKEN)).isEqualTo(69);

        assertThat(err.toString())
                .contains("answering 500")
                .doesNotContain("repository", "account");
    }
}
