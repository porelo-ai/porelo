package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.organization.git.RegistryState;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards the change of a person's own key: {@code identity rotate}, {@code revoke --lost}, {@code
 * publish} and {@code status}, against a real registry checkout, a real ssh-agent and a bare
 * repository the checkout pushes to.
 */
class IdentityRotateCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private Path bare;

    /** Where the registry is pushed: the bare repository, or one that is not there. */
    private Path pushTo;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    /** The stand-in KeePassXC, once a test makes one. */
    private KeePassXcStandIn standIn;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
        registry.founded();
        bare = dir.resolve("origin.git");
        registry.runner.runOrFail(Command.of("git", "init", "-q", "--bare", bare.toString()));
        // Git fetches from the origin the reader names, and the test pushes that address here.
        pushTo = bare;
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
    }

    /** alice's first card filed and read here, and kept as this machine's own. */
    private String filed() throws Exception {
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        final String card = registry.card("alice", "alice");
        final Path file = dir.resolve("alice.card");
        Files.writeString(file, card);
        assertThat(
                        registry.registry(
                                "file",
                                "card",
                                registry.repo.toString(),
                                "--card",
                                file.toString()))
                .as(registry.err.toString())
                .isZero();
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        new IdentityStore(registry.context()).keep(card);
        registry.forge.keys.put("alice", List.of(registry.key("alice"), registry.key("alice-new")));
        return card;
    }

    /** hank's wharf6 names the project demo, whose admin is alice. */
    private void host() throws Exception {
        final Path access = dir.resolve("access.yml");
        Files.writeString(access, registry.access("demo"));
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
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
    }

    private int identity(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final RegistryReadCommand.Forges forges = (address, token) -> registry.forge;
        final var verbs = cli.getSubcommands().get("identity").getSubcommands();
        final IdentityChange.Remotes remotes =
                address -> {
                    assertThat(address).isEqualTo(RegistryCheckout.ORIGIN);
                    return new IdentityChange.Remote(pushTo.toString(), List.of("file"));
                };
        ((IdentityRotateCommand) verbs.get("rotate").getCommand()).forges = forges;
        ((IdentityRotateCommand) verbs.get("rotate").getCommand()).remotes = remotes;
        ((IdentityPublishCommand) verbs.get("publish").getCommand()).forges = forges;
        ((IdentityPublishCommand) verbs.get("publish").getCommand()).remotes = remotes;
        if (standIn != null) {
            final KeePassXcKey store = standIn.store(java.time.Duration.ofMillis(800));
            ((IdentityRotateCommand) verbs.get("rotate").getCommand()).store = store;
            ((IdentityRevokeCommand) verbs.get("revoke").getCommand()).store = store;
        }
        final List<String> all = new ArrayList<>(List.of("identity"));
        all.addAll(List.of(args));
        return cli.execute(all.toArray(String[]::new));
    }

    /** The stand-in KeePassXC the verbs run, on a terminal, giving keys to the test's agent. */
    private KeePassXcStandIn keepass(final KeePassXcStandIn.Mode mode) throws Exception {
        registry.terminal = true;
        standIn = new KeePassXcStandIn(dir, mode, registry.environment().apply("SSH_AUTH_SOCK"));
        return standIn;
    }

    /** Takes a login's key out of the agent, as a lost or locked key leaves it. */
    private void forget(String login) {
        registry.runner.runOrFail(
                Command.of("ssh-add", "-d", registry.keyFiles.get(login) + ".pub")
                        .withEnvironment(
                                Map.of(
                                        "SSH_AUTH_SOCK",
                                        registry.environment().apply("SSH_AUTH_SOCK"))));
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString());
    }

    private PersonCard kept() throws Exception {
        return new IdentityStore(registry.context()).card().orElseThrow();
    }

    private Path ops() {
        return IdentityOperation.directory(registry.context());
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String pushed() {
        return registry.runner
                .runOrFail(
                        Command.of(
                                "git",
                                "-C",
                                bare.toString(),
                                "cat-file",
                                "blob",
                                "main:people/alice.card"))
                .standardOutput();
    }

    /**
     * The point of the verb: a card signed by both keys, filed and pushed to the registry, which
     * reads the new key as alice's; without the push the registry would never hear of it.
     */
    @Test
    void a_rotation_files_and_pushes_a_card_signed_by_both_keys() throws Exception {
        registry.agent("alice", "alice-new");
        filed();

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("id")).isEqualTo("rot-" + today() + "-1");
        assertThat(answer.get("action")).isEqualTo("rotate");
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice-new"));
        assertThat(answer.get("predecessor")).isEqualTo(registry.pin("alice"));
        final List<?> published = (List<?>) answer.get("published");
        assertThat(published).hasSize(1);
        final Map<?, ?> place = (Map<?, ?>) published.getFirst();
        assertThat(place.get("where")).isEqualTo("registry");
        assertThat(place.get("address")).isEqualTo(RegistryCheckout.ORIGIN);
        assertThat(place.get("status")).isEqualTo("done");
        assertThat(place.get("who_may")).isEqualTo(List.of());
        assertThat(out.toString()).contains("\"why\":null");

        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(registry.pin("alice-new"));
        assertThat(card.predecessor()).isEqualTo(registry.key("alice"));
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(card.expires()).isEqualTo(card.created().plusDays(30));
        assertThat(pushed())
                .isEqualTo(
                        Files.readString(registry.paths.xdg().config().resolve("identity/card")));

        final Path record = ops().resolve("rot-" + today() + "-1.json");
        assertThat(Files.getPosixFilePermissions(record))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(((Map<?, ?>) Json.parse(Files.readString(record))).get("done")).isEqualTo(true);

        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        final Map<?, ?> person = (Map<?, ?>) ((List<?>) registry.answer().get("people")).getFirst();
        assertThat(person.get("fingerprint")).isEqualTo(registry.pin("alice-new"));
        assertThat(person.get("state")).isEqualTo(RegistryState.TRUSTED);
    }

    /** A second run of the same id must never make a second card, whatever key it names. */
    @Test
    void the_same_id_again_answers_the_same_operation() throws Exception {
        registry.agent("alice", "alice-new", "bob");
        filed();
        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--id", "change-1"))
                .as(err.toString())
                .isZero();
        final String first = kept().body();
        final String head = registry.head();

        assertThat(identity("rotate", "--key", registry.pin("bob"), "--id", "change-1", "--json"))
                .as(err.toString())
                .isZero();
        assertThat(answer().get("fingerprint")).isEqualTo(registry.pin("alice-new"));
        assertThat(kept().body()).isEqualTo(first);
        assertThat(registry.head()).isEqualTo(head);
    }

    /**
     * A push that fails is answered refused with why, and the card stays recorded; publish sends it
     * again and status reads it back without sending anything.
     */
    @Test
    void a_refused_push_is_answered_and_publish_sends_it_again() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        pushTo = dir.resolve("nowhere.git");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--id", "r1", "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> place = (Map<?, ?>) ((List<?>) answer().get("published")).getFirst();
        assertThat(place.get("status")).isEqualTo("refused");
        assertThat((String) place.get("why")).contains("git push to origin failed");
        final String committed = registry.head();

        pushTo = bare;
        assertThat(identity("status", "--id", "r1", "--json")).isZero();
        assertThat(((Map<?, ?>) ((List<?>) answer().get("published")).getFirst()).get("status"))
                .as("status sends nothing")
                .isEqualTo("refused");

        assertThat(identity("publish", "--id", "r1", "--json")).as(err.toString()).isZero();
        assertThat(((Map<?, ?>) ((List<?>) answer().get("published")).getFirst()).get("status"))
                .isEqualTo("done");
        assertThat(registry.head()).as("the commit made before is pushed").isEqualTo(committed);
        assertThat(pushed())
                .isEqualTo(registry.git("cat-file", "blob", "HEAD:people/alice.card") + "\n");
        assertThat(identity("status", "--id", "r1", "--json")).isZero();
        assertThat(((Map<?, ?>) ((List<?>) answer().get("published")).getFirst()).get("status"))
                .isEqualTo("done");
    }

    /** The old key signs the handing on; without it, a card only half signed would be made. */
    @Test
    void the_old_key_must_be_served_too() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        forget("alice");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json")).isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("key-not-in-agent");
        assertThat((String) answer().get("words")).contains(registry.pin("alice"));
        assertThat(ops()).doesNotExist();
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(card);
    }

    /** A revoked key's signature is refused everywhere, so it may hand nothing on. */
    @Test
    void a_revoked_key_is_refused_naming_the_owners() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path krl = registry.paths.projects().revocations();
        Files.createDirectories(krl.getParent());
        registry.runner.runOrFail(
                Command.of(
                        "ssh-keygen",
                        "-q",
                        "-k",
                        "-f",
                        krl.toString(),
                        registry.keyFiles.get("alice") + ".pub"));

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json")).isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("revoked");
        assertThat(out.toString())
                .contains(
                        "\"who_may\":[{\"login\":\"michael\",\"name\":null," + "\"email\":null}]");
        assertThat(ops()).doesNotExist();
    }

    /** Both ways at once, an entry without KeePassXC, or a bad id, is wrong use. */
    @Test
    void both_ways_at_once_or_an_entry_alone_is_wrong_use() throws Exception {
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
    void a_rotation_into_keepassxc_signs_with_the_key_the_agent_serves_and_the_old_one()
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
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("action")).isEqualTo("rotate");
        assertThat(answer.get("entry")).isEqualTo("alice-2");
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("predecessor")).isEqualTo(registry.pin("alice"));
        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(answer.get("fingerprint"));
        assertThat(card.predecessor()).isEqualTo(registry.key("alice"));
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("alice-2"));
        assertThat(keepass.holding(card.key())).isEmpty();
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
                .isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("key-not-in-agent");
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

    /** A replacement into KeePassXC answers its places as one with --key does. */
    @Test
    void a_lost_key_replaced_into_keepassxc_waits_at_every_place() throws Exception {
        registry.agent("alice", "hank");
        filed();
        host();
        forget("alice");
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
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("action")).isEqualTo("revoke-lost");
        assertThat(answer.get("entry")).isEqualTo("alice-new");
        assertThat(answer.get("agent")).isEqualTo("holds");
        for (Object each : (List<?>) answer.get("published")) {
            assertThat(((Map<?, ?>) each).get("status")).isEqualTo("pending");
        }
        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(answer.get("fingerprint"));
        assertThat(card.predecessor()).isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(keepass.calls()).isEqualTo(keepass.madeInto("alice-new"));
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

    /**
     * A dry run names each place with who must accept there, and the host vaults made again, and
     * keeps nothing.
     */
    @Test
    void a_dry_run_names_the_places_and_keeps_nothing() throws Exception {
        registry.agent("alice", "alice-new", "hank");
        final String card = filed();
        host();

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--dry-run",
                                "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        final List<?> places = (List<?>) answer.get("places");
        assertThat(places).hasSize(2);
        final Map<?, ?> owners = (Map<?, ?>) places.get(0);
        assertThat(owners.get("where")).isEqualTo("registry");
        assertThat(owners.get("status")).isEqualTo("planned");
        assertThat(((Map<?, ?>) ((List<?>) owners.get("who_may")).getFirst()).get("login"))
                .isEqualTo("michael");
        final Map<?, ?> walk = (Map<?, ?>) places.get(1);
        assertThat(walk.get("where")).isEqualTo("host");
        assertThat(walk.get("host")).isEqualTo("wharf6");
        assertThat(((Map<?, ?>) ((List<?>) walk.get("who_may")).getFirst()).get("login"))
                .isEqualTo("hank");
        assertThat(answer.get("hosts"))
                .isEqualTo(List.of(Map.of("host", "wharf6", "vault", "reset")));
        assertThat(answer.get("sign_ins"))
                .isEqualTo(List.of(Map.of("host", "wharf6", "entries", List.of())));
        assertThat(ops()).doesNotExist();
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(card);
    }

    /**
     * A lost key's replacement is signed by the new key alone and waits at every place for the
     * people named; nothing is filed with the person's own key, which the registry would refuse.
     */
    @Test
    void a_lost_key_replacement_waits_at_every_place_for_who_may_accept() throws Exception {
        registry.agent("alice", "alice-new", "hank");
        filed();
        host();
        forget("alice");
        final String head = registry.head();

        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("id")).isEqualTo("lost-" + today() + "-1");
        assertThat(answer.get("action")).isEqualTo("revoke-lost");
        assertThat(answer.get("predecessor")).isEqualTo(registry.pin("alice"));
        for (Object each : (List<?>) answer.get("published")) {
            assertThat(((Map<?, ?>) each).get("status")).isEqualTo("pending");
            assertThat((List<?>) ((Map<?, ?>) each).get("who_may")).isNotEmpty();
        }
        final PersonCard card = kept();
        assertThat(card.fingerprint()).isEqualTo(registry.pin("alice-new"));
        assertThat(card.predecessor()).isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(registry.head()).isEqualTo(head);
        assertThat(
                        ((Map<?, ?>)
                                        Json.parse(
                                                Files.readString(
                                                        ops().resolve(
                                                                        "lost-" + today()
                                                                                + "-1.json"))))
                                .get("done"))
                .isEqualTo(false);
    }

    /**
     * Each followed project whose access file in force names the person is a place, waiting for its
     * admins; its host is one too.
     */
    @Test
    void a_followed_project_naming_the_person_waits_for_its_admins() throws Exception {
        registry.agent("alice", "alice-new", "hank");
        filed();
        host();
        followedDemo();

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        final List<?> places = (List<?>) answer().get("published");
        assertThat(places).hasSize(3);
        assertThat(places.get(1))
                .isEqualTo(
                        Map.of(
                                "where",
                                "project",
                                "project",
                                "demo",
                                "status",
                                "pending",
                                "why",
                                "a project admin accepts it",
                                "who_may",
                                List.of(
                                        Map.of(
                                                "login",
                                                "michael",
                                                "name",
                                                "Michael",
                                                "email",
                                                "m@example.org"))));
        assertThat(((Map<?, ?>) places.get(2)).get("host")).isEqualTo("wharf6");
        assertThat(((Map<?, ?>) places.get(2)).get("why"))
                .isEqualTo("the host takes it at its next sync");
        assertThat(identity("status", "--id", "rot-" + today() + "-1")).isZero();
        assertThat(out.toString()).contains("project demo pending");
    }

    /** Whether the bare repository the test pushes to holds any branch. */
    private static boolean holdsBranch(RegistryCheckout registry, Path bare) {
        return !registry.runner
                .runOrFail(Command.of("git", "-C", bare.toString(), "for-each-ref", "refs/heads/"))
                .trimmedOutput()
                .isEmpty();
    }

    private Map<?, ?> registryPlace() {
        return (Map<?, ?>) ((List<?>) answer().get("published")).getFirst();
    }

    /**
     * The checkout's configuration is anybody's who can write to it: a push rewrite in it must not
     * send the card elsewhere, nor may its push URL or hooks take part.
     */
    @Test
    void the_push_takes_nothing_of_the_checkouts_configuration() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path elsewhere = dir.resolve("elsewhere.git");
        registry.runner.runOrFail(Command.of("git", "init", "-q", "--bare", elsewhere.toString()));
        registry.git("config", "url." + elsewhere + ".pushInsteadOf", bare.toString());
        registry.git("config", "remote.origin.pushurl", elsewhere.toString());

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(registryPlace().get("status")).isEqualTo("done");
        assertThat(pushed()).contains(registry.key("alice-new"));
        assertThat(holdsBranch(registry, elsewhere)).isFalse();
    }

    /** A checkout repointed to another repository would carry the card to whoever runs that one. */
    @Test
    void a_checkout_whose_origin_moved_is_refused_and_nothing_pushed() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        registry.git("remote", "set-url", "origin", "https://forge.example.org/evil/registry.git");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(registryPlace().get("status")).isEqualTo("refused");
        assertThat((String) registryPlace().get("why")).startsWith("origin-moved: ");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /** A token in the origin's URL is a secret: no answer, message or record may hold it. */
    @Test
    void an_origin_with_a_token_is_never_printed_or_kept() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        registry.git(
                "remote", "set-url", "origin", "https://alice:tok3n@forge.example.org/acme/x.git");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--id", "t1", "--json"))
                .as(err.toString())
                .isZero();
        assertThat(registryPlace().get("status")).isEqualTo("refused");
        assertThat(out.toString() + err).doesNotContain("tok3n");
        assertThat(Files.readString(ops().resolve("t1.json"))).doesNotContain("tok3n");
    }

    /**
     * Commits the person did not read as the registry would be pushed with their card; only the
     * commit read, and this card filed on it, may go.
     */
    @Test
    void a_checkout_holding_more_than_the_commit_read_is_refused_and_nothing_pushed()
            throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        registry.write("notes.txt", "not read\n");
        registry.commit("michael");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(registryPlace().get("status")).isEqualTo("refused");
        assertThat((String) registryPlace().get("why")).startsWith("checkout-moved: ");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /** A checkout swapped for a link points git at a directory nobody read. */
    @Test
    void a_checkout_replaced_by_a_link_is_refused() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path moved = dir.resolve("moved");
        Files.move(registry.repo, moved);
        Files.createSymbolicLink(registry.repo, moved);

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat((String) registryPlace().get("why")).startsWith("checkout-moved: ");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /**
     * A trust record that no longer reads may be the one naming the key revoked; leaving it out
     * would take the key.
     */
    @Test
    void an_unreadable_trust_record_refuses_rotate_and_revoke() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        Files.writeString(
                RegistryReadCommand.trustRecord(registry.context(), RegistryCheckout.ORIGIN),
                "{\"commit\":");

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json")).isEqualTo(66);
        assertThat(identity("revoke", "--lost", "--key", registry.pin("alice-new"), "--dry-run"))
                .isEqualTo(66);
        assertThat(ops()).doesNotExist();
    }

    /**
     * A card the registry refused names nobody, and a backup key is kept offline: neither may put a
     * name or an address before the person as one to ask, nor name its owner twice.
     */
    @Test
    void who_may_names_no_refused_card_and_no_backup_owner() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        // As a registry read leaves them: a backup key of michael's, and michael's own card, which
        // the forge does not list, refused.
        registry.write(
                "owners.yml",
                registry.owners()
                        .replace(
                                "host_admins:",
                                "  - login: michael\n    key: \""
                                        + registry.key("bob")
                                        + "\"\n    backup: true\nhost_admins:"));
        registry.write(
                "people/michael.card",
                PersonCard.write(
                        "michael",
                        "Spoofed Owner",
                        "spoof@example.org",
                        registry.keys.get("michael"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));
        registry.commit("michael");
        registry.forge.keys.put("michael", List.of(registry.key("bob")));
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        final RegistryState read =
                RegistryState.fromJson(
                        Files.readString(
                                RegistryReadCommand.trustRecord(
                                        registry.context(), RegistryCheckout.ORIGIN)));
        assertThat(read.owners())
                .anyMatch(owner -> owner.login().equals("michael") && owner.backup());
        assertThat(read.people())
                .anyMatch(
                        person ->
                                person.login().equals("michael")
                                        && person.state().equals(RegistryState.REFUSED));

        assertThat(
                        identity(
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--dry-run",
                                "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> place = (Map<?, ?>) ((List<?>) answer().get("places")).getFirst();
        final Map<String, Object> michael = new java.util.HashMap<>();
        michael.put("login", "michael");
        michael.put("name", null);
        michael.put("email", null);
        assertThat(place.get("who_may")).isEqualTo(List.of(michael));
    }

    /** Writes an operation record by hand, as anybody who can write the state directory could. */
    private void record(String file, String id, String card) throws Exception {
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
                                "published",
                                List.of(
                                        Map.of(
                                                "where",
                                                "registry",
                                                "address",
                                                RegistryCheckout.ORIGIN,
                                                "status",
                                                "pending",
                                                "who_may",
                                                List.of())),
                                "kept",
                                true)));
    }

    /** A record copied under another name would be published, and kept, as that other one. */
    @Test
    void a_record_whose_id_is_not_its_name_is_damaged() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        record("foo", "bar", registry.card("alice", "alice-new", "alice"));

        assertThat(identity("publish", "--id", "foo", "--json")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(ops().resolve("bar.json")).doesNotExist();
        assertThat(holdsBranch(registry, bare)).isFalse();
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
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        registry.keys.get("alice"));
        record("foo", "foo", bobs);

        assertThat(identity("publish", "--id", "foo", "--json")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /**
     * A run stopped after its record was kept and before its card was leaves the old card here; the
     * next run of the id keeps the recorded card, never a second one.
     */
    @Test
    void a_card_recorded_but_not_kept_is_kept_by_the_next_run() throws Exception {
        registry.agent("alice", "alice-new");
        final String old = filed();
        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--id", "r1"))
                .as(err.toString())
                .isZero();
        final Path file = ops().resolve("r1.json");
        final Map<String, Object> stopped =
                new java.util.LinkedHashMap<>(
                        (Map<String, Object>) Json.parse(Files.readString(file)));
        stopped.put("kept", false);
        Files.writeString(file, Json.write(stopped));
        new IdentityStore(registry.context()).keep(old);

        assertThat(identity("publish", "--id", "r1", "--json")).as(err.toString()).isZero();
        assertThat(kept().fingerprint()).isEqualTo(registry.pin("alice-new"));
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(stopped.get("card"));
        assertThat(((Map<?, ?>) Json.parse(Files.readString(file))).get("kept")).isEqualTo(true);
    }

    /** A directory the umask left readable would show others which keys the person changes. */
    @Test
    void the_identity_and_ops_directories_are_made_owner_only() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path identity = registry.paths.xdg().state().resolve("identity");
        Files.createDirectories(identity);
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(identity("rotate", "--key", registry.pin("alice-new"))).isZero();
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
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(card);
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
                .as(err.toString())
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
                        List.of(),
                        false);

        final IdentityChange.Refused refused =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                new IdentityChange(registry.context(), IdentityChange.NO_FORGE)
                                        .begin("x", rotation));
        assertThat(refused.code()).isEqualTo(64);
        assertThat(refused.words()).contains("revoke-lost");
    }

    /**
     * A partial checkout fetches a missing object by the program its configuration names: reading
     * the checkout must start nothing of it.
     */
    @Test
    void a_promisor_checkout_starts_no_program_of_its_configuration() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path marker = dir.resolve("marker");
        registry.git("config", "core.repositoryformatversion", "1");
        registry.git("config", "extensions.partialClone", "lazy");
        registry.git("config", "remote.lazy.url", "ssh://git@example.invalid/registry.git");
        registry.git("config", "remote.lazy.promisor", "true");
        registry.git("config", "core.sshCommand", "touch " + marker + "; exit 1; :");
        final String blob = registry.git("rev-parse", "HEAD:people/alice.card");
        Files.delete(
                registry.repo.resolve(
                        ".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2)));

        identity("rotate", "--key", registry.pin("alice-new"), "--json");
        assertThat(marker).doesNotExist();
    }

    /** The followed project demo, its access file in force naming michael admin and alice. */
    private Path followedDemo() throws Exception {
        final Path clone = registry.paths.projects().followedAccessClone("demo");
        Files.createDirectories(clone.resolve("cards"));
        final String michael =
                PersonCard.write(
                        "michael",
                        "Michael",
                        "m@example.org",
                        registry.keys.get("michael"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null);
        Files.writeString(clone.resolve("cards/michael.card"), michael);
        Files.writeString(clone.resolve("cards/alice.card"), registry.card("alice", "alice"));
        Files.writeString(
                clone.resolve("access.yml"),
                "persons:\n  michael:\n    fingerprint: "
                        + registry.pin("michael")
                        + "\n    role: admin\n    hosts: [wharf6]\n  alice:\n    fingerprint: "
                        + registry.pin("alice")
                        + "\n    role: developer\n    hosts: [wharf6]\n");
        final Map<String, String> plain =
                Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");
        for (List<String> git :
                List.of(
                        List.of("init", "-q"),
                        List.of("add", "-A"),
                        List.of(
                                "-c",
                                "user.name=a",
                                "-c",
                                "user.email=a@example.org",
                                "commit",
                                "-q",
                                "-m",
                                "access"))) {
            final List<String> all = new ArrayList<>(List.of("git", "-C", clone.toString()));
            all.addAll(git);
            registry.runner.runOrFail(Command.of(all).withEnvironment(plain));
        }
        final String commit =
                registry.runner
                        .runOrFail(
                                Command.of("git", "-C", clone.toString(), "rev-parse", "HEAD")
                                        .withEnvironment(plain))
                        .trimmedOutput();
        new FollowedProjects(registry.paths.projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "demo",
                                "ssh://git@example.org/demo.git",
                                "project-commit",
                                "",
                                "UNCHANGED",
                                "",
                                "",
                                "",
                                false,
                                commit,
                                "UNCHANGED"));
        return clone;
    }

    /**
     * A partial clone of a followed project fetches a missing card by the program its configuration
     * names: reading who its admins are must start nothing of it.
     */
    @Test
    void a_promisor_project_clone_starts_no_program_of_its_configuration() throws Exception {
        registry.agent("alice", "alice-new", "hank");
        filed();
        host();
        final Path clone = followedDemo();
        final Path marker = dir.resolve("marker");
        final Map<String, String> plain =
                Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");
        for (List<String> git :
                List.of(
                        List.of("config", "core.repositoryformatversion", "1"),
                        List.of("config", "extensions.partialClone", "lazy"),
                        List.of("config", "remote.lazy.url", "ssh://git@example.invalid/a.git"),
                        List.of("config", "remote.lazy.promisor", "true"),
                        List.of("config", "core.sshCommand", "touch " + marker + "; exit 1; :"))) {
            final List<String> all = new ArrayList<>(List.of("git", "-C", clone.toString()));
            all.addAll(git);
            registry.runner.runOrFail(Command.of(all).withEnvironment(plain));
        }
        final String blob =
                registry.runner
                        .runOrFail(
                                Command.of(
                                                "git",
                                                "-C",
                                                clone.toString(),
                                                "rev-parse",
                                                "HEAD:cards/michael.card")
                                        .withEnvironment(plain))
                        .trimmedOutput();
        Files.delete(
                clone.resolve(".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2)));

        identity("rotate", "--key", registry.pin("alice-new"), "--json");
        assertThat(marker).doesNotExist();
    }

    /** Runs git in the checkout with an environment of its own, as the person would. */
    private String checkoutGit(Map<String, String> environment, String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", registry.repo.toString()));
        all.addAll(List.of(args));
        final Map<String, String> plain = new java.util.HashMap<>(environment);
        plain.put("GIT_CONFIG_GLOBAL", "/dev/null");
        plain.put("GIT_CONFIG_NOSYSTEM", "1");
        return registry.runner.runOrFail(Command.of(all).withEnvironment(plain)).trimmedOutput();
    }

    /** A rotation whose push failed, leaving its signed filing at the checkout's HEAD. */
    private void filedNotPushed() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        pushTo = dir.resolve("nowhere.git");
        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--id", "r1"))
                .as(err.toString())
                .isZero();
        pushTo = bare;
    }

    /**
     * An unsigned commit of the card would be pushed as the person's and refused by every reader.
     */
    @Test
    void an_unsigned_filing_at_head_is_not_pushed() throws Exception {
        filedNotPushed();
        final String unsigned =
                checkoutGit(
                        Map.of(
                                "GIT_AUTHOR_NAME", "a",
                                "GIT_AUTHOR_EMAIL", "a@example.org",
                                "GIT_COMMITTER_NAME", "a",
                                "GIT_COMMITTER_EMAIL", "a@example.org"),
                        "commit-tree",
                        "HEAD^{tree}",
                        "-p",
                        "HEAD~1",
                        "-m",
                        "File people/alice.card");
        checkoutGit(Map.of(), "update-ref", "HEAD", unsigned);

        assertThat(identity("publish", "--id", "r1", "--json")).as(err.toString()).isZero();
        assertThat((String) registryPlace().get("why")).startsWith("checkout-moved: ");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /** The card as a link is no card a reader takes, signed or not. */
    @Test
    void a_filing_of_the_card_as_a_link_is_not_pushed() throws Exception {
        filedNotPushed();
        final String blob = checkoutGit(Map.of(), "rev-parse", "HEAD:people/alice.card");
        final Map<String, String> index =
                Map.of("GIT_INDEX_FILE", dir.resolve("link-index").toString());
        checkoutGit(index, "read-tree", "HEAD");
        checkoutGit(index, "update-index", "--cacheinfo", "120000," + blob + ",people/alice.card");
        final String tree = checkoutGit(index, "write-tree");
        final String linked =
                checkoutGit(
                        Map.of(
                                "GIT_AUTHOR_NAME", "a",
                                "GIT_AUTHOR_EMAIL", "a@example.org",
                                "GIT_COMMITTER_NAME", "a",
                                "GIT_COMMITTER_EMAIL", "a@example.org"),
                        "-c",
                        "gpg.format=ssh",
                        "-c",
                        "user.signingkey=" + registry.keyFiles.get("alice-new"),
                        "commit-tree",
                        "-S",
                        tree,
                        "-p",
                        "HEAD~1",
                        "-m",
                        "File people/alice.card");
        checkoutGit(Map.of(), "update-ref", "HEAD", linked);

        assertThat(identity("publish", "--id", "r1", "--json")).as(err.toString()).isZero();
        assertThat((String) registryPlace().get("why")).startsWith("checkout-moved: ");
        assertThat(holdsBranch(registry, bare)).isFalse();
    }

    /**
     * A linked ops directory holds records nobody here wrote: one would bring an old card back as
     * the person's before anything was written.
     */
    @Test
    void a_record_read_through_a_linked_ops_directory_is_refused() throws Exception {
        filedNotPushed();
        final Path file = ops().resolve("r1.json");
        final Map<String, Object> stopped =
                new java.util.LinkedHashMap<>(
                        (Map<String, Object>) Json.parse(Files.readString(file)));
        stopped.put("kept", false);
        Files.writeString(file, Json.write(stopped));
        final Path elsewhere = dir.resolve("elsewhere-ops");
        Files.move(ops(), elsewhere);
        Files.createSymbolicLink(ops(), elsewhere);
        final String before =
                Files.readString(registry.paths.xdg().config().resolve("identity/card"));

        assertThat(identity("publish", "--id", "r1", "--json")).isEqualTo(66);
        assertThat(answer().get("reason")).isEqualTo("operation-damaged");
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(before);
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

    /** A trust record through a link may be another's; one left out may name the key revoked. */
    @Test
    void a_linked_trust_record_is_unreadable() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path record =
                RegistryReadCommand.trustRecord(registry.context(), RegistryCheckout.ORIGIN);
        final Path moved = dir.resolve("record.json");
        Files.move(record, moved);
        Files.createSymbolicLink(record, moved);

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json")).isEqualTo(66);
        assertThat(ops()).doesNotExist();
    }

    /** Without its record of where it was read, a trust record is still one naming keys revoked. */
    @Test
    void a_trust_record_without_its_where_is_unreadable() throws Exception {
        registry.agent("alice", "alice-new");
        filed();
        final Path record =
                RegistryReadCommand.trustRecord(registry.context(), RegistryCheckout.ORIGIN);
        final String name = record.getFileName().toString();
        Files.delete(record.resolveSibling(name.replace(".json", ".where.json")));

        assertThat(identity("rotate", "--key", registry.pin("alice-new"), "--json")).isEqualTo(66);
        assertThat(ops()).doesNotExist();
    }

    /** Registries read through a linked state directory may not be the ones read here. */
    @Test
    void a_linked_state_directory_is_refused() throws Exception {
        registry.agent("alice", "alice-new");
        final String card = filed();
        final Path state = registry.paths.xdg().state();
        final Path elsewhere = dir.resolve("elsewhere-state");
        Files.move(state, elsewhere);
        Files.createSymbolicLink(state, elsewhere);

        assertThat(identity("rotate", "--key", registry.pin("alice-new"))).isEqualTo(66);
        assertThat(Files.readString(registry.paths.xdg().config().resolve("identity/card")))
                .isEqualTo(card);
    }
}
