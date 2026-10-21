package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.agent.api.ProviderDirectory;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentSignIns}: a host's play stops for a sign-in exactly where this says one is
 * missing, so a wrong {@code signed_in} either starts a task with no credential or makes a person
 * sign in again for nothing.
 */
class AgentSignInsTest {

    private static final Path SHIPPED = Path.of("..", "..", "providers");

    private static final String SECRET = "sk-ant-not-a-real-key-0123456789";

    private static Map<String, ProviderDefinition> providers() {
        return new ProviderDirectory(List.of(SHIPPED)).all();
    }

    private static AgentSignIns.Found agent(String name, String providerBlock) {
        final AgentDefinition definition =
                AgentDefinitionReader.read(
                        new java.io.StringReader(
                                "name: "
                                        + name
                                        + """

                                binary: example-cli
                                git_identity:
                                  name: Example
                                  email: noreply@example.com
                                headless:
                                  prompt_flag: "-p"
                                """
                                        + providerBlock),
                        "test.yaml");
        return new AgentSignIns.Found(definition, Path.of("/usr/share/porelo/agents.d/" + name));
    }

    private static AgentSignIns.Found anthropicAgent(String name) {
        return agent(
                name,
                """
                provider:
                  default: anthropic
                  dialect: anthropic-messages
                  endpoint: url
                """);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("agents");
    }

    @Test
    void anOpenVaultSaysSignedInOnlyForTheAgentWhoseProviderEntryIsThere() {

        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(
                                anthropicAgent("one"),
                                agent(
                                        "two",
                                        """
                                provider:
                                  default: openrouter
                                  dialect: openai
                                  endpoint: url
                                """)),
                        providers(),
                        true,
                        Optional.of(Map.of("anthropic", VaultEntry.of(SECRET))));

        assertThat(report.get("vault")).as("the vault's state").isEqualTo("open");
        assertThat(rows(report))
                .as("each agent with the entry a login stores under")
                .extracting(row -> row.get("name"), row -> row.get("credential"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("one", "anthropic"),
                        org.assertj.core.groups.Tuple.tuple("two", "openrouter"));
        assertThat(rows(report))
                .as("signed in only where the vault holds that entry")
                .extracting(row -> row.get("signed_in"))
                .containsExactly(true, false);
    }

    @Test
    void anEntryStillUnderTheAgentsOwnNameCountsAsSignedIn() {

        // A start still uses it, so a play that asked for a sign-in here would ask for nothing.
        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(anthropicAgent("one")),
                        providers(),
                        true,
                        Optional.of(Map.of("one", VaultEntry.of(SECRET))));

        assertThat(rows(report).get(0))
                .as("the legacy entry")
                .containsEntry("credential", "one")
                .containsEntry("signed_in", true);
    }

    @Test
    void anAgentThatIsNotBrokeredSignsInUnderItsOwnName() {

        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(agent("direct", "")),
                        providers(),
                        true,
                        Optional.of(Map.of("direct", VaultEntry.of(SECRET))));

        assertThat(rows(report).get(0))
                .containsEntry("credential", "direct")
                .containsEntry("signed_in", true);
    }

    @Test
    void aShutVaultGivesNoAnswerRatherThanFalse() {

        // False would send a play to a sign-in the vault may already hold.
        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(anthropicAgent("one")), providers(), true, Optional.empty());

        assertThat(report.get("vault")).as("the vault's state").isEqualTo("shut");
        assertThat(rows(report).get(0))
                .as("no signed_in while shut")
                .containsEntry("credential", "anthropic")
                .doesNotContainKey("signed_in");
    }

    @Test
    void noVaultIsSaidAsSuchAndGivesNoAnswer() {

        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(anthropicAgent("one")), providers(), false, Optional.of(Map.of()));

        assertThat(report.get("vault")).as("the vault's state").isEqualTo("none");
        assertThat(rows(report).get(0))
                .as("no signed_in without a vault")
                .doesNotContainKey("signed_in");
    }

    @Test
    void anAgentWithNoDefaultProviderNamesNoEntry() {

        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(
                                agent(
                                        "open-ended",
                                        """
                                provider:
                                  dialect: openai
                                  endpoint: url
                                """)),
                        providers(),
                        true,
                        Optional.of(Map.of()));

        assertThat(rows(report).get(0))
                .as("a start would have to choose a provider first")
                .containsOnlyKeys("name", "found_by");
    }

    @Test
    void aSignInLackingWhatItsProviderSendsBesideTheTokenIsNotSignedIn() {

        // A start refuses it and asks for a sign-in; a play told "signed in" would skip that and
        // start a task that is refused.
        final AgentSignIns.Found agent =
                agent(
                        "codex",
                        """
                provider:
                  default: chatgpt
                  dialect: openai
                  endpoint: url
                """);

        final Map<String, Object> without =
                AgentSignIns.report(
                        List.of(agent),
                        providers(),
                        true,
                        Optional.of(Map.of("chatgpt", new VaultEntry(SECRET, "oauth", Map.of()))));
        final Map<String, Object> with =
                AgentSignIns.report(
                        List.of(agent),
                        providers(),
                        true,
                        Optional.of(
                                Map.of(
                                        "chatgpt",
                                        new VaultEntry(
                                                SECRET, "oauth", Map.of("account_id", "acct-1")))));

        assertThat(rows(without).get(0)).as("no account kept").containsEntry("signed_in", false);
        assertThat(rows(with).get(0)).as("the account kept").containsEntry("signed_in", true);
    }

    @Test
    void anAgentWhoseProviderCannotBeUsedNamesNoEntry() {

        // Its start is refused whatever the vault holds, so a play asking for a sign-in would ask
        // a person for nothing.
        final Map<String, Object> report =
                AgentSignIns.report(
                        List.of(
                                agent(
                                        "nowhere",
                                        """
                                provider:
                                  default: nowhere
                                  dialect: openai
                                  endpoint: url
                                """)),
                        providers(),
                        true,
                        Optional.of(Map.of()));

        assertThat(rows(report).get(0)).containsOnlyKeys("name", "found_by");
    }

    @Test
    void theReportCarriesNoCredentialValue() {

        final String json =
                org.fuin.porelo.wire.Json.write(
                        AgentSignIns.report(
                                List.of(anthropicAgent("one")),
                                providers(),
                                true,
                                Optional.of(Map.of("anthropic", VaultEntry.of(SECRET)))));

        assertThat(json)
                .as("the printed report")
                .contains("\"signed_in\":true")
                .doesNotContain(SECRET);
    }
}
