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
    private TestOrganization organization;

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

    /** As {@link #create}, on a machine that names the organization. */
    private int createInOrganization(String... more) throws Exception {
        if (organization == null) {
            organization = new TestOrganization(registry);
        }
        final List<String> args =
                new ArrayList<>(List.of("create", "--login", "alice", "--name", "Alice Doe"));
        args.addAll(List.of(more));
        return IdentityRun.identity(organization, args.toArray(String[]::new));
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
                .containsExactly(
                        "revision",
                        "id",
                        "again",
                        "login",
                        "fingerprint",
                        "key",
                        "card",
                        "agent",
                        "forge",
                        "next");
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(answer.get("key")).isEqualTo(registry.key("alice"));
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("revision")).as("no organization to file in").isNull();
        assertThat(answer.get("forge")).as("no organization names one").isNull();
        assertThat(answer.get("next")).isEqualTo(List.of("setup"));
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

    /** With an organization named, a first card goes there; kept here alone nobody knows it. */
    @Test
    void a_first_card_files_through_the_store_when_this_machine_names_an_organization()
            throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final String before = organization.tip();

        assertThat(createInOrganization("--key", registry.pin("alice"), "--id", "c1", "--json"))
                .as(registry.err.toString())
                .isZero();

        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("id")).isEqualTo("c1");
        assertThat(answer.get("again")).isEqualTo(false);
        assertThat(organization.tip()).isNotEqualTo(before);
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        assertThat(answer.get("next")).isEqualTo(List.of());
        assertThat(organization.card("alice")).isEqualTo(answer.get("card"));
        assertThat(Files.readString(cardFile())).isEqualTo(answer.get("card"));
        assertThat(organization.signer("HEAD")).isEqualTo("alice");
        final Map<?, ?> forge = (Map<?, ?>) answer.get("forge");
        assertThat(forge.get("key_listed")).isEqualTo(true);

        assertThat(createInOrganization("--key", registry.pin("alice"), "--id", "c1", "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.answer().get("again")).isEqualTo(true);
        assertThat(registry.answer().get("revision")).isEqualTo(organization.tip());
    }

    /** The disk filling up after the push leaves the person a change to ask about, by its id. */
    @Test
    void a_local_write_failing_after_the_first_card_is_filed_is_failed_as_an_object_with_the_id()
            throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path pub = cardFile().resolveSibling("key.pub");
        Files.createDirectories(pub.resolve("in-the-way"));

        assertThat(createInOrganization("--key", registry.pin("alice"), "--id", "c1", "--json"))
                .isEqualTo(70);

        assertThat(reason()).isEqualTo("failed");
        assertThat(registry.answer().get("id")).isEqualTo("c1");
    }

    /** Kept here and nowhere else: the same object, for a machine naming no organization. */
    @Test
    void a_card_that_cannot_be_kept_is_failed_as_an_object() throws Exception {
        registry.agent("alice");
        final Path pub = cardFile().resolveSibling("key.pub");
        Files.createDirectories(pub.resolve("in-the-way"));

        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(70);

        assertThat(reason()).isEqualTo("failed");
    }

    /** A card made before the machine had an organization goes in once it has: never a second. */
    @Test
    void a_card_kept_before_the_organization_was_named_is_filed_as_it_is() throws Exception {
        registry.agent("alice");
        assertThat(create("--key", registry.pin("alice"), "--json")).isZero();
        final String kept = Files.readString(cardFile());
        organization = new TestOrganization(registry);
        registry.forge.keys.put("alice", List.of(registry.key("alice")));

        assertThat(createInOrganization("--key", registry.pin("alice"), "--json"))
                .as(registry.err.toString())
                .isZero();

        assertThat(organization.card("alice")).isEqualTo(kept);
        assertThat(Files.readString(cardFile())).isEqualTo(kept);
    }

    /** A forge not listing the key must be told apart from a refusal the person cannot act on. */
    @Test
    void a_key_the_forge_does_not_list_is_refused_and_nothing_is_kept() throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        registry.forge.keys.put("alice", List.of(registry.key("bob")));
        final String before = organization.tip();

        assertThat(createInOrganization("--key", registry.pin("alice"), "--json")).isEqualTo(65);

        assertThat(reason()).isEqualTo("not-allowed");
        assertThat(organization.tip()).isEqualTo(before);
        assertThat(cardFile()).doesNotExist();
    }

    /**
     * A new key cannot be on the forge before it is made, and a first card has no old key to hand
     * on from: the person must learn which key to add, and go on with it, not make a second.
     */
    @Test
    void a_key_made_into_keepassxc_the_forge_does_not_list_names_it_to_go_on_with()
            throws Exception {
        registry.agent();
        organization = new TestOrganization(registry);
        registry.terminal = true;
        final KeePassXcStandIn keepass =
                new KeePassXcStandIn(
                        dir,
                        KeePassXcStandIn.Mode.SERVES,
                        registry.environment().apply("SSH_AUTH_SOCK"));
        registry.forge.keys.put("alice", List.of(registry.key("bob")));

        assertThat(
                        IdentityRun.identity(
                                organization,
                                keepass.store(java.time.Duration.ofMillis(800)),
                                "create",
                                "--login",
                                "alice",
                                "--name",
                                "Alice Doe",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "ssh/alice",
                                "--id",
                                "c1",
                                "--json"))
                .as(registry.err.toString())
                .isEqualTo(65);

        final Map<?, ?> refused = registry.answer();
        assertThat(refused.get("reason")).isEqualTo("not-allowed");
        assertThat(refused.get("entry")).isEqualTo("ssh/alice");
        assertThat(cardFile()).doesNotExist();
        final String fingerprint = (String) refused.get("fingerprint");
        final String listed =
                new org.fuin.porelo.vault.SshAgentClient(
                                Path.of(registry.environment().apply("SSH_AUTH_SOCK")))
                        .identities().stream()
                                .map(PersonCard::keyLine)
                                .filter(key -> PersonCard.fingerprint(key).equals(fingerprint))
                                .findFirst()
                                .orElseThrow();
        registry.forge.keys.put("alice", List.of(listed));
        final int calls = keepass.calls().size();

        assertThat(createInOrganization("--key", fingerprint, "--id", "c1", "--json"))
                .as(registry.err.toString())
                .isZero();

        assertThat(keepass.calls()).as("no second key").hasSize(calls);
        assertThat(PersonCard.read(organization.card("alice"), "filed").fingerprint())
                .isEqualTo(fingerprint);
    }

    /** A person names the key by its .pub file as often as by its fingerprint. */
    @Test
    void takes_the_key_by_its_public_key_file() throws Exception {
        registry.agent("alice");
        assertThat(create("--key", registry.keyFiles.get("alice") + ".pub", "--json")).isZero();
        assertThat(registry.answer().get("fingerprint")).isEqualTo(registry.pin("alice"));
    }

    /** A script stopped later runs from the top: its first step must not stop it there. */
    @Test
    void a_first_card_asked_again_under_its_id_answers_as_made() throws Exception {
        registry.agent("alice");
        assertThat(create("--key", registry.pin("alice"), "--id", "c1", "--json"))
                .as(registry.err.toString())
                .isZero();
        final Object made = registry.answer().get("card");

        assertThat(create("--key", registry.pin("alice"), "--id", "c1", "--json"))
                .as(registry.err.toString())
                .isZero();

        assertThat(registry.answer().get("again")).isEqualTo(true);
        assertThat(registry.answer().get("card")).isEqualTo(made);
        assertThat(Files.readString(cardFile())).isEqualTo(made);
    }

    /** The founder's card: the organization took it with no record of the id here. */
    @Test
    void a_first_card_the_organization_took_since_answers_as_made_under_its_id() throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        organization.filed("alice");
        final String card = organization.card("alice");
        final PersonCard read = PersonCard.read(card, "filed");
        IdentityRun.keep(registry, card);
        final List<String> again =
                new ArrayList<>(
                        List.of(
                                "create",
                                "--login",
                                read.login(),
                                "--name",
                                read.name(),
                                "--key",
                                registry.pin("alice"),
                                "--id",
                                "c1",
                                "--json"));
        final String tip = organization.tip();

        assertThat(IdentityRun.identity(organization, again.toArray(String[]::new)))
                .as(registry.err.toString())
                .isZero();

        assertThat(registry.answer().get("again")).isEqualTo(true);
        assertThat(registry.answer().get("revision")).isEqualTo(tip);
        assertThat(organization.tip()).as("nothing filed twice").isEqualTo(tip);
    }

    /** An id names one change: another name on the same key is not what it made. */
    @Test
    void a_kept_card_under_another_name_is_still_identity_exists() throws Exception {
        registry.agent("alice");
        assertThat(create("--key", registry.pin("alice"), "--id", "c1", "--json")).isZero();

        assertThat(
                        IdentityRun.identity(
                                registry,
                                "create",
                                "--login",
                                "alice",
                                "--name",
                                "Somebody Else",
                                "--key",
                                registry.pin("alice"),
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(65);
        assertThat(reason()).isEqualTo("identity-exists");
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
    void a_key_the_agent_does_not_serve_is_refused_no_key() throws Exception {
        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(69);
        assertThat(reason()).as("no agent").isEqualTo("no-key");

        registry.agent("bob");
        assertThat(create("--key", registry.pin("alice"), "--json")).isEqualTo(69);
        assertThat(reason()).isEqualTo("no-key");
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
        registry.agent("bob");
        organization = new TestOrganization(registry);
        organization.filed("bob");

        assertThat(createInOrganization("--key", registry.pin("bob"), "--json")).isEqualTo(65);
        assertThat(keys(registry.answer())).containsExactly("reason", "words", "revision", "login");
        assertThat(reason()).isEqualTo("key-on-another-card");
        assertThat(registry.answer().get("login")).isEqualTo("bob");
        assertThat(cardFile()).doesNotExist();
    }

    /** A revoked key's card is accepted nowhere; the person is told whom to ask. */
    @Test
    void a_revoked_key_is_refused_naming_who_may_replace_it() throws Exception {
        IdentityRun.revoke(registry, "alice");
        registry.agent("alice");

        assertThat(createInOrganization("--key", registry.pin("alice"), "--json")).isEqualTo(65);
        assertThat(keys(registry.answer()))
                .containsExactly("reason", "words", "revision", "who_may");
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

    /**
     * Founding an organization takes a card first, and its empty repository is named before there
     * is one: refusing here would leave the person no way to either.
     */
    @Test
    void a_repository_holding_nothing_yet_prepares_a_first_card_and_files_nothing()
            throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        organization.holdsNothing();

        assertThat(createInOrganization("--key", registry.pin("alice"), "--json"))
                .as(registry.err.toString())
                .isZero();

        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("revision")).as("nothing was accepted yet").isNull();
        assertThat(answer.get("next")).isEqualTo(List.of("org create"));
        assertThat(Files.readString(cardFile())).isEqualTo(answer.get("card"));
        assertThat(organization.git("for-each-ref").isEmpty()).as("nothing was filed").isTrue();
    }

    /** A store that held a state and now holds nothing says nothing of what became of it. */
    @Test
    void a_repository_emptied_after_it_was_read_is_cannot_say() throws Exception {
        registry.agent("alice");
        organization = new TestOrganization(registry);
        assertThat(IdentityRun.identity(organization, "keys", "--json"))
                .as(registry.err.toString())
                .isZero();
        organization.holdsNothing();

        assertThat(createInOrganization("--key", registry.pin("alice"), "--json")).isEqualTo(69);

        assertThat(reason()).isEqualTo("cannot-say");
        assertThat(cardFile()).doesNotExist();
    }

    /** Only a first card needs no store: every other verb has nothing to rest on. */
    @Test
    void every_other_verb_finds_a_repository_holding_nothing_cannot_say() throws Exception {
        registry.agent("alice", "bob");
        organization = new TestOrganization(registry);
        organization.holdsNothing();
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        final String pin = registry.pin("bob");

        final List<List<String>> verbs =
                List.of(
                        List.of("rotate", "--key", pin, "--json"),
                        List.of("revoke", "--lost", "--key", pin, "--json"),
                        List.of("show", "--json"),
                        List.of("keys", "--json"),
                        List.of("backup-key", "--organization", "--out", "unused", "--json"));
        for (final List<String> verb : verbs) {
            assertThat(IdentityRun.identity(organization, verb.toArray(String[]::new)))
                    .as(verb.toString())
                    .isEqualTo(69);
            assertThat(reason()).as(verb.toString()).isEqualTo("cannot-say");
        }
    }
}
