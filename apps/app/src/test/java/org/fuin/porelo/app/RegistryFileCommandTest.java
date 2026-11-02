package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.git.RegistryState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the registry's filing verb: one signed commit the reader accepts, and nothing else. */
// Every test drives git and an ssh-agent, either of which can wait for ever on a mistake.
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RegistryFileCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private String founded;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
        founded = registry.founded();
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
    }

    private Path file(String name, String text) throws Exception {
        final Path path = dir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private Map<?, ?> read() {
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        return registry.answer();
    }

    /**
     * Filing reads the registry and keeps its trust record as a read does; a record it discarded
     * must be said, or the next read would never learn the record was replaced.
     */
    @Test
    void a_trust_record_discarded_while_filing_is_warned_about() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        read();
        Files.writeString(registry.record(), "{\"commit\": \"\u001b[2J secret\"");
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.err.toString())
                .contains(
                        "porelo: warning: the registry trust record was discarded and the registry"
                                + " read afresh")
                .contains(registry.record().toString())
                .doesNotContain("secret")
                .doesNotContain("\u001b");
    }

    /** The point of the verb: what it files, the reader answers as trusted. */
    @Test
    void a_filed_card_is_a_signed_commit_the_reader_answers_as_trusted() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));

        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .as(registry.err.toString())
                .isZero();
        final String head = registry.head();
        assertThat(registry.out.toString().strip())
                .isEqualTo("committed " + head + " people/alice.card");
        assertThat(registry.git("rev-parse", "HEAD^")).isEqualTo(founded);
        assertThat(registry.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
                .isEqualTo("people/alice.card");
        assertThat(registry.git("status", "--porcelain")).isEmpty();

        final Map<?, ?> answer = read();
        assertThat(answer.get("revision")).isEqualTo(head);
        assertThat(answer.get("warnings")).isEqualTo(registry.soleWarnings());
        final Map<?, ?> person = (Map<?, ?>) ((List<?>) answer.get("people")).getFirst();
        assertThat(person.get("login")).isEqualTo("alice");
        assertThat(person.get("state")).isEqualTo(RegistryState.TRUSTED);
        assertThat(person.get("fingerprint")).isEqualTo(registry.pin("alice"));
    }

    /** A host's files are filed by its admin, whom only owners.yml names, and read as accepted. */
    @Test
    void a_host_card_and_access_file_are_filed_by_an_agent_held_host_admin() throws Exception {
        registry.agent("bob", "hank");
        final Path card = file("card", registry.hostCard("wharf6", "hank"));
        final Path access = file("access.yml", registry.access("demo"));

        assertThat(
                        registry.registry(
                                "file",
                                "host-card",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--card",
                                card.toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.answer())
                .isEqualTo(Map.of("commit", registry.head(), "path", "hosts/wharf6/card"));
        assertThat(registry.out.toString()).startsWith("{\"commit\":");
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.out.toString()).contains("hosts/wharf6/access.yml");

        final Map<?, ?> answer = read();
        assertThat(answer.get("warnings")).isEqualTo(registry.soleWarnings());
        final Map<?, ?> host = (Map<?, ?>) ((List<?>) answer.get("hosts")).getFirst();
        assertThat(((Map<?, ?>) host.get("card")).get("signed_by")).isEqualTo(registry.pin("hank"));
        assertThat(((Map<?, ?>) ((List<?>) host.get("projects")).getFirst()).get("name"))
                .isEqualTo("demo");
    }

    /** The owners file with alice's key as michael's backup key. */
    private String backedOwners() {
        return registry.owners()
                .replace(
                        "host_admins:",
                        "  - login: michael\n    key: \""
                                + registry.key("alice")
                                + "\"\n    backup: true\nhost_admins:");
    }

    /** The owners file is filed by an owner's own key, and a backup key filed so is answered. */
    @Test
    void an_owners_file_is_filed_by_an_agent_held_owner() throws Exception {
        registry.agent("michael");
        final Path owners = file("owners.yml", backedOwners());

        assertThat(
                        registry.registry(
                                "file",
                                "owners",
                                registry.repo.toString(),
                                "--file",
                                owners.toString(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.answer())
                .isEqualTo(Map.of("commit", registry.head(), "path", "owners.yml"));
        assertThat(read().get("owners"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "login",
                                        "michael",
                                        "fingerprint",
                                        registry.pin("michael"),
                                        "backup",
                                        false),
                                Map.of(
                                        "login",
                                        "michael",
                                        "fingerprint",
                                        registry.pin("alice"),
                                        "backup",
                                        true)));
    }

    /**
     * A key this machine's revocation list names signs nothing a follower here accepts, so it never
     * signs the owners file, by the agent or by a backup key's file.
     */
    @Test
    void a_key_the_revocation_list_names_never_signs_the_owners_file() throws Exception {
        registry.agent("michael");
        IdentityRun.revoke(registry, "michael");
        final String before = registry.head();
        final Path owners = file("owners.yml", backedOwners());

        assertThat(
                        registry.registry(
                                "file",
                                "owners",
                                registry.repo.toString(),
                                "--file",
                                owners.toString()))
                .as(registry.err.toString())
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(registry.pin("michael"));
        assertThat(registry.head()).as("nothing is committed").isEqualTo(before);
    }

    /** A backup key is kept offline and files no owners file, even when an agent holds it. */
    @Test
    void a_backup_key_files_no_owners_file() throws Exception {
        registry.agent("michael");
        final Path owners = file("owners.yml", backedOwners());
        assertThat(
                        registry.registry(
                                "file",
                                "owners",
                                registry.repo.toString(),
                                "--file",
                                owners.toString()))
                .isZero();
        registry.close();
        registry.agent("alice");
        final String filed = registry.head();
        final Path again =
                file(
                        "again.yml",
                        backedOwners().replace(registry.key("hank"), registry.key("bob")));

        assertThat(
                        registry.registry(
                                "file",
                                "owners",
                                registry.repo.toString(),
                                "--file",
                                again.toString()))
                .as(registry.err.toString())
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains(registry.pin("michael"))
                .doesNotContain(registry.pin("alice"));
        assertThat(registry.head()).isEqualTo(filed);
    }

    /** Without the key the rule wants, a commit would only be refused later, by every reader. */
    @Test
    void a_key_the_agent_does_not_hold_is_refused_naming_the_fingerprints_wanted()
            throws Exception {
        registry.agent("bob");
        final Path access = file("access.yml", registry.access("demo"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(registry.pin("hank"));
        assertThat(registry.head()).isEqualTo(founded);
        assertThat(registry.repo.resolve("hosts")).doesNotExist();

        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(registry.pin("alice"));
    }

    /**
     * No agent at all: the same refusal, never a commit signed by something else. The machine's
     * environment is the context's alone; an agent the process itself inherited is not asked.
     */
    @Test
    void without_an_agent_nothing_is_committed() throws Exception {
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final PoreloContext none =
                new PoreloContext(registry.runner, registry.paths, args -> 0, name -> null);
        assertThat(
                        registry.registry(
                                none,
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("no ssh-agent is reachable")
                .contains(registry.pin("alice"));
        assertThat(registry.head()).isEqualTo(founded);
    }

    /** A commit without a name the checkout gives would take one from wherever git finds it. */
    @Test
    void a_checkout_that_names_no_user_is_wrong_use() throws Exception {
        registry.agent("alice");
        registry.git("config", "--unset", "user.email");
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(64);
        assertThat(registry.err.toString()).contains("user.name and user.email");
        assertThat(registry.head()).isEqualTo(founded);
    }

    /**
     * The checkout's owners.yml is anybody's to commit; who may sign a host's files is what the
     * registry accepted, so a writer naming themself host admin is not let sign for the host.
     */
    @Test
    void host_admins_come_from_the_accepted_owners_file_not_the_one_checked_out() throws Exception {
        registry.write(
                "owners.yml",
                registry.owners()
                        .replace("login: hank", "login: bob")
                        .replace(registry.key("hank"), registry.key("bob")));
        final String seized = registry.commit("bob");
        registry.agent("bob");
        final Path access = file("access.yml", registry.access("demo"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        // The read's warning names the refused owners file's signer; the refusal names only hank.
        assertThat(registry.err.toString())
                .contains(
                        "porelo: warning: "
                                + seized
                                + " owners.yml: signed by "
                                + registry.pin("bob"));
        assertThat(
                        registry.err
                                .toString()
                                .lines()
                                .filter(line -> !line.startsWith("porelo: warning: "))
                                .toList())
                .singleElement()
                .asString()
                .contains(registry.pin("hank"))
                .doesNotContain(registry.pin("bob"));
        assertThat(registry.head()).isEqualTo(seized);
    }

    /**
     * A writer who commits a person's own card, signed by their own key, has it refused; the same
     * bytes filed on top would change nothing a reader judges, so the person could never file it.
     */
    @Test
    void a_card_head_holds_from_a_refused_commit_is_removed_and_filed_again() throws Exception {
        final String text = registry.card("alice", "alice");
        registry.write("people/alice.card", text);
        final String squat = registry.commit("bob");
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", text);

        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.git("rev-parse", "HEAD~2")).isEqualTo(squat);
        assertThat(registry.git("ls-tree", "--name-only", "-r", "HEAD~1"))
                .as("the removal comes first")
                .doesNotContain("people/alice.card");
        assertThat(registry.git("cat-file", "blob", "HEAD:people/alice.card") + "\n")
                .isEqualTo(text);
        final Map<?, ?> answer = read();
        final Map<?, ?> person = (Map<?, ?>) ((List<?>) answer.get("people")).getFirst();
        assertThat(person.get("state")).isEqualTo(RegistryState.TRUSTED);
        assertThat(answer.get("revision")).isEqualTo(registry.head());
        assertThat(answer.get("refused_commit")).as("the path is accepted at last").isNull();
    }

    /**
     * The read decides who may sign; a commit made after it, such as one taking the host from its
     * admin, must not have a filing written on top under the stale decision.
     */
    @Test
    void a_head_that_moved_after_the_read_is_refused_naming_both_and_nothing_is_written()
            throws Exception {
        registry.agent("hank");
        final Path access = file("access.yml", registry.access("demo"));
        final String[] moved = new String[1];
        registry.afterRead =
                () -> {
                    try {
                        registry.write("notes.txt", "later\n");
                        moved[0] = registry.commit("bob");
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                };
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(moved[0]).contains(founded);
        assertThat(registry.head()).isEqualTo(moved[0]);
        assertThat(registry.repo.resolve("hosts")).doesNotExist();
    }

    /**
     * Removing a refused copy of a changed card would take the accepted card and its key with it,
     * so the next card would be judged as a first one and its owner's chain lost.
     */
    @Test
    void a_refused_copy_of_a_changed_card_is_never_removed_but_named_to_revert() throws Exception {
        registry.forge.keys.put("alice", List.of(registry.key("alice"), registry.key("alice-new")));
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        final String text = registry.card("alice", "alice-new", "alice");
        registry.write("people/alice.card", text);
        final String squat = registry.commit("bob");
        final Path card = file("alice.card", text);

        registry.agent("alice-new", "alice", "michael");
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("revert " + squat + " with git, then file again");
        assertThat(registry.head()).isEqualTo(squat);
    }

    /** A revoked key signs nothing the reader accepts, so it is never chosen to sign. */
    @Test
    void a_revoked_key_is_never_chosen_to_sign() throws Exception {
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        registry.write("people/hank.card", registry.card("hank", "hank"));
        registry.commit("hank");
        registry.write("people/alice.card", registry.card("alice", "alice-new"));
        registry.write("people/hank.card", registry.card("hank", "bob"));
        registry.commit("michael");
        final String revoked = registry.head();
        registry.agent("alice", "hank");

        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("revoked");
        final Path access = file("access.yml", registry.access("demo"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("names no admin of wharf6");
        assertThat(registry.head()).isEqualTo(revoked);

        // A key handed on from a revoked one is refused by every reader, so it is never filed.
        final Path handed = file("handed.card", registry.card("alice", "michael", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                handed.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("handed on from " + registry.pin("alice") + ", which is revoked");
        assertThat(registry.head()).isEqualTo(revoked);
    }

    /**
     * A commit made after the read but before HEAD moves must not leave the filed bytes in the work
     * tree, where the next {@code git commit} of anybody would carry them.
     */
    @Test
    void a_head_that_moved_before_the_update_leaves_the_work_tree_untouched() throws Exception {
        registry.agent("hank");
        final Path access = file("access.yml", registry.access("demo"));
        final String[] moved = new String[1];
        registry.beforeUpdate = () -> moved[0] = registry.commit("bob", "later");
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        assertThat(registry.head()).isEqualTo(moved[0]);
        assertThat(registry.repo.resolve("hosts")).doesNotExist();
        assertThat(registry.git("status", "--porcelain")).isEmpty();
    }

    /**
     * A read that stopped short judged nothing at HEAD; its empty list of admins must not be the
     * reason given, which would send the admin looking for a fault in the owners file.
     */
    @Test
    void a_read_stopped_short_is_refused_naming_head_before_any_other_check() throws Exception {
        registry.forge.owners.remove("michael");
        registry.agent("hank");
        final Path access = file("access.yml", registry.access("demo"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-access",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--file",
                                access.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(founded).doesNotContain("names no admin");
        assertThat(registry.head()).isEqualTo(founded);
    }

    /**
     * A host leaving the registry has its card removed by one of its admins; a removal nobody
     * judged, or one asked again, must not leave a second commit or the file behind.
     */
    @Test
    void a_host_card_is_removed_by_its_admin_and_asked_again_answers_the_same() throws Exception {
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        final String filed = registry.commit("hank");
        registry.agent("hank");
        final String[] args = {
            "file",
            "host-card-remove",
            registry.repo.toString(),
            "--host",
            "wharf6",
            "--id",
            "gone-1",
            "--base",
            filed,
            "--json"
        };

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        assertThat(registry.answer())
                .isEqualTo(Map.of("commit", made, "path", "hosts/wharf6/card"));
        assertThat(registry.git("rev-parse", "HEAD^")).isEqualTo(filed);
        assertThat(registry.git("log", "-1", "--format=%B"))
                .contains("Porelo-Change-Id: gone-1")
                .contains("Porelo-Change-Digest: " + digest("hosts/wharf6/card", ""));
        assertThat(registry.repo.resolve("hosts/wharf6/card")).doesNotExist();
        assertThat(registry.git("status", "--porcelain")).isEmpty();
        final Map<?, ?> answer = read();
        assertThat(answer.get("hosts")).isEqualTo(List.of());
        assertThat(answer.get("refused_commit")).isNull();

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        assertThat(registry.answer().get("commit")).isEqualTo(made);
        assertThat(registry.head()).isEqualTo(made);
    }

    /** A removal of nothing would be a commit no reader judges, answered as if it did something. */
    @Test
    void a_host_card_the_registry_does_not_hold_is_not_removed() throws Exception {
        registry.agent("hank");
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6"))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("accepts no hosts/wharf6/card");
        assertThat(registry.head()).isEqualTo(founded);
    }

    /**
     * A host card HEAD holds that the registry refused is no card: a removal committed for it would
     * be ignored by every reader, and its refusal still in force.
     */
    @Test
    void a_refused_host_card_at_head_is_not_removed_naming_why() throws Exception {
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        registry.commit("bob");
        final String refused = registry.head();
        registry.agent("hank");
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6"))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("accepts no hosts/wharf6/card")
                .contains("HEAD holds one it refused");
        assertThat(registry.head()).isEqualTo(refused);
    }

    /**
     * An accepted host card whose refused deletion HEAD holds must still be removable by its admin;
     * a removal of nothing at HEAD would be judged by nobody and the host stay listed.
     */
    @Test
    void an_accepted_host_card_head_no_longer_holds_is_put_back_and_removed() throws Exception {
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        registry.commit("hank");
        Files.delete(registry.repo.resolve("hosts/wharf6/card"));
        final String deleted = registry.commit("bob");
        registry.agent("hank");
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--id",
                                "gone-2"))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.git("rev-parse", "HEAD~2")).isEqualTo(deleted);
        assertThat(registry.git("log", "-1", "--format=%B", "HEAD~1"))
                .startsWith("Restore hosts/wharf6/card")
                .contains("Porelo-Change-Step: gone-2")
                .doesNotContain("Porelo-Change-Id");
        final Map<?, ?> answer = read();
        assertThat(answer.get("hosts")).as("the card is removed").isEqualTo(List.of());
        assertThat(answer.get("refused_commit")).isNull();
    }

    /**
     * Only a host's admin may take its card away; a removal signed by anybody else is refused by
     * every reader, and the host would still be listed while its maintainer believes it gone.
     */
    @Test
    void a_host_card_is_never_removed_by_anybody_but_its_admin() throws Exception {
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        final String filed = registry.commit("hank");
        registry.agent("bob", "michael");
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6"))
                .isEqualTo(65);
        assertThat(registry.head()).isEqualTo(filed);
        assertThat(registry.repo.resolve("hosts/wharf6/card")).exists();
    }

    /** A removal decided on an older registry, or given a file to file, is not this removal. */
    @Test
    void a_host_card_removal_on_another_base_or_with_a_file_is_refused() throws Exception {
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        final String filed = registry.commit("hank");
        registry.agent("hank");
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--base",
                                founded))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(filed).contains(founded);
        final Path card = file("wharf6.card", registry.hostCard("wharf6", "hank"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-card-remove",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--card",
                                card.toString()))
                .isEqualTo(64);
        assertThat(registry.registry("file", "host-card-remove", registry.repo.toString()))
                .isEqualTo(64);
        assertThat(registry.head()).isEqualTo(filed);
    }

    private static String digest(String path, String text) throws Exception {
        final var sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update((path + "\0" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    /**
     * A caller that lost the answer asks again; a second removal and filing would bury the first,
     * and the caller could not tell which commit is its own.
     */
    @Test
    void a_change_asked_again_by_its_id_is_answered_the_commit_made_before() throws Exception {
        final String text = registry.card("alice", "alice");
        registry.write("people/alice.card", text);
        final String squat = registry.commit("bob");
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", text);
        final String[] args = {
            "file",
            "card",
            registry.repo.toString(),
            "--card",
            card.toString(),
            "--id",
            "card-1",
            "--base",
            squat
        };

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        assertThat(registry.git("rev-parse", "HEAD~2")).isEqualTo(squat);
        assertThat(registry.git("log", "-1", "--format=%B"))
                .contains("Porelo-Change-Id: card-1")
                .contains("Porelo-Change-Digest: " + digest("people/alice.card", text));

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        assertThat(registry.out.toString().strip())
                .isEqualTo("committed " + made + " people/alice.card");
        assertThat(registry.head()).isEqualTo(made);
    }

    /** One id naming two changes would answer the first to a caller who asked for the second. */
    @Test
    void an_id_used_for_another_change_is_refused() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "card-1",
                                "--base",
                                founded))
                .as(registry.err.toString())
                .isZero();
        final String made = registry.head();

        final String other =
                org.fuin.porelo.vault.PersonCard.write(
                        "alice",
                        "Alicia",
                        "",
                        registry.keys.get("alice"),
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        null);
        final Path changed = file("other.card", other);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                changed.toString(),
                                "--id",
                                "card-1",
                                "--base",
                                founded))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("--id card-1 names another change: " + made);
        assertThat(registry.head()).isEqualTo(made);
    }

    /** A change made against an older registry was decided on what it held then. */
    @Test
    void a_change_made_against_another_base_is_refused_naming_both() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final String later = registry.commit("bob", "later");
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--base",
                                founded))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(later).contains(founded);
        assertThat(registry.head()).isEqualTo(later);
    }

    /** An id is written into a commit message; a line break there would forge a trailer. */
    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a\nPorelo-Change-Digest: x", "../x/"})
    void an_id_or_base_of_another_shape_is_refused(String id) throws Exception {
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                id))
                .isEqualTo(64);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--base",
                                "HEAD"))
                .isEqualTo(64);
        assertThat(registry.head()).isEqualTo(founded);
    }

    /**
     * Anybody can write a commit carrying the id and digest; answered as made, the caller would
     * believe a change filed that no reader accepts, and passed over, it would move the base.
     */
    @Test
    void an_id_in_a_commit_the_registry_did_not_accept_is_refused_by_name() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final String text = registry.card("alice", "alice");
        final String forged =
                registry.commit(
                        "bob",
                        "File people/alice.card\n\nPorelo-Change-Id: card-1\n"
                                + "Porelo-Change-Digest: "
                                + digest("people/alice.card", text));
        final Path card = file("alice.card", text);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "card-1"))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("--id card-1 names " + forged + ", which the registry does not hold");
        assertThat(registry.head()).isEqualTo(forged);
    }

    /**
     * The commit that removes a refused copy before filing it again deletes the path; answered for
     * the id when it is all that was pushed, the caller would believe the card filed.
     */
    @Test
    void the_removal_before_a_filing_never_answers_for_its_id() throws Exception {
        final String text = registry.card("alice", "alice");
        registry.write("people/alice.card", text);
        final String squat = registry.commit("bob");
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", text);
        final String[] args = {
            "file",
            "card",
            registry.repo.toString(),
            "--card",
            card.toString(),
            "--id",
            "card-1",
            "--base",
            squat
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        registry.git("reset", "-q", "--hard", "HEAD~1");
        final String removal = registry.head();
        assertThat(registry.git("log", "-1", "--format=%B"))
                .contains("Porelo-Change-Step: card-1")
                .doesNotContain("Porelo-Change-Digest");

        assertThat(registry.registry(args)).isEqualTo(65);
        assertThat(registry.err.toString())
                .as("not answered: refused as made against another base")
                .contains("HEAD is " + removal);
        assertThat(registry.head()).isEqualTo(removal);
    }

    /**
     * A host admin can sign any commit carrying a filing's id and digest; one that changes another
     * path, answered for the id, would tell the caller its card was filed by that commit.
     */
    @Test
    void an_id_on_a_commit_of_another_change_is_refused_by_name() throws Exception {
        registry.agent("hank");
        final String text = registry.hostCard("wharf6", "hank");
        final Path card = file("wharf6.card", text);
        final String[] args = {
            "file",
            "host-card",
            registry.repo.toString(),
            "--host",
            "wharf6",
            "--card",
            card.toString(),
            "--id",
            "host-1"
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        registry.write("hosts/wharf6/access.yml", registry.access("demo"));
        registry.git("add", "-A");
        final String other =
                registry.commit(
                        "hank",
                        "File hosts/wharf6/access.yml\n\nPorelo-Change-Id: host-1\n"
                                + "Porelo-Change-Digest: "
                                + digest("hosts/wharf6/card", text));

        assertThat(registry.registry(args)).isEqualTo(65);
        assertThat(registry.err.toString()).contains("--id host-1 names " + other + ",");
        assertThat(registry.head()).isEqualTo(other);
    }

    /**
     * A commit the read refused stays refused when owners.yml later lets its signer file the path;
     * answered for the id, the caller would believe filed what no reader holds.
     */
    @Test
    void an_id_on_a_commit_refused_before_its_signer_was_allowed_is_refused_by_name()
            throws Exception {
        final String text = registry.hostCard("wharf6", "bob");
        registry.write("hosts/wharf6/card", text);
        registry.git("add", "-A");
        final String early =
                registry.commit(
                        "bob",
                        "File hosts/wharf6/card\n\nPorelo-Change-Id: host-1\n"
                                + "Porelo-Change-Digest: "
                                + digest("hosts/wharf6/card", text));
        registry.write(
                "owners.yml",
                registry.owners()
                        + "    - login: bob\n      key: \""
                        + registry.key("bob")
                        + "\"\n");
        final String allowed = registry.commit("michael");
        registry.agent("bob");
        final Path card = file("wharf6.card", text);
        assertThat(
                        registry.registry(
                                "file",
                                "host-card",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--card",
                                card.toString(),
                                "--id",
                                "host-1"))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("--id host-1 names " + early + ",");
        assertThat(registry.head()).isEqualTo(allowed);
    }

    /**
     * An id reused for another change on the base of its first commit must be refused as the other
     * change it names, not passed over for its base and filed as a second change.
     */
    @Test
    void an_id_reused_on_the_base_after_its_first_change_is_refused() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "card-1",
                                "--base",
                                founded))
                .as(registry.err.toString())
                .isZero();
        final String made = registry.head();
        final Path changed =
                file(
                        "other.card",
                        org.fuin.porelo.vault.PersonCard.write(
                                "alice",
                                "Alicia",
                                "",
                                registry.keys.get("alice"),
                                java.time.LocalDate.of(2026, 1, 1),
                                java.time.LocalDate.of(2030, 1, 1),
                                null));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                changed.toString(),
                                "--id",
                                "card-1",
                                "--base",
                                made))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("--id card-1 names another change: " + made);
        assertThat(registry.head()).isEqualTo(made);
    }

    /**
     * A commit's bytes need be no UTF-8; counted as decoded text, a commit could hide the one after
     * it, or forge it, and with it the id's collision check or a step.
     */
    @Test
    void a_commit_of_bytes_that_are_no_utf8_neither_hides_nor_forges_another() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final String[] args = {
            "file", "card", registry.repo.toString(), "--card", card.toString(), "--id", "card-1"
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        final String tree = registry.git("rev-parse", "HEAD^{tree}");
        final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.writeBytes(
                ("tree "
                                + tree
                                + "\nparent "
                                + made
                                + "\nauthor A <a@example.org> 0 +0000\ncommitter A <a@example.org> 0"
                                + " +0000\n\nnote ")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        raw.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80});
        raw.writeBytes(
                ("\n" + made + " commit 46\ntree " + tree + "\n\nnothing\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        final String odd =
                registry.runner
                        .runOrFail(
                                org.fuin.porelo.core.process.Command.of(
                                                "git",
                                                "hash-object",
                                                "-t",
                                                "commit",
                                                "-w",
                                                "--stdin")
                                        .in(registry.repo)
                                        .withBinaryInput(raw.toByteArray()))
                        .trimmedOutput();
        registry.git("update-ref", "HEAD", odd);

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        assertThat(registry.out.toString().strip())
                .isEqualTo("committed " + made + " people/alice.card");
    }

    /**
     * Anybody can push a commit carrying a step's trailer before a filing; taken as the step, it
     * would move the filing's base to its own parent, answering for a base the change never had.
     */
    @Test
    void a_step_another_key_signed_moves_no_base() throws Exception {
        registry.write(
                "people/alice.card",
                org.fuin.porelo.vault.PersonCard.write(
                        "alice",
                        "Mallory",
                        "",
                        registry.keys.get("alice"),
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        null));
        registry.git("add", "-A");
        final String step =
                registry.commit(
                        "bob",
                        "Remove people/alice.card to file it again\n\nPorelo-Change-Step: card-1");
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final String[] args = {
            "file",
            "card",
            registry.repo.toString(),
            "--card",
            card.toString(),
            "--id",
            "card-1",
            "--base",
            step
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        assertThat(registry.git("rev-parse", "HEAD^")).isEqualTo(step);

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        assertThat(registry.out.toString().strip())
                .isEqualTo("committed " + made + " people/alice.card");
        args[args.length - 1] = founded;
        assertThat(registry.registry(args)).isEqualTo(65);
        assertThat(registry.err.toString()).contains("was filed as " + made + " on " + step);
    }

    /**
     * An id once filed is never committed again: asked on a refused commit after it, a second
     * commit under the id would leave two, and the id never answered again.
     */
    @Test
    void an_id_filed_before_is_never_committed_again_on_another_base() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final String[] args = {
            "file",
            "card",
            registry.repo.toString(),
            "--card",
            card.toString(),
            "--id",
            "card-1",
            "--base",
            founded
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        Files.delete(registry.repo.resolve("people/alice.card"));
        final String refused = registry.commit("bob");
        args[args.length - 1] = refused;

        assertThat(registry.registry(args)).isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("--id card-1 was filed as " + made + " on " + founded);
        assertThat(registry.head()).isEqualTo(refused);
    }

    /**
     * Asked again, the checkout's configuration must start no program: Git's readers of a commit
     * run the signature program it names, and show signatures when it says so.
     */
    @Test
    void asked_again_by_its_id_no_program_the_checkout_names_runs() throws Exception {
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final String[] args = {
            "file", "card", registry.repo.toString(), "--card", card.toString(), "--id", "card-1"
        };
        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        final String made = registry.head();
        final Path marker = dir.resolve("program-ran");
        final Path program = dir.resolve("program");
        Files.writeString(program, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        program.toFile().setExecutable(true);
        for (String name : List.of("gpg.program", "gpg.ssh.program", "gpg.x509.program")) {
            registry.git("config", name, program.toString());
        }
        registry.git("config", "log.showSignature", "true");
        registry.git("config", "gpg.ssh.allowedSignersFile", program.toString());

        assertThat(registry.registry(args)).as(registry.err.toString()).isZero();
        assertThat(registry.out.toString().strip())
                .isEqualTo("committed " + made + " people/alice.card");
        assertThat(marker).doesNotExist();
    }

    /** A signed commit carrying a change staged for something else would vouch for it. */
    @Test
    void another_staged_change_is_refused_and_left_staged() throws Exception {
        registry.agent("alice");
        registry.write("notes.txt", "draft\n");
        registry.git("add", "notes.txt");
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("other staged change");
        assertThat(registry.head()).isEqualTo(founded);
        assertThat(registry.git("diff", "--cached", "--name-only")).isEqualTo("notes.txt");
    }

    /**
     * The checkout's hooks, filters and attributes are anybody's who wrote the registry or set up
     * the checkout: filing runs none of them, and signs exactly the bytes given.
     */
    @Test
    void the_checkouts_hooks_filters_and_attributes_neither_run_nor_change_what_is_signed()
            throws Exception {
        registry.write(".gitattributes", "* filter=x\n");
        registry.commit("michael");
        final List<String> ran = new java.util.ArrayList<>();
        for (String hook : List.of("pre-commit", "post-index-change", "post-commit")) {
            final Path marker = dir.resolve(hook + "-ran");
            ran.add(marker.toString());
            final Path script = registry.repo.resolve(".git/hooks/" + hook);
            Files.writeString(script, "#!/bin/sh\ntouch '" + marker + "'\n");
            script.toFile().setExecutable(true);
        }
        final Path cleaned = dir.resolve("clean-ran");
        final Path monitored = dir.resolve("fsmonitor-ran");
        registry.git("config", "filter.x.clean", "touch '" + cleaned + "'; sed s/Alice/Mallory/");
        registry.git("config", "core.fsmonitor", "touch '" + monitored + "'; false");
        registry.agent("alice");
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final String text = registry.card("alice", "alice");
        final Path card = file("alice.card", text);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.git("cat-file", "blob", "HEAD:people/alice.card") + "\n")
                .as("the committed blob is the bytes given")
                .isEqualTo(text);
        assertThat(Files.readString(registry.repo.resolve("people/alice.card"))).isEqualTo(text);
        for (String marker : ran) {
            assertThat(Path.of(marker)).doesNotExist();
        }
        assertThat(cleaned).as("no clean filter").doesNotExist();
        assertThat(monitored).as("no fsmonitor").doesNotExist();
        assertThat(read().get("warnings")).isEqualTo(registry.soleWarnings());
    }

    /** A card the reader would refuse for itself must not become a commit. */
    @Test
    void a_card_not_signed_as_it_must_be_or_of_another_host_is_refused() throws Exception {
        registry.agent("alice", "hank");
        final String text = registry.card("alice", "alice").replace("Alice", "Mallory");
        final Path card = file("alice.card", text);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString())
                .contains("not signed as it must be")
                .doesNotContain("Mallory");

        final Path other = file("card", registry.hostCard("walk7", "hank"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-card",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--card",
                                other.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains("not the card of wharf6");

        final Path bobs = file("card", registry.hostCard("wharf6", "bob"));
        assertThat(
                        registry.registry(
                                "file",
                                "host-card",
                                registry.repo.toString(),
                                "--host",
                                "wharf6",
                                "--card",
                                bobs.toString()))
                .isEqualTo(65);
        assertThat(registry.err.toString()).contains(registry.pin("bob")).contains("does not name");
        assertThat(registry.head()).isEqualTo(founded);
    }

    /** A symlink in the checkout would have the card written wherever it points. */
    @Test
    void a_symlinked_directory_in_the_checkout_is_not_written_through() throws Exception {
        registry.agent("alice");
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(registry.repo.resolve("people"), elsewhere);
        final Path card = file("alice.card", registry.card("alice", "alice"));
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                card.toString()))
                .isEqualTo(65);
        assertThat(elsewhere.resolve("alice.card")).doesNotExist();
        assertThat(registry.head()).isEqualTo(founded);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "letter --card x",
                "card --file x",
                "card --card x --host wharf6",
                "host-card --card x",
                "host-access --card x --host wharf6",
                "host-access --host wharf6",
                "owners --card x",
                "owners --file x --host wharf6",
                "owners",
            })
    void options_the_kind_does_not_take_are_wrong_use(String use) throws Exception {
        final List<String> args = new java.util.ArrayList<>(List.of(use.split(" ")));
        args.add(1, registry.repo.toString());
        args.addFirst("file");
        assertThat(registry.registry(args.toArray(String[]::new))).isEqualTo(64);
        assertThat(registry.head()).isEqualTo(founded);
    }

    @Test
    void a_directory_that_is_not_a_checkout_is_not_writable() throws Exception {
        final Path card = file("alice.card", registry.card("alice", "alice"));
        final Path plain = Files.createDirectory(dir.resolve("plain"));
        assertThat(registry.registry("file", "card", plain.toString(), "--card", card.toString()))
                .isEqualTo(66);
    }
}
