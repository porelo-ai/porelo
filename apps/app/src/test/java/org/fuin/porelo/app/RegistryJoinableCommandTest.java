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
    }

    /** A git that reaches each served address on disk, and no configuration of this machine. */
    private PoreloContext context() {
        final CommandRunner local =
                command -> {
                    final List<String> arguments = new ArrayList<>();
                    for (String argument : command.arguments()) {
                        arguments.add(served.getOrDefault(argument, argument));
                    }
                    final Map<String, String> environment =
                            new HashMap<>(command.inheritsEnvironment() ? caller : Map.of());
                    environment.putAll(command.environment());
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
        return new PoreloContext(local, registry.paths, args -> 0, registry.environment());
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

    @Test
    void an_access_address_with_a_credential_is_answered_without_it() throws Exception {
        final String secret = "https://bob:tok-4711@example.org/demo.git";
        registered(registry.access("demo").replace(DEMO, secret));
        served.put(secret, dir.resolve("gone").toString());

        assertThat(joinable("bob")).isZero();

        assertThat(registry.out.toString() + registry.err)
                .as("a token in the registry's address is never printed")
                .doesNotContain("tok-4711")
                .contains("https://example.org/demo.git");
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
    void a_file_naming_no_homeserver_answers_null_and_a_credential_in_the_source_is_stripped()
            throws Exception {
        registered(registry.access("demo"));
        preamble = "source: https://bob:tok-0815@example.org/acme/demo.git\n";
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
        assertThat(project.get("source")).isEqualTo("https://example.org/acme/demo.git");
        assertThat(registry.out.toString() + registry.err).doesNotContain("tok-0815");
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

    /** A user part holding an at sign, a slash or an ssh password is a credential all the same. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "https://bob:tok@4711@example.org/demo.git, https://example.org/demo.git",
        "ssh://git:tok-4711@example.org/demo.git, ssh://git@example.org/demo.git",
        "ssh://git:tok:4711@example.org:2222/demo.git, ssh://git@example.org:2222/demo.git"
    })
    void no_part_of_an_addresss_user_part_but_an_ssh_user_is_printed(String secret, String printed)
            throws Exception {
        registered(registry.access("demo").replace(DEMO, "\"" + secret + "\""));
        preamble =
                "source: \""
                        + secret.replace("demo.git", "acme/demo.git")
                        + "\"\ntransports:\n  matrix: {homeserver: \"https://bob:tok@4711@walk6m.example.org:8448\","
                        + " room: \"#demo:walk6m.example.org\"}\n";
        served.put(secret, access.toString());
        admitted();

        assertThat(joinable("bob")).as(registry.err.toString()).isZero();
        final Map<?, ?> project = (Map<?, ?>) list("projects").getFirst();
        assertThat(project.get("access_repository")).isEqualTo(printed);
        assertThat(project.get("source")).isEqualTo(printed.replace("demo.git", "acme/demo.git"));
        assertThat(project.get("homeserver")).isEqualTo("https://walk6m.example.org:8448");

        served.put(secret, dir.resolve("gone").toString());
        assertThat(joinable("bob")).isZero();
        final Map<?, ?> refused = (Map<?, ?>) list("refused").getFirst();
        assertThat(refused.get("access_repository")).isEqualTo(printed);
        assertThat(refused.get("why").toString()).contains("cannot fetch");
        assertThat(registry.out.toString() + registry.err)
                .as("nothing of the token in the answer or the words")
                .doesNotContain("tok@")
                .doesNotContain("tok-")
                .doesNotContain("tok:")
                .doesNotContain("4711");
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
}
