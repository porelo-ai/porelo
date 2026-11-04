package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        create.store = store;
        final List<String> all =
                new ArrayList<>(
                        List.of("identity", "create", "--login", "alice", "--name", "Alice Doe"));
        all.addAll(List.of(more));
        return cli.execute(all.toArray(String[]::new));
    }

    @org.junit.jupiter.api.Test
    void aKeyMadeIntoKeePassXcSaysSoFirstAndEndsWithItsPublicHalf() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        final int code =
                create(
                        keepass.store(java.time.Duration.ofSeconds(5)),
                        "--keepassxc",
                        keepass.database.toString(),
                        "--entry",
                        "ssh/alice");

        org.assertj.core.api.Assertions.assertThat(code).as(registry.err.toString()).isZero();
        org.assertj.core.api.Assertions.assertThat(registry.out.toString())
                .as("one key, where it lies, and no file for it, before any password")
                .startsWith("This makes one Ed25519 key in " + keepass.database)
                .contains("no file is written")
                .contains("key ssh-ed25519 ")
                .contains("https://github.com/settings/ssh/new")
                .doesNotContain("PRIVATE KEY");
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
                        "revision",
                        "id",
                        "again",
                        "login",
                        "fingerprint",
                        "key",
                        "card",
                        "agent",
                        "entry",
                        "forge",
                        "next");
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

    private void refusedOn(final Path in, final Path out, final String words) throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        create(
                                keepass.on(in, out),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .isEqualTo(64);
        assertThat(registry.answer().get("reason")).isEqualTo("no-terminal");
        assertThat(registry.answer().toString()).contains(words);
        assertThat(keepass.calls()).as("keepassxc-cli never ran").isEmpty();
    }

    /** Standard error redirected would hide the prompt the person has to answer. */
    @Test
    void a_standard_error_that_is_no_terminal_is_refused_saying_which() throws Exception {
        final Path tty = Files.createSymbolicLink(dir.resolve("in"), Path.of("/dev/tty"));
        final Path file = Files.writeString(dir.resolve("file"), "");
        final Path link = Files.createSymbolicLink(dir.resolve("err"), file);

        refusedOn(tty, link, "standard error is not a terminal");
    }

    /** A /proc that hides this process's descriptors says so, rather than a wrong reason. */
    @Test
    void a_descriptor_that_cannot_be_read_is_refused_saying_so() throws Exception {
        final Path missing = dir.resolve("no-such-descriptor");

        refusedOn(missing, missing, "hidepid");
    }

    /** Under su or sudo -u the terminal is the other account's, and nobody is asked on it. */
    @Test
    void a_terminal_of_another_account_is_refused() {
        assertThatThrownBy(() -> KeePassXcKey.owned("bob", "alice", "standard input"))
                .isInstanceOfSatisfying(
                        IdentityChange.Refused.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(64);
                            assertThat(ex.reason()).isEqualTo("terminal-not-yours");
                        });
    }

    /** With neither, the terminal is what is said first: an agent would not help. */
    @Test
    void with_neither_a_terminal_nor_an_agent_the_answer_is_no_terminal() throws Exception {
        final KeePassXcStandIn keepass =
                new KeePassXcStandIn(dir, KeePassXcStandIn.Mode.SERVES, "/no-such-agent");
        registry.terminal = false;

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(1)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .isEqualTo(64);
        assertThat(registry.answer().get("reason")).isEqualTo("no-terminal");
    }

    /** A retry takes the key the first run made: no second key, and no password asked. */
    @Test
    void a_retry_after_key_not_served_waits_for_the_first_key_and_makes_no_second()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES_AFTER_UNLOCK);
        keepass.running(false);
        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "ssh/alice",
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(65);
        final Map<?, ?> first = registry.answer();
        assertThat(first.get("reason")).isEqualTo("key-not-served");
        assertThat(first.get("id")).isEqualTo("c1");
        final List<String> calls = keepass.calls();
        keepass.running(true);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--id",
                                "c1",
                                "--json"))
                .as(registry.err.toString())
                .isZero();

        final Map<?, ?> again = registry.answer();
        assertThat(again.get("fingerprint")).isEqualTo(first.get("fingerprint"));
        assertThat(again.get("entry")).as("the first run's entry").isEqualTo("ssh/alice");
        assertThat(keepass.calls()).as("keepassxc-cli was not asked again").isEqualTo(calls);
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("ssh/alice"));
    }

    /** A new id is a new key, and an entry that exists is what KeePassXC refuses it. */
    @Test
    void an_entry_that_exists_under_a_new_id_is_refused_with_no_key_handed_over() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);
        final String[] asked = {
            "--keepassxc", keepass.database.toString(), "--entry", "ssh/alice", "--json", "--id"
        };
        keepass.running(false);
        final List<String> first = new ArrayList<>(List.of(asked));
        first.add("c1");
        assertThat(create(keepass.store(Duration.ofMillis(100)), first.toArray(String[]::new)))
                .isEqualTo(65);
        final List<String> second = new ArrayList<>(List.of(asked));
        second.add("c2");

        assertThat(create(keepass.store(Duration.ofMillis(100)), second.toArray(String[]::new)))
                .isEqualTo(65);

        assertThat(registry.answer().get("reason")).isEqualTo("key-store-refused");
        final List<String> expected = new ArrayList<>(keepass.madeInto("ssh/alice"));
        expected.add("add " + keepass.database + " ssh/alice");
        assertThat(keepass.calls()).isEqualTo(expected);
        assertThat(cardFile()).doesNotExist();
    }

    /** Another entry under the id that made the key would answer for a key it does not hold. */
    @Test
    void another_entry_under_the_id_that_made_a_key_is_refused() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);
        keepass.running(false);
        final String db = keepass.database.toString();
        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                db,
                                "--entry",
                                "one",
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(65);
        final List<String> calls = keepass.calls();

        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                db,
                                "--entry",
                                "two",
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(64);
        assertThat(keepass.calls()).isEqualTo(calls);
    }

    /** A key made for one change is no other's to take. */
    @Test
    void a_rotation_under_an_id_whose_key_a_first_card_waits_for_does_not_take_it()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);
        keepass.running(false);
        final Path note =
                IdentityOperation.directory(registry.context())
                        .resolveSibling("keepassxc")
                        .resolve("c1");

        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(65);
        final List<String> lines = Files.readAllLines(note);
        assertThat(lines).hasSize(3).last().isEqualTo("create");
        Files.write(note, List.of(lines.get(0), lines.get(1), "rotate"));

        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(64);
        assertThat(registry.answer().toString()).contains("another change");
    }

    /** Two runs with no id must each keep their own key to retry. */
    @Test
    void a_second_run_with_no_id_does_not_take_the_note_of_the_first() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);
        keepass.running(false);
        final String db = keepass.database.toString();
        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                db,
                                "--entry",
                                "one",
                                "--json"))
                .isEqualTo(65);
        final Map<?, ?> first = registry.answer();

        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                db,
                                "--entry",
                                "two",
                                "--json"))
                .isEqualTo(65);

        assertThat(registry.answer().get("id")).isNotEqualTo(first.get("id"));
        assertThat(
                        create(
                                keepass.store(Duration.ofMillis(100)),
                                "--keepassxc",
                                db,
                                "--id",
                                (String) first.get("id"),
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("fingerprint")).isEqualTo(first.get("fingerprint"));
        assertThat(registry.answer().get("entry")).isEqualTo("one");
    }

    /**
     * The one guard that a private key never sits in a file, even for a moment: the JVM's own
     * record of each file written while the key is made, so a file deleted at once still shows.
     */
    private List<String> filesWrittenDuring(final org.junit.jupiter.api.function.Executable made)
            throws Throwable {
        final Path recorded = dir.resolve("writes.jfr");
        try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
            recording.start();
            made.execute();
            recording.stop();
            recording.dump(recorded);
        }
        final List<String> paths = new ArrayList<>();
        for (final jdk.jfr.consumer.RecordedEvent event :
                jdk.jfr.consumer.RecordingFile.readAllEvents(recorded)) {
            final String path = event.getString("path");
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** What may be written while a key is made: nothing that could hold it but the agent's way. */
    private List<String> wroteOutside(final List<String> paths) {
        final String state = dir.resolve("state").toString();
        final String config = dir.resolve("config").toString();
        return paths.stream()
                .filter(path -> !path.startsWith(state + "/porelo/identity/"))
                .filter(path -> !path.startsWith(state + "/identity/"))
                .filter(path -> !path.startsWith(config + "/porelo/identity/"))
                .filter(path -> !path.equals(dir.resolve("keepassxc/terminal").toString()))
                .toList();
    }

    @Test
    void no_file_is_written_while_a_key_is_made_not_even_one_deleted_at_once() throws Throwable {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        final List<String> written =
                filesWrittenDuring(
                        () ->
                                assertThat(
                                                create(
                                                        keepass.store(Duration.ofSeconds(10)),
                                                        "--keepassxc",
                                                        keepass.database.toString(),
                                                        "--entry",
                                                        "alice",
                                                        "--json"))
                                        .as(registry.err.toString())
                                        .isZero());

        assertThat(wroteOutside(written)).isEmpty();
    }

    /** The guard itself: a key written to a temporary file and deleted is seen. */
    @Test
    void a_key_written_to_a_temporary_file_and_deleted_is_caught() throws Throwable {
        final List<String> written =
                filesWrittenDuring(
                        () -> {
                            final Path file = Files.createTempFile("key", ".tmp");
                            Files.writeString(file, "-----BEGIN OPENSSH PRIVATE KEY-----\n");
                            Files.delete(file);
                        });

        assertThat(wroteOutside(written)).hasSize(1).first().asString().endsWith(".tmp");
    }

    private String refusedAttachment(final KeePassXcStandIn.Mode mode) throws Exception {
        final KeePassXcStandIn keepass = standIn(mode);
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
        assertThat(keepass.calls()).contains("rm " + keepass.database + " alice").hasSize(3);
        assertThat(cardFile()).doesNotExist();
        if (mode != KeePassXcStandIn.Mode.REFUSES_KEY_AND_REMOVAL) {
            assertThat(keepass.entries()).as("no orphan entry").isEmpty();
        }
        return registry.answer().toString();
    }

    /** No orphan: the entry goes, and the answer says the key stays in the bin. */
    @Test
    void a_refused_attachment_takes_the_entry_to_the_bin_and_says_what_stays() throws Exception {
        assertThat(refusedAttachment(KeePassXcStandIn.Mode.REFUSES_KEY))
                .contains("recycle bin", "private key stays");
    }

    @Test
    void a_refused_attachment_in_a_database_with_no_bin_says_removed() throws Exception {
        assertThat(refusedAttachment(KeePassXcStandIn.Mode.REFUSES_KEY_NO_BIN))
                .contains("it was removed")
                .doesNotContain("recycle bin");
    }

    @Test
    void a_refused_attachment_whose_entry_stays_says_to_remove_it() throws Exception {
        assertThat(refusedAttachment(KeePassXcStandIn.Mode.REFUSES_KEY_AND_REMOVAL))
                .contains("not removed: remove alice in KeePassXC");
    }

    /** A person who never types is told so, not that KeePassXC is missing. */
    @Test
    void a_password_not_typed_in_time_is_no_password_and_nothing_is_kept() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.NEVER_TYPED);

        assertThat(
                        create(
                                keepass.typing(Duration.ofSeconds(1)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--id",
                                "c1",
                                "--json"))
                .isEqualTo(64);

        assertThat(registry.answer().get("reason")).isEqualTo("no-password");
        assertThat(keepass.calls()).hasSize(1);
        assertThat(cardFile()).doesNotExist();
        assertThat(served()).isEmpty();
        assertThat(
                        IdentityOperation.directory(registry.context())
                                .resolveSibling("keepassxc")
                                .resolve("c1"))
                .doesNotExist();
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
        assertThat((String) answer.get("words"))
                .as("KeePassXC was locked and the database's unlock view opened; it is said")
                .contains("unlock")
                .contains("--key " + answer.get("fingerprint"));
        assertThat(keepass.guiCalls())
                .as("KeePassXC is locked and asked to show the database, nothing else")
                .containsExactly("--lock", keepass.database.toString());
        assertThat(keepass.calls()).as("the entry is kept").isEqualTo(keepass.madeInto("alice"));
        assertThat(keepass.seen())
                .as("KeePassXC got an OpenSSH private key")
                .contains("-----BEGIN OPENSSH PRIVATE KEY-----");
        assertThat(cardFile()).doesNotExist();
        assertThat(cardFile().getParent()).doesNotExist();
    }

    /** A person's agent often holds other keys too, of other kinds; the new one is still found. */
    @Test
    void a_key_made_while_the_agent_also_holds_an_rsa_key_is_found() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);
        final Path rsa = dir.resolve("other_rsa");
        final String socket = registry.environment().apply("SSH_AUTH_SOCK");
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "rsa",
                                        "-b",
                                        "2048",
                                        "-N",
                                        "",
                                        "-f",
                                        rsa.toString())
                                .redirectErrorStream(true)
                                .start()
                                .waitFor())
                .isZero();
        final ProcessBuilder add = new ProcessBuilder("ssh-add", "-q", rsa.toString());
        add.environment().put("SSH_AUTH_SOCK", socket);
        assertThat(add.redirectErrorStream(true).start().waitFor()).isZero();

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.answer().get("agent")).isEqualTo("holds");
        assertThat(cardFile()).exists();
    }

    /**
     * Without --json the entry is named too, since only then does the person know where it went.
     */
    @Test
    void a_key_made_without_json_names_its_entry() throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(10)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "ssh/alice"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.out.toString()).contains("entry ssh/alice");
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
        assertThat(keepass.guiCalls())
                .as("a key served within the grace never locks KeePassXC")
                .isEmpty();
    }

    /**
     * KeePassXC loads an entry added while its database is open only once the database is locked
     * and unlocked (measured with KeePassXC 2.7.10): Porelo locks it, opens the database's unlock
     * view, says so, and waits, so the person unlocks it in KeePassXC's window and nothing else.
     */
    @Test
    void a_key_kept_while_keepassxc_runs_is_served_once_porelo_locks_it_and_it_is_unlocked()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.SERVES_AFTER_UNLOCK);

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(20)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice",
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(served()).containsExactly((String) registry.answer().get("key"));
        assertThat(cardFile()).exists();
        assertThat(keepass.guiCalls()).containsExactly("--lock", keepass.database.toString());
        assertThat(Files.readString(keepass.terminal))
                .as("the person is told what happened and what to do, on the terminal")
                .contains("locked")
                .contains("unlock " + keepass.database);
        assertThat(keepass.holding((String) registry.answer().get("key"))).isEmpty();
    }

    /**
     * KeePassXC not running: Porelo starts no window of its own, which would hold this command
     * until it is closed, and says to open the database in KeePassXC.
     */
    @Test
    void keepassxc_not_running_is_never_started_and_the_refusal_says_to_open_the_database()
            throws Exception {
        final KeePassXcStandIn keepass = standIn(KeePassXcStandIn.Mode.HOLDS);
        keepass.running(false);
        final long started = System.nanoTime();

        assertThat(
                        create(
                                keepass.store(Duration.ofSeconds(30)),
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice",
                                "--json"))
                .isEqualTo(65);
        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("reason")).isEqualTo("key-not-served");
        assertThat((String) answer.get("words"))
                .contains("not running")
                .contains("--key " + answer.get("fingerprint"));
        assertThat(keepass.guiCalls()).as("no window is started").containsExactly("--lock");
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as(
                        "nobody can unlock a database no KeePassXC has open: it is said at once, not after the wait")
                .isLessThan(Duration.ofSeconds(15));
        assertThat((String) answer.get("words")).doesNotContain("within");
        assertThat(cardFile()).doesNotExist();
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
                        dir.resolve("nowhere-either").toString(),
                        Duration.ofSeconds(1),
                        Path.of("/dev/null"),
                        keepass.terminal,
                        KeePassXcKey.TYPING,
                        false);

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
