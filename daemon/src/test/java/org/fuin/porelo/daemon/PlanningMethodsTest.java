package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.planning.stub.StubPlanning;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the board contract clients generate against and prevents fictional work starts. */
class PlanningMethodsTest {
    @TempDir Path dir;
    private PoreloContext context;
    private final FakeCommandRunner runner = new FakeCommandRunner();
    private Path project;

    @BeforeEach
    void installTheAdapterAndRecordTheProject() throws Exception {
        final XdgPaths absolute =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("d").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("s").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("c").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("r").toString();
                                    default -> null;
                                },
                        dir);
        final Path relative = Path.of("").toRealPath().relativize(dir.toRealPath());
        final XdgPaths xdg =
                new XdgPaths(
                        absolute.config(),
                        absolute.data(),
                        absolute.state(),
                        relative.resolve("r/porelo"),
                        dir);
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n  name: shop\nplanning:\n  adapter: stub-planning\n  settings: {}\n");
        final Path projects = Files.createDirectories(dir.resolve("d/porelo/projects"));
        Files.writeString(projects.resolve("shop"), project.toAbsolutePath() + "\n");
        final Path executable =
                Files.createDirectories(context.paths().planningDirectories().getFirst())
                        .resolve("stub-planning");
        Files.writeString(
                executable,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin/java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + StubPlanning.class.getName()
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }

    private Path socket() throws Exception {
        return Path.of("").toRealPath().relativize(dir.toRealPath()).resolve("sock");
    }

    private interface Calls {
        void with(VarlinkClient client) throws Exception;
    }

    private void serving(Calls calls) throws Exception {
        try (VarlinkServer server = PoreloDaemon.serving(context, socket())) {
            Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                calls.with(client);
            }
        }
    }

    private static Map<String, Object> call(
            VarlinkClient client, String method, Map<String, Object> fields) {
        final var parameters = new java.util.LinkedHashMap<String, Object>(fields);
        parameters.put("project", "shop");
        return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
    }

    @Test
    void readsEveryBoardResourceFromTheInstalledAdapter() throws Exception {
        serving(
                client -> {
                    assertThat(call(client, "GetPlanningWorkflow", Map.of()))
                            .as("workflow comes from the installed adapter")
                            .containsEntry(
                                    "states", StubPlanning.defaultWorkflow().asMap().get("states"));
                    assertThat((List<?>) call(client, "ListStories", Map.of()).get("stories"))
                            .as("the adapter's two stories are visible")
                            .hasSize(2);
                    assertThat(
                                    ((Map<?, ?>)
                                                    call(
                                                                    client,
                                                                    "GetStory",
                                                                    Map.of("story", "story"))
                                                            .get("story"))
                                            .get("link"))
                            .as("the complete story keeps its web link")
                            .isEqualTo("https://example.org/story");
                    assertThat(
                                    (List<?>)
                                            call(
                                                            client,
                                                            "ListStoryIssues",
                                                            Map.of("story", "story"))
                                                    .get("issues"))
                            .as("the story's issues cross the daemon boundary")
                            .hasSize(2);
                    final Map<?, ?> issue =
                            (Map<?, ?>)
                                    call(client, "GetIssue", Map.of("issue", "one")).get("issue");
                    assertThat(issue.get("whatMustBeTrue"))
                            .as("acceptance remains available to the window")
                            .isEqualTo("The first condition holds.");
                    final var decoded = org.fuin.porelo.wire.planning.PlanIssue.of(issue);
                    assertThat(decoded.roundsMost())
                            .as("a no-cycle issue uses the project default")
                            .isEqualTo(2);
                    assertThat(call(client, "GetStoryWork", Map.of("story", "story")))
                            .as("no local work has started")
                            .isEmpty();
                    assertThat(call(client, "WatchStories", Map.of()))
                            .as("a non-streaming board watch reads one snapshot")
                            .isEqualTo(call(client, "ListStories", Map.of()));
                    assertThat((List<?>) issue.get("subAgents"))
                            .as("whole briefs reach the window")
                            .hasSize(3);
                });
    }

    @Test
    void preservesNamedAdapterRefusals() throws Exception {
        serving(
                client -> {
                    refused(
                            client,
                            "GetStory",
                            Map.of("story", "missing"),
                            "NoSuchStory",
                            Map.of("story", "missing"));
                    refused(
                            client,
                            "GetIssue",
                            Map.of("issue", "missing"),
                            "NoSuchIssue",
                            Map.of("issue", "missing"));
                });
    }

    @Test
    void unfinishedActionsRefuseByNameAndLaunchNothing() throws Exception {
        serving(
                client -> {
                    final var before = runner.invocations();
                    for (String method :
                            List.of(
                                    "RefreshStories",
                                    "ProposeStoryWork",
                                    "StartStoryWork",
                                    "StopStoryWork",
                                    "SaveMoves",
                                    "ResolveIssueReading")) {
                        refused(
                                client,
                                method,
                                Map.of(
                                        "story",
                                        "story",
                                        "issue",
                                        "one",
                                        "to",
                                        "Working",
                                        "revision",
                                        "0"),
                                "NotYetBuilt",
                                Map.of("method", method));
                    }
                    assertThat(runner.invocations())
                            .as("refused actions launch no runtime or helper commands")
                            .isEqualTo(before);
                });
    }

    @Test
    void savingMixedMovesRefusesWithoutChangingTheBoardOrLaunchingWork() throws Exception {
        serving(
                client -> {
                    final var board = call(client, "ListStories", Map.of());
                    final var before = runner.invocations();
                    refused(
                            client,
                            "SaveMoves",
                            Map.of(
                                    "moves",
                                    List.of(
                                            Map.of("story", "empty", "to", "Review"),
                                            Map.of("issue", "one", "to", "Review")),
                                    "revision",
                                    board.get("revision")),
                            "NotYetBuilt",
                            Map.of("method", "SaveMoves"));
                    assertThat(runner.invocations()).isEqualTo(before);
                    assertThat(call(client, "ListStories", Map.of())).isEqualTo(board);
                    for (String removed : List.of("MoveStory", "MoveIssues")) {
                        assertThatThrownBy(() -> call(client, removed, Map.of()))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getErrorName())
                                                        .isEqualTo(
                                                                "org.varlink.service.MethodNotFound"));
                    }
                });
    }

    /** Supplies a priority only on the nonempty story, on both adapter read paths. */
    public static final class WithPriority extends StubPlanning {
        public static void main(String[] arguments) {
            org.fuin.porelo.planning.api.PlanningMain.run(new WithPriority(), arguments);
        }

        @Override
        public Result<List<org.fuin.porelo.planning.api.StorySummary>> listStories(
                Map<String, Object> settings,
                String token,
                @org.jspecify.annotations.Nullable List<String> states) {
            final var found = super.listStories(settings, token, states);
            return new Result<>(
                    found.value().stream()
                            .map(
                                    summary ->
                                            org.fuin.porelo.planning.api.StorySummary.of(
                                                    priority(summary.asMap())))
                            .toList(),
                    found.revision());
        }

        @Override
        public Result<org.fuin.porelo.planning.api.Story> getStory(
                Map<String, Object> settings, String token, String story) {
            final var found = super.getStory(settings, token, story);
            return new Result<>(
                    org.fuin.porelo.planning.api.Story.of(priority(found.value().asMap())),
                    found.revision());
        }

        private static Map<String, Object> priority(Map<String, Object> original) {
            final var fields = new java.util.LinkedHashMap<>(original);
            if ("story".equals(fields.get("id"))) {
                fields.put("priority", "Highest");
            } else {
                fields.remove("priority");
            }
            return fields;
        }
    }

    @Test
    void priorityReachesListsDetailsAndWatchesWithoutInventingAnAbsentLabel() throws Exception {
        executable(WithPriority.class);
        serving(
                client -> {
                    for (String method : List.of("ListStories", "WatchStories")) {
                        for (Object value :
                                (List<?>) call(client, method, Map.of()).get("stories")) {
                            final var story =
                                    org.fuin.porelo.wire.planning.PlanStory.of((Map<?, ?>) value);
                            final var detail =
                                    org.fuin.porelo.wire.planning.PlanStory.of(
                                            (Map<?, ?>)
                                                    call(
                                                                    client,
                                                                    "GetStory",
                                                                    Map.of("story", story.id()))
                                                            .get("story"));
                            assertThat(detail.priority()).isEqualTo(story.priority());
                            if (story.id().equals("story")) {
                                assertThat(story.priority()).isEqualTo("Highest");
                            } else {
                                assertThat(story.asMap()).doesNotContainKey("priority");
                                assertThat(detail.asMap()).doesNotContainKey("priority");
                            }
                        }
                    }
                });
    }

    @Test
    void missingPlanningAndAnUnavailableAdapterStayDistinct() throws Exception {
        Files.writeString(project, "project:\n  name: shop\n");
        serving(
                client ->
                        refused(
                                client,
                                "GetPlanningWorkflow",
                                Map.of(),
                                "PlanningNotSet",
                                Map.of("project", "shop")));
        Files.writeString(
                project, "project:\n  name: shop\nplanning:\n  adapter: absent-adapter\n");
        serving(
                client ->
                        refused(
                                client,
                                "GetPlanningWorkflow",
                                Map.of(),
                                "PlanningAdapterRefused",
                                Map.of("adapter", "absent-adapter")));
    }

    /** Serves a project with no workflow using the installed adapter protocol. */
    public static final class MissingWorkflow {
        /** Starts the fixture on the requested owner-only socket. */
        public static void main(String[] arguments) {
            org.fuin.porelo.planning.api.PlanningMain.run(
                    new StubPlanning(null, List.of("issues")), arguments);
        }
    }

    /** Serves a read adapter without optional issue support. */
    public static final class WithoutIssues {
        /** Starts the fixture on the requested owner-only socket. */
        public static void main(String[] arguments) {
            org.fuin.porelo.planning.api.PlanningMain.run(
                    new StubPlanning(StubPlanning.defaultWorkflow(), List.of()), arguments);
        }
    }

    private void executable(Class<?> main) throws Exception {
        final Path executable =
                context.paths().planningDirectories().getFirst().resolve("stub-planning");
        Files.writeString(
                executable,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin/java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + main.getName()
                        + "' \"$@\"\n");
    }

    @Test
    void aMissingWorkflowIsNamedRatherThanAnEmptyBoard() throws Exception {
        executable(MissingWorkflow.class);
        serving(
                client -> {
                    for (String method :
                            List.of(
                                    "GetPlanningWorkflow",
                                    "ListStories",
                                    "GetStory",
                                    "ListStoryIssues",
                                    "GetIssue")) {
                        refused(
                                client,
                                method,
                                Map.of("story", "story", "issue", "one"),
                                "WorkflowMissing",
                                Map.of("project", "shop"));
                    }
                });
    }

    @Test
    void anUnsupportedIssueReadStaysNamedAndIssueLessReadsStillWork() throws Exception {
        executable(WithoutIssues.class);
        serving(
                client -> {
                    assertThat((List<?>) call(client, "ListStories", Map.of()).get("stories"))
                            .as("adapters without issue support still serve stories")
                            .hasSize(2);
                    refused(
                            client,
                            "GetIssue",
                            Map.of("issue", "one"),
                            "NotSupported",
                            Map.of("capability", "issues"));
                    refused(
                            client,
                            "ListStoryIssues",
                            Map.of("story", "story"),
                            "NotSupported",
                            Map.of("capability", "issues"));
                });
    }

    @Test
    void aCredentialConfigurationIsRefusedWithoutOpeningAnyVault() throws Exception {
        Files.writeString(
                project,
                "project:\n  name: shop\nplanning:\n  adapter: stub-planning\n  credential: token\n");
        serving(
                client ->
                        refused(
                                client,
                                "GetPlanningWorkflow",
                                Map.of(),
                                "NotYetBuilt",
                                Map.of("method", "PlanningCredentials")));
    }

    @Test
    void anEmptyStateFilterReturnsNoStories() throws Exception {
        serving(
                client ->
                        assertThat(
                                        (List<?>)
                                                call(
                                                                client,
                                                                "ListStories",
                                                                Map.of("states", List.of()))
                                                        .get("stories"))
                                .as("empty is distinct from an absent filter")
                                .isEmpty());
    }

    @Test
    void aStreamingBoardWatchRefusesRatherThanPretendingToSubscribe() throws Exception {
        serving(
                client ->
                        assertThatThrownBy(
                                        () ->
                                                client.callMore(
                                                        PoreloDaemon.INTERFACE + ".WatchStories",
                                                        Map.of("project", "shop"),
                                                        reply -> true))
                                .as("a stream is not silently answered as a completed subscription")
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".NotYetBuilt")));
    }

    @Test
    void configuredReadingDefaultsReachAllNestedIssueViews() throws Exception {
        Files.writeString(
                project,
                "project:\n  name: shop\nplanning:\n  adapter: stub-planning\n  reading: {rounds: 4}\n");
        serving(
                client -> {
                    final var list =
                            (List<?>)
                                    call(client, "ListStoryIssues", Map.of("story", "story"))
                                            .get("issues");
                    assertThat(
                                    org.fuin.porelo.wire.planning.PlanIssue.of(
                                                    (Map<?, ?>) list.getFirst())
                                            .roundsMost())
                            .as("a no-cycle issue uses the configured project limit")
                            .isEqualTo(4);
                    final var story =
                            org.fuin.porelo.wire.planning.PlanStory.of(
                                    (Map<?, ?>)
                                            call(client, "GetStory", Map.of("story", "story"))
                                                    .get("story"));
                    assertThat(story.issues())
                            .as("nested issue summaries are the same enriched records")
                            .allSatisfy(
                                    issue ->
                                            assertThat(issue.roundsMost())
                                                    .as("configured per-issue default")
                                                    .isEqualTo(4));
                });
    }

    @Test
    void stoppedReadingAndFindingsAgreeInEveryViewAndLocalWork() throws Exception {
        final var finding =
                new org.fuin.porelo.wire.planning.PlanFinding(
                        1,
                        org.fuin.porelo.wire.planning.PlanFinding.Severity.BLOCKING,
                        "Missing check",
                        null,
                        null);
        final var result =
                new org.fuin.porelo.wire.planning.PlanWorkIssue(
                        "one",
                        2,
                        2,
                        false,
                        true,
                        true,
                        List.of(finding),
                        org.fuin.porelo.wire.planning.PlanWorkIssue.Reason.ROUND_LIMIT);
        final var work =
                new org.fuin.porelo.wire.planning.PlanWork(
                        List.of(), null, "", false, List.of(), "", 2, List.of(), List.of(result));
        final var local = new org.fuin.porelo.app.PlanningReadSnapshot(Map.of("story", work));
        try (var server = new VarlinkServer(socket(), PoreloDaemon.INTERFACE)) {
            PlanningMethods.register(
                    server,
                    context,
                    org.fuin.porelo.app.TaskInventory.deriving(context),
                    new org.fuin.porelo.app.TaskControl(context),
                    project -> local);
            Thread.ofPlatform().daemon().start(server);
            try (var client = new VarlinkClient(server.socketPath())) {
                final var full =
                        org.fuin.porelo.wire.planning.PlanIssue.of(
                                (Map<?, ?>)
                                        call(client, "GetIssue", Map.of("issue", "one"))
                                                .get("issue"));
                assertThat(full.rounds())
                        .as("consumed rounds are not reset by a read")
                        .isEqualTo(2);
                assertThat(full.readingStopped())
                        .as("the local record determines the stop")
                        .isTrue();
                assertThat(full.needsYou())
                        .as("the daemon answers who needs the operator")
                        .isTrue();
                assertThat(full.atWork()).as("exhausted reading launches nobody").isFalse();
                assertThat(full.findings())
                        .as("optional finding locations survive the wire")
                        .containsExactly(finding);
                final var summary =
                        (List<?>)
                                call(client, "ListStoryIssues", Map.of("story", "story"))
                                        .get("issues");
                for (String method : List.of("GetStory", "ListStories", "WatchStories")) {
                    final var answer = call(client, method, Map.of("story", "story"));
                    final Map<?, ?> item =
                            method.equals("GetStory")
                                    ? (Map<?, ?>) answer.get("story")
                                    : (Map<?, ?>) ((List<?>) answer.get("stories")).getFirst();
                    final var story = org.fuin.porelo.wire.planning.PlanStory.of(item);
                    assertThat(story.issues().getFirst())
                            .as("nested %s shares the same local snapshot", method)
                            .isEqualTo(
                                    org.fuin.porelo.wire.planning.PlanIssue.of(
                                            (Map<?, ?>) summary.getFirst()));
                }
                assertThat(
                                org.fuin.porelo.wire.planning.PlanWork.of(
                                        (Map<?, ?>)
                                                call(
                                                                client,
                                                                "GetStoryWork",
                                                                Map.of("story", "story"))
                                                        .get("work")))
                        .as("the work result and issue views share one record")
                        .isEqualTo(work);
            }
        }
    }

    /** Stores a deliberately stale story summary alongside changed issue states. */
    public static final class ChangedIssues {
        /** Starts the fixture after two changes observed by the adapter. */
        public static void main(String[] arguments) {
            final var adapter = new StubPlanning();
            adapter.externalIssueState("one", "Working");
            adapter.externalIssueState("two", "Done");
            org.fuin.porelo.planning.api.PlanningMain.run(adapter, arguments);
        }
    }

    @Test
    void filtersByComputedIssueStateAndNeverInfersLocalWorkFromIt() throws Exception {
        executable(ChangedIssues.class);
        serving(
                client -> {
                    final var story =
                            org.fuin.porelo.wire.planning.PlanStory.of(
                                    (Map<?, ?>)
                                            call(client, "GetStory", Map.of("story", "story"))
                                                    .get("story"));
                    assertThat(story.state())
                            .as("the adapter's stored Backlog summary is superseded by issue order")
                            .isEqualTo("Working");
                    final var stories =
                            (List<?>)
                                    call(
                                                    client,
                                                    "ListStories",
                                                    Map.of("states", List.of("Working")))
                                            .get("stories");
                    assertThat(stories)
                            .as("filtering happens after computing the story state")
                            .hasSize(1);
                    assertThat(
                                    org.fuin.porelo.wire.planning.PlanStory.of(
                                                    (Map<?, ?>) stories.getFirst())
                                            .id())
                            .as("the working story is selected")
                            .isEqualTo("story");
                    assertThat(story.issues())
                            .as("adapter working state alone supplies no local work result")
                            .allSatisfy(
                                    issue -> {
                                        assertThat(issue.rounds())
                                                .as("no locally recorded readings")
                                                .isZero();
                                        assertThat(issue.atWork())
                                                .as("no locally recorded participant")
                                                .isFalse();
                                        assertThat(issue.needsYou())
                                                .as("no locally judged outcome")
                                                .isFalse();
                                    });
                });
    }

    /** A listing snapshot must suffice even when detail reads cannot answer. */
    public static class SnapshotOnly extends StubPlanning {
        public static void main(String[] arguments) {
            var adapter = new SnapshotOnly();
            adapter.externalIssueState("one", "Working");
            adapter.externalIssueState("two", "Done");
            org.fuin.porelo.planning.api.PlanningMain.run(adapter, arguments);
        }

        @Override
        public Result<List<org.fuin.porelo.planning.api.StorySummary>> listStories(
                Map<String, Object> settings,
                String token,
                @org.jspecify.annotations.Nullable List<String> states) {
            if (states != null)
                throw new AssertionError("Computed states must be filtered by porelod");
            return super.listStories(settings, token, states);
        }

        @Override
        public Result<org.fuin.porelo.planning.api.Story> getStory(
                Map<String, Object> settings, String token, String story) {
            throw new AssertionError("Board listing must not read story details");
        }

        @Override
        public Result<List<org.fuin.porelo.planning.api.IssueSummary>> listStoryIssues(
                Map<String, Object> settings, String token, String story) {
            throw new AssertionError("Board listing must use the supplied issue snapshot");
        }
    }

    @Test
    void aBoardSnapshotNeedsNoPerStoryCallsOrStoredStateFilter() throws Exception {
        executable(SnapshotOnly.class);
        serving(
                client -> {
                    var stories =
                            (List<?>)
                                    call(
                                                    client,
                                                    "ListStories",
                                                    Map.of("states", List.of("Working")))
                                            .get("stories");
                    assertThat(stories).hasSize(1);
                    var story =
                            org.fuin.porelo.wire.planning.PlanStory.of(
                                    (Map<?, ?>) stories.getFirst());
                    assertThat(story.state()).isEqualTo("Working");
                    assertThat(story.issues()).hasSize(2);
                    assertThat(story.description()).isEmpty();
                });
    }

    /** An older adapter has stories but no immutable issue snapshot on its listing. */
    public static final class WithoutSnapshot extends SnapshotOnly {
        public static void main(String[] arguments) {
            org.fuin.porelo.planning.api.PlanningMain.run(new WithoutSnapshot(), arguments);
        }

        @Override
        public Result<List<org.fuin.porelo.planning.api.StorySummary>> listStories(
                Map<String, Object> settings,
                String token,
                @org.jspecify.annotations.Nullable List<String> states) {
            var result = super.listStories(settings, token, states);
            return new Result<>(
                    result.value().stream()
                            .map(
                                    story ->
                                            new org.fuin.porelo.planning.api.StorySummary(
                                                    story.id(),
                                                    story.title(),
                                                    story.state(),
                                                    story.link()))
                            .toList(),
                    result.revision());
        }
    }

    @Test
    void anOlderBoardWithoutItsSnapshotRefusesByCapabilityInsteadOfReadingEveryStory()
            throws Exception {
        executable(WithoutSnapshot.class);
        serving(
                client ->
                        refused(
                                client,
                                "ListStories",
                                Map.of(),
                                "NotSupported",
                                Map.of("capability", "board-snapshot")));
    }

    /** Serves named store errors from a deterministic settings selector. */
    public static final class StoreErrors extends StubPlanning {
        public static void main(String[] arguments) {
            org.fuin.porelo.planning.api.PlanningMain.run(new StoreErrors(), arguments);
        }

        @Override
        public Result<org.fuin.porelo.planning.api.Workflow> getWorkflow(
                Map<String, Object> settings, String token) {
            var name = (String) settings.get("error");
            var reason = org.fuin.porelo.planning.api.PlanningRefused.Reason.valueOf(name);
            throw new org.fuin.porelo.planning.api.PlanningRefused(
                    reason, Map.of("detail", "Safe store explanation", "retryAfter", 41));
        }
    }

    @Test
    void storeRefusalsRemainDistinctWithTheirWaitOverTheSocket() throws Exception {
        executable(StoreErrors.class);
        for (String reason :
                List.of("CREDENTIAL_REFUSED", "RATE_LIMITED", "NO_SUCH_STORE", "UNREACHABLE")) {
            Files.writeString(
                    project,
                    "project:\n  name: shop\nplanning:\n  adapter: stub-planning\n  settings: {error: "
                            + reason
                            + "}\n");
            String wire =
                    switch (reason) {
                        case "CREDENTIAL_REFUSED" -> "PlanningCredentialRefused";
                        case "RATE_LIMITED" -> "RateLimited";
                        case "NO_SUCH_STORE" -> "NoSuchStore";
                        default -> "Unreachable";
                    };
            serving(
                    client ->
                            refused(
                                    client,
                                    "ListStories",
                                    Map.of(),
                                    wire,
                                    Map.of("detail", "Safe store explanation", "retryAfter", 41L)));
        }
    }

    /** A newer adapter's states and proposals are visible but cannot authorize work. */
    public static final class FutureKinds extends StubPlanning {
        public FutureKinds() {
            super(
                    new org.fuin.porelo.planning.api.Workflow(
                            List.of(
                                    new org.fuin.porelo.planning.api.PlanState(
                                            "Backlog",
                                            org.fuin.porelo.planning.api.PlanState.Kind.WAITING),
                                    new org.fuin.porelo.planning.api.PlanState(
                                            "Future",
                                            org.fuin.porelo.planning.api.PlanState.Kind.OTHER)),
                            List.of()),
                    List.of("issues", "write"));
        }

        public static void main(String[] arguments) {
            var adapter = new FutureKinds();
            adapter.externalIssueState("one", "Future");
            adapter.externalIssueState("two", "Future");
            org.fuin.porelo.planning.api.PlanningMain.run(adapter, arguments);
        }

        @Override
        public Result<org.fuin.porelo.planning.api.Issue> getIssue(
                Map<String, Object> settings, String token, String id) {
            var found = super.getIssue(settings, token, id);
            var fields = new java.util.LinkedHashMap<>(found.value().asMap());
            fields.put(
                    "subAgents",
                    List.of(
                            Map.of(
                                    "name",
                                    "future",
                                    "kind",
                                    "FUTURE",
                                    "agent",
                                    "example-agent",
                                    "model",
                                    "model",
                                    "brief",
                                    "Whole brief")));
            return new Result<>(org.fuin.porelo.planning.api.Issue.of(fields), found.revision());
        }
    }

    @Test
    void otherKindsAreShownButEveryStartAndMoveRefusesWithoutLaunching() throws Exception {
        executable(FutureKinds.class);
        serving(
                client -> {
                    var before = runner.invocations();
                    var workflow = call(client, "GetPlanningWorkflow", Map.of());
                    assertThat(
                                    ((Map<?, ?>) ((List<?>) workflow.get("states")).getLast())
                                            .get("kind"))
                            .isEqualTo("OTHER");
                    var stories =
                            (List<?>)
                                    call(client, "ListStories", Map.of("states", List.of("Future")))
                                            .get("stories");
                    assertThat(
                                    org.fuin.porelo.wire.planning.PlanStory.of(
                                                    (Map<?, ?>) stories.getFirst())
                                            .state())
                            .isEqualTo("Future");
                    var issue =
                            org.fuin.porelo.wire.planning.PlanIssue.of(
                                    (Map<?, ?>)
                                            call(client, "GetIssue", Map.of("issue", "one"))
                                                    .get("issue"));
                    assertThat(issue.subAgents().getFirst().role())
                            .isEqualTo(org.fuin.porelo.wire.planning.Role.OTHER);
                    for (String method :
                            List.of("ProposeStoryWork", "StartStoryWork", "SaveMoves")) {
                        refused(
                                client,
                                method,
                                Map.of("story", "story", "to", "Future", "revision", "0"),
                                "NotYetBuilt",
                                Map.of("method", method));
                    }
                    assertThat(runner.invocations()).isEqualTo(before);
                });
    }

    private static void refused(
            VarlinkClient client,
            String method,
            Map<String, Object> fields,
            String error,
            Map<String, Object> parameters) {
        assertThatThrownBy(() -> call(client, method, fields))
                .as("a contract refusal stays named over the real socket")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("qualified error name")
                                    .isEqualTo(PoreloDaemon.INTERFACE + "." + error);
                            assertThat(ex.getParameters())
                                    .as("typed refusal fields")
                                    .containsAllEntriesOf(parameters);
                        });
    }
}
