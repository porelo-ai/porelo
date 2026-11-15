package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.ModelLists;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
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
                                        context(), new ModelLists(), Map.of("provider", "nowhere")))
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
                        clock);
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
}
