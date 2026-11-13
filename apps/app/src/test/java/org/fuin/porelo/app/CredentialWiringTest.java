package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/**
 * Tests for what a task is told when it starts without a credential.
 *
 * <p>A launch does not stop for a missing credential - the task comes up and the agent fails to
 * authenticate from inside - so this line is the only warning anybody gets, and it has to name the
 * thing they actually have to do.
 */
class CredentialWiringTest {

    private static final Map<String, VaultEntry> HOLDING =
            Map.of("anthropic", VaultEntry.of("sk-test-value"));

    @Test
    void aLockedVaultSaysSoRatherThanClaimingTheCredentialIsMissing() {

        // The bug this replaced: a locked vault reads as an empty set, so the credential sitting in
        // it was reported absent. That sent somebody to store a credential they already had, and it
        // is the one confusion the interface asked never to be shown.
        assertThat(CredentialWiring.credentialUnavailable(Optional.empty(), "anthropic"))
                .isEqualTo(StartCredentials.State.VAULT_LOCKED);
    }

    @Test
    void anUnlockedVaultWithoutThisCredentialNamesTheOneItWouldNeed() {

        // The name, not the category: 'anthropic is not in the vault' is something somebody can act
        // on, 'a credential is missing' is not.
        assertThat(CredentialWiring.credentialUnavailable(Optional.of(HOLDING), "openai"))
                .isEqualTo(StartCredentials.State.MISSING);
    }

    @Test
    void anUnlockedVaultHoldingItSaysNothing() {

        assertThat(CredentialWiring.credentialUnavailable(Optional.of(HOLDING), "anthropic"))
                .isEqualTo(StartCredentials.State.PRESENT);
    }

    @Test
    void anUnlockedButEmptyVaultReportsTheCredentialAbsentRatherThanTheVaultLocked() {

        // The case the fix turns on. Both are "nothing to authenticate with", and only one of them
        // is fixed by unlocking - so an empty vault must not borrow the locked wording.
        assertThat(CredentialWiring.credentialUnavailable(Optional.of(Map.of()), "anthropic"))
                .isEqualTo(StartCredentials.State.MISSING);
    }

    @Test
    void askingForTheAgentsSessionIsRefusedWhenItsCredentialCannotBeHad() {

        // The defect this pins: only UNATTENDED refused, so 'porelo task start' - whose --attach
        // defaults to 'agent' - warned "the vault is locked" and then built an image, a container
        // and a workspace, landing somebody in an agent that fails on its first request. The
        // refusal for a missing agent two checks later already argues this exact case: a person who
        // asked for an agent must not be handed something that is not one.
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.porelo.wire.TaskMode.AGENT))
                .as("--attach agent asked for the agent itself")
                .isTrue();
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.porelo.wire.TaskMode.UNATTENDED))
                .as("nobody is watching an unattended run")
                .isTrue();
    }

    @Test
    void aShellTaskStartsWithoutOneBecauseThatIsWhatItIsFor() {

        // Not symmetry for its own sake. Working inside the container by hand is exactly what a
        // shell task is for, and it is the fallback the agent refusal points people at - so
        // refusing it too would close the door the message holds open.
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.porelo.wire.TaskMode.SHELL))
                .isFalse();
    }

    @Test
    void namesTheOneCommandThatGetsTheCredential() {
        // On 2026-10-09 a start said only that the vault held nothing for 'github-copilot'.
        final org.fuin.porelo.agent.api.ProviderDefinition copilot =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: github-copilot
                        upstream: https://api.githubcopilot.com
                        dialects:
                          openai: ""
                        grant:
                          kind: oauth-device
                          device_authorization_url: https://github.com/login/device/code
                          token_url: https://github.com/login/oauth/access_token
                        """),
                        "github-copilot.yaml");
        final org.fuin.porelo.agent.api.ProviderDefinition openrouter =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: openrouter
                        upstream: https://openrouter.ai
                        dialects:
                          openai: "/api/v1"
                        """),
                        "openrouter.yaml");

        assertThat(TaskLaunch.nextStep(null, false, copilot, "github-copilot"))
                .isEqualTo("Grant it first with 'porelo vault authorize github-copilot'; ");
        assertThat(TaskLaunch.nextStep("claude", true, null, "anthropic"))
                .isEqualTo("Sign in first with 'porelo vault login claude'; ");
        assertThat(TaskLaunch.nextStep("pi", false, openrouter, "openrouter"))
                .isEqualTo("Store its key first with 'porelo vault put openrouter'; ");
    }

    @Test
    void anAgentThatAddsTheDialectsPathItselfGetsTheBrokersBareAddressInItsBaseUrlVariable() {
        // An agent whose client appends '/v1beta' itself sends '/v1beta/v1beta/...' when the base
        // URL already ends in '/v1beta'; an agent that does not needs the path on the URL.
        final org.fuin.porelo.agent.api.ProviderDefinition google =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: google
                        upstream: https://generativelanguage.googleapis.com
                        dialects:
                          google-generative-ai: "/v1beta"
                        """),
                        "google.yaml");
        final org.fuin.porelo.agent.api.AgentDefinition addsItself =
                org.fuin.porelo.agent.api.AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        provider:
                          default: google
                          dialect: google-generative-ai
                          endpoint: url
                          base_url_env: GOOGLE_GEMINI_BASE_URL
                          adds_path: true
                        """),
                        "adds-path.yaml");
        final org.fuin.porelo.agent.api.AgentDefinition takesPath =
                org.fuin.porelo.agent.api.AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        provider:
                          default: google
                          dialect: google-generative-ai
                          endpoint: url
                          base_url_env: GOOGLE_GEMINI_BASE_URL
                        """),
                        "takes-path.yaml");

        assertThat(
                        CredentialWiring.agentEnvironment(
                                        addsItself,
                                        "GEMINI_API_KEY",
                                        "porelo_pt_test",
                                        org.fuin.porelo.agent.api.ProviderRoute.of(
                                                google, addsItself.provider()))
                                .get("GOOGLE_GEMINI_BASE_URL"))
                .as("adds_path: true gets the broker's bare address in base_url_env")
                .isEqualTo(TaskWiring.VAULT_URL);
        assertThat(
                        CredentialWiring.agentEnvironment(
                                        takesPath,
                                        "GEMINI_API_KEY",
                                        "porelo_pt_test",
                                        org.fuin.porelo.agent.api.ProviderRoute.of(
                                                google, takesPath.provider()))
                                .get("GOOGLE_GEMINI_BASE_URL"))
                .as("without adds_path, base_url_env includes the dialect's path")
                .isEqualTo(TaskWiring.VAULT_URL + "/v1beta");
        assertThat(
                        CredentialWiring.endpointFor(
                                org.fuin.porelo.agent.api.ProviderRoute.of(
                                        google, addsItself.provider()),
                                true,
                                java.util.Map.of()))
                .as("the endpoint the agent's setup is told is the same bare address")
                .isEqualTo(TaskWiring.VAULT_URL);
        assertThat(
                        CredentialWiring.endpointFor(
                                org.fuin.porelo.agent.api.ProviderRoute.of(
                                        google, takesPath.provider()),
                                false,
                                java.util.Map.of()))
                .as("and with the path for an agent that does not add it")
                .isEqualTo(TaskWiring.VAULT_URL + "/v1beta");
    }

    @Test
    void environmentAndEndpointForTakeTheChosenDialectsVariableAndAddsPath() {
        // An agent speaking two dialects gets the variable and adds_path of whichever dialect fits
        // the chosen provider, never the other dialect's variable.
        final org.fuin.porelo.agent.api.ProviderDefinition google =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: google
                        upstream: https://generativelanguage.googleapis.com
                        dialects:
                          google-generative-ai: "/v1beta"
                        """),
                        "google.yaml");
        final org.fuin.porelo.agent.api.ProviderDefinition googleSignIn =
                org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: google-sign-in
                        upstream: https://daily-cloudcode-pa.googleapis.com
                        dialects:
                          cloud-code: "/v1internal"
                        """),
                        "google-sign-in.yaml");
        final org.fuin.porelo.agent.api.AgentDefinition agent =
                org.fuin.porelo.agent.api.AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        provider:
                          default: google
                          dialect: google-generative-ai
                          endpoint: url
                          base_url_env: GEMINI_API_BASE_URL
                          adds_path: false
                          dialects:
                            cloud-code:
                              base_url_env: CLOUDCODE_URL
                              adds_path: true
                        """),
                        "example.yaml");

        final org.fuin.porelo.agent.api.ProviderRoute signInRoute =
                org.fuin.porelo.agent.api.ProviderRoute.of(googleSignIn, agent.provider());
        final java.util.Map<String, String> signInEnv =
                CredentialWiring.agentEnvironment(
                        agent, "GOOGLE_SIGN_IN_TOKEN", "porelo_pt_test", signInRoute);
        assertThat(signInEnv)
                .as(
                        "the chosen secondary dialect's variable carries the bare origin when adds_path is true")
                .containsEntry("CLOUDCODE_URL", TaskWiring.VAULT_URL)
                .doesNotContainKey("GEMINI_API_BASE_URL");
        assertThat(CredentialWiring.endpointFor(signInRoute, signInRoute.addsPath(), signInEnv))
                .as("endpointFor uses the chosen secondary dialect's adds_path")
                .isEqualTo(TaskWiring.VAULT_URL);

        final org.fuin.porelo.agent.api.ProviderRoute googleRoute =
                org.fuin.porelo.agent.api.ProviderRoute.of(google, agent.provider());
        final java.util.Map<String, String> googleEnv =
                CredentialWiring.agentEnvironment(
                        agent, "GEMINI_API_KEY", "porelo_pt_test", googleRoute);
        assertThat(googleEnv)
                .as("the primary dialect's variable carries the path when its adds_path is false")
                .containsEntry("GEMINI_API_BASE_URL", TaskWiring.VAULT_URL + "/v1beta")
                .doesNotContainKey("CLOUDCODE_URL");
        assertThat(CredentialWiring.endpointFor(googleRoute, googleRoute.addsPath(), googleEnv))
                .as("endpointFor uses the primary dialect's adds_path")
                .isEqualTo(TaskWiring.VAULT_URL + "/v1beta");
    }

    @Test
    void aRelayThatNeverRecordsItsPidIsNotUpAndIsNotLeftRunning(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {

        // Taken for up as soon as it was started, a relay that never came up left the agent
        // without an endpoint, the start answering 0, and a process no pid file named.
        relayStarts(dir, false, false);
        relayStarts(dir.resolve("again"), true, true);
    }

    private static void relayStarts(
            final java.nio.file.Path dir, final boolean recordsItsPid, final boolean up)
            throws Exception {
        final org.fuin.porelo.core.config.XdgPaths xdg =
                org.fuin.porelo.core.config.XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final org.fuin.porelo.testing.FakeCommandRunner runner =
                new org.fuin.porelo.testing.FakeCommandRunner().answering("{{.State.Pid}}", "4242");
        final PoreloPaths paths = new PoreloPaths(xdg, dir.resolve("bin"));
        final PoreloContext context = new PoreloContext(runner, paths, arguments -> 0);
        final String container = "porelo-uc-relay";
        final java.nio.file.Path state = paths.tasks().containerState(container);
        java.nio.file.Files.createDirectories(state);
        org.fuin.porelo.wire.Sidecar.recordId(state, "c0ffeec0ffee");
        final java.nio.file.Path helpers = dir.resolve("helpers");
        final java.nio.file.Path standIn = HelperStandIn.write(helpers, recordsItsPid);
        final java.util.List<String> recorded = new java.util.ArrayList<>();
        final CredentialWiring wiring =
                new CredentialWiring(
                        context,
                        new CredentialChoice(context, null, null, null),
                        (name, command, environment, phase) -> recorded.add(name),
                        "relay",
                        8,
                        null);
        // Where the task's namespace would be entered: here the relay runs where it is.
        wiring.namespace = (pid, command) -> command;
        final String before = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, standIn.toString());
        final java.io.StringWriter err = new java.io.StringWriter();
        try {
            final boolean started =
                    wiring.startRelay(
                            new TaskRunner(runner, paths),
                            container,
                            state.resolve("vault.sock"),
                            true,
                            false,
                            new java.io.PrintWriter(new java.io.StringWriter()),
                            new java.io.PrintWriter(err, true));

            assertThat(started).as("whether it came up: %s", err).isEqualTo(up);
            assertThat(HelperStandIn.started(helpers)).as("it was started").isEqualTo(1);
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("running only when it came up")
                    .isEqualTo(up);
            assertThat(recorded).as("recorded for a resume either way").containsExactly("relay");
        } finally {
            if (before == null) {
                System.clearProperty(PoreloBinary.NAMED);
            } else {
                System.setProperty(PoreloBinary.NAMED, before);
            }
            HelperStandIn.stop(helpers);
        }
    }
}
