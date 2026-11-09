package org.fuin.porelo.planning.api;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The published names and record representations are what outside repositories build against. */
class ContractTest {
    @Test
    void stateChangesNameExactlyOneResourceAndRejectMalformedMoves() {
        var story = new StateChange("s", null, "Done");
        var issue = new StateChange(null, "i", "Done");
        assertThat(StateChange.of(story.asMap())).isEqualTo(story);
        assertThat(StateChange.of(issue.asMap())).isEqualTo(issue);
        assertThat(story.asMap()).doesNotContainKey("issue");
        assertThat(issue.asMap()).doesNotContainKey("story");
        var nullable = new java.util.LinkedHashMap<>(story.asMap());
        nullable.put("issue", null);
        assertThat(StateChange.of(nullable)).isEqualTo(story);
        for (var fields :
                List.of(
                        Map.of("to", "Done"),
                        Map.of("story", "s", "issue", "i", "to", "Done"),
                        Map.of("story", " ", "to", "Done"),
                        Map.of("story", false, "to", "Done"),
                        Map.of("issue", 1, "to", "Done"),
                        Map.of("issue", "i", "to", ""))) {
            assertThatThrownBy(() -> StateChange.of(fields))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new StateChange(null, null, "Done"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateChange("s", "i", "Done"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateChange(null, " ", "Done"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storyPriorityKeepsTheAdaptersWordsAndOlderRepliesRemainReadable() {
        var fields =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "id",
                                "s",
                                "title",
                                "Story",
                                "state",
                                "",
                                "link",
                                "",
                                "description",
                                ""));
        fields.put("priority", "Highest — this week");
        assertThat(Story.of(fields).asMap()).containsEntry("priority", "Highest — this week");
        assertThat(Story.of(fields).summary().asMap())
                .containsEntry("priority", "Highest — this week");
        assertThat(StorySummary.of(fields).asMap())
                .containsEntry("priority", "Highest — this week");
        fields.remove("priority");
        assertThat(Story.of(fields).asMap()).doesNotContainKey("priority");
        assertThat(StorySummary.of(fields).asMap()).doesNotContainKey("priority");
        fields.put("priority", null);
        assertThat(Story.of(fields).asMap()).doesNotContainKey("priority");
        assertThat(StorySummary.of(fields).asMap()).doesNotContainKey("priority");
        fields.put("priority", 1);
        assertThatThrownBy(() -> Story.of(fields)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorySummary.of(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void genericServerFailuresUseTheSharedMessageField() {
        var heard =
                InstalledPlanning.heard(
                        java.nio.file.Path.of("example"),
                        new org.fuin.porelo.wire.varlink.VarlinkException(
                                PlanningProtocol.INTERFACE + ".Failed",
                                Map.of("message", "Safe failure")));
        assertThat(heard)
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        refusal ->
                                assertThat(refusal.parameters())
                                        .containsEntry("message", "Safe failure"));
    }

    @Test
    void storeRefusalsAndNewerErrorsPreserveTheirNamesAndTypedFields() {
        for (String name :
                List.of(
                        "CredentialRefused",
                        "RateLimited",
                        "NoSuchStore",
                        "Unreachable",
                        "InvalidSettings",
                        "Failed")) {
            Map<String, Object> fields =
                    List.of("Failed", "InvalidSettings").contains(name)
                            ? Map.of(name.equals("Failed") ? "message" : "detail", "Safe detail")
                            : Map.of("detail", "Safe detail", "retryAfter", 17L);
            var heard =
                    InstalledPlanning.heard(
                            java.nio.file.Path.of("example"),
                            new org.fuin.porelo.wire.varlink.VarlinkException(
                                    PlanningProtocol.INTERFACE + "." + name, fields));
            assertThat(heard)
                    .isInstanceOfSatisfying(
                            PlanningRefused.class,
                            refusal -> {
                                assertThat(refusal.reason().wire()).isEqualTo(name);
                                assertThat(refusal.parameters()).isEqualTo(fields);
                            });
        }
        var future =
                InstalledPlanning.heard(
                        java.nio.file.Path.of("example"),
                        new org.fuin.porelo.wire.varlink.VarlinkException(
                                "example.Future", Map.of("extra", "keep")));
        assertThat(future)
                .isInstanceOfSatisfying(
                        PlanningException.class,
                        failure -> {
                            assertThat(failure.errorName()).isEqualTo("example.Future");
                            assertThat(failure.parameters()).containsEntry("extra", "keep");
                        });
        var malformed =
                InstalledPlanning.heard(
                        java.nio.file.Path.of("example"),
                        new org.fuin.porelo.wire.varlink.VarlinkException(
                                PlanningProtocol.INTERFACE + ".CredentialRefused",
                                Map.of("detail", "safe", "retryAfter", -1)));
        assertThat(malformed)
                .isInstanceOf(PlanningException.class)
                .hasMessageContaining("malformed refusal");
        assertThat((PlanningException) malformed)
                .satisfies(
                        failure -> {
                            assertThat(failure.errorName())
                                    .isEqualTo(PlanningProtocol.INTERFACE + ".CredentialRefused");
                            assertThat(failure.parameters()).containsEntry("retryAfter", -1);
                        });
    }

    @Test
    void everyPublishedTypeMethodAndErrorHasItsReviewedSignature() throws Exception {
        try (var input = ContractTest.class.getResourceAsStream("planning-contract.txt")) {
            assertThat(input).isNotNull();
            assertThat(
                            PlanningServer.idl()
                                    .lines()
                                    .filter(
                                            line ->
                                                    line.startsWith("type ")
                                                            || line.startsWith("method ")
                                                            || line.startsWith("error "))
                                    .toList())
                    .containsExactlyElementsOf(
                            new String(
                                            input.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    .lines()
                                    .toList());
        }
    }

    @Test
    void publishedExtensionsMakeBoardReadsAndRefusalsUnambiguous() {
        assertThat(PlanningServer.idl())
                .contains(
                        "subAgents: ?[]SubAgent",
                        "issues: ?[]IssueSummary",
                        "error StateNotAllowed(item: string, from: string, to: string, reason: string)",
                        "error StoryHasIssues(story: string)",
                        "error CredentialRefused(detail: string, retryAfter: int)",
                        "error RateLimited(detail: string, retryAfter: int)",
                        "error NoSuchStore(detail: string, retryAfter: int)",
                        "error Unreachable(detail: string, retryAfter: int)",
                        "error InvalidSettings(detail: string)",
                        "error Failed(message: string)");
    }

    @Test
    void newerOptionalFieldsMayBeNullWithoutDiscardingTheObject() {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("future", null);
        assertThat(Wire.object(Map.of("settings", fields), "settings")).containsKey("future");
        var issue = new Issue("i", "repo", "Title", "Open", "", List.of(), "", "", List.of());
        var newer = new java.util.LinkedHashMap<>(issue.asMap());
        newer.put("subAgents", null);
        assertThat(Issue.of(newer).subAgents()).isEmpty();
        var story = new StorySummary("s", "Story", "", "", List.of(issue.summary()));
        assertThat(StorySummary.of(story.asMap())).isEqualTo(story);
        var older = new java.util.LinkedHashMap<>(story.asMap());
        older.remove("issues");
        assertThat(StorySummary.of(older).issues()).isNull();
        older.put("issues", null);
        assertThat(StorySummary.of(older).issues()).isNull();
    }

    @Test
    void newerKindsDecodeAsOtherInsteadOfPreventingARead() {
        assertThat(PlanState.of(Map.of("name", "Future", "kind", "PAUSED")).kind().name())
                .isEqualTo("OTHER");
        assertThat(
                        SubAgent.of(
                                        Map.of(
                                                "name",
                                                "future",
                                                "kind",
                                                "COORDINATOR",
                                                "agent",
                                                "a",
                                                "model",
                                                "m",
                                                "brief",
                                                ""))
                                .kind()
                                .name())
                .isEqualTo("OTHER");
    }

    @Test
    void refusalAllowsUnknownNullFieldsAndRejectsBlankIdentifiers() {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("story", "s");
        fields.put("future", null);
        assertThat(new PlanningRefused(PlanningRefused.Reason.NO_SUCH_STORY, fields).parameters())
                .containsEntry("story", "s");
        assertThatThrownBy(() -> StateChange.of(Map.of("issue", " ", "to", "Done")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePublishedInterfaceDeclaresEveryMethodAndNamedRefusal() {
        String idl = PlanningServer.idl();
        assertThat(
                        Pattern.compile("(?m)^method (\\w+)\\(")
                                .matcher(idl)
                                .results()
                                .map(match -> PlanningProtocol.INTERFACE + "." + match.group(1)))
                .as("every promised operation is published")
                .containsExactlyInAnyOrder(
                        PlanningProtocol.DESCRIBE,
                        PlanningProtocol.GET_WORKFLOW,
                        PlanningProtocol.LIST_STORIES,
                        PlanningProtocol.GET_STORY,
                        PlanningProtocol.LIST_STORY_ISSUES,
                        PlanningProtocol.GET_ISSUE,
                        PlanningProtocol.SET_STATES,
                        PlanningProtocol.WATCH_CHANGES,
                        PlanningProtocol.REFRESH);
        assertThat(
                        Pattern.compile("(?m)^error (\\w+)\\(")
                                .matcher(idl)
                                .results()
                                .map(match -> match.group(1)))
                .as("the full refusal set is published")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(PlanningRefused.Reason.values())
                                .map(PlanningRefused.Reason::wire)
                                .toList());
        assertThat(idl)
                .as("critical extension fields are part of the public contract")
                .contains(
                        "credentials: []string",
                        "whatMustBeTrue: string",
                        "type SubAgent (name: string, kind: (BUILDER, TESTER, READER, OTHER), agent: string, model: string, brief: string)",
                        "subAgents: ?[]SubAgent",
                        "error WorkflowMissing(detail: string)");
    }

    @Test
    void subAgentListsRejectMalformedProposalsAndStayOutOfSummaries() {
        var issue =
                new Issue(
                        "i", "repo", "Title", "Open", "S", List.of(), "link", "Accept", List.of());
        assertThat(issue.summary().asMap())
                .as("proposals belong only to the full issue")
                .doesNotContainKey("subAgents");
        var fields = new java.util.LinkedHashMap<>(issue.asMap());
        fields.put("subAgents", "invalid");
        assertThatThrownBy(() -> Issue.of(fields))
                .as("a present malformed list cannot silently discard proposals")
                .isInstanceOf(IllegalArgumentException.class);
        fields.put(
                "subAgents",
                List.of(
                        Map.of(
                                "name",
                                "part",
                                "kind",
                                "OTHER",
                                "agent",
                                "example-agent",
                                "model",
                                "model",
                                "brief",
                                "# Brief")));
        assertThat(Issue.of(fields).subAgents().getFirst().kind())
                .as("a newer proposal kind is preserved as OTHER")
                .isEqualTo(SubAgent.Kind.OTHER);
        fields.put("subAgents", List.of(Map.of("name", "part", "kind", "BUILDER")));
        assertThatThrownBy(() -> Issue.of(fields))
                .as("a proposal must carry its agent, model and complete brief")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordReadersRefuseMissingAndMistypedFieldsRatherThanInventingBoardData() {
        assertThatThrownBy(() -> Issue.of(Map.of()))
                .as("missing issue fields cannot become an empty issue")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PlanState.of(Map.of("name", "A", "kind", "arbitrary")).kind())
                .as("unknown state categories remain readable and cannot drive work")
                .isEqualTo(PlanState.Kind.OTHER);
        assertThatThrownBy(
                        () ->
                                Change.of(
                                        Map.of(
                                                "revision",
                                                "r",
                                                "stories",
                                                List.of(1),
                                                "issues",
                                                List.of())))
                .as("resource ids must remain strings")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workflow.of(Map.of("states", List.of(true), "phases", List.of())))
                .as("a malformed state is never treated as an empty workflow")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
