package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards which projects a person is told they may join. Harm: a join offered from an access file
 * nobody the registry trusts signed, to a person it does not admit, or a project silently left out
 * when its access repository cannot be checked, so that nobody is asked to fix it.
 */
class RegistryJoinableCommandTest {

    private static final String DEMO = "ssh://git@example.org/demo.git";

    private static final String SOURCE = "git@example.org:acme/demo.git";

    @TempDir Path dir;

    private RegistryCheckout registry;

    private Path access;

    /** What the access file says before its persons: the project's repository by default. */
    private String preamble = "source: " + SOURCE + "\n";

    /** Where each access address the registry names is fetched from instead of the network. */
    private final Map<String, String> served = new HashMap<>();

    /** Every argument a command of the check was handed, before it was served from disk. */
    private final List<String> handed = new ArrayList<>();

    /** The environment of every command that reaches the access address, as its process gets it. */
    private final List<Map<String, String>> environments = new ArrayList<>();

    /** What stands in for a part of an argument, as a local server stands in for a forge. */
    private final Map<String, String> rewritten = new HashMap<>();

    /** What the account's environment says, beyond the agent the test started. */
    private final Map<String, String> given = new HashMap<>();

    /** What the caller's environment gives a command that inherits it. */
    private Map<String, String> caller =
            Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
        access = Files.createDirectory(dir.resolve("demo-access"));
        git(access, "init", "-q", "-b", "main");
        served.put(DEMO, access.toString());
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
        System.clearProperty(PoreloBinary.NAMED);
    }

    /** A git that reaches each served address on disk, and no configuration of this machine. */
    private PoreloContext context() {
        final CommandRunner local =
                command -> {
                    final List<String> arguments = new ArrayList<>();
                    for (String argument : command.arguments()) {
                        handed.add(argument);
                        String standIn = served.getOrDefault(argument, argument);
                        for (Map.Entry<String, String> part : rewritten.entrySet()) {
                            standIn = standIn.replace(part.getKey(), part.getValue());
                        }
                        arguments.add(standIn);
                    }
                    final Map<String, String> environment =
                            new HashMap<>(command.inheritsEnvironment() ? caller : Map.of());
                    environment.putAll(command.environment());
                    if (command.arguments().contains(DEMO)
                            || command.arguments().contains(PRIVATE)) {
                        environments.add(Map.copyOf(environment));
                    }
                    return new ProcessCommandRunner()
                            .run(
                                    new Command(
                                            arguments,
                                            command.workingDirectory(),
                                            environment,
                                            command.input(),
                                            command.inheritsEnvironment(),
                                            command.binaryInput()));
                };
        return new PoreloContext(
                local,
                registry.paths,
                args -> 0,
                name ->
                        given.containsKey(name)
                                ? given.get(name)
                                : registry.environment().apply(name));
    }

    private String git(Path repository, String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repository.toString()));
        all.addAll(List.of(args));
        return registry.runner
                .runOrFail(
                        Command.of(all)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    /** The registry: michael owns it, hank administers walk6m, alice and bob filed cards. */
    private void registered(String accessFile) throws IOException {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.forge.keys.put("bob", List.of(registry.key("bob")));
        registry.write("hosts/walk6m/card", registry.hostCard("walk6m", "hank"));
        registry.write("hosts/walk6m/access.yml", accessFile);
        registry.commit("hank");
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
    }

    /** One person of an access file, as {@code login: {fingerprint, role, hosts[, locked]}}. */
    private String person(String login, String role, String host, boolean locked) {
        return "  "
                + login
                + ":\n    fingerprint: \""
                + registry.pin(login)
                + "\"\n    role: "
                + role
                + "\n    hosts: ["
                + host
                + "]\n"
                + (locked ? "    locked: true\n" : "");
    }

    /** Commits an access file and the cards of these logins, signed by the signer's key file. */
    private String accessCommit(String signer, String persons, String... cards) throws IOException {
        Files.writeString(access.resolve("access.yml"), preamble + "persons:\n" + persons);
        Files.createDirectories(access.resolve("cards"));
        for (String login : cards) {
            Files.writeString(
                    access.resolve("cards/" + login + ".card"), registry.card(login, login));
        }
        git(access, "add", "-A");
        git(
                access,
                "-c",
                "user.name=Access",
                "-c",
                "user.email=access@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + registry.keyFiles.get(signer),
                "commit",
                "-q",
                "-S",
                "-m",
                "access");
        return git(access, "rev-parse", "HEAD");
    }

    private int joinable(String login) {
        return registry.registry(
                context(), "joinable", registry.repo.toString(), "--login", login, "--json");
    }

    private List<?> list(String field) {
        return (List<?>) registry.answer().get(field);
    }

    private static List<Object> keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    @Test
    void a_project_whose_verified_access_file_admits_the_login_is_answered_with_its_host_card()
            throws Exception {
        registered(registry.access("demo"));
        preamble +=
                "transports:\n  matrix: {homeserver: \"https://walk6m.example.org:8448\","
                        + " room: \"#demo:walk6m.example.org\"}\n";
        final String revision =
                accessCommit(
                        "alice",
                        person("alice", "admin", "walk6m", false)
                                + person("bob", "developer", "walk6m", false),
                        "alice",
                        "bob");

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(keys(registry.answer())).containsExactly("projects", "refused");
        assertThat(list("refused")).isEmpty();
        assertThat(list("projects")).hasSize(1);
        final Map<?, ?> project = (Map<?, ?>) list("projects").getFirst();
        assertThat(keys(project))
                .as("a program reads the answer by these fields")
                .containsExactly(
                        "project",
                        "host",
                        "host_account",
                        "host_card",
                        "homeserver",
                        "source",
                        "access_repository",
                        "revision",
                        "signers",
                        "admins");
        assertThat(project.get("project")).isEqualTo("demo");
        assertThat(project.get("host")).isEqualTo("walk6m");
        assertThat(project.get("host_account")).isEqualTo("bob");
        assertThat(project.get("source"))
                .as("the project's repository the verified access file names")
                .isEqualTo(SOURCE);
        assertThat(project.get("homeserver"))
                .as("the verified access file's Matrix homeserver")
                .isEqualTo("https://walk6m.example.org:8448");
        assertThat(keys(project.get("host_card")))
                .as("exactly the accepted card")
                .doesNotContain("homeserver");
        assertThat(project.get("access_repository")).isEqualTo(DEMO);
        assertThat(project.get("revision")).as("the access commit verified").isEqualTo(revision);
        assertThat(project.get("signers"))
                .as("the key the registry holds for the project's admin")
                .isEqualTo(List.of(registry.key("alice")));
        final Map<?, ?> card = (Map<?, ?>) project.get("host_card");
        assertThat(card.get("address"))
                .as("the tunnel is opened from the accepted card")
                .isEqualTo("walk6m.example.org");
        assertThat(((Number) card.get("ssh_port")).longValue()).isEqualTo(2201L);
        assertThat(card.get("ssh_host_key")).isEqualTo(registry.key("hank"));
        final List<?> admins = (List<?>) project.get("admins");
        assertThat(admins).as("the admitted admins alone, not bob").hasSize(1);
        final Map<?, ?> admin = (Map<?, ?>) admins.getFirst();
        assertThat(keys(admin)).containsExactly("login", "name", "email");
        assertThat(admin.get("login")).isEqualTo("alice");
        assertThat(admin.get("name")).as("as the registry's card names her").isEqualTo("Alice");
        assertThat(admin.get("email")).as("her card has none").isNull();
    }

    @Test
    void a_login_not_named_named_locked_revoked_or_on_another_host_is_not_answered()
            throws Exception {
        registered(registry.access("demo"));
        accessCommit(
                "alice",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", true)
                        + person("michael", "developer", "elsewhere", false)
                        + person("hank", "developer", "walk6m", false),
                "alice",
                "bob",
                "michael");

        for (String login : List.of("bob", "michael", "hank", "carol")) {
            assertThat(joinable(login)).as(login + ": " + registry.err).isZero();
            assertThat(list("projects")).as(login + " is not admitted on walk6m").isEmpty();
            assertThat(list("refused")).as(login + ": the file itself verified").isEmpty();
        }
    }

    @Test
    void an_access_head_not_signed_by_the_admins_registry_key_is_refused_never_answered()
            throws Exception {
        registered(registry.access("demo"));
        accessCommit(
                "bob",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", false),
                "alice",
                "bob");

        assertThat(joinable("bob")).isZero();

        assertThat(list("projects")).as("a forged admission").isEmpty();
        final Map<?, ?> refused = (Map<?, ?>) list("refused").getFirst();
        assertThat(keys(refused))
                .containsExactly("project", "host", "access_repository", "why", "admins");
        assertThat(refused.get("project")).isEqualTo("demo");
        assertThat(refused.get("host")).isEqualTo("walk6m");
        assertThat(refused.get("access_repository")).isEqualTo(DEMO);
        assertThat(refused.get("why").toString()).startsWith("unknown_key");
        final Map<?, ?> admin = (Map<?, ?>) ((List<?>) refused.get("admins")).getFirst();
        assertThat(keys(admin)).containsExactly("login", "name", "email");
        assertThat(admin.get("login")).as("who the registry names to ask").isEqualTo("alice");
    }

    @Test
    void an_access_repository_that_cannot_be_fetched_is_refused_naming_it_and_its_host()
            throws Exception {
        registered(registry.access("demo"));
        served.put(DEMO, dir.resolve("gone").toString());

        assertThat(joinable("bob")).isZero();

        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("never left out").hasSize(1);
        final Map<?, ?> refused = (Map<?, ?>) list("refused").getFirst();
        assertThat(refused.get("access_repository")).isEqualTo(DEMO);
        assertThat(refused.get("host")).isEqualTo("walk6m");
        assertThat(refused.get("why").toString())
                .contains("cannot fetch ssh://example.org/demo.git");
    }

    /**
     * The registry names no credential: a token comes from the vault, chosen by the host. One
     * written into an access address, an https user alone or a blank after it included, is refused
     * before git sees it, and kept in no trust record and no answer; were it fetched, nothing
     * listens on the port it names.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://bob:tok-xyzzy@127.0.0.1:PORT/demo.git",
                "https://tok-xyzzy@127.0.0.1:PORT/demo.git",
                "https://bob:tok%40xyzzy@127.0.0.1:PORT/demo.git",
                "ssh://git:tok-xyzzy@127.0.0.1:PORT/demo.git",
                "ssh://git%3Atok-xyzzy@127.0.0.1:PORT/demo.git",
                "ssh://-oProxyCommand=x:tok-xyzzy@127.0.0.1:PORT/demo.git",
                "https://bob:tok-xyzzy@127.0.0.1:PORT/demo.git "
            })
    void an_access_address_with_a_credential_is_refused_and_kept_nowhere(String written)
            throws Exception {
        final String secret = written.replace("PORT", Integer.toString(unservedPort()));
        registered(registry.access("demo").replace(DEMO, "\"" + secret + "\""));

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(handed)
                .as("never handed to git")
                .noneMatch(argument -> argument.contains("xyzzy"));
        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("the host is named, never left out").hasSize(1);
        assertThat(registry.out.toString() + registry.err)
                .as("nothing of the token in the answer or the words")
                .doesNotContain("xyzzy");
        assertThat(Files.readString(registry.record()))
                .as("nothing of the token in the trust record")
                .doesNotContain("xyzzy");
    }

    /** A loopback port nothing listens on. */
    private static int unservedPort() throws IOException {
        try (java.net.ServerSocket socket =
                new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void a_host_without_an_accepted_card_is_refused_never_left_out() throws Exception {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.write("hosts/walk6m/access.yml", registry.access("demo"));
        registry.commit("hank");
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        accessCommit("alice", person("bob", "developer", "walk6m", false), "bob");

        assertThat(joinable("bob")).isZero();

        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("never left out").hasSize(1);
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("why").toString())
                .contains("no accepted card");
    }

    @Test
    void an_admin_whose_accepted_card_holds_another_key_than_the_pinned_one_is_refused()
            throws Exception {
        registered(registry.access("demo").replace(registry.pin("alice"), registry.pin("bob")));
        accessCommit(
                "alice",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", false),
                "alice",
                "bob");

        assertThat(joinable("bob")).isZero();

        assertThat(list("projects")).isEmpty();
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("why").toString())
                .contains("no accepted card of its admin alice");
    }

    @Test
    void a_registry_whose_history_is_refused_answers_no_project() throws Exception {
        registered(registry.access("demo"));
        accessCommit("alice", person("bob", "developer", "walk6m", false), "bob");
        assertThat(joinable("bob")).isZero();
        assertThat(list("projects")).hasSize(1);

        // Another history under the same origin: it does not continue the trusted commit.
        registry.git("checkout", "-q", "--orphan", "other");
        registry.write("owners.yml", registry.owners());
        registry.commit("michael");

        assertThat(joinable("bob")).as("refused as a whole").isEqualTo(65);
        assertThat(registry.out.toString()).as("no project answered").isEmpty();
    }

    @Test
    void a_login_of_another_shape_is_wrong_use_and_reads_nothing() throws Exception {
        registered(registry.access("demo"));

        assertThat(joinable("Bob;")).isEqualTo(64);

        assertThat(registry.out.toString()).isEmpty();
        assertThat(registry.record()).as("the registry was not read").doesNotExist();
    }

    @Test
    void a_verified_access_file_naming_no_project_repository_is_refused_never_a_row()
            throws Exception {
        registered(registry.access("demo"));
        preamble = "";
        accessCommit(
                "alice",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", false),
                "alice",
                "bob");

        assertThat(joinable("bob")).isZero();

        assertThat(list("projects")).as("nothing to follow").isEmpty();
        assertThat(list("refused")).hasSize(1);
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("why"))
                .isEqualTo("the access file names no project repository");
    }

    @Test
    void a_file_naming_no_homeserver_answers_null() throws Exception {
        registered(registry.access("demo"));
        accessCommit(
                "alice",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", false),
                "alice",
                "bob");

        assertThat(joinable("bob")).isZero();

        final Map<?, ?> project = (Map<?, ?>) list("projects").getFirst();
        assertThat(project.containsKey("homeserver")).as("there, and null").isTrue();
        assertThat(project.get("homeserver")).isNull();
    }

    /** An access file names no credential: a source holding one refuses its project. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://bob:tok-xyzzy@example.org/acme/demo.git",
                "https://bob:tok%40xyzzy@example.org/acme/demo.git",
                "ssh://git:tok-xyzzy@example.org/acme/demo.git",
                "bob:tok-xyzzy@example.org:acme/demo.git"
            })
    void a_source_with_a_credential_refuses_its_project_naming_nothing_of_it(String source)
            throws Exception {
        registered(registry.access("demo"));
        preamble = "source: \"" + source + "\"\n";
        admitted();

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("projects")).as("never offered to follow").isEmpty();
        assertThat(list("refused")).hasSize(1);
        assertThat(registry.out.toString() + registry.err).doesNotContain("xyzzy");
    }

    /** A login admitted on walk6m by an access file alice signed, with this preamble. */
    private void admitted() throws IOException {
        accessCommit(
                "alice",
                person("alice", "admin", "walk6m", false)
                        + person("bob", "developer", "walk6m", false),
                "alice",
                "bob");
    }

    /**
     * Git reads the host of {@code https://bob:tok/4711@...} before the slash, so the vault entry a
     * refusal names would carry the token: the registry refuses the address instead.
     */
    @Test
    void an_access_address_whose_user_part_holds_a_slash_is_refused_naming_nothing_of_it()
            throws Exception {
        final String secret = "https://bob:tok/4711@example.org/demo.git";
        registered(registry.access("demo").replace(DEMO, "\"" + secret + "\""));

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("the host is named, never left out").hasSize(1);
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("host")).isEqualTo("walk6m");
        assertThat(registry.out.toString() + registry.err)
                .as("no vault entry named after the user part")
                .doesNotContain("git.token.bob")
                .doesNotContain("bob:")
                .doesNotContain("tok/")
                .doesNotContain("4711");
    }

    @Test
    void a_registry_that_accepted_no_commit_is_refused_never_an_empty_answer() throws Exception {
        // Its founding cannot be decided: the forge says nothing of michael's ownership.
        registry.write("owners.yml", registry.owners());
        registry.commit("michael");
        registry.write("hosts/walk6m/access.yml", registry.access("demo"));
        registry.commit("hank");

        assertThat(joinable("bob")).as("refused").isEqualTo(65);

        assertThat(registry.out.toString()).as("no answer to read as no project").isEmpty();
        assertThat(registry.err.toString()).contains("accepted no commit");
    }

    @Test
    void a_host_whose_access_file_the_registry_refused_is_named_under_refused() throws Exception {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.forge.keys.put("bob", List.of(registry.key("bob")));
        registry.write("hosts/walk6m/card", registry.hostCard("walk6m", "hank"));
        registry.commit("hank");
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
        // Not walk6m's admin: the registry refuses the file.
        registry.write("hosts/walk6m/access.yml", registry.access("demo"));
        registry.commit("bob");
        admitted();

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("never left out").hasSize(1);
        final Map<?, ?> refused = (Map<?, ?>) list("refused").getFirst();
        assertThat(refused.get("host")).isEqualTo("walk6m");
        assertThat(refused.get("why").toString()).contains("hosts/walk6m/access.yml");
        final Map<?, ?> admin = (Map<?, ?>) ((List<?>) refused.get("admins")).getFirst();
        assertThat(admin.get("login")).as("who signs the host's files").isEqualTo("hank");
    }

    /**
     * The caller's git environment and configuration decide nothing: neither which repository is
     * fetched nor where it goes.
     */
    @Test
    void the_callers_git_environment_and_configuration_do_not_reach_the_check() throws Exception {
        registered(registry.access("demo"));
        admitted();
        final Path config = dir.resolve("callers.gitconfig");
        Files.writeString(
                config, "[url \"" + dir.resolve("gone") + "\"]\n\tinsteadOf = " + access + "\n");
        caller =
                Map.of(
                        "GIT_CONFIG_GLOBAL",
                        config.toString(),
                        "GIT_DIR",
                        dir.resolve("elsewhere").toString());

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("refused")).as(registry.out.toString()).isEmpty();
        assertThat(list("projects")).hasSize(1);
        assertThat(dir.resolve("elsewhere"))
                .as("nothing made where the caller pointed")
                .doesNotExist();
    }

    /**
     * git ends an https host at {@code ?} or {@code #} and reads {@code %40} as an at sign; git
     * splits {@code user@host:path} at its first colon and ssh takes the last of two at signs. Each
     * hands git a token as the host, or answers one: the registry refuses the address instead.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://tok-4711?x@example.org/demo.git",
                "https://tok-4711#x@example.org/demo.git",
                "https://bob:tok-4711%40example.org/demo.git",
                "bob@tok-4711@example.org:acme/demo.git",
                "https@bob:tok-4711@example.org/demo.git",
                "bob%3Atok-4711@example.org:acme/demo.git"
            })
    void an_access_address_git_reads_with_a_credential_in_its_host_is_never_fetched(String secret)
            throws Exception {
        registered(registry.access("demo").replace(DEMO, "\"" + secret + "\""));
        served.put(secret, dir.resolve("gone").toString());

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(handed).as("never handed to git").doesNotContain(secret);
        assertThat(list("projects")).isEmpty();
        assertThat(list("refused")).as("the host is named, never left out").hasSize(1);
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("host")).isEqualTo("walk6m");
        assertThat(registry.out.toString() + registry.err)
                .as("nothing of the token in the answer or the words")
                .doesNotContain("4711");
    }

    /**
     * A homeserver that is no https base URL with a real host is not one to connect to: a user
     * part, a query or a fragment could carry a token, and a blank or control character of any
     * script could hide one.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://bob:tok-4711@walk6m.example.org:8448",
                "https://bob:tok-4711%40walk6m.example.org:8448",
                " https://bob:tok-4711@walk6m.example.org:8448",
                "http://walk6m.example.org:8448",
                "https://walk6m.example.org?x=tok-4711@other.example.org",
                "walk6m.example.org:8448",
                "https://bob:tok-4711@walk6m.example.org:8448",
                "https://walk6m.example.org?x=/bob:tok-4711@evil.example.org",
                "https://walk6m.example.org#/bob:tok-4711@evil.example.org",
                "https://walk6m.example.org/?access_token=tok-4711",
                "https://walk6m.example.org/\\u00a0tok-4711",
                "https://walk6m.example.org/\\u2028tok-4711",
                "https://walk6m.example.org/\\etok-4711"
            })
    void a_homeserver_that_is_no_https_url_refuses_its_project_naming_nothing_of_it(
            String homeserver) throws Exception {
        registered(registry.access("demo"));
        preamble +=
                "transports:\n  matrix: {homeserver: \""
                        + homeserver
                        + "\", room: \"#demo:walk6m.example.org\"}\n";
        admitted();

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("projects")).as("never offered to connect to").isEmpty();
        assertThat(list("refused")).hasSize(1);
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("why").toString())
                .contains("transports.matrix.homeserver");
        assertThat(registry.out.toString() + registry.err).doesNotContain("4711");
    }

    /** An at sign in the path is the path's, and a scheme is read in either case. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "https://walk6m.example.org/_matrix/@room, https://walk6m.example.org/_matrix/@room",
        "HTTPS://walk6m.example.org:8448/x, HTTPS://walk6m.example.org:8448/x"
    })
    void a_homeserver_is_answered_as_written(String homeserver, String answered) throws Exception {
        registered(registry.access("demo"));
        preamble +=
                "transports:\n  matrix: {homeserver: \""
                        + homeserver
                        + "\", room: \"#demo:walk6m.example.org\"}\n";
        admitted();

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(((Map<?, ?>) list("projects").getFirst()).get("homeserver")).isEqualTo(answered);
    }

    /**
     * Nobody owns a registry whose founding owners.yml was refused, so a card accepted after it
     * names no project anybody may join: an empty answer would read as "none for you".
     */
    @ParameterizedTest
    @ValueSource(strings = {"michael", "alice"})
    void a_registry_whose_founding_owners_file_was_refused_is_refused_though_a_card_was_accepted(
            String founder) throws Exception {
        // michael is named but the forge says he owns nothing; alice is not named at all.
        registry.forge.owners.put("michael", false);
        registry.forge.keys.put("michael", List.of(registry.key("michael")));
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.write("owners.yml", registry.owners());
        registry.commit(founder);
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");

        assertThat(joinable("bob")).as(registry.err.toString()).isEqualTo(65);

        assertThat(registry.out.toString()).as("no answer to read as no project").isEmpty();
        assertThat(registry.err.toString()).contains("owners.yml");
    }

    /** Where the private access repository is served over loopback, as its forge serves it. */
    private static final String PRIVATE = "https://forge.example.org/acme/demo-access.git";

    /** The registry names a private https access repository, served bare from the directory. */
    private Path privateAccess() throws IOException {
        registered(registry.access("demo").replace(DEMO, PRIVATE));
        admitted();
        final Path root = dir.resolve("served");
        final Path bare = root.resolve("acme/demo-access.git");
        Files.createDirectories(bare.getParent());
        git(access, "clone", "-q", "--bare", access.toString(), bare.toString());
        git(bare, "update-server-info");
        // The account's own directories, as its environment names them: what the check must not
        // hand git, and where the vault's helper finds the vault.
        given.put("HOME", dir.resolve("home").toString());
        given.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        given.put("XDG_DATA_HOME", dir.resolve("data").toString());
        given.put("XDG_STATE_HOME", dir.resolve("state").toString());
        given.put("XDG_RUNTIME_DIR", dir.resolve("run").toString());
        Files.createDirectories(dir.resolve("home"));
        return root;
    }

    /** The vault the check's machine reads, holding the token git asks for the forge's host. */
    private char[] vaultHoldingTheToken() throws IOException {
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final PoreloContext machine = context();
        Files.createDirectories(machine.vault().path().getParent());
        machine.vault()
                .write(
                        Map.of(
                                "git.token.forge.example.org",
                                new org.fuin.porelo.vault.VaultEntry("tok-4711", "token")),
                        passphrase);
        return passphrase;
    }

    /**
     * git starts {@code porelo vault credential} for a private https access repository in the
     * check's own environment, which holds nothing of the account's: this build's own helper must
     * still find the account's vault, open it from the keyring and answer its token.
     */
    @Test
    void
            a_private_https_access_repository_is_fetched_with_the_token_the_helper_reads_from_the_vault()
                    throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        fetchedWithTheVaultsToken();
    }

    /**
     * Java reads a variable and an argument by the locale, and in the C locale as ASCII: the helper
     * git starts could not name a vault under a directory with an umlaut, and the project was
     * answered as unreachable.
     */
    @Test
    void an_account_whose_vault_path_holds_an_umlaut_is_still_lent_its_token() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "UTF-8".equals(System.getProperty("sun.jnu.encoding")),
                "this JVM reads file names as ASCII, so it cannot make the directory");
        registry.close();
        registry = new RegistryCheckout(Files.createDirectory(dir.resolve("m\u00fcller")));
        assertThat(registry.paths.vault().vaultFile().toString()).contains("\u00fc");
        fetchedWithTheVaultsToken();
    }

    /** The registry's private access repository, fetched with the token the vault holds. */
    private void fetchedWithTheVaultsToken() throws Exception {
        final Path root = privateAccess();
        final char[] passphrase = vaultHoldingTheToken();
        System.setProperty(PoreloBinary.NAMED, ownPorelo().toString());
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(registry.paths.vault().vaultKeyringKey());
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer forge =
                forge(root, "x-access-token:tok-4711", seen);
        try {
            keyring.store(passphrase);
            rewritten.put(
                    "https://forge.example.org",
                    "http://127.0.0.1:" + forge.getAddress().getPort());

            assertThat(joinable("bob")).as(registry.err.toString()).isZero();

            assertThat(list("refused")).as(registry.out.toString()).isEmpty();
            assertThat(((Map<?, ?>) list("projects").getFirst()).get("access_repository"))
                    .isEqualTo(PRIVATE);
        } finally {
            forge.stop(0);
            keyring.forget();
        }
        assertThat(seen).as("the token reached the forge").contains("x-access-token:tok-4711");
        assertThat(registry.out.toString() + registry.err).doesNotContain("tok-4711");
    }

    /**
     * libcurl answers a forge from {@code $HOME/.netrc}, so git run with the account's home lent it
     * a credential nobody chose for the check; without a home git asks the password database.
     */
    @Test
    void the_accounts_netrc_lends_the_check_nothing() throws Exception {
        final Path root = privateAccess();
        final Path netrc = dir.resolve("home/.netrc");
        Files.writeString(netrc, "machine 127.0.0.1 login x-access-token password tok-4711\n");
        Files.setPosixFilePermissions(
                netrc, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer forge =
                forge(root, "x-access-token:tok-4711", seen);
        try {
            rewritten.put(
                    "https://forge.example.org",
                    "http://127.0.0.1:" + forge.getAddress().getPort());

            assertThat(joinable("bob")).as(registry.err.toString()).isZero();
        } finally {
            forge.stop(0);
        }
        assertThat(seen).as("asked of the forge, and nothing of the netrc sent").isNotEmpty();
        assertThat(seen).doesNotContain("x-access-token:tok-4711");
        assertThat(list("projects")).as(registry.out.toString()).isEmpty();
        assertThat(list("refused")).hasSize(1);
        // Without a home of its own, libcurl reads the netrc of the password database's home,
        // which this test cannot plant: the fetch must be handed one that is neither.
        assertThat(environments).as("the fetch of the access repository").isNotEmpty();
        for (Map<String, String> environment : environments) {
            assertThat(environment.get("HOME"))
                    .as("the fetch's home")
                    .isNotBlank()
                    .isNotEqualTo(dir.resolve("home").toString())
                    .isNotEqualTo(System.getProperty("user.home"));
        }
    }

    /**
     * Of the variables credentials name, git gets only those of a token that serves git: never one
     * git, libcurl, ssh, the loader or the shell acts on, and never a token kept for another use.
     */
    @Test
    void only_a_git_tokens_variable_reaches_the_check_and_none_a_program_acts_on()
            throws Exception {
        registered(registry.access("demo"));
        admitted();
        final StringBuilder credentials = new StringBuilder("credentials:\n");
        final Map<String, String> named = new java.util.LinkedHashMap<>();
        named.put("LD_PRELOAD", "git");
        named.put("SSL_CERT_FILE", "git");
        named.put("CURL_CA_BUNDLE", "git");
        named.put("SSH_ASKPASS", "git");
        named.put("PATH", "git");
        named.put("PORELO_TEST_REGISTRY_TOKEN", "registry");
        named.put("PORELO_TEST_JOIN_TOKEN", "git");
        int at = 0;
        for (Map.Entry<String, String> name : named.entrySet()) {
            credentials
                    .append("  - match: https://host")
                    .append(at++)
                    .append(".example.org/\n    kind: token\n    purpose: ")
                    .append(name.getValue())
                    .append("\n    source: env\n    env: ")
                    .append(name.getKey())
                    .append('\n');
            given.put(name.getKey(), "/nonexistent/value-" + name.getKey());
        }
        Files.createDirectories(registry.paths.vault().credentialRegistry().getParent());
        Files.writeString(registry.paths.vault().credentialRegistry(), credentials.toString());

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();

        assertThat(list("projects")).as(registry.out.toString()).hasSize(1);
        assertThat(environments).as("the fetch of the access repository").isNotEmpty();
        for (Map<String, String> environment : environments) {
            assertThat(environment)
                    .doesNotContainKeys(
                            "LD_PRELOAD",
                            "SSL_CERT_FILE",
                            "CURL_CA_BUNDLE",
                            "SSH_ASKPASS",
                            "PORELO_TEST_REGISTRY_TOKEN")
                    .containsEntry(
                            "PORELO_TEST_JOIN_TOKEN", "/nonexistent/value-PORELO_TEST_JOIN_TOKEN");
            assertThat(environment.get("PATH")).isNotEqualTo("/nonexistent/value-PATH");
        }
    }

    /**
     * A passphrase typed at the terminal opens the vault for this process alone, and git starts the
     * helper as a process of its own, which finds it shut: the repository is answered as needing
     * the vault unlocked, never as a fetch that failed for a reason nobody can see.
     */
    @Test
    void a_vault_opened_only_by_a_passphrase_typed_here_is_answered_shut_to_the_helper()
            throws Exception {
        final Path root = privateAccess();
        final char[] passphrase = vaultHoldingTheToken();
        System.setProperty(PoreloBinary.NAMED, ownPorelo().toString());
        context().requireOpener(() -> java.util.Optional.of(passphrase.clone()));
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer forge =
                forge(root, "x-access-token:tok-4711", seen);
        try {
            rewritten.put(
                    "https://forge.example.org",
                    "http://127.0.0.1:" + forge.getAddress().getPort());

            assertThat(joinable("bob")).as(registry.err.toString()).isZero();
        } finally {
            forge.stop(0);
        }
        assertThat(list("projects")).isEmpty();
        assertThat(((Map<?, ?>) list("refused").getFirst()).get("why").toString())
                .as("what helps is unlocking the vault")
                .startsWith("vault_locked");
        assertThat(registry.out.toString() + registry.err).doesNotContain("tok-4711");
    }

    /**
     * This build's own {@code porelo}, as git starts it for the credential protocol: the test's
     * class path, and the test's keyring names, so the passphrase the test keeps is the one found.
     */
    private Path ownPorelo() throws IOException {
        final Path launcher = dir.resolve("own").resolve("porelo");
        Files.createDirectories(launcher.getParent());
        Files.writeString(
                launcher,
                "#!/bin/sh\nexec '"
                        + ProcessHandle.current().info().command().orElse("java")
                        + "' --enable-native-access=ALL-UNNAMED '-Dporelo.keyring.prefix="
                        + System.getProperty("porelo.keyring.prefix", "porelo")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(
                launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return launcher;
    }

    /**
     * A forge on loopback serving a repository's files only to the user and token given, noting
     * each credential it was sent.
     */
    private static com.sun.net.httpserver.HttpServer forge(
            Path root, String credential, List<String> seen) throws IOException {
        final String expected =
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString(
                                        credential.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8));
        final com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        final String sent = exchange.getRequestHeaders().getFirst("Authorization");
                        seen.add(
                                sent == null || !sent.startsWith("Basic ")
                                        ? "none"
                                        : new String(
                                                java.util.Base64.getDecoder()
                                                        .decode(sent.substring(6)),
                                                java.nio.charset.StandardCharsets.UTF_8));
                        if (!expected.equals(
                                exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.getResponseHeaders()
                                    .add("WWW-Authenticate", "Basic realm=\"forge\"");
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        final Path file =
                                root.resolve(exchange.getRequestURI().getPath().substring(1))
                                        .normalize();
                        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        final byte[] body = Files.readAllBytes(file);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        return server;
    }
}
