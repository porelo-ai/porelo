package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for the machines a machine admits: only the machine let in is named, an offline project
 * admits nobody, and a secret never lands where a person or a log can read it.
 */
class MachineTransportsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    /** A secret long enough to be withheld, and recognizable wherever it leaks. */
    private static final String TOKEN = "machine-token-SECRET-1234";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private @Nullable PoreloContext used;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        used = context;
        return context;
    }

    @AfterEach
    void forgetWhatTheKeyringHolds() {
        PoreloContext.forgetAsked();
        if (used != null && KernelKeyring.available()) {
            new KernelKeyring(used.paths().vault().vaultKeyringKey()).forget();
            VaultShare.forget(used.paths());
        }
    }

    /**
     * A vault holding the transport's account, opened by a passphrase asked for rather than the
     * kernel keyring, so these tests run where the keyring is refused too, a task among them.
     */
    private PoreloContext unlockedVault() throws IOException {
        return unlockedVault(Map.of());
    }

    /** A vault as {@link #unlockedVault()}, holding the project's secrets {@code project} too. */
    private PoreloContext unlockedVault(final Map<String, String> project) throws IOException {
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        used = context;
        Files.createDirectories(context.vault().path().getParent());
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        entries.put(
                VaultNames.TRANSPORT_PREFIX + "room/account", VaultEntry.of("{\"ADMIN\":\"a-1\"}"));
        if (!project.isEmpty()) {
            entries.put(
                    VaultNames.TRANSPORT_PREFIX + "room/project/p",
                    VaultEntry.of(org.fuin.porelo.wire.Json.write(project)));
        }
        context.vault().write(entries, PASSPHRASE);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter())))
                .as("the vault opens for the test")
                .isPresent();
        return context;
    }

    /** A project registered here whose conversation is on the transport {@code room}. */
    private Project project(final PoreloContext context, final String securityClass)
            throws IOException {
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        final Path file = dir.resolve("p-project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"p\"\n  security_class: \""
                        + securityClass
                        + "\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + "mail:\n  transports:\n    room:\n      homeserver: \"https://project.example\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve("p"),
                file + "\n",
                StandardCharsets.UTF_8);
        return GateSupport.byName(context, "p");
    }

    /** The transport {@code room}, which lets other machines in. */
    private void transport() throws IOException {
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"settings\",\"admit\",\"dismiss\","
                        + "\"clear\"],\"takes\":[\"machine\"]}");
        runner.answering(" settings", "{\"refused\":[],\"warnings\":[]}");
    }

    private static final String WIREGUARD = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /** The applied access file's developer list: two accounts on this host. */
    private static final String DEVELOPERS =
            """
            host:
              address: host.example.org
              endpoint: host.example.org:51820
              wireguard_public_key: %s
              network: 10.77.0.0/24
              ssh_host_key: ssh-ed25519 AAAA host@example.org
              ca_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            transports:
              room: {homeserver: "https://access.example"}
            developers:
              bob:
                laptops:
                  - ssh_public_key: ssh-ed25519 AAAA bob@laptop
                    wireguard_public_key: %s
                    vpn_address: 10.77.0.11
              alice:
                laptops:
                  - ssh_public_key: ssh-ed25519 AAAA alice@laptop
                    wireguard_public_key: %s
                    vpn_address: 10.77.0.10
            """
                    .formatted(WIREGUARD, WIREGUARD, WIREGUARD);

    /** A followed project whose access file, with its developers, is applied at {@code a1}. */
    private Project followed(final PoreloContext context) throws IOException {
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("p"));
        Files.writeString(
                clone.resolve("project.yml"),
                "project:\n  name: \"p\"\n  security_class: \"guarded\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + "mail:\n  peers:\n    people: {address: \"room:\", trust: \"external\"}\n"
                        + "access: {repository: \"git@example.org:p/access.git\"}\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(context.paths().projects().followedAccessClone("p"));
        runner.answering("show a1:access.yml", DEVELOPERS);
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "p",
                                "git@example.org:p/project.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                "a1",
                                "APPLIED"));
        return GateSupport.byName(context, "p");
    }

    /** The transport {@code room}, making rooms and keeping their members. */
    private void roomTransport() throws IOException {
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"settings\",\"members\"],"
                        + "\"takes\":[\"machine\",\"create-room\"]}");
        runner.answering(" settings", "{\"refused\":[],\"warnings\":[]}");
        runner.answering(
                " setup",
                "{\"conversation\":\"!r\",\"room\":\"#p:access.example\",\"machine\":\"made-here\","
                        + "\"admitted\":true}");
        runner.answering(" members", "{\"invited\":[\"x\"],\"removed\":[]}");
    }

    @Test
    void theAdminsMachineMakesTheRoomAndKeepsItsMachinesToTheAppliedList() throws Exception {
        final PoreloContext context = unlockedVault();
        roomTransport();
        followed(context);

        final int code = execute(context, "talk", "room", "p");

        assertThat(code).as("the room is made: " + err).isZero();
        final List<String> setup = only("setup").arguments();
        assertThat(setup).as("made as the room's maker").contains("--create-room");
        final List<String> members = only("members").arguments();
        assertThat(members.subList(members.indexOf("members"), members.indexOf("members") + 5))
                .as("this machine and each developer's account on this host, in order")
                .containsExactly(
                        "members",
                        "--project",
                        "p",
                        "--machines",
                        String.join(
                                ",",
                                new java.util.TreeSet<>(
                                        List.of(
                                                context.machineName().get(),
                                                MachineName.of(MachineName.hostName(), "alice"),
                                                MachineName.of(MachineName.hostName(), "bob")))));
        assertThat(out.toString())
                .as("says what it made and whom it invited and removed")
                .contains("#p:access.example")
                .contains("invited    x")
                .contains("removed    -");
    }

    @Test
    void anAppliedCommitKeepsTheRoomOnlyWhereThisAccountMadeIt() throws Exception {
        final PoreloContext context = unlockedVault();
        roomTransport();
        final Project project = followed(context);
        final FollowedProjects.Followed before =
                new FollowedProjects.Followed(
                        "p",
                        "git@example.org:p/project.git",
                        "c0",
                        "",
                        "APPLIED",
                        "",
                        "",
                        "",
                        false,
                        "a0",
                        "APPLIED");
        final Reconcile.Result applied =
                new Reconcile.Result(Reconcile.Outcome.APPLIED, "c1", "", "", "", "a1", "APPLIED");

        Reconcile.recordApplied(context, before, applied);

        assertThat(runner.lines())
                .as("an account that did not make the room asks nothing of it")
                .noneMatch(line -> line.contains(" members"));

        MachineTransports.createRoom(context, project);
        Reconcile.recordApplied(context, before, applied);

        assertThat(runner.lines().stream().filter(line -> line.contains(" members")))
                .as("the maker keeps the room to the list after the commit")
                .hasSize(1);
    }

    @Test
    void aRoomIsMadeOnlyWhereTheTransportTakesIt() throws Exception {
        final PoreloContext context = unlockedVault();
        transport();
        followed(context);

        assertThat(execute(context, "talk", "room", "p"))
                .as("refused before anything is run")
                .isEqualTo(69);
        assertThat(err.toString()).as("says why").contains("does not take --create-room");
        assertThat(runner.lines()).as("no setup ran").noneMatch(line -> line.contains(" setup"));
    }

    /** The transport {@code room}, keeping persons in its rooms beside machines. */
    private void personsTransport(final String members) throws IOException {
        roomTransport();
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"settings\",\"members\"],"
                        + "\"takes\":[\"machine\",\"create-room\",\"persons\"]}");
        runner.answering(" members", members);
    }

    /** What follows an option in the last {@code members} run, or {@code null} without it. */
    private @Nullable String membersOption(final String option) {
        final List<String> members =
                runner.invocations().stream()
                        .filter(each -> each.arguments().contains("members"))
                        .reduce((before, after) -> after)
                        .orElseThrow()
                        .arguments();
        final int at = members.indexOf(option);
        return at < 0 ? null : members.get(at + 1);
    }

    @Test
    void aTransportTakingPersonsKeepsEveryDeveloperInTheRoomAsAPerson() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport(
                "{\"invited\":[],\"removed\":[],"
                        + "\"persons_invited\":[\"alice\",\"bob\"],\"persons_removed\":[]}");
        followed(context);

        final int code = execute(context, "talk", "room", "p");

        assertThat(code).as("the room is made: " + err).isZero();
        final List<String> members = only("members").arguments();
        final int machines = members.indexOf("--machines");
        assertThat(members.subList(machines + 2, machines + 4))
                .as("every developer's account, sorted, right after the machines")
                .containsExactly("--persons", "alice,bob");
        assertThat(members).as("given once").containsOnlyOnce("--persons");
        assertThat(out.toString())
                .as("the persons on lines of their own")
                .contains("persons invited  alice, bob\n")
                .contains("persons removed  -\n");
    }

    @Test
    void aDeveloperDroppedFromTheAccessFileIsPassedNoMoreAndShownRemoved() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport(
                "{\"invited\":[],\"removed\":[],"
                        + "\"persons_invited\":[],\"persons_removed\":[\"bob\"]}");
        followed(context);
        runner.answering(
                "show a1:access.yml",
                DEVELOPERS.substring(0, DEVELOPERS.indexOf("  bob:"))
                        + DEVELOPERS.substring(DEVELOPERS.indexOf("  alice:")));

        assertThat(execute(context, "talk", "room", "p")).as(err.toString()).isZero();

        assertThat(membersOption("--persons")).as("bob is passed no more").isEqualTo("alice");
        assertThat(out.toString())
                .as("the transport's answer names him removed")
                .contains("persons removed  bob\n");
    }

    @Test
    void anAppliedAccessCommitKeepsThePersonsTooWhereTheTransportTakesThem() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Project project = followed(context);
        MachineTransports.createRoom(context, project);

        Reconcile.recordApplied(
                context,
                new FollowedProjects.Followed(
                        "p",
                        "git@example.org:p/project.git",
                        "c0",
                        "",
                        "APPLIED",
                        "",
                        "",
                        "",
                        false,
                        "a0",
                        "APPLIED"),
                new Reconcile.Result(Reconcile.Outcome.APPLIED, "c1", "", "", "", "a1", "APPLIED"));

        assertThat(membersOption("--persons"))
                .as("every developer of the applied access file")
                .isEqualTo("alice,bob");
    }

    @Test
    void aTransportNotTakingPersonsIsGivenTheMachinesAlone() throws Exception {
        final PoreloContext context = unlockedVault();
        roomTransport();
        followed(context);

        assertThat(execute(context, "talk", "room", "p")).as(err.toString()).isZero();

        assertThat(membersOption("--persons")).as("never given").isNull();
        assertThat(membersOption("--machines")).as("the machines kept as before").isNotEmpty();
        assertThat(out.toString()).as("no person said").doesNotContain("persons");
    }

    @Test
    void aTransportAskingForPeoplesWordsAloneIsGivenNoPersonsOnMembers() throws Exception {
        final PoreloContext context = unlockedVault();
        roomTransport();
        // "persons": true asks for people's words on poll; its list does not name persons.
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,"
                        + "\"lifecycle\":[\"setup\",\"settings\",\"members\"],"
                        + "\"takes\":[\"machine\",\"create-room\"]}");
        followed(context);

        assertThat(execute(context, "talk", "room", "p")).as(err.toString()).isZero();

        assertThat(only("members").arguments()).as("never given").doesNotContain("--persons");
    }

    @Test
    void anAppliedAccessCommitAloneKeepsThePersons() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport(
                "{\"invited\":[\"m\\u001b[2J\"],\"removed\":[],\"persons_removed\":[\"bob\\u2028\"]}");
        final Project project = followed(context);
        MachineTransports.createRoom(context, project);
        runner.answering(
                "show a2:access.yml",
                DEVELOPERS.substring(0, DEVELOPERS.indexOf("  bob:"))
                        + DEVELOPERS.substring(DEVELOPERS.indexOf("  alice:")));
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "p",
                                "git@example.org:p/project.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                "a2",
                                "APPLIED"));

        // The project file is as it was; only the access file moved on.
        Reconcile.recordApplied(
                context,
                new FollowedProjects.Followed(
                        "p",
                        "git@example.org:p/project.git",
                        "c1",
                        "",
                        "APPLIED",
                        "",
                        "",
                        "",
                        false,
                        "a1",
                        "APPLIED"),
                new Reconcile.Result(
                        Reconcile.Outcome.UNCHANGED, "c1", "", "", "", "a2", "APPLIED"));

        assertThat(membersOption("--persons")).as("bob dropped").isEqualTo("alice");
        assertThat(EventLog.of(context).entries(null))
                .as("the log names the persons kept, a missing list as none")
                .anySatisfy(
                        entry ->
                                assertThat(String.valueOf(entry.get("detail")))
                                        .contains(
                                                "invited m?[2J, removed -; persons invited -,"
                                                        + " removed bob?"));
    }

    @Test
    void theLastDeveloperDroppedIsPassedAnEmptyList() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[],\"persons_removed\":[\"bob\"]}");
        followed(context);
        runner.answering(
                "show a1:access.yml",
                DEVELOPERS.substring(0, DEVELOPERS.indexOf("developers:")) + "developers: {}\n");

        assertThat(execute(context, "talk", "room", "p")).as(err.toString()).isZero();

        assertThat(membersOption("--persons"))
                .as("given empty, so the transport removes every person")
                .isEmpty();
    }

    @Test
    void aPersonsLineCannotBeForgedByTheTransportsAnswer() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport(
                "{\"invited\":[],\"removed\":[],"
                        + "\"persons_invited\":[\"alice\\npersons removed  bob\\u2028\"],"
                        + "\"persons_removed\":[]}");
        followed(context);

        assertThat(execute(context, "talk", "room", "p")).as(err.toString()).isZero();

        assertThat(out.toString())
                .contains("persons invited  alice?persons removed  bob?\n")
                .contains("persons removed  -\n");
    }

    @Test
    void aDevelopersWordsAreTakenWithoutAJoinAndTheyCanBeAnswered() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");

        final var outcome = conversations(context).pass(group(review));

        assertThat(outcome.failures()).noneMatch(each -> each.startsWith("dropped"));
        assertThat(fromIn(review)).as("handed to the task as bob's words").containsExactly("bob");
        assertThat(kept(context, "taken"))
                .as("kept apart from who joined, so a task can answer him")
                .isEqualTo(Map.of("bob", "@bob:hs"));
        assertThat(kept(context, "members")).as("nobody joined").isEmpty();
        assertThat(Conversations.members(context, GateSupport.byName(context, "p")))
                .as("a task answers him by name")
                .isEqualTo(Map.of("bob", "@bob:hs"));
    }

    @Test
    void aDeveloperTheAccessFileDropsIsForgotten() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");
        conversations(context).pass(group(review));
        applyWithoutBob(context);

        assertThat(Conversations.members(context, GateSupport.byName(context, "p")))
                .as("no task answers him any more, before any pass")
                .isEmpty();
        said(context, "t2--room-person-two.json", "@bob:hs", null);
        final var outcome = conversations(context).pass(group(review));

        assertThat(fromIn(review)).as("only his words from before").containsExactly("bob");
        assertThat(outcome.failures())
                .anySatisfy(each -> assertThat(each).contains("t2--room-person-two.json"));
    }

    @Test
    void aSecondSenderClaimingTheSameDeveloperInOneBatchIsRefused() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");
        said(context, "t2--room-person-two.json", "@mallory:hs", "bob");

        final var outcome = conversations(context).pass(group(review));

        assertThat(fromIn(review)).containsExactly("bob");
        assertThat(outcome.failures()).anySatisfy(each -> assertThat(each).contains("@mallory:hs"));
        assertThat(kept(context, "taken")).isEqualTo(Map.of("bob", "@bob:hs"));
    }

    @Test
    void thisMachinesOwnAccountIsNeverTakenForADeveloper() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context, "@made-here:hs");
        said(context, "t1--room-person-one.json", "@made-here:hs", "bob");

        final var outcome = conversations(context).pass(group(review));

        assertThat(fromIn(review)).isEmpty();
        assertThat(outcome.failures())
                .anySatisfy(each -> assertThat(each).contains("@made-here:hs"));
        assertThat(kept(context, "taken")).as("bob's own account is still free").isEmpty();
    }

    @Test
    void aTransportWhoseListNamesNoPersonsHasNoDeveloperTakenByAccount() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,"
                        + "\"lifecycle\":[\"setup\",\"settings\",\"members\"],"
                        + "\"takes\":[\"machine\",\"create-room\"]}");
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");

        conversations(context).pass(group(review));

        assertThat(fromIn(review)).isEmpty();
        assertThat(kept(context, "taken")).isEmpty();
    }

    @Test
    void aJoinIsNeverUndoneWhenTheAccessFileDropsADeveloperTakenBefore() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");
        conversations(context).pass(group(review));
        // As talk join keeps a person.
        Files.writeString(
                Files.createDirectories(
                                context.paths().xdg().state().resolve("transport/room/members"))
                        .resolve("p.json"),
                "{\"bob\":\"@bob:hs\"}");
        applyWithoutBob(context);
        said(context, "t2--room-person-two.json", "@bob:hs", null);

        conversations(context).pass(group(review));

        assertThat(fromIn(review)).as("heard as one who joined").containsExactly("bob", "bob");
        assertThat(kept(context, "members")).isEqualTo(Map.of("bob", "@bob:hs"));
    }

    @Test
    void aDamagedRecordOfWhoWasTakenLetsNobodyIn() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        final Path taken =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/taken"));
        Files.writeString(taken.resolve("p.json"), "{\"bob\":");
        said(context, "t1--room-person-one.json", "@bob:hs", null);

        conversations(context).pass(group(review));

        assertThat(fromIn(review)).isEmpty();
        assertThat(Conversations.members(context, GateSupport.byName(context, "p"))).isEmpty();
    }

    @Test
    void clearingTheConversationRemovesWhoWasTaken() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");
        conversations(context).pass(group(review));
        runner.answering(" clear", "{\"removed\":[]}");

        new TransportLifecycle(context, context.paths().messaging().transportDirectory())
                .clear("room", "p", Map.of());

        assertThat(context.paths().xdg().state().resolve("transport/room/taken/p.json"))
                .doesNotExist();
    }

    @Test
    void aDevelopersWordsInADirectChatAreHeardUntilTheAccessFileDropsThem() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        final Mailbox review = conversation(context);
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"poll\":true,"
                        + "\"lifecycle\":[\"setup\",\"settings\",\"members\",\"enroll\"],"
                        + "\"takes\":[\"machine\",\"create-room\",\"persons\",\"direct\"]}");
        runner.answering(
                " enroll", "{\"secrets\":{\"T\":\"t-1\"},\"address\":\"@porelo-p-review:hs\"}");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.enroll("room", "p", "porelo-p-review", Map.of());
        saidDirectly(context, "t1--room-person-one.json", "@bob:hs", "bob");

        conversations(context).pass(group(review));
        applyWithoutBob(context);
        saidDirectly(context, "t2--room-person-two.json", "@bob:hs", null);
        final var outcome = conversations(context).waitDirect(group(review).getFirst());

        assertThat(fromIn(review)).as("only the words from before").containsExactly("bob");
        assertThat(outcome.failures())
                .anySatisfy(each -> assertThat(each).contains("t2--room-person-two.json"));
    }

    @Test
    void aMachineThatDidNotMakeTheRoomTakesADeveloperToo() throws Exception {
        final PoreloContext context = unlockedVault();
        personsTransport("{\"invited\":[],\"removed\":[]}");
        runner.answering(
                " setup",
                "{\"conversation\":\"!r\",\"room\":\"#p:access.example\","
                        + "\"machine\":\"follower\",\"admitted\":true,"
                        + "\"secrets\":{\"POLLER\":\"p-1\"}}");
        followed(context);
        new TransportLifecycle(context, context.paths().messaging().transportDirectory())
                .setup("room", "p", Map.of());
        assertThat(context.paths().xdg().state().resolve("transport/room/p.room"))
                .as("this machine made no room")
                .doesNotExist();
        final Mailbox review = new Mailbox(dir.resolve("review"));
        said(context, "t1--room-person-one.json", "@bob:hs", "bob");

        conversations(context).pass(group(review));

        assertThat(fromIn(review)).containsExactly("bob");
    }

    /** A person's words in a direct chat with the review task, as the transport left them. */
    private static void saidDirectly(
            final PoreloContext context,
            final String name,
            final String sender,
            final @Nullable String account)
            throws IOException {
        final Path into =
                Files.createDirectories(
                        context.paths()
                                .xdg()
                                .state()
                                .resolve("transport/room/direct/porelo-p-review"));
        Files.writeString(
                into.resolve(name),
                "{\"sender\":\""
                        + sender
                        + "\""
                        + (account == null ? "" : ",\"account\":\"" + account + "\"")
                        + ",\"direct\":true,\"body\":\"hi\",\"to\":[\"@porelo-p-review:hs\"]}");
    }

    /** The project's room made here, and the one task that reads it. */
    private Mailbox conversation(final PoreloContext context) throws Exception {
        return conversation(context, "");
    }

    /** The project's room made here under this machine's account, and the task that reads it. */
    private Mailbox conversation(final PoreloContext context, final String address)
            throws Exception {
        runner.answering(
                " setup",
                "{\"conversation\":\"!r\",\"room\":\"#p:access.example\","
                        + "\"machine\":\"made-here\",\"address\":\""
                        + address
                        + "\",\"admitted\":true,\"secrets\":{\"POLLER\":\"p-1\"}}");
        MachineTransports.createRoom(context, followed(context));
        return new Mailbox(dir.resolve("review"));
    }

    private static List<TransportConversations.Member> group(final Mailbox review) {
        return List.of(new TransportConversations.Member("porelo-p-review", review, "p", "room"));
    }

    private static TransportConversations conversations(final PoreloContext context) {
        return new TransportConversations(
                context, context.paths().messaging().transportDirectory());
    }

    /** A person's words as the transport left them, with the host account it names, if any. */
    private static void said(
            final PoreloContext context,
            final String name,
            final String sender,
            final @Nullable String account)
            throws IOException {
        final Path inbound =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/inbound/p"));
        Files.writeString(
                inbound.resolve(name),
                "{\"sender\":\""
                        + sender
                        + "\""
                        + (account == null ? "" : ",\"account\":\"" + account + "\"")
                        + ",\"body\":\"hi\",\"to\":[]}");
    }

    /** Whom each message the task was handed is from. */
    private static List<Object> fromIn(final Mailbox review) throws IOException {
        if (!Files.isDirectory(review.inbound())) {
            return List.of();
        }
        try (var files = Files.list(review.inbound())) {
            return files.filter(each -> each.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(
                            each -> {
                                try {
                                    final Map<?, ?> message =
                                            (Map<?, ?>)
                                                    org.fuin.porelo.wire.Json.parse(
                                                            Files.readString(each));
                                    return ((Map<?, ?>) message.get("metadata")).get("from");
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            })
                    .map(Object.class::cast)
                    .toList();
        }
    }

    /** Who is kept under one of the transport's state folders, as a map. */
    private static Map<?, ?> kept(final PoreloContext context, final String folder)
            throws IOException {
        final Path file =
                context.paths().xdg().state().resolve("transport/room/" + folder + "/p.json");
        return Files.isRegularFile(file)
                ? (Map<?, ?>) org.fuin.porelo.wire.Json.parse(Files.readString(file))
                : Map.of();
    }

    /** The access file applied at a2, which no longer names bob. */
    private void applyWithoutBob(final PoreloContext context) throws IOException {
        runner.answering(
                "show a2:access.yml",
                DEVELOPERS.substring(0, DEVELOPERS.indexOf("  bob:"))
                        + DEVELOPERS.substring(DEVELOPERS.indexOf("  alice:")));
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "p",
                                "git@example.org:p/project.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                "a2",
                                "APPLIED"));
    }

    private Command only(final String verb) {
        final List<Command> found =
                runner.invocations().stream()
                        .filter(each -> each.arguments().contains(verb))
                        .toList();
        assertThat(found).as("exactly one '%s' was run", verb).hasSize(1);
        return found.getFirst();
    }

    private int execute(final PoreloContext context, final String... arguments) {
        final CommandLine command = PoreloCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute(arguments);
    }

    @Test
    void aMachinesNameThatReadsAsAnOptionIsRefused() throws Exception {
        final PoreloContext context = unlockedVault();
        transport();
        final Project project = project(context, "guarded");

        assertThatThrownBy(() -> MachineTransports.admit(context, project, "--help"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("not a machine's name");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "-x"))
                .isInstanceOf(Conversations.Refused.class);
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" admit") || line.contains(" dismiss"));
    }

    @Test
    void anOfflineProjectAdmitsNobody() throws Exception {

        // An offline project's messages never leave this machine.
        final PoreloContext context = context();
        transport();
        final Project project = project(context, "offline");

        assertThatThrownBy(() -> MachineTransports.admit(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("offline");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("offline");
        assertThat(runner.lines()).noneMatch(line -> line.contains(" admit"));
    }

    @Test
    void anOfflineSetupReachingAwayIsRefusedOnEveryPathNotOnlyAtEnroll() throws Exception {

        // Set up through the daemon or a join, it was checked nowhere: an offline project's
        // conversation could reach another server and the room was remembered.
        final PoreloContext context = unlockedVault();
        transport();
        runner.answering(
                " setup",
                "{\"conversation\":\"!room\",\"reaches\":[\"machine.example\"],"
                        + "\"secrets\":{\"POLLER\":\"poll-12345678\"}}");
        final Project project = project(context, "offline");

        assertThatThrownBy(() -> new TaskConversations(context).setUp(project))
                .isInstanceOf(TransportLifecycle.Refused.class)
                .hasMessageContaining("machine.example");
        assertThat(dir.resolve("state/porelo/transport/room/p.json"))
                .as("no room remembered from a refused setup")
                .doesNotExist();
        assertThat(
                        new TransportLifecycle(
                                        context, context.paths().messaging().transportDirectory())
                                .secrets("room", "project/p"))
                .as("no secret kept that would start polling the server elsewhere")
                .isEmpty();
    }

    @Test
    void admitNamesOnlyTheMachineLetInAndWritesItsSecretToANewFileOfTheOwners() throws Exception {

        // This machine's own name appended after it would let this machine in instead; a token
        // printed to the terminal would sit in its scrollback.
        final PoreloContext context = unlockedVault();
        transport();
        runner.answering(
                " admit",
                "{\"admission\":{\"machine\":\"host2\",\"room\":\"!r\"},"
                        + "\"secrets\":{\"registration_token\":\""
                        + TOKEN
                        + "\"}}");
        project(context, "guarded");
        final Path into = dir.resolve("admission.json");

        final int code = execute(context, "talk", "admit", "p", "host2", "--into", into.toString());

        assertThat(code).as(err.toString()).isZero();
        final List<String> arguments = only("admit").arguments();
        assertThat(arguments.subList(arguments.indexOf("admit"), arguments.size()))
                .as("the admitted machine, and no other --machine")
                .containsExactly("admit", "--project", "p", "--machine", "host2");
        assertThat(out.toString() + err).as("no secret on the terminal").doesNotContain(TOKEN);
        assertThat(Files.readString(into)).as("the secret in the file").contains(TOKEN);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(into)))
                .isEqualTo("rw-------");
        assertThat(execute(context, "talk", "admit", "p", "host2", "--into", into.toString()))
                .as("an existing file is never replaced")
                .isEqualTo(73);
        only("admit");
        assertThat(
                        execute(
                                context,
                                "talk",
                                "admit",
                                "p",
                                "host3",
                                "--into",
                                dir.resolve("none/a.json").toString()))
                .as("nowhere to write it")
                .isEqualTo(73);
        assertThat(runner.lines())
                .as("no token is minted that nobody could keep")
                .noneMatch(line -> line.contains("host3"));
        try (var left = Files.list(dir)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .as("nothing staged is left behind")
                    .noneMatch(name -> name.startsWith(".admission"));
        }
    }

    @Test
    void aRefusalsMessageWithholdsTheSecretsTheTransportWasGiven() throws Exception {

        // The message is what every caller prints; a transport echoing its environment put the
        // secret on the terminal.
        final PoreloContext context = unlockedVault(Map.of("ROOM_TOKEN", TOKEN));
        transport();
        runner.failing(" setup", 78, "cannot register with token " + TOKEN);
        project(context, "guarded");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());

        assertThatThrownBy(
                        () ->
                                lifecycle.setup(
                                        "room",
                                        "p",
                                        Map.of("homeserver", "https://project.example"),
                                        false))
                .isInstanceOf(TransportLifecycle.Refused.class)
                .satisfies(
                        ex ->
                                assertThat(ex.getMessage())
                                        .as("the secret withheld from the message")
                                        .doesNotContain(TOKEN)
                                        .contains("[withheld]"));
    }

    @Test
    void aSecretSplitByAnEscapeSequenceOrPrintedBeforeFailingIsWithheldToo() throws Exception {

        // Redacted before it was cleaned, a secret split by an escape sequence was whole again in
        // the cause; a password a failing verb printed was not among the secrets it was given.
        final PoreloContext context = unlockedVault(Map.of("ROOM_TOKEN", TOKEN));
        transport();
        runner.failing(
                " setup",
                78,
                "{\"login\":{\"password\":\"pw-SECRET-98765\"}}",
                "cannot register with machine-token-\u001b[31mSECRET-1234 and pw-SECRET-98765");
        project(context, "guarded");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());

        assertThatThrownBy(
                        () ->
                                lifecycle.setup(
                                        "room",
                                        "p",
                                        Map.of("homeserver", "https://project.example"),
                                        false))
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        ex -> {
                            assertThat(ex.getMessage() + ex.cause())
                                    .doesNotContain("SECRET")
                                    .doesNotContain("\u001b");
                        });
    }

    @Test
    void aTransportThatCannotRunIsARefusalAndAdmitSaysItNeedsTheVault() throws Exception {
        final PoreloContext shut = context();
        transport();
        final Project project = project(shut, "guarded");

        assertThatThrownBy(() -> MachineTransports.admit(shut, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("needs the secrets it keeps");

        final PoreloContext context = unlockedVault();
        runner.timingOut(" dismiss ");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("could not dismiss");
    }

    @Test
    void aNameTheFileSystemRefusesIsFoundBeforeATokenIsMinted() throws Exception {
        final PoreloContext context = unlockedVault();
        transport();
        project(context, "guarded");

        assertThat(
                        execute(
                                context,
                                "talk",
                                "admit",
                                "p",
                                "host2",
                                "--into",
                                dir.resolve("x".repeat(300) + ".json").toString()))
                .isEqualTo(73);
        assertThat(runner.lines()).noneMatch(line -> line.contains(" admit"));
        try (var left = Files.list(dir)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".admission"));
        }
    }
}
