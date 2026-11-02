package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the agent protocol by running both halves against each other over a real socket.
 *
 * <p>Not by calling the server's methods directly: the wire is where the two sides can disagree,
 * and a test that skips it would pass while a released Porelo and a released agent failed to talk.
 */
class AgentProtocolTest {

    private static final String DEFINITION =
            """
            name: example
            label: Example
            binary: example-cli
            git_identity:
              name: Example
              email: noreply@example.com
            headless:
              prompt_flag: "-p"
              model_flag: "--model"
            session:
              supports_resume: true
              resume_flag: "--resume"
            provider:
              token_env:
                oauth: EXAMPLE_OAUTH
                _default: EXAMPLE_KEY
            allowed_domains:
              - api.example.com
            """;

    private static Agent agent() {
        final AgentDefinition definition =
                AgentDefinitionReader.read(new StringReader(DEFINITION), "test.yaml");
        return new Agent() {
            @Override
            public AgentDefinition definition() {
                return definition;
            }

            @Override
            public CredentialExtractor credentialExtractor() {
                return new EnvFileExtractor(".env", "EXAMPLE_KEY", "api-key");
            }

            @Override
            public LogFormatter logFormatter() {
                return line -> line.startsWith("noise") ? null : line.toUpperCase();
            }
        };
    }

    private static Agent holding(final CredentialExtractor extractor) {
        final Agent plain = agent();
        return new Agent() {
            @Override
            public AgentDefinition definition() {
                return plain.definition();
            }

            @Override
            public CredentialExtractor credentialExtractor() {
                return extractor;
            }
        };
    }

    private static java.util.Optional<Credential> asked(
            final Agent agent, final Path dir, final @Nullable String provider) throws Exception {
        try (AgentServer server = new AgentServer(agent, dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);
            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                return InstalledAgent.credentialOf(
                        client.call(
                                AgentProtocol.EXTRACT_CREDENTIAL,
                                InstalledAgent.extractParameters(dir, provider)));
            }
        }
    }

    @Test
    void theProviderAskedForReachesTheExtractorAndTheAnswerNamesIt(@TempDir Path dir)
            throws Exception {

        final Agent agent =
                holding(
                        new CredentialExtractor() {
                            @Override
                            public java.util.Optional<Credential> extract(Path directory) {
                                throw new AssertionError("the one asking for a provider was lost");
                            }

                            @Override
                            public java.util.Optional<Credential> extract(
                                    Path directory, @Nullable String provider) {
                                return java.util.Optional.of(
                                        Credential.of("api-key", "value of " + provider)
                                                .forProvider(String.valueOf(provider)));
                            }
                        });

        final Credential answer = asked(agent, dir, "second").orElseThrow();

        assertThat(answer.provider()).as("a store holding several says which").isEqualTo("second");
        assertThat(answer.secret()).as("that one's value").isEqualTo("value of second");
    }

    @Test
    void anExtractorWrittenBeforeTheParameterIgnoresItAndNamesNone(@TempDir Path dir)
            throws Exception {

        final Agent agent =
                holding(directory -> java.util.Optional.of(Credential.of("api-key", "value")));

        final Credential answer = asked(agent, dir, "second").orElseThrow();

        assertThat(answer.provider())
                .as("Porelo refuses this answer because it names none, so none may be made up")
                .isNull();
    }

    @Test
    void noProviderIsSentWhenNoneIsAsked(@TempDir Path dir) {

        assertThat(InstalledAgent.extractParameters(dir, null))
                .as("an agent's own parameter check must see the call it has always seen")
                .containsOnlyKeys("configDirectory");
    }

    @Test
    void aDefinitionSurvivesTheWire(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            final AgentDefinition sent = agent().definition();
            final AgentDefinition received =
                    AgentDefinitionJson.read(AgentDefinitionJson.write(sent));

            // Every field, not just the ones a caller happens to read first: a field lost in
            // translation is behavior nobody declared.
            assertThat(received).isEqualTo(sent);
        }
    }

    @Test
    void anArtifactsDigestAndLicenseSurviveTheWire() {
        final AgentDefinition pinned =
                AgentDefinitionReader.read(
                        new StringReader(
                                """
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                install:
                  version: "2.1.267"
                  artifacts:
                    - url: https://downloads.example/cli/2.1.267/cli
                      sha256: "%s"
                      license: "SEE LICENSE IN README.md"
                      target: /usr/local/bin/example-cli
                """
                                        .formatted("a".repeat(64))),
                        "t.yaml");

        final AgentDefinition round = AgentDefinitionJson.read(AgentDefinitionJson.write(pinned));

        assertThat(round.artifacts())
                .singleElement()
                .satisfies(
                        artifact -> {
                            assertThat(artifact.license()).isEqualTo("SEE LICENSE IN README.md");
                            assertThat(artifact.sha256()).isEqualTo("a".repeat(64));
                        });
        assertThat(round).isEqualTo(pinned);
    }

    @Test
    void anArtifactWithoutALicenseSurvivesWithoutOne() {
        final AgentDefinition pinned =
                AgentDefinitionReader.read(
                        new StringReader(
                                """
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://downloads.example/cli
                      sha256: "%s"
                      target: /usr/local/bin/example-cli
                """
                                        .formatted("b".repeat(64))),
                        "t.yaml");

        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(pinned)).artifacts())
                .singleElement()
                .extracting(InstallArtifact::license)
                .isNull();
    }

    @Test
    void nullFieldsSurviveAsNull() {

        final AgentDefinition minimal =
                AgentDefinitionReader.read(
                        new StringReader(
                                """
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                """),
                        "t.yaml");

        final AgentDefinition round = AgentDefinitionJson.read(AgentDefinitionJson.write(minimal));

        assertThat(round.headless().modelFlag()).isNull();
        assertThat(round.resumeFlag()).isNull();
        assertThat(round.provider()).isNull();
        assertThat(round).isEqualTo(minimal);
    }

    @Test
    void describeCarriesTheProtocolVersion(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer = client.call(AgentProtocol.DESCRIBE, Map.of());

                assertThat(((Number) answer.get("protocolVersion")).intValue())
                        .isEqualTo(AgentProtocol.VERSION);
            }
        }
    }

    @Test
    void buildsACommandAcrossTheWire(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Object command =
                        client.call(
                                        AgentProtocol.BUILD_COMMAND,
                                        Map.of("prompt", "go", "model", "big"))
                                .get("command");

                assertThat((List<?>) command)
                        .map(String::valueOf)
                        .containsExactly("example-cli", "--model", "big", "-p", "go");
            }
        }
    }

    /** An adapter that answers with what it was told, as the command it would run. */
    private static Agent echoingSubagents() {
        final AgentDefinition definition =
                AgentDefinitionReader.read(new StringReader(DEFINITION), "test.yaml");
        return new Agent() {
            @Override
            public AgentDefinition definition() {
                return definition;
            }

            @Override
            public List<String> headlessCommand(RunRequest request) {
                return List.of(request.prompt(), "subagents=" + request.subagents());
            }

            @Override
            public ContainerSetup containerSetup() {
                return context ->
                        List.of(
                                new ContainerFile(
                                        "/subagents", String.valueOf(context.subagents()), false));
            }
        };
    }

    @Test
    void aRunThatSaysNothingOfSubagentsReachesTheAdapterAsNone(@TempDir Path dir) throws Exception {

        // The default is none, and it has to arrive as a number: an adapter told nothing would
        // leave its agent's own default in place, which is to start as many as it likes.
        try (AgentServer server = new AgentServer(echoingSubagents(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> plain = InstalledAgent.parameters(RunRequest.of("go"));
                assertThat(plain)
                        .as("the client sends none as a number")
                        .containsEntry("subagents", 0);
                assertThat((List<?>) client.call(AgentProtocol.BUILD_COMMAND, plain).get("command"))
                        .as("the adapter is told none")
                        .map(String::valueOf)
                        .containsExactly("go", "subagents=0");
                assertThat(
                                (List<?>)
                                        client.call(
                                                        AgentProtocol.BUILD_COMMAND,
                                                        InstalledAgent.parameters(
                                                                new RunRequest(
                                                                        "go", null, null, null,
                                                                        false, false, 3)))
                                                .get("command"))
                        .as("the adapter is told the task's number")
                        .map(String::valueOf)
                        .containsExactly("go", "subagents=3");
                assertThat(
                                (List<?>)
                                        client.call(
                                                        AgentProtocol.BUILD_COMMAND,
                                                        Map.of("prompt", "go"))
                                                .get("command"))
                        .as("an older Porelo that sends nothing is read as none")
                        .map(String::valueOf)
                        .containsExactly("go", "subagents=0");
            }
        }
    }

    @Test
    void anAttendedTasksSetupTellsTheAdapterItsSubagents(@TempDir Path dir) throws Exception {

        // An attended agent is started without a run request, so its setup is where an adapter
        // hears the number, and none when the task says nothing.
        try (AgentServer server = new AgentServer(echoingSubagents(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                for (final var told :
                        Map.of(
                                        new SetupContext("t", "api-key", "/workspace", "", "p"),
                                        "0",
                                        new SetupContext("t", "api-key", "/workspace", "", "p", 2),
                                        "2")
                                .entrySet()) {
                    assertThat(
                                    (List<?>)
                                            client.call(
                                                            AgentProtocol.CONTAINER_SETUP,
                                                            told.getKey().parameters())
                                                    .get("files"))
                            .as("the adapter is told %s", told.getValue())
                            .singleElement()
                            .extracting(file -> ((Map<?, ?>) file).get("content"))
                            .isEqualTo(told.getValue());
                }
            }
        }
    }

    @Test
    void aNumberOfSubagentsThatIsNoWholeNumberFromNoneUpIsRefusedNotRounded(@TempDir Path dir)
            throws Exception {

        // Rounded or dropped, the adapter would bound its agent to a number nobody asked for.
        try (AgentServer server = new AgentServer(echoingSubagents(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                for (final Object given : List.<Object>of(-0.5, 2.7, 1e11, -1, "2", true)) {
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    AgentProtocol.BUILD_COMMAND,
                                                    Map.of("prompt", "go", "subagents", given)))
                            .as("a run asking %s is refused", given)
                            .hasMessageContaining("subagents takes a whole number from 0");
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    AgentProtocol.CONTAINER_SETUP,
                                                    Map.of(
                                                            "token",
                                                            "t",
                                                            "credentialType",
                                                            "api-key",
                                                            "workspace",
                                                            "/workspace",
                                                            "subagents",
                                                            given)))
                            .as("a setup asking %s is refused", given)
                            .hasMessageContaining("subagents takes a whole number from 0");
                }
                assertThat(
                                (List<?>)
                                        client.call(
                                                        AgentProtocol.BUILD_COMMAND,
                                                        Map.of("prompt", "go", "subagents", 4))
                                                .get("command"))
                        .as("a whole number is taken as it is")
                        .map(String::valueOf)
                        .containsExactly("go", "subagents=4");
            }
        }
    }

    @Test
    void theClientSendsEveryFieldOfARunRequest() {

        // As for the setup context: a field added to the request and never sent is an adapter that
        // is quietly told nothing.
        final RunRequest request = new RunRequest("go", "big", 5, "abc", true, true, 2);

        assertThat(InstalledAgent.parameters(request).keySet())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(RunRequest.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName)
                                .toList());
    }

    @Test
    void aNegativeNumberOfSubagentsIsNoRequest() {
        assertThatThrownBy(() -> new RunRequest("go", null, null, null, false, false, -1))
                .as("below none is a mistake, never a bound")
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new SetupContext("t", "api-key", "/workspace", "", "p", -1))
                .as("below none is a mistake in a setup too")
                .isInstanceOf(AgentException.class);
    }

    @Test
    void theClientSendsEveryFieldOfTheSetupContext() {

        // The failure this guards against is silent on both sides: a field added to the record,
        // read by the agent, never sent by the client. Comparing against the record's own
        // components means a new field breaks this test rather than a task.
        final SetupContext context =
                new SetupContext(
                        "porelo_pt_x",
                        "api-key",
                        "/workspace",
                        "http://127.0.0.1:9419/api/v1",
                        "openrouter");

        assertThat(context.parameters().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(SetupContext.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName)
                                .toList());
        assertThat(context.parameters().values()).doesNotContainNull();
    }

    @Test
    void everyPartOfTheSetupContextSurvivesTheWire(@TempDir Path dir) throws Exception {

        // A regression test with a cause: the provider name was added to the context, the agent
        // read it, and the client never sent it - so a task got registerProvider("") and failed to
        // authenticate. Nothing below the wire could see that, because both sides compiled.
        final Agent echoing =
                new Agent() {
                    @Override
                    public AgentDefinition definition() {
                        return AgentDefinitionReader.read(
                                new StringReader(DEFINITION), "test.yaml");
                    }

                    @Override
                    public ContainerSetup containerSetup() {
                        return context ->
                                List.of(
                                        new ContainerFile(
                                                "/echo",
                                                String.join(
                                                        "|",
                                                        context.token(),
                                                        context.credentialType(),
                                                        context.workspace(),
                                                        context.endpoint(),
                                                        context.provider()),
                                                false));
                    }
                };

        try (AgentServer server = new AgentServer(echoing, dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Object files =
                        client.call(
                                        AgentProtocol.CONTAINER_SETUP,
                                        Map.of(
                                                "token",
                                                "porelo_pt_x",
                                                "credentialType",
                                                "api-key",
                                                "workspace",
                                                "/workspace",
                                                "endpoint",
                                                "http://127.0.0.1:9419/api/v1",
                                                "provider",
                                                "openrouter"))
                                .get("files");

                assertThat((List<?>) files)
                        .singleElement()
                        .extracting(f -> ((Map<?, ?>) f).get("content"))
                        .isEqualTo(
                                "porelo_pt_x|api-key|/workspace|"
                                        + "http://127.0.0.1:9419/api/v1|openrouter");
            }
        }
    }

    @Test
    void extractsACredentialAcrossTheWire(@TempDir Path dir) throws Exception {

        java.nio.file.Files.writeString(dir.resolve(".env"), "EXAMPLE_KEY=\"sk-example\"\n");

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer =
                        client.call(
                                AgentProtocol.EXTRACT_CREDENTIAL,
                                Map.of("configDirectory", dir.toString()));

                assertThat(answer.get("found")).isEqualTo(Boolean.TRUE);
                assertThat(answer.get("secret")).isEqualTo("sk-example");
                assertThat(answer.get("type")).isEqualTo("api-key");
            }
        }
    }

    @Test
    void reportsNotLoggedInWithoutInventingACredential(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer =
                        client.call(
                                AgentProtocol.EXTRACT_CREDENTIAL,
                                Map.of("configDirectory", dir.resolve("nowhere").toString()));

                assertThat(answer.get("found")).isEqualTo(Boolean.FALSE);
                assertThat(answer).doesNotContainKey("secret");
            }
        }
    }

    @Test
    void streamsFormattedLogLinesAndDropsTheOnesTheAgentSuppresses(@TempDir Path dir)
            throws Exception {

        java.nio.file.Files.writeString(dir.resolve("raw.log"), "one\nnoise here\ntwo\nthree\n");

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            final List<String> lines = new java.util.ArrayList<>();
            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                client.callMore(
                        AgentProtocol.FORMAT_LOG,
                        Map.of("source", dir.resolve("raw.log").toString()),
                        reply -> {
                            lines.add(String.valueOf(reply.get("line")));
                            return true;
                        });
            }
            assertThat(lines).containsExactly("ONE", "TWO", "THREE");
        }
    }

    @Test
    void refusesACallItCannotHonour(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.porelo.wire.varlink.VarlinkClient(server.socketPath())) {
                assertThatThrownBy(() -> client.call(AgentProtocol.BUILD_COMMAND, Map.of()))
                        .hasMessageContaining("needs a prompt");
            }
        }
    }
}
