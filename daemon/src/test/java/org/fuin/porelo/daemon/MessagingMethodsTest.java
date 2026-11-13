package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.Conversations;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Holds conversation revocation and its distinct refusals at the daemon boundary. */
class MessagingMethodsTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    @ParameterizedTest
    @CsvSource({
        "removed, @alice, false, ''",
        "kept, @alice, true, another room",
        "empty, '', false, ''"
    })
    void removingAMemberReturnsTheTransportsOutcomeAndChangesTheListing(
            String answer, String user, boolean kept, String why) throws Exception {
        // Clients must distinguish a retained account from deactivation and from nothing left.
        final PoreloContext context = context();
        runner.answering(
                " part ",
                answer.equals("empty")
                        ? "{}"
                        : org.fuin.porelo.wire.Json.write(Map.of(answer, user, "why", why)));
        final Map<String, Object> reply =
                MessagingMethods.removeMessageMember(
                        context, Map.of("project", "p", "person", "alice"));

        assertThat(reply)
                .as("the wire carries the complete removal outcome")
                .containsExactlyInAnyOrderEntriesOf(Map.of("user", user, "kept", kept, "why", why));
        assertThat(Conversations.members(context, ProjectReader.read(dir.resolve("project.yml"))))
                .as("the membership used by the daemon listing observes removal")
                .isEmpty();
        assertThat(runner.only(" part ").arguments())
                .as("the daemon uses the same lifecycle, part without the login")
                .containsSubsequence("part", "--project", "p", "--person", "alice")
                .doesNotContain("--login");
    }

    @Test
    void aTalkEventCarriesTheRecordsMarkAndNeverOneTheMessageWrote() {
        // A mark read from the message would be the sender's word; a lying sender leaves it out.
        final Map<String, Object> marked =
                MessagingMethods.talkEvent(
                        "porelo-p-t",
                        Map.of(
                                "at", "2026-11-13T00:00:00Z",
                                "event", "held",
                                "message", "m.json",
                                "mark", "unsigned"));
        final Map<String, Object> unmarked =
                MessagingMethods.talkEvent(
                        "porelo-p-t", Map.of("event", "delivered", "message", "m.json"));

        assertThat(marked).containsEntry("mark", "unsigned").containsEntry("event", "held");
        assertThat(unmarked)
                .as("a line without a verdict says none; the client never guesses one")
                .containsEntry("mark", "")
                .containsKeys("task", "at", "event", "message", "id", "peer", "detail");
    }

    @Test
    void aPersonsRefusalOfAnUnsignedMessageReachesTheLiveStreamWithItsVerdict(
            @TempDir final Path mail) throws Exception {
        // The stream reads each line alone: the refusal's own line must carry what intake found.
        final org.fuin.porelo.app.Mailbox mailbox =
                new org.fuin.porelo.app.Mailbox(mail.resolve("porelo-p-t"));
        mailbox.create();
        Files.writeString(
                mailbox.hold().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\"}");
        new org.fuin.porelo.app.MessageRecord(mailbox)
                .append(
                        org.fuin.porelo.app.MessageRecord.HELD,
                        "m-1.json",
                        "",
                        "",
                        "it was not sent: no key of its own",
                        org.fuin.porelo.app.MessageRecord.OUT,
                        org.fuin.porelo.app.TrustMark.UNSIGNED);

        new org.fuin.porelo.app.MessageRelease().decide(mailbox, "m-1", true);

        final java.util.List<Map<String, Object>> lines =
                new org.fuin.porelo.app.MessageRecord(mailbox).entries();
        assertThat(MessagingMethods.talkEvent("porelo-p-t", lines.getLast()))
                .containsEntry("event", org.fuin.porelo.app.MessageRecord.REFUSED)
                .containsEntry("mark", org.fuin.porelo.app.TrustMark.UNSIGNED);
    }

    @Test
    void aJoinAndTheSetupBeforeItAreGivenThisAccountsLogin() throws Exception {
        // A transport suffixes every account it makes with the login: without it, two persons'
        // tasks of one name would share an account on a shared homeserver.
        final PoreloContext context = context(false);

        final Map<String, Object> joined =
                daemon(
                        context,
                        "JoinMessages",
                        Map.of("project", "p", "person", "alice", "reset", false));

        assertThat(joined.get("login"))
                .as("the daemon answers the transport's login")
                .isEqualTo(Map.of("user", "@alice"));
        for (final String verb : new String[] {" setup ", " join "}) {
            assertThat(runner.only(verb).arguments())
                    .as(verb.strip() + " names the person's login")
                    .containsSubsequence("--login", LOGIN);
        }
    }

    @Test
    void aJoinThatInvitedAnAccountThePersonHasSaysSo() throws Exception {
        // Told to sign in with a password that does not exist, a person asks for a new one and
        // loses their account's sessions; an invitation is accepted in the client they use.
        final PoreloContext context = context(false);
        runner.answering(" join ", "{\"login\":{\"user\":\"@alice\"},\"invited\":true}");

        final Map<String, Object> joined =
                daemon(
                        context,
                        "JoinMessages",
                        Map.of("project", "p", "person", "alice", "reset", false));

        assertThat(joined.get("invited"))
                .as("the transport's invitation reaches the window")
                .isEqualTo(true);
    }

    @Test
    void aJoinThatMadeAnAccountSaysItInvitedNobody() throws Exception {
        // An absent field read as an invitation would hide the password made once.
        final PoreloContext context = context(false);

        final Map<String, Object> joined =
                daemon(
                        context,
                        "JoinMessages",
                        Map.of("project", "p", "person", "alice", "reset", false));

        assertThat(joined.get("invited")).as("a made account is no invitation").isEqualTo(false);
    }

    @Test
    void aSetUpByTheDaemonIsGivenThisAccountsLogin() throws Exception {
        // As a join's: an account set up without the login is shared by two persons' projects.
        final PoreloContext context = context(false);

        daemon(context, "SetUpMessages", Map.of("project", "p"));

        assertThat(runner.only(" setup ").arguments())
                .as("setup names the person's login")
                .containsSubsequence("--login", LOGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SetUpMessages", "JoinMessages", "RemoveMessageMember"})
    void aConversationForDefaultIsRefusedNamingAProjectRepository(final String method)
            throws Exception {
        // Answered NoConversation, 'default' read as a project that only lacks a mail.transports.
        final PoreloContext context = context(false);
        final int before = runner.invocations().size();

        assertThatThrownBy(
                        () ->
                                daemon(
                                        context,
                                        method,
                                        switch (method) {
                                            case "SetUpMessages" -> Map.of("project", "default");
                                            case "JoinMessages" ->
                                                    Map.of(
                                                            "project", "default",
                                                            "person", "alice",
                                                            "reset", false);
                                            default ->
                                                    Map.of("project", "default", "person", "alice");
                                        }))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ConversationRefused");
                            assertThat(((Number) ex.getParameters().get("code")).intValue())
                                    .isEqualTo(78);
                            assertThat(String.valueOf(ex.getParameters().get("message")))
                                    .contains("project repository", "project.yml");
                        });
        assertThat(runner.invocations().subList(before, runner.invocations().size()))
                .as("no transport was run")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Core", "runner_1"})
    void anAccountWhoseLoginNoTransportTakesIsRefusedBeforeAnyTransportRuns(final String login)
            throws Exception {
        // An account made with a login the transport's rule refuses is one nobody can tell apart.
        final PoreloContext context = context(false);
        System.setProperty("user.name", login);

        for (final String method : new String[] {"SetUpMessages", "JoinMessages"}) {
            assertThatThrownBy(
                            () ->
                                    daemon(
                                            context,
                                            method,
                                            "JoinMessages".equals(method)
                                                    ? Map.of(
                                                            "project", "p", "person", "alice",
                                                            "reset", false)
                                                    : Map.of("project", "p")))
                    .as(method + " for login " + login)
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex -> {
                                assertThat(ex.getErrorName())
                                        .as("the named refusal")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".ConversationRefused");
                                assertThat(((Number) ex.getParameters().get("code")).intValue())
                                        .as("a refused login's code")
                                        .isEqualTo(78);
                            });
        }
        assertThat(runner.invocations())
                .as("no transport set up or joined")
                .noneMatch(command -> command.arguments().contains("setup"))
                .noneMatch(command -> command.arguments().contains("join"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice", "--token", "-x", "al_ice"})
    void aPersonNoTransportTakesIsNeitherJoinedNorPartedWithoutAnAccessFile(final String person)
            throws Exception {
        // With no access file nobody is checked against the persons; a person read as an option
        // reached the transport as one.
        final PoreloContext context = context();
        final int before = runner.invocations().size();

        for (final String method : new String[] {"JoinMessages", "RemoveMessageMember"}) {
            if ("RemoveMessageMember".equals(method)) {
                // Listed as joined, as a join before the check could have left it.
                final Path members =
                        context.paths().xdg().state().resolve("transport/room/members/p.json");
                assertThat(members).as("alice's join recorded her").exists();
                Files.writeString(
                        members,
                        org.fuin.porelo.wire.Json.write(
                                Map.of("alice", "@alice", person, "@someone")));
            }
            assertThatThrownBy(
                            () ->
                                    daemon(
                                            context,
                                            method,
                                            "JoinMessages".equals(method)
                                                    ? Map.of(
                                                            "project", "p", "person", person,
                                                            "reset", false)
                                                    : Map.of("project", "p", "person", person)))
                    .as(method + " for '" + person + "'")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(ex.getErrorName())
                                            .as("the named refusal")
                                            .isEqualTo(
                                                    PoreloDaemon.INTERFACE
                                                            + ".ConversationRefused"));
        }
        assertThat(runner.invocations().subList(before, runner.invocations().size()))
                .as("no transport is run for '" + person + "'")
                .noneMatch(command -> command.arguments().contains("--person"))
                .noneMatch(command -> command.arguments().contains("setup"));
    }

    @Test
    void removingAnUnknownMemberReturnsNoSuchMemberWithoutRunningATransport() throws Exception {
        // An unknown name is a distinct outcome clients can act on without parsing sentences.
        final PoreloContext context = context();
        final int before = runner.invocations().size();
        assertThatThrownBy(
                        () ->
                                MessagingMethods.removeMessageMember(
                                        context, Map.of("project", "p", "person", "missing")))
                .as("an unlisted person is NoSuchMember")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("the named refusal")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchMember");
                            assertThat(ex.getParameters())
                                    .as("the name that was refused")
                                    .containsExactlyEntriesOf(Map.of("person", "missing"));
                        });
        assertThat(runner.invocations())
                .as("no transport is run for an unknown member")
                .hasSize(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {75, 77, 78})
    void aRefusedRemovalPreservesMembershipAndReturnsTheTransportsCode(int code) throws Exception {
        // Remote failure must not look successful or revoke only the local half of membership.
        final PoreloContext context = context();
        runner.failing(" part ", code, "cannot remove this account");
        assertThatThrownBy(
                        () ->
                                MessagingMethods.removeMessageMember(
                                        context, Map.of("project", "p", "person", "alice")))
                .as("the transport refusal crosses the wire")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("join and remove share the refusal")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ConversationRefused");
                            assertThat(ex.getParameters().get("code"))
                                    .as("the transport exit code is preserved")
                                    .isEqualTo(code);
                            assertThat(ex.getParameters().get("message").toString())
                                    .as("the transport's own words")
                                    .contains("cannot remove this account");
                        });
        assertThat(Conversations.members(context, ProjectReader.read(dir.resolve("project.yml"))))
                .as("the member remains after a failed part")
                .hasSize(1);
    }

    @Test
    void anAdmissionCrossesTheWireWithItsSecretApartAndABadMachineNameIsARefusal()
            throws Exception {

        // Mixed into the admission, a client would show or log the token with the room's name; a
        // machine's name read as an option, the transport would take it for one.
        final PoreloContext context = context();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"admit\",\"dismiss\","
                        + "\"settings\"]}");
        runner.answering(
                " admit ",
                "{\"admission\":{\"room\":\"!r\"},\"secrets\":{\"registration_token\":\"t-12345678\"}}");

        final Map<String, Object> admitted =
                MessagingMethods.admitMachine(context, Map.of("project", "p", "machine", "host2"));

        assertThat(admitted.get("admission"))
                .as("what the other machine sets, holding no secret")
                .isEqualTo(Map.of("room", "!r"));
        assertThat(admitted.get("secrets"))
                .as("the secret apart")
                .isEqualTo(Map.of("registration_token", "t-12345678"));
        assertThatThrownBy(
                        () ->
                                MessagingMethods.dismissMachine(
                                        context, Map.of("project", "p", "machine", "--help")))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("the named refusal, not a crash")
                                        .isEqualTo(
                                                PoreloDaemon.INTERFACE + ".ConversationRefused"));
    }

    /** The login this account has, whatever the machine running the tests has. */
    private static final String LOGIN = "core-2";

    private @Nullable String machineLogin;

    @BeforeEach
    void login() {
        machineLogin = System.getProperty("user.name");
        System.setProperty("user.name", LOGIN);
    }

    @AfterEach
    void restoreLogin() {
        if (machineLogin == null) {
            System.clearProperty("user.name");
        } else {
            System.setProperty("user.name", machineLogin);
        }
    }

    /** Asks the daemon, over its socket, as a client does. */
    private Map<String, Object> daemon(
            final PoreloContext context, final String method, final Map<String, Object> parameters)
            throws Exception {
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context, socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    private PoreloContext context() throws Exception {
        return context(true);
    }

    private PoreloContext context(final boolean joined) throws Exception {
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
        final char[] passphrase = "fixture passphrase".toCharArray();
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .asking(prompt -> passphrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), passphrase);
        assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter =
                adapters.resolve(org.fuin.porelo.app.TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"takes\":[\"login\"],\"lifecycle\":[\"setup\",\"join\",\"part\"]}");
        runner.answering(
                " setup ", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"}}");
        runner.answering(" join ", "{\"login\":{\"user\":\"@alice\"}}");
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                "project:\n  name: p\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\nmail:\n  transports:\n    room: {}\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("p"), project + "\n");
        if (joined) {
            Conversations.join(context, ProjectReader.read(project), "alice", false);
        }
        return context;
    }
}
