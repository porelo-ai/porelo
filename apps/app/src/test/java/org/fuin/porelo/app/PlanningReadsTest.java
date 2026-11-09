package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.PlanningAdapter.Result;
import org.fuin.porelo.planning.api.PlanningException;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.Workflow;
import org.fuin.porelo.wire.planning.PlanIssue;
import org.fuin.porelo.wire.planning.PlanStory;
import org.fuin.porelo.wire.planning.Role;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/** Exercises consistency and error translation without requiring a socket or the real account. */
class PlanningReadsTest {
    private static class Source implements PlanningReads.Source {
        final List<String> calls = new ArrayList<>();
        String revision = "first";
        boolean snapshot = true;
        boolean hasIssues = true;
        String priority = "Highest";
        Workflow workflow =
                new Workflow(
                        List.of(
                                new PlanState("Queue", PlanState.Kind.WAITING),
                                new PlanState("Build", PlanState.Kind.WORKING),
                                new PlanState("Future", PlanState.Kind.OTHER)),
                        List.of());
        List<IssueSummary> issues =
                List.of(
                        new IssueSummary(
                                "issue",
                                "repo",
                                "Title",
                                "Build",
                                "S",
                                List.of(),
                                "https://example.org/issue"));

        public Result<Workflow> workflow() {
            calls.add("workflow");
            return new Result<>(workflow, revision);
        }

        public Result<List<StorySummary>> stories() {
            calls.add("stories");
            return new Result<>(
                    List.of(
                            new StorySummary(
                                    "story",
                                    "Story",
                                    "Queue",
                                    "link",
                                    snapshot ? issues : null,
                                    priority)),
                    revision);
        }

        public Result<Story> story(String id) {
            calls.add("story");
            return new Result<>(
                    new Story(id, "Story", "Queue", "link", "Full text", priority), revision);
        }

        public Result<List<IssueSummary>> issues(String story) {
            calls.add("issues");
            return new Result<>(issues, revision);
        }

        public Result<Issue> issue(String id) {
            calls.add("issue");
            return new Result<>(
                    new Issue(
                            id,
                            "repo",
                            "Title",
                            "Build",
                            "S",
                            List.of(),
                            "link",
                            "Acceptance",
                            List.of()),
                    revision);
        }

        public boolean hasIssues() {
            return hasIssues;
        }
    }

    private static PlanningReads reads(Source source) {
        return new PlanningReads(source, "shop", PlanningReadSnapshot.empty(), 3);
    }

    private static List<PlanStory> board(PlanningReads reads, Map<String, Object> parameters) {
        return ((List<?>) reads.read("ListStories", parameters).get("stories"))
                .stream().map(item -> PlanStory.of((Map<?, ?>) item)).toList();
    }

    @Test
    void everyStoryViewKeepsTheAdaptersOptionalPriority() {
        var source = new Source();
        try (var reads = reads(source)) {
            for (String priority : new String[] {"Highest", null}) {
                source.priority = priority;
                final var listed = board(reads, Map.of()).getFirst();
                final var full =
                        PlanStory.of(
                                (Map<?, ?>)
                                        reads.read("GetStory", Map.of("story", "story"))
                                                .get("story"));
                for (var story : List.of(listed, full)) {
                    if (priority == null) {
                        assertThat(story.asMap()).doesNotContainKey("priority");
                    } else {
                        assertThat(story.asMap()).containsEntry("priority", priority);
                    }
                }
            }
        }
    }

    @Test
    void boardUsesOneListingAndComputesThenFiltersStatesWithoutDetailReads() {
        var source = new Source();
        try (var reads = reads(source)) {
            var stories = board(reads, Map.of("states", List.of("Build")));
            assertThat(source.calls).containsExactly("workflow", "stories");
            assertThat(stories).hasSize(1);
            assertThat(stories.getFirst().state()).isEqualTo("Build");
            assertThat(stories.getFirst().description()).isEmpty();
            assertThat(stories.getFirst().issues().getFirst().roundsMost()).isEqualTo(3);
            assertThat(board(reads, Map.of("states", List.of("Queue")))).isEmpty();
            assertThat(board(reads, Map.of("states", List.of()))).isEmpty();
            assertThat(board(reads, Map.of())).hasSize(1);
            var optional = new LinkedHashMap<String, Object>();
            optional.put("states", null);
            assertThat(board(reads, optional)).hasSize(1);
            var full =
                    PlanStory.of(
                            (Map<?, ?>)
                                    reads.read("GetStory", Map.of("story", "story")).get("story"));
            assertThat(full.description()).isEqualTo("Full text");
            assertThat(full.issues()).isEqualTo(stories.getFirst().issues());
        }
    }

    @Test
    void missingBoardSnapshotRefusesButAnExplicitEmptySnapshotKeepsTheStoredState() {
        var source = new Source();
        source.snapshot = false;
        try (var reads = reads(source)) {
            refusal(
                    () -> board(reads, Map.of()),
                    "NotSupported",
                    Map.of("capability", "board-snapshot"));
            assertThat(source.calls).containsExactly("workflow", "stories");
            source.snapshot = true;
            source.issues = List.of();
            assertThat(board(reads, Map.of()).getFirst().state()).isEqualTo("Queue");
            source.snapshot = false;
            source.hasIssues = false;
            refusal(
                    () -> board(reads, Map.of()),
                    "NotSupported",
                    Map.of("capability", "board-snapshot"));
            source.snapshot = true;
            assertThat(board(reads, Map.of()).getFirst().issues()).isEmpty();
        }
    }

    @Test
    void retriesTheWholeReadWhenWorkflowAndBoardRevisionsDiffer() {
        var source =
                new Source() {
                    public Result<List<StorySummary>> stories() {
                        revision = "current";
                        return super.stories();
                    }
                };
        try (var reads = reads(source)) {
            assertThat(reads.read("ListStories", Map.of())).containsEntry("revision", "current");
            assertThat(source.calls).containsExactly("workflow", "stories", "workflow", "stories");
        }
    }

    @Test
    void repeatedConcurrentChangesRefuseWithTheLatestRevisionWithoutAMixedBoard() {
        var source =
                new Source() {
                    public Result<List<StorySummary>> stories() {
                        revision += "+";
                        return super.stories();
                    }
                };
        try (var reads = reads(source)) {
            refusal(() -> board(reads, Map.of()), "StaleRevision", Map.of("revision", "first+++"));
            assertThat(source.calls)
                    .containsExactly(
                            "workflow", "stories", "workflow", "stories", "workflow", "stories");
        }
    }

    @Test
    void otherStatesAndProposalKindsRemainVisibleAndLocalWorkIsNeverInvented() {
        var source =
                new Source() {
                    public Result<Issue> issue(String id) {
                        var issue =
                                new LinkedHashMap<String, Object>(super.issue(id).value().asMap());
                        issue.put(
                                "subAgents",
                                List.of(
                                        Map.of(
                                                "name",
                                                "future",
                                                "kind",
                                                "NEW_KIND",
                                                "agent",
                                                "example-agent",
                                                "model",
                                                "model",
                                                "brief",
                                                "Complete brief")));
                        return new Result<>(Issue.of(issue), revision);
                    }
                };
        source.issues =
                List.of(
                        new IssueSummary(
                                "issue", "repo", "Title", "Future", "S", List.of(), "link"));
        try (var reads = reads(source)) {
            var state =
                    org.fuin.porelo.wire.planning.PlanState.of(
                            (Map<?, ?>)
                                    ((List<?>)
                                                    reads.read("GetPlanningWorkflow", Map.of())
                                                            .get("states"))
                                            .getLast());
            assertThat(state.kind()).isEqualTo(org.fuin.porelo.wire.planning.PlanState.Kind.OTHER);
            var story = board(reads, Map.of("states", List.of("Future"))).getFirst();
            assertThat(story.state()).isEqualTo("Future");
            assertThat(story.work()).isNull();
            assertThat(story.issues().getFirst().atWork()).isFalse();
            var issue =
                    PlanIssue.of(
                            (Map<?, ?>)
                                    reads.read("GetIssue", Map.of("issue", "issue")).get("issue"));
            assertThat(issue.subAgents()).hasSize(1);
            assertThat(issue.subAgents().getFirst().role()).isEqualTo(Role.OTHER);
            assertThat(issue.subAgents().getFirst().brief()).isEqualTo("Complete brief");
        }
    }

    @Test
    void boardAndDetailViewsKeepTheSameLocalStoppedReadingAndFindings() {
        var source = new Source();
        var finding =
                new org.fuin.porelo.wire.planning.PlanFinding(
                        1,
                        org.fuin.porelo.wire.planning.PlanFinding.Severity.BLOCKING,
                        "Missing condition",
                        null,
                        null);
        var result =
                new org.fuin.porelo.wire.planning.PlanWorkIssue(
                        "issue",
                        2,
                        2,
                        false,
                        true,
                        true,
                        List.of(finding),
                        org.fuin.porelo.wire.planning.PlanWorkIssue.Reason.ROUND_LIMIT);
        var work =
                new org.fuin.porelo.wire.planning.PlanWork(
                        List.of(), null, "", false, List.of(), "", 2, List.of(), List.of(result));
        var local = new PlanningReadSnapshot(Map.of("story", work));
        try (var reads = new PlanningReads(source, "shop", local, 3)) {
            var story = board(reads, Map.of()).getFirst();
            var issue = story.issues().getFirst();
            assertThat(story.work()).isEqualTo(work);
            assertThat(issue.rounds()).isEqualTo(2);
            assertThat(issue.roundsMost()).isEqualTo(2);
            assertThat(issue.readingStopped()).isTrue();
            assertThat(issue.needsYou()).isTrue();
            assertThat(issue.atWork()).isFalse();
            assertThat(issue.findings()).containsExactly(finding);
            var details =
                    PlanStory.of(
                            (Map<?, ?>)
                                    reads.read("GetStory", Map.of("story", "story")).get("story"));
            assertThat(details.issues()).containsExactly(issue);
            var summaries =
                    (List<?>) reads.read("ListStoryIssues", Map.of("story", "story")).get("issues");
            assertThat(PlanIssue.of((Map<?, ?>) summaries.getFirst())).isEqualTo(issue);
            var full =
                    PlanIssue.of(
                            (Map<?, ?>)
                                    reads.read("GetIssue", Map.of("issue", "issue")).get("issue"));
            assertThat(full.findings()).isEqualTo(issue.findings());
            assertThat(full.readingStopped()).isEqualTo(issue.readingStopped());
        }
    }

    @Test
    void absentAndNullProposalsBecomeAnEmptyListInFullIssueReads() {
        for (boolean explicitNull : List.of(false, true)) {
            var source =
                    new Source() {
                        public Result<Issue> issue(String id) {
                            var fields = new LinkedHashMap<>(super.issue(id).value().asMap());
                            fields.remove("subAgents");
                            if (explicitNull) fields.put("subAgents", null);
                            return new Result<>(Issue.of(fields), revision);
                        }
                    };
            try (var reads = reads(source)) {
                var issue =
                        PlanIssue.of(
                                (Map<?, ?>)
                                        reads.read("GetIssue", Map.of("issue", "issue"))
                                                .get("issue"));
                assertThat(issue.subAgents()).isEmpty();
            }
        }
    }

    @Test
    void everyNamedAdapterErrorKeepsItsFieldsAndTheCredentialErrorAvoidsTheVaultName() {
        for (var reason : PlanningRefused.Reason.values()) {
            Map<String, Object> fields =
                    switch (reason) {
                        case STALE_REVISION -> Map.of("revision", "current");
                        case NO_SUCH_STORY, STORY_HAS_ISSUES -> Map.of("story", "story");
                        case NO_SUCH_ISSUE -> Map.of("issue", "issue");
                        case STATE_NOT_ALLOWED ->
                                Map.of(
                                        "item", "issue", "from", "Queue", "to", "Build", "reason",
                                        "Refused");
                        case NOT_SUPPORTED -> Map.of("capability", "issues");
                        case WORKFLOW_MISSING, INVALID_SETTINGS -> Map.of("detail", "Safe detail");
                        case FAILED -> Map.of("message", "Safe explanation");
                        case CREDENTIAL_REFUSED, RATE_LIMITED, NO_SUCH_STORE, UNREACHABLE ->
                                Map.of("detail", "Safe detail", "retryAfter", 37);
                    };
            var source =
                    new Source() {
                        public Result<Workflow> workflow() {
                            throw new PlanningRefused(reason, fields);
                        }
                    };
            try (var reads = reads(source)) {
                var expected = new LinkedHashMap<>(fields);
                if (reason == PlanningRefused.Reason.WORKFLOW_MISSING)
                    expected.put("project", "shop");
                refusal(
                        () -> reads.read("ListStories", Map.of()),
                        reason == PlanningRefused.Reason.CREDENTIAL_REFUSED
                                ? "PlanningCredentialRefused"
                                : reason.wire(),
                        expected);
            }
        }
    }

    @Test
    void unknownNamedErrorsPreserveTheirNameAndNullExtensionFieldsWhileLocalFailuresStaySeparate() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("extension", null);
        fields.put("wait", 29);
        var source =
                new Source() {
                    public Result<Workflow> workflow() {
                        throw new PlanningException(
                                "New remote error", "org.example.Future.NewError", fields);
                    }
                };
        try (var reads = reads(source)) {
            refusal(
                    () -> reads.read("ListStories", Map.of()),
                    "PlanningAdapterError",
                    Map.of(
                            "adapter",
                            "in-memory",
                            "error",
                            "org.example.Future.NewError",
                            "parameters",
                            fields));
        }
        var local =
                new Source() {
                    public Result<Workflow> workflow() {
                        throw new PlanningException("Transport detail");
                    }
                };
        try (var reads = reads(local)) {
            assertThatThrownBy(() -> reads.read("ListStories", Map.of()))
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex -> {
                                assertThat(ex.getErrorName())
                                        .isEqualTo(
                                                TasksContract.INTERFACE
                                                        + ".PlanningAdapterRefused");
                                assertThat(ex.getParameters())
                                        .doesNotContainValue("Transport detail");
                            });
        }
    }

    private static void refusal(Runnable action, String name, Map<String, Object> fields) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(TasksContract.INTERFACE + "." + name);
                            assertThat(ex.getParameters()).isEqualTo(fields);
                        });
    }
}
