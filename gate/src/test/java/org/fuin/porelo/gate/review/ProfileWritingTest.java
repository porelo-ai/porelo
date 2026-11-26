package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ProfileWritingTest {

    static final String HEAD = "a".repeat(40);
    static final String BASE = "b".repeat(40);
    static final String FORGE = "github.com";
    static final String PULL_REQUEST = "https://github.com/shop/app/pull/42";
    static final String REPOSITORY = "https://github.com/shop/app";
    static final PullRequestFacts FACTS =
            new PullRequestFacts(
                    new PullRequestRef(FORGE, URI.create(PULL_REQUEST)),
                    REPOSITORY,
                    HEAD,
                    BASE,
                    BASE,
                    PullRequestState.OPEN,
                    false,
                    null,
                    null,
                    null,
                    null);
    static final Instant AT = Instant.parse("2026-11-20T09:12:05Z");
    static final ForgeAccount OCTOCAT = new ForgeAccount(FORGE, "583231", "octocat");
    static final ForgeAccount MONA = new ForgeAccount(FORGE, "2154823", "mona");
    static final LineAnchor LOGIN =
            new LineAnchor("src/main/java/org/example/shop/Login.java", Side.NEW, 41, 43);
    static final LineAnchor SESSION =
            new LineAnchor("src/main/java/org/example/shop/Session.java", Side.OLD, 88, 88);

    private final ReviewSarif writer = new ReviewSarif();

    @Test
    void aConversationOfTwoThreadsIsOnePageThatPassesProfileTwoAndTheAdapterCheck()
            throws Exception {
        final List<byte[]> pages = writer.pages(FACTS, twoThreads());
        assertThat(pages).as("the pages of a short conversation").hasSize(1);
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.ADAPTER,
                                        SarifReading.read(pages.get(0))))
                .as("the page read with the profile's schema and checked as an adapter's page")
                .doesNotThrowAnyException();
        final Map<String, Object> doc = parse(pages.get(0));
        assertThat(map(doc.get("properties")))
                .as("the root's properties")
                .isEqualTo(Map.of("porelo/profileVersion", "2", "porelo/documentKind", "page"));
        final Map<String, Object> run = run(doc);
        assertThat(map(list(run.get("versionControlProvenance")).get(0)))
                .as("the forge's repository and the pinned head")
                .isEqualTo(Map.of("repositoryUri", REPOSITORY, "revisionId", HEAD));
        assertThat(map(run.get("properties")))
                .as("the run's properties")
                .isEqualTo(
                        Map.of(
                                "porelo/pullRequest", PULL_REQUEST,
                                "porelo/baseRevisionId", BASE,
                                "porelo/completeness", "complete"));
    }

    @Test
    void eachResultNamesItsForgeAccountTheForgesThreadIdsAndAReplyStandsAtItsThreadsPlace() {
        final Conversation conversation = twoThreads();
        final List<Map<String, Object>> results =
                results(parse(writer.pages(FACTS, conversation).get(0)));
        final Map<String, Object> root = results.get(0);
        final Map<String, Object> reply = results.get(1);
        assertThat(root.get("guid"))
                .as("the comment's guid")
                .isEqualTo(conversation.items().get(0).guid());
        assertThat(map(root.get("properties")))
                .as("the comment's properties")
                .containsEntry("porelo/threadId", "1834527690")
                .containsEntry("porelo/sourceKind", "forge-account")
                .containsEntry("porelo/forgeAccountId", "583231")
                .containsEntry("porelo/forgeAccountName", "octocat")
                .containsEntry("porelo/createdAt", "2026-11-20T09:12:05Z")
                .doesNotContainKey("porelo/replyTo");
        assertThat(map(reply.get("properties")))
                .as("the reply's properties")
                .containsEntry("porelo/threadId", "1834527690")
                .containsEntry("porelo/replyTo", "1834527690")
                .containsEntry("porelo/forgeAccountId", "2154823")
                .containsEntry("porelo/forgeAccountName", "mona")
                .containsEntry("porelo/createdAt", "2026-11-20T09:13:05Z");
        assertThat(reply.get("locations"))
                .as("the reply's location, its thread's")
                .isEqualTo(root.get("locations"));
        assertThat(reply).as("a reply's result").doesNotContainKey("fixes");
        assertThat(list(root.get("fixes"))).as("the comment's suggestion as its fix").hasSize(1);
    }

    @Test
    void aResolvedThreadIsWrittenResolvedOnEachOfItsResultsAndAnOpenOneOpen() throws Exception {
        final Conversation conversation =
                new Conversation(
                        twoThreads().items(),
                        Set.of("1834530112"),
                        List.of(),
                        Completeness.COMPLETE,
                        null);
        final byte[] page = writer.pages(FACTS, conversation).get(0);
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.ADAPTER, SarifReading.read(page)))
                .as("the page with a resolved thread read and checked as an adapter's page")
                .doesNotThrowAnyException();
        assertThat(
                        results(parse(page)).stream()
                                .map(result -> map(result.get("properties")))
                                .map(
                                        properties ->
                                                properties.get("porelo/threadId")
                                                        + " "
                                                        + properties.get("porelo/threadState"))
                                .toList())
                .as("each result's thread and its state")
                .containsExactly(
                        "1834527690 open",
                        "1834527690 open",
                        "1834530112 resolved",
                        "1834530112 resolved");
    }

    @Test
    void theItemsComeInThreadOrderEachThreadsItemsInTheirs() {
        final Kept first = comment("100", OCTOCAT, LOGIN, "First thread.", null);
        final Kept second = comment("200", MONA, SESSION, "Second thread.", null);
        final Kept answer = reply("100", MONA, "An answer to the first.");
        final List<Map<String, Object>> results =
                results(
                        parse(
                                writer.pages(
                                                FACTS,
                                                new Conversation(
                                                        List.of(first, second, answer),
                                                        Set.of(),
                                                        List.of(),
                                                        Completeness.COMPLETE,
                                                        null))
                                        .get(0)));
        assertThat(results.stream().map(result -> result.get("guid")).toList())
                .as("the guids in thread order")
                .containsExactly(first.guid(), answer.guid(), second.guid());
    }

    @Test
    void anIncompleteConversationSaysSoOnItsLastPage() {
        final Conversation partial =
                new Conversation(
                        twoThreads().items(),
                        Set.of(),
                        List.of(),
                        Completeness.PARTIAL,
                        "RateLimited");
        assertThat(map(run(parse(writer.pages(FACTS, partial).get(0))).get("properties")))
                .as("the last page's properties")
                .containsEntry("porelo/completeness", "partial");
    }

    @Test
    void aConversationPastAHundredItemsGoesOnOnMoreAndEveryPageButTheLastSaysPartial() {
        final List<Kept> items = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            items.add(comment(String.valueOf(1000 + i), OCTOCAT, LOGIN, "Item " + i + ".", null));
        }
        final List<byte[]> pages =
                writer.pages(
                        FACTS,
                        new Conversation(items, Set.of(), List.of(), Completeness.COMPLETE, null));
        assertThat(pages.stream().map(page -> results(parse(page)).size()).toList())
                .as("the results per page")
                .containsExactly(100, 50);
        assertThat(
                        pages.stream()
                                .map(
                                        page ->
                                                map(run(parse(page)).get("properties"))
                                                        .get("porelo/completeness"))
                                .toList())
                .as("each page's completeness")
                .containsExactly("partial", "complete");
    }

    @Test
    void everyPageLeavesRoomForTheMessageItTravelsIn() throws Exception {
        final List<Kept> items = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            items.add(
                    comment(
                            String.valueOf(1000 + i),
                            OCTOCAT,
                            new LineAnchor("src/Big.java", Side.NEW, 1, 100),
                            "[x] ".repeat(2000),
                            ("y".repeat(79) + "\n").repeat(100)));
        }
        final List<byte[]> pages =
                writer.pages(
                        FACTS,
                        new Conversation(items, Set.of(), List.of(), Completeness.COMPLETE, null));
        assertThat(pages).as("a conversation of over a page's bytes").hasSizeGreaterThan(1);
        for (final byte[] page : pages) {
            assertThat(page.length)
                    .as("a page's bytes")
                    .isLessThanOrEqualTo(ReviewSarif.MAX_PAGE_BYTES);
            assertThatCode(
                            () ->
                                    ProfileCheck.check(
                                            ProfileCheck.Channel.ADAPTER, SarifReading.read(page)))
                    .as("each page read and checked")
                    .doesNotThrowAnyException();
        }
        assertThat(pages.stream().mapToInt(page -> results(parse(page)).size()).sum())
                .as("every item on some page")
                .isEqualTo(60);
    }

    @Test
    void thePageBoundIsAMebibyteLessTheRoomOfTheMessageAroundIt() {
        assertThat(ReviewSarif.MAX_PAGE_BYTES)
                .as("the page bound")
                .isEqualTo(1024 * 1024 - 64 * 1024);
    }

    @Test
    void aCommentTooLargeForAPageIsRefusedNeverCut() {
        final Kept huge =
                comment(
                        "1",
                        OCTOCAT,
                        new LineAnchor("src/Big.java", Side.NEW, 1, 100),
                        "\\".repeat(8192),
                        ("\"".repeat(79) + "\n").repeat(100));
        assertThatThrownBy(
                        () ->
                                writer.pages(
                                        FACTS,
                                        new Conversation(
                                                List.of(huge),
                                                Set.of(),
                                                List.of(),
                                                Completeness.COMPLETE,
                                                null)))
                .as("a comment past a result's bound")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 KiB");
    }

    @Test
    void anEmptyConversationIsOnePageWithoutResults() throws Exception {
        final List<byte[]> pages =
                writer.pages(
                        FACTS,
                        new Conversation(
                                List.of(), Set.of(), List.of(), Completeness.COMPLETE, null));
        assertThat(pages).as("the pages of an empty conversation").hasSize(1);
        assertThat(
                        ProfileCheck.check(
                                        ProfileCheck.Channel.ADAPTER,
                                        SarifReading.read(pages.get(0)))
                                .getRuns()
                                .get(0)
                                .getResults())
                .as("its results")
                .isEmpty();
    }

    @Test
    void anItemNotOfAnAccountAtThisForgeIsRefused() {
        final Kept task =
                new Kept(
                        UUID.randomUUID().toString(),
                        "1",
                        Origin.of(new Task("shop-2")),
                        new ReviewComment(LOGIN, new Body("Mine."), null));
        final Kept elsewhere =
                comment("1", new ForgeAccount("gitlab.com", "7", "other"), LOGIN, "Hi.", null);
        for (final Kept kept : List.of(task, elsewhere)) {
            assertThatThrownBy(
                            () ->
                                    writer.pages(
                                            FACTS,
                                            new Conversation(
                                                    List.of(kept),
                                                    Set.of(),
                                                    List.of(),
                                                    Completeness.COMPLETE,
                                                    null)))
                    .as("an item of " + kept.origin().producer())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anItemWithoutTheTimeTheForgeGaveItIsRefusedSinceThePageWouldFailTheProfile() {
        final Kept untimed =
                new Kept(
                        UUID.randomUUID().toString(),
                        "1",
                        Origin.of(OCTOCAT),
                        new ReviewComment(LOGIN, new Body("When?"), null));
        assertThatThrownBy(
                        () ->
                                writer.pages(
                                        FACTS,
                                        new Conversation(
                                                List.of(untimed),
                                                Set.of(),
                                                List.of(),
                                                Completeness.COMPLETE,
                                                null)))
                .as("a page of an item without its time")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.one(FACTS, untimed, untimed))
                .as("one item without its time")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReplyWhoseThreadsCommentIsNotInTheConversationIsRefused() {
        assertThatThrownBy(
                        () ->
                                writer.pages(
                                        FACTS,
                                        new Conversation(
                                                List.of(reply("9", MONA, "Lost.")),
                                                Set.of(),
                                                List.of(),
                                                Completeness.COMPLETE,
                                                null)))
                .as("a reply without its thread's place")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneItemIsAOneResultPageOfTheSameShape() throws Exception {
        final Conversation conversation = twoThreads();
        final byte[] one =
                writer.one(FACTS, conversation.items().get(0), conversation.items().get(0));
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.ADAPTER, SarifReading.read(one)))
                .as("the one-item page read and checked")
                .doesNotThrowAnyException();
        assertThat(results(parse(one)))
                .as("its results")
                .containsExactly(results(parse(writer.pages(FACTS, conversation).get(0))).get(0));
    }

    @Test
    void aSubmissionPassesTheTaskCheckAgainstTheHeadAndNamesNoProducer() throws Exception {
        final byte[] submission =
                writer.submission(
                        FACTS, new ReviewComment(LOGIN, new Body("Check first."), null), LOGIN);
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.TASK, SarifReading.read(submission)))
                .as("the submission read and checked as a task's")
                .doesNotThrowAnyException();
        final Map<String, Object> doc = parse(submission);
        assertThat(map(doc.get("properties")))
                .as("the root's properties")
                .containsEntry("porelo/documentKind", "submission");
        final Map<String, Object> run = run(doc);
        assertThat(map(list(run.get("versionControlProvenance")).get(0)).get("revisionId"))
                .as("the commit it was held for")
                .isEqualTo(HEAD);
        assertThat(map(run.get("properties")))
                .as("the run's properties")
                .containsEntry("porelo/pullRequest", PULL_REQUEST)
                .doesNotContainKey("porelo/completeness");
        final Map<String, Object> result = results(doc).get(0);
        assertThat(result).as("the result").doesNotContainKey("guid");
        assertThat(map(result.get("properties")).keySet())
                .as("the result's properties, no producer among them")
                .containsExactlyInAnyOrder("porelo/anchorKind", "porelo/diffSide");
    }

    @Test
    void oneReplyIsWrittenAtItsThreadsPlaceAndNamesItsThread() throws Exception {
        final Conversation conversation = twoThreads();
        final Kept root = conversation.items().get(0);
        final byte[] one = writer.one(FACTS, conversation.items().get(1), root);
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.ADAPTER, SarifReading.read(one)))
                .as("the one-reply page read and checked")
                .doesNotThrowAnyException();
        assertThat(results(parse(one)))
                .as("its result, the reply's as a page of its conversation writes it")
                .containsExactly(results(parse(writer.pages(FACTS, conversation).get(0))).get(1));
    }

    @Test
    void oneItemWithARootThatIsNotItsThreadsOpeningCommentIsRefused() {
        final Conversation conversation = twoThreads();
        final Kept first = conversation.items().get(0);
        final Kept reply = conversation.items().get(1);
        final Kept other = conversation.items().get(2);
        assertThatThrownBy(() -> writer.one(FACTS, reply, other))
                .as("a reply with another thread's comment")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.one(FACTS, reply, reply))
                .as("a reply as its own root")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.one(FACTS, first, other))
                .as("a comment with another comment as its root")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSubmittedReplyStandsAtTheGivenPlaceAndNamesItsThreadAlone() throws Exception {
        final byte[] submission =
                writer.submission(FACTS, new ForgeReply("1834527690", new Body("Done.")), LOGIN);
        assertThatCode(
                        () ->
                                ProfileCheck.check(
                                        ProfileCheck.Channel.TASK, SarifReading.read(submission)))
                .as("the submitted reply read and checked as a task's")
                .doesNotThrowAnyException();
        final Map<String, Object> result = results(parse(submission)).get(0);
        assertThat(map(result.get("properties")))
                .as("the reply's properties")
                .isEqualTo(
                        Map.of(
                                "porelo/anchorKind", "line",
                                "porelo/diffSide", "new",
                                "porelo/replyTo", "1834527690"));
        assertThat(result.get("locations"))
                .as("the reply's location, the place given")
                .isEqualTo(
                        results(
                                        parse(
                                                writer.submission(
                                                        FACTS,
                                                        new ReviewComment(
                                                                LOGIN, new Body("Opened."), null),
                                                        LOGIN)))
                                .get(0)
                                .get("locations"));
        assertThat(result).as("a reply's result").doesNotContainKey("fixes");
    }

    @Test
    void aSubmittedCommentWithAnotherPlaceThanItsAnchorIsRefused() {
        assertThatThrownBy(
                        () ->
                                writer.submission(
                                        FACTS,
                                        new ReviewComment(LOGIN, new Body("Here."), null),
                                        SESSION))
                .as("a comment given another place")
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Conversation twoThreads() {
        return new Conversation(
                List.of(
                        comment(
                                "1834527690",
                                OCTOCAT,
                                LOGIN,
                                "The expiry is read after items[0] is used; check it first.",
                                "        if (token.expired()) {\n        }\n"),
                        reply("1834527690", MONA, "Moved the check up."),
                        comment(
                                "1834530112",
                                MONA,
                                SESSION,
                                "Was this fallback removed on purpose?",
                                null),
                        reply("1834530112", OCTOCAT, "Yes: the gateway sets {the} cookie.")),
                Set.of(),
                List.of(),
                Completeness.COMPLETE,
                null);
    }

    static Kept comment(
            final String thread,
            final ForgeAccount account,
            final LineAnchor anchor,
            final String text,
            final @Nullable String suggestion) {
        return new Kept(
                UUID.randomUUID().toString(),
                thread,
                Origin.of(account),
                new ReviewComment(
                        anchor,
                        new Body(text),
                        suggestion == null ? null : new Suggestion(suggestion)),
                AT);
    }

    static Kept reply(final String thread, final ForgeAccount account, final String text) {
        return new Kept(
                UUID.randomUUID().toString(),
                thread,
                Origin.of(account),
                new ForgeReply(thread, new Body(text)),
                AT.plusSeconds(60));
    }

    static Map<String, Object> parse(final byte[] document) {
        return map(Json.parseExact(new String(document, StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> run(final Map<String, Object> doc) {
        return map(list(doc.get("runs")).get(0));
    }

    private static List<Map<String, Object>> results(final Map<String, Object> doc) {
        return list(run(doc).get("results")).stream().map(ProfileWritingTest::map).toList();
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
