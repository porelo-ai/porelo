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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.gittree.ForgeReadKey;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards giving a host up: its keys removed at the central forge and only then its identity
 * deleted; a key left there named with where it is removed; the token sent nowhere else.
 */
class HostGiveUpCommandTest {

    private static final String TOKEN = "invented-central-token-7c21";
    private static final String HOST = "build";
    private static final String ORG_KEY = key("org");
    private static final String APP_KEY = key("app");
    private static final String LIB_KEY = key("lib");
    private static final String TITLE = "porelo host build project shop";

    @TempDir private Path dir;
    private HttpServer server;
    private final byte[] share = new byte[32];
    private boolean open = true;
    private PoreloContext context;

    /** What each repository lists, by its path at the forge's API. */
    private final Map<String, List<Map<String, Object>>> listed = new HashMap<>();

    private final Map<String, Integer> listStatus = new HashMap<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private @Nullable Runnable onDelete;
    private boolean samePageAlways;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private static String key(final String seed) {
        final byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        final byte[] bytes = new byte[4 + name.length + 4 + 32];
        bytes[3] = (byte) name.length;
        System.arraycopy(name, 0, bytes, 4, name.length);
        bytes[4 + name.length + 3] = 32;
        final byte[] tail = seed.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tail, 0, bytes, bytes.length - tail.length, tail.length);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes);
    }

    private static Map<String, Object> row(
            final long id, final String key, final String title, final boolean readOnly) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("key", key + " a comment");
        row.put("title", title);
        row.put("read_only", readOnly);
        return row;
    }

    @BeforeEach
    void machine() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/", this::forge);
        server.start();
        context =
                new PoreloContext(
                                new ProcessCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .withHostVault(
                                new HostVaultAccess() {
                                    public void keep(final byte[] value) {
                                        open = true;
                                    }

                                    public boolean isOpen() {
                                        return open;
                                    }

                                    public byte[] share() {
                                        return share.clone();
                                    }

                                    public boolean forget() {
                                        open = false;
                                        return true;
                                    }
                                });
        final Path config = context.paths().xdg().config();
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("organization.yml"),
                "adapter: git\nsettings:\n  repository: https://forge.example.org/acme/organization.git\n");
        Files.writeString(
                context.paths().vault().credentialRegistry(),
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: vault\n    vault: central\n");
        keep(identity("https://forge.example.org/acme/app.git"), true);
        listed.put(
                "/repos/acme/organization/keys",
                List.of(row(7, ORG_KEY, "porelo host read key " + fingerprint(ORG_KEY), true)));
        listed.put("/repos/acme/app/keys", List.of(row(8, APP_KEY, TITLE, true)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String fingerprint(final String key) {
        return OrgAddReadKeyCommand.fingerprint(key);
    }

    private static String identity(final String address) {
        final Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("host", HOST);
        identity.put("organization", Map.of("public", ORG_KEY, "private", "the private half"));
        identity.put(
                "repositories",
                List.of(
                        Map.of(
                                "project",
                                "shop",
                                "repository",
                                "app",
                                "address",
                                address,
                                "key_id",
                                8,
                                "title",
                                TITLE,
                                "public",
                                APP_KEY,
                                "private",
                                "the private half")));
        identity.put("ssh_host_keys", List.of());
        return Json.write(identity);
    }

    private void keep(final String identity, final boolean withToken) {
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        if (withToken) {
            entries.put("central", new VaultEntry(TOKEN, "token"));
        }
        entries.put(
                HostGiveUpCommand.ENTRY_PREFIX + HOST, new VaultEntry(identity, "host-identity"));
        if (Files.exists(context.vault().path())) {
            try {
                Files.delete(context.vault().path());
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
        }
        context.vault().write(entries, share);
    }

    private void forge(final HttpExchange exchange) throws IOException {
        tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        final String path = exchange.getRequestURI().getPath();
        asked.add(path);
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deleted.add(path);
            if (onDelete != null) {
                onDelete.run();
            }
            send(exchange, 204, "");
            return;
        }
        final Integer status = listStatus.get(path);
        if (status != null) {
            send(exchange, status, "{\"message\":\"" + TOKEN + "\"}");
            return;
        }
        final String query = exchange.getRequestURI().getQuery();
        final int page = Integer.parseInt(query.replaceAll(".*[?&]?page=([0-9]+).*", "$1"));
        final List<Map<String, Object>> rows = listed.getOrDefault(path, List.of());
        send(exchange, 200, page == 1 || samePageAlways ? Json.write(rows) : "[]");
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

    private int run(final String input, final String... options) {
        final PoreloContext machine =
                context.reading(
                        new StandardInput(
                                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                                false,
                                false));
        final CommandLine line = PoreloCli.commandLine(machine);
        final CommandLine command =
                line.getSubcommands().get("host").getSubcommands().get("give-up");
        assertThat(command).as("host registers give-up").isNotNull();
        final URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ((HostGiveUpCommand) command.getCommand()).forge =
                (forge, owner, name, token, client) ->
                        ForgeReadKey.of(api, owner, name, token, client);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        final List<String> args = new ArrayList<>(List.of("host", "give-up", HOST));
        args.addAll(List.of(options));
        return line.execute(args.toArray(String[]::new));
    }

    private boolean kept() {
        return context.vault().read(share).containsKey(HostGiveUpCommand.ENTRY_PREFIX + HOST);
    }

    @Test
    void everyKeyRemovedDeletesTheIdentityAndAnswersWhatWasRemoved() throws Exception {
        assertThat(run("", "--json")).as(err.toString()).isZero();

        assertThat(deleted)
                .containsExactlyInAnyOrder(
                        "/repos/acme/organization/keys/7", "/repos/acme/app/keys/8");
        assertThat(kept()).as("the identity is deleted once every key is gone").isFalse();
        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString().strip());
        assertThat((List<?>) answer.get("kept")).isEmpty();
        assertThat((List<?>) answer.get("removed")).hasSize(2);
        assertThat(out.toString()).doesNotContain("private").doesNotContain(TOKEN);
        assertThat(tokens).containsOnly("token " + TOKEN);
    }

    @Test
    void aKeyNoLongerListedCountsAsGone() {
        listed.put("/repos/acme/app/keys", List.of());

        assertThat(run("", "--json")).as(err.toString()).isZero();

        assertThat(deleted).containsExactly("/repos/acme/organization/keys/7");
        assertThat(kept()).isFalse();
    }

    @Test
    void aRepositoryTheForgeDoesNotShowIsKeptWithItsLinkAndTheIdentityStays() {
        listStatus.put("/repos/acme/app/keys", 404);

        assertThat(run("", "--json")).isEqualTo(65);

        assertThat(kept()).as("a 404 is never taken as gone").isTrue();
        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString().strip());
        assertThat((List<?>) answer.get("kept"))
                .singleElement()
                .satisfies(
                        row -> {
                            final Map<?, ?> kept = (Map<?, ?>) row;
                            assertThat(kept.get("project")).isEqualTo("shop");
                            assertThat(kept.get("repository")).isEqualTo("app");
                            assertThat(kept.get("title")).isEqualTo(TITLE);
                            assertThat(kept.get("link"))
                                    .isEqualTo("https://forge.example.org/acme/app/settings/keys");
                            assertThat((String) kept.get("why")).contains("404");
                        });
        assertThat(out + err.toString())
                .as("a forge's words never reach the person")
                .doesNotContain(TOKEN);
    }

    @Test
    void aMatchingKeyUnderAnotherTitleOrWithWriteAccessIsLeftAsItIs() {
        listed.put("/repos/acme/app/keys", List.of(row(8, APP_KEY, "someone's own key", true)));
        assertThat(run("")).isEqualTo(65);
        assertThat(deleted).doesNotContain("/repos/acme/app/keys/8");
        assertThat(kept()).isTrue();
        assertThat(out.toString()).contains("another title or with write access");

        out.getBuffer().setLength(0);
        listed.put("/repos/acme/app/keys", List.of(row(8, APP_KEY, TITLE, false)));
        assertThat(run("")).isEqualTo(65);
        assertThat(deleted).doesNotContain("/repos/acme/app/keys/8");
        assertThat(kept()).isTrue();
        assertThat(out.toString()).contains("another title or with write access");
    }

    @Test
    void aForgeRepeatingItsPageIsNotReadAsTheEndOfTheList() {
        listed.put("/repos/acme/app/keys", List.of(row(9, LIB_KEY, "another key", true)));
        samePageAlways = true;

        assertThat(run("")).isEqualTo(65);

        assertThat(kept()).isTrue();
        assertThat(out.toString()).contains("the same keys on two pages");
    }

    @Test
    void anAddressOnAnotherServerIsKeptWithoutARequestWhileTheOthersAreGivenUp() {
        keep(identity("https://elsewhere.example.net/acme/app.git"), true);
        listed.put(
                "/repos/acme/organization/keys",
                List.of(row(3, ORG_KEY, "porelo host read key " + fingerprint(ORG_KEY), true)));

        assertThat(run("", "--json")).isEqualTo(65);

        assertThat(asked)
                .as("the token goes to the central forge, and for its own repositories alone")
                .noneMatch(path -> path.contains("/acme/app/"));
        assertThat(deleted).containsExactly("/repos/acme/organization/keys/3");
        assertThat(kept()).isTrue();
        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat((List<?>) answer.get("kept"))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(((Map<?, ?>) row).get("why"))
                                    .isEqualTo("its address is not on the central forge");
                            assertThat(((Map<?, ?>) row).get("link")).isNull();
                        });
    }

    @Test
    void anAddressTheForgeCannotNameIsKeptAndNamedWhileTheOthersAreGivenUp() {
        keep(identity("https://forge.example.org/acme/app.git.git"), true);
        listed.put(
                "/repos/acme/organization/keys",
                List.of(row(3, ORG_KEY, "porelo host read key " + fingerprint(ORG_KEY), true)));

        assertThat(run("")).isEqualTo(65);

        assertThat(deleted).containsExactly("/repos/acme/organization/keys/3");
        assertThat(kept()).isTrue();
        assertThat(out.toString()).contains("removed   ").contains("kept      ");
        assertThat(err.toString()).doesNotContain("organization.yml");
    }

    @Test
    void anIdentityThatGainsAKeyWhileItIsGivenUpIsKept() {
        listed.put(
                "/repos/acme/organization/keys",
                List.of(row(3, ORG_KEY, "porelo host read key " + fingerprint(ORG_KEY), true)));
        listed.put("/repos/acme/app/keys", List.of(row(8, APP_KEY, TITLE, true)));
        final String grown =
                identity("https://forge.example.org/acme/app.git")
                        .replace("\"ssh_host_keys\":[]", "\"ssh_host_keys\":[],\"grown\":true");
        assertThat(grown).as("the stand-in for a gained key changes the value").contains("grown");
        onDelete = () -> keep(grown, true);

        assertThat(run("")).isEqualTo(65);

        assertThat(kept()).as("a key added meanwhile is never forgotten").isTrue();
        assertThat(context.vault().read(share).get(HostGiveUpCommand.ENTRY_PREFIX + HOST).value())
                .isEqualTo(grown);
        assertThat(err.toString()).contains("changed while it was given up");
    }

    @Test
    void withoutATokenInTheVaultOneOnStandardInputIsTaken() {
        keep(identity("https://forge.example.org/acme/app.git"), false);

        assertThat(run(TOKEN + "\n")).as(err.toString()).isZero();

        assertThat(tokens).containsOnly("token " + TOKEN);
        assertThat(kept()).isFalse();
    }

    @Test
    void withoutAnyTokenNothingIsAskedOrDeleted() {
        keep(identity("https://forge.example.org/acme/app.git"), false);

        assertThat(run("")).isEqualTo(69);

        assertThat(tokens).isEmpty();
        assertThat(kept()).isTrue();
    }

    @Test
    void aHostWithoutAnIdentityOrAShutVaultIsRefused() {
        assertThat(run("", "--json")).isZero();
        assertThat(run("")).as("given up already").isEqualTo(66);
        assertThat(err.toString()).contains("no identity of build");

        tokens.clear();
        open = false;
        assertThat(run("")).isEqualTo(77);
        assertThat(err.toString()).contains("porelo vault unlock");
        assertThat(tokens).as("nothing is asked while the vault is shut").isEmpty();
    }

    @Test
    void anIdentityOfAnotherHostIsNotGivenUp() {
        keep(
                identity("https://forge.example.org/acme/app.git")
                        .replace("\"host\":\"build\"", "\"host\":\"other\""),
                true);

        assertThat(run("")).isEqualTo(65);

        assertThat(tokens).isEmpty();
        assertThat(kept()).isTrue();
    }
}
