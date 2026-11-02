package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests for {@link InstalledAgent}. */
class InstalledAgentTest {

    @Test
    void twoLookupsAtOnceNeverShareASocket() {

        // Named after the daemon's pid alone, two lookups at once - a task starting while another
        // start is checked - shared one path: the second adapter found the first one's socket live
        // and exited, and the lookup listed no agent at all.
        final Path executable = Path.of("/usr/libexec/porelo/agents/porelo-agent-claude");
        final Path directory = Path.of("/run/user/1000/porelo");

        assertThat(InstalledAgent.socketFor(executable, directory))
                .isNotEqualTo(InstalledAgent.socketFor(executable, directory));
        assertThat(InstalledAgent.socketFor(executable, directory).getParent())
                .isEqualTo(directory);
        assertThat(InstalledAgent.socketFor(executable, directory).getFileName().toString())
                .startsWith("porelo-agent-claude-")
                .endsWith(".sock");
    }

    private static Path adapter(Path dir, String name, String script) throws java.io.IOException {
        final Path file =
                java.nio.file.Files.createDirectories(dir.resolve("agents"))
                        .resolve(AgentDirectory.PREFIX + name);
        java.nio.file.Files.writeString(file, "#!/bin/sh\n" + script + "\n");
        java.nio.file.Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    @org.junit.jupiter.api.Test
    void anAdapterThatSaysHalfALineAndHangsIsGivenUpOnAtTheLimit(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {

        // The line was read whole once anything was ready: an adapter that printed a word with no
        // line end and hung held every task start for ever.
        final Path executable = adapter(dir, "half", "printf starting; sleep 600");
        final java.time.Instant started = java.time.Instant.now();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new InstalledAgent(executable, dir.resolve("run")))
                .isInstanceOf(AgentException.class);
        assertThat(java.time.Duration.between(started, java.time.Instant.now()))
                .isLessThan(java.time.Duration.ofSeconds(InstalledAgent.READY_TIMEOUT_SECONDS + 5));
    }

    @org.junit.jupiter.api.Test
    void anAdapterThatFailsAfterSayingItIsReadyIsOneFailureAmongTheOthers(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {

        // Its connection failed as a VarlinkException, which the list did not catch: one broken
        // adapter threw the whole lookup away and left the ones started before it running.
        adapter(dir, "broken", "printf 'ready now\\n'; sleep 30");

        final InstalledAgents agents =
                new InstalledAgents(
                        new AgentDirectory(java.util.List.of(dir.resolve("agents"))),
                        dir.resolve("run"));

        assertThat(agents.failures()).containsKey(AgentDirectory.PREFIX + "broken");
        assertThat(
                        java.nio.file.Files.list(
                                java.nio.file.Files.createDirectories(dir.resolve("run"))))
                .as("no socket left")
                .isEmpty();
        agents.close();
    }

    @Test
    void theDescriptionIsAwaitedOnAThreadOfItsOwnAndNotOnTheSchedulersOwn() {

        // On a virtual thread, the one step of a lookup that waits on the agent waited on a
        // scheduler too: on a machine of two processors under a suite's load, an agent that
        // answered in a tenth of a second "did not describe itself within 10 seconds".
        final Boolean virtual =
                InstalledAgent.bounded(
                        Path.of("agent"),
                        "describe itself",
                        () -> Thread.currentThread().isVirtual(),
                        5);

        assertThat(virtual).isFalse();
    }

    @Test
    void aDescriptionThatNeverComesLeavesNoThreadBehind() throws Exception {

        // The executor was never closed and a cancel interrupts nothing: every timeout left its
        // thread waiting.
        final java.util.concurrent.CountDownLatch ended =
                new java.util.concurrent.CountDownLatch(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                InstalledAgent.bounded(
                                        Path.of("agent"),
                                        "describe itself",
                                        () -> {
                                            try {
                                                Thread.sleep(60_000);
                                            } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                            } finally {
                                                ended.countDown();
                                            }
                                            return null;
                                        },
                                        1))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("within 1 seconds");

        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the waiting step was ended")
                .isTrue();
    }

    @Test
    void aDescriptionThatDidNotComeSaysWhetherTheAgentStillRunsAndWhatItLastSaid() {

        // "did not describe itself within 10 seconds" was all there was, three times, and never
        // reproduced: whether the agent was alive, and what it had written after it said it was
        // ready, is what tells the cause apart.
        assertThat(InstalledAgent.diagnosis(true, "serving on /run/x.sock\nslow\n"))
                .contains("still running")
                .contains("slow");
        assertThat(InstalledAgent.diagnosis(false, "")).contains("had exited").contains("nothing");
    }

    @Test
    void anAgentBelowTheLowestVersionIsRefusedNamingBothNumbers() {
        final Path executable = Path.of("/usr/libexec/porelo/agents/porelo-agent-old");

        assertThat(InstalledAgent.refusal(executable, AgentProtocol.LOWEST - 1))
                .contains(executable.toString())
                .contains(" " + (AgentProtocol.LOWEST - 1) + ",")
                .contains("at least " + AgentProtocol.LOWEST)
                .contains("Upgrade the agent");
        assertThat(InstalledAgent.refusal(executable, -1)).as("no version at all").isNotNull();
    }

    @Test
    void anAgentAtTheLowestVersionOrNewerIsAccepted() {
        final Path executable = Path.of("/usr/libexec/porelo/agents/porelo-agent-new");

        assertThat(InstalledAgent.refusal(executable, AgentProtocol.LOWEST)).isNull();
        assertThat(InstalledAgent.refusal(executable, AgentProtocol.VERSION)).isNull();
        assertThat(InstalledAgent.refusal(executable, AgentProtocol.VERSION + 1))
                .as("a newer one, since Porelo calls only methods it knows")
                .isNull();
    }

    private static org.fuin.porelo.wire.varlink.VarlinkServer serving(final Path socket) {
        final org.fuin.porelo.wire.varlink.VarlinkServer server =
                new org.fuin.porelo.wire.varlink.VarlinkServer(socket, AgentProtocol.INTERFACE);
        server.method(
                "Known", (parameters, replies) -> replies.last(java.util.Map.of("said", "yes")));
        server.method(
                "Broken",
                (parameters, replies) -> {
                    throw new AgentException("it broke");
                });
        Thread.ofPlatform().daemon().start(server);
        return server;
    }

    @Test
    void aMethodTheAgentHasIsAnswered(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("s.sock");
        try (var server = serving(socket);
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            assertThat(
                            InstalledAgent.optional(
                                    client, AgentProtocol.INTERFACE + ".Known", java.util.Map.of()))
                    .isEqualTo(new InstalledAgent.Answer.Answered(java.util.Map.of("said", "yes")));
        }
    }

    @Test
    void aMethodTheAgentLacksCannotSayAndIsNotNothingFound(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("s.sock");
        final String method = AgentProtocol.INTERFACE + ".NotThere";
        try (var server = serving(socket);
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            assertThat(InstalledAgent.optional(client, method, java.util.Map.of()))
                    .isEqualTo(new InstalledAgent.Answer.CannotSay(method));
        }
    }

    @Test
    void anyOtherErrorIsAnErrorAndNotAnOldAgent(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        final Path socket = dir.resolve("s.sock");
        try (var server = serving(socket);
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    InstalledAgent.optional(
                                            client,
                                            AgentProtocol.INTERFACE + ".Broken",
                                            java.util.Map.of()))
                    .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class);
        }
    }

    /** An agent's server answering what a test gives it; a name not given is a method it lacks. */
    private static org.fuin.porelo.wire.varlink.VarlinkServer agent(
            final Path socket,
            final java.util.Map<String, org.fuin.porelo.wire.varlink.VarlinkServer.Method>
                    methods) {
        final org.fuin.porelo.wire.varlink.VarlinkServer server =
                new org.fuin.porelo.wire.varlink.VarlinkServer(socket, AgentProtocol.INTERFACE);
        methods.forEach(server::method);
        Thread.ofPlatform().daemon().start(server);
        return server;
    }

    private static org.fuin.porelo.wire.varlink.VarlinkServer describing(
            final Path socket, final java.util.Map<String, Object> answer) {
        return agent(
                socket,
                java.util.Map.of("Describe", (parameters, replies) -> replies.last(answer)));
    }

    private static java.util.Map<String, Object> definition() {
        return AgentDefinitionJson.write(
                AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                                name: example
                                binary: example-cli
                                git_identity:
                                  name: Example
                                  email: noreply@example.com
                                headless:
                                  prompt_flag: "-p"
                                """),
                        "test"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 0, 1})
    void theHandshakeAcceptsAnAgentAtTheLowestVersionOrNewer(
            final int aboveLowest, @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // Below the lowest is the one case a start must not get past, tested through the handshake
        // itself: the refusal's words alone would pass with the handshake ignoring them.
        final int version = AgentProtocol.LOWEST + aboveLowest;
        final Path socket = dir.resolve("s.sock");
        try (var server =
                        describing(
                                socket,
                                java.util.Map.of(
                                        "protocolVersion", version, "definition", definition()));
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            if (version < AgentProtocol.LOWEST) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> InstalledAgent.handshake(client, Path.of("/agent")))
                        .as("an agent below the lowest version is refused")
                        .isInstanceOf(AgentException.class)
                        .hasMessageContaining("at least " + AgentProtocol.LOWEST);
            } else {
                assertThat(InstalledAgent.handshake(client, Path.of("/agent")).name())
                        .as("version %d is at or above the lowest", version)
                        .isEqualTo("example");
            }
        }
    }

    @Test
    void anAgentNamingNoVersionIsNotToldToUpgrade(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        // A broken agent read as an old one sends the person to a package that does not help.
        final Path socket = dir.resolve("s.sock");
        try (var server =
                        describing(
                                socket,
                                java.util.Map.of(
                                        "protocolVersion", "3", "definition", definition()));
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> InstalledAgent.handshake(client, Path.of("/agent")))
                    .isInstanceOf(AgentException.class)
                    .hasMessageContaining("no agent protocol version")
                    .hasMessageNotContaining("speaks agent protocol");
        }
    }

    @Test
    void anAgentWithoutFormatShowsItsLinesAsTheyAre(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        final Path socket = dir.resolve("s.sock");
        try (var server = agent(socket, java.util.Map.of());
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            assertThat(InstalledAgent.format(client, java.util.List.of("one", "two")))
                    .containsExactly("one", "two");
        }
    }

    @Test
    void anAgentWithoutEndedSaysNothingAboutItsEnd(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        final Path socket = dir.resolve("s.sock");
        try (var server = agent(socket, java.util.Map.of());
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            assertThat(InstalledAgent.ended(client, "a line")).isNull();
        }
    }

    @Test
    void aFailingFormatOrEndedIsAFailureAndNotAnOldAgent(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        // Both caught every error once, so a broken agent read exactly like an old one.
        final Path socket = dir.resolve("s.sock");
        final org.fuin.porelo.wire.varlink.VarlinkServer.Method broken =
                (parameters, replies) -> {
                    throw new AgentException("it broke");
                };
        try (var server = agent(socket, java.util.Map.of("Format", broken, "Ended", broken));
                var client = new org.fuin.porelo.wire.varlink.VarlinkClient(socket)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> InstalledAgent.format(client, java.util.List.of("one")))
                    .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> InstalledAgent.ended(client, "a line"))
                    .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class);
        }
    }
}
