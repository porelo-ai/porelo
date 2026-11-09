package org.fuin.porelo.planning.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.SubAgent;
import org.junit.jupiter.api.Test;

/** These run without sockets as well, so batch safety can be checked in a confined build. */
class StubPlanningTest {
    @Test
    void aMixedBatchAdvancesOnceAndPublishesBothResourcesTogether() throws Exception {
        var adapter = new StubPlanning();
        var story = adapter.getStory(Map.of(), "", "empty");
        try (var watch = adapter.watchChanges(Map.of(), "")) {
            watch.next(1);
            String revision =
                    adapter.setStates(
                            Map.of(),
                            "",
                            List.of(
                                    new StateChange("empty", null, "Working"),
                                    new StateChange(null, "one", "Done")),
                            story.revision());
            assertThat(revision).isEqualTo("1");
            assertThat(adapter.getStory(Map.of(), "", "empty").value().state())
                    .isEqualTo("Working");
            assertThat(adapter.getStory(Map.of(), "", "empty").value().priority())
                    .isEqualTo(story.value().priority());
            assertThat(adapter.getIssue(Map.of(), "", "one").value().state()).isEqualTo("Done");
            var change = watch.next(1);
            assertThat(change.revision()).isEqualTo(revision);
            assertThat(change.stories()).containsExactlyInAnyOrder("empty", "story");
            assertThat(change.issues()).containsExactly("one");
            assertThat(watch.next(1)).isNull();
            assertThat(adapter.listStories(Map.of(), "", null).value().getFirst().priority())
                    .isEqualTo("Highest");
        }
    }

    @Test
    void aRefusedStoryCannotPartiallyMoveAnIssue() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        for (var id : List.of("missing", "story")) {
            assertThatThrownBy(
                            () ->
                                    adapter.setStates(
                                            Map.of(),
                                            "",
                                            List.of(
                                                    new StateChange(null, "one", "Done"),
                                                    new StateChange(id, null, "Done")),
                                            before.revision()))
                    .isInstanceOfSatisfying(
                            PlanningRefused.class,
                            ex -> {
                                assertThat(ex.reason())
                                        .isEqualTo(
                                                id.equals("missing")
                                                        ? PlanningRefused.Reason.NO_SUCH_STORY
                                                        : PlanningRefused.Reason.STORY_HAS_ISSUES);
                                assertThat(ex.parameters()).containsEntry("story", id);
                            });
            assertThat(adapter.getIssue(Map.of(), "", "one")).isEqualTo(before);
        }
    }

    @Test
    void duplicateStoriesAndStaleMixedBatchesLeaveEverythingUnchanged() {
        var adapter = new StubPlanning();
        var before = adapter.getStory(Map.of(), "", "empty");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange("empty", null, "Working"),
                                                new StateChange(null, "one", "Done"),
                                                new StateChange("empty", null, "Done")),
                                        before.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex ->
                                assertThat(ex.parameters())
                                        .containsEntry("item", "empty")
                                        .containsEntry("reason", "Duplicate story"));
        assertThat(adapter.getStory(Map.of(), "", "empty")).isEqualTo(before);
        adapter.externalIssueState("two", "Working");
        var current = adapter.getStory(Map.of(), "", "empty");
        var issue = adapter.getIssue(Map.of(), "", "one");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange("empty", null, "Done"),
                                                new StateChange(null, "one", "Done")),
                                        before.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex -> {
                            assertThat(ex.reason())
                                    .isEqualTo(PlanningRefused.Reason.STALE_REVISION);
                            assertThat(ex.parameters())
                                    .containsEntry("revision", current.revision());
                        });
        assertThat(adapter.getStory(Map.of(), "", "empty")).isEqualTo(current);
        assertThat(adapter.getIssue(Map.of(), "", "one")).isEqualTo(issue);
    }

    @Test
    void aRefusedMixedBatchMovesNothingAndKeepsItsRevision() {
        var adapter = new StubPlanning();
        var story = adapter.getStory(Map.of(), "", "empty");
        var issue = adapter.getIssue(Map.of(), "", "one");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange("empty", null, "Done"),
                                                new StateChange(null, "one", "Absent")),
                                        story.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex -> {
                            assertThat(ex.reason())
                                    .isEqualTo(PlanningRefused.Reason.STATE_NOT_ALLOWED);
                            assertThat(ex.parameters())
                                    .containsEntry("item", "one")
                                    .containsEntry("from", "Backlog")
                                    .containsEntry("to", "Absent")
                                    .containsEntry("reason", "Unknown state");
                        });
        assertThat(adapter.getStory(Map.of(), "", "empty")).isEqualTo(story);
        assertThat(adapter.getIssue(Map.of(), "", "one")).isEqualTo(issue);
    }

    @Test
    void aStagedRemoteChangeCannotBeOverwrittenByALocalWrite() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        adapter.stageIssueState("one", "Done");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(new StateChange(null, "one", "Working")),
                                        before.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex -> {
                            assertThat(ex.reason())
                                    .isEqualTo(PlanningRefused.Reason.STALE_REVISION);
                            assertThat(ex.parameters().get("revision"))
                                    .isNotEqualTo(before.revision());
                        });
        assertThat(adapter.getIssue(Map.of(), "", "one").value().state()).isEqualTo("Done");
    }

    @Test
    void emptyBatchesStillCheckTheCurrentRevisionAndDuplicatesNameTheIssue() {
        var adapter = new StubPlanning();
        String revision = adapter.getWorkflow(Map.of(), "").revision();
        assertThat(adapter.setStates(Map.of(), "", List.of(), revision)).isEqualTo(revision);
        adapter.externalIssueState("one", "Done");
        assertThatThrownBy(() -> adapter.setStates(Map.of(), "", List.of(), revision))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex ->
                                assertThat(ex.parameters())
                                        .containsEntry(
                                                "revision",
                                                adapter.getWorkflow(Map.of(), "").revision()));
        String current = adapter.getWorkflow(Map.of(), "").revision();
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange(null, "one", "Working"),
                                                new StateChange(null, "one", "Done")),
                                        current))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex ->
                                assertThat(ex.parameters())
                                        .containsEntry("item", "one")
                                        .containsEntry("reason", "Duplicate issue"));
        assertThat(adapter.getIssue(Map.of(), "", "one").value().state()).isEqualTo("Done");
    }

    @Test
    void aRefusedBatchIdentifiesTheIssueAndWhyWithoutWriting() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange(null, "one", "Done"),
                                                new StateChange(null, "two", "Absent")),
                                        before.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex ->
                                assertThat(ex.parameters())
                                        .containsEntry("item", "two")
                                        .containsEntry("reason", "Unknown state"));
        assertThat(adapter.getIssue(Map.of(), "", "one")).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(new StateChange("story", null, "Done")),
                                        before.revision()))
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex -> assertThat(ex.reason().wire()).isEqualTo("StoryHasIssues"));
    }

    @Test
    void listStoriesCarriesIssueSummariesAtItsOneRevision() {
        var adapter = new StubPlanning();
        var before = adapter.listStories(Map.of(), "", null);
        var summary = before.value().getFirst().asMap();
        assertThat(summary).containsKey("issues");
        assertThat(summary.get("issues"))
                .isEqualTo(
                        adapter.listStoryIssues(Map.of(), "", "story").value().stream()
                                .map(org.fuin.porelo.planning.api.IssueSummary::asMap)
                                .toList());
        adapter.externalIssueState("one", "Done");
        assertThat(before.value().getFirst().asMap()).isEqualTo(summary);
    }

    @Test
    void aWriteBeforeTheFirstTakeDoesNotReplaceTheSubscriptionBaseline() throws Exception {
        var adapter = new StubPlanning();
        String read = adapter.getWorkflow(Map.of(), "").revision();
        try (var watch = adapter.watchChanges(Map.of(), "")) {
            var write =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> adapter.externalIssueState("one", "Done"));
            write.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(watch.next(1).revision()).isEqualTo(read);
            assertThat(watch.next(1).issues()).containsExactly("one");
        }
    }

    @Test
    void closingABlockedSubscriptionReturnsNoInventedChange() throws Exception {
        var adapter = new StubPlanning();
        var watch = adapter.watchChanges(Map.of(), "");
        watch.next(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var result =
                new java.util.concurrent.CompletableFuture<org.fuin.porelo.planning.api.Change>();
        var reader =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    entered.countDown();
                                    try {
                                        result.complete(watch.next(5000));
                                    } catch (InterruptedException ex) {
                                        result.completeExceptionally(ex);
                                    }
                                });
        try {
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (reader.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until)
                Thread.yield();
            assertThat(reader.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            watch.close();
            assertThat(result.get(1, java.util.concurrent.TimeUnit.SECONDS)).isNull();
        } finally {
            watch.close();
            reader.interrupt();
            reader.join(1000);
        }
    }

    @Test
    void issueBatchesRequireIssuesButEmptyAndStoryBatchesOnlyRequireWrite() {
        for (var capabilities : List.of(List.<String>of(), List.of("write"))) {
            var adapter = new StubPlanning(StubPlanning.defaultWorkflow(), capabilities);
            assertThatThrownBy(
                            () ->
                                    adapter.setStates(
                                            Map.of(),
                                            "",
                                            List.of(new StateChange(null, "one", "Done")),
                                            "0"))
                    .isInstanceOfSatisfying(
                            PlanningRefused.class,
                            ex ->
                                    assertThat(ex.parameters())
                                            .containsEntry(
                                                    "capability",
                                                    capabilities.isEmpty() ? "write" : "issues"));
        }
        var adapter = new StubPlanning(StubPlanning.defaultWorkflow(), List.of("write"));
        assertThat(adapter.setStates(Map.of(), "", List.of(), "0")).isEqualTo("0");
        assertThat(
                        adapter.setStates(
                                Map.of(), "", List.of(new StateChange("empty", null, "Done")), "0"))
                .isEqualTo("1");
    }

    @Test
    void subAgentProposalsRoundTripAllKindsAndCompleteBriefsAndSurviveStateChanges() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        assertThat(before.value().subAgents())
                .as("the fixture proposes all three roles")
                .extracting(SubAgent::kind)
                .containsExactly(SubAgent.Kind.BUILDER, SubAgent.Kind.TESTER, SubAgent.Kind.READER);
        assertThat(before.value().subAgents().getFirst().brief())
                .as("the brief is complete Markdown text")
                .isEqualTo("# Build\n\nImplement the first condition.\nOutcome: done\n");
        assertThat(Issue.of(before.value().asMap()))
                .as("all proposal fields survive encoding and decoding")
                .isEqualTo(before.value());
        adapter.setStates(
                Map.of(), "", List.of(new StateChange(null, "one", "Working")), before.revision());
        assertThat(adapter.getIssue(Map.of(), "", "one").value().subAgents())
                .as("moving an issue retains its proposals")
                .isEqualTo(before.value().subAgents());
    }

    @Test
    void emptyAndMissingSubAgentListsDecodeAsNoProposals() {
        var issue = new StubPlanning().getIssue(Map.of(), "", "two").value();
        assertThat(issue.asMap())
                .as("the server emits an explicit empty list for no proposals")
                .containsEntry("subAgents", List.of());
        assertThat(Issue.of(issue.asMap()).subAgents()).as("an empty list round trips").isEmpty();
        var older = new java.util.LinkedHashMap<>(issue.asMap());
        older.remove("subAgents");
        assertThat(Issue.of(older).subAgents()).as("older adapters may omit the list").isEmpty();
    }

    @Test
    void anUnknownIssueCannotPartiallyApplyABatch() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(
                                                new StateChange(null, "one", "Done"),
                                                new StateChange(null, "missing", "Done")),
                                        before.revision()))
                .as("the entire batch is validated before writing")
                .isInstanceOf(PlanningRefused.class);
        assertThat(adapter.getIssue(Map.of(), "", "one"))
                .as("no partial state or revision change")
                .isEqualTo(before);
    }

    @Test
    void aStaleRevisionCannotMoveAnIssue() {
        var adapter = new StubPlanning();
        var before = adapter.getIssue(Map.of(), "", "one");
        adapter.setStates(
                Map.of(), "", List.of(new StateChange(null, "two", "Done")), before.revision());
        var current = adapter.getIssue(Map.of(), "", "one");
        assertThatThrownBy(
                        () ->
                                adapter.setStates(
                                        Map.of(),
                                        "",
                                        List.of(new StateChange(null, "one", "Done")),
                                        before.revision()))
                .as("old revisions refuse without writing")
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the stale revision is distinguishable")
                                        .isEqualTo(PlanningRefused.Reason.STALE_REVISION));
        assertThat(adapter.getIssue(Map.of(), "", "one"))
                .as("the issue and current revision stay unchanged")
                .isEqualTo(current);
    }

    @Test
    void refreshAndAnExternalChangeReachIndependentWatchers() throws InterruptedException {
        var adapter = new StubPlanning();
        try (var first = adapter.watchChanges(Map.of(), "");
                var second = adapter.watchChanges(Map.of(), "")) {
            first.next(1);
            second.next(1);
            adapter.stageIssueState("one", "Done");
            assertThat(adapter.getIssue(Map.of(), "", "one").value().state())
                    .as("reads do not fetch")
                    .isEqualTo("Backlog");
            String revision = adapter.refresh(Map.of(), "");
            assertThat(first.next(1))
                    .as("refresh publishes to the first watcher")
                    .isNotNull()
                    .extracting(change -> change.revision())
                    .isEqualTo(revision);
            assertThat(second.next(1))
                    .as("refresh publishes to the second watcher")
                    .isNotNull()
                    .extracting(change -> change.revision())
                    .isEqualTo(revision);
            adapter.externalIssueState("two", "Working");
            assertThat(first.next(1))
                    .as("outside changes are also published")
                    .isNotNull()
                    .extracting(change -> change.issues())
                    .isEqualTo(List.of("two"));
        }
        assertThat(adapter.subscribers()).as("closing each watcher releases it").isZero();
    }
}
