package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for a transport that keeps a conversation of its own - a room - as Porelo sees it: without
 * knowing which transport it is.
 */
@org.junit.jupiter.api.extension.ExtendWith(TransportLogin.class)
class TransportConversationsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    /** One empty vault for the class, sealed once with the production cost; each test copies it. */
    private static Path sealedVault;

    @BeforeAll
    static void sealVault(@TempDir final Path shared) {
        sealedVault = shared.resolve("vault");
        new org.fuin.porelo.vault.VaultFile(sealedVault).write(Map.of(), PASSPHRASE);
    }

    /** Gives a test its own copy of the empty vault. */
    private static void emptyVault(final PoreloContext context) throws IOException {
        Files.copy(
                sealedVault,
                context.vault().path(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    @Test
    void aRemovedPersonIsPartedByTheTransportAndNoLongerListed() throws Exception {
        // Revocation must reach the transport with its credentials before local access is removed.
        final PoreloContext context = removalContext("guarded", true);
        runner.answering(" part ", "{\"removed\":\"@alice\"}");
        final Path previous =
                Files.createLink(dir.resolve("previous-members.json"), memberFile(context));
        final byte[] before = Files.readAllBytes(previous);

        final TalkReply reply = talk(context, "remove", "p", "alice");

        assertThat(reply.code()).as("the removal succeeds").isZero();
        assertThat(reply.out())
                .as("the removed account is identified")
                .isEqualTo("removed   alice (@alice)\n");
        final var part = runner.only(" part ");
        assertThat(part.arguments().subList(1, part.arguments().size()))
                .as(
                        "part receives only the project and person, even when the transport takes login")
                .containsExactly("part", "--project", "p", "--person", "alice");
        assertThat(part.environment())
                .as("the account and project credentials reach part")
                .containsEntry("ADMIN", "a-1")
                .containsEntry("POLLER", "p-1");
        assertThat(org.fuin.porelo.wire.Json.parse(part.input()))
                .as("the project's settings reach stdin")
                .isEqualTo(Map.of("server", "https://example.org"));
        assertThat(part.describe())
                .as("credentials never become arguments")
                .doesNotContain("a-1", "p-1");
        assertThat(Files.readAllBytes(previous))
                .as("publishing a new list replaces the file rather than modifying its old inode")
                .isEqualTo(before);
        assertThat(Conversations.members(context, project("guarded")))
                .as("only the removed person disappears")
                .containsExactlyEntriesOf(Map.of("bob", "@bob"));
        assertThat(talk(context, "members", "p").out())
                .as("the command reads the changed membership")
                .contains("PERSON", "ACCOUNT", "bob", "@bob")
                .doesNotContain("alice");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {75, 77, 78})
    void aRefusedPartLeavesThePersonListedAndExitsAsJoinDoes(int code) throws Exception {
        // A failed remote revocation must never be reported or recorded as a successful one.
        final PoreloContext context = removalContext("guarded", true);
        runner.failing(" part ", code, "membership cannot be changed");
        runner.failing(" join ", code, "membership cannot be changed");
        final byte[] before = Files.readAllBytes(memberFile(context));

        final TalkReply reply = talk(context, "remove", "p", "alice");
        final TalkReply joined = talk(context, "join", "p", "alice", "--reset");

        assertThat(reply.code())
                .as("part uses join's transport refusal mapping")
                .isEqualTo(69)
                .isEqualTo(joined.code());
        assertThat(reply.err())
                .as("the transport's refusal reaches the person")
                .contains("membership cannot be changed", "exit " + code);
        assertThat(Files.readAllBytes(memberFile(context)))
                .as("a refused part leaves the list byte for byte unchanged")
                .isEqualTo(before);
        assertThat(Conversations.members(context, project("guarded")))
                .as("the refused person stays listed")
                .containsEntry("alice", "@alice");
    }

    @Test
    void aJoinIntoTheVaultKeepsTheTokenAndShowsNoSecret() throws Exception {
        // Run by an agent, the output is its transcript: no password and no token may reach it.
        final PoreloContext context = removalContext("guarded", false);
        runner.answering(
                " join ",
                "{\"login\":{\"user\":\"@carol\",\"password\":\"pw-SECRET\",\"room\":\"!room-p\"},"
                        + "\"shown\":\"user @carol password pw-SECRET\","
                        + "\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":\"tok-SECRET\"}}");

        final TalkReply reply = talk(context, "join", "p", "carol", "--into-vault", "talk.carol");

        assertThat(reply.code()).as(reply.err()).isZero();
        assertThat(reply.out())
                .as("the agent learns its room from this line alone, since the room acts by its id")
                .contains(" in !room-p", "'talk.carol'");
        assertThat(reply.out() + reply.err())
                .as("neither the password nor the token is shown")
                .doesNotContain("pw-SECRET", "tok-SECRET");
        final List<String> joins =
                runner.lines().stream().filter(line -> line.contains("--person carol")).toList();
        assertThat(joins)
                .singleElement()
                .as("the transport is asked for a token, not a password")
                .satisfies(line -> assertThat(line).contains(" join ", "--token"));
        final org.fuin.porelo.vault.VaultEntry kept =
                context.vault().read(PASSPHRASE).get("talk.carol");
        assertThat(kept).isNotNull();
        assertThat(kept.value()).isEqualTo("tok-SECRET");
        assertThat(kept.type()).isEqualTo("token");
    }

    @Test
    void aJoinHandsThePersonsDisplayNameToTheTransport() throws Exception {
        // The room names a member by its display name; without it no head names anyone.
        final PoreloContext context = removalContext("guarded", false);

        final TalkReply reply = talk(context, "join", "p", "carol", "--display", "Carol Smith");

        assertThat(reply.code()).as(reply.err()).isZero();
        assertThat(runner.only("--person carol").arguments())
                .as("the transport's join is given the display name as one argument")
                .containsSubsequence("--display", "Carol Smith");
    }

    @Test
    void aDisplayNameThatReadsAsAnOptionIsRefusedBeforeTheTransportRuns() throws Exception {
        // Handed on as its own argument, "--token" reaches the transport's join as a flag; picocli
        // refuses only the options of porelo's own join.
        final PoreloContext context = removalContext("guarded", false);

        final TalkReply reply = talk(context, "join", "p", "carol", "--display=--token");

        assertThat(reply.code()).as(reply.err()).isEqualTo(64);
        assertThat(reply.err()).contains("'--token'");
        assertThat(runner.lines())
                .as("the transport's join never ran for the refused name")
                .noneMatch(line -> line.contains("--person carol"));
    }

    @Test
    void aJoinIntoAMissingVaultIsRefusedBeforeAnyAccountIsMade() throws Exception {
        // An account whose login nobody keeps must not be made.
        final PoreloContext context = removalContext("guarded", false);
        Files.delete(context.vault().path());

        final TalkReply reply = talk(context, "join", "p", "carol", "--into-vault", "talk.carol");

        assertThat(reply.code()).isEqualTo(69);
        assertThat(reply.err())
                .isEqualTo(
                        "porelo: there is no vault to keep the login in; 'porelo vault init' makes one\n");
        assertThat(runner.lines())
                .as("the transport's join never ran for the refused person")
                .noneMatch(line -> line.contains("--person carol"));
    }

    @Test
    void aTransportWithoutPartIsNeverAskedToRemoveAPerson() throws Exception {
        // An unsupported lifecycle verb must not cause a destructive fallback.
        final PoreloContext context = removalContext("guarded", false);
        final int before = runner.invocations().size();

        final TalkReply reply = talk(context, "remove", "p", "alice");

        assertThat(reply.code()).as("unsupported removal is unavailable").isEqualTo(69);
        assertThat(reply.err())
                .as("the refusal identifies the transport and missing verb")
                .contains("room", "part");
        assertThat(runner.invocations().subList(before, runner.invocations().size()))
                .as("only describe may run to discover support")
                .allSatisfy(
                        command ->
                                assertThat(command.arguments())
                                        .as("no lifecycle runs")
                                        .contains("describe"));
        assertThat(Conversations.members(context, project("guarded")))
                .as("unsupported removal preserves membership")
                .containsEntry("alice", "@alice");
    }

    @Test
    void aPersonNotListedIsRefusedWithoutRunningTheTransport() throws Exception {
        // A mistyped name must not revoke an account or contact the transport.
        final PoreloContext context = removalContext("guarded", true);
        final int before = runner.invocations().size();

        final TalkReply reply = talk(context, "remove", "p", "nobody");

        assertThat(reply.code()).as("an unknown member is a usage refusal").isEqualTo(64);
        assertThat(reply.err())
                .as("the refusal points to the available member list")
                .isEqualTo(
                        "porelo: 'nobody' has not joined p; 'porelo talk members p' lists who has\n");
        assertThat(runner.invocations())
                .as("no transport command runs for an unknown person")
                .hasSize(before);
    }

    @Test
    void aKeptAccountIsExplainedAndThePersonIsNoLongerListed() throws Exception {
        // Keeping an account on the server must still revoke this project's membership.
        final PoreloContext context = removalContext("guarded", true);
        runner.answering(" part ", "{\"kept\":\"@alice\",\"why\":\"belongs to another room\"}");

        final TalkReply reply = talk(context, "remove", "p", "alice");

        assertThat(reply.code()).as("kept is a successful part").isZero();
        assertThat(reply.out())
                .as("the account's continued existence is explained")
                .isEqualTo(
                        "removed   alice from p; their account @alice stays: belongs to another room\n");
        assertThat(Conversations.members(context, project("guarded")))
                .as("keeping the account does not keep the member")
                .doesNotContainKey("alice");
    }

    @Test
    void anAccountAlreadyGoneStillRemovesThePersonFromTheList() throws Exception {
        // Retry after remote removal must finish local revocation rather than leave stale access.
        final PoreloContext context = removalContext("guarded", true);
        runner.answering(" part ", "{}");

        final TalkReply reply = talk(context, "remove", "p", "alice");

        assertThat(reply.code()).as("an empty answer completes removal").isZero();
        assertThat(reply.out())
                .as("nothing left is distinct from a newly removed account")
                .isEqualTo("removed   alice (nothing was left in the room)\n");
        assertThat(Conversations.members(context, project("guarded")))
                .as("an empty answer also revokes membership")
                .doesNotContainKey("alice");
    }

    @Test
    void anOfflinePartIsRestrictedToLoopbackAndNeverToldTheMachine() throws Exception {
        // An offline revocation must not reach another host or pass an unsupported machine option.
        final PoreloContext context = removalContext("offline", true);
        runner.answering(" part ", "{}");

        assertThat(talk(context, "remove", "p", "alice").code())
                .as("offline removal succeeds")
                .isZero();
        assertThat(runner.only(" part ").arguments())
                .as("offline part is loopback only and never gets a login")
                .containsSubsequence("part", "--project", "p", "--person", "alice")
                .contains("--loopback-only")
                .doesNotContain("--login");
    }

    @Test
    void membersAndRemovalRefuseAProjectWithoutAConversationAsJoinDoes() throws Exception {
        // A project with no conversation must not be mistaken for an empty room or acted on.
        final PoreloContext context = removalContext("guarded", true);
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: p\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n");
        final TalkReply joined = talk(context, "join", "p", "alice");
        for (final String[] arguments :
                List.of(new String[] {"members", "p"}, new String[] {"remove", "p", "alice"})) {
            final TalkReply reply = talk(context, arguments);
            assertThat(reply.code()).as("no conversation is a usage refusal").isEqualTo(64);
            assertThat(reply.err())
                    .as("the same project state has join's explanation")
                    .isEqualTo(joined.err());
        }
    }

    @Test
    void anEmptyConversationListsNobodyWithoutCallingTheTransport() throws Exception {
        // A local listing must not need network access or confuse no members with a failure.
        final PoreloContext context = removalContext("guarded", true);
        Files.writeString(memberFile(context), "{}");
        final int before = runner.invocations().size();

        final TalkReply reply = talk(context, "members", "p");

        assertThat(reply.code()).as("empty membership is successful").isZero();
        assertThat(reply.out())
                .as("an empty conversation is identified")
                .isEqualTo("nobody has joined p\n");
        assertThat(runner.invocations()).as("listing never runs the transport").hasSize(before);
    }

    @Test
    void aRemovedPersonsIncomingWordsAreNotDeliveredToATask() throws Exception {
        // Revoking membership must also revoke delivery of unsigned person messages.
        final PoreloContext context = removalContext("guarded", true);
        runner.answering(" part ", "{}");
        final Mailbox review = mailbox("porelo-p-review");
        final var group = List.of(member("porelo-p-review", review, "p", "room"));
        final Path inbound =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/inbound/p"));
        Files.writeString(
                inbound.resolve("before--room-person-one.json"),
                "{\"sender\":\"@alice\",\"body\":\"before\",\"to\":[]}");
        final var conversations =
                new TransportConversations(
                        context, context.paths().messaging().transportDirectory());
        assertThat(conversations.pass(group).handed())
                .as("the joined person reaches the task before removal")
                .isNotEmpty();
        final List<Path> delivered;
        try (var files = Files.walk(review.root())) {
            delivered = files.filter(Files::isRegularFile).toList();
        }
        assertThat(talk(context, "remove", "p", "alice").code())
                .as("the person is removed")
                .isZero();
        Files.writeString(
                inbound.resolve("after--room-person-two.json"),
                "{\"sender\":\"@alice\",\"body\":\"after\",\"to\":[]}");

        final var outcome = conversations.pass(group);

        assertThat(outcome.handed()).as("a removed sender reaches no task").isEmpty();
        assertThat(outcome.failures())
                .as("the membership check explains the dropped line")
                .anySatisfy(
                        said ->
                                assertThat(said)
                                        .as("the sender is no longer admitted")
                                        .contains("@alice", "not in the project's conversation"));
        try (var files = Files.walk(review.root())) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .as("no file from the removed person reaches the mailbox")
                    .containsExactlyInAnyOrderElementsOf(delivered);
        }
    }

    private PoreloContext removalContext(String securityClass, boolean offersPart)
            throws Exception {
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"takes\":[\"login\"],\"lifecycle\":[\"setup\",\"join\""
                        + (offersPart ? ",\"part\"" : "")
                        + "]}");
        runner.answering(
                " setup ",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering(" join ", "{\"login\":{\"user\":\"@alice\"}}");
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .as("the fixture opens its vault without a kernel keyring")
                .isPresent();
        final String yaml =
                "project:\n  name: p\n  security_class: "
                        + securityClass
                        + "\nimage:\n  from: ubuntu:24.04\nmail:\n  transports:\n    room:\n      server: https://example.org\n";
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, yaml);
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("p", file);
        new TaskConversations(context).join(context, project(securityClass), "alice", false);
        final Map<String, String> members =
                new LinkedHashMap<>(Conversations.members(context, project(securityClass)));
        members.put("bob", "@bob");
        Files.writeString(memberFile(context), org.fuin.porelo.wire.Json.write(members));
        return context;
    }

    private Path memberFile(PoreloContext context) {
        return context.paths().xdg().state().resolve("transport/room/members/p.json");
    }

    private record TalkReply(int code, String out, String err) {}

    private TalkReply talk(PoreloContext context, String... arguments) {
        final var out = new java.io.StringWriter();
        final var err = new java.io.StringWriter();
        final var cli = new picocli.CommandLine(new TalkCommand(), new PoreloFactory(context));
        cli.setOut(new java.io.PrintWriter(out));
        cli.setErr(new java.io.PrintWriter(err));
        return new TalkReply(cli.execute(arguments), out.toString(), err.toString());
    }

    @Test
    void aWordToHelpersIsAnsweredOnceAsTheProjectWithoutWakingAnyTask() throws Exception {
        // A helper mention must not wake tasks or use their credentials to answer for the relay.
        answerToHelpers(0, false);
    }

    @Test
    void aWordToHelpersIsAnsweredWithTheProjectsSecretOnTheInputOfATransportTakingItSo()
            throws Exception {
        answerToHelpers(0, true);
    }

    @Test
    void aFailedHelperAnswerLeavesNoFilesAndWakesNoTaskAndIsNeverRetried() throws Exception {
        // A failed answer must not wake tasks or keep files that send it again on the next pass.
        answerToHelpers(TransportSend.TEMPORARY, false);
    }

    // Move 3: the room showed each task by its account, "porelo-porelo-pi", where the persons
    // wrote to "Agent Pi".
    @Test
    void aTaskIsEnrolledUnderItsAgentNameWhereTheTransportShowsOne() throws Exception {
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"display\":true,"
                        + "\"lifecycle\":[\"setup\",\"enroll\"]}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .isPresent();
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.setup("room", "p", Map.of());

        lifecycle.enroll("room", "p", "porelo-p-unit", Map.of());
        LegacyTaskFixture.record(context, "porelo-porelo-pi", "porelo", "a".repeat(64));
        lifecycle.enroll("room", "porelo", "porelo-porelo-pi", Map.of());

        final List<List<String>> enrolled =
                runner.invocations().stream()
                        .map(org.fuin.porelo.core.process.Command::arguments)
                        .filter(arguments -> arguments.contains("enroll"))
                        .toList();
        assertThat(enrolled)
                .extracting(arguments -> arguments.get(arguments.indexOf("--display") + 1))
                .containsExactly("Agent Unit", "Agent Pi");
    }

    /** A room whose transport shows the name it is given for each task, set up for project p. */
    private TransportLifecycle room() throws Exception {
        return room("{\"TASK\":\"t-1\"}");
    }

    /** The stand-in transport, its enroll printing these task secrets. */
    private TransportLifecycle room(final String taskSecrets) throws Exception {
        return room(taskSecrets, "{\"POLLER\":\"p-1\"}");
    }

    /** The stand-in transport, its setup printing these project secrets, its enroll these. */
    private TransportLifecycle room(final String taskSecrets, final String projectSecrets)
            throws Exception {
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"display\":true,"
                        + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\"]}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":"
                        + projectSecrets
                        + ",\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering("enroll", "{\"secrets\":" + taskSecrets + "}");
        runner.answering(" retire ", "{}");
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .as("the fixture opens its vault without a kernel keyring")
                .isPresent();
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.setup("room", "p", Map.of());
        return lifecycle;
    }

    /** The name each enroll so far handed the transport, in order. */
    private List<String> shown() {
        return runner.invocations().stream()
                .map(org.fuin.porelo.core.process.Command::arguments)
                .filter(arguments -> arguments.contains("enroll"))
                .map(
                        arguments ->
                                arguments.contains("--display")
                                        ? arguments.get(arguments.indexOf("--display") + 1)
                                        : "")
                .toList();
    }

    /**
     * Writes the names the room already shows the project's tasks by, as earlier enrolls kept them.
     */
    private void holding(final Map<String, String> names) throws IOException {
        final Path file = context().paths().xdg().state().resolve("transport/room/displays/p.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, org.fuin.porelo.wire.Json.write(names));
    }

    /** Records a task made under the new container name, with its own task name. */
    private void recorded(final PoreloContext context, final String container, final String task)
            throws IOException {
        LegacyTaskFixture.record(context, container, "p", "b".repeat(64));
        final Path sidecar = context.paths().tasks().taskRecord(container).resolve("sidecar.json");
        Files.writeString(
                sidecar, Files.readString(sidecar).replace("{", "{\"task\":\"" + task + "\","));
    }

    /** The task name each call of a verb so far handed the transport, in order. */
    private List<String> tasksGiven(final String verb) {
        return runner.invocations().stream()
                .map(org.fuin.porelo.core.process.Command::arguments)
                .filter(arguments -> arguments.contains(verb) && arguments.contains("--task"))
                .map(arguments -> arguments.get(arguments.indexOf("--task") + 1))
                .toList();
    }

    @Test
    void aTaskMadeUnderTheNewNameJoinsAndLeavesUnderTheContractsName() throws Exception {
        // Joined as porelo-p_unit, the room's members read it as nobody's task, and a person or a
        // rehearsal waiting on the project's running tasks finds none.
        final TransportLifecycle lifecycle = room();
        recorded(context(), "porelo-p_unit", "unit");

        lifecycle.enroll("room", "p", "porelo-p_unit", "scout", Map.of(), false);
        lifecycle.retire("room", "p", "porelo-p_unit", Map.of());

        assertThat(tasksGiven("enroll"))
                .as("enrolled under the transport contract's name")
                .containsExactly("porelo-p-unit");
        assertThat(tasksGiven("retire"))
                .as("retired under the name it was enrolled by")
                .containsExactly("porelo-p-unit");
    }

    @Test
    void aStoppedTaskOnACentralHomeserverLeavesItsRoomAndItsNextStartBringsItBack()
            throws Exception {
        // Left in the room, a stopped task is counted among the project's running tasks.
        final TransportLifecycle lifecycle =
                room("{\"TASK\":\"t-1\",\"PORELO_MATRIX_LOGIN\":\"ms\"}");
        recorded(context(), "porelo-p_unit", "unit");
        lifecycle.enroll("room", "p", "porelo-p_unit", "scout", Map.of(), false);

        final boolean left = lifecycle.leave("room", "p", "porelo-p_unit", Map.of());
        lifecycle.enroll("room", "p", "porelo-p_unit", "scout", Map.of(), false);

        assertThat(left).as("it left").isTrue();
        assertThat(tasksGiven("retire"))
                .as("retired at the stop, under the name it was enrolled by")
                .containsExactly("porelo-p-unit");
        assertThat(tasksGiven("enroll"))
                .as("enrolled again at the next start")
                .containsExactly("porelo-p-unit", "porelo-p-unit");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"{\"TASK\":\"t-1\",\"PORELO_MATRIX_LOGIN\":\"\"}", "{\"TASK\":\"t-1\"}"})
    void aStoppedTaskOnItsOwnHomeserverStaysInItsRoom(final String secrets) throws Exception {
        // There retire deactivates the account for good: a stop must never ask for it.
        final TransportLifecycle lifecycle = room(secrets);
        lifecycle.enroll("room", "p", "porelo-p-unit", "scout", Map.of(), false);

        assertThat(lifecycle.leave("room", "p", "porelo-p-unit", Map.of()))
                .as("nothing left")
                .isFalse();
        assertThat(tasksGiven("retire")).as("no retire").isEmpty();
    }

    @Test
    void onlyTheTasksOwnSecretsSayItsRoomIsOnACentralHomeserver() throws Exception {
        // The project's or the account's secrets may hold a login from another setup; a retire
        // read from them would end an account on its own homeserver for good.
        final TransportLifecycle lifecycle =
                room("{\"TASK\":\"t-1\"}", "{\"POLLER\":\"p-1\",\"PORELO_MATRIX_LOGIN\":\"ms\"}");
        lifecycle.enroll("room", "p", "porelo-p-unit", "scout", Map.of(), false);

        assertThat(lifecycle.leave("room", "p", "porelo-p-unit", Map.of()))
                .as("nothing left")
                .isFalse();
        assertThat(tasksGiven("retire")).as("no retire").isEmpty();
    }

    @Test
    void aTaskMadeBeforeKeepsItsName() throws Exception {
        // Its account exists under the name it has: another would be a second account.
        final TransportLifecycle lifecycle = room();

        lifecycle.enroll("room", "p", "porelo-p-unit", "scout", Map.of(), false);

        assertThat(tasksGiven("enroll"))
                .as("a task made before is enrolled as it was")
                .containsExactly("porelo-p-unit");
    }

    @Test
    void aMemberNamedByTheContractsNameIsItsTasksContainer() throws Exception {
        // Read back by the contract's name alone, the member is no recorded container and the
        // whole member list is refused.
        final TransportLifecycle lifecycle = room();
        recorded(context(), "porelo-p_unit", "unit");
        lifecycle.enroll("room", "p", "porelo-p_unit", "scout", Map.of(), false);
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"display\":true,\"secrets\":\"input\","
                        + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"members\"]}");
        runner.answering(
                " members ",
                "{\"room\":\"!room-p\",\"members\":[{\"id\":\"@porelo-p-unit.me:x\","
                        + "\"shown\":\"Agent Unit\",\"state\":\"join\",\"kind\":\"task\","
                        + "\"task\":\"porelo-p-unit\"}]}");
        // The task runs: podman lists it with the id its record holds.
        runner.answering(
                " ps ",
                "porelo-p_unit\tUp 1 minute\t\t\tp\tguarded\t\t\t" + "b".repeat(64) + "\tunit\n");
        final TransportLifecycle asked =
                new TransportLifecycle(
                        context().asking(prompt -> PASSPHRASE.clone()),
                        context().paths().messaging().transportDirectory());

        assertThat(asked.members("room", "p", Map.of(), false).members())
                .as("the member is the task's container")
                .extracting(RoomMembers.Member::task)
                .containsExactly("porelo-p_unit");
    }

    @Test
    void startsAtOnceMakeOneAccountAndEachTakesIt() throws Exception {
        // Each start that gives the transport no account secret has it make one of its own: the
        // first registers the person's account, the next finds it under another password and the
        // task never joins.
        final java.util.concurrent.atomic.AtomicInteger registered =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch inside =
                new java.util.concurrent.CountDownLatch(1);
        final org.fuin.porelo.core.process.CommandRunner transport =
                command -> {
                    final List<String> arguments = command.arguments();
                    if (arguments.contains("describe")) {
                        return new org.fuin.porelo.core.process.CommandResult(
                                command,
                                0,
                                "{\"scheme\":\"room\",\"poll\":true,"
                                        + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\"]}",
                                "");
                    }
                    if (!arguments.contains("setup")) {
                        return new org.fuin.porelo.core.process.CommandResult(command, 0, "{}", "");
                    }
                    String secret = command.environment().get("ADMIN");
                    if (secret == null) {
                        if (registered.incrementAndGet() > 1) {
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command, 78, "", "the account exists with another password");
                        }
                        secret = "a-" + registered.get();
                        inside.countDown();
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new org.fuin.porelo.core.process.CommandResult(
                            command,
                            0,
                            "{\"account\":{\"ADMIN\":\""
                                    + secret
                                    + "\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                                    + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}",
                            "");
                };
        final PoreloContext context = context(transport).asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .as("the fixture opens its vault without a kernel keyring")
                .isPresent();
        final java.util.concurrent.ExecutorService other =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> first =
                    other.submit(
                            () -> {
                                new TransportLifecycle(
                                                context,
                                                context.paths().messaging().transportDirectory())
                                        .setup("room", "p", Map.of());
                                return null;
                            });
            assertThat(inside.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the first start is making the account")
                    .isTrue();
            final TransportLifecycle second =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());

            second.setup("room", "p", Map.of());
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(registered.get()).as("one account is made").isEqualTo(1);
            assertThat(second.secrets("room", "account"))
                    .as("both keep the one account's secret")
                    .containsEntry("ADMIN", "a-1");
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void aTaskIsShownInTheRoomByItsOwnNameWhateverAgentRunsIt() throws Exception {
        // Shown by its agent's name, a line headed "Agent Fix-Login:" reaches nobody and the
        // person cannot tell two tasks of one agent apart.
        final TransportLifecycle lifecycle = room();

        lifecycle.enroll("room", "p", "porelo-p-fix-login", "scout", Map.of(), false);

        assertThat(shown())
                .as("the room shows the task by its own name")
                .containsExactly("Agent Fix-Login");
    }

    @Test
    void twoTasksOfOneAgentAreEachShownByTheirOwnNameOnEveryEnroll() throws Exception {
        // Two tasks of one agent shown alike would each take the other's words as their own.
        final TransportLifecycle lifecycle = room();

        lifecycle.enroll("room", "p", "porelo-p-scout", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-fix", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-scout", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-fix", "scout", Map.of(), false);

        assertThat(shown())
                .as("each keeps its own name on every enroll")
                .containsExactly("Agent Scout", "Agent Fix", "Agent Scout", "Agent Fix");
    }

    @Test
    void aTaskWhoseOwnNameAnotherHoldsIsShownWithItsAgentInBracketsUntilThatTaskIsRetired()
            throws Exception {
        // Handed on while the task holding it is still enrolled, a name would reach two tasks.
        final TransportLifecycle lifecycle = room();
        holding(Map.of("porelo-p-a", "Agent B", "porelo-p-c", "Agent D"));

        lifecycle.enroll("room", "p", "porelo-p-b", "scout", Map.of(), false);
        lifecycle.retire("room", "p", "porelo-p-c", Map.of());
        lifecycle.enroll("room", "p", "porelo-p-d", "scout", Map.of(), false);

        assertThat(shown())
                .as("held by an enrolled task it is not given; retired, it is free again")
                .containsExactly("Agent B (Scout)", "Agent D");
    }

    @Test
    void aRetiredTaskLeavesNoDirectChatsBehind() throws Exception {
        // Its direct chats were polled into a directory of its own; kept, its removal leaves it.
        final TransportLifecycle lifecycle = room();
        final Path direct =
                context().paths().xdg().state().resolve("transport/room/direct/porelo-p-all");
        Files.createDirectories(direct);
        Files.writeString(direct.resolve("said.json"), "{}");

        lifecycle.retire("room", "p", "porelo-p-all", Map.of());

        assertThat(direct).as("its direct chats go with it").doesNotExist();
        assertThat(direct.getParent()).as("the other tasks' stay").isDirectory();
    }

    @Test
    void aTaskEnrolledBeforeNamesWereKeptHoldsTheNameItWasShownByUntilItIsRetired()
            throws Exception {
        // Its room still shows it as "Agent Scout": a task outside the project's form, shown by
        // its agent, given that name too would share it.
        final TransportLifecycle lifecycle = room();
        final Path file =
                context().paths().xdg().state().resolve("transport/room/addresses/p.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"porelo-p-scout\":\"@porelo-p-scout\"}");

        lifecycle.enroll("room", "p", "custom-box", "scout", Map.of(), false);
        lifecycle.retire("room", "p", "porelo-p-scout", Map.of());
        lifecycle.enroll("room", "p", "other-box", "scout", Map.of(), false);

        assertThat(shown())
                .as("held by the earlier task while it is enrolled, free once it is retired")
                .containsExactly("Agent Scout (custom-box)", "Agent Scout");
    }

    @Test
    void aHeldNameAnotherTaskHoldsTooIsNotKept() throws Exception {
        // Kept unchecked, an earlier task's name that a later one was shown by reaches both.
        final TransportLifecycle lifecycle = room();
        holding(Map.of("porelo-p-x", "Agent Pi"));
        final Path file =
                context().paths().xdg().state().resolve("transport/room/addresses/p.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"porelo-p-pi\":\"@porelo-p-pi\"}");

        lifecycle.enroll("room", "p", "porelo-p-pi", "claude", Map.of(), false);

        assertThat(shown()).containsExactly("Agent Pi (Claude)");
    }

    @Test
    void aTaskNamedAfterItsAgentKeepsTheNameItIsGivenNow() throws Exception {
        // Given up on every enroll, the name of a task called as its agent changed back and forth.
        final TransportLifecycle lifecycle = room();
        holding(Map.of("porelo-p-fix", "Agent Scout", "porelo-p-scout", "Agent Scout (scout)"));

        lifecycle.enroll("room", "p", "porelo-p-scout", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-fix", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-scout", "scout", Map.of(), false);

        assertThat(shown())
                .containsExactly("Agent Scout (Scout)", "Agent Fix", "Agent Scout (Scout)");
    }

    @Test
    void aNameHeldInTheAgentsFormIsGivenUpForTheTasksOwn() throws Exception {
        // Kept, a task named before the room showed tasks by their own names would stay "Agent
        // Scout (fix)", and a line headed "Agent Fix:" would never reach it.
        final TransportLifecycle lifecycle = room();
        holding(Map.of("porelo-p-main", "Agent Scout", "porelo-p-fix", "Agent Scout (fix)"));

        lifecycle.enroll("room", "p", "porelo-p-main", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-fix", "scout", Map.of(), false);

        assertThat(shown()).containsExactly("Agent Main", "Agent Fix");
    }

    @Test
    void aNameHeldInAnotherFormIsKept() throws Exception {
        // A name the room shows a task by that is not its agent's form was chosen for it; renamed,
        // the persons' lines to it would reach nobody.
        final TransportLifecycle lifecycle = room();
        holding(Map.of("porelo-p-x", "Agent Pi", "porelo-p-y", "Agent Scout (Reviewer)"));

        lifecycle.enroll("room", "p", "porelo-p-x", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-y", "scout", Map.of(), false);

        assertThat(shown()).containsExactly("Agent Pi", "Agent Scout (Reviewer)");
    }

    @Test
    void tasksWithLongNamesAlikeAreEachShownByANameOfTheirOwn() throws Exception {
        // Two long names alike once cut to one, and the second was given no name and the room
        // showed
        // its account.
        final TransportLifecycle lifecycle = room();
        final String base = "feature-authentication-oauth-token-refresh-handling-v";

        lifecycle.enroll("room", "p", "porelo-p-main", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-" + base + "1", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-" + base + "2", "scout", Map.of(), false);
        lifecycle.enroll("room", "p", "porelo-p-" + base + "3", "scout", Map.of(), false);

        assertThat(shown())
                .as("every task is handed a name, none twice, each within 64")
                .hasSize(4)
                .doesNotContain("")
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(64));
    }

    @Test
    void tasksOfOneAgentEnrolledAtOnceAreEachGivenANameNoOtherHolds() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        // Each start that chose its name before another recorded one took "Agent Scout" too: tasks
        // outside the project's form all want their agent's name.
        final int tasks = 6;
        final List<String> displays =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final org.fuin.porelo.core.process.CommandRunner enrolling =
                command -> {
                    final List<String> arguments = command.arguments();
                    if (arguments.contains("describe")) {
                        return new org.fuin.porelo.core.process.CommandResult(
                                command,
                                0,
                                "{\"scheme\":\"room\",\"display\":true,"
                                        + "\"lifecycle\":[\"setup\",\"enroll\"]}",
                                "");
                    }
                    if (!arguments.contains("enroll")) {
                        return new org.fuin.porelo.core.process.CommandResult(command, 0, "", "");
                    }
                    displays.add(arguments.get(arguments.indexOf("--display") + 1));
                    try {
                        // As slow as a transport that makes an account, so the starts overlap.
                        Thread.sleep(150);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    final String task = arguments.get(arguments.indexOf("--task") + 1);
                    return new org.fuin.porelo.core.process.CommandResult(
                            command, 0, "{\"address\":\"@" + task + "\"}", "");
                };
        final PoreloContext context = context(enrolling);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        VaultCache.hold();
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            final TransportLifecycle lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());
            final List<Throwable> failed =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            final List<Thread> starts =
                    java.util.stream.IntStream.range(0, tasks)
                            .mapToObj(
                                    n ->
                                            Thread.ofPlatform()
                                                    .start(
                                                            () -> {
                                                                try {
                                                                    lifecycle.enroll(
                                                                            "room",
                                                                            "p",
                                                                            "custom-t" + n,
                                                                            "scout",
                                                                            Map.of(),
                                                                            false);
                                                                } catch (final Exception ex) {
                                                                    failed.add(ex);
                                                                }
                                                            }))
                            .toList();
            for (final Thread start : starts) {
                start.join(java.time.Duration.ofSeconds(30));
            }

            assertThat(failed).as("every enroll ran").isEmpty();
            assertThat(displays)
                    .as("no two tasks were handed one name, however their starts overlapped")
                    .hasSize(tasks)
                    .doesNotHaveDuplicates();
        } finally {
            VaultCache.stop();
            keyring.forget();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Agent Scout",
                "agent scout",
                "AGENT-Scout",
                " Agent\u00a0Scout",
                "agent-scout",
                "\u200bAgent Scout",
                "Agent\u200bScout",
                "Agent\tScout",
                "\uff21\uff47\uff45\uff4e\uff54 Scout",
                "agent",
                "Agent_Scout",
                "agent.scout"
            })
    void aPersonCannotJoinUnderANameTheRoomShowsForATask(final String name) throws Exception {
        // A person shown as "Agent Scout" would be read as the task's own words.
        final PoreloContext context = removalContext("guarded", false);

        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        final boolean login = name.matches("[a-z][a-z0-9-]*");

        final TalkReply byDisplay = talk(context, "join", "p", "carol", "--display", name);
        assertThat(byDisplay.code()).as("refused as a display: %s", byDisplay.err()).isNotZero();
        assertThatThrownBy(
                        () ->
                                lifecycle.join(
                                        "room", "p", "carol", false, Map.of(), false, false, name))
                .as("the transport's join refuses it as a display, whoever calls it")
                .isInstanceOf(TransportLifecycle.Refused.class);
        if (login) {
            assertThatThrownBy(
                            () ->
                                    lifecycle.join(
                                            "room", "p", name, false, Map.of(), false, false, null))
                    .as("the transport's join refuses it as a person's name")
                    .isInstanceOf(TransportLifecycle.Refused.class);
        }
        assertThat(runner.lines())
                .as("the transport's join never ran for the refused name")
                .noneMatch(
                        line -> line.contains("--person carol") || line.contains("--person agent"));
    }

    @Test
    void aTransportThatShowsNoNameIsGivenNone() throws Exception {
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"lifecycle\":[\"setup\",\"enroll\"]}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.setup("room", "p", Map.of());

        lifecycle.enroll("room", "p", "porelo-p-unit", Map.of());

        assertThat(runner.invocations())
                .filteredOn(command -> command.arguments().contains("enroll"))
                .singleElement()
                .satisfies(command -> assertThat(command.arguments()).doesNotContain("--display"));
    }

    private void answerToHelpers(int exit, boolean onInput) throws Exception {
        final String line =
                "This is Porelo's technical account; it passes your messages on to the tasks and does not answer itself. Mention a task to reach it.";
        final List<Path> temporary = new java.util.ArrayList<>();
        final List<byte[]> answers = new java.util.ArrayList<>();
        final List<String> signatures = new java.util.ArrayList<>();
        final List<String> shown = new java.util.ArrayList<>();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"shown\":true,\"mention\":true,\"lifecycle\":[\"setup\",\"enroll\",\"join\"]"
                        + input(onInput)
                        + "}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1\"]}");
        runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"},\"address\":\"@review\"}");
        runner.answering("join", "{\"login\":{\"user\":\"@alice\"}}");
        runner.failing(" send ", exit, exit == 0 ? "" : "the homeserver is busy");
        final PoreloContext context =
                context(
                                command -> {
                                    final List<String> arguments = command.arguments();
                                    if (arguments.contains("send")) {
                                        try {
                                            final Path message = Path.of(arguments.get(2));
                                            final Path signature = Path.of(arguments.get(3));
                                            final Path shownFile =
                                                    Path.of(
                                                            arguments.get(
                                                                    arguments.indexOf("--shown")
                                                                            + 1));
                                            temporary.addAll(
                                                    List.of(message, signature, shownFile));
                                            answers.add(Files.readAllBytes(message));
                                            signatures.add(Files.readString(signature));
                                            shown.add(Files.readString(shownFile));
                                        } catch (IOException ex) {
                                            throw new java.io.UncheckedIOException(ex);
                                        }
                                    }
                                    return runner.run(command);
                                })
                        .asking(prompt -> PASSPHRASE.clone());
        Files.createDirectories(context.vault().path().getParent());
        emptyVault(context);
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        lifecycle.setup("room", "p", Map.of());
        lifecycle.enroll("room", "p", "porelo-p-review", Map.of());
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                mail:
                  transports:
                    room: {server: 'https://example.org'}
                """);
        new ProjectRegistry(context.paths().projects().projectRegistry())
                .remember("p", projectFile);
        new TaskConversations(context).join(context, project("guarded"), "alice", false);
        assertThat(lifecycle.people("room", "p"))
                .as("the fixture admits the sender")
                .containsEntry("alice", "@alice");
        final var key = HostKey.loadOrCreate(context.paths().messaging().messageKey(), "fixture");
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/inbound/p"));
        final String name = "arrival--room-person-helper.json";
        Files.writeString(
                inbound.resolve(name),
                "{\"sender\":\"@alice\",\"body\":\"Who are you?\",\"to\":[],\"helpers\":true}");
        final List<TransportConversations.Member> members =
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room"));
        final TransportConversations conversations =
                new TransportConversations(
                        context, context.paths().messaging().transportDirectory());

        final TransportConversations.Outcome outcome = conversations.pass(members);
        conversations.pass(members);

        final List<org.fuin.porelo.core.process.Command> sends =
                runner.invocations().stream()
                        .filter(command -> command.arguments().contains("send"))
                        .toList();
        assertThat(sends).as("the relay answers exactly once and never queues a retry").hasSize(1);
        final var sent = sends.getFirst();
        handed(sent, onInput, "POLLER", "p-1");
        notHanded(sent, "ADMIN");
        notHanded(sent, "TASK");
        assertThat(sent.arguments())
                .as("the answer goes to the project's room and mentions its author")
                .containsSubsequence("--to", "!room-p")
                .containsSubsequence("--mention", "@alice");
        assertThat(shown)
                .as("the host supplies the standard line while send runs")
                .containsExactly(line);
        final Map<?, ?> answer =
                (Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                new String(answers.getFirst(), StandardCharsets.UTF_8));
        assertThat(((Map<?, ?>) answer.get("metadata")).get("from"))
                .as("Porelo speaks for its own technical account")
                .isEqualTo("porelo");
        assertThat(((Map<?, ?>) answer.get("metadata")).get("to"))
                .as("the answer addresses the person's account")
                .isEqualTo("@alice");
        assertThat(
                        TransportSend.shown(
                                new String(answers.getFirst(), StandardCharsets.UTF_8), "porelo"))
                .as("the signed message holds the same standard line")
                .isEqualTo(line);
        final byte[] signer = org.fuin.porelo.vault.SshSignature.signerOf(signatures.getFirst());
        assertThat(signer).as("the answer is signed").isNotNull();
        assertThat(
                        org.fuin.porelo.vault.SshSignature.verify(
                                answers.getFirst(),
                                signatures.getFirst(),
                                signer,
                                MessageIntake.NAMESPACE))
                .as("a key of its own signs the exact answer bytes")
                .isTrue();
        assertThat(signer).as("never this machine's message key").isNotEqualTo(key.keyBlob());
        assertThat(outcome.handed()).as("no task is handed the helper mention").isEmpty();
        for (final Mailbox mailbox : List.of(review, build)) {
            try (var files = Files.walk(mailbox.root())) {
                assertThat(files.filter(Files::isRegularFile))
                        .as("no task gains a message or queued answer")
                        .isEmpty();
            }
        }
        try (var files = Files.list(inbound)) {
            assertThat(files).as("the person file and all answer files are removed").isEmpty();
        }
        assertThat(temporary)
                .as("message, signature and shown line were supplied")
                .hasSize(3)
                .allSatisfy(
                        file ->
                                assertThat(file)
                                        .as("an answer file is removed after sending")
                                        .doesNotExist());
        assertThat(outcome.failures())
                .as("the hand-out says whether the person was answered")
                .contains(
                        exit == 0
                                ? "answered " + name + ": it named only the room's helpers"
                                : "could not answer "
                                        + name
                                        + ": the transport refused it, exit 75");
    }

    private PoreloContext context() throws IOException {
        return context(runner);
    }

    private PoreloContext context(final org.fuin.porelo.core.process.CommandRunner runner)
            throws IOException {
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
        // An adapter the directory finds: its name after the prefix is the scheme.
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        for (String container :
                List.of(
                        "porelo-p-a",
                        "porelo-p-b",
                        "porelo-p-build",
                        "porelo-p-c",
                        "porelo-p-d",
                        "porelo-p-fix",
                        "porelo-p-fix-login",
                        "porelo-p-main",
                        "porelo-p-other",
                        "porelo-p-pi",
                        "porelo-p-pi-2",
                        "porelo-p-review",
                        "porelo-p-scout",
                        "porelo-p-t",
                        "porelo-p-unit",
                        "porelo-p-write",
                        "porelo-p-writer",
                        "porelo-p-x",
                        "porelo-p-y")) {
            LegacyTaskFixture.record(context, container, "p", "a".repeat(64));
        }
        return context;
    }

    private static Project project(String securityClass) {
        return ProjectReader.read(
                new java.io.StringReader(
                        """
                project:
                  name: "p"
                  security_class: "%s"
                image:
                  from: "ubuntu:24.04"
                mail:
                  transports:
                    room:
                      server: https://example.org
                  peers:
                    reviewer: { address: "room:", trust: vouched }
                """
                                .formatted(securityClass)),
                "test");
    }

    private Mailbox mailbox(String container) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve(container));
        mailbox.create();
        return mailbox;
    }

    private static void message(Path directory, String name, Object to) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(name),
                org.fuin.porelo.wire.Json.write(
                        Map.of(
                                "role",
                                MessageIntake.AGENT,
                                "metadata",
                                Map.of("to", to),
                                "body",
                                "hi")),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(name + ".sig"), "sig", StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("namedTaskRecipients")
    void whatArrivesGoesOnlyToTheTaskItNamesAndNothingIsKept(final Object to) throws IOException {
        // Waking an unaddressed task wastes its work and can start a loop of unwanted answers.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Mailbox writer = mailbox("porelo-p-writer");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", to);
        Files.writeString(inbound.resolve("$a.json.sender"), "@writer");
        final byte[] original = Files.readAllBytes(inbound.resolve("$a.json"));
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        final int dropped =
                TransportConversations.handOut(
                        inbound,
                        List.of(
                                member("porelo-p-review", review, "p", "room"),
                                member("porelo-p-build", build, "p", "room"),
                                member("porelo-p-writer", writer, "p", "room")),
                        Map.of("porelo-p-writer", "@writer"),
                        Map.of(),
                        handed,
                        said);

        assertThat(build.inbound().resolve("$a.json"))
                .as("an unaddressed task receives nothing")
                .doesNotExist();
        assertThat(writer.inbound().resolve("$a.json"))
                .as("the sender receives nothing back")
                .doesNotExist();
        assertThat(Files.readAllBytes(review.inbound().resolve("$a.json")))
                .as("the named task gets the original signed bytes")
                .isEqualTo(original);
        assertThat(review.inbound().resolve("$a.json.sig"))
                .as("the signature travels with the message")
                .hasContent("sig");
        assertThat(handed)
                .as("only the named task is handed the message")
                .containsExactlyEntriesOf(Map.of("$a.json", "porelo-p-review"));
        assertThat(dropped).as("the named task is here").isZero();
        assertThat(said).as("no answer or diagnostic is added").isEmpty();
        assertThat(inbound).as("the message and its companions are consumed").isEmptyDirectory();
    }

    private static Stream<Object> namedTaskRecipients() {
        return Stream.of(
                "porelo-p-review",
                "review",
                "Agent Review",
                List.of("review"),
                List.of("", "Agent Review", " "));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("otherRecipients")
    void aTasksMessageNamingNobodyHereReachesNoTaskSilently(final Object to) throws IOException {
        // A foreign or inexact recipient must not leak a message into an unrelated task's mailbox.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", to);
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room")),
                handed,
                said);

        assertThat(review.inbound().resolve("$a.json"))
                .as("no local task was named")
                .doesNotExist();
        assertThat(build.inbound().resolve("$a.json"))
                .as("no fallback to another task")
                .doesNotExist();
        assertThat(handed).as("nothing is handed here").isEmpty();
        assertThat(said)
                .as("the room already shows it; no answer or diagnostic is added")
                .isEmpty();
        assertThat(inbound).as("nothing waits for another pass").isEmptyDirectory();
    }

    @Test
    void aMessageForATaskRemovedMeanwhileMakesNoMailboxAgain() throws IOException {
        // A conversation loop works on the members its last pass found: a task removed since
        // got its mailbox back as an inbound and a record, and its name stayed taken.
        final Mailbox review = mailbox("porelo-p-review");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "review");
        review.delete();
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound, List.of(member("porelo-p-review", review, "p", "room")), handed, said);

        assertThat(review.root()).as("no mailbox made again").doesNotExist();
        assertThat(handed).as("handed to nobody").isEmpty();
    }

    @Test
    void aReaderGoneSinceTheMembersWereLookedAtIsSkippedAndTheOthersGetIt() throws IOException {
        // One task's mailbox went between the look at the members and the hand-out: the failure
        // ended the whole conversation's pass, and the others never got what came. Stand-in for
        // the removal in between: a mailbox the look takes as there and the hand-out does not.
        final Mailbox review = mailbox("porelo-p-review");
        final Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        final Mailbox build = new Mailbox(dir.resolve("mail").resolve("porelo-p-build"));
        Files.createSymbolicLink(build.root(), elsewhere);
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", List.of("review", "build"));
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room")),
                handed,
                said);

        assertThat(review.inbound().resolve("$a.json")).as("the other reader has it").exists();
        assertThat(handed).containsExactly(Map.entry("$a.json", "porelo-p-review"));
        assertThat(elsewhere).as("nothing written for the gone one").isEmptyDirectory();
    }

    private static Stream<Object> otherRecipients() {
        return Stream.of(
                "someone-on-another-machine",
                "porelo-q-review",
                "alice",
                "Review",
                "Agent review",
                "@porelo-p-review:example.org",
                "task:p:review",
                " review ",
                "Agent Review ",
                "",
                List.of());
    }

    @Test
    void aDerivedDisplayCannotTakeAnotherMembersRecordedName() throws IOException {
        // A missing enrollment must not leak a message addressed to another member's room name.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox scout = mailbox("porelo-p-scout");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "Agent Scout");
        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room", "Agent Scout"),
                        member("porelo-p-scout", scout, "p", "room")),
                new LinkedHashMap<>(),
                new java.util.ArrayList<>());
        assertThat(scout.inbound().resolve("$a.json"))
                .as("a recorded display belongs only to its recorded owner")
                .doesNotExist();
        assertThat(review.inbound().resolve("$a.json"))
                .as("the recorded owner receives the message")
                .exists();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "absent,review",
        "agent,review",
        "ROLE_SYSTEM,review",
        "absent,people",
        "agent,people",
        "ROLE_SYSTEM,people"
    })
    void onlyAPersonsRoleCanBroadcastASignedMessage(final String role, final String to)
            throws IOException {
        // An unusual or missing task role must not broadcast private work or answers to people.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", to);
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("metadata", Map.of("to", to));
        if (!role.equals("absent")) {
            document.put("role", role);
        }
        Files.writeString(inbound.resolve("$a.json"), org.fuin.porelo.wire.Json.write(document));
        final List<String> said = new java.util.ArrayList<>();
        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room")),
                new LinkedHashMap<>(),
                said);
        assertThat(build.inbound().resolve("$a.json"))
                .as("a signed non-person message reaches no unaddressed task")
                .doesNotExist();
        assertThat(Files.exists(review.inbound().resolve("$a.json")))
                .as("only the named task receives it; people reaches none")
                .isEqualTo(to.equals("review"));
        assertThat(said).as("no answer is added").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"review\",null]", "[true,\"review\",42,{},[]]"})
    void nonStringListEntriesDoNotEraseNamedRecipients(final String recipients) throws IOException {
        // One optional or malformed list entry must not silently discard another task's message.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", org.fuin.porelo.wire.Json.parse(recipients));
        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room")),
                new LinkedHashMap<>(),
                new java.util.ArrayList<>());
        assertThat(review.inbound().resolve("$a.json"))
                .as("string recipients survive non-string list entries")
                .exists();
        assertThat(build.inbound().resolve("$a.json"))
                .as("non-string entries name no other task")
                .doesNotExist();
    }

    @Test
    void aTasksMessageNamingSeveralTasksReachesEachOfThemAndNoOther() throws IOException {
        // A message to two tasks that reached neither would be lost for both.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Mailbox scout = mailbox("porelo-p-scout");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", List.of("review", "porelo-p-build", "alice"));
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-review", review, "p", "room"),
                        member("porelo-p-build", build, "p", "room"),
                        member("porelo-p-scout", scout, "p", "room")),
                handed,
                said);

        assertThat(review.inbound().resolve("$a.json")).as("named by its short name").exists();
        assertThat(build.inbound().resolve("$a.json")).as("named by its container").exists();
        assertThat(scout.inbound().resolve("$a.json")).as("not named").doesNotExist();
    }

    @Test
    void aTasksMessageToPeopleReachesNoTask() throws IOException {
        // An answer meant for the persons must not wake tasks that answer it in turn.
        final Mailbox review = mailbox("porelo-p-review");
        final Mailbox build = mailbox("porelo-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "people");
        Files.writeString(inbound.resolve("$a.json.sender"), "@writer");
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        final int dropped =
                TransportConversations.handOut(
                        inbound,
                        List.of(
                                member("porelo-p-review", review, "p", "room"),
                                member("porelo-p-build", build, "p", "room")),
                        handed,
                        said);

        assertThat(review.inbound().resolve("$a.json")).as("people means no task").doesNotExist();
        assertThat(build.inbound().resolve("$a.json"))
                .as("people means no other task either")
                .doesNotExist();
        assertThat(handed).as("the persons read it in the room").isEmpty();
        assertThat(dropped).as("the room still holds the message").isZero();
        assertThat(said).as("no answer or diagnostic is added").isEmpty();
        assertThat(inbound).as("the message and its companions are consumed").isEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo-p-writer", "writer", "Agent Writer"})
    void aTaskIsNotHandedItsOwnPostEvenWhenItNamesItself(final String to) throws IOException {
        // Echoing a task's own post can wake it into answering itself indefinitely.
        final Mailbox writer = mailbox("porelo-p-writer");
        final Mailbox review = mailbox("porelo-p-review");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", to);
        Files.writeString(inbound.resolve("$a.json.sender"), "@writer");
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound,
                List.of(
                        member("porelo-p-writer", writer, "p", "room"),
                        member("porelo-p-review", review, "p", "room")),
                Map.of("porelo-p-writer", "@writer"),
                Map.of(),
                handed,
                said);

        assertThat(writer.inbound().resolve("$a.json"))
                .as("the posting account is excluded even when it is the recipient")
                .doesNotExist();
        assertThat(review.inbound().resolve("$a.json"))
                .as("a self-addressed post is not broadcast to another task")
                .doesNotExist();
        assertThat(handed).as("no task receives the echo").isEmpty();
        assertThat(said).as("no answer is added").isEmpty();
        assertThat(inbound).as("the echo and companions are consumed").isEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "null"})
    void aNonStringRecipientCannotTurnIntoATasksName(final String literal) throws IOException {
        // Coercing JSON values into names can hand a message to a task it never named.
        final String container = "porelo-p-" + literal;
        final Mailbox task = mailbox(container);
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", org.fuin.porelo.wire.Json.parse("[" + literal + "]"));
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(
                inbound, List.of(member(container, task, "p", "room")), handed, said);

        assertThat(task.inbound().resolve("$a.json"))
                .as("a JSON literal is not a string naming the task")
                .doesNotExist();
        assertThat(handed).as("an invalid address reaches no task").isEmpty();
        assertThat(said).as("no answer is added").isEmpty();
        assertThat(inbound).as("nothing waits for another pass").isEmptyDirectory();
    }

    @Test
    void aMessageTheAgentTookIsMarkedReadOnceAndOnlyAMessageThatCameThroughTheConversation()
            throws IOException {
        final Mailbox review = mailbox("porelo-p-review");
        Files.writeString(
                review.record().resolve(TransportConversations.ARRIVED), "$a.json\n$b.json\n");
        // Taken by the agent: $a as a maildir reader names it, with flags; $b not yet; x never came
        // that way.
        Files.writeString(review.inboxCur().resolve("$a.json:2,S"), "{}");
        Files.writeString(review.inboxNew().resolve("$b.json"), "{}");
        Files.writeString(review.inboxCur().resolve("x.json"), "{}");

        assertThat(TransportConversations.taken(review)).containsExactly("$a.json");
        Files.writeString(review.record().resolve(TransportConversations.MARKED), "$a.json\n");
        assertThat(TransportConversations.taken(review)).as("marked once").isEmpty();
        assertThat(TransportConversations.reference("$a.json")).isEqualTo("$a");
    }

    @ParameterizedTest(name = "secrets on the input: {0}")
    @ValueSource(booleans = {false, true})
    void aPassPollsOncePerProjectWithTheProjectsSecretsAndMarksWhatWasTaken(boolean onInput)
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"direct\":true,"
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]"
                            + input(onInput)
                            + "}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("porelo-p-review");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            // Set up again at the next start: given back what it printed, the account's and the
            // project's.
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-other"))
                    .isNull();
            final org.fuin.porelo.core.process.Command again =
                    runner.invocations().stream()
                            .filter(command -> command.describe().contains(" setup "))
                            .toList()
                            .getLast();
            handed(again, onInput, "ADMIN", "a-1");
            handed(again, onInput, "POLLER", "p-1");
            Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n");
            Files.writeString(review.inboxCur().resolve("$a.json"), "{}");
            final TransportConversations.Outcome outcome =
                    new TransportConversations(
                                    context, context.paths().messaging().transportDirectory())
                            .pass(List.of(member("porelo-p-review", review, "p", "room")));

            assertThat(outcome.marked()).containsExactly("$a.json");
            final List<org.fuin.porelo.core.process.Command> polls =
                    runner.invocations().stream()
                            .filter(command -> command.arguments().contains("poll"))
                            .toList();
            final org.fuin.porelo.core.process.Command poll =
                    polls.stream()
                            .filter(command -> !command.arguments().contains("--direct"))
                            .findFirst()
                            .orElseThrow();
            handed(poll, onInput, "POLLER", "p-1");
            notHanded(poll, "ADMIN");
            final org.fuin.porelo.core.process.Command direct =
                    polls.stream()
                            .filter(command -> command.arguments().contains("--direct"))
                            .findFirst()
                            .orElseThrow();
            handed(direct, onInput, "TASK", "t-1");
            notHanded(direct, "POLLER");
            final org.fuin.porelo.core.process.Command read = runner.only(" read ");
            handed(read, onInput, "TASK", "t-1");
            notHanded(read, "ADMIN");
            assertThat(runner.only("--task porelo-p-review").input())
                    .as("the project's settings, verbatim")
                    .contains(
                            onInput
                                    ? "\"settings\":{\"server\":\"https://example.org\"}"
                                    : "{\"server\":\"https://example.org\"}");
            // Sending acts as the task, to the conversation setup named.
            assertThat(
                            new TransportConversations(
                                            context,
                                            context.paths().messaging().transportDirectory())
                                    .acting("room", "p", "porelo-p-review"))
                    .isEqualTo(new TransportSend.Acting(Map.of("TASK", "t-1"), "!room-p"));
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aReadTheHomeserverRefusesIsReportedAndNeverRecordedAsMarkedWhileATemporaryOneIsAskedAgain()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        // 77 is the homeserver refusing - a rejected token, an account in no room that holds the
        // event - and was recorded as marked, so the sender's receipt said its message was read
        // when nobody had confirmed it.
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"lifecycle\":[\"setup\",\"enroll\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.failing(" read $refused", 77, "M_FORBIDDEN: the token was rejected");
            runner.failing(" read $later", TransportSend.TEMPORARY, "the homeserver is busy");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final Mailbox review = mailbox("porelo-p-review");
            Files.writeString(
                    review.record().resolve(TransportConversations.ARRIVED),
                    "$refused.json\n$later.json\n");
            Files.writeString(review.inboxCur().resolve("$refused.json"), "{}");
            Files.writeString(review.inboxCur().resolve("$later.json"), "{}");
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members =
                    List.of(member("porelo-p-review", review, "p", "room"));

            final TransportConversations.Outcome first = conversations.pass(members);
            final TransportConversations.Outcome second = conversations.pass(members);

            assertThat(first.marked()).as("refused is not done").isEmpty();
            assertThat(second.marked()).isEmpty();
            assertThat(review.record().resolve(TransportConversations.MARKED)).doesNotExist();
            assertThat(first.failures())
                    .singleElement()
                    .asString()
                    .contains("refused")
                    .contains("exit 77")
                    .contains("M_FORBIDDEN");
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $refused")))
                    .as("a refusal is final, asking again every pass changes nothing")
                    .hasSize(1);
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $later")))
                    .as("only a temporary failure is asked again")
                    .hasSize(2);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aTransportThatTakesTheLoginIsGivenThisAccountsLoginAndNoMachinesName() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"login\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{},\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();

            // Two persons on one homeserver run tasks of the same name; the login keeps them apart.
            assertThat(runner.only(" setup ").arguments())
                    .as("a first setup is given this account's login on this host")
                    .containsSequence("--login", System.getProperty("user.name"))
                    .doesNotContain("--machine", PoreloContext.TEST_MACHINE);
            assertThat(runner.only(" enroll ").arguments())
                    .as("and so is every task's enroll")
                    .containsSequence("--login", System.getProperty("user.name"))
                    .doesNotContain("--machine", PoreloContext.TEST_MACHINE);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aNameTheTransportAnsweredAtSetupNeverReplacesTheAccountsOwn() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"login\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{},\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"],"
                            + "\"login\":\"build-1\"}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .as("the task enrolls")
                    .isNull();

            // Passing a name back would let two persons share it in the room.
            assertThat(runner.only(" enroll ").arguments())
                    .as("this account's login, not the one the transport answered at setup")
                    .containsSequence("--login", System.getProperty("user.name"))
                    .doesNotContain("build-1");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void anAccountWhoseLoginNoTransportTakesIsRefusedBeforeTheTransportRuns() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        final String login = System.getProperty("user.name");
        System.setProperty("user.name", "al ice");
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"login\"]}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{},\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");

            final String refused =
                    new TaskConversations(context).enroll(project("guarded"), "porelo-p-review");

            // A login no transport takes must never reach the transport's command line.
            assertThat(refused).as("the task is refused, naming what is wrong").contains("al ice");
            assertThat(runner.lines().stream().filter(line -> line.contains(" setup ")))
                    .as("the transport was never run with it")
                    .isEmpty();
            assertThatThrownBy(
                            () ->
                                    new TransportLifecycle(
                                                    context,
                                                    context.paths()
                                                            .messaging()
                                                            .transportDirectory())
                                            .setup("room", "p", Map.of()))
                    .as("a configuration fault, which the daemon's set-up answers with")
                    .isInstanceOfSatisfying(
                            TransportLifecycle.Refused.class,
                            ex -> assertThat(ex.code()).as("exit").isEqualTo(78));
        } finally {
            System.setProperty("user.name", login);
            keyring.forget();
        }
    }

    @Test
    void aMachineNotLetIntoASharedConversationKeepsItsAccountAndStartsNoTaskUntilItIs()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                            + "\"takes\":[\"login\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"MACHINE\":\"m-1\"},\"login\":\"host-b\","
                            + "\"admitted\":false,\"address\":\"@porelo.host-b:example.org\",\"room\":\"#p:example.org\"}");

            final String refused =
                    new TaskConversations(context).enroll(project("guarded"), "porelo-p-review");

            assertThat(refused)
                    .as("whom to let in, and where")
                    .contains("@porelo.host-b:example.org")
                    .contains("#p:example.org");
            assertThat(runner.lines())
                    .as("no task is enrolled while the machine is not in")
                    .noneMatch(each -> each.contains(" enroll "));
            assertThat(
                            new TransportLifecycle(
                                            context,
                                            context.paths().messaging().transportDirectory())
                                    .secrets("room", "account"))
                    .as("the account it registered is kept, not lost with a refusal")
                    .containsEntry("MACHINE", "m-1");
            assertThat(Conversations.row(context, project("guarded")))
                    .containsEntry("machine", "host-b")
                    .containsEntry("admitted", false)
                    .containsEntry("address", "@porelo.host-b:example.org")
                    .containsEntry("room", "#p:example.org")
                    .containsEntry("ready", false);
        } finally {
            keyring.forget();
        }
    }

    @ParameterizedTest(name = "secrets on the input: {0}")
    @ValueSource(booleans = {false, true})
    void whatATaskSentIsAskedAboutByTheRecipientsAddressAndRecordedReadOnce(boolean onInput)
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                            + "\"lifecycle\":[\"setup\",\"enroll\"]"
                            + input(onInput)
                            + "}");
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering(
                    "--task porelo-p-write",
                    "{\"secrets\":{\"TASK\":\"w-1\"},\"address\":\"@write\"}");
            runner.answering(
                    "--task porelo-p-review",
                    "{\"secrets\":{\"TASK\":\"r-1\"},\"address\":\"@review\"}");
            runner.answering(" receipt ", "{\"state\":\"read\",\"at\":\"2026-09-30T08:00:00Z\"}");
            final TaskConversations tasks = new TaskConversations(context);
            assertThat(tasks.enroll(project("guarded"), "porelo-p-write")).isNull();
            assertThat(tasks.enroll(project("guarded"), "porelo-p-review")).isNull();
            final Mailbox write = mailbox("porelo-p-write");
            Files.writeString(
                    write.sent().resolve("m-1.json"),
                    "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"review\"}}");
            Files.writeString(
                    write.sent().resolve("m-1.json.receipt.json"), "{\"reference\":\"$e1\"}");
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members =
                    List.of(member("porelo-p-write", write, "p", "room"));

            conversations.pass(members);
            conversations.pass(members);

            final org.fuin.porelo.core.process.Command asked = runner.only(" receipt ");
            assertThat(asked.arguments()).endsWith("receipt", "$e1", "--by", "@review");
            handed(asked, onInput, "TASK", "w-1");
            assertThat(Files.readString(write.record().resolve(MessageRecord.FILE)))
                    .contains("\"event\":\"read\"")
                    .contains("\"peer\":\"review\"");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void tasksOfOneProjectEnrolledAtOnceAreEachNamedInItsConversation() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        // Two starts of one project's tasks at once each wrote the addresses as they had read them,
        // and one task's address was lost: its own words in the room then came back to it.
        final int tasks = 8;
        final org.fuin.porelo.core.process.CommandRunner enrolling =
                command -> {
                    final List<String> arguments = command.arguments();
                    if (arguments.contains("describe")) {
                        return new org.fuin.porelo.core.process.CommandResult(
                                command,
                                0,
                                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"]}",
                                "");
                    }
                    if (!arguments.contains("enroll")) {
                        return new org.fuin.porelo.core.process.CommandResult(command, 0, "", "");
                    }
                    try {
                        // As slow as a transport that makes an account, so the starts overlap.
                        Thread.sleep(150);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    final String task = arguments.get(arguments.indexOf("--task") + 1);
                    return new org.fuin.porelo.core.process.CommandResult(
                            command, 0, "{\"address\":\"@" + task + "\"}", "");
                };
        final PoreloContext context = context(enrolling);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        // Read once and held, as the daemon holds it, so the starts meet at the transport and not
        // at the vault's key derivation.
        VaultCache.hold();
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            final TransportLifecycle lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());
            final List<String> containers =
                    java.util.stream.IntStream.range(0, tasks)
                            .mapToObj(n -> "porelo-p-t" + n)
                            .toList();
            final List<Throwable> failed =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            final List<Thread> starts =
                    containers.stream()
                            .map(
                                    container ->
                                            Thread.ofPlatform()
                                                    .start(
                                                            () -> {
                                                                try {
                                                                    lifecycle.enroll(
                                                                            "room", "p", container,
                                                                            Map.of());
                                                                } catch (final Exception ex) {
                                                                    failed.add(ex);
                                                                }
                                                            }))
                            .toList();
            for (final Thread start : starts) {
                start.join(java.time.Duration.ofSeconds(30));
            }

            assertThat(failed).as("every enroll ran").isEmpty();
            assertThat(lifecycle.addresses("room", "p"))
                    .as("each task's address, as its enroll said it")
                    .containsOnlyKeys(containers);
        } finally {
            VaultCache.stop();
            keyring.forget();
        }
    }

    @Test
    void anAddressIsATasksShortOrFullNameOrAPersons() throws IOException {
        final Map<String, String> tasks = Map.of("porelo-p-review", "@review");
        final Map<String, String> people = Map.of("alice", "@alice");
        assertThat(TransportConversations.addressOf(context(), "review", "p", tasks, people))
                .isEqualTo("@review");
        assertThat(
                        TransportConversations.addressOf(
                                context(), "porelo-p-review", "p", tasks, people))
                .isEqualTo("@review");
        assertThat(TransportConversations.addressOf(context(), "alice", "p", tasks, people))
                .isEqualTo("@alice");
        assertThat(TransportConversations.addressOf(context(), "nobody", "p", tasks, people))
                .isNull();
    }

    @Test
    void anOfflineProjectsConversationMustNotReachBeyondLoopback() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "setup",
                    "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                            + "\"reaches\":[\"matrix.example.org\"]}");

            assertThat(new TaskConversations(context).enroll(project("offline"), "porelo-p-review"))
                    .contains("an offline project's messages never leave this machine")
                    .contains("matrix.example.org");
            assertThat(runner.lines())
                    .as("never enrolled")
                    .noneMatch(line -> line.contains(" enroll "));
            assertThat(runner.only(" setup ").arguments())
                    .as("told before it reaches anything")
                    .endsWith("--loopback-only");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void loopbackIsLoopbackWithOrWithoutAPort() {
        assertThat(TaskConversations.loopback("127.0.0.1")).isTrue();
        assertThat(TaskConversations.loopback("127.0.0.1:18008")).isTrue();
        assertThat(TaskConversations.loopback("[::1]:18008")).isTrue();
        assertThat(TaskConversations.loopback("localhost")).isTrue();
        assertThat(TaskConversations.loopback("matrix.example.org")).isFalse();
        assertThat(TaskConversations.loopback("127.0.0.1.example.org")).isFalse();
    }

    @Test
    void aBracketedHostIsLoopbackOnlyWhenNothingButAPortFollowsIt() {
        assertThat(TaskConversations.loopback("[::1]")).as("a bracketed loopback alone").isTrue();
        assertThat(TaskConversations.loopback("[localhost].example.org:8008"))
                .as("a name that only starts with a bracketed loopback is another host")
                .isFalse();
        assertThat(TaskConversations.loopback("[::1]x:8008"))
                .as("anything between the bracket and the port makes it another host")
                .isFalse();
        assertThat(TaskConversations.loopback("[::1]:"))
                .as("a colon with no port after the bracket")
                .isFalse();
        assertThat(TaskConversations.loopback("[::1")).as("a bracket never closed").isFalse();
    }

    @Test
    void aTaskWhoseAccountCannotBeReadHereIsNotSentAsNobody() throws Exception {

        // Its conversation is there, its account is not readable - a locked vault, or a daemon that
        // sees another one: send ran without it, and the message came back held with
        // "PORELO_MATRIX_HOMESERVER is not set".
        final PoreloContext context = context();
        final Path state =
                Files.createDirectories(context.paths().xdg().state().resolve("transport/room"));
        Files.writeString(
                state.resolve("p.json"), "{\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");

        final TransportConversations conversations =
                new TransportConversations(
                        context, context.paths().messaging().transportDirectory());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> conversations.acting("room", "p", "porelo-p-review"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no account");
        // A project with no conversation in it asks for nothing beyond the message, as before.
        assertThat(conversations.acting("room", "other", "porelo-other-review")).isNull();
    }

    @Test
    void clearingAProjectRunsTheTransportsClearAndForgetsWhatPoreloKeptForItThenTheAccountsToo()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        // With them kept, a task started again after the transport had cleared ran no setup and
        // stayed outside a conversation that was gone (measured by porelo-message-matrix,
        // 2026-10-02).
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"clear\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final TransportLifecycle lifecycle =
                    new TransportLifecycle(
                            context, context.paths().messaging().transportDirectory());

            final List<String> removed = lifecycle.clear("room", "p", Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.contains(" clear --project p"));
            assertThat(removed).contains("the room");
            assertThat(lifecycle.secrets("room", "project/p")).isEmpty();
            assertThat(lifecycle.secrets("room", "task/porelo-p-review")).isEmpty();
            assertThat(lifecycle.conversation("room", "p"))
                    .as("set up anew at the next start")
                    .isNull();
            assertThat(lifecycle.secrets("room", "account"))
                    .as("the account outlives one project")
                    .isNotEmpty();

            lifecycle.clear("room", null, Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.endsWith(" clear"));
            assertThat(lifecycle.secrets("room", "account")).isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aWaitingPollAsksForPersonsAndHoldsTheServerOpenAndAPassThenLeavesFetchingToIt()
            throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"waits\":true,"
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("porelo-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();
            final List<TransportConversations.Member> group =
                    List.of(member("porelo-p-review", review, "p", "room"));
            final TransportConversations conversations =
                    new TransportConversations(
                            context, context.paths().messaging().transportDirectory());

            conversations.waitOnce(group);

            assertThat(runner.only(" poll ").arguments())
                    .contains("--persons")
                    .containsSubsequence("--wait", "30");
            TransportConversations.WAITING.add(TransportConversations.key("room", "p"));
            try {
                conversations.pass(group);
                assertThat(
                                runner.invocations().stream()
                                        .filter(command -> command.describe().contains(" poll "))
                                        .count())
                        .as("no second poll beside the waiting one")
                        .isEqualTo(1);
            } finally {
                TransportConversations.WAITING.remove(TransportConversations.key("room", "p"));
            }
        } finally {
            keyring.forget();
        }
    }

    @Test
    void whatAPollSaysOnItsErrorIsSaidEvenWhenItSucceeds() throws Exception {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            emptyVault(context);
            keyring.store(PASSPHRASE);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"poll\":true,"
                            + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering(
                    "setup",
                    "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                            + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" poll ", "", "skipped 1 encrypted message from @alice:localhost");
            final Mailbox review = mailbox("porelo-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "porelo-p-review"))
                    .isNull();

            final TransportConversations.Outcome outcome =
                    new TransportConversations(
                                    context, context.paths().messaging().transportDirectory())
                            .pass(List.of(member("porelo-p-review", review, "p", "room")));

            assertThat(outcome.failures())
                    .anySatisfy(said -> assertThat(said).contains("encrypted message"));
        } finally {
            keyring.forget();
        }
    }

    /** The field of a transport that takes its secrets on its input, or nothing. */
    private static String input(boolean onInput) {
        return onInput ? ",\"secrets\":\"input\"" : "";
    }

    /**
     * Asserts a secret reached the transport the way it takes it: on its input, then never in its
     * environment, where another process of the account reads it - or as before, in its
     * environment.
     */
    private static void handed(
            org.fuin.porelo.core.process.Command command,
            boolean onInput,
            String name,
            String value) {
        if (onInput) {
            assertThat(command.environment())
                    .as("%s kept out of the environment of %s", name, command.describe())
                    .doesNotContainKey(name)
                    .doesNotContainValue(value);
            assertThat(command.input())
                    .as("%s on the input of %s", name, command.describe())
                    .contains("\"secrets\":{")
                    .contains("\"" + name + "\":\"" + value + "\"");
        } else {
            assertThat(command.environment())
                    .as("%s in the environment of %s", name, command.describe())
                    .containsEntry(name, value);
        }
    }

    /** Asserts a secret reached the transport in no way at all. */
    private static void notHanded(org.fuin.porelo.core.process.Command command, String name) {
        assertThat(command.environment()).doesNotContainKey(name);
        assertThat(String.valueOf(command.input())).doesNotContain("\"" + name + "\"");
    }

    // The name's reading: a name no task can carry is shown as nobody, never as a line of its own.
    @Test
    void aNameNoTaskCanCarryIsShownAsNobody() {
        assertThat(TransportLifecycle.agentName("pi\nAdmin")).isNull();
    }

    private static TransportConversations.Member member(
            String container, Mailbox mailbox, String project, String scheme) {
        return member(container, mailbox, project, scheme, null);
    }

    private static TransportConversations.Member member(
            String container,
            Mailbox mailbox,
            String project,
            String scheme,
            @org.jspecify.annotations.Nullable String display) {
        return new TransportConversations.Member(
                container,
                mailbox,
                project,
                scheme,
                display,
                org.fuin.porelo.runtime.ContainerName.taskIn(project, container));
    }

    @Test
    void aFileOfAnArrivedNameThatPoreloDidNotDeliverIsNotMarkedRead() throws IOException {
        // Harm: the container could tell the person's side that a message was read which the agent
        // never had.
        final Mailbox review = mailbox("porelo-p-review");
        Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n");
        Files.writeString(review.inboxCur().resolve("$a.json"), "{}");

        assertThat(TransportConversations.taken(new Mailbox(review.root(), () -> "someone-else")))
                .as("a file the account does not own was not taken from Porelo")
                .isEmpty();
        assertThat(TransportConversations.taken(review))
                .as("the account's own file was")
                .containsExactly("$a.json");
    }
}
