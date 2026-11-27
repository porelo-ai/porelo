package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfileTranslatorTest {

    // The profile's fixtures are read where porelo-build-api keeps them, never copied.
    private static final Path REVIEW =
            Path.of("../builds/api/src/test/resources/org/fuin/porelo/build/api/review");

    private static final ProfileChannel ADAPTER = new ProfileChannel.Adapter("github.com");

    private static final Task TASK = new Task("shop-2");

    private static final ProfileChannel FROM_TASK = new ProfileChannel.FromTask(TASK);

    private static final String LOGIN = "src/main/java/org/example/shop/Login.java";

    private static final String SESSION = "src/main/java/org/example/shop/Session.java";

    private final ProfileTranslator translator = new ProfileTranslator();

    private static final String PULL_REQUEST = "https://github.com/shop/app/pull/42";

    @Test
    void aForgePageBecomesItsForgeAccountsCommentsAndRepliesAtTheirPlaces() throws Exception {
        final ProfileRead read =
                translator.read(fixture("fixtures/forge-page.sarif.json"), ADAPTER);
        assertThat(read.completeness())
                .as("the page's completeness")
                .isEqualTo(Completeness.COMPLETE);
        assertThat(read.resolved())
                .as("the thread both of whose results say resolved")
                .containsExactly("1834530112");
        assertThat(read.items())
                .as("the forge page's items, each at the time the forge gave it")
                .containsExactly(
                        new ProfileItem(
                                "5b2e9d40-1c7a-4f3e-8d6b-2a9c4e7f1b03",
                                "1834527690",
                                new ForgeAccount("github.com", "583231", "octocat"),
                                new ReviewComment(
                                        new LineAnchor(LOGIN, Side.NEW, 41, 43),
                                        new Body(
                                                "The expiry is read after items[0] is used; check it first."),
                                        new Suggestion(
                                                "        if (token.expired()) {\n"
                                                        + "            return Result.denied();\n"
                                                        + "        }\n"
                                                        + "        final Item first = items.get(0);\n")),
                                Instant.parse("2026-11-20T09:12:05Z")),
                        new ProfileItem(
                                "e3a1f6c2-7b84-4d09-9e5a-6c1b8d2f4a70",
                                "1834527690",
                                new ForgeAccount("github.com", "1050231", "shop-reviewer"),
                                new ForgeReply(
                                        "1834527690",
                                        new Body(
                                                "Moved the check up; the test LoginTest.expiredTokenIsDenied covers it.")),
                                Instant.parse("2026-11-20T10:30:00Z")),
                        new ProfileItem(
                                "0d7c4b19-3e62-4a8f-b5d1-9f2e6a3c8b54",
                                "1834530112",
                                new ForgeAccount("github.com", "2154823", "mona"),
                                new ReviewComment(
                                        new LineAnchor(SESSION, Side.OLD, 88, 88),
                                        new Body(
                                                "Was this fallback to the session cookie removed on purpose?"),
                                        null),
                                Instant.parse("2026-11-21T08:00:41Z")),
                        new ProfileItem(
                                "a94f2e7b-6d13-4c58-a0b2-3e8d7f1c6a29",
                                "1834530112",
                                new ForgeAccount("github.com", "9919", "hubot"),
                                new ForgeReply(
                                        "1834530112",
                                        new Body(
                                                "Yes: the cookie is set by the gateway now, see docs/session.md.")),
                                Instant.parse("2026-11-21T08:45:17Z")));
    }

    @Test
    void aSubmittedCommentBecomesTheTasksCommentWithItsSuggestion() throws Exception {
        final ProfileRead read =
                translator.read(fixture("fixtures/submitted-comment.sarif.json"), FROM_TASK);
        assertThat(read.completeness())
                .as("a submission's completeness")
                .isEqualTo(Completeness.COMPLETE);
        assertThat(read.resolved()).as("a submission's resolved threads").isEmpty();
        assertThat(read.items())
                .as("the submitted comment")
                .containsExactly(
                        new ProfileItem(
                                null,
                                null,
                                TASK,
                                new ReviewComment(
                                        new LineAnchor(LOGIN, Side.NEW, 27, 28),
                                        new Body(
                                                "A blank password reaches hash() here; refuse it before the lookup."),
                                        new Suggestion(
                                                "        if (password.isBlank()) {\n"
                                                        + "            return Result.denied();\n"
                                                        + "        }\n"
                                                        + "        final Account account = accounts.find(name);\n")),
                                null));
    }

    @Test
    void aSubmittedReplyBecomesTheTasksReplyToItsThread() throws Exception {
        final ProfileRead read =
                translator.read(fixture("fixtures/submitted-reply.sarif.json"), FROM_TASK);
        assertThat(read.completeness())
                .as("a submission's completeness")
                .isEqualTo(Completeness.COMPLETE);
        assertThat(read.items())
                .as("the submitted reply")
                .containsExactly(
                        new ProfileItem(
                                null,
                                null,
                                TASK,
                                new ForgeReply(
                                        "1834527690",
                                        new Body(
                                                "The check now runs first; LoginTest.expiredTokenIsDenied shows it.")),
                                null));
    }

    @Test
    void aSubmittedResolveBecomesTheTasksResolveOfItsThreadAfterTheResults() throws Exception {
        assertThat(
                        translator
                                .read(fixture("fixtures/submitted-resolve.sarif.json"), FROM_TASK)
                                .items())
                .as("the submitted resolve, the task's, naming its thread alone")
                .containsExactly(
                        new ProfileItem(null, null, TASK, new ThreadResolve("1834527690"), null));
        final String both =
                text("fixtures/submitted-reply.sarif.json")
                        .replace(
                                "\"porelo/baseRevisionId\": \"9f8e7d6c5b4a39281706f5e4d3c2b1a098765432\"",
                                "\"porelo/baseRevisionId\": \"9f8e7d6c5b4a39281706f5e4d3c2b1a098765432\","
                                        + " \"porelo/resolveThreads\": [\"1834527690\"]");
        assertThat(translator.read(both.getBytes(StandardCharsets.UTF_8), FROM_TASK).items())
                .as("a reply and then the resolve of its thread")
                .extracting(ProfileItem::item)
                .hasSize(2)
                .last()
                .isEqualTo(new ThreadResolve("1834527690"));
        assertThat(refused(text("fixtures/submitted-resolve.sarif.json"), ADAPTER).rule())
                .as("a resolve through a forge's adapter")
                .isEqualTo("channel: /properties/porelo~1documentKind");
    }

    @Test
    void aPercentEncodedPathIsTakenAsTheRawPath() throws Exception {
        final String submitted =
                text("fixtures/submitted-comment.sarif.json")
                        .replace(LOGIN, "src/a%20b%C3%A9.java");
        final ReviewItem comment =
                translator
                        .read(submitted.getBytes(StandardCharsets.UTF_8), FROM_TASK)
                        .items()
                        .get(0)
                        .item();
        assertThat(((ReviewComment) comment).anchor())
                .as("the anchor of a comment on a path with a space")
                .isEqualTo(new LineAnchor("src/a b\u00e9.java", Side.NEW, 27, 28));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // A pull request run's page holds forge accounts' items alone.
                "fixtures/forge-page.sarif.json | ADAPTER | \"porelo/sourceKind\": \"forge-account\" | \"porelo/sourceKind\": \"owner-account\" | schema: /runs/0/results/0/properties",
                // A submission's producer is the channel's to stamp.
                "fixtures/submitted-reply.sarif.json | TASK | \"porelo/replyTo\": \"1834527690\" | \"porelo/replyTo\": \"1834527690\", \"porelo/sourceKind\": \"task\", \"porelo/taskId\": \"shop-3\" | schema: /runs/0/results/0/properties"
            })
    void aDocumentClaimingAProducerTheChannelDoesNotGrantIsRefused(
            final String fixture,
            final String channel,
            final String from,
            final String to,
            final String rule)
            throws Exception {
        final String text = text(fixture);
        assertThat(text).as("%s holding the point broken", fixture).contains(from);
        final ProfileRefused refused = refused(text.replace(from, to), channel(channel));
        // Which of the result's violations the schema names first is the validator's choice.
        assertThat(refused.rule()).as("%s claiming another producer", fixture).startsWith(rule);
    }

    @ParameterizedTest
    @CsvSource({
        "adversarial/text-bidi.sarif.json, TASK, profile: format-character at /runs/0/results/0/message/text",
        "adversarial/uri-encoded-dot-segment.sarif.json, TASK, profile: artifact-uri at /runs/0/results/0/locations/0/physicalLocation/artifactLocation/uri",
        "adversarial/adapter-push-run.sarif.json, ADAPTER, channel: /runs/0/properties",
        "fixtures/forge-page.sarif.json, TASK, channel: /properties/porelo~1documentKind",
        "hostile/duplicate-key.sarif.json, TASK, reading: duplicate-key at /runs/0/results/0/properties"
    })
    void aDocumentBreakingTheProfileIsRefusedByItsRule(
            final String fixture, final String channel, final String rule) throws Exception {
        final ProfileRefused refused = refused(text(fixture), channel(channel));
        assertThat(refused.rule()).as("%s from %s", fixture, channel).isEqualTo(rule);
        assertThat(refused.getMessage())
                .as("%s's refusal, never its text", fixture)
                .doesNotContain("MARKER");
    }

    @Test
    void aThreadWhoseResultsDisagreeOnItsStateReadsAsOpen() throws Exception {
        final String page = text("fixtures/forge-page.sarif.json");
        final String resolved = "\"porelo/threadState\": \"resolved\"";
        final int last = page.lastIndexOf(resolved);
        final String mixed =
                page.substring(0, last)
                        + "\"porelo/threadState\": \"open\""
                        + page.substring(last + resolved.length());
        assertThat(translator.read(mixed.getBytes(StandardCharsets.UTF_8), ADAPTER).resolved())
                .as("the resolved threads of a page whose reply calls its thread open")
                .isEmpty();
    }

    @Test
    void aForgeAccountTheRecordsRefuseIsRefusedAsAWhole() throws Exception {
        // Id 64 and name 256 are the schema's bounds too; a blank name passes neither.
        final String page =
                text("fixtures/forge-page.sarif.json")
                        .replace(
                                "\"porelo/forgeAccountName\": \"octocat\"",
                                "\"porelo/forgeAccountName\": \" \"");
        final ProfileRefused refused = refused(page, ADAPTER);
        assertThat(refused.rule())
                .as("a forge account named by a blank")
                .isEqualTo("record: forge account at /runs/0/results/0");
    }

    @Test
    void aSubmittedRejectIsItsReasonAndNoItem() throws Exception {
        final ProfileRead read =
                translator.read(fixture("fixtures/submitted-reject.sarif.json"), FROM_TASK);
        assertThat(read.items()).as("a reject is no comment or reply").isEmpty();
        assertThat(read.reject())
                .as("its reason, as given")
                .isEqualTo(
                        new Body(
                                "The login now accepts a blank password; this cannot be reviewed into shape."));
        assertThat(
                        translator
                                .read(fixture("fixtures/submitted-comment.sarif.json"), FROM_TASK)
                                .reject())
                .as("a submission without one")
                .isNull();
    }

    @Test
    void aSubmissionNamesThePlaceItsRunNamesAndAPageNamesNone() throws Exception {
        assertThat(
                        translator
                                .read(fixture("fixtures/submitted-reject.sarif.json"), FROM_TASK)
                                .named())
                .as("a push run, by project, repository and push")
                .containsExactly("push:shop/app/fix-login");
        assertThat(
                        translator
                                .read(fixture("fixtures/submitted-comment.sarif.json"), FROM_TASK)
                                .named())
                .as("a pull request run, by its address")
                .containsExactly("pullRequest:" + PULL_REQUEST);
        assertThat(translator.read(fixture("fixtures/forge-page.sarif.json"), ADAPTER).named())
                .as("a page binds nothing")
                .isEmpty();
    }

    @Test
    void aSubmissionWithTwoRejectsIsRefusedAsAWhole() throws Exception {
        final String one = text("fixtures/submitted-reject.sarif.json");
        final int start = one.indexOf("{", one.indexOf("\"results\""));
        // The reject's result holds no array, so the results end at the first bracket after it.
        final int end = one.lastIndexOf("}", one.indexOf("]", start)) + 1;
        final String result = one.substring(start, end);
        final String two = one.replace(result, result + ",\n" + result);
        assertThat(refused(two, FROM_TASK).rule())
                .as("two rejects in one submission")
                .isEqualTo("submission: more than one reject at /runs/0/results/1");
    }

    private ProfileRefused refused(final String document, final ProfileChannel channel) {
        final ProfileRefused refused =
                catchThrowableOfType(
                        ProfileRefused.class,
                        () -> translator.read(document.getBytes(StandardCharsets.UTF_8), channel));
        assertThat(refused).as("a refusal").isNotNull();
        return refused;
    }

    private static ProfileChannel channel(final String name) {
        return switch (name) {
            case "ADAPTER" -> ADAPTER;
            case "TASK" -> FROM_TASK;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static byte[] fixture(final String name) throws IOException {
        return Files.readAllBytes(REVIEW.resolve(name));
    }

    private static String text(final String name) throws IOException {
        return Files.readString(REVIEW.resolve(name), StandardCharsets.UTF_8);
    }
}
