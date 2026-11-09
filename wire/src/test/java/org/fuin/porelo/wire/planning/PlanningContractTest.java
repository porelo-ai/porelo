package org.fuin.porelo.wire.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.junit.jupiter.api.Test;

/** Guards generated clients against malformed IDL, missing fields and lossy record decoders. */
class PlanningContractTest {
    @Test
    void thePartParsesAndEveryRecordDeclaresItsWireFields() throws Exception {
        final String part;
        try (var stream =
                getClass()
                        .getResourceAsStream(
                                "/varlink/" + TasksContract.INTERFACE + "/60-planning.varlink")) {
            part =
                    new String(
                            java.util.Objects.requireNonNull(
                                            stream, "planning contract resource is packaged")
                                    .readAllBytes(),
                            StandardCharsets.UTF_8);
        }
        final var parsed = new Grammar(part);
        final Map<String, Set<String>> types = parsed.parse();
        assertThat(parsed.methods)
                .as("every promised board operation is declared")
                .containsExactlyInAnyOrder(
                        "GetPlanningWorkflow",
                        "ListStories",
                        "GetStory",
                        "ListStoryIssues",
                        "GetIssue",
                        "WatchStories",
                        "RefreshStories",
                        "ProposeStoryWork",
                        "StartStoryWork",
                        "GetStoryWork",
                        "StopStoryWork",
                        "SaveMoves",
                        "ResolveIssueReading");
        for (Class<?> type :
                List.of(
                        PlanState.class,
                        PlanPhase.class,
                        PlanWorkRule.class,
                        PlanFinding.class,
                        PlanIssue.class,
                        PlanWorker.class,
                        PlanStory.class,
                        PlanRepositoryWork.class,
                        PlanAssignment.class,
                        PlanWorkIssue.class,
                        PlanIssueReadingRounds.class,
                        WorkEnded.class,
                        PlanWorkTask.class,
                        PlanWork.class)) {
            assertThat(types.get(type.getSimpleName()))
                    .as("wire fields of %s", type.getSimpleName())
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.Arrays.stream(type.getRecordComponents())
                                    .map(java.lang.reflect.RecordComponent::getName)
                                    .toList());
        }
        assertThat(part)
                .as("bounded-reading inputs retain their exact optionality and refusal names")
                .contains(
                        "comment: ?string",
                        "readingRounds: ?int",
                        "issueReadingRounds: ?[](issue: string, rounds: int)",
                        "file: ?string",
                        "line: ?int",
                        "priority: ?string",
                        "method SaveMoves(project: string, moves: [](story: ?string, issue: ?string, to: string), revision: string) -> (revision: string, moved: []string)",
                        "choice: (REVIEW_ANYWAY, ONE_MORE_ROUND, REWORK)",
                        "error ReadingNotStopped(issue: string)",
                        "error ReadingRoundChanged(issue: string, rounds: int)",
                        "kind: (WAITING, WORKING, CLOSED, OTHER)",
                        "role: (MAIN, BUILDER, TESTER, READER, OTHER)",
                        "kind: (BUILDER, TESTER, READER, OTHER)",
                        "error StateNotAllowed(item: string, from: string, to: string, reason: string)",
                        "error StoryHasIssues(story: string)",
                        "error PlanningCredentialRefused(detail: string, retryAfter: int)",
                        "error RateLimited(detail: string, retryAfter: int)",
                        "error NoSuchStore(detail: string, retryAfter: int)",
                        "error Unreachable(detail: string, retryAfter: int)",
                        "error InvalidSettings(detail: string)",
                        "error PlanningAdapterError(adapter: string, error: string, parameters: object)");
        assertThatThrownBy(() -> new Grammar(part.replace("rounds: int", "rounds int")).parse())
                .as("the parser rejects an invalid field declaration")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storyPrioritySurvivesDecodingAndRejectsNonText() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("id", "story");
        fields.put("title", "A story");
        fields.put("state", "Backlog");
        fields.put("link", "https://example.org/story");
        fields.put("description", "");
        fields.put("issues", List.of());
        fields.put("priority", "Highest");
        assertThat(PlanStory.of(fields).asMap()).containsEntry("priority", "Highest");
        fields.put("priority", 1);
        assertThatThrownBy(() -> PlanStory.of(fields)).isInstanceOf(IllegalArgumentException.class);
        fields.put("priority", null);
        assertThat(PlanStory.of(fields).asMap()).doesNotContainKey("priority");
        fields.remove("priority");
        assertThat(PlanStory.of(fields).asMap()).doesNotContainKey("priority");
    }

    @Test
    void otherValuesRoundTripWithoutBecomingAnActionableKind() {
        var state = new PlanState("Future", PlanState.Kind.OTHER);
        roundTrip(state, state.asMap(), PlanState::of);
        var worker = new PlanWorker("future", "example-agent", "model", Role.OTHER, "Brief");
        roundTrip(worker, worker.asMap(), PlanWorker::of);
        var assignment =
                new PlanAssignment(
                        "repo/issue/future",
                        "issue",
                        "future",
                        "example-agent",
                        "model",
                        PlanAssignment.Kind.OTHER,
                        "Future",
                        "Brief",
                        PlanAssignment.Choice.DROP);
        roundTrip(assignment, assignment.asMap(), PlanAssignment::of);
    }

    @Test
    void everyRecordSurvivesJsonWithBriefsFindingsChoicesAndOptionalValues() {
        final var finding =
                new PlanFinding(
                        1,
                        PlanFinding.Severity.BLOCKING,
                        "A condition is missing",
                        "src/A.java",
                        17);
        final var note =
                new PlanFinding(
                        2, PlanFinding.Severity.NOTE, "Clarify | this diagnostic", null, null);
        final var fileOnly =
                new PlanFinding(
                        3, PlanFinding.Severity.NOTE, "Review this file", "src/A.java", null);
        final var worker =
                new PlanWorker(
                        "main", "example-agent", "build-model", Role.MAIN, "Build the issue.");
        final var reader =
                new PlanWorker(
                        "reader",
                        "example-agent",
                        "read-model",
                        Role.READER,
                        "# Read\nRead the acceptance.\nOutcome: done\n");
        final var issue =
                new PlanIssue(
                        "one",
                        "service",
                        "First",
                        "Reading",
                        "S",
                        List.of("two"),
                        "https://example.org/one",
                        2,
                        2,
                        false,
                        true,
                        true,
                        List.of(finding, note),
                        null,
                        null);
        final var full =
                new PlanIssue(
                        "one",
                        "service",
                        "First",
                        "Reading",
                        "S",
                        List.of("two"),
                        "https://example.org/one",
                        2,
                        2,
                        false,
                        true,
                        true,
                        List.of(finding, note),
                        "The condition holds.",
                        List.of(reader));
        final var result =
                new PlanWorkIssue(
                        "one",
                        2,
                        2,
                        false,
                        true,
                        true,
                        List.of(finding, note),
                        PlanWorkIssue.Reason.ROUND_LIMIT);
        final var ended =
                new WorkEnded(
                        WorkEnded.Outcome.BLOCKED,
                        "Needs review",
                        "Outcome: blocked - Needs review",
                        "2026-01-01T00:00:00Z");
        final var task =
                new PlanWorkTask(
                        "story-service",
                        "container-identity",
                        "service",
                        List.of("one"),
                        "Reading",
                        "Review",
                        Mode.UNATTENDED,
                        "example-agent",
                        "read-model",
                        Role.READER,
                        "2026-01-01T00:00:00Z",
                        false,
                        ended);
        final var assignment =
                new PlanAssignment(
                        "service/one/reader",
                        "one",
                        "reader",
                        "example-agent",
                        "read-model",
                        PlanAssignment.Kind.READER,
                        "Reading",
                        reader.brief(),
                        PlanAssignment.Choice.KEEP);
        final var repository =
                new PlanRepositoryWork("service", List.of("one"), worker, List.of(assignment));
        final var rounds = new PlanIssueReadingRounds("one", 2);
        final var work =
                new PlanWork(
                        List.of(task),
                        "coordinator",
                        "2026-01-01T00:00:00Z",
                        false,
                        List.of(repository),
                        "Read carefully",
                        2,
                        List.of(rounds),
                        List.of(result));
        final var story =
                new PlanStory(
                        "story",
                        "A story",
                        "Reading",
                        "https://example.org/story",
                        "Description",
                        List.of(issue),
                        work,
                        "Highest");
        roundTrip(finding, finding.asMap(), PlanFinding::of);
        roundTrip(note, note.asMap(), PlanFinding::of);
        roundTrip(fileOnly, fileOnly.asMap(), PlanFinding::of);
        roundTrip(issue, issue.asMap(), PlanIssue::of);
        roundTrip(full, full.asMap(), PlanIssue::of);
        roundTrip(worker, worker.asMap(), PlanWorker::of);
        roundTrip(reader, reader.asMap(), PlanWorker::of);
        roundTrip(repository, repository.asMap(), PlanRepositoryWork::of);
        roundTrip(rounds, rounds.asMap(), PlanIssueReadingRounds::of);
        roundTrip(result, result.asMap(), PlanWorkIssue::of);
        roundTrip(ended, ended.asMap(), WorkEnded::of);
        roundTrip(task, task.asMap(), PlanWorkTask::of);
        roundTrip(work, work.asMap(), PlanWork::of);
        roundTrip(story, story.asMap(), PlanStory::of);
        for (var choice : PlanAssignment.Choice.values()) {
            final var selected =
                    new PlanAssignment(
                            "service/one/reader",
                            "one",
                            "reader",
                            "other-agent",
                            "other-model",
                            PlanAssignment.Kind.READER,
                            "Reading",
                            reader.brief(),
                            choice);
            roundTrip(selected, selected.asMap(), PlanAssignment::of);
        }
        final var rule =
                new PlanWorkRule(
                        "Building",
                        List.of("Backlog"),
                        "Review",
                        "Reading",
                        "Reworking",
                        PlanWorkRule.Per.REPOSITORY,
                        List.of(Mode.AGENT, Mode.UNATTENDED),
                        "Build {issues}");
        roundTrip(rule, rule.asMap(), PlanWorkRule::of);
        final var finalRule =
                new PlanWorkRule(
                        "Reading",
                        List.of("Building"),
                        "Review",
                        null,
                        null,
                        PlanWorkRule.Per.STORY,
                        List.of(Mode.UNATTENDED),
                        "Read");
        roundTrip(finalRule, finalRule.asMap(), PlanWorkRule::of);
        final var state = new PlanState("Building", PlanState.Kind.WORKING);
        roundTrip(state, state.asMap(), PlanState::of);
        final var phase = new PlanPhase("Build", List.of("Building", "Reading"));
        roundTrip(phase, phase.asMap(), PlanPhase::of);
        final var running =
                new PlanWorkTask(
                        "story-service",
                        "container-identity",
                        "service",
                        List.of("one"),
                        "Building",
                        "Review",
                        Mode.AGENT,
                        "example-agent",
                        "build-model",
                        Role.MAIN,
                        "2026-01-01T00:00:00Z",
                        true,
                        null);
        roundTrip(running, running.asMap(), PlanWorkTask::of);
        final var solo =
                new PlanWork(List.of(), null, "", false, List.of(), "", 2, List.of(), List.of());
        roundTrip(solo, solo.asMap(), PlanWork::of);
        final var empty =
                new PlanStory(
                        "empty",
                        "Empty",
                        "Backlog",
                        "https://example.org/empty",
                        "",
                        List.of(),
                        null,
                        null);
        roundTrip(empty, empty.asMap(), PlanStory::of);
    }

    private static <T> void roundTrip(
            T expected,
            Map<String, Object> fields,
            java.util.function.Function<Map<?, ?>, T> read) {
        assertThat(read.apply((Map<?, ?>) Json.parse(Json.write(fields))))
                .as(
                        "%s keeps its complete wire representation",
                        expected.getClass().getSimpleName())
                .isEqualTo(expected);
    }

    @Test
    void malformedReadingDataCannotSilentlyBecomeNoFindingsOrNoWork() {
        assertThatThrownBy(
                        () ->
                                PlanIssue.of(
                                        Map.of(
                                                "id",
                                                "one",
                                                "repository",
                                                "repo",
                                                "title",
                                                "Title",
                                                "state",
                                                "Working",
                                                "size",
                                                "S",
                                                "blockedBy",
                                                List.of(),
                                                "link",
                                                "https://example.org/one")))
                .as("missing status is not a fabricated idle issue")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                PlanFinding.of(
                                        Map.of(
                                                "number",
                                                1,
                                                "severity",
                                                "NOTE",
                                                "text",
                                                "note",
                                                "line",
                                                1.5)))
                .as("source lines remain whole numbers")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                PlanAssignment.of(
                                        Map.of(
                                                "id",
                                                "repo/one/reader",
                                                "issue",
                                                "one",
                                                "name",
                                                "reader",
                                                "agent",
                                                "example-agent",
                                                "model",
                                                "read-model",
                                                "kind",
                                                "READER",
                                                "workingState",
                                                "Reading",
                                                "brief",
                                                "Read the issue",
                                                "choice",
                                                "START")))
                .as("only KEEP CHANGE DROP are accepted")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Recursive descent over this part's varlink grammar, including inline structs and enums. */
    private static final class Grammar {
        private final List<String> tokens;
        private int at;
        private final Set<String> methods = new java.util.HashSet<>();

        Grammar(String source) {
            final String withoutComments = source.replaceAll("(?m)#.*$", "");
            final var matcher =
                    java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|->|[^\\s]")
                            .matcher(withoutComments);
            tokens = matcher.results().map(java.util.regex.MatchResult::group).toList();
        }

        Map<String, Set<String>> parse() {
            final Map<String, Set<String>> types = new java.util.LinkedHashMap<>();
            while (at < tokens.size()) {
                final String declaration = take();
                final String name = identifier();
                switch (declaration) {
                    case "type" -> {
                        if (types.put(name, fields()) != null) {
                            throw invalid();
                        }
                    }
                    case "error" -> fields();
                    case "method" -> {
                        if (!methods.add(name)) {
                            throw invalid();
                        }
                        fields();
                        expect("->");
                        fields();
                    }
                    default -> throw invalid();
                }
            }
            return types;
        }

        private Set<String> fields() {
            expect("(");
            final Set<String> names = new java.util.LinkedHashSet<>();
            if (peek(")")) {
                take();
                return names;
            }
            do {
                final String name = identifier();
                if (!names.add(name)) {
                    throw invalid();
                }
                if (peek(":")) {
                    take();
                    type();
                } else if (!name.matches("[A-Z][A-Z0-9_]*")) {
                    throw invalid();
                }
                if (!peek(",")) {
                    break;
                }
                take();
            } while (true);
            expect(")");
            return names;
        }

        private void type() {
            if (peek("?")) {
                take();
            }
            if (peek("[")) {
                take();
                expect("]");
            }
            if (peek("(")) {
                fields();
            } else {
                identifier();
            }
        }

        private String identifier() {
            final String word = take();
            if (!word.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw invalid();
            }
            return word;
        }

        private boolean peek(String token) {
            return at < tokens.size() && token.equals(tokens.get(at));
        }

        private String take() {
            if (at >= tokens.size()) {
                throw invalid();
            }
            return tokens.get(at++);
        }

        private void expect(String token) {
            if (!token.equals(take())) {
                throw invalid();
            }
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid varlink at token " + at);
        }
    }
}
