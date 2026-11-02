package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards the backup key: made once into a file, filed beside the key it backs, used for that one
 * change.
 */
// Every test drives git and an ssh-agent, either of which can wait for ever on a mistake.
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class IdentityBackupKeyCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private TestOrganization organization;

    /** What the verb ran on the terminal. */
    private final List<List<String>> ran = new ArrayList<>();

    private boolean terminal = true;

    @BeforeEach
    void founded() throws Exception {
        registry = new RegistryCheckout(dir);
        organization = new TestOrganization(registry);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    /**
     * Runs {@code porelo identity backup-key ...}; ssh-keygen gets an empty passphrase, as no
     * person types one.
     */
    private int backupKey(final String... args) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final CommandLine cli =
                PoreloCli.commandLine(
                        new PoreloContext(
                                registry.runner,
                                registry.paths,
                                command -> {
                                    ran.add(command);
                                    final List<String> keygen = new ArrayList<>(command);
                                    keygen.addAll(List.of("-N", ""));
                                    return registry.runner.run(Command.of(keygen)).exitCode();
                                },
                                registry.environment()));
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        IdentityRun.wire(cli, organization.opener(), null);
        ((IdentityBackupKeyCommand)
                                cli.getSubcommands()
                                        .get("identity")
                                        .getSubcommands()
                                        .get("backup-key")
                                        .getCommand())
                        .terminal =
                () -> terminal;
        final List<String> all = new ArrayList<>(List.of("identity", "backup-key"));
        all.addAll(List.of(args));
        return cli.execute(all.toArray(String[]::new));
    }

    /**
     * Copies the key file with its private part's check value changed, which ssh-keygen reads as a
     * wrong passphrase, without asking one on a terminal.
     */
    static Path misread(final Path file, final Path copy) throws Exception {
        final List<String> lines = Files.readAllLines(file);
        final byte[] key =
                Base64.getDecoder().decode(String.join("", lines.subList(1, lines.size() - 1)));
        final ByteBuffer at = ByteBuffer.wrap(key);
        at.position("openssh-key-v1\0".length());
        for (int each = 0; each < 5; each++) {
            // cipher, kdf, kdf options, the key count, the public key; then the private part's
            // length
            if (each == 3) {
                at.getInt();
            } else {
                at.position(at.position() + 4 + at.getInt());
            }
        }
        at.getInt();
        key[at.position()] ^= 1;
        return Files.writeString(
                copy,
                lines.getFirst()
                        + "\n"
                        + Base64.getEncoder().encodeToString(key)
                        + "\n"
                        + lines.getLast()
                        + "\n");
    }

    private Map<?, ?> answer() {
        return registry.answer();
    }

    private String err() {
        return registry.err.toString();
    }

    /** michael's first card accepted and kept here, as an owner's is. */
    private void owner() throws Exception {
        new IdentityStore(registry.context()).keep(organization.filed("michael"));
        registry.agent("michael");
    }

    /** michael has filed the key in a file of the test's own as his backup. */
    private Path backup() throws Exception {
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--organization", "--out", file.toString(), "--json"))
                .as(err())
                .isZero();
        return file;
    }

    /** A new card of michael's, on a key his agent serves and the forge lists. */
    private Path nextCard() throws Exception {
        organization.listed("michael", "alice-new");
        registry.close();
        registry.agent("alice-new");
        return Files.writeString(dir.resolve("next.card"), registry.card("michael", "alice-new"));
    }

    /** Why the verb is here: the owner's place with a second key that no daily agent holds. */
    @Test
    void an_owner_makes_a_backup_key_into_a_file_filed_beside_their_key() throws Exception {
        owner();
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--organization", "--out", file.toString(), "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("scope"))
                .isEqualTo(Map.of("kind", "organization", "name", TestOrganization.ADDRESS));
        assertThat(answer.get("file")).isEqualTo(file.toString());
        assertThat(answer.get("pushed")).isEqualTo(true);
        assertThat(answer.get("again")).isEqualTo(false);
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        assertThat(ran)
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .startsWith("ssh-keygen")
                .doesNotContain("-N");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(dir.resolve("backup.pub"))
                .as("only the file is the person's to keep")
                .doesNotExist();
        assertThat(organization.owners())
                .contains(
                        org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file)));
        assertThat(organization.signer("HEAD")).isEqualTo("michael");

        assertThat(backupKey("--organization", "--out", dir.resolve("again").toString(), "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(dir.resolve("again")).doesNotExist();
    }

    /** A host's admin holds a place of their own, which goes by the same verb. */
    @Test
    void a_host_admin_files_a_backup_key_for_their_host() throws Exception {
        organization.filed("michael");
        new IdentityStore(registry.context()).keep(organization.filed("hank"));
        organization
                .writes()
                .addHost(organization.change("michael", "michael", "h1"), "wharf6", "hank");
        registry.agent("hank");

        assertThat(
                        backupKey(
                                "--host",
                                "wharf6",
                                "--out",
                                dir.resolve("backup").toString(),
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("scope")).isEqualTo(Map.of("kind", "host", "name", "wharf6"));
        assertThat(organization.signer("HEAD")).isEqualTo("hank");
    }

    /** No key file is left behind for a place the person does not hold, or a host not there. */
    @Test
    void nobody_who_does_not_hold_the_place_and_nothing_over_a_file_or_without_a_terminal()
            throws Exception {
        new IdentityStore(registry.context()).keep(organization.filed("alice"));
        registry.agent("alice");
        assertThat(backupKey("--organization", "--out", dir.resolve("a").toString(), "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(backupKey("--host", "wharf6", "--out", dir.resolve("a").toString(), "--json"))
                .isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("not-found");

        registry.close();
        owner();
        final Path taken = Files.writeString(dir.resolve("taken"), "mine");
        assertThat(backupKey("--organization", "--out", taken.toString(), "--json")).isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("file-exists");
        assertThat(Files.readString(taken)).isEqualTo("mine");

        terminal = false;
        assertThat(backupKey("--organization", "--out", dir.resolve("b").toString(), "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("no-terminal");
        assertThat(ran).isEmpty();
        assertThat(backupKey("--organization", "--json")).isEqualTo(64);
        assertThat(backupKey("--organization", "--host", "wharf6", "--out", "x")).isEqualTo(64);
    }

    /** The word the old registry had is gone from the verb, and with it its projects. */
    @Test
    void the_old_scopes_are_wrong_use() throws Exception {
        owner();
        assertThat(backupKey("--registry", "--out", dir.resolve("a").toString())).isEqualTo(64);
        assertThat(backupKey("--project", "demo", "--out", dir.resolve("a").toString()))
                .isEqualTo(64);
        assertThat(dir.resolve("a")).doesNotExist();
    }

    /** A person who could not sign would hold a key that backs nothing. */
    @Test
    void a_key_the_agent_does_not_serve_makes_no_backup_key() throws Exception {
        new IdentityStore(registry.context()).keep(organization.filed("michael"));
        registry.agent("alice");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--organization", "--out", file.toString(), "--json")).isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("no-key");
        assertThat(ran).isEmpty();
        assertThat(file).doesNotExist();
    }

    /** A revoked key may hand nothing on, and a backup key made by it would back nothing. */
    @Test
    void an_owner_whose_own_key_the_revocation_list_names_makes_no_backup_key() throws Exception {
        owner();
        IdentityRun.revoke(registry, "michael");

        assertThat(backupKey("--organization", "--out", dir.resolve("a").toString(), "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("revoked");
        assertThat(ran).isEmpty();
    }

    /** Someone else's public half is theirs, whichever of the two names is taken. */
    @Test
    void a_public_half_already_there_is_neither_overwritten_nor_removed() throws Exception {
        owner();
        final Path pub = Files.writeString(dir.resolve("backup.pub"), "mine");

        assertThat(backupKey("--organization", "--out", dir.resolve("backup").toString(), "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("file-exists");
        assertThat(Files.readString(pub)).isEqualTo("mine");
    }

    /** A key the store refused backs nothing: a file left would be taken for a backup. */
    @Test
    void a_refused_make_keeps_no_key_file() throws Exception {
        owner();
        final String base = organization.tip();
        organization.filed("alice");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--organization", "--out", file.toString(), "--base", base, "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("moved-on");
        assertThat(ran).as("the key was made").hasSize(1);
        assertThat(file).as("a key never filed is not kept").doesNotExist();
        assertThat(Path.of(file + ".pub")).doesNotExist();
    }

    /** The store lost while it filed may have taken the key: its file must stay. */
    @Test
    void a_key_whose_filing_lost_the_store_is_kept_and_the_same_id_files_it() throws Exception {
        owner();
        final Path file = dir.resolve("backup");
        organization.unreachableForChanges = true;

        assertThat(backupKey("--organization", "--out", file.toString(), "--id", "b1", "--json"))
                .isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(file).exists();
        final String made = Files.readString(file);

        organization.unreachableForChanges = false;
        assertThat(backupKey("--organization", "--out", file.toString(), "--id", "b1", "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("again")).isEqualTo(false);
        assertThat(ran).as("the same key, not a second").hasSize(1);
        assertThat(Files.readString(file)).isEqualTo(made);
        assertThat(organization.owners())
                .contains(org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(made));
    }

    /** The id asked again answers the change it made, the store having moved on or not. */
    @Test
    void the_same_id_answers_the_same_change_and_makes_no_second_key() throws Exception {
        owner();
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--organization", "--out", file.toString(), "--id", "b1", "--json"))
                .as(err())
                .isZero();
        final Object revision = answer().get("revision");
        organization.filed("alice");
        final String moved = organization.tip();

        assertThat(backupKey("--organization", "--out", file.toString(), "--id", "b1", "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(ran).hasSize(1);
        assertThat(organization.tip()).isEqualTo(moved);
    }

    /**
     * The backup's whole use: it names the new key in the owner's place, and that key signs the
     * card, so the lost key signs nothing further; other owners' entries stay as they are.
     */
    @Test
    void the_backup_key_replaces_the_key_it_backs_and_nothing_else() throws Exception {
        owner();
        final Path file = backup();
        final Path next = nextCard();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("replaced")).isEqualTo(registry.pin("michael"));
        assertThat(answer.get("by")).isEqualTo(registry.pin("alice-new"));
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        assertThat(organization.owners())
                .contains(registry.key("alice-new"))
                .contains(backupKey)
                .doesNotContain(registry.key("michael"));
        assertThat(PersonCard.read(organization.card("michael"), "filed").fingerprint())
                .isEqualTo(registry.pin("alice-new"));
        assertThat(organization.signer("HEAD")).isEqualTo("alice-new");
    }

    /** A person cut off from a passphrase prompt asks again, and no id of theirs is needed. */
    @Test
    void the_backup_key_used_with_no_id_makes_one_and_with_an_id_answers_the_same_change()
            throws Exception {
        owner();
        final Path file = backup();
        final Path next = nextCard();

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--json"))
                .as(err())
                .isZero();
        final Object id = answer().get("id");
        final Object revision = answer().get("revision");

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                (String) id,
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(answer().get("replaced"))
                .as("what the change replaced, not what stands since")
                .isEqualTo(registry.pin("michael"));
    }

    /** Any other key names nothing in the owner's place: the store and the verb both say no. */
    @Test
    void a_file_that_is_not_the_backup_replaces_nothing() throws Exception {
        owner();
        backup();
        final String before = organization.tip();
        final Path next = nextCard();

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                registry.keyFiles.get("bob").toString(),
                                "--card",
                                next.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("not-the-backup");

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                registry.keyFiles.get("bob").toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(organization.tip()).isEqualTo(before);
    }

    /** A card of another person, or one not signed as a card must be, replaces nothing. */
    @Test
    void a_card_of_another_login_or_signed_wrongly_replaces_nothing() throws Exception {
        owner();
        final Path file = backup();
        final String before = organization.tip();
        final Path others =
                Files.writeString(dir.resolve("others.card"), registry.card("alice", "alice-new"));

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                others.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("card-login");

        final Path forged =
                Files.writeString(
                        dir.resolve("forged.card"),
                        registry.card("michael", "alice-new").replace("name: ", "name: x"));
        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                forged.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("card-login");
        assertThat(organization.tip()).isEqualTo(before);
    }

    /** A revoked key, the backup's or the new card's, is signed for by nobody. */
    @Test
    void a_key_the_revocation_list_names_replaces_nothing() throws Exception {
        owner();
        final Path file = backup();
        final Path next = nextCard();
        final String before = organization.tip();
        IdentityRun.revoke(registry, "alice-new");

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("revoked");
        assertThat(organization.tip()).isEqualTo(before);
    }

    /**
     * The grave one: a person told their other file is the backup keeps that file and loses the
     * real one, and on the day it counts neither replaces anything. The id answers what it made.
     */
    @Test
    void the_same_id_with_another_key_file_claims_nothing_and_leaves_the_file() throws Exception {
        owner();
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--organization", "--out", file.toString(), "--id", "b1", "--json"))
                .as(err())
                .isZero();
        final String tip = organization.tip();
        final Path other = registry.keyFiles.get("bob");
        final String held = Files.readString(other);

        assertThat(backupKey("--organization", "--out", other.toString(), "--id", "b1", "--json"))
                .as(registry.out.toString())
                .isEqualTo(64);

        assertThat(answer().get("reason")).isEqualTo("usage");
        assertThat(answer().get("id")).isEqualTo("b1");
        assertThat(answer().get("fingerprint")).as("no claim about that key").isNull();
        assertThat(organization.owners()).doesNotContain(registry.key("bob"));
        assertThat(organization.tip()).isEqualTo(tip);
        assertThat(Files.readString(other)).isEqualTo(held);
    }

    /** The same for a replacement: the id made one card stand, and another is not claimed. */
    @Test
    void the_same_id_with_another_card_claims_no_replacement() throws Exception {
        owner();
        final Path file = backup();
        final Path next = nextCard();
        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .as(err())
                .isZero();
        final Path other =
                Files.writeString(dir.resolve("other.card"), registry.card("michael", "bob"));

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                other.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .as(registry.out.toString())
                .isEqualTo(64);

        assertThat(answer().get("reason")).isEqualTo("usage");
        assertThat(answer().get("by")).isNull();
    }

    /** An id another key made a change under is no change of this backup key's to answer. */
    @Test
    void an_id_answers_no_replacement_another_key_than_the_backup_key_signed() throws Exception {
        owner();
        final Path file = backup();
        organization.listed("michael", "alice-new");
        final String card = registry.card("michael", "alice-new", "michael");
        organization
                .writes()
                .changeCard(
                        new org.fuin.porelo.organization.OrganizationWrites.Change(
                                new org.fuin.porelo.organization.OrganizationWrites.Acting(
                                        "michael", registry.key("alice-new")),
                                organization.tip(),
                                "lost-1"),
                        card);
        final String byOwner = organization.tip();
        final Path next =
                Files.writeString(dir.resolve("new.card"), registry.card("michael", "alice-new"));
        registry.close();
        registry.agent("alice-new");

        final int code =
                backupKey(
                        "--organization",
                        "--use",
                        file.toString(),
                        "--card",
                        next.toString(),
                        "--id",
                        "lost-1",
                        "--json");

        assertThat(code)
                .as("lost-1 was signed by michael's own keys, not the backup key: " + registry.out)
                .isEqualTo(65);
        assertThat(organization.tip()).as("nothing is committed").isEqualTo(byOwner);
    }

    /** The store lost, the file kept, "ask again with the same id": which, if none was given? */
    @Test
    void a_made_id_is_named_in_the_refusal_and_the_retry_can_use_it() throws Exception {
        owner();
        final Path file = dir.resolve("backup");
        organization.unreachableForChanges = true;

        assertThat(backupKey("--organization", "--out", file.toString(), "--json")).isEqualTo(69);

        final String id = (String) answer().get("id");
        assertThat(id).as("named, so a retry can name it").isNotNull();
        assertThat(err()).contains(id);
        organization.unreachableForChanges = false;

        assertThat(backupKey("--organization", "--out", file.toString(), "--id", id, "--json"))
                .as(err())
                .isZero();
        assertThat(answer().get("id")).isEqualTo(id);
        assertThat(ran).as("the same key").hasSize(1);
    }

    /** Named in a plain refusal too, for it is the same answer whatever the reason. */
    @Test
    void a_made_id_is_named_when_the_store_refuses() throws Exception {
        owner();
        final String base = organization.tip();
        organization.filed("alice");

        assertThat(
                        backupKey(
                                "--organization",
                                "--out",
                                dir.resolve("backup").toString(),
                                "--base",
                                base,
                                "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("moved-on");
        assertThat((String) answer().get("id")).startsWith("bk-");
    }

    /** A file that does not open says that, so the person types it again, not that it is wrong. */
    @Test
    void a_backup_key_file_that_will_not_open_is_wrong_passphrase() throws Exception {
        owner();
        final Path file = backup();
        final Path next = nextCard();
        final Path misread = misread(file, dir.resolve("misread"));
        Files.setPosixFilePermissions(misread, PosixFilePermissions.fromString("rw-------"));

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                misread.toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("wrong-passphrase");
    }

    /** The new key signs the card, so an agent that does not serve it gets no change made. */
    @Test
    void a_new_key_the_agent_does_not_serve_is_no_key_and_nothing_stands() throws Exception {
        owner();
        final Path file = backup();
        final String before = organization.tip();
        final Path next = nextCard();
        registry.close();
        registry.agent("bob");

        assertThat(
                        backupKey(
                                "--organization",
                                "--use",
                                file.toString(),
                                "--card",
                                next.toString(),
                                "--id",
                                "u1",
                                "--json"))
                .isEqualTo(69);
        assertThat(answer().get("reason"))
                .as("the file signed; the agent lacks the new key, so a passphrase is not why")
                .isEqualTo("no-key");
        assertThat(organization.tip()).isEqualTo(before);
    }
}
