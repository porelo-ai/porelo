package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.agent.api.ProviderDefinitionReader;
import org.fuin.porelo.app.ModelLists;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A window's way to the models a provider offers this machine. */
class ModelsMethodTest {

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    @Test
    void aProviderTheMachineDoesNotDeclareIsModelsFailedNamingIt() {
        assertThatThrownBy(
                        () ->
                                AgentMethods.models(
                                        context(),
                                        new ModelLists(LimitFactors.DEFAULT),
                                        Map.of("provider", "nowhere")))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure -> {
                            assertThat(failure.getErrorName())
                                    .as("the contract's named error, never an empty list")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ModelsFailed");
                            assertThat(failure.getParameters())
                                    .as("with the provider asked for")
                                    .containsEntry("provider", "nowhere");
                            assertThat(String.valueOf(failure.getParameters().get("message")))
                                    .as("and where the known ones are listed")
                                    .contains("porelo providers");
                        });
    }

    /** A clock the test moves. */
    private static final class Hands extends java.time.Clock {

        private volatile java.time.Instant now = java.time.Instant.parse("2026-11-15T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    @Test
    void aFreshAskFromATasksSocketIsAnsweredFromWhatWasKept() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger asked =
                new java.util.concurrent.atomic.AtomicInteger();
        final Hands clock = new Hands();
        final ModelLists models =
                new ModelLists(
                        (uri, headers, timeout) -> {
                            asked.incrementAndGet();
                            return new ModelLists.Answer(200, "{\"data\":[{\"id\":\"m\"}]}");
                        },
                        clock,
                        LimitFactors.DEFAULT);
        final org.fuin.porelo.agent.api.ProviderDefinition provider =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                                name: listing
                                upstream: https://listing.example
                                dialects:
                                  openai: /v1
                                auth_header:
                                  _default: Authorization
                                auth_prefix:
                                  _default: "Bearer "
                                models:
                                  path: /v1/models
                                  format: data
                                """),
                        "test.yaml");
        final Map<String, org.fuin.porelo.agent.api.ProviderDefinition> providers =
                Map.of("listing", provider);
        final java.util.function.Supplier<
                        java.util.Optional<Map<String, org.fuin.porelo.vault.VaultEntry>>>
                credentials =
                        () ->
                                java.util.Optional.of(
                                        Map.of(
                                                "listing",
                                                new org.fuin.porelo.vault.VaultEntry(
                                                        "key", "api-key")));
        final Map<String, Object> fresh = Map.of("provider", "listing", "fresh", true);

        AgentMethods.models(providers, credentials, models, Map.of("provider", "listing"));
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(5));
        McpTaskSockets.as(
                "porelo-uc-lead", () -> AgentMethods.models(providers, credentials, models, fresh));

        assertThat(asked).as("a task's fresh ask takes what was kept").hasValue(1);

        AgentMethods.models(providers, credentials, models, fresh);

        assertThat(asked).as("the person's fresh ask reads again").hasValue(2);
    }

    private static AgentDefinition agent(String id) {
        return AgentDefinitionReader.read(
                new StringReader(
                        """
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: ""
                models:
                  listing:
                    - {id: %s, name: Large Model}
                    - {id: small, name: Small Model}
                """
                                .formatted(id)),
                "agent.yaml");
    }

    private static ProviderDefinition provider(String name, boolean lists) {
        return ProviderDefinitionReader.read(
                new StringReader(
                        """
                name: %s
                upstream: https://listing.example
                dialects:
                  openai: /v1
                auth_header:
                  _default: Authorization
                auth_prefix:
                  _default: "Bearer "
                """
                                        .formatted(name)
                                + (lists
                                        ? """
                models:
                  path: /v1/models
                  format: data
                """
                                        : "")),
                "provider.yaml");
    }

    private static Supplier<Optional<Map<String, VaultEntry>>> stored() {
        return () -> Optional.of(Map.of("listing", new VaultEntry("fixture", "api-key")));
    }

    private static ModelLists noAsk() {
        return new ModelLists(
                (uri, headers, timeout) -> {
                    throw new AssertionError("No provider request is possible here");
                },
                new Hands(),
                LimitFactors.DEFAULT);
    }

    private static Map<String, Object> reply(
            ProviderDefinition provider,
            Supplier<Optional<Map<String, VaultEntry>>> credentials,
            ModelLists lists) {
        return AgentMethods.models(
                Map.of(provider.name(), provider),
                credentials,
                lists,
                Map.of("provider", provider.name(), "agent", "example"),
                name -> agent("large"));
    }

    private static void isAgentList(Map<String, Object> reply) {
        assertThat(reply)
                .as("a person's model choices come from this agent")
                .containsEntry("from", "agent")
                .containsEntry(
                        "models",
                        List.of(
                                Map.of("id", "large", "label", "Large Model"),
                                Map.of("id", "small", "label", "Small Model")));
        assertThat(reply.get("readAt"))
                .as("the contract carries a read time")
                .isInstanceOf(String.class);
    }

    @Test
    void noStoredProviderCredentialAnswersTheAgentsModelsWithoutEfforts() {
        isAgentList(reply(provider("listing", true), () -> Optional.of(Map.of()), noAsk()));
    }

    @Test
    void aProviderWithNoListingAnswersTheAgentWithoutReadingTheVault() {
        isAgentList(
                reply(
                        provider("listing", false),
                        () -> {
                            throw new AssertionError("An agent's static list needs no vault");
                        },
                        noAsk()));
    }

    @Test
    void aShutVaultStillAnswersTheAgentsModels() {
        isAgentList(reply(provider("listing", true), Optional::empty, noAsk()));
    }

    @Test
    void aFailedAskAndItsKeptFailureAnswerTheAgent() {
        final AtomicInteger asked = new AtomicInteger();
        final ModelLists lists =
                new ModelLists(
                        (uri, headers, timeout) -> {
                            asked.incrementAndGet();
                            return new ModelLists.Answer(403, "refused");
                        },
                        new Hands(),
                        LimitFactors.DEFAULT);

        isAgentList(reply(provider("listing", true), stored(), lists));
        isAgentList(reply(provider("listing", true), stored(), lists));
        assertThat(asked).as("fallback does not bypass provider spacing").hasValue(1);
    }

    @Test
    void aProvidersListWinsAndItsKeptListWinsWithAShutVault() {
        final ModelLists lists =
                new ModelLists(
                        (uri, headers, timeout) ->
                                new ModelLists.Answer(
                                        200,
                                        "{\"data\":[{\"id\":\"remote\",\"name\":\"Remote\"}]}"),
                        new Hands(),
                        LimitFactors.DEFAULT);

        final Map<String, Object> expected = reply(provider("listing", true), stored(), lists);
        assertThat(expected)
                .as("provider model choices take precedence")
                .containsEntry("from", "provider")
                .containsEntry("models", List.of(Map.of("id", "remote", "label", "Remote")));
        assertThat(
                        reply(
                                provider("listing", true),
                                () -> {
                                    throw new AssertionError("A kept provider list needs no vault");
                                },
                                lists))
                .as("locking does not replace a kept provider list")
                .isEqualTo(expected);
    }

    @Test
    void anEmptyProviderListStillWins() {
        final ModelLists lists =
                new ModelLists(
                        (uri, headers, timeout) -> new ModelLists.Answer(200, "{\"data\":[]}"),
                        new Hands(),
                        LimitFactors.DEFAULT);
        assertThat(reply(provider("listing", true), stored(), lists))
                .as("empty is a provider answer, not an absent listing")
                .containsEntry("from", "provider")
                .containsEntry("models", List.of());
    }

    @Test
    void anotherProviderDoesNotOfferThisAgentsModels() {
        assertThat(reply(provider("other", false), () -> Optional.of(Map.of()), noAsk()))
                .as("a model id belongs to its declared provider")
                .containsEntry("from", "none")
                .containsEntry("models", List.of());
        assertThatThrownBy(() -> reply(provider("other", true), Optional::empty, noAsk()))
                .as("a failure without a matching agent list stays a failure")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure ->
                                assertThat(failure.getErrorName())
                                        .as("the contract's failure")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".ModelsFailed"));
    }

    @Test
    void anUnknownAgentIsInvalidBeforeAnyCredentialIsRead() {
        assertThatThrownBy(
                        () ->
                                AgentMethods.models(
                                        Map.of("listing", provider("listing", true)),
                                        () -> {
                                            throw new AssertionError(
                                                    "An unknown agent cannot read credentials");
                                        },
                                        noAsk(),
                                        Map.of("provider", "listing", "agent", "absent"),
                                        name -> null))
                .as("a misspelled agent must not silently choose provider models")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure -> {
                            assertThat(failure.getErrorName())
                                    .as("the invalid parameter error")
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(failure.getParameters())
                                    .as("names the rejected parameter")
                                    .containsEntry("parameter", "agent");
                        });
    }

    @Test
    void anUnknownAgentFromTheMachinesDirectoryIsInvalid() {
        assertThatThrownBy(
                        () ->
                                AgentMethods.models(
                                        context(),
                                        noAsk(),
                                        Map.of("provider", "listing", "agent", "absent")))
                .as("the machine path resolves the supplied agent too")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure ->
                                assertThat(failure.getErrorName())
                                        .as("unknown agent is InvalidParameter")
                                        .isEqualTo("org.varlink.service.InvalidParameter"));
    }

    @Test
    void anUnknownProviderDoesNotUseAnAgentsDeclaration() {
        assertThatThrownBy(
                        () ->
                                AgentMethods.models(
                                        Map.of(),
                                        stored(),
                                        noAsk(),
                                        Map.of("provider", "listing", "agent", "example"),
                                        name -> agent("large")))
                .as("an undeclared provider is still refused")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure ->
                                assertThat(failure.getErrorName())
                                        .as("the provider error")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".ModelsFailed"));
    }

    @Test
    void anOmittedAgentKeepsTheProviderOnlyAnswer() {
        assertThat(
                        AgentMethods.models(
                                Map.of("listing", provider("listing", false)),
                                () -> {
                                    throw new AssertionError("No listing needs no vault");
                                },
                                noAsk(),
                                Map.of("provider", "listing")))
                .as("the agent parameter remains optional")
                .containsEntry("from", "none")
                .containsEntry("models", List.of());
    }

    @Test
    void agentListsAreNeitherSharedBetweenAgentsNorKeptInsteadOfAProvider() {
        final Hands clock = new Hands();
        final AtomicInteger status = new AtomicInteger(403);
        final ModelLists lists =
                new ModelLists(
                        (uri, headers, timeout) ->
                                new ModelLists.Answer(
                                        status.get(), "{\"data\":[{\"id\":\"remote\"}]}"),
                        clock,
                        LimitFactors.DEFAULT);
        final Map<String, ProviderDefinition> providers =
                Map.of("listing", provider("listing", true));
        final var first =
                AgentMethods.models(
                        providers,
                        stored(),
                        lists,
                        Map.of("provider", "listing", "agent", "first"),
                        name -> agent(name));
        final var second =
                AgentMethods.models(
                        providers,
                        stored(),
                        lists,
                        Map.of("provider", "listing", "agent", "second"),
                        name -> agent(name));
        assertThat(first.get("models"))
                .as("the first agent keeps its own model ids")
                .isEqualTo(
                        List.of(
                                Map.of("id", "first", "label", "Large Model"),
                                Map.of("id", "small", "label", "Small Model")));
        assertThat(second.get("models"))
                .as("a kept provider failure uses the second agent's list")
                .isEqualTo(
                        List.of(
                                Map.of("id", "second", "label", "Large Model"),
                                Map.of("id", "small", "label", "Small Model")));
        status.set(200);
        clock.now = clock.now.plus(ModelLists.SPACING);
        assertThat(reply(provider("listing", true), stored(), lists))
                .as("a later provider answer replaces the fallback immediately")
                .containsEntry("from", "provider");
    }
}
