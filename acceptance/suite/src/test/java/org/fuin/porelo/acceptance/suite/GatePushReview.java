package org.fuin.porelo.acceptance.suite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The stub agent's {@code .porelo-stub-review} for a task that reads another task's push waiting at
 * the gate: what the stub sends to the review in its run there, as {@link StubReview} makes it for
 * a pull request.
 *
 * <p>The file lies in the pushed work, which the reading task's workspace is moved to, and names
 * the push's run: its project and repository, the push's name, {@link StubReview#HEAD} for the head
 * the stub stands on, and {@link #BASE} for the commit the push started from, which only the
 * pushing task knows and puts in place as it commits. The work it lies in is {@link #LINES} in
 * {@link #PATH}, so a comment stands at lines the push has.
 */
final class GatePushReview {

    /** Where the pushing task puts the commit its work started from. */
    static final String BASE = "PORELO_ACCEPTANCE_BASE";

    /** The file the pushed work holds, which the items stand at. */
    static final String PATH = "notes.txt";

    /** What that file holds: three lines. */
    static final String LINES = "one\ntwo\nthree\n";

    /** The line the owner account's thread stands at. */
    static final int OWNERS_LINE = 2;

    /** What the reading task's new comment says, at the third line. */
    static final String COMMENT = "Why does three follow two here?";

    /** What the reading task's reply says. */
    static final String REPLY = "Two stays; it is the middle line.";

    /** Why the reading task discards the push. */
    static final String REASON = "This cannot be reviewed into shape.";

    private static final String MARK = "PORELO_ACCEPTANCE_MARK";

    private GatePushReview() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Makes the file for a review of a given kind.
     *
     * @param kind {@code comments}, {@code replies}, {@code resolves} or {@code rejects}.
     * @param project The project, which names its repository too.
     * @param push The push's name, the pushing task's.
     * @return The file's text.
     */
    static String of(String kind, String project, String push) {
        return switch (kind) {
            case "comments" -> comments(project, push);
            case "replies" -> replies(project, push);
            case "resolves" -> resolves(project, push);
            case "rejects" -> rejects(project, push);
            default ->
                    throw new IllegalArgumentException(
                            "a stub review comments, replies, resolves or rejects, not: " + kind);
        };
    }

    /**
     * Makes the file that has the stub open a thread at the push's third line.
     *
     * @param project The project.
     * @param push The push's name.
     * @return The file's text.
     */
    static String comments(String project, String push) {
        final Map<String, Object> document =
                at(fixture("submitted-comment.sarif.json"), project, push);
        final Map<String, Object> run = first(document);
        final Map<String, Object> comment = map(list(run.get("results")).getFirst());
        comment.remove("fixes");
        comment.put("message", Map.of("text", COMMENT));
        comment.put("locations", location(3));
        run.put("results", List.of(comment));
        document.put("runs", List.of(run));
        return "submit gate-comment\n" + Json.write(document) + "\n";
    }

    /**
     * Makes the file that has the stub reply in every open thread it is given, at the owner
     * account's line.
     *
     * @param project The project.
     * @param push The push's name.
     * @return The file's text.
     */
    static String replies(String project, String push) {
        final Map<String, Object> document =
                at(fixture("submitted-reply.sarif.json"), project, push);
        final Map<String, Object> run = first(document);
        final Map<String, Object> reply = map(list(run.get("results")).getFirst());
        reply.put("message", Map.of("text", REPLY));
        reply.put("locations", location(OWNERS_LINE));
        final Map<String, Object> properties = map(reply.get("properties"));
        properties.put("porelo/replyTo", StubReview.THREAD);
        reply.put("properties", properties);
        run.put("results", List.of(MARK));
        document.put("runs", List.of(run));
        return "replies\n" + repeated(document, Json.write(reply));
    }

    /**
     * Makes the file that has the stub mark every open thread it is given resolved, with no result.
     *
     * @param project The project.
     * @param push The push's name.
     * @return The file's text.
     */
    static String resolves(String project, String push) {
        final Map<String, Object> document =
                at(fixture("submitted-resolve.sarif.json"), project, push);
        final Map<String, Object> run = first(document);
        final Map<String, Object> properties = map(run.get("properties"));
        properties.put("porelo/resolveThreads", List.of(MARK));
        run.put("properties", properties);
        run.put("results", List.of());
        document.put("runs", List.of(run));
        return "replies\n" + repeated(document, "\"" + StubReview.THREAD + "\"");
    }

    /**
     * Makes the file that has the stub discard the push, with {@link #REASON} as its reason.
     *
     * @param project The project.
     * @param push The push's name.
     * @return The file's text.
     */
    static String rejects(String project, String push) {
        final Map<String, Object> document =
                at(fixture("submitted-reject.sarif.json"), project, push);
        final Map<String, Object> run = first(document);
        final Map<String, Object> reject = map(list(run.get("results")).getFirst());
        reject.put("message", Map.of("text", REASON));
        run.put("results", List.of(reject));
        document.put("runs", List.of(run));
        return "submit gate-reject\n" + Json.write(document) + "\n";
    }

    /**
     * Puts the one line the stub repeats for every open thread on a line of its own, where the
     * document holds {@link #MARK}.
     */
    private static String repeated(Map<String, Object> document, String line) {
        final String[] around = Json.write(document).split("\"" + MARK + "\"", -1);
        if (around.length != 2) {
            throw new AssertionError("the place the stub repeats is not one");
        }
        return around[0] + "\n" + line + "\n" + around[1] + "\n";
    }

    /** Points a fixture's run at the push: its project, repository, name, head and base. */
    private static Map<String, Object> at(String fixture, String project, String push) {
        final Map<String, Object> document = map(Json.parseNumberTypes(fixture));
        final Map<String, Object> run = first(document);
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/project", project);
        properties.put("porelo/repository", project);
        properties.put("porelo/push", push);
        properties.put("porelo/baseRevisionId", BASE);
        run.put("properties", properties);
        final Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("repositoryUri", "urn:porelo:repository:" + project + ":" + project);
        provenance.put("revisionId", StubReview.HEAD);
        run.put("versionControlProvenance", List.of(provenance));
        document.put("runs", List.of(run));
        return document;
    }

    /** A result's location: one line of {@link #PATH}. */
    private static List<Object> location(int line) {
        return List.of(
                Map.of(
                        "physicalLocation",
                        Map.of(
                                "artifactLocation",
                                Map.of("uri", PATH),
                                "region",
                                Map.of("startLine", line, "endLine", line))));
    }

    private static String fixture(String name) {
        return ForgeStub.fixture(name);
    }

    private static Map<String, Object> first(Map<String, Object> document) {
        return map(list(document.get("runs")).getFirst());
    }

    private static Map<String, Object> map(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> found)) {
            throw new AssertionError("a fixture's part is not a JSON object");
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        found.forEach((key, one) -> copy.put(String.valueOf(key), one));
        return copy;
    }

    private static List<Object> list(@Nullable Object value) {
        if (!(value instanceof List<?> found) || found.isEmpty()) {
            throw new AssertionError("a fixture's part is not a JSON array with an element");
        }
        return List.copyOf(found);
    }
}
