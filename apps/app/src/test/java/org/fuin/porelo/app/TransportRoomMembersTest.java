package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The transport's room claims cross into Porelo only after they have been judged. */
class TransportRoomMembersTest {
    @TempDir Path dir;
    private FakeCommandRunner runner = new FakeCommandRunner();
    private String previousLogin;
    private static final String TASK = "porelo-p-api";
    private static final String SECRET = "transport-fixture-secret";

    @Test
    void membersCannotUseTheAccountsNetworkBeyondTheSavedReaches() throws Exception {
        // Harm: a live membership read accidentally connects to a host the conversation never
        // named.
        final PoreloContext context = context();
        remember(context, List.of("192.0.2.10:8448"));
        answer(Map.of("room", "!p", "members", List.of(member("person"))));

        read(context, project());

        final Command command = runner.only(" members ");
        assertThat(command.program())
                .as("members goes through the fenced runner")
                .isEqualTo("/usr/bin/pasta");
        assertThat(fenceScript(command))
                .as("only the saved numeric reach and namespace loopback can pass")
                .contains("policy drop;", "oifname \"lo\" accept", "ip daddr 192.0.2.10 accept")
                .doesNotContain("ct state", "dport 53");
    }

    @Test
    void anOfflineMembershipReadCannotTrustAnExternalSavedReach() throws Exception {
        // Harm: stale online state opens the network after a project becomes offline.
        final PoreloContext context = context();
        remember(context, List.of("192.0.2.10"));
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(projectFile, Files.readString(projectFile).replace("guarded", "offline"));
        answer(Map.of("room", "!p", "members", List.of(member("person"))));

        read(context, project());

        final Command command = runner.only(" members ");
        assertThat(command.program())
                .as("offline membership runs through the namespace too")
                .isEqualTo("/usr/bin/pasta");
        assertThat(command.arguments())
                .as("the cooperative offline flag remains")
                .contains("--loopback-only");
        assertThat(fenceScript(command))
                .as("the enforced rule is loopback alone")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain("192.0.2.10", "ip daddr", "ip6 daddr");
    }

    @Test
    void membersBeforeSetupCannotGainNetworkAccessByHavingNoSavedReaches() throws Exception {
        // Harm: absent conversation state is mistaken for unrestricted access.
        final PoreloContext context = context();
        answer(Map.of("room", "", "members", List.of()));

        assertThatThrownBy(() -> read(context, project()))
                .as("the pre-setup answer still means no room")
                .isInstanceOfSatisfying(
                        RoomMembers.Refused.class,
                        ex ->
                                assertThat(ex.failure())
                                        .as("no room was made")
                                        .isEqualTo(RoomMembers.Failure.NO_ROOM));
        final Command command = runner.only(" members ");
        assertThat(command.program())
                .as("missing reaches cannot bypass the namespace")
                .isEqualTo("/usr/bin/pasta");
        assertThat(fenceScript(command))
                .as("a read before setup has only namespace loopback")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain("ip daddr", "ip6 daddr");
    }

    @Test
    void membersCannotFallBackWhenPastaIsMissing() throws Exception {
        // Harm: the production membership entry point catches a missing tool and runs on the host.
        final PoreloContext context = context();

        assertThatThrownBy(
                        () ->
                                RoomMembers.read(
                                        context,
                                        project(),
                                        name -> {
                                            throw new java.io.UncheckedIOException(
                                                    new java.io.IOException(name + " is missing"));
                                        }))
                .as("the caller receives a named refusal and the missing dependency")
                .isInstanceOfSatisfying(
                        RoomMembers.Refused.class,
                        ex -> {
                            assertThat(ex.failure())
                                    .as("the read was refused")
                                    .isEqualTo(RoomMembers.Failure.REFUSED);
                            assertThat(ex.code()).as("it never ran").isEqualTo(77);
                            assertThat(ex.getMessage())
                                    .as("the remedy names the namespace dependency")
                                    .contains("pasta");
                        });
        assertThat(runner.lines())
                .as("no members launch or fallback occurred")
                .noneMatch(line -> line.contains(" members "));
    }

    @Test
    void anUnsupportedSavedNameCannotRunMembersUnfenced() throws Exception {
        // Harm: a hostname unsupported by this slice silently loses the network fence.
        final PoreloContext context = context();
        remember(context, List.of("server.example.org"));

        assertThatThrownBy(() -> read(context, project()))
                .as("names fail closed until resolution is supported")
                .isInstanceOf(RoomMembers.Refused.class)
                .hasMessageContaining("literal");
        assertThat(runner.lines())
                .as("no unfenced members invocation")
                .noneMatch(line -> line.contains(" members "));
    }

    private static String fenceScript(final Command command) {
        return command.arguments().get(command.arguments().indexOf("-c") + 1);
    }

    private static void remember(final PoreloContext context, final List<String> reaches)
            throws java.io.IOException {
        final Path state = context.paths().xdg().state().resolve("transport/room/p.json");
        Files.createDirectories(state.getParent());
        Files.writeString(state, Json.write(Map.of("conversation", "!p", "reaches", reaches)));
    }

    @BeforeEach
    void login() {
        previousLogin = System.getProperty("user.name");
        System.setProperty("user.name", "alice");
    }

    @AfterEach
    void restoreLogin() {
        if (previousLogin == null) System.clearProperty("user.name");
        else System.setProperty("user.name", previousLogin);
    }

    @Test
    void theJudgedAnswerKeepsNamesStatesAndKindsAndHandsSecretsOnlyOnInput() throws Exception {
        // Raw transport fields and host credentials must never become the client's answer.
        final PoreloContext context = context();
        final char[] passphrase = "fixture passphrase".toCharArray();
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "transport/room/account",
                                VaultEntry.of(Json.write(Map.of("ADMIN", SECRET)))),
                        passphrase);
        final PoreloContext opened = context.asking(prompt -> passphrase.clone());
        assertThat(opened.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault without the keyring")
                .isPresent();
        final Map<String, Object> task = member("task");
        task.put("private", "not-for-the-client");
        final List<Map<String, Object>> members =
                List.of(task, member("person"), member("helper"), member("other"));
        answer(Map.of("room", "!p", "members", members, "secrets", Map.of("PRIVATE", "opaque")));

        final RoomMembers result = read(opened, project());

        assertThat(result.asMap())
                .as("only the judged fields cross to the client")
                .isEqualTo(
                        Map.of(
                                "room",
                                "!p",
                                "members",
                                List.of(
                                        Map.of(
                                                "id",
                                                "@task",
                                                "shown",
                                                "Shown task",
                                                "state",
                                                "join",
                                                "kind",
                                                "task",
                                                "task",
                                                TASK),
                                        member("person"),
                                        member("helper"),
                                        member("other"))));
        final var command = runner.only(" members ");
        assertThat(command.arguments())
                .as("members receives the project and account login")
                .containsSubsequence("members", "--project", "p", "--login", "alice");
        assertThat(Json.parse(command.input()))
                .as("the settings and account credential reach stdin")
                .isEqualTo(Map.of("settings", Map.of(), "secrets", Map.of("ADMIN", SECRET)));
        assertThat(command.environment().values())
                .as("the secret is absent from the environment")
                .doesNotContain(SECRET);
        assertThat(command.describe())
                .as("the secret is absent from the arguments")
                .doesNotContain(SECRET);
        assertThat(runner.lines())
                .as("reading never sets up or enrolls a room")
                .noneMatch(line -> line.contains(" setup ") || line.contains(" enroll "));
    }

    @Test
    void anotherProjectsContainerIsRefusedEvenWhenItsNameBeginsWithThisProjects() throws Exception {
        // A shared name prefix must not turn another project's task into this room's task.
        final PoreloContext context = context();
        runner.answering("ps", TASK + "\tUp\t0\t0\tp-api\tguarded\t\t\t" + "a".repeat(64));
        answer(Map.of("room", "!p", "members", List.of(member("task"))));

        refused(context, "task", 76);
    }

    @Test
    void aContainerThatWasNeverRecordedIsRefused() throws Exception {
        // A matching container name and label alone are no proof that Porelo made the task.
        final PoreloContext context = context();
        Files.delete(context.paths().tasks().taskRecord(TASK).resolve(Sidecar.RECORDED_ID));
        answer(Map.of("room", "!p", "members", List.of(member("task"))));

        refused(context, "task", 76);
    }

    @Test
    void onlyThisPersonsTaskMembersAreKeptOnASharedHomeserver() throws Exception {
        // Two people may have identically named containers; the other's account is not ours.
        final PoreloContext context = context();
        final Map<String, Object> foreign = member("task");
        foreign.put("login", "bob");
        foreign.put("id", "@foreign");
        foreign.put("task", "porelo-other-api");
        answer(Map.of("room", "!p", "members", List.of(foreign, member("task"), member("person"))));

        assertThat(read(context, project()).members())
                .as("foreign tasks are omitted")
                .extracting(RoomMembers.Member::id)
                .containsExactly("@task", "@person");
    }

    @Test
    void aSharedServersTaskWithoutLoginIsRefused() throws Exception {
        // Omitting ownership must not let a task bypass the shared-server login check.
        final PoreloContext context = context();
        final Map<String, Object> task = member("task");
        task.remove("login");
        answer(Map.of("room", "!p", "members", List.of(task)));

        refused(context, "login", 76);
    }

    @Test
    void aPrivateServersTaskDoesNotNeedALoginField() throws Exception {
        // Older private transports do not label task accounts with a person's login.
        final PoreloContext context = context();
        describe(false, true, true);
        final Map<String, Object> task = member("task");
        task.remove("login");
        answer(Map.of("room", "!p", "members", List.of(task)));

        assertThat(read(context, project()).members())
                .as("a private task is recognized")
                .extracting(RoomMembers.Member::task)
                .containsExactly(TASK);
    }

    @Test
    void aRoomOfPeopleAndHelpersDoesNotNeedTheContainerRuntime() throws Exception {
        // A broken runtime must not hide a valid room that requires no task ownership checks.
        final PoreloContext context = context();
        runner.failing("ps", 125, "runtime unavailable");
        answer(Map.of("room", "!p", "members", List.of(member("person"), member("helper"))));

        assertThat(read(context, project()).members())
                .as("people can still be listed")
                .extracting(RoomMembers.Member::kind)
                .containsExactly("person", "helper");
        assertThat(runner.invocations())
                .as("no task identity was needed")
                .noneMatch(command -> command.arguments().contains("ps"));
    }

    @Test
    void invocationAndOwnershipUseTheSameTransportDescription() throws Exception {
        // Changing capabilities between reads must not let another login masquerade as our task.
        runner =
                new FakeCommandRunner() {
                    private int descriptions;

                    @Override
                    public CommandResult run(Command command) {
                        final CommandResult result = super.run(command);
                        if (command.arguments().contains("describe") && ++descriptions == 3) {
                            return new CommandResult(
                                    command,
                                    0,
                                    "{\"scheme\":\"room\",\"secrets\":\"input\",\"lifecycle\":[\"members\"]}",
                                    "");
                        }
                        return result;
                    }
                };
        final PoreloContext context = context();
        final Map<String, Object> task = member("task");
        task.put("login", "bob");
        answer(Map.of("room", "!p", "members", List.of(task)));

        assertThat(read(context, project()).members())
                .as("the other person's task is omitted")
                .isEmpty();
        assertThat(runner.only(" members ").arguments())
                .as("the invocation requires this person's login")
                .containsSubsequence("--login", "alice");
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void aMalformedAnswerIsRefusedNamingItsField(Object answer, String field) throws Exception {
        // Guessing missing or unknown fields would misrepresent who has access to the room.
        final PoreloContext context = context();
        answer(answer);

        refused(context, field, 76);
    }

    static Stream<Arguments> invalidAnswers() {
        final Map<String, Object> task = member("task");
        return Stream.of(
                Arguments.of(List.of(), "JSON object"),
                Arguments.of(Map.of("room", "!p"), "members"),
                Arguments.of(Map.of("room", 4, "members", List.of()), "room"),
                Arguments.of(Map.of("room", "", "members", List.of(member("person"))), "room"),
                Arguments.of(Map.of("room", "!p", "members", "none"), "members"),
                Arguments.of(Map.of("room", "!p", "members", List.of("person")), "member"),
                Arguments.of(changed("id", null), "id"),
                Arguments.of(changed("shown", 7), "shown"),
                Arguments.of(changed("state", "leave"), "state"),
                Arguments.of(changed("kind", "administrator"), "kind"),
                Arguments.of(changed("task", "../other"), "task"),
                Arguments.of(changed("task", null), "task"),
                Arguments.of(changed("kind", "other"), "task"),
                Arguments.of(changed("id", "@bad\nname"), "id"),
                Arguments.of(changed("shown", "x".repeat(257)), "shown"),
                Arguments.of(changed("id", "x".repeat(1025)), "id"),
                Arguments.of(Map.of("room", "x".repeat(1025), "members", List.of()), "room"),
                Arguments.of(
                        Map.of("room", "!p", "members", java.util.Collections.nCopies(1025, task)),
                        "members"),
                Arguments.of(Map.of("room", "!p", "members", List.of(task, task)), "duplicate"));
    }

    @Test
    void anOversizedDocumentIsRefusedBeforeParsing() throws Exception {
        // Padding and ignored fields still cost memory and cannot escape the answer limit.
        final PoreloContext context = context();
        runner.answering(" members ", " ".repeat(1024 * 1024 + 1));

        refused(context, "answer", 76);
    }

    @ParameterizedTest
    @ValueSource(strings = {"room", "id", "shown"})
    void anEchoedCredentialIsRefusedWithoutRepeatingIt(String field) throws Exception {
        // A successful transport must not leak a supplied credential through a display field.
        final PoreloContext context = context();
        final char[] passphrase = "fixture passphrase".toCharArray();
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "transport/room/account",
                                VaultEntry.of(Json.write(Map.of("ADMIN", SECRET)))),
                        passphrase);
        final PoreloContext opened = context.asking(prompt -> passphrase.clone());
        assertThat(opened.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("fixture vault opens")
                .isPresent();
        if ("room".equals(field)) answer(Map.of("room", SECRET, "members", List.of()));
        else answer(changed(field, SECRET));

        assertThatThrownBy(() -> read(opened, project()))
                .as("an echoed credential is withheld")
                .isInstanceOf(RoomMembers.Refused.class)
                .hasMessageContaining("credential")
                .hasMessageNotContaining(SECRET);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTransportWithoutTheVerbOrStdinSecretsIsRefusedBeforeMembersRuns(boolean offers)
            throws Exception {
        // Credentials must not be sent to an unsupported verb or exposed in its environment.
        final PoreloContext context = context();
        describe(true, offers, false);

        assertThatThrownBy(() -> read(context, project()))
                .as("missing capability is named")
                .isInstanceOfSatisfying(
                        RoomMembers.Refused.class,
                        ex ->
                                assertThat(ex.failure())
                                        .as("the named failure")
                                        .isEqualTo(
                                                offers
                                                        ? RoomMembers.Failure.REFUSED
                                                        : RoomMembers.Failure.NO_TRANSPORT));
        assertThat(runner.lines())
                .as("members was never run")
                .noneMatch(line -> line.contains(" members "));
    }

    @Test
    void aProjectOfTheOrganizationWithNoHomeserverHasNoRoomAndRunsNoTransport() throws Exception {
        // It started with no conversation: asking for its room says it has none, not a refusal.
        final PoreloContext context = context();
        Files.writeString(
                dir.resolve("project.yml"),
                "project:\n"
                        + "  name: p\n"
                        + "  security_class: guarded\n"
                        + "image:\n"
                        + "  from: ubuntu:24.04\n"
                        + "mail:\n"
                        + "  transports:\n"
                        + "    matrix: {}\n");
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "p",
                                "c0",
                                "https://forge.example/p.git",
                                null,
                                List.of(),
                                null,
                                null,
                                null));

        assertThatThrownBy(() -> read(context, project()))
                .as("no homeserver is no room")
                .isInstanceOfSatisfying(
                        RoomMembers.Refused.class,
                        ex -> {
                            assertThat(ex.failure())
                                    .as("the named failure")
                                    .isEqualTo(RoomMembers.Failure.NO_ROOM);
                            assertThat(ex.code()).as("not a refusal's code").isZero();
                        });
        assertThat(runner.lines())
                .as("no transport was run")
                .noneMatch(line -> line.contains(" members "));
    }

    private static RoomMembers read(final PoreloContext context, final Project project)
            throws RoomMembers.Refused {
        return RoomMembers.read(context, project, name -> Path.of("/usr/bin", name));
    }

    private void refused(PoreloContext context, String field, int code) {
        assertThatThrownBy(() -> read(context, project()))
                .as("the invalid " + field + " is refused")
                .isInstanceOfSatisfying(
                        RoomMembers.Refused.class,
                        ex -> {
                            assertThat(ex.code()).as("malformed transport answer").isEqualTo(code);
                            assertThat(ex.getMessage())
                                    .as("the offending field is named")
                                    .contains(field);
                        });
    }

    private static Map<String, Object> member(String kind) {
        final Map<String, Object> row =
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                "@" + kind,
                                "shown",
                                "Shown " + kind,
                                "state",
                                "person".equals(kind) ? "invite" : "join",
                                "kind",
                                kind));
        if ("task".equals(kind)) {
            row.put("task", TASK);
            row.put("login", "alice");
        }
        return row;
    }

    private static Map<String, Object> changed(String field, Object value) {
        final Map<String, Object> row = member("task");
        if (value == null) row.remove(field);
        else row.put(field, value);
        return Map.of("room", "!p", "members", List.of(row));
    }

    private void answer(Object answer) {
        runner.answering(" members ", Json.write(answer));
    }

    private void describe(boolean login, boolean members, boolean input) {
        runner.answering(
                "describe",
                Json.write(
                        Map.of(
                                "scheme",
                                "room",
                                "takes",
                                login ? List.of("login") : List.of(),
                                "secrets",
                                input ? "input" : "environment",
                                "lifecycle",
                                members ? List.of("members") : List.of())));
    }

    private Project project() {
        return ProjectReader.read(dir.resolve("project.yml"));
    }

    private PoreloContext context() throws Exception {
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
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .recognizingTasks();
        final Path transports = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path executable =
                Files.writeString(transports.resolve(TransportDirectory.PREFIX + "room"), "");
        assertThat(executable.toFile().setExecutable(true))
                .as("fixture transport is executable")
                .isTrue();
        describe(true, true, true);
        Files.writeString(
                dir.resolve("project.yml"),
                "project:\n"
                        + "  name: p\n"
                        + "  security_class: guarded\n"
                        + "image:\n"
                        + "  from: ubuntu:24.04\n"
                        + "mail:\n"
                        + "  transports:\n"
                        + "    room: {}\n");
        Files.createDirectories(context.paths().tasks().taskRecord(TASK));
        Files.writeString(
                context.paths().tasks().taskRecord(TASK).resolve(Sidecar.RECORDED_ID),
                "a".repeat(64));
        runner.answering("ps", TASK + "\tUp\t0\t0\tp\tguarded\t\t\t" + "a".repeat(64));
        return context;
    }
}
