package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshAgentClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards {@code identity create --keepassxc}: a new key made in memory goes to KeePassXC over a
 * pipe and nowhere else, and the card is signed only once the agent serves it.
 */
class IdentityKeePassXcTest {
    @TempDir Path dir;
    private RegistryCheckout registry;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
        registry.terminal = true;
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    private KeePassXcStandIn standIn(final KeePassXcStandIn.Mode mode) throws Exception {
        registry.agent();
        return new KeePassXcStandIn(dir, mode, registry.environment().apply("SSH_AUTH_SOCK"));
    }

    private int create(final KeePassXcKey store, final String... more) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        final IdentityCreateCommand create =
                (IdentityCreateCommand)
                        cli.getSubcommands()
                                .get("identity")
                                .getSubcommands()
                                .get("create")
                                .getCommand();
        create.forges = (address, token) -> registry.forge;
        create.store = store;
        final List<String> all =
                new ArrayList<>(
                        List.of("identity", "create", "--login", "alice", "--name", "Alice Doe"));
        all.addAll(List.of(more));
        return cli.execute(all.toArray(String[]::new));
    }

    private Path cardFile() {
        return new IdentityStore(registry.context()).cardFile();
    }

    private List<String> served() {
        return new SshAgentClient(Path.of(registry.environment().apply("SSH_AUTH_SOCK")))
                .identities().stream().map(PersonCard::keyLine).toList();
    }

    /** The whole point: a key a person never saw as a file, served by KeePassXC's agent. */
    @Test
    void a_key_made_into_keepassxc_is_served_by_the_agent_and_its_card_passes_card_verify()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "ssh/alice",
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        final Map<?, ?> answer = registry.answer();
        assertThat(new ArrayList<Object>(answer.keySet()))
                .containsExactly(
                        "login", "fingerprint", "key", "card", "agent", "entry", "forge", "next");
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("entry")).isEqualTo("ssh/alice");
        assertThat(served())
                .as("the agent serves the new key")
                .containsExactly((String) answer.get("key"));
        assertThat(PersonCard.fingerprint((String) answer.get("key")))
                .isEqualTo(answer.get("fingerprint"));
        assertThat(Files.readString(cardFile())).isEqualTo(answer.get("card"));
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("ssh/alice"));

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
                                (String) answer.get("fingerprint")))
                .as("card verify takes the card the agent signed")
                .isZero();
        assertThat(out.toString()).contains("alice (Alice Doe)");
    }

    /**
     * A key file anywhere, or the key in an argument list every account can read, would be the key
     * leaked: both attachments arrive on a pipe, and the agent settings never save it.
     */
    @Test
    void no_file_holds_the_private_key_at_any_time_and_no_argument_carries_it() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        final String key = (String) registry.answer().get("key");
        final String entry = (String) registry.answer().get("entry");
        assertThat(entry).as("named after the login and the time").matches("porelo-alice-.+");
        assertThat(keepass.seen())
                .as("each attachment arrived on a pipe")
                .containsExactly("pipe id_ed25519", "pipe KeeAgent.settings");
        assertThat(keepass.holding(key))
                .as("no private key file of the new key, while KeePassXC took it or after")
                .isEmpty();
        assertThat(keepass.calls())
                .as("the arguments name the pipe, never the key")
                .isEqualTo(keepass.madeInto(entry));
        assertThat(keepass.settings())
                .contains("<AllowUseOfSshKey>true</AllowUseOfSshKey>")
                .contains("<AddAtDatabaseOpen>true</AddAtDatabaseOpen>")
                .contains("<RemoveAtDatabaseClose>true</RemoveAtDatabaseClose>")
                .contains("<SelectedType>attachment</SelectedType>")
                .contains("<AttachmentName>id_ed25519</AttachmentName>")
                .as("KeePassXC never writes the key to a temporary file")
                .contains("<SaveAttachmentToTempFile>false</SaveAttachmentToTempFile>");
        assertThat(registry.out.toString())
                .as("keepassxc-cli talks on the terminal")
                .startsWith("{");
        assertThat(Files.readString(keepass.terminal)).contains("Entry added.");
    }

    /** keepassxc-cli asks for the master password itself; without a terminal nobody can type. */
    @Test
    void without_a_terminal_it_is_refused_64_and_keepassxc_is_never_asked() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);
        registry.terminal = false;

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .isEqualTo(64);
        assertThat(registry.answer().get("reason")).isEqualTo("no-terminal");
        assertThat(keepass.calls()).isEmpty();
        assertThat(served()).isEmpty();
        assertThat(cardFile()).doesNotExist();
    }

    /**
     * The database not open in KeePassXC: the entry stays, so a retry names the key and never makes
     * a second one, and no card is made for a key nobody can sign with.
     */
    @Test
    void a_key_not_served_in_time_is_refused_key_not_served_keeping_the_entry_and_no_card()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);

        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(600)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice",
                                "--json"))
                .isEqualTo(65);
        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("reason")).isEqualTo("key-not-served");
        assertThat(answer.get("entry")).isEqualTo("alice");
        assertThat((String) answer.get("fingerprint")).startsWith("SHA256:");
        assertThat((String) answer.get("words")).contains((String) answer.get("fingerprint"));
        assertThat(keepass.calls()).as("the entry is kept").isEqualTo(keepass.madeInto("alice"));
        assertThat(keepass.seen())
                .as("KeePassXC got an OpenSSH private key")
                .contains("-----BEGIN OPENSSH PRIVATE KEY-----");
        assertThat(cardFile()).doesNotExist();
        assertThat(cardFile().getParent()).doesNotExist();
    }

    /** KeePassXC's agent takes a moment after the database opens; that is waited for. */
    @Test
    void a_key_served_a_second_later_is_waited_for() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES_LATE);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(20)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(served()).containsExactly((String) registry.answer().get("key"));
        assertThat(cardFile()).exists();
    }

    /** A wrong password: KeePassXC took nothing, and Porelo keeps nothing either. */
    @Test
    void a_refused_password_is_refused_key_store_refused_and_nothing_is_kept() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.REFUSES);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice",
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("key-store-refused");
        assertThat(keepass.calls())
                .as("no key is handed to a database that refused")
                .containsExactly("add " + keepass.database + " alice");
        assertThat(served()).isEmpty();
        assertThat(cardFile().getParent()).doesNotExist();
        assertThat(Files.readString(keepass.terminal)).contains("Invalid credentials");
    }

    /** Without keepassxc-cli there is no store; that is said, and no key is made. */
    @Test
    void without_keepassxc_cli_it_is_refused_key_store_missing() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);
        final KeePassXcKey missing =
                new KeePassXcKey(
                        dir.resolve("nowhere").toString(),
                        Duration.ofSeconds(1),
                        Path.of("/dev/null"),
                        keepass.terminal);

        assertThat(create(missing, "--keepassxc", keepass.database.toString(), "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("key-store-missing");
        assertThat(cardFile().getParent()).doesNotExist();
    }

    /** An entry path that keepassxc-cli would read as an option is wrong use. */
    @Test
    void an_entry_starting_with_a_dash_is_wrong_use() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(1)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry=--no-password"))
                .isEqualTo(64);
        assertThat(keepass.calls()).isEmpty();
    }

    /** No key is made into a store when no agent could ever serve it. */
    @Test
    void without_an_agent_it_is_refused_key_not_in_agent_before_keepassxc_is_asked()
            throws Exception {
        final KeePassXcStandIn keepass =
                new KeePassXcStandIn(dir, KeePassXcStandIn.Mode.SERVES, "/nonexistent");

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(1)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("key-not-in-agent");
        assertThat(keepass.calls()).isEmpty();
    }
}
