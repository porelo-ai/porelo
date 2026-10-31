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

    /** What the verb ran on the terminal. */
    private final List<List<String>> ran = new ArrayList<>();

    private boolean terminal = true;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
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
                        registry.context(
                                command -> {
                                    ran.add(command);
                                    final List<String> keygen = new ArrayList<>(command);
                                    keygen.addAll(List.of("-N", ""));
                                    return registry.runner.run(Command.of(keygen)).exitCode();
                                }));
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        final IdentityBackupKeyCommand verb =
                (IdentityBackupKeyCommand)
                        cli.getSubcommands()
                                .get("identity")
                                .getSubcommands()
                                .get("backup-key")
                                .getCommand();
        verb.forges = (address, token) -> registry.forge;
        verb.terminal = () -> terminal;
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

    private Map<?, ?> read() {
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        return registry.answer();
    }

    @Test
    void the_one_owner_makes_a_backup_key_into_a_file_filed_beside_their_key() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--registry", "--out", file.toString(), "--json"))
                .as(registry.err.toString())
                .isZero();
        final Map<?, ?> answer = registry.answer();
        final Map<String, Object> registryScope = new java.util.HashMap<>();
        registryScope.put("kind", "registry");
        registryScope.put("name", null);
        assertThat(answer.get("scope")).isEqualTo(registryScope);
        assertThat(answer.get("file")).isEqualTo(file.toString());
        assertThat(answer.get("pushed")).isEqualTo(false);
        assertThat(answer.get("revision")).isEqualTo(registry.head());
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

        final Map<?, ?> read = read();
        assertThat(read.get("owners"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains(
                        Map.of(
                                "login",
                                "michael",
                                "fingerprint",
                                answer.get("fingerprint"),
                                "backup",
                                true));
        assertThat(read.get("warnings"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(registry.soleWarnings().getLast());

        assertThat(backupKey("--registry", "--out", dir.resolve("again").toString(), "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("backup-exists");
        assertThat(dir.resolve("again")).doesNotExist();
    }

    @Test
    void a_host_admins_backup_key_is_filed_by_an_owner() throws Exception {
        IdentityRun.keep(registry, registry.card("hank", "hank"));
        registry.agent("michael");
        assertThat(
                        backupKey(
                                "--host",
                                "walk6m",
                                "--out",
                                dir.resolve("backup").toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.answer().get("scope"))
                .isEqualTo(Map.of("kind", "host", "name", "walk6m"));
        assertThat(read().get("warnings"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(registry.soleWarnings().getFirst());
    }

    @Test
    void nobody_but_the_one_owner_and_nothing_without_a_terminal_or_over_a_file() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        registry.agent("michael");
        assertThat(backupKey("--registry", "--out", dir.resolve("a").toString(), "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("not-sole");

        IdentityRun.keep(registry, registry.card("michael", "michael"));
        final Path taken = Files.writeString(dir.resolve("taken"), "mine");
        assertThat(backupKey("--registry", "--out", taken.toString(), "--json")).isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("file-exists");
        assertThat(Files.readString(taken)).isEqualTo("mine");

        terminal = false;
        assertThat(backupKey("--registry", "--out", dir.resolve("b").toString(), "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("no-terminal");
        assertThat(ran).isEmpty();
        assertThat(backupKey("--registry", "--json")).isEqualTo(64);
        assertThat(backupKey("--registry", "--host", "walk6m", "--out", "x")).isEqualTo(64);
    }

    @Test
    void the_backup_key_replaces_the_key_it_backs_and_nothing_else() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--registry", "--out", file.toString())).isZero();
        assertThat(read().get("warnings")).isNotNull();
        registry.close();
        final Path card =
                Files.writeString(dir.resolve("new.card"), registry.card("michael", "alice-new"));

        assertThat(
                        backupKey(
                                "--registry",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-1",
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("replaced")).isEqualTo(registry.pin("michael"));
        assertThat(answer.get("by")).isEqualTo(registry.pin("alice-new"));
        final Map<?, ?> read = read();
        assertThat(((List<?>) read.get("owners")).getFirst())
                .isEqualTo(
                        Map.of(
                                "login",
                                "michael",
                                "fingerprint",
                                registry.pin("alice-new"),
                                "backup",
                                false));
        assertThat(read.get("refused_commit")).isNull();

        final Path other = dir.resolve("other");
        registry.runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", other.toString()));
        assertThat(
                        backupKey(
                                "--registry",
                                "--use",
                                other.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-2",
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("not-the-backup");
        final Path locked = misread(file, dir.resolve("locked"));
        final Path newer =
                Files.writeString(dir.resolve("newer.card"), registry.card("michael", "bob"));
        assertThat(
                        backupKey(
                                "--registry",
                                "--use",
                                locked.toString(),
                                "--card",
                                newer.toString(),
                                "--id",
                                "lost-3",
                                "--json"))
                .as("a file whose passphrase is not given does not sign")
                .isEqualTo(65);
        final Map<?, ?> misread = registry.answer();
        assertThat(misread.get("reason")).as(misread.toString()).isEqualTo("wrong-passphrase");
        assertThat(backupKey("--registry", "--use", file.toString(), "--card", card.toString()))
                .as("--use names its change")
                .isEqualTo(64);

        // Signed by the backup key itself, outside the verb: it makes itself michael's own key.
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        registry.write(
                "owners.yml",
                "owners:\n  - login: michael\n    key: \""
                        + backupKey
                        + "\"\nhost_admins:\n  walk6m:\n    - login: hank\n      key: \""
                        + registry.key("hank")
                        + "\"\n");
        registry.keyFiles.put("backup", file);
        final String promoted = registry.commit("backup");
        final Map<?, ?> refused = read();
        assertThat(refused.get("owners"))
                .as("the backup key changes nothing but the key it backs")
                .isEqualTo(read.get("owners"));
        assertThat(((Map<?, ?>) refused.get("refused_commit")).get("commit")).isEqualTo(promoted);
    }

    @Test
    void a_key_the_revocation_list_names_replaces_nothing() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--registry", "--out", file.toString())).isZero();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        registry.close();
        final String before = registry.head();
        final Path card =
                Files.writeString(dir.resolve("new.card"), registry.card("michael", "alice-new"));
        IdentityRun.revoke(registry, "alice-new");
        assertThat(
                        backupKey(
                                "--registry",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-1",
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason"))
                .as("the new card's key is revoked here")
                .isEqualTo("revoked");

        final Path pub = dir.resolve("backup-public");
        Files.writeString(
                Path.of(pub + ".pub"),
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file)) + "\n");
        registry.keyFiles.put("backup-public", pub);
        IdentityRun.revoke(registry, "backup-public");
        assertThat(
                        backupKey(
                                "--registry",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-2",
                                "--json"))
                .isEqualTo(65);
        assertThat(registry.answer().get("reason"))
                .as("the backup key is revoked here")
                .isEqualTo("revoked");
        assertThat(registry.head()).as("nothing was filed").isEqualTo(before);
    }

    @Test
    void a_trust_record_behind_the_checkout_makes_no_second_backup_key() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        assertThat(backupKey("--registry", "--out", dir.resolve("backup").toString())).isZero();
        final String filed = registry.head();
        final String owners = registry.git("show", "HEAD:owners.yml");

        final Path again = dir.resolve("again");
        final int code =
                backupKey("--registry", "--out", again.toString(), "--base", filed, "--json");

        assertThat(code).as("judged on the checkout, not on the last read").isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("backup-exists");
        assertThat(again).doesNotExist();
        assertThat(registry.head()).isEqualTo(filed);
        assertThat(registry.git("show", "HEAD:owners.yml")).isEqualTo(owners);
    }

    @Test
    void the_same_id_answers_the_same_change_before_and_after_a_read() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--registry", "--out", file.toString(), "--id", "bk-1", "--json"))
                .as(registry.err.toString())
                .isZero();
        final Map<?, ?> made = registry.answer();
        final Path again = dir.resolve("again");
        for (String when : List.of("before a read", "after a read")) {
            ran.clear();
            assertThat(backupKey("--registry", "--out", again.toString(), "--id", "bk-1", "--json"))
                    .as(when + ": " + registry.err)
                    .isZero();
            final Map<?, ?> answer = registry.answer();
            assertThat(answer.get("revision")).as(when).isEqualTo(made.get("revision"));
            assertThat(answer.get("fingerprint")).as(when).isEqualTo(made.get("fingerprint"));
            assertThat(ran).as("%s: no second key is made", when).isEmpty();
            assertThat(again).doesNotExist();
            assertThat(registry.registry("read", registry.repo.toString())).isZero();
        }

        registry.close();
        final Path card =
                Files.writeString(dir.resolve("new.card"), registry.card("michael", "alice-new"));
        final String[] use = {
            "--registry",
            "--use",
            file.toString(),
            "--card",
            card.toString(),
            "--id",
            "lost-1",
            "--json"
        };
        assertThat(backupKey(use)).as(registry.err.toString()).isZero();
        final Map<?, ?> replaced = registry.answer();
        assertThat(replaced.get("replaced")).isEqualTo(registry.pin("michael"));
        for (String when : List.of("before a read", "after a read")) {
            assertThat(backupKey(use)).as(when + ": " + registry.err).isZero();
            assertThat(registry.answer()).as(when).isEqualTo(replaced);
            assertThat(registry.registry("read", registry.repo.toString())).isZero();
        }
    }

    @Test
    void an_id_answers_only_the_backup_key_it_filed_on_the_base_it_names() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        assertThat(backupKey("--registry", "--out", dir.resolve("r1").toString(), "--id", "bk-r"))
                .as(registry.err.toString())
                .isZero();
        final String filed = registry.head();
        final Path again = dir.resolve("r2");
        assertThat(
                        backupKey(
                                "--registry",
                                "--out",
                                again.toString(),
                                "--id",
                                "bk-r",
                                "--base",
                                filed,
                                "--json"))
                .as("bk-r was filed on the commit before " + filed + ", not on it")
                .isEqualTo(65);
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("hank", "hank"));
        assertThat(
                        backupKey(
                                "--host",
                                "walk6m",
                                "--out",
                                dir.resolve("h").toString(),
                                "--id",
                                "op-1"))
                .as(registry.err.toString())
                .isZero();
        IdentityRun.keep(registry, registry.card("michael", "michael"));

        assertThat(backupKey("--registry", "--out", again.toString(), "--id", "op-1", "--json"))
                .as("op-1 filed hank's key for walk6m, not michael's for the registry")
                .isEqualTo(65);
        assertThat(again).doesNotExist();
    }

    @Test
    void an_id_answers_only_the_key_it_replaced_on_the_base_it_names() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("r1");
        assertThat(backupKey("--registry", "--out", file.toString())).isZero();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("hank", "hank"));
        final Path host = dir.resolve("h");
        assertThat(backupKey("--host", "walk6m", "--out", host.toString())).isZero();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        registry.close();
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        final Path card =
                Files.writeString(dir.resolve("new.card"), registry.card("michael", "alice-new"));
        final String[] use = {
            "--registry", "--use", file.toString(), "--card", card.toString(), "--id", "lost-1"
        };
        assertThat(backupKey(use)).as(registry.err.toString()).isZero();
        final String replaced = registry.head();
        final List<String> moved = new ArrayList<>(List.of(use));
        moved.addAll(List.of("--base", replaced, "--json"));
        assertThat(backupKey(moved.toArray(String[]::new)))
                .as("lost-1 was filed on the commit before " + replaced + ", not on it")
                .isEqualTo(65);

        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("hank", "hank"));
        final Path hanks =
                Files.writeString(dir.resolve("hank-new.card"), registry.card("hank", "bob"));
        assertThat(
                        backupKey(
                                "--host",
                                "walk6m",
                                "--use",
                                host.toString(),
                                "--card",
                                hanks.toString(),
                                "--id",
                                "op-2"))
                .as(registry.err.toString())
                .isZero();
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        final int code =
                backupKey(
                        "--registry",
                        "--use",
                        file.toString(),
                        "--card",
                        card.toString(),
                        "--id",
                        "op-2",
                        "--json");

        assertThat(code)
                .as("op-2 replaced hank's key for walk6m, not michael's: " + registry.out)
                .isEqualTo(65);
    }

    @Test
    void a_checkout_whose_origin_moved_is_not_read_as_another_registry() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path records = registry.record().getParent();
        final List<Path> before;
        try (var listed = Files.list(records)) {
            before = listed.sorted().toList();
        }
        registry.git("remote", "set-url", "origin", "https://forge.example.org/other/registry.git");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--registry", "--out", file.toString(), "--json")).isEqualTo(65);

        try (var listed = Files.list(records)) {
            assertThat(listed.sorted().toList())
                    .as("no trust record is written for the other address")
                    .isEqualTo(before);
        }
        assertThat(file).doesNotExist();
        assertThat(ran).isEmpty();
    }

    @Test
    void a_read_the_history_refuses_ends_in_a_refusal() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        // A shallow checkout cannot prove its history, which the read refuses.
        Files.writeString(registry.repo.resolve(".git/shallow"), registry.head() + "\n");
        final Path file = dir.resolve("backup");

        final int code = backupKey("--registry", "--out", file.toString(), "--json");

        assertThat(code).as(registry.err.toString()).isEqualTo(65);
        assertThat(dir.resolve("state/porelo/failures.log"))
                .as("a refusal, not an unhandled failure")
                .doesNotExist();
        assertThat(file).doesNotExist();
    }

    @Test
    void an_owner_whose_own_key_the_revocation_list_names_makes_no_backup_key() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        IdentityRun.revoke(registry, "michael");
        final String before = registry.head();
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--registry", "--out", file.toString(), "--json")).isEqualTo(65);

        assertThat(registry.answer().get("reason")).isEqualTo("revoked");
        assertThat(ran).as("no key is made").isEmpty();
        assertThat(file).doesNotExist();
        assertThat(registry.head()).isEqualTo(before);
    }

    @Test
    void a_public_half_already_there_is_neither_overwritten_nor_removed() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final Path file = dir.resolve("backup");
        final Path pub = Files.writeString(dir.resolve("backup.pub"), "mine\n");

        assertThat(backupKey("--registry", "--out", file.toString(), "--json")).isEqualTo(65);

        assertThat(registry.answer().get("reason")).isEqualTo("file-exists");
        assertThat(ran).as("no key is made").isEmpty();
        assertThat(pub).hasContent("mine");
        assertThat(file).doesNotExist();
    }

    @Test
    void a_key_filed_before_the_checkout_refused_its_work_tree_is_kept() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("michael");
        final String before = registry.head();
        final Path owners = registry.repo.resolve("owners.yml");
        Files.setPosixFilePermissions(owners, PosixFilePermissions.fromString("r--r--r--"));
        final Path file = dir.resolve("backup");

        final int code = backupKey("--registry", "--out", file.toString(), "--json");

        Files.setPosixFilePermissions(owners, PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(code).as("the work tree's owners.yml could not be written").isNotZero();
        assertThat(registry.head()).as("the commit was made").isNotEqualTo(before);
        assertThat(file).as("the key its commit files is the person's to keep").exists();
    }

    @Test
    void a_refused_make_keeps_no_key_file_and_names_only_what_refused() throws Exception {
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        registry.agent("alice");
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--registry", "--out", file.toString(), "--json")).isEqualTo(65);
        assertThat(registry.answer().get("reason")).isEqualTo("not-signer");
        assertThat(ran).as("the key was made").hasSize(1);
        assertThat(file).as("a key never filed is not kept").doesNotExist();
        assertThat(Path.of(file + ".pub")).doesNotExist();

        registry.close();
        registry.agent("michael");
        registry.write("notes.txt", "staged for something else");
        registry.git("add", "notes.txt");
        assertThat(backupKey("--registry", "--out", file.toString(), "--json")).isEqualTo(65);
        assertThat(registry.out.toString())
                .as("a refusal that is not the agent's names no signer: " + registry.err)
                .doesNotContain("not-signer");
        assertThat(file).doesNotExist();

        assertThat(backupKey("--registry", "--out", file.toString(), "--base", "HEAD"))
                .as("a --base that is no commit's full hash")
                .isEqualTo(64);
    }
}
