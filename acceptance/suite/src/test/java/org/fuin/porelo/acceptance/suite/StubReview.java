package org.fuin.porelo.acceptance.suite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The stub agent's {@code .porelo-stub-review}, which the pull request's head carries: what the
 * stub sends to the review in its next run there.
 *
 * <p>Its first line is {@code submit <fixture>}, the rest the submission itself; or {@code
 * replies}, the rest a submission whose one line holding {@link #THREAD} the stub repeats for every
 * open thread it is given. Submissions are the review profile's fixtures, at the scenario's pull
 * request.
 */
final class StubReview {

    /** The file's name at the root of the pull request's head. */
    static final String FILE = ".porelo-stub-review";

    /** Where the stub puts an open thread's native id in a reply. */
    static final String THREAD = "PORELO_STUB_THREAD";

    /** Where the stub puts the head commit its workspace stands on, which the file cannot name. */
    static final String HEAD = "PORELO_STUB_HEAD";

    private static final String RESULT = "PORELO_STUB_RESULT";

    private StubReview() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Makes the file that has the stub submit a fixture's submission.
     *
     * @param fixture The submission fixture's file name.
     * @param url The pull request.
     * @param base The pull request's base commit.
     * @param field A property the profile does not have, added to every result, or {@code null}.
     * @return The file's text.
     */
    static String submit(String fixture, String url, String base, @Nullable String field) {
        final Map<String, Object> document = at(ForgeStub.fixture(fixture), url, base);
        if (field != null) {
            final Map<String, Object> run = first(document);
            run.put(
                    "results",
                    list(run.get("results"), "results").stream()
                            .map(
                                    one -> {
                                        final Map<String, Object> result = map(one, "a result");
                                        final Map<String, Object> properties =
                                                map(result.get("properties"), "its properties");
                                        properties.put(field, "outside the profile");
                                        result.put("properties", properties);
                                        return (Object) result;
                                    })
                            .toList());
            document.put("runs", List.of(run));
        }
        return "submit " + fixture + "\n" + Json.write(document) + "\n";
    }

    /**
     * Makes the file that has the stub submit one comment on one line, with no suggestion: the
     * comment of {@code submitted-comment.sarif.json} on its first line, saying the text given. The
     * one comment a forge takes that cannot reply in a thread, comment on several lines or carry a
     * suggestion; an answer there is such a comment on the same line.
     *
     * @param url The pull request.
     * @param base The pull request's base commit.
     * @param text What the comment says.
     * @return The file's text.
     */
    static String singleLine(String url, String base, String text) {
        final String fixture = "submitted-comment.sarif.json";
        final Map<String, Object> document = at(ForgeStub.fixture(fixture), url, base);
        final Map<String, Object> run = first(document);
        final Map<String, Object> comment =
                map(list(run.get("results"), "results").getFirst(), "the comment");
        comment.remove("fixes");
        comment.put("message", Map.of("text", text));
        final Map<String, Object> location =
                map(list(comment.get("locations"), "its locations").getFirst(), "its location");
        final Map<String, Object> physical =
                map(location.get("physicalLocation"), "its physical location");
        final Map<String, Object> region = map(physical.get("region"), "its region");
        region.put("endLine", region.get("startLine"));
        physical.put("region", region);
        location.put("physicalLocation", physical);
        comment.put("locations", List.of(location));
        run.put("results", List.of(comment));
        document.put("runs", List.of(run));
        return "submit " + fixture + "\n" + Json.write(document) + "\n";
    }

    /**
     * Makes the file that has the stub reply to every open thread it is given, with the reply of
     * {@code submitted-reply.sarif.json}.
     *
     * @param url The pull request.
     * @param base The pull request's base commit.
     * @return The file's text.
     */
    static String replies(String url, String base) {
        final Map<String, Object> document =
                at(ForgeStub.fixture("submitted-reply.sarif.json"), url, base);
        final Map<String, Object> run = first(document);
        final Map<String, Object> reply =
                map(list(run.get("results"), "results").getFirst(), "the reply");
        final Map<String, Object> properties = map(reply.get("properties"), "its properties");
        properties.put("porelo/replyTo", THREAD);
        reply.put("properties", properties);
        run.put("results", List.of(RESULT));
        document.put("runs", List.of(run));
        final String[] around = Json.write(document).split("\"" + RESULT + "\"", -1);
        if (around.length != 2) {
            throw new AssertionError("the reply's place in the submission is not one");
        }
        return "replies\n" + around[0] + "\n" + Json.write(reply) + "\n" + around[1] + "\n";
    }

    /** Reads a fixture and points its run at the scenario's pull request and its base. */
    private static Map<String, Object> at(String fixture, String url, String base) {
        final Map<String, Object> document = map(Json.parseNumberTypes(fixture), "the fixture");
        final Map<String, Object> run = first(document);
        final Map<String, Object> properties = map(run.get("properties"), "the run's properties");
        properties.put("porelo/pullRequest", url);
        properties.put("porelo/baseRevisionId", base);
        run.put("properties", properties);
        final Map<String, Object> provenance =
                map(list(run.get("versionControlProvenance"), "the provenance").getFirst(), "it");
        provenance.put("repositoryUri", repositoryOf(url));
        provenance.put("revisionId", HEAD);
        run.put("versionControlProvenance", List.of(provenance));
        document.put("runs", List.of(run));
        return document;
    }

    /**
     * Names the repository a pull request belongs to: its address up to {@code /pull/}.
     *
     * @param url The pull request.
     * @return The repository's https address.
     */
    static String repositoryOf(String url) {
        // GitHub's pull request lies under /pull/, Forgejo's under /pulls/.
        final int pull = Math.max(url.lastIndexOf("/pull/"), url.lastIndexOf("/pulls/"));
        if (pull < 0) {
            throw new AssertionError("not a pull request's address: " + url);
        }
        return url.substring(0, pull);
    }

    private static Map<String, Object> first(Map<String, Object> document) {
        return map(list(document.get("runs"), "runs").getFirst(), "the run");
    }

    private static Map<String, Object> map(@Nullable Object value, String what) {
        if (!(value instanceof Map<?, ?> found)) {
            throw new AssertionError(what + " is not a JSON object");
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        found.forEach((key, one) -> copy.put(String.valueOf(key), one));
        return copy;
    }

    private static List<Object> list(@Nullable Object value, String what) {
        if (!(value instanceof List<?> found) || found.isEmpty()) {
            throw new AssertionError(what + " is not a JSON array with an element");
        }
        return List.copyOf(found);
    }
}
