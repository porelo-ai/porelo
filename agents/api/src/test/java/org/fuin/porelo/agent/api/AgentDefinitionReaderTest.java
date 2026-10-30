package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link AgentDefinitionReader}. */
class AgentDefinitionReaderTest {

    private static final String MINIMAL =
            """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void refusesAnUnknownLoginFlow() {
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                login:
                  flow: invalid
                """))
                .as("an unknown flow must not silently bypass the sign-in policy")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("login.flow");
    }

    @ParameterizedTest
    @CsvSource({"paste-code, PASTE_CODE", "device-code, DEVICE_CODE", "loopback, LOOPBACK"})
    void readsEachDeclaredLoginFlow(String value, LoginFlow expected) {
        final AgentDefinition definition = read(MINIMAL + "login:\n  flow: " + value + "\n");

        assertThat(definition.loginFlow())
                .as("the adapter's declaration controls the sign-in policy")
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "true", "7", "[]", "{}", "null"})
    void refusesALoginFlowThatIsNotOneOfTheDeclaredValues(String value) {
        assertThatThrownBy(() -> read(MINIMAL + "login:\n  flow: " + value + "\n"))
                .as("a malformed flow must not silently become an undeclared flow")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("login.flow");
    }

    @Test
    void anOlderDefinitionDeclaresNoLoginFlow() {
        assertThat(read(MINIMAL).loginFlow())
                .as("an older adapter keeps its sign-in behavior")
                .isNull();
        assertThat(read(MINIMAL + "login:\n  arguments: []\n").loginFlow())
                .as("a login declared before flows keeps its behavior too")
                .isNull();
    }

    @Test
    void readsTheMinimumAnAgentMustDeclare() {

        final AgentDefinition definition = read(MINIMAL);

        assertThat(definition.name()).isEqualTo("example");
        assertThat(definition.binary()).isEqualTo("example-cli");
        assertThat(definition.gitIdentity().email()).isEqualTo("noreply@example.com");
        assertThat(definition.headless().promptFlag()).isEqualTo("-p");
    }

    @Test
    void labelDefaultsToTheName() {

        assertThat(read(MINIMAL).label()).isEqualTo("example");
        assertThat(read(MINIMAL + "label: Example Agent\n").label()).isEqualTo("Example Agent");
    }

    @Test
    void readsAnInstallBlockWrittenAsAYamlBlockScalar() {

        // Install fragments are shell. Writing them as a readable block beats a list of quoted
        // one-liners, so a block scalar is split into lines here.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                install:
                  as_root: |
                    RUN mkdir -p /opt/example

                    RUN chown agent /opt/example
                  as_agent: |
                    RUN curl -fsSL https://example.com/install.sh | bash
                """);

        assertThat(definition.installAsRoot())
                .containsExactly("RUN mkdir -p /opt/example", "RUN chown agent /opt/example");
        assertThat(definition.installAsAgent()).hasSize(1);
    }

    @Test
    void readsTheDomainsAnAgentNeeds() {

        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                allowed_domains:
                  - api.example.com
                  - registry.npmjs.org
                """);

        assertThat(definition.allowedDomains())
                .containsExactly("api.example.com", "registry.npmjs.org");
    }

    @Test
    void readsWhatTheAgentSaysAboutProviders() {

        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """

                provider:
                  default: example
                  dialect: openai
                  endpoint: socket
                  socket_env: EXAMPLE_UNIX_SOCKET
                  base_url_env: EXAMPLE_BASE_URL
                  token_env:
                    oauth: EXAMPLE_OAUTH_TOKEN
                """);

        assertThat(definition.provider()).isNotNull();
        assertThat(definition.provider().dialect()).isEqualTo("openai");
        assertThat(definition.provider().defaultProvider()).isEqualTo("example");
        assertThat(definition.provider().socketEnvironment()).isEqualTo("EXAMPLE_UNIX_SOCKET");
        assertThat(definition.provider().baseUrlEnvironment()).isEqualTo("EXAMPLE_BASE_URL");

        // The agent's own override still works; the fallback lives on the provider now.
        assertThat(definition.tokenVariable("oauth")).isEqualTo("EXAMPLE_OAUTH_TOKEN");
    }

    @Test
    void anAgentThatCannotBeRedirectedDeclaresNoProvider() {

        // Not an error. It means the agent must be given its credential directly, which is the
        // operator's decision to make rather than the reader's to paper over.
        assertThat(read(MINIMAL).provider()).isNull();
    }

    @Test
    void readsTheDomainsAnAgentIsDeliberatelyDenied() {

        // Telemetry hosts are declared, not merely left out: the firewall cannot tell an oversight
        // from a policy, and the domain-coverage test needs to.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                allowed_domains:
                  - api.example.com
                refused_domains:
                  - telemetry.example.com
                """);

        assertThat(definition.allowedDomains()).containsExactly("api.example.com");
        assertThat(definition.refusedDomains()).containsExactly("telemetry.example.com");
    }

    @Test
    void refusesADomainThatIsBothAllowedAndRefused() {

        // Which list wins would otherwise depend on which one the ruleset consults first.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                allowed_domains:
                  - api.example.com
                refused_domains:
                  - api.example.com
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("both allows and refuses")
                .hasMessageContaining("api.example.com");
    }

    @Test
    void refusingNothingIsTheNormalCase() {

        assertThat(read(MINIMAL).refusedDomains()).isEmpty();
    }

    @Test
    void mapsCredentialTypesToEnvironmentVariables() {

        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                provider:
                  dialect: openai
                  base_url_env: EXAMPLE_BASE_URL
                  token_env:
                    oauth: EXAMPLE_OAUTH_TOKEN
                    _default: EXAMPLE_API_KEY
                """);

        assertThat(definition.tokenVariable("oauth")).isEqualTo("EXAMPLE_OAUTH_TOKEN");
        assertThat(definition.tokenVariable("api-key")).isEqualTo("EXAMPLE_API_KEY");
        assertThat(definition.provider().baseUrlEnvironment()).isEqualTo("EXAMPLE_BASE_URL");
    }

    @Test
    void anAgentWithNoProviderTakesNoToken() {

        assertThat(read(MINIMAL).tokenVariable("oauth")).isNull();
    }

    @Test
    void defaultsToNoSessionSupport() {

        assertThat(read(MINIMAL).supportsResume()).isFalse();
        assertThat(read(MINIMAL).resumeFlag()).isNull();
    }

    @Test
    void refusesToSaySupportsResumeWithoutNamingTheFlag() {

        // Otherwise a resume request builds a command line with a null flag in it.
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  supports_resume: true\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("supports resume but names no flag");
    }

    @Test
    void refusesAMissingSection() {

        assertThatThrownBy(() -> read("name: example\nbinary: x\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("no 'git_identity' section");
    }

    @Test
    void refusesANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read(MINIMAL.replace("name: example", "name: Example Agent")))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Invalid agent name");
    }

    @Test
    void refusesDuplicateKeys() {

        assertThatThrownBy(() -> read(MINIMAL + "binary: something-else\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Cannot parse");
    }

    @Test
    void reportsTheOriginOfTheProblem() {

        assertThatThrownBy(
                        () -> AgentDefinitionReader.read(new StringReader("[]"), "/x/claude.yaml"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("/x/claude.yaml");
    }

    @Test
    void readsWhatAnAgentShipsInItsOwnPackage() {

        // The alternative to a pinned URL, for a tool that is a tree of files rather than one
        // binary: the package carries it and the image build fetches nothing.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                install:
                  packaged:
                    - source: /usr/share/porelo/agents/uc
                      target: /opt/uc
                """);

        assertThat(definition.packaged()).hasSize(1);
        assertThat(definition.packaged().getFirst().source())
                .isEqualTo("/usr/share/porelo/agents/uc");
        assertThat(definition.packaged().getFirst().target()).isEqualTo("/opt/uc");
    }

    @Test
    void readsARelativePackagedSourceAsBesideTheAgent() {

        // Resolved beside the agent's binary where it is staged, so a copy of the agent in one
        // account ships its own tree. It was refused while it meant "wherever the build ran".
        assertThat(
                        read(MINIMAL
                                        + """
                install:
                  packaged:
                    - source: uc/tree.tar.gz
                      target: /opt/uc
                """)
                                .packaged())
                .containsExactly(new PackagedTree("uc/tree.tar.gz", "/opt/uc"));
    }

    @Test
    void refusesARelativePackagedSourceThatLeavesTheAgentsDirectory() {
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                install:
                  packaged:
                    - source: ../elsewhere/uc
                      target: /opt/uc
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("stays beside the agent's binary");
    }

    @Test
    void refusesAPackagedTargetThatIsNotAbsolute() {
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                install:
                  packaged:
                    - source: /usr/share/uc
                      target: opt/uc
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be an absolute path");
    }

    @Test
    void defaultsToASocketEndpoint() {

        // The safer one, and what every agent gets unless it says it cannot use a socket.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                provider:
                  dialect: openai
                  socket_env: UC_SOCKET
                """);

        assertThat(definition.provider().endpoint()).isEqualTo(ProviderRoute.Endpoint.SOCKET);
    }

    @Test
    void readsAnAgentThatCanOnlyBeGivenAUrl() {

        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                provider:
                  dialect: native
                  endpoint: url
                """);

        assertThat(definition.provider().endpoint()).isEqualTo(ProviderRoute.Endpoint.URL);
    }

    @Test
    void refusesAnEndpointItCannotServe() {

        // Named rather than ignored: an unknown endpoint would otherwise fall back to a socket and
        // the agent would be pointed at something it cannot address.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: openai
                  endpoint: carrier-pigeon
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be 'socket' or 'url'");
    }

    @Test
    void readsWhereTheAgentKeepsItsOwnCredentials() {
        assertThat(read(MINIMAL + "config_dir: \"~/.example\"\n").configDirectory())
                .isEqualTo("~/.example");
    }

    @Test
    void leavesTheConfigDirectoryUnsetWhenNoneIsDeclared() {

        // An agent that manages no credential of its own must not get a guessed directory.
        assertThat(read(MINIMAL).configDirectory()).isNull();
    }

    @Test
    void readsWhatTheAgentShowsOnceAtWorkAndHowLongThatMayTake() {
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                session:
                  ready_marker: "at work"
                  ready_within_seconds: 45
                """);

        assertThat(definition.ready()).isEqualTo(new ReadyMarker("at work", 45));
        // And through the description the kit reads, unchanged.
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).ready())
                .isEqualTo(new ReadyMarker("at work", 45));
    }

    @Test
    void anAgentWithNoStableReadyTextDeclaresNone() {
        assertThat(read(MINIMAL).ready()).isNull();
    }

    @Test
    void refusesABoundWithNothingToBound() {
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  ready_within_seconds: 30\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("none is declared");
    }

    @Test
    void refusesAMarkerOfMoreThanOneLineAndABoundPastTheLimit() {
        assertThatThrownBy(() -> new ReadyMarker("one\ntwo", null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new ReadyMarker("ok", ReadyMarker.MOST_SECONDS + 1))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void aModeWrittenAsANumberIsRefusedBecauseYamlReadsItInOctal() {

        // 'mode: 0644' is the number 420 to YAML, and the file was installed as 0420 - nobody may
        // read it but its owner's group; '0755' became 493 and was refused with words that did not
        // say why.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://example.com/uc
                      sha256: "%s"
                      target: /usr/local/bin/uc
                      mode: 0644
                """
                                                        .formatted("a".repeat(64))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("'0644'");
        assertThat(
                        read(MINIMAL
                                        + """
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://example.com/uc
                      sha256: "%s"
                      target: /usr/local/bin/uc
                      mode: "0644"
                """
                                                .formatted("a".repeat(64)))
                                .artifacts()
                                .getFirst()
                                .mode())
                .isEqualTo("0644");
    }

    @Test
    void whatGoesIntoTheImageBuildIsAPathOrPlainWordsNothingThatStartsAnotherCommand() {

        // The target went into the build script unquoted, so ';' ran a second command; a line break
        // in a reason or a packaged target started an instruction of its own.
        assertThatThrownBy(
                        () ->
                                new InstallArtifact(
                                        "https://example.com/uc",
                                        "a".repeat(64),
                                        "/usr/local/bin/uc;curl x|sh",
                                        "0755",
                                        false,
                                        null,
                                        null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(
                        () ->
                                new InstallArtifact(
                                        "https://example.com/uc",
                                        null,
                                        "/usr/local/bin/uc",
                                        "0755",
                                        true,
                                        "no digest\nRUN curl x|sh",
                                        null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new PackagedTree("uc/tree", "/opt/uc\nRUN id"))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new PackagedTree("uc/tree", "/opt/my tree"))
                .isInstanceOf(AgentException.class);
        assertThat(new PackagedTree("uc/tree", "/opt/uc-1.2_x").target())
                .isEqualTo("/opt/uc-1.2_x");
    }

    @Test
    void readsTheAppItSignsInToAProviderWithOneIdPerHost() {
        // An agent may use one OAuth app on github.com and another on an Enterprise host; Porelo
        // grants whichever the agent itself would use, and says whose it is.
        final AgentDefinition agent =
                read(
                        MINIMAL
                                + """
                login:
                  arguments: []
                  grants:
                    github-copilot:
                      hosts:
                        github.com: Ov23-for-github
                        "*": Ov23-for-enterprise
                      owner: another tool's app, as its source says
                """);

        final AgentGrant grant = agent.grantFor("github-copilot");
        assertThat(grant).isNotNull();
        assertThat(grant.clientIdFor("github.com")).isEqualTo("Ov23-for-github");
        assertThat(grant.clientIdFor("ghe.example.com")).isEqualTo("Ov23-for-enterprise");
        assertThat(grant.owner()).isEqualTo("another tool's app, as its source says");
        assertThat(agent.grantFor("openrouter")).isNull();
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(agent)).grants())
                .as("the same after the interface's JSON")
                .isEqualTo(agent.grants());
    }

    @Test
    void anAgentThatSignsInToNothingHasNoGrant() {
        assertThat(read(MINIMAL).grants()).isEmpty();
    }

    @Test
    void refusesAGrantWithoutAClientId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                login:
                  grants:
                    github-copilot:
                      owner: nobody
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("hosts");
    }

    @Test
    void anAgentThatAddsTheDialectsPathItselfIsReadSo() {
        // An agent whose client appends the dialect's path to its base URL would double the path if
        // Porelo put it on the base URL too.
        final AgentDefinition addsItself =
                read(
                        MINIMAL
                                + """
                provider:
                  default: google
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GOOGLE_GEMINI_BASE_URL
                  adds_path: true
                """);
        final AgentDefinition takesPath =
                read(
                        MINIMAL
                                + """
                provider:
                  default: google
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GOOGLE_GEMINI_BASE_URL
                """);

        assertThat(addsItself.provider().addsPath())
                .as("adds_path: true is read from the provider block")
                .isTrue();
        assertThat(takesPath.provider().addsPath())
                .as("absent adds_path defaults to false")
                .isFalse();
        assertThat(
                        AgentDefinitionJson.read(AgentDefinitionJson.write(addsItself))
                                .provider()
                                .addsPath())
                .as("addsPath survives the wire's JSON")
                .isTrue();
        assertThat(
                        AgentDefinitionJson.read(AgentDefinitionJson.write(takesPath))
                                .provider()
                                .addsPath())
                .as("false addsPath survives the wire's JSON")
                .isFalse();
    }

    @Test
    void refusesAddsPathWhenNotABooleanOrWithoutABaseUrlVariable() {
        // Anything other than a boolean or a declaration with no base_url_env to change would be
        // ignored in silence and send requests to the wrong path.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GOOGLE_GEMINI_BASE_URL
                  adds_path: "/v1beta"
                """))
                .as("a path string in adds_path is refused as not a boolean")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("adds_path")
                .hasMessageContaining("boolean");
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  adds_path: true
                """))
                .as("adds_path without base_url_env is refused, naming both keys")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("adds_path")
                .hasMessageContaining("base_url_env");
    }

    @Test
    void providerDialectsRoundTripThroughJson() {
        // An agent that speaks both an API-key dialect and a sign-in dialect declares the second
        // under provider.dialects, each with its own base URL variable and adds_path.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
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
                """);

        assertThat(definition.provider().dialects())
                .as("secondary dialects are read from provider.dialects")
                .containsExactlyEntriesOf(
                        java.util.Map.of(
                                "cloud-code", new AgentProvider.Dialect("CLOUDCODE_URL", true)));
        final AgentDefinition roundTripped =
                AgentDefinitionJson.read(AgentDefinitionJson.write(definition));
        assertThat(roundTripped)
                .as("the whole definition survives AgentDefinitionJson")
                .isEqualTo(definition);
        assertThat(roundTripped.provider().dialects())
                .as("provider.dialects survives AgentDefinitionJson both ways")
                .containsExactlyEntriesOf(
                        java.util.Map.of(
                                "cloud-code", new AgentProvider.Dialect("CLOUDCODE_URL", true)));
    }

    @Test
    void
            refusesTheBlocksOwnDialectListedAgainOrOneVariableNamedForTwoDialectsOrADialectListedTwice() {
        // Listing the primary dialect again or sharing a base_url_env between two dialects leaves
        // which setting applies ambiguous, and a duplicate YAML key silently overwrites the first.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GEMINI_API_BASE_URL
                  dialects:
                    google-generative-ai:
                      base_url_env: OTHER_URL
                """))
                .as(
                        "the block's own dialect listed again in provider.dialects is refused naming it")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("google-generative-ai");
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GEMINI_API_BASE_URL
                  dialects:
                    cloud-code:
                      base_url_env: GEMINI_API_BASE_URL
                """))
                .as(
                        "a variable named for both the block's dialect and another is refused naming it")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("GEMINI_API_BASE_URL");
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GEMINI_API_BASE_URL
                  dialects:
                    cloud-code:
                      base_url_env: SHARED_BASE_URL
                    openai:
                      base_url_env: SHARED_BASE_URL
                """))
                .as("a variable named for two entries in provider.dialects is refused naming it")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("SHARED_BASE_URL");
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                provider:
                  dialect: google-generative-ai
                  endpoint: url
                  base_url_env: GEMINI_API_BASE_URL
                  dialects:
                    cloud-code:
                      base_url_env: CLOUDCODE_URL
                    cloud-code:
                      base_url_env: OTHER_URL
                """))
                .as("a dialect listed twice in provider.dialects is refused naming it")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("cloud-code");
    }
}
