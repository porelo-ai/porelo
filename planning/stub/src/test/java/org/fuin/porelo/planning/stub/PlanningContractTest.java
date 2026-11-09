package org.fuin.porelo.planning.stub;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.fuin.porelo.planning.api.Change;
import org.fuin.porelo.planning.api.InstalledPlanning;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningMain;
import org.fuin.porelo.planning.api.PlanningProtocol;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.SubAgent;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real sockets guard the external contract against partial writes and lost or misread board data.
 */
@Timeout(20)
class PlanningContractTest {
    @TempDir Path directory;
    private static final Map<String, Object> SETTINGS = fixtureSettings();

    private static Map<String, Object> fixtureSettings() {
        var settings =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of("repository", "project", "nested", Map.of("flag", true)));
        settings.put("optional", null);
        return java.util.Collections.unmodifiableMap(settings);
    }

    private static final String TOKEN = "synthetic-call-token";

    @Test
    void setStatesMovesAStoryAndAnIssueWithOneRevisionAndKeepsPriority() throws IOException {
        try (var client = client("normal")) {
            var before = client.getStory(SETTINGS, TOKEN, "empty");
            String revision =
                    client.setStates(
                            SETTINGS,
                            TOKEN,
                            List.of(
                                    new StateChange("empty", null, "Working"),
                                    new StateChange(null, "one", "Done")),
                            before.revision());
            var story = client.getStory(SETTINGS, TOKEN, "empty");
            var issue = client.getIssue(SETTINGS, TOKEN, "one");
            assertThat(story.value().state()).isEqualTo("Working");
            assertThat(story.value().priority()).isEqualTo("Low");
            assertThat(issue.value().state()).isEqualTo("Done");
            assertThat(story.revision()).isEqualTo(revision).isEqualTo("1");
            assertThat(issue.revision()).isEqualTo(revision);
            assertThat(client.listStories(SETTINGS, TOKEN, null).value())
                    .extracting(StorySummary::priority)
                    .containsExactly("Highest", "Low");
        }
    }

    @Test
    void aRefusedMixedBatchMovesNothingAndKeepsItsRevision() throws IOException {
        try (var client = client("normal")) {
            var story = client.getStory(SETTINGS, TOKEN, "empty");
            var issue = client.getIssue(SETTINGS, TOKEN, "one");
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(
                                            new StateChange("empty", null, "Done"),
                                            new StateChange(null, "one", "Absent")),
                                    story.revision()),
                    PlanningRefused.Reason.STATE_NOT_ALLOWED,
                    Map.of(
                            "item",
                            "one",
                            "from",
                            "Backlog",
                            "to",
                            "Absent",
                            "reason",
                            "Unknown state"));
            assertThat(client.getStory(SETTINGS, TOKEN, "empty")).isEqualTo(story);
            assertThat(client.getIssue(SETTINGS, TOKEN, "one")).isEqualTo(issue);
        }
    }

    @Test
    void unknownNamedErrorsKeepTheirFieldsForANewerCaller() throws IOException {
        try (var client = client("error-FutureError")) {
            assertThatThrownBy(() -> client.getWorkflow(SETTINGS, TOKEN))
                    .isInstanceOfSatisfying(
                            org.fuin.porelo.planning.api.PlanningException.class,
                            ex -> {
                                assertThat(ex.errorName())
                                        .isEqualTo(PlanningProtocol.INTERFACE + ".FutureError");
                                assertThat(ex.parameters())
                                        .containsEntry("detail", "Safe store detail");
                            });
        }
    }

    @Test
    void boardSummariesAndAWatchBaselineCloseTheReadToSubscribeGap() throws Exception {
        try (var client = client("normal")) {
            var read = client.listStories(SETTINGS, TOKEN, null);
            assertThat(read.value().getFirst().issues()).hasSize(2);
            String moved =
                    client.setStates(
                            SETTINGS,
                            TOKEN,
                            List.of(new StateChange(null, "one", "Done")),
                            read.revision());
            var first = new java.util.concurrent.atomic.AtomicReference<Change>();
            client.watchChanges(
                    SETTINGS,
                    TOKEN,
                    change -> {
                        first.set(change);
                        return false;
                    });
            assertThat(first.get().revision()).isEqualTo(moved).isNotEqualTo(read.revision());
            assertThat(
                            client.listStories(SETTINGS, TOKEN, null)
                                    .value()
                                    .getFirst()
                                    .issues()
                                    .getFirst()
                                    .state())
                    .isEqualTo("Done");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CredentialRefused",
                "RateLimited",
                "NoSuchStore",
                "Unreachable",
                "InvalidSettings",
                "Failed"
            })
    void storeFailuresRetainTheirNamesAndParameters(String error) throws IOException {
        try (var client = client("error-" + error)) {
            assertThatThrownBy(() -> client.getWorkflow(SETTINGS, TOKEN))
                    .isInstanceOfSatisfying(
                            PlanningRefused.class,
                            ex -> {
                                assertThat(ex.reason().wire()).isEqualTo(error);
                                assertThat(ex.parameters())
                                        .containsEntry(
                                                error.equals("Failed") ? "message" : "detail",
                                                "Safe store detail");
                                if (!List.of("Failed", "InvalidSettings").contains(error))
                                    assertThat(ex.parameters()).containsEntry("retryAfter", 17L);
                            });
        }
    }

    @Test
    void closingCancelsABlockedCallWithoutWaitingForItsDeadline() throws Exception {
        var client = client("blocked");
        var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var call = executor.submit(() -> client.getWorkflow(SETTINGS, TOKEN));
            Path entered = directory.resolve("project/blocked");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(entered) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(entered).exists();
            assertThat(executor.submit(client::describe).get(1, TimeUnit.SECONDS).name())
                    .isEqualTo("stub-planning");
            executor.submit(client::close).get(1, TimeUnit.SECONDS);
            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        } finally {
            client.close();
            executor.shutdownNow();
        }
    }

    @Test
    void theNextCallRestartsADeadAdapterAndCancelsItsWatch() throws Exception {
        try (var client = client("normal")) {
            var first = new CompletableFuture<Change>();
            var executor =
                    Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                var watching =
                        executor.submit(
                                () ->
                                        client.watchChanges(
                                                SETTINGS,
                                                TOKEN,
                                                change -> {
                                                    first.complete(change);
                                                    return true;
                                                }));
                first.get(5, TimeUnit.SECONDS);
                Path pid = directory.resolve("project/pid");
                long old = Long.parseLong(Files.readString(pid));
                var process = ProcessHandle.of(old).orElseThrow();
                process.destroyForcibly();
                process.onExit().get(5, TimeUnit.SECONDS);
                assertThat(client.getWorkflow(SETTINGS, TOKEN).value())
                        .isEqualTo(StubPlanning.defaultWorkflow());
                assertThat(Files.readString(pid)).isNotEqualTo(Long.toString(old));
                assertThatThrownBy(() -> watching.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void anUnknownIssueInABatchLeavesTheOtherIssueAndRevisionUnchanged() throws IOException {
        try (var client = client("normal")) {
            var before = client.getIssue(SETTINGS, TOKEN, "one");
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(
                                            new StateChange(null, "one", "Done"),
                                            new StateChange(null, "missing", "Done")),
                                    before.revision()),
                    PlanningRefused.Reason.NO_SUCH_ISSUE,
                    Map.of("issue", "missing"));
            assertThat(client.getIssue(SETTINGS, TOKEN, "one"))
                    .as("the valid first move and revision remain untouched by a refused batch")
                    .isEqualTo(before);
        }
    }

    @Test
    void anOldRevisionRefusesTheWholeBatch() throws IOException {
        try (var client = client("normal")) {
            String old = client.getWorkflow(SETTINGS, TOKEN).revision();
            String current =
                    client.setStates(
                            SETTINGS, TOKEN, List.of(new StateChange(null, "two", "Working")), old);
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(new StateChange(null, "one", "Done")),
                                    old),
                    PlanningRefused.Reason.STALE_REVISION,
                    Map.of("revision", current));
            assertThat(client.getIssue(SETTINGS, TOKEN, "one").value().state())
                    .as("a stale move writes nothing")
                    .isEqualTo("Backlog");
        }
    }

    @Test
    void describeCarriesCapabilitiesAndAcceptedCredentialKinds() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.describe())
                    .as("the socket handshake exposes all five description fields")
                    .isEqualTo(
                            new InstalledPlanning.Description(
                                    1,
                                    "stub-planning",
                                    "1",
                                    List.of("write", "watch", "issues"),
                                    List.of("github-app", "token")));
        }
    }

    @Test
    void getWorkflowPreservesTheSharedStatesAndPhasesInOrder() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.getWorkflow(SETTINGS, TOKEN).value())
                    .as("the adapter's workflow is shared and ordered")
                    .isEqualTo(StubPlanning.defaultWorkflow());
        }
    }

    @Test
    void listStoriesDistinguishesNoFilterFromAnEmptyFilter() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.listStories(SETTINGS, TOKEN, null).value())
                    .as("an absent filter lists every story")
                    .extracting(StorySummary::id)
                    .containsExactly("story", "empty");
            assertThat(client.listStories(SETTINGS, TOKEN, List.of()).value())
                    .as("an empty filter selects none")
                    .isEmpty();
            assertThat(client.listStories(SETTINGS, TOKEN, List.of("Done")).value())
                    .as("states are filtered by their own names")
                    .isEmpty();
        }
    }

    @Test
    void getStoryPreservesItsResourceFieldsAndDescription() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.getStory(SETTINGS, TOKEN, "story").value())
                    .as("detail survives the round trip")
                    .isEqualTo(
                            new Story(
                                    "story",
                                    "A story",
                                    "Backlog",
                                    "https://example.org/story",
                                    "Story text",
                                    "Highest"));
        }
    }

    @Test
    void listStoryIssuesKeepsRepositoryKeysAndDependencies() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.listStoryIssues(SETTINGS, TOKEN, "story").value())
                    .as("summaries preserve routing and dependency fields")
                    .containsExactly(
                            new IssueSummary(
                                    "one",
                                    "service",
                                    "First issue",
                                    "Backlog",
                                    "S",
                                    List.of(),
                                    "https://example.org/one"),
                            new IssueSummary(
                                    "two",
                                    "client",
                                    "Second issue",
                                    "Backlog",
                                    "M",
                                    List.of("one"),
                                    "https://example.org/two"));
        }
    }

    @Test
    void getIssueIncludesWhatMustBeTrue() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.getIssue(SETTINGS, TOKEN, "two").value())
                    .as("the window can show acceptance text and repository identity")
                    .isEqualTo(
                            new Issue(
                                    "two",
                                    "client",
                                    "Second issue",
                                    "Backlog",
                                    "M",
                                    List.of("one"),
                                    "https://example.org/two",
                                    "The second condition holds.",
                                    List.of()));
        }
    }

    @Test
    void getIssueRoundTripsAllSubAgentKindsAndCompleteMarkdownBriefs() throws IOException {
        try (var client = client("normal")) {
            var issue = client.getIssue(SETTINGS, TOKEN, "one");
            assertThat(issue.value().subAgents())
                    .as("proposals preserve every field and the complete multiline Markdown")
                    .containsExactly(
                            new SubAgent(
                                    "builder",
                                    SubAgent.Kind.BUILDER,
                                    "example-agent",
                                    "build-model",
                                    "# Build\n\nImplement the first condition.\nOutcome: done\n"),
                            new SubAgent(
                                    "tester",
                                    SubAgent.Kind.TESTER,
                                    "example-agent",
                                    "test-model",
                                    "# Test\n\nCheck the first condition.\nOutcome: done\n"),
                            new SubAgent(
                                    "reader",
                                    SubAgent.Kind.READER,
                                    "example-agent",
                                    "read-model",
                                    "# Read\n\nRead against the acceptance.\nOutcome: done\n"));
            client.setStates(
                    SETTINGS,
                    TOKEN,
                    List.of(new StateChange(null, "one", "Working")),
                    issue.revision());
            assertThat(client.getIssue(SETTINGS, TOKEN, "one").value().subAgents())
                    .as("moving the issue preserves its proposals")
                    .isEqualTo(issue.value().subAgents());
        }
    }

    @Test
    void getIssueWithoutProposalsRoundTripsAnEmptyList() throws IOException {
        try (var client = client("normal")) {
            assertThat(client.getIssue(SETTINGS, TOKEN, "two").value().subAgents())
                    .as("an issue without proposals answers an empty list")
                    .isEmpty();
        }
    }

    @Test
    void getIssueFromAnOlderAdapterDecodesAMissingSubAgentListAsEmpty() throws IOException {
        try (var client = client("missing-subagents")) {
            assertThat(client.getIssue(SETTINGS, TOKEN, "one").value().subAgents())
                    .as("an older GetIssue reply without subAgents remains readable")
                    .isEmpty();
        }
    }

    @Test
    void setStatesMovesAnIssueLessStory() throws IOException {
        try (var client = client("normal")) {
            String old = client.getStory(SETTINGS, TOKEN, "empty").revision();
            String revision =
                    client.setStates(
                            SETTINGS, TOKEN, List.of(new StateChange("empty", null, "Done")), old);
            var result = client.getStory(SETTINGS, TOKEN, "empty");
            assertThat(result.value().state()).as("the requested story is moved").isEqualTo("Done");
            assertThat(result.revision())
                    .as("the new snapshot has the write's revision")
                    .isEqualTo(revision)
                    .isNotEqualTo(old);
        }
    }

    @Test
    void setStatesMovesTheWholeBatchWithOneRevision() throws IOException {
        try (var client = client("normal")) {
            String old = client.getWorkflow(SETTINGS, TOKEN).revision();
            String revision =
                    client.setStates(
                            SETTINGS,
                            TOKEN,
                            List.of(
                                    new StateChange(null, "one", "Working"),
                                    new StateChange(null, "two", "Done")),
                            old);
            var first = client.getIssue(SETTINGS, TOKEN, "one");
            var second = client.getIssue(SETTINGS, TOKEN, "two");
            assertThat(first.value().state()).as("first move applied").isEqualTo("Working");
            assertThat(second.value().state()).as("second move applied").isEqualTo("Done");
            assertThat(List.of(first.revision(), second.revision()))
                    .as("one revision covers the whole transaction")
                    .containsOnly(revision);
        }
    }

    @Test
    void aMissingStoryIsANamedRefusal() throws IOException {
        try (var client = client("normal")) {
            refusal(
                    () -> client.getStory(SETTINGS, TOKEN, "missing"),
                    PlanningRefused.Reason.NO_SUCH_STORY,
                    Map.of("story", "missing"));
            refusal(
                    () -> client.listStoryIssues(SETTINGS, TOKEN, "missing"),
                    PlanningRefused.Reason.NO_SUCH_STORY,
                    Map.of("story", "missing"));
        }
    }

    @Test
    void aMissingIssueIsANamedRefusal() throws IOException {
        try (var client = client("normal")) {
            refusal(
                    () -> client.getIssue(SETTINGS, TOKEN, "missing"),
                    PlanningRefused.Reason.NO_SUCH_ISSUE,
                    Map.of("issue", "missing"));
        }
    }

    @Test
    void aStoryWithIssuesCannotHaveItsOwnStateSet() throws IOException {
        try (var client = client("normal")) {
            var before = client.getStory(SETTINGS, TOKEN, "story");
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(new StateChange("story", null, "Done")),
                                    before.revision()),
                    PlanningRefused.Reason.STORY_HAS_ISSUES,
                    Map.of("story", "story"));
            assertThat(client.getStory(SETTINGS, TOKEN, "story"))
                    .as("the refusal changes no story or revision")
                    .isEqualTo(before);
        }
    }

    @Test
    void aDisallowedStateInABatchChangesNothing() throws IOException {
        try (var client = client("normal")) {
            var before = client.getIssue(SETTINGS, TOKEN, "one");
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(
                                            new StateChange(null, "one", "Done"),
                                            new StateChange(null, "two", "absent")),
                                    before.revision()),
                    PlanningRefused.Reason.STATE_NOT_ALLOWED,
                    Map.of(
                            "item",
                            "two",
                            "from",
                            "Backlog",
                            "to",
                            "absent",
                            "reason",
                            "Unknown state"));
            assertThat(client.getIssue(SETTINGS, TOKEN, "one"))
                    .as("even a late transition refusal rolls back the whole batch")
                    .isEqualTo(before);
        }
    }

    @Test
    void issueWritesRequireTheIssuesCapabilityAsWellAsWrite() throws IOException {
        try (var client = client("stories-only")) {
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(new StateChange(null, "one", "Done")),
                                    "0"),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "issues"));
        }
    }

    @Test
    void unsupportedCapabilitiesRemainNamedErrors() throws IOException {
        try (var client = client("read-only")) {
            refusal(
                    () -> client.setStates(SETTINGS, TOKEN, List.of(), "0"),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "write"));
            refusal(
                    () ->
                            client.setStates(
                                    SETTINGS,
                                    TOKEN,
                                    List.of(new StateChange("empty", null, "Done")),
                                    "0"),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "write"));
            refusal(
                    () -> client.listStoryIssues(SETTINGS, TOKEN, "story"),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "issues"));
            refusal(
                    () -> client.getIssue(SETTINGS, TOKEN, "one"),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "issues"));
            refusal(
                    () -> client.refresh(SETTINGS, TOKEN),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "watch"));
            refusal(
                    () -> client.watchChanges(SETTINGS, TOKEN, change -> false),
                    PlanningRefused.Reason.NOT_SUPPORTED,
                    Map.of("capability", "watch"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GetWorkflow",
                "ListStories",
                "GetStory",
                "ListStoryIssues",
                "GetIssue",
                "SetStates",
                "Refresh",
                "WatchChanges"
            })
    void everyDataMethodRefusesAMissingWorkflow(String method) throws IOException {
        try (var client = client("missing-workflow")) {
            Runnable call =
                    switch (method) {
                        case "GetWorkflow" -> () -> client.getWorkflow(SETTINGS, TOKEN);
                        case "ListStories" -> () -> client.listStories(SETTINGS, TOKEN, null);
                        case "GetStory" -> () -> client.getStory(SETTINGS, TOKEN, "story");
                        case "ListStoryIssues" ->
                                () -> client.listStoryIssues(SETTINGS, TOKEN, "story");
                        case "GetIssue" -> () -> client.getIssue(SETTINGS, TOKEN, "one");
                        case "SetStates" ->
                                () ->
                                        client.setStates(
                                                SETTINGS,
                                                TOKEN,
                                                List.of(new StateChange(null, "one", "Done")),
                                                "0");
                        case "Refresh" -> () -> client.refresh(SETTINGS, TOKEN);
                        case "WatchChanges" ->
                                () -> client.watchChanges(SETTINGS, TOKEN, change -> false);
                        default -> throw new IllegalArgumentException(method);
                    };
            refusal(
                    call,
                    PlanningRefused.Reason.WORKFLOW_MISSING,
                    Map.of("detail", "The project has no workflow"));
        }
    }

    @Test
    void watchChangesStreamsAWriteOnAnIndependentConnection() throws Exception {
        try (var client = client("normal")) {
            var first = new CompletableFuture<Change>();
            var second = new CompletableFuture<Change>();
            var executor =
                    Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                Future<?> watch =
                        executor.submit(
                                () ->
                                        client.watchChanges(
                                                SETTINGS,
                                                TOKEN,
                                                change -> {
                                                    if (first.complete(change)) {
                                                        return true;
                                                    }
                                                    second.complete(change);
                                                    return false;
                                                }));
                Change initial = first.get(5, TimeUnit.SECONDS);
                String revision =
                        client.setStates(
                                SETTINGS,
                                TOKEN,
                                List.of(new StateChange(null, "one", "Done")),
                                initial.revision());
                assertThat(second.get(5, TimeUnit.SECONDS))
                        .as(
                                "a write reaches the existing watcher without blocking the write connection")
                        .isEqualTo(new Change(revision, List.of("story"), List.of("one")));
                watch.get(5, TimeUnit.SECONDS);
            } finally {
                client.close();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void refreshFetchesImmediatelyAndPublishesTheChange() throws Exception {
        try (var client = client("remote")) {
            var first = new CompletableFuture<Change>();
            var second = new CompletableFuture<Change>();
            var executor =
                    Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                Future<?> watch =
                        executor.submit(
                                () ->
                                        client.watchChanges(
                                                SETTINGS,
                                                TOKEN,
                                                change -> {
                                                    if (first.complete(change)) {
                                                        return true;
                                                    }
                                                    second.complete(change);
                                                    return false;
                                                }));
                Change before = first.get(5, TimeUnit.SECONDS);
                assertThat(client.getIssue(SETTINGS, TOKEN, "one").value().state())
                        .as("ordinary reads do not fetch the staged remote state")
                        .isEqualTo("Backlog");
                String revision = client.refresh(SETTINGS, TOKEN);
                assertThat(revision)
                        .as("refresh observed a new revision")
                        .isNotEqualTo(before.revision());
                assertThat(client.getIssue(SETTINGS, TOKEN, "one").value().state())
                        .as("refresh fetched the pending remote change")
                        .isEqualTo("Done");
                assertThat(second.get(5, TimeUnit.SECONDS).revision())
                        .as("the refresh also notified existing watchers")
                        .isEqualTo(revision);
                watch.get(5, TimeUnit.SECONDS);
            } finally {
                client.close();
                executor.shutdownNow();
            }
        }
    }

    private InstalledPlanning client(String mode) throws IOException {
        Path executable = directory.resolve("stub-planning");
        String command =
                "#!/bin/sh\nexec "
                        + quote(Path.of(System.getProperty("java.home"), "bin/java").toString())
                        + " "
                        + quote("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"))
                        + " -cp "
                        + quote(System.getProperty("java.class.path"))
                        + " "
                        + quote(AdapterMain.class.getName())
                        + " "
                        + quote(mode)
                        + " \"$@\"\n";
        Files.writeString(executable, command);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var client =
                new InstalledPlanning(
                        executable, directory, Files.createDirectory(directory.resolve("project")));
        assertThat(directory.resolve("project/initialized"))
                .as("serve passes the caller's durable project directory to initialize")
                .isRegularFile();
        return client;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void refusal(
            Runnable call, PlanningRefused.Reason reason, Map<String, Object> fields) {
        assertThatThrownBy(call::run)
                .as("a contract refusal preserves its name and fields")
                .isInstanceOfSatisfying(
                        PlanningRefused.class,
                        ex -> {
                            assertThat(ex.reason()).as("typed refusal").isEqualTo(reason);
                            assertThat(ex.parameters()).as("wire fields").isEqualTo(fields);
                        });
    }

    /** A fresh adapter process for each contract test, serving through PlanningServer. */
    public static final class AdapterMain {
        /** Launches the requested fixture through the published entry point. */
        public static void main(String[] args) throws IOException {
            String mode = args[0];
            StubPlanning adapter =
                    new StubPlanning(
                            "missing-workflow".equals(mode) ? null : StubPlanning.defaultWorkflow(),
                            "read-only".equals(mode)
                                    ? List.of()
                                    : "stories-only".equals(mode)
                                            ? List.of("write")
                                            : List.of("write", "watch", "issues"));
            if ("remote".equals(mode)) {
                adapter.stageIssueState("one", "Done");
            }
            if ("missing-subagents".equals(mode)) {
                serveWithoutSubAgents(adapter, args);
                return;
            }
            PlanningAdapter verified =
                    (PlanningAdapter)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    PlanningAdapter.class.getClassLoader(),
                                    new Class<?>[] {PlanningAdapter.class},
                                    (proxy, method, parameters) -> {
                                        if (parameters != null
                                                && parameters.length >= 2
                                                && parameters[0] instanceof Map<?, ?>) {
                                            if (!SETTINGS.equals(parameters[0])
                                                    || !TOKEN.equals(parameters[1])) {
                                                throw new IllegalArgumentException(
                                                        "Settings or token did not arrive unchanged");
                                            }
                                        }
                                        if (method.getName().equals("initialize")) {
                                            Files.writeString(
                                                    ((Path) parameters[0]).resolve("pid"),
                                                    Long.toString(ProcessHandle.current().pid()));
                                            Files.writeString(
                                                    ((Path) parameters[0]).resolve("initialized"),
                                                    "initialized");
                                        }
                                        if (mode.equals("blocked")
                                                && method.getName().equals("getWorkflow")) {
                                            Files.writeString(
                                                    Path.of(args[3]).resolve("blocked"), "blocked");
                                            Thread.sleep(120_000);
                                        }
                                        if (mode.startsWith("error-")
                                                && method.getName().equals("getWorkflow")) {
                                            String name = mode.substring(6);
                                            Map<String, Object> detail =
                                                    new java.util.LinkedHashMap<>();
                                            detail.put(
                                                    name.equals("Failed") ? "message" : "detail",
                                                    "Safe store detail");
                                            if (!List.of("Failed", "InvalidSettings")
                                                    .contains(name)) detail.put("retryAfter", 17);
                                            throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                                    PlanningProtocol.INTERFACE + "." + name,
                                                    detail);
                                        }
                                        try {
                                            return method.invoke(adapter, parameters);
                                        } catch (java.lang.reflect.InvocationTargetException ex) {
                                            throw ex.getCause();
                                        }
                                    });
            PlanningMain.run(verified, java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        private static void serveWithoutSubAgents(StubPlanning adapter, String[] args)
                throws IOException {
            Files.writeString(Path.of(args[3]).resolve("initialized"), "initialized");
            try (var server = new VarlinkServer(Path.of(args[2]), PlanningProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (p, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                1,
                                                "name",
                                                "stub-planning",
                                                "version",
                                                "1",
                                                "capabilities",
                                                List.of("issues"),
                                                "credentials",
                                                List.of("token"))));
                server.method(
                        "GetIssue",
                        (p, replies) -> {
                            var issue = adapter.getIssue(SETTINGS, TOKEN, (String) p.get("issue"));
                            var fields = new java.util.LinkedHashMap<>(issue.value().asMap());
                            fields.remove("subAgents");
                            replies.last(Map.of("issue", fields, "revision", issue.revision()));
                        });
                System.out.println("ready " + server.socketPath());
                System.out.flush();
                server.run();
            }
        }
    }
}
