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
import org.fuin.porelo.wire.varlink.VarlinkException;
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
                .as("the daemon uses the same lifecycle without machine")
                .containsSubsequence("part", "--project", "p", "--person", "alice")
                .doesNotContain("--machine");
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
                "{\"scheme\":\"room\",\"poll\":true,\"takes\":[\"machine\"],\"lifecycle\":[\"setup\",\"join\",\"part\"]}");
        runner.answering(
                " setup ", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"}}");
        runner.answering(" join ", "{\"login\":{\"user\":\"@alice\"}}");
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                "project:\n  name: p\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\nmail:\n  transports:\n    room: {}\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("p"), project + "\n");
        Conversations.join(context, ProjectReader.read(project), "alice", false);
        return context;
    }
}
