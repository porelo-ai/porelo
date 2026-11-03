package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the change of a person's own key: {@code identity rotate} and {@code revoke --lost},
 * against the real store on a repository of the test's own and a real ssh-agent.
 */
class IdentityRotateCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private TestOrganization organization;

    /** The stand-in KeePassXC, once a test makes one. */
    private KeePassXcStandIn standIn;

    @BeforeEach
    void founded() throws Exception {
        registry = new RegistryCheckout(dir);
        organization = new TestOrganization(registry);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
    }

    /** alice's first card accepted, kept as this machine's own, her new key on the forge. */
    private String filed() throws Exception {
        final String card = organization.filed("alice");
        new IdentityStore(registry.context()).keep(card);
        organization.listed("alice", "alice-new");
        return card;
    }

    private int identity(final String... args) {
        return standIn == null
                ? IdentityRun.identity(organization, args)
                : IdentityRun.identity(
                        organization, standIn.store(java.time.Duration.ofMillis(800)), args);
    }

    private int rotate(final String id) {
        return identity("rotate", "--key", registry.pin("alice-new"), "--id", id, "--json");
    }

    /** The stand-in KeePassXC the verbs run, on a terminal, giving keys to the test's agent. */
    private KeePassXcStandIn keepass(final KeePassXcStandIn.Mode mode) throws Exception {
        registry.terminal = true;
        standIn = new KeePassXcStandIn(dir, mode, registry.environment().apply("SSH_AUTH_SOCK"));
        return standIn;
    }

    /** Takes a login's key out of the agent, as a lost or locked key leaves it. */
    private void forget(final String login) {
        registry.runner.runOrFail(
                Command.of("ssh-add", "-d", registry.keyFiles.get(login) + ".pub")
                        .withEnvironment(
                                Map.of(
                                        "SSH_AUTH_SOCK",
                                        registry.environment().apply("SSH_AUTH_SOCK"))));
    }

    private Map<?, ?> answer() {
        return registry.answer();
    }

    private String err() {
        return registry.err.toString();
    }

    private PersonCard kept() throws Exception {
        return new IdentityStore(registry.context()).card().orElseThrow();
    }

    private String keptText() throws Exception {
        return Files.readString(new IdentityStore(registry.context()).cardFile());
    }

    private Path ops() {
        return IdentityOperation.directory(registry.context());
    }

    /** A record as a run left it that stopped right after the store took its card. */
    private void stoppedBeforeItNoted(final String id) throws Exception {
        final Path file = ops().resolve(id + ".json");
        final Map<String, Object> stopped =
                new LinkedHashMap<>((Map<String, Object>) Json.parse(Files.readString(file)));
        stopped.put("done", false);
        stopped.put("kept", false);
        stopped.remove("revision");
        Files.writeString(file, Json.write(stopped));
    }

    /**
     * The point of the verb: a card signed by both keys, in the store, which the store's readers
     * take as alice's; signed by the new key, as the store's rule asks of a person's own change.
     */
    @Test
    void a_rotation_files_through_the_store_a_card_signed_by_both_keys() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final String before = organization.tip();

        assertThat(rotate("c1")).as(err()).isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("id")).isEqualTo("c1");
        assertThat(answer.get("again")).isEqualTo(false);
        assertThat(answer.get("of")).isEqualTo("organization");
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice-new"));
        assertThat(answer.get("predecessor")).isEqualTo(registry.pin("alice"));
        assertThat(organization.tip()).as("the store took a change").isNotEqualTo(before);
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        final PersonCard filed = PersonCard.read(organization.card("alice"), "filed");
        assertThat(filed.fingerprint()).isEqualTo(registry.pin("alice-new"));
        assertThat(filed.predecessor()).isEqualTo(registry.key("alice"));
        assertThat(filed.signedAsItMustBe()).isTrue();
        assertThat(organization.signer("HEAD")).isEqualTo("alice-new");
        assertThat(kept().fingerprint())
                .as("kept here once the organization took it")
                .isEqualTo(registry.pin("alice-new"));
        assertThat(
                        organization
                                .opener()
                                .open(registry.context(), TestOrganization.ADDRESS)
                                .store()
                                .read(registry.forge)
                                .trusts("alice", registry.key("alice-new"))
                                .answer())
                .as("a reader of the store takes the new key as alice's")
                .isEqualTo("yes");
    }

    /** An owner's key is in the owners file too: left there, the owner could sign nothing. */
    @Test
    void an_owners_rotation_changes_the_owners_file_signed_by_the_old_key() throws Exception {
        registry.agent("michael", "alice-new");
        new IdentityStore(registry.context()).keep(organization.filed("michael"));
        organization.listed("michael", "alice-new");
        final int commits = organization.commits();

        assertThat(rotate("c1")).as(err()).isZero();

        assertThat(organization.commits()).isEqualTo(commits + 2);
        assertThat(organization.owners()).contains(registry.key("alice-new"));
        assertThat(organization.owners()).doesNotContain(registry.key("michael"));
        assertThat(organization.signer("HEAD~1")).isEqualTo("michael");
        assertThat(organization.signer("HEAD")).isEqualTo("alice-new");
    }

    /** An id asked again answers that change, whatever key is named, and makes no second. */
    @Test
    void the_same_id_again_answers_the_same_change_although_the_store_moved_on() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        assertThat(rotate("c1")).as(err()).isZero();
        final Object revision = answer().get("revision");
        organization.filed("hank");
        final String moved = organization.tip();

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c1", "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(answer().get("fingerprint")).isEqualTo(registry.pin("alice-new"));
        assertThat(organization.tip()).as("no second change").isEqualTo(moved);
    }

    /**
     * A run stopped after the store took the card asks the store again, which answers that change;
     * a second card would stand for nothing.
     */
    @Test
    void a_card_the_store_took_before_the_run_stopped_is_kept_by_the_next_run() throws Exception {
        registry.agent("alice", "alice-new");
        final String old = filed();
        assertThat(rotate("c1")).as(err()).isZero();
        final String revision = organization.tip();
        stoppedBeforeItNoted("c1");
        new IdentityStore(registry.context()).keep(old);

        assertThat(rotate("c1")).as(err()).isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(organization.tip()).isEqualTo(revision);
        assertThat(kept().fingerprint()).isEqualTo(registry.pin("alice-new"));
        final IdentityOperation record = IdentityOperation.read(registry.context(), "c1");
        assertThat(record.done()).isTrue();
        assertThat(record.kept()).isTrue();
    }

    /** Someone else changed the organization: the card was made against what no longer is. */
    @Test
    void a_base_the_store_moved_past_is_moved_on_and_nothing_stands() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        final String base = organization.tip();
        organization.filed("hank");

        assertThat(
                        identity(
                                "rotate",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "c1",
                                "--base",
                                base,
                                "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("moved-on");
        assertThat(answer().get("revision")).isEqualTo(organization.tip());
        assertThat(keptText()).isEqualTo(card);
        assertThat(IdentityOperation.read(registry.context(), "c1"))
                .as("it made nothing, so a new try may take the id")
                .isNull();
    }

    /** A machine that names no organization has nowhere to file; no key is made for it. */
    @Test
    void no_organization_is_no_organization_and_nothing_is_begun() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        Files.delete(registry.paths.xdg().config().resolve("organization.yml"));

        assertThat(rotate("c1")).isEqualTo(78);
        assertThat(answer().get("reason")).isEqualTo("no-organization");
        assertThat(ops()).doesNotExist();
    }

    /** An organization out of reach says nothing: a change never rests on what it said once. */
    @Test
    void a_store_out_of_reach_is_cannot_say_and_nothing_is_begun() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        assertThat(identity("show", "--json")).as("it read the store once").isZero();
        organization.outOfReach();

        assertThat(rotate("c1")).isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(ops()).doesNotExist();
        assertThat(keptText()).isEqualTo(card);
    }

    /**
     * The store going away while it files says nothing of whether the change stands: the record
     * stays, nothing is kept yet, and the same id goes on with the same card.
     */
    @Test
    void a_store_lost_while_filing_keeps_the_id_and_the_same_id_goes_on() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        organization.unreachableForChanges = true;

        assertThat(rotate("c1")).isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(keptText()).as("not taken yet").isEqualTo(card);
        final String made = IdentityOperation.read(registry.context(), "c1").card();

        organization.unreachableForChanges = false;
        assertThat(rotate("c1")).as(err()).isZero();
        assertThat(answer().get("again")).isEqualTo(false);
        assertThat(organization.card("alice")).isEqualTo(made);
        assertThat(keptText()).isEqualTo(made);
    }

    /** Another key under a new id replaces a change that reached nowhere: nothing stands of it. */
    @Test
    void a_new_id_replaces_a_pending_change_that_reached_nowhere() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        organization.listed("alice", "bob");
        organization.unreachableForChanges = true;
        assertThat(rotate("c1")).isEqualTo(69);
        organization.unreachableForChanges = false;

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c2", "--json"))
                .as(err())
                .isZero();

        assertThat(kept().fingerprint()).isEqualTo(registry.pin("bob"));
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNull();
        assertThat(identity("show", "--json")).isZero();
        assertThat((List<?>) answer().get("pending")).isEmpty();
    }

    /** One that did reach the store stands: a new id would put a second key over it unnoticed. */
    @Test
    void a_new_id_is_exists_for_a_pending_change_that_reached_the_store() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        organization.listed("alice", "bob");
        assertThat(rotate("c1")).as(err()).isZero();
        final String revision = organization.tip();
        stoppedBeforeItNoted("c1");

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c2", "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(answer().get("id")).isEqualTo("c1");
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(organization.tip()).isEqualTo(revision);
    }

    /**
     * The store took the card and the answer was lost; then the forge serves the state from before.
     * That state cannot prove the change reached nowhere, so the record that could finish it stays
     * and no second key goes over it.
     */
    @Test
    void a_change_whose_answer_was_lost_is_exists_under_a_new_id_though_the_forge_went_back()
            throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        final String before = organization.tip();
        organization.afterPush = new java.io.IOException("the connection went away");

        assertThat(rotate("c1")).isEqualTo(70);
        assertThat(answer().get("reason")).isEqualTo("failed");
        assertThat(answer().get("id")).isEqualTo("c1");
        organization.servesAgain(before);

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c2", "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(answer().get("id")).isEqualTo("c1");
        assertThat((String) answer().get("words")).contains("c1");
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNotNull();
        assertThat(organization.tip()).isEqualTo(before);
    }

    /** The same when the run died right after the push: it had no moment to write anything. */
    @Test
    void a_run_that_died_after_its_push_is_exists_under_a_new_id_though_the_forge_went_back()
            throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        final String before = organization.tip();
        organization.afterPush = new IllegalStateException("the run ends here");

        assertThat(rotate("c1")).isNotZero();
        organization.servesAgain(before);

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c2", "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(answer().get("id")).isEqualTo("c1");
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNotNull();
    }

    /**
     * A push the repository did not take may still have reached it: git's word ends at the wire.
     */
    @Test
    void a_push_that_failed_keeps_its_change_and_a_new_id_is_exists() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        organization.git("config", "receive.denyCurrentBranch", "refuse");

        assertThat(rotate("c1")).isEqualTo(69);
        assertThat(answer().get("id")).isEqualTo("c1");
        assertThat(String.valueOf(answer().get("words")))
                .as("a person reads what to check, not that the organization is out of reach")
                .contains("did not take the change")
                .contains("may push there");
        organization.git("config", "receive.denyCurrentBranch", "updateInstead");

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "c2", "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(answer().get("id")).isEqualTo("c1");
    }

    /** A refusal now says nothing of the push an earlier run may have made: the record stays. */
    @Test
    void a_refusal_of_a_later_try_does_not_delete_a_change_that_may_stand() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final String before = organization.tip();
        organization.afterPush = new java.io.IOException("the connection went away");
        assertThat(rotate("c1")).isEqualTo(70);
        organization.servesAgain(before);
        organization.filed("hank");

        assertThat(
                        identity(
                                "rotate",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "c1",
                                "--base",
                                before,
                                "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("moved-on");
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNotNull();
    }

    /**
     * The way out under a lost key stays open, and ends the first change: nothing it could file
     * would be this person's card any more.
     */
    @Test
    void a_lost_key_goes_past_a_change_that_may_stand_and_retires_its_record() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        final String before = organization.tip();
        organization.afterPush = new java.io.IOException("the connection went away");
        assertThat(rotate("c1")).isEqualTo(70);
        organization.servesAgain(before);

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("bob"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("predecessor")).isEqualTo(registry.pin("alice"));
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNull();
        assertThat(answer().get("retired")).isEqualTo(List.of("c1"));
        assertThat(kept().fingerprint()).isEqualTo(registry.pin("bob"));

        organization.listed("alice", "alice-new");
        identity("rotate", "--key", registry.pin("alice-new"), "--id", "c3", "--json");
        assertThat(answer().get("reason"))
                .as("the first change hands on from a key this card no longer is")
                .isNotEqualTo("exists");
    }

    /** The disk filling up after the push leaves the person a change to ask about, by its id. */
    @Test
    void a_local_write_failing_after_the_push_is_failed_with_the_id_and_the_id_finishes_it()
            throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        final Path pub = new IdentityStore(registry.context()).cardFile().resolveSibling("key.pub");
        Files.delete(pub);
        Files.createDirectories(pub.resolve("in-the-way"));

        assertThat(rotate("c1")).isEqualTo(70);

        assertThat(answer().get("reason")).isEqualTo("failed");
        assertThat(answer().get("id")).isEqualTo("c1");
        final String revision = organization.tip();
        Files.delete(pub.resolve("in-the-way"));
        Files.delete(pub);

        assertThat(rotate("c1")).as(err()).isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(keptText()).isNotEqualTo(card);
        assertThat(kept().fingerprint()).isEqualTo(registry.pin("alice-new"));
    }

    /** And when the record already says done and only the card is left to keep. */
    @Test
    void a_local_write_failing_for_a_change_noted_done_is_failed_with_the_id() throws Exception {
        registry.agent("alice", "alice-new");
        final String old = filed();
        assertThat(rotate("c1")).as(err()).isZero();
        final Path file = ops().resolve("c1.json");
        final Map<String, Object> noted =
                new LinkedHashMap<>((Map<String, Object>) Json.parse(Files.readString(file)));
        noted.put("kept", false);
        Files.writeString(file, Json.write(noted));
        new IdentityStore(registry.context()).keep(old);
        final Path pub = new IdentityStore(registry.context()).cardFile().resolveSibling("key.pub");
        Files.delete(pub);
        Files.createDirectories(pub.resolve("in-the-way"));

        assertThat(rotate("c1")).isEqualTo(70);

        assertThat(answer().get("reason")).isEqualTo("failed");
        assertThat(answer().get("id")).isEqualTo("c1");
    }

    /** The old key signs the handing on; without it, a card only half signed would be made. */
    @Test
    void the_old_key_must_be_served_too() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        forget("alice");

        assertThat(rotate("c1")).isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("no-key");
        assertThat((String) answer().get("words")).contains(registry.pin("alice"));
        assertThat(ops()).doesNotExist();
        assertThat(keptText()).isEqualTo(card);
    }

    /** A revoked key's signature is refused everywhere, so it may hand nothing on. */
    @Test
    void a_revoked_key_is_refused_naming_the_owners() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        IdentityRun.revoke(registry, "alice");

        assertThat(rotate("c1")).isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("revoked");
        assertThat((List<?>) answer().get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));
        assertThat(ops()).doesNotExist();
    }

    /** Whom to ask are those who may; an owner's offline backup key is nobody to ask. */
    @Test
    void who_may_names_each_owner_once_and_no_backup_key() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        organization.filed("michael");
        organization
                .writes()
                .addBackupKey(
                        organization.change("michael", "michael", "bk"),
                        null,
                        registry.key("hank"));

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> plan = (Map<?, ?>) answer().get("plan");
        assertThat((List<?>) plan.get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));
    }

    /**
     * A card the forge refuses names nobody: its words are whoever wrote them, so neither name nor
     * address goes before the person as one to ask.
     */
    @Test
    void who_may_shows_nothing_of_an_owners_refused_card() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        organization.filed("michael");
        organization.filed("bob");
        organization
                .writes()
                .addOrganizationOwner(organization.change("michael", "michael", "o1"), "bob");
        registry.forge.keys.put("bob", List.of(registry.key("hank")));

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--dry-run",
                                "--json"))
                .as(err())
                .isZero();

        final List<?> whoMay = (List<?>) ((Map<?, ?>) answer().get("plan")).get("who_may");
        final Map<?, ?> bob =
                whoMay.stream()
                        .map(each -> (Map<?, ?>) each)
                        .filter(each -> "bob".equals(each.get("login")))
                        .findFirst()
                        .orElseThrow();
        assertThat(bob.get("name")).as("of a refused card").isNull();
        assertThat(bob.get("email")).as("of a refused card").isNull();
    }

    /** Both ways at once, an entry without KeePassXC, or a bad id, is wrong use. */
    @Test
    void both_ways_at_once_or_an_entry_alone_is_wrong_use() {
        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--keepassxc",
                                "db.kdbx",
                                "--key",
                                registry.pin("alice")))
                .isEqualTo(64);
        assertThat(identity("rotate", "--key", registry.pin("alice"), "--entry", "a/b"))
                .isEqualTo(64);
        assertThat(identity("rotate", "--key", registry.pin("alice"), "--id", "a/b")).isEqualTo(64);
    }

    /** A rotation into KeePassXC signs with the new key once the agent serves it, and the old. */
    @Test
    void a_rotation_into_keepassxc_files_with_the_key_the_agent_serves_and_the_old_one()
            throws Exception {
        registry.agent("alice");
        filed();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);
        assertThat(
                        identity(
                                "rotate",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice-2",
                                "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("action")).isEqualTo("rotate");
        assertThat(answer.get("entry")).isEqualTo("alice-2");
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("predecessor")).isEqualTo(registry.pin("alice"));
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(answer.get("fingerprint"));
        assertThat(card.predecessor()).isEqualTo(registry.key("alice"));
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("alice-2"));
        assertThat(keepass.holding(card.key())).isEmpty();
    }

    /**
     * A key already made into KeePassXC is the person's: refused after that, they must learn which
     * it is, to go on with it, and not make a third.
     */
    @Test
    void a_key_made_into_keepassxc_and_then_refused_is_named_to_go_on_with() throws Exception {
        registry.agent("alice");
        final String card = filed();
        final String base = organization.tip();
        organization.filed("hank");
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        identity(
                                "rotate",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice-2",
                                "--base",
                                base,
                                "--id",
                                "c1",
                                "--json"))
                .as(err())
                .isEqualTo(65);

        final Map<?, ?> refused = answer();
        assertThat(refused.get("reason")).isEqualTo("moved-on");
        assertThat(refused.get("entry")).isEqualTo("alice-2");
        assertThat(keptText()).isEqualTo(card);
        final int calls = keepass.calls().size();

        assertThat(
                        identity(
                                "rotate",
                                "--key",
                                (String) refused.get("fingerprint"),
                                "--id",
                                "c1",
                                "--json"))
                .as(err())
                .isZero();
        assertThat(keepass.calls()).as("no second key").hasSize(calls);
        assertThat(kept().fingerprint()).isEqualTo(refused.get("fingerprint"));
    }

    /** Not served in time: the entry is kept, no card made and no operation begun. */
    @Test
    void a_rotation_whose_key_is_not_served_makes_no_card_and_begins_nothing() throws Exception {
        registry.agent("alice");
        final String filed = filed();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.HOLDS);

        assertThat(
                        identity(
                                "rotate",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice-2",
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("key-not-served");
        assertThat(answer().get("entry")).isEqualTo("alice-2");
        assertThat(kept().body()).isEqualTo(PersonCard.read(filed, "filed").body());
        assertThat(ops()).doesNotExist();
    }

    /** An old key the agent does not serve could not sign: no entry is made for it. */
    @Test
    void a_rotation_whose_old_key_is_not_served_makes_no_keepassxc_entry() throws Exception {
        registry.agent("alice");
        filed();
        forget("alice");
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);

        assertThat(identity("rotate", "--keepassxc", keepass.database.toString(), "--json"))
                .isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("no-key");
        assertThat(keepass.calls()).isEmpty();
    }

    /** A change standing already leaves no KeePassXC entry behind, for a card that could not go. */
    @Test
    void a_change_that_reached_the_store_makes_no_keepassxc_entry() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        assertThat(rotate("c1")).as(err()).isZero();
        stoppedBeforeItNoted("c1");
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        identity(
                                "rotate",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--id",
                                "c2",
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(keepass.calls()).isEmpty();
    }

    /** Without a terminal no rotation asks KeePassXC, and nothing is begun. */
    @Test
    void a_rotation_without_a_terminal_is_refused_64() throws Exception {
        registry.agent("alice");
        filed();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);
        registry.terminal = false;

        assertThat(identity("rotate", "--keepassxc", keepass.database.toString(), "--json"))
                .isEqualTo(64);
        assertThat(answer().get("reason")).isEqualTo("no-terminal");
        assertThat(keepass.calls()).isEmpty();
        assertThat(ops()).doesNotExist();
    }

    /** A replacement into KeePassXC answers its card and plan as one with --key does. */
    @Test
    void a_lost_key_replaced_into_keepassxc_files_nothing_and_answers_its_card() throws Exception {
        registry.agent("alice");
        filed();
        forget("alice");
        final String before = organization.tip();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--entry",
                                "alice-new",
                                "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("action")).isEqualTo("revoke-lost");
        assertThat(answer.get("entry")).isEqualTo("alice-new");
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(organization.tip()).isEqualTo(before);
        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(answer.get("fingerprint"));
        assertThat(card.predecessor()).isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("alice-new"));
    }

    /** The three verbs answer one refusal with one code, before keepassxc-cli starts. */
    @Test
    void rotate_and_revoke_lost_refuse_a_terminal_as_create_does() throws Exception {
        registry.agent("alice");
        filed();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES);
        final java.nio.file.Path missing = dir.resolve("no-such-descriptor");
        final String db = keepass.database.toString();

        assertThat(
                        IdentityRun.identity(
                                organization,
                                keepass.on(missing, missing),
                                "rotate",
                                "--keepassxc",
                                db,
                                "--json"))
                .isEqualTo(64);
        assertThat(answer().get("reason")).isEqualTo("no-terminal");
        assertThat(
                        IdentityRun.identity(
                                organization,
                                keepass.on(missing, missing),
                                "revoke",
                                "--lost",
                                "--keepassxc",
                                db,
                                "--json"))
                .isEqualTo(64);
        assertThat(answer().get("reason")).isEqualTo("no-terminal");
        assertThat(keepass.calls()).isEmpty();
    }

    /** A retried rotation signs with the key the first run made, and asks KeePassXC nothing. */
    @Test
    void a_retried_rotation_takes_the_key_the_first_run_made() throws Exception {
        registry.agent("alice");
        filed();
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.SERVES_AFTER_UNLOCK);
        keepass.running(false);
        final String db = keepass.database.toString();
        assertThat(
                        identity(
                                "rotate",
                                "--keepassxc",
                                db,
                                "--entry",
                                "alice-2",
                                "--id",
                                "r1",
                                "--json"))
                .isEqualTo(65);
        final Object fingerprint = answer().get("fingerprint");
        assertThat(answer().get("reason")).isEqualTo("key-not-served");
        keepass.running(true);

        assertThat(
                        IdentityRun.identity(
                                organization,
                                keepass.store(java.time.Duration.ofSeconds(10)),
                                "rotate",
                                "--keepassxc",
                                db,
                                "--id",
                                "r1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("fingerprint")).isEqualTo(fingerprint);
        assertThat(answer().get("entry")).isEqualTo("alice-2");
        assertThat(kept().fingerprint()).isEqualTo(fingerprint);
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("alice-2"));
    }

    /** A refused password replaces nothing. */
    @Test
    void a_replacement_keepassxc_refuses_is_key_store_refused() throws Exception {
        registry.agent("alice");
        filed();
        forget("alice");
        final KeePassXcStandIn keepass = keepass(KeePassXcStandIn.Mode.REFUSES);

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--keepassxc",
                                keepass.database.toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("key-store-refused");
        assertThat(ops()).doesNotExist();
    }

    /** A dry run says who must accept and which hosts' vaults are made again, and keeps nothing. */
    @Test
    void a_dry_run_names_who_accepts_and_the_hosts_and_keeps_nothing() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        organization.filed("michael");
        organization
                .writes()
                .addHost(organization.change("michael", "michael", "h1"), "wharf6", "alice");

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--dry-run",
                                "--json"))
                .as(err())
                .isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        final Map<?, ?> plan = (Map<?, ?>) answer.get("plan");
        assertThat((List<?>) plan.get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));
        assertThat(answer.get("hosts"))
                .isEqualTo(List.of(Map.of("host", "wharf6", "vault", "reset")));
        assertThat(answer.get("sign_ins"))
                .isEqualTo(List.of(Map.of("host", "wharf6", "entries", List.of())));
        assertThat(ops()).doesNotExist();
        assertThat(keptText()).isEqualTo(card);
    }

    /**
     * A lost key's replacement is signed by the new key alone, which the store would refuse from
     * the person: nothing is filed, and the card goes by hand to an owner, who is named.
     */
    @Test
    void a_lost_key_replacement_files_nothing_and_answers_its_card_and_who_accepts()
            throws Exception {
        registry.agent("alice-new");
        filed();
        final String before = organization.tip();

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(organization.tip()).as("it files nothing").isEqualTo(before);
        final Map<?, ?> answer = answer();
        assertThat(answer.get("revision")).isEqualTo(before);
        assertThat(answer.get("action")).isEqualTo("revoke-lost");
        final PersonCard card = PersonCard.read((String) answer.get("card"), "answered");
        assertThat(card.fingerprint()).isEqualTo(registry.pin("alice-new"));
        assertThat(card.predecessor()).isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(kept().fingerprint()).isEqualTo(registry.pin("alice-new"));
        final Map<?, ?> plan = (Map<?, ?>) answer.get("plan");
        assertThat((List<?>) plan.get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));
        assertThat(identity("show", "--json")).isZero();
        assertThat(answer().get("pending"))
                .as("it waits for an owner")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "id",
                                        "l1",
                                        "action",
                                        "revoke-lost",
                                        "fingerprint",
                                        registry.pin("alice-new"))));
    }

    /**
     * A person who still has a backup key loses it by doing these in the wrong order: taken off the
     * forge, the old key's backup no longer counts. The answer must say the backup comes first.
     */
    @Test
    void a_lost_keys_answer_puts_the_backup_before_the_key_comes_off_the_forge() throws Exception {
        registry.agent("alice-new");
        filed();

        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--json"))
                .as(err())
                .isZero();

        final String order = (String) ((Map<?, ?>) answer().get("plan")).get("order");
        assertThat(order.indexOf("backup-key"))
                .as("first the backup")
                .isNotNegative()
                .isLessThan(order.indexOf("off the forge"));
        assertThat(order.indexOf("off the forge"))
                .as("then the key off the forge, and only after that an owner")
                .isLessThan(order.indexOf("org person replace"));
    }

    /** A rotation that reached nowhere is no reason to refuse someone whose key is gone. */
    @Test
    void a_lost_key_goes_past_a_pending_rotation_that_reached_nowhere() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        organization.listed("alice", "bob");
        organization.unreachableForChanges = true;
        assertThat(rotate("c1")).isEqualTo(69);
        organization.unreachableForChanges = false;

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("bob"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("predecessor")).isEqualTo(registry.pin("alice"));
        assertThat(IdentityOperation.read(registry.context(), "c1")).isNull();
    }

    /**
     * A run that may have pushed leaves its row, and only the key it hands on from could end it.
     * With that key revoked the person would read it under pending for good.
     */
    @Test
    void a_lost_key_retires_a_row_that_hands_on_from_it_and_says_so() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        organization.listed("alice", "bob");
        organization.unreachableForChanges = true;
        assertThat(rotate("c1")).isEqualTo(69);
        organization.unreachableForChanges = false;
        final Path row = ops().resolve("c1.json");
        final Map<String, Object> tried =
                new LinkedHashMap<>((Map<String, Object>) Json.parse(Files.readString(row)));
        tried.put("push_tried", true);
        Files.writeString(row, Json.write(tried));

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("bob"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("retired")).as("what it ended").isEqualTo(List.of("c1"));
        assertThat(identity("show", "--json")).as(err()).isZero();
        final List<Object> waiting = new java.util.ArrayList<>();
        ((List<?>) answer().get("pending"))
                .forEach(each -> waiting.add(((Map<?, ?>) each).get("id")));
        assertThat(waiting)
                .as("the replacement waits for an owner, and nothing else does")
                .isEqualTo(List.of("l1"));
    }

    /** One that reached the store stands: what is lost now is that key, which an owner replaces. */
    @Test
    void a_lost_key_goes_past_a_pending_rotation_that_reached_the_store_and_is_made_against_it()
            throws Exception {
        registry.agent("alice", "alice-new", "bob");
        final String old = filed();
        organization.listed("alice", "bob");
        assertThat(rotate("c1")).as(err()).isZero();
        stoppedBeforeItNoted("c1");
        new IdentityStore(registry.context()).keep(old);

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("bob"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("predecessor")).isEqualTo(registry.pin("alice-new"));
        final IdentityOperation rotated = IdentityOperation.read(registry.context(), "c1");
        assertThat(rotated.done()).as("its card stands").isTrue();
        assertThat(rotated.kept()).isTrue();
    }

    /** The same id again answers the same card, never a second key. */
    @Test
    void a_lost_keys_id_asked_again_answers_the_same_card() throws Exception {
        registry.agent("alice-new", "bob");
        filed();
        organization.listed("alice", "bob");
        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "l1",
                                "--json"))
                .isZero();
        final Object card = answer().get("card");

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("bob"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("again")).isEqualTo(true);
        assertThat(answer().get("card")).isEqualTo(card);
    }

    /** Someone cut off from the store has lost the key all the same: stale, and it says so. */
    @Test
    void a_store_out_of_reach_still_answers_a_card_on_the_last_state() throws Exception {
        registry.agent("alice-new");
        filed();
        assertThat(identity("show", "--json")).as("it read the store once").isZero();
        final String last = organization.tip();
        organization.outOfReach();

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "l1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("stale")).isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(last);
        assertThat((List<?>) ((Map<?, ?>) answer().get("plan")).get("who_may")).isNotEmpty();
    }

    /** And with nothing read yet, what the organization holds cannot be said. */
    @Test
    void a_store_out_of_reach_with_nothing_read_is_cannot_say() throws Exception {
        registry.agent("alice-new");
        filed();
        organization.outOfReach();

        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--json"))
                .isEqualTo(69);
        assertThat(answer().get("reason")).isEqualTo("cannot-say");
    }

    /** A machine with no organization still makes the card, to hand to whoever may take it. */
    @Test
    void no_organization_still_makes_a_lost_keys_card() throws Exception {
        registry.agent("alice-new");
        filed();
        Files.delete(registry.paths.xdg().config().resolve("organization.yml"));

        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--json"))
                .as(err())
                .isZero();

        assertThat(answer().get("revision")).isNull();
        assertThat((List<?>) ((Map<?, ?>) answer().get("plan")).get("who_may")).isEmpty();
    }

    /** Written as an old run left it, with what no record says any more. */
    private void record(final String file, final String id, final String card) throws Exception {
        Files.createDirectories(ops());
        Files.writeString(
                ops().resolve(file + ".json"),
                Json.write(
                        Map.of(
                                "id",
                                id,
                                "action",
                                "rotate",
                                "fingerprint",
                                PersonCard.read(card, "test").fingerprint(),
                                "predecessor",
                                registry.pin("alice"),
                                "card",
                                card,
                                "done",
                                false,
                                "kept",
                                true)));
    }

    /** A record copied under another name would be filed, and kept, as that other one. */
    @Test
    void a_record_whose_id_is_not_its_name_is_damaged() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        record("foo", "bar", registry.card("alice", "alice-new", "alice"));
        final String before = organization.tip();

        assertThat(rotate("foo")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(ops().resolve("bar.json")).doesNotExist();
        assertThat(organization.tip()).isEqualTo(before);
    }

    /** A record holding another person's card would file it with this person's agent. */
    @Test
    void a_record_of_another_login_is_damaged() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        final String bobs =
                PersonCard.write(
                        "bob",
                        "Bob",
                        "",
                        registry.keys.get("alice-new"),
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        registry.keys.get("alice"));
        record("foo", "foo", bobs);
        final String before = organization.tip();

        assertThat(rotate("foo")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(organization.tip()).isEqualTo(before);
    }

    /** A directory the umask left readable would show others which keys the person changes. */
    @Test
    void the_identity_and_ops_directories_are_made_owner_only() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path identity = registry.paths.xdg().state().resolve("identity");
        Files.createDirectories(identity);
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(identity("rotate", "--key", registry.pin("alice-new"))).as(err()).isZero();
        assertThat(Files.getPosixFilePermissions(identity))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(ops()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /** A linked directory would put the record wherever the link points. */
    @Test
    void a_linked_identity_directory_is_refused() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createDirectories(registry.paths.xdg().state());
        Files.createSymbolicLink(registry.paths.xdg().state().resolve("identity"), elsewhere);

        assertThat(identity("rotate", "--key", registry.pin("alice-new"))).isEqualTo(66);
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).isEmpty();
        }
        assertThat(keptText()).isEqualTo(card);
    }

    /**
     * A run at once that kept a replacement under the id must not be answered as this rotation: the
     * caller would take another action's card for their own.
     */
    @Test
    void begin_refuses_an_id_kept_for_the_other_action() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--id", "x"))
                .as(err())
                .isZero();
        final IdentityOperation lost = IdentityOperation.read(registry.context(), "x");
        final IdentityOperation rotation =
                new IdentityOperation(
                        "",
                        IdentityOperation.ROTATE,
                        lost.fingerprint(),
                        lost.predecessor(),
                        lost.card(),
                        false,
                        null,
                        false,
                        false);

        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () -> new IdentityChange(registry.context()).begin("x", rotation));
        assertThat(refused.code()).isEqualTo(64);
        assertThat(refused.words()).contains("revoke-lost");
    }

    /**
     * A linked ops directory holds records nobody here wrote: one would bring an old card back as
     * the person's before anything was written.
     */
    @Test
    void a_record_read_through_a_linked_ops_directory_is_refused() throws Exception {
        registry.agent("alice", "alice-new");
        final String old = filed();
        assertThat(rotate("c1")).as(err()).isZero();
        stoppedBeforeItNoted("c1");
        new IdentityStore(registry.context()).keep(old);
        final Path elsewhere = dir.resolve("elsewhere-ops");
        Files.move(ops(), elsewhere);
        Files.createSymbolicLink(ops(), elsewhere);

        assertThat(rotate("c1")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(keptText()).isEqualTo(old);
    }

    /** A card read through a linked directory is anybody's card. */
    @Test
    void a_card_read_through_a_linked_identity_directory_is_refused() throws Exception {
        registry.agent("alice");
        filed();
        final Path identity = registry.paths.xdg().config().resolve("identity");
        final Path elsewhere = dir.resolve("elsewhere-identity");
        Files.move(identity, elsewhere);
        Files.createSymbolicLink(identity, elsewhere);

        assertThat(identity("show", "--json")).isEqualTo(66);
    }

    /** What is read through a linked state directory may not be what was read here. */
    @Test
    void a_linked_state_directory_is_refused() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        final Path state = registry.paths.xdg().state();
        Files.createDirectories(state);
        final Path elsewhere = dir.resolve("elsewhere-state");
        Files.move(state, elsewhere);
        Files.createSymbolicLink(state, elsewhere);

        assertThat(identity("rotate", "--key", registry.pin("alice-new"))).isEqualTo(66);
        assertThat(keptText()).isEqualTo(card);
    }

    /**
     * Harm guarded: a change of key that reaches the organization any other way than through its
     * store's writes, or under another person's name.
     */
    @Test
    void a_rotation_goes_through_the_stores_writes_alone() throws Exception {
        registry.agent("alice", "alice-new");
        final PersonCard before = PersonCard.read(filed(), "the first card");
        final List<String> calls = new ArrayList<>();
        final List<OrganizationWrites.Change> changes = new ArrayList<>();
        final List<String> cards = new ArrayList<>();
        final OrganizationWrites standIn =
                (OrganizationWrites)
                        java.lang.reflect.Proxy.newProxyInstance(
                                OrganizationWrites.class.getClassLoader(),
                                new Class<?>[] {OrganizationWrites.class},
                                (proxy, method, arguments) -> {
                                    calls.add(method.getName());
                                    changes.add((OrganizationWrites.Change) arguments[0]);
                                    cards.add((String) arguments[1]);
                                    return new OrganizationWrites.Written("r1", "c1", false);
                                });
        final IdentityOrganization.Opener opener =
                (context, repository) -> {
                    final IdentityOrganization.Opened real =
                            organization.opener().open(context, repository);
                    return new IdentityOrganization.Opened(
                            real.store(), real.identities(), real.forge(), signing -> standIn);
                };
        final String tip = organization.tip();

        assertThat(
                        IdentityRun.identity(
                                organization,
                                opener,
                                "rotate",
                                "--key",
                                registry.pin("alice-new"),
                                "--id",
                                "c1",
                                "--json"))
                .as(err())
                .isZero();

        assertThat(calls).containsExactly("changeCard");
        final OrganizationWrites.Change change = changes.getFirst();
        assertThat(change.id()).isEqualTo("c1");
        assertThat(change.base()).isEqualTo(tip);
        assertThat(change.acting().login()).isEqualTo("alice");
        final PersonCard filed = PersonCard.read(cards.getFirst(), "the card filed");
        assertThat(filed.key()).isEqualTo(registry.key("alice-new"));
        assertThat(filed.predecessor()).isEqualTo(before.key());
        assertThat(organization.tip()).as("nothing else wrote to the repository").isEqualTo(tip);
    }
}
