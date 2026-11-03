package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery on a person's machine and a forge that answers only what the test gives it. */
class IdentityProposeCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private HttpServer server;
    private URI forge = URI.create("http://127.0.0.1:1");
    private final Map<String, String> replies = new HashMap<>();
    private final Map<String, String> config = new HashMap<>();
    private final List<Command> commands = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private String greeting = "Hi alice! You've successfully authenticated.";
    private int sshExit = 1;
    private boolean authorized;
    private boolean defaultForge;
    private IdentityOrganization.Opener opener =
            (machine, repository) -> {
                throw new AssertionError("no organization");
            };

    @BeforeEach
    void prepare() throws Exception {
        registry = new RegistryCheckout(dir);
    }

    private void startForge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final String path = exchange.getRequestURI().getPath();
                    requests.add(path);
                    if (path.equals("/api/v1/user")) {
                        authorized = exchange.getRequestHeaders().getFirst("Authorization") != null;
                    }
                    final String reply = replies.get(path);
                    final byte[] body =
                            (reply == null ? "unavailable" : reply)
                                    .getBytes(StandardCharsets.UTF_8);
                    try (exchange) {
                        exchange.sendResponseHeaders(reply == null ? 503 : 200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        forge = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        replies.put(
                "/api/v1/users/alice",
                "{\"full_name\":\"Forge Alice\",\"email\":\"forge@example.org\"}");
    }

    @AfterEach
    void close() throws Exception {
        if (server != null) server.stop(0);
        if (registry != null) registry.close();
    }

    private int propose(String... options) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            commands.add(command);
                            if (command.program().equals("git")
                                    && command.arguments().contains("--get")) {
                                final String value = config.get(command.arguments().getLast());
                                return new CommandResult(
                                        command,
                                        value == null ? 1 : 0,
                                        value == null ? "" : value,
                                        "");
                            }
                            if (command.program().equals("timeout")) {
                                return new CommandResult(command, sshExit, "", greeting);
                            }
                            return registry.runner.run(command);
                        },
                        registry.paths,
                        args -> 0,
                        registry.environment());
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        IdentityRun.wire(cli, opener, null);
        final List<String> args = new ArrayList<>(List.of("identity", "propose", "--json"));
        if (!defaultForge) args.addAll(List.of("--forge", forge.toString()));
        args.addAll(List.of(options));
        return cli.execute(args.toArray(String[]::new));
    }

    private Map<?, ?> field(String name) {
        return (Map<?, ?>) registry.answer().get(name);
    }

    private void signing(String... holders) {
        replies.put(
                "/api/v1/users/alice/ssh_signing_keys",
                org.fuin.porelo.wire.Json.write(
                        java.util.Arrays.stream(holders)
                                .map(holder -> Map.of("key", registry.key(holder)))
                                .toList()));
    }

    private void authentication(String... holders) {
        replies.put(
                "/alice.keys",
                String.join("\n", java.util.Arrays.stream(holders).map(registry::key).toList()));
    }

    @Test
    void the_organization_forge_is_the_default_without_reading_its_store() throws Exception {
        final TestOrganization organization = new TestOrganization(registry);
        organization.forgeAddress = forge;
        organization.outOfReach();
        opener = organization.opener();
        defaultForge = true;
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("forge")).isEqualTo(forge.toString());
        assertThat(registry.paths.xdg().state().resolve("organization")).doesNotExist();
    }

    @Test
    void no_organization_defaults_to_github_and_bad_options_are_misuse() {
        defaultForge = true;
        greeting = "";
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("forge")).isEqualTo("https://github.com");
        defaultForge = false;
        assertThat(propose("--unknown")).isEqualTo(64);
        forge = URI.create("http://forge.example.org");
        assertThat(propose()).isEqualTo(64);
    }

    @Test
    void token_login_precedes_ssh_and_the_token_stays_in_http() throws Exception {
        startForge();
        Files.createDirectories(registry.paths.xdg().config());
        Files.writeString(
                registry.paths.xdg().config().resolve("credentials.yml"),
                "credentials:\n  - match: "
                        + forge
                        + "/\n    kind: token\n    source: env\n    env: PROPOSAL_TOKEN\n");
        final String secret = "proposal-test-credential";
        registry.variables.put("PROPOSAL_TOKEN", secret);
        replies.put("/api/v1/user", "{\"login\":\"alice\"}");
        greeting = "Hi bob!";
        assertThat(propose()).as(registry.err.toString()).isZero();
        assertThat(field("login")).isEqualTo(Map.of("value", "alice", "source", "token"));
        assertThat(authorized).isTrue();
        assertThat(commands.stream().noneMatch(command -> command.program().equals("timeout")))
                .isTrue();
        assertThat(
                        commands.stream()
                                .flatMap(command -> command.arguments().stream())
                                .noneMatch(arg -> arg.contains(secret)))
                .isTrue();
        assertThat((registry.out.toString() + registry.err).contains(secret)).isFalse();
    }

    @Test
    void ssh_greetings_and_exit_one_find_the_login() {
        for (String text : List.of("Hi alice!", "Hi there, alice!")) {
            greeting = text;
            assertThat(propose()).isZero();
            assertThat(field("login")).isEqualTo(Map.of("value", "alice", "source", "ssh"));
        }
        assertThat(commands.stream().filter(command -> command.program().equals("timeout")))
                .allSatisfy(
                        command ->
                                assertThat(command.arguments())
                                        .contains("BatchMode=yes", "ConnectTimeout=5", "12s"));
    }

    @Test
    void git_fields_precede_profile_and_a_noreply_email_is_only_a_fallback() throws Exception {
        startForge();
        config.put("user.name", "Git Alice");
        config.put("user.email", "git@example.org");
        assertThat(propose()).isZero();
        assertThat(field("name")).isEqualTo(Map.of("value", "Git Alice", "source", "git-config"));
        assertThat(field("email"))
                .isEqualTo(Map.of("value", "git@example.org", "source", "git-config"));
        assertThat(requests).doesNotContain("/api/v1/users/alice");
        config.clear();
        assertThat(propose()).isZero();
        assertThat(field("name"))
                .isEqualTo(Map.of("value", "Forge Alice", "source", "forge-profile"));
        assertThat(field("email"))
                .isEqualTo(Map.of("value", "forge@example.org", "source", "forge-profile"));
        config.put("user.email", "alice@users.noreply.github.com");
        assertThat(propose()).isZero();
        assertThat(field("email").get("value")).isEqualTo("forge@example.org");
        replies.put("/api/v1/users/alice", "{\"full_name\":null,\"email\":\"\"}");
        assertThat(propose()).isZero();
        assertThat(field("email").get("source")).isEqualTo("git-config");
        assertThat(registry.answer().get("missing")).isEqualTo(List.of("name", "key"));
    }

    @Test
    void git_signing_key_precedes_signing_and_authentication_lists() throws Exception {
        startForge();
        registry.agent("alice", "bob", "hank");
        config.put("user.signingkey", registry.keyFiles.get("hank") + ".pub");
        signing("alice");
        authentication("bob");
        assertThat(propose()).isZero();
        assertThat(field("key").get("fingerprint")).isEqualTo(registry.pin("hank"));
        assertThat(field("key").get("source")).isEqualTo("git-config");
        for (String given : List.of(registry.key("hank"), "key::" + registry.key("hank"))) {
            config.put("user.signingkey", given);
            assertThat(propose()).isZero();
            assertThat(field("key").get("fingerprint")).isEqualTo(registry.pin("hank"));
        }
        config.clear();
        assertThat(propose()).isZero();
        assertThat(field("key").get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(field("key").get("source")).isEqualTo("forge-signing");
        signing();
        assertThat(propose()).isZero();
        assertThat(field("key").get("fingerprint")).isEqualTo(registry.pin("bob"));
        assertThat(field("key").get("source")).isEqualTo("forge-authentication");
    }

    @Test
    void a_signing_only_proposal_creates_a_card_the_store_vouches_for() throws Exception {
        startForge();
        registry.agent("alice", "bob");
        signing("alice");
        authentication("bob");
        assertThat(propose()).isZero();
        final String fingerprint = (String) field("key").get("fingerprint");
        assertThat(field("key").get("source")).isEqualTo("forge-signing");
        assertThat(registry.answer().get("ready")).isEqualTo(true);
        final String login = (String) field("login").get("value");
        final String name = (String) field("name").get("value");
        final TestOrganization organization = new TestOrganization(registry);
        final var directory =
                new org.fuin.porelo.organization.gittree.HttpForgeDirectory(
                        forge, null, OrganizationAccess.http(registry.context()));
        organization.identities =
                new org.fuin.porelo.organization.IdentitySource() {
                    @Override
                    public java.util.Optional<Keys> keys(String who) {
                        if (!who.equals("alice")) return registry.forge.keys(who);
                        final List<String> merged =
                                new ArrayList<>(directory.authenticationKeys(who).orElseThrow());
                        merged.addAll(directory.signingKeys(who).orElseThrow());
                        return java.util.Optional.of(new Keys(merged, true));
                    }

                    @Override
                    public java.util.Optional<Boolean> owns(String owner, String who) {
                        return registry.forge.owns(owner, who);
                    }
                };
        organization.authenticateWith("bob");
        assertThat(
                        IdentityRun.identity(
                                organization,
                                "create",
                                "--login",
                                login,
                                "--name",
                                name,
                                "--key",
                                fingerprint,
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        final var reading =
                IdentityOrganization.required(registry.context(), organization.opener());
        assertThat(reading.answers().getPersons())
                .anySatisfy(
                        person -> {
                            assertThat(person.login()).isEqualTo(login);
                            assertThat(person.state())
                                    .isEqualTo(org.fuin.porelo.organization.Organization.TRUSTED);
                        });
        assertThat(
                        org.fuin.porelo.vault.PersonCard.read(organization.card(login), "filed")
                                .fingerprint())
                .isEqualTo(fingerprint);
    }

    @Test
    void a_configured_key_outside_the_agent_names_how_to_load_it() throws Exception {
        config.put("user.signingkey", registry.keyFiles.get("alice").toString());
        assertThat(propose()).isZero();
        assertThat(field("key").get("in_agent")).isEqualTo(false);
        assertThat(registry.answer().get("ready")).isEqualTo(false);
        assertThat(registry.answer().get("ssh_add"))
                .isEqualTo("ssh-add '" + registry.keyFiles.get("alice") + "'");
        config.put("user.signingkey", registry.keyFiles.get("alice") + ".pub");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("ssh_add"))
                .isEqualTo("ssh-add '" + registry.keyFiles.get("alice") + "'");
        config.put("user.signingkey", "key::" + registry.key("alice"));
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("ssh_add")).isEqualTo("ssh-add");
        assertThat(new IdentityStore(registry.context()).cardFile()).doesNotExist();
    }

    @Test
    void a_signing_key_file_that_is_a_link_is_not_followed() throws Exception {
        final java.nio.file.Path link = registry.keyFiles.get("alice").resolveSibling("linked.pub");
        java.nio.file.Files.createSymbolicLink(
                link, java.nio.file.Path.of(registry.keyFiles.get("alice") + ".pub"));
        config.put("user.signingkey", link.toString());

        assertThat(propose()).isZero();

        assertThat(registry.answer().get("key")).isNull();
        assertThat(String.valueOf(registry.answer().get("notes"))).contains("a link");
    }

    @Test
    void two_signing_keys_are_ambiguous_without_falling_back() throws Exception {
        startForge();
        registry.agent("alice", "bob", "hank");
        signing("alice", "bob");
        authentication("hank");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("key")).isNull();
        assertThat(registry.answer().get("ambiguous"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "field",
                                        "key",
                                        "values",
                                        List.of(registry.pin("alice"), registry.pin("bob")))));
        assertThat(registry.answer().get("ready")).isEqualTo(false);
        signing();
        authentication("alice", "bob");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("key")).isNull();
        assertThat((List<?>) registry.answer().get("ambiguous")).hasSize(1);
    }

    @Test
    void invalid_login_and_key_are_explained_and_never_proposed() {
        greeting = "Hi Alice!";
        config.put("user.signingkey", "key::ssh-rsa AAAA");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("login")).isNull();
        assertThat(registry.answer().get("key")).isNull();
        assertThat((List<?>) registry.answer().get("notes")).hasSize(2);
        config.put("user.signingkey", "key::ssh-ed25519");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("key")).isNull();
        assertThat((List<?>) registry.answer().get("notes")).hasSize(2);
        config.put("user.signingkey", "key::ssh-ed25519 AAAA");
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("key")).isNull();
        assertThat((List<?>) registry.answer().get("notes")).hasSize(2);
    }

    @Test
    void an_unreachable_forge_and_no_ssh_answer_are_missing_with_exit_zero() {
        greeting = "";
        sshExit = 255;
        assertThat(propose()).isZero();
        assertThat(registry.answer().get("missing")).isEqualTo(List.of("login", "name", "key"));
        assertThat(registry.answer().get("email")).isNull();
        assertThat(registry.answer().get("ready")).isEqualTo(false);
    }
}
