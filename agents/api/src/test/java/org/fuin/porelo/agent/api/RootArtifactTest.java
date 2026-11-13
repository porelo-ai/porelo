package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for an artifact a root step owns: {@code owner: root} in an agent definition.
 *
 * <p>What an agent writes during a task must never pick what Porelo starts as that agent next time,
 * so an adapter installs its binary as root. A fetched binary a root fragment unpacks has to be
 * there, checked, before {@code as_root} runs - and owned by root, out of the agent's home.
 */
class RootArtifactTest {

    private static final String ROOT_DIGEST = "a".repeat(64);

    private static final String AGENT_DIGEST = "b".repeat(64);

    private static final String DEFINITION =
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
                - url: https://example.com/root.tar.gz
                  sha256: "%s"
                  target: /opt/example/root.tar.gz
                  mode: "0644"
                  owner: root
                - url: https://example.com/agent-tool
                  sha256: "%s"
                  target: /home/agent/.local/bin/agent-tool
              as_root:
                - "RUN tar -xzf /opt/example/root.tar.gz -C /usr/local/bin"
              as_agent:
                - "RUN agent-tool --version"
            """
                    .formatted(ROOT_DIGEST, AGENT_DIGEST);

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    private static String withRootArtifact(String owner, String target) {
        return """
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
                - url: https://example.com/tool
                  sha256: "%s"
                  target: %s
                  owner: %s
            """
                .formatted(ROOT_DIGEST, target, owner);
    }

    @Test
    void aRootArtifactIsFetchedAndCheckedAsRootBeforeTheRootFragments() {

        final Agent agent = () -> read(DEFINITION);
        final ImageLayer layer = agent.imageLayer();
        final List<String> asRoot = layer.asRoot();

        // The root part is rendered before 'USER agent', so it runs as root.
        final int fetch = indexOf(asRoot, "https://example.com/root.tar.gz");
        final int check = indexOf(asRoot, "echo '" + ROOT_DIGEST);
        final int install = indexOf(asRoot, "/opt/example/root.tar.gz; \\");
        final int fragment = indexOf(asRoot, "RUN tar -xzf");
        assertThat(asRoot.getFirst()).isEqualTo("RUN set -eux; \\");
        assertThat(fetch).isGreaterThan(-1);
        assertThat(check).isGreaterThan(fetch);
        assertThat(install).isGreaterThan(check);
        assertThat(fragment).as("as_root runs after its artifact is there").isGreaterThan(install);
        assertThat(asRoot.get(fragment - 1)).as("a blank line between them").isEmpty();

        // The agent's own artifact stays where it was: after 'USER agent', before as_agent.
        assertThat(String.join("\n", layer.asAgent()))
                .doesNotContain("root.tar.gz")
                .contains("https://example.com/agent-tool")
                .contains("echo '" + AGENT_DIGEST);
        assertThat(indexOf(layer.asAgent(), "agent-tool --version"))
                .isGreaterThan(indexOf(layer.asAgent(), "https://example.com/agent-tool"));
    }

    @Test
    void anArtifactThatNamesNoOwnerIsTheAgentsAsToday() {

        final Agent agent = () -> read(withRootArtifact("agent", "/home/agent/.local/bin/tool"));

        assertThat(agent.imageLayer().asRoot()).isEmpty();
        assertThat(String.join("\n", agent.imageLayer().asAgent()))
                .contains("https://example.com/tool");
        assertThat(read(DEFINITION.replace("      owner: root\n", "")).artifacts())
                .allSatisfy(
                        artifact ->
                                assertThat(artifact.owner())
                                        .isEqualTo(InstallArtifact.Owner.AGENT));
    }

    @Test
    void refusesAnOwnerThatIsNeitherRootNorTheAgent() {

        assertThatThrownBy(() -> read(withRootArtifact("nobody", "/usr/local/bin/tool")))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("owner")
                .hasMessageContaining("nobody");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/home/agent",
                "/home/agent/.local/bin/tool",
                "/home/agent/../agent/tool",
                "/usr/../home/agent/tool",
                "//home/agent/tool"
            })
    void refusesARootArtifactInTheAgentsHome(String target) {

        // Root-owned in a directory the agent owns is not root-owned for long: the agent renames
        // it away and puts its own in its place.
        assertThatThrownBy(() -> read(withRootArtifact("root", target)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("/home/agent")
                .hasMessageContaining(target);
    }

    @Test
    void aRootArtifactBesideTheAgentsHomeIsNotInIt() {

        final AgentDefinition definition = read(withRootArtifact("root", "/home/agent-tools/tool"));

        assertThat(definition.artifacts().getFirst().owner()).isEqualTo(InstallArtifact.Owner.ROOT);
    }

    @Test
    void theSupplyChainSeesARootArtifactAsAnyOther() {

        final AgentDefinition definition =
                read(
                        DEFINITION.replace(
                                "      sha256: \"" + ROOT_DIGEST + "\"\n",
                                "      unverified: true\n"
                                        + "      reason: \"the publisher offers no digest\"\n"));

        assertThat(definition.unverifiedArtifacts())
                .extracting(InstallArtifact::url)
                .containsExactly("https://example.com/root.tar.gz");
        assertThat(String.join("\n", ((Agent) () -> definition).imageLayer().asRoot()))
                .contains("# UNVERIFIED: the publisher offers no digest");
    }

    @Test
    void theOwnerSurvivesTheInterfacesJson() {

        final AgentDefinition definition = read(DEFINITION);
        final AgentDefinition again =
                AgentDefinitionJson.read(AgentDefinitionJson.write(definition));

        assertThat(again.artifacts()).isEqualTo(definition.artifacts());
        assertThat(again.artifacts())
                .extracting(InstallArtifact::owner)
                .containsExactly(InstallArtifact.Owner.ROOT, InstallArtifact.Owner.AGENT);
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
