package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewSarifTest {

    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String CONTAINER = "0123456789ab".repeat(4);
    private static final Push PUSH = new Push("shop", "app", "fix-login", HEAD, BASE, CONTAINER);

    private static final JsonSchema SCHEMA = schema();

    private final ReviewSarif writer = new ReviewSarif();

    @ParameterizedTest
    @ValueSource(strings = {"comment-with-fix.sarif.json", "thread-with-reply.sarif.json"})
    void theBundledFixturesHoldToTheProfilesSchema(final String fixture) throws IOException {
        assertThat(violations(resource("fixtures/" + fixture))).isEmpty();
    }

    @Test
    void aWrittenPageHoldsToTheSchemaAndNamesItsPushAndCommits() throws ReviewRefused {
        final Map<String, Object> doc = page(List.of(thread(comment(Side.NEW, 3, 5, "why?", "x"))));
        final Map<String, Object> run = run(doc);
        assertThat(doc.get("version")).isEqualTo("2.1.0");
        assertThat(map(doc.get("properties"))).isEqualTo(Map.of("porelo/profileVersion", "1"));
        assertThat(map(list(run.get("versionControlProvenance")).get(0)).get("revisionId"))
                .isEqualTo(HEAD);
        assertThat(map(run.get("properties")))
                .containsEntry("porelo/baseRevisionId", BASE)
                .containsEntry("porelo/project", "shop")
                .containsEntry("porelo/repository", "app")
                .containsEntry("porelo/push", "fix-login")
                .containsEntry("porelo/completeness", "complete");
        assertThat(run.get("columnKind")).isEqualTo("unicodeCodePoints");
    }

    @Test
    void aPushWithoutABaseWritesNone() throws ReviewRefused {
        final Push noBase = new Push("shop", "app", "fix-login", HEAD, null, CONTAINER);
        final String sarif = writer.page(noBase, List.of(), null).sarif();
        assertThat(violations(sarif)).isEmpty();
        assertThat(map(run(parse(sarif)).get("properties")))
                .doesNotContainKey("porelo/baseRevisionId");
    }

    @Test
    void aCommentIsAReviewResultAnchoredOnItsSideAndItsSuggestionAFixOfItsLines()
            throws ReviewRefused {
        final Map<String, Object> result =
                results(page(List.of(thread(comment(Side.NEW, 3, 5, "why?", "y = 2;\n"))))).get(0);
        assertThat(result).containsEntry("kind", "review").containsEntry("level", "none");
        assertThat(map(result.get("properties")))
                .containsEntry("porelo/anchorKind", "line")
                .containsEntry("porelo/diffSide", "new")
                .containsEntry("porelo/threadState", "open")
                .containsEntry("porelo/sourceKind", "owner-account")
                .doesNotContainKey("porelo/taskId")
                .doesNotContainKey("porelo/replyTo");
        final Map<String, Object> location = physical(result);
        assertThat(map(location.get("region"))).isEqualTo(Map.of("startLine", 3L, "endLine", 5L));
        final Map<String, Object> change =
                map(list(map(list(result.get("fixes")).get(0)).get("artifactChanges")).get(0));
        final Map<String, Object> replacement = map(list(change.get("replacements")).get(0));
        assertThat(map(replacement.get("deletedRegion")))
                .isEqualTo(Map.of("startLine", 3L, "endLine", 5L));
        assertThat(map(replacement.get("insertedContent")).get("text")).isEqualTo("y = 2;\n");
    }

    @Test
    void aReplyIsAResultOfItsOwnInItsThreadAtItsRootsPlaceWithoutAFix() throws ReviewRefused {
        final Kept root = comment(Side.OLD, 7, 7, "old code?", null);
        final Kept reply =
                new Kept(
                        UUID.randomUUID().toString(),
                        root.thread(),
                        Origin.of(new Task("shop-2")),
                        new ReviewReply(root.thread(), new Body("fixed")));
        final List<Map<String, Object>> results =
                results(
                        page(
                                List.of(
                                        new ReviewThread(
                                                root.thread(),
                                                ThreadState.RESOLVED,
                                                List.of(root, reply)))));
        assertThat(results).hasSize(2);
        final Map<String, Object> answer = results.get(1);
        assertThat(answer.get("guid"))
                .isEqualTo(reply.guid())
                .isNotEqualTo(results.get(0).get("guid"));
        assertThat(answer).doesNotContainKey("fixes").doesNotContainKey("correlationGuid");
        assertThat(map(answer.get("properties")))
                .containsEntry("porelo/replyTo", root.thread())
                .containsEntry("porelo/threadId", root.thread())
                .containsEntry("porelo/threadState", "resolved")
                .containsEntry("porelo/diffSide", "old")
                .containsEntry("porelo/sourceKind", "task")
                .containsEntry("porelo/taskId", "shop-2");
        assertThat(physical(answer)).isEqualTo(physical(results.get(0)));
    }

    @Test
    void braceIsDoubledInTheMessageAloneAndAPathIsWrittenAsAUriReference() throws ReviewRefused {
        final Kept kept =
                new Kept(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Origin.of(new OwnerAccount()),
                        new ReviewComment(
                                new LineAnchor("docs/my file{1}%.md", Side.NEW, 1, 1),
                                new Body("use {0} here"),
                                new Suggestion("if (a) { b(); }")));
        final String sarif = writer.page(PUSH, List.of(thread(kept)), null).sarif();
        assertThat(violations(sarif)).isEmpty();
        final Map<String, Object> result = results(parse(sarif)).get(0);
        assertThat(map(result.get("message")).get("text")).isEqualTo("use {{0}} here");
        assertThat(map(physical(result).get("artifactLocation")).get("uri"))
                .isEqualTo("docs/my%20file%7B1%7D%25.md");
        assertThat(sarif).contains("if (a) { b(); }");
    }

    @Test
    void aPageHoldsAtMostAHundredResultsAndItsCursorLeadsToTheNext() throws ReviewRefused {
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, "c" + i, null)));
        }
        final List<String> texts = new ArrayList<>();
        final List<String> completeness = new ArrayList<>();
        @Nullable String cursor = null;
        int pages = 0;
        do {
            final ReviewWriter.Page page = writer.page(PUSH, threads, cursor);
            assertThat(violations(page.sarif())).isEmpty();
            final Map<String, Object> doc = parse(page.sarif());
            assertThat(results(doc)).hasSizeLessThanOrEqualTo(100);
            results(doc).forEach(r -> texts.add((String) map(r.get("message")).get("text")));
            completeness.add((String) map(run(doc).get("properties")).get("porelo/completeness"));
            cursor = page.next();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(texts).hasSize(250).startsWith("c0", "c1").endsWith("c249");
        assertThat(completeness).containsExactly("partial", "partial", "complete");
    }

    @Test
    void aPageStaysUnderOneMebibyteAndStillCarriesAtLeastOneResult() throws ReviewRefused {
        final String braces = "{".repeat(8000);
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, braces, "}".repeat(8000))));
        }
        final ReviewWriter.Page first = writer.page(PUSH, threads, null);
        assertThat(first.sarif().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(1 << 20);
        assertThat(results(parse(first.sarif()))).isNotEmpty().hasSizeLessThan(100);
        assertThat(first.next()).isNotNull();
    }

    @Test
    void aCursorThatThisWriterDidNotHandOutForThisPushIsRefused() throws ReviewRefused {
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, "c" + i, null)));
        }
        final String next = Objects.requireNonNull(writer.page(PUSH, threads, null).next());
        final Push other = new Push("shop", "app", "other", HEAD, BASE, CONTAINER);
        for (final Object[] call :
                List.of(
                        new Object[] {PUSH, "garbage"},
                        new Object[] {PUSH, "-1." + next.substring(next.indexOf('.') + 1)},
                        new Object[] {PUSH, "9999." + next.substring(next.indexOf('.') + 1)},
                        new Object[] {other, next})) {
            assertThatThrownBy(() -> writer.page((Push) call[0], threads, (String) call[1]))
                    .isInstanceOf(ReviewRefused.class)
                    .extracting(e -> ((ReviewRefused) e).refusal().error())
                    .isEqualTo(ReviewError.CURSOR_REFUSED);
        }
    }

    @ParameterizedTest
    @MethodSource("refusedTexts")
    void aKeptTextTheCheckShouldHaveRefusedIsNeverWritten(final String text) {
        final List<ReviewThread> threads = List.of(thread(comment(Side.NEW, 1, 1, text, null)));
        assertThatThrownBy(() -> writer.page(PUSH, threads, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(text);
    }

    @Test
    void aDocumentThatBreaksTheProfileFailsTheSchema() throws ReviewRefused {
        final Kept root = comment(Side.NEW, 1, 1, "why?", null);
        final Kept reply =
                new Kept(
                        UUID.randomUUID().toString(),
                        root.thread(),
                        Origin.of(new Task("shop-2")),
                        new ReviewReply(root.thread(), new Body("done")));
        final String valid =
                writer.page(
                                PUSH,
                                List.of(
                                        new ReviewThread(
                                                root.thread(),
                                                ThreadState.OPEN,
                                                List.of(root, reply))),
                                null)
                        .sarif();
        assertThat(violations(valid)).isEmpty();

        final Map<String, Object> noGuid = parse(valid);
        results(noGuid).get(0).remove("guid");
        assertThat(violations(Json.write(noGuid))).isNotEmpty();

        final Map<String, Object> shared = parse(valid);
        results(shared).get(1).put("correlationGuid", results(shared).get(0).get("guid"));
        assertThat(violations(Json.write(shared))).isNotEmpty();

        final Map<String, Object> person = parse(valid);
        map(results(person).get(0).get("properties")).put("porelo/sourceKind", "person");
        assertThat(violations(Json.write(person))).isNotEmpty();

        final Map<String, Object> oldFix = parse(valid);
        map(results(oldFix).get(0).get("properties")).put("porelo/diffSide", "old");
        results(oldFix)
                .get(0)
                .put(
                        "fixes",
                        List.of(
                                Map.of(
                                        "artifactChanges",
                                        List.of(
                                                Map.of(
                                                        "artifactLocation",
                                                        Map.of("uri", "a.txt"),
                                                        "replacements",
                                                        List.of(
                                                                Map.of(
                                                                        "deletedRegion",
                                                                        Map.of(
                                                                                "startLine",
                                                                                1,
                                                                                "endLine",
                                                                                1),
                                                                        "insertedContent",
                                                                        Map.of("text", "x"))))))));
        assertThat(violations(Json.write(oldFix))).isNotEmpty();
    }

    static List<String> refusedTexts() {
        final List<String> texts = new ArrayList<>();
        for (final int c :
                new int[] {0x1b, 0x0d, 0x7f, 0x85, 0x9b, 0x202e, 0x200b, 0xe0041, 0x2028, 0x2029}) {
            texts.add("a" + Character.toString(c) + "b");
        }
        texts.add("a" + (char) 0xdc00 + "b");
        texts.add(" \n\t ");
        texts.add("x".repeat(8193));
        return texts;
    }

    @ParameterizedTest
    @MethodSource("refusedTexts")
    void aKeptReplyOrSuggestionTheCheckShouldHaveRefusedIsNeverWritten(final String text) {
        final Kept root = comment(Side.NEW, 1, 1, "why?", null);
        final Kept reply =
                new Kept(
                        UUID.randomUUID().toString(),
                        root.thread(),
                        Origin.of(new Task("shop-2")),
                        new ReviewReply(root.thread(), new Body(text)));
        assertThatThrownBy(
                        () ->
                                writer.page(
                                        PUSH,
                                        List.of(
                                                new ReviewThread(
                                                        root.thread(),
                                                        ThreadState.OPEN,
                                                        List.of(root, reply))),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        if (!text.isBlank()) {
            assertThatThrownBy(
                            () ->
                                    writer.page(
                                            PUSH,
                                            List.of(thread(comment(Side.NEW, 1, 1, "why?", text))),
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aPathWithARefusedCharacterIsNeverWritten() {
        final Kept kept =
                new Kept(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Origin.of(new OwnerAccount()),
                        new ReviewComment(
                                new LineAnchor("a" + (char) 0x1b + ".txt", Side.NEW, 1, 1),
                                new Body("why?"),
                                null));
        assertThatThrownBy(() -> writer.page(PUSH, List.of(thread(kept)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void squareBracketsAndBackslashesAreEscapedSoNoTextBecomesALink() throws ReviewRefused {
        final Map<String, Object> result =
                results(
                                page(
                                        List.of(
                                                thread(
                                                        comment(
                                                                Side.NEW,
                                                                1,
                                                                1,
                                                                "[open](file:///etc/passwd) a\\b",
                                                                "[x](y)")))))
                        .get(0);
        assertThat(map(result.get("message")).get("text"))
                .isEqualTo("\\[open\\](file:///etc/passwd) a\\\\b");
        final Map<String, Object> change =
                map(list(map(list(result.get("fixes")).get(0)).get("artifactChanges")).get(0));
        assertThat(
                        map(map(list(change.get("replacements")).get(0)).get("insertedContent"))
                                .get("text"))
                .isEqualTo("[x](y)");
    }

    @Test
    void aCursorIsRefusedOnceAnythingBeforeItChangedSoNoResultIsRepeatedOrLost()
            throws ReviewRefused {
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, "c" + i, null)));
        }
        final String next = Objects.requireNonNull(writer.page(PUSH, threads, null).next());
        assertThat(results(parse(writer.page(PUSH, threads, next).sarif()))).hasSize(1);
        final ReviewThread first = threads.get(0);
        final Kept reply =
                new Kept(
                        UUID.randomUUID().toString(),
                        first.id(),
                        Origin.of(new Task("shop-2")),
                        new ReviewReply(first.id(), new Body("done")));
        final List<ReviewThread> grown = new ArrayList<>(threads);
        grown.set(
                0,
                new ReviewThread(
                        first.id(), ThreadState.OPEN, List.of(first.items().get(0), reply)));
        assertThatThrownBy(() -> writer.page(PUSH, grown, next))
                .isInstanceOf(ReviewRefused.class)
                .extracting(e -> ((ReviewRefused) e).refusal().error())
                .isEqualTo(ReviewError.CURSOR_REFUSED);
    }

    @Test
    void aCursorNeverHandedOutOrForAnotherTaskOrHeadIsRefused() throws ReviewRefused {
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, "c" + i, null)));
        }
        final String next = Objects.requireNonNull(writer.page(PUSH, threads, null).next());
        final String digest = next.substring(next.indexOf('.') + 1);
        final Push otherTask =
                new Push("shop", "app", "fix-login", HEAD, BASE, "fedcba987654".repeat(4));
        final Push otherHead =
                new Push("shop", "app", "fix-login", "c".repeat(40), BASE, CONTAINER);
        for (final Object[] call :
                List.of(
                        new Object[] {PUSH, "42." + digest},
                        new Object[] {PUSH, "0." + digest},
                        new Object[] {PUSH, "150." + digest},
                        new Object[] {otherTask, next},
                        new Object[] {otherHead, next})) {
            assertThatThrownBy(() -> writer.page((Push) call[0], threads, (String) call[1]))
                    .isInstanceOf(ReviewRefused.class)
                    .extracting(e -> ((ReviewRefused) e).refusal().error())
                    .isEqualTo(ReviewError.CURSOR_REFUSED);
        }
    }

    @Test
    void aLaterCommentInAThreadIsWrittenAtItsOwnAnchor() throws ReviewRefused {
        final Kept root = comment(Side.OLD, 7, 7, "old code?", null);
        final Kept later =
                new Kept(
                        UUID.randomUUID().toString(),
                        root.thread(),
                        Origin.of(new OwnerAccount()),
                        new ReviewComment(
                                new LineAnchor("src/B.java", Side.NEW, 2, 3),
                                new Body("and here"),
                                new Suggestion("z")));
        final List<Map<String, Object>> results =
                results(
                        page(
                                List.of(
                                        new ReviewThread(
                                                root.thread(),
                                                ThreadState.OPEN,
                                                List.of(root, later)))));
        assertThat(map(physical(results.get(1)).get("artifactLocation")).get("uri"))
                .isEqualTo("src/B.java");
        assertThat(map(results.get(1).get("properties"))).containsEntry("porelo/diffSide", "new");
    }

    @Test
    void theSchemaRefusesAUriThatLeavesTheRepositoryAndAFixOnAReply() throws ReviewRefused {
        final Kept root = comment(Side.NEW, 1, 1, "why?", "x");
        final Kept reply =
                new Kept(
                        UUID.randomUUID().toString(),
                        root.thread(),
                        Origin.of(new Task("shop-2")),
                        new ReviewReply(root.thread(), new Body("done")));
        final String valid =
                writer.page(
                                PUSH,
                                List.of(
                                        new ReviewThread(
                                                root.thread(),
                                                ThreadState.OPEN,
                                                List.of(root, reply))),
                                null)
                        .sarif();
        for (final String uri :
                List.of(
                        "file:///etc/passwd",
                        "/etc/passwd",
                        "../x",
                        "a/../../x",
                        "a/./b",
                        "a%2",
                        "a//b")) {
            final Map<String, Object> doc = parse(valid);
            map(physical(results(doc).get(0)).get("artifactLocation")).put("uri", uri);
            assertThat(violations(Json.write(doc))).as(uri).isNotEmpty();
        }
        final Map<String, Object> fixed = parse(valid);
        results(fixed).get(1).put("fixes", results(fixed).get(0).get("fixes"));
        assertThat(violations(Json.write(fixed))).isNotEmpty();
        final Map<String, Object> blank = parse(valid);
        map(results(blank).get(1).get("message")).put("text", " ");
        assertThat(violations(Json.write(blank))).isNotEmpty();
    }

    @Test
    void manyLargeResultsArePagedUnderOneMebibyteEachAndNoneIsLost() throws ReviewRefused {
        final List<ReviewThread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            threads.add(thread(comment(Side.NEW, 1, 1, i + "{".repeat(4000), "}".repeat(8000))));
        }
        final List<Object> guids = new ArrayList<>();
        @Nullable String cursor = null;
        do {
            final ReviewWriter.Page page = writer.page(PUSH, threads, cursor);
            assertThat(page.sarif().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(1 << 20);
            assertThat(violations(page.sarif())).isEmpty();
            results(parse(page.sarif())).forEach(r -> guids.add(r.get("guid")));
            cursor = page.next();
        } while (cursor != null);
        assertThat(guids).hasSize(100).doesNotHaveDuplicates();
    }

    private Map<String, Object> page(final List<ReviewThread> threads) throws ReviewRefused {
        final ReviewWriter.Page page = writer.page(PUSH, threads, null);
        assertThat(violations(page.sarif())).isEmpty();
        assertThat(page.next()).isNull();
        return parse(page.sarif());
    }

    private static Kept comment(
            final Side side,
            final int start,
            final int end,
            final String text,
            final @Nullable String replacement) {
        return new Kept(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Origin.of(new OwnerAccount()),
                new ReviewComment(
                        new LineAnchor("src/A.java", side, start, end),
                        new Body(text),
                        replacement == null ? null : new Suggestion(replacement)));
    }

    private static ReviewThread thread(final Kept root) {
        return new ReviewThread(root.thread(), ThreadState.OPEN, List.of(root));
    }

    private static Set<ValidationMessage> violations(final String sarif) {
        return SCHEMA.validate(sarif, InputFormat.JSON);
    }

    private static JsonSchema schema() {
        try (InputStream in =
                ReviewSarif.class.getResourceAsStream("review-profile-1.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Objects.requireNonNull(in, "the bundled schema"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(final String name) throws IOException {
        try (InputStream in = ReviewSarifTest.class.getResourceAsStream(name)) {
            return new String(
                    Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> parse(final String sarif) {
        return map(Json.parseExact(sarif));
    }

    private static Map<String, Object> run(final Map<String, Object> doc) {
        return map(list(doc.get("runs")).get(0));
    }

    private static List<Map<String, Object>> results(final Map<String, Object> doc) {
        final List<Map<String, Object>> results = new ArrayList<>();
        list(run(doc).get("results")).forEach(r -> results.add(map(r)));
        return results;
    }

    private static Map<String, Object> physical(final Map<String, Object> result) {
        return map(map(list(result.get("locations")).get(0)).get("physicalLocation"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final @Nullable Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final @Nullable Object value) {
        return (List<Object>) Objects.requireNonNull(value);
    }
}
