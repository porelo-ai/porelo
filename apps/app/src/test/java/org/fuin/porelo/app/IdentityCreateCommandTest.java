package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Guards {@code identity create}: a first card for a key the agent serves, signed through it. */
class IdentityCreateCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    private static List<Object> keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    private int create(String... more) {
        final List<String> args =
                new ArrayList<>(List.of("create", "--login", "alice", "--name", "Alice Doe"));
        args.addAll(List.of(more));
        return IdentityRun.identity(registry, args.toArray(String[]::new));
    }

    private Path cardFile() {
        return new IdentityStore(registry.context()).cardFile();
    }

    private Object reason() {
        return registry.answer().get("reason");
    }

    /** The card made must be one a host and a registry accept, kept where show reads it. */
    @Test
    void makes_a_card_card_verify_accepts_signed_through_the_agent() throws Exception {
        registry.agent("bob", "alice");

        assertThat(create("--email", "alice@example.org", "--key", registry.pin("alice"), "--json"))
                .isZero();
        final Map<?, ?> answer = registry.answer();
        assertThat(keys(answer))
                .containsExactly("login", "fingerprint", "key", "card", "agent", "forge", "next");
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(answer.get("key")).isEqualTo(registry.key("alice"));
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("forge")).as("no registry read").isNull();
        assertThat(answer.get("next")).isEqualTo(List.of("file-card"));
        assertThat(answer.get("card")).isEqualTo(Files.readString(cardFile()));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(cardFile())))
                .isEqualTo("rw-------");
        assertThat(Files.readString(cardFile().resolveSibling("key.pub")))
                .isEqualTo(registry.key("alice") + " alice\n");

        final PersonCard card = PersonCard.read(Files.readString(cardFile()), "card");
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(card.created()).isEqualTo(today);
        assertThat(card.expires()).isEqualTo(today.plusDays(30));
        assertThat(card.email()).isEqualTo("alice@example.org");
        assertThat(card.predecessor()).isEmpty();

        final StringWriter out = new StringWriter();
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(new StringWriter()));
        assertThat(
                        cli.execute(
                                "card",
                                "verify",
                                cardFile().toString(),
                                "--pinned",
                                registry.pin("alice")))
                .isZero();
        assertThat(out.toString()).contains("alice (Alice Doe)");
    }

    /** A person names the key by its .pub file as often as by its fingerprint. */
    @Test
    void takes_the_key_by_its_public_key_file_and_answers_the_forge_listing() throws Exception {
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        registry.forge.keys.put("alice", List.of(registry.key("bob")));
        registry.agent("alice");

        assertThat(create("--key", registry.keyFiles.get("alice") + ".pub", "--json")).isZero();
        assertThat(registry.answer().get("fingerprint")).isEqualTo(registry.pin("alice"));
        final Map<?, ?> forge = (Map<?, ?>) registry.answer().get("forge");
        assertThat(keys(forge)).containsExactly("login", "key_listed", "keys_url", "account_url");
        assertThat(forge.get("key_listed")).as("not a refusal: added later").isEqualTo(false);
    }

    /** A second create would throw away the identity the registry knows. */
    @Test
    void a_card_kept_already_is_refused_identity_exists() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "bob"));
        registry.agent("alice");
        final String kept = Files.readString(cardFile());

        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(65);
        assertThat(reason()).isEqualTo("identity-exists");
        assertThat(Files.readString(cardFile())).isEqualTo(kept);
    }

    /** A locked KeePassXC serves nothing; the person is told, and nothing is kept. */
    @Test
    void a_key_the_agent_does_not_serve_is_refused_key_not_in_agent() throws Exception {
        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(65);
        assertThat(reason()).as("no agent").isEqualTo("key-not-in-agent");

        registry.agent("bob");
        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(65);
        assertThat(reason()).isEqualTo("key-not-in-agent");
        assertThat(cardFile()).doesNotExist();
    }

    /** A card takes an Ed25519 key only, whether named by file or by fingerprint. */
    @Test
    void a_key_of_another_type_is_refused_key_not_ed25519() throws Exception {
        final Path rsa = dir.resolve("rsa");
        registry.runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "rsa", "-N", "", "-f", rsa.toString()));
        assertThat(create("--key", rsa + ".pub", "--json")).isEqualTo(65);
        assertThat(reason()).isEqualTo("key-not-ed25519");

        registry.agent();
        registry.runner.runOrFail(
                Command.of("ssh-add", "-q", rsa.toString())
                        .withEnvironment(
                                Map.of(
                                        "SSH_AUTH_SOCK",
                                        registry.environment().apply("SSH_AUTH_SOCK"))));
        final String fingerprint =
                registry.runner
                        .runOrFail(Command.of("ssh-keygen", "-l", "-f", rsa + ".pub"))
                        .trimmedOutput()
                        .split(" ")[1];
        assertThat(create("--key", fingerprint, "--json")).isEqualTo(65);
        assertThat(reason()).isEqualTo("key-not-ed25519");
        assertThat(cardFile()).doesNotExist();
    }

    /** One key on two logins' cards would let one person sign as the other. */
    @Test
    void a_key_on_another_logins_accepted_card_is_refused_naming_the_login() throws Exception {
        registry.founded();
        registry.forge.keys.put("bob", List.of(registry.key("bob")));
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        registry.agent("bob");

        assertThat(create("--key", registry.pin("bob"), "--json")).isEqualTo(65);
        assertThat(keys(registry.answer())).containsExactly("reason", "words", "login");
        assertThat(reason()).isEqualTo("key-on-another-card");
        assertThat(registry.answer().get("login")).isEqualTo("bob");
        assertThat(cardFile()).doesNotExist();
    }

    /** A revoked key's card is accepted nowhere; the person is told whom to ask. */
    @Test
    void a_revoked_key_is_refused_naming_who_may_replace_it() throws Exception {
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.revoke(registry, "alice");
        registry.agent("alice");

        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(65);
        assertThat(keys(registry.answer())).containsExactly("reason", "words", "who_may");
        assertThat(reason()).isEqualTo("revoked");
        assertThat((List<?>) registry.answer().get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));
        assertThat(cardFile()).doesNotExist();
    }

    /** Wrong use is told apart from a refusal. */
    @Test
    void both_ways_or_neither_or_an_entry_alone_is_wrong_use() {
        assertThat(
                        create(
                                "--key",
                                registry.pin("alice"),
                                "--keepassxc",
                                dir.resolve("db.kdbx").toString()))
                .isEqualTo(64);
        assertThat(create()).isEqualTo(64);
        assertThat(create("--key", registry.pin("alice"), "--entry", "ssh/alice")).isEqualTo(64);
    }

    /** A login a card does not take is refused before the agent is asked to sign anything. */
    @ParameterizedTest
    @ValueSource(strings = {"Alice", "porelo", "porelo-agent", "1alice", "a‮b"})
    void a_login_a_card_does_not_take_is_wrong_use(String login) throws Exception {
        registry.agent("alice");
        assertThat(
                        IdentityRun.identity(
                                registry,
                                "create",
                                "--login",
                                login,
                                "--name",
                                "Alice",
                                "--key",
                                registry.pin("alice")))
                .isEqualTo(64);
        assertThat(registry.err.toString()).doesNotContain("‮");
        assertThat(cardFile()).doesNotExist();
    }

    /** A card looked for through a linked directory is anybody's; no card is made beside it. */
    @Test
    void a_linked_config_directory_is_refused() throws Exception {
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        final Path config = registry.paths.xdg().config();
        Files.createDirectories(config.getParent());
        Files.createSymbolicLink(config, elsewhere);
        registry.agent("alice");

        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(66);
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).isEmpty();
        }
    }
}
