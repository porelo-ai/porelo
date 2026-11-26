package org.fuin.porelo.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentFields;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewRole;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What is open on a pull request and its decision, read through the forge's stood-in adapter, the
 * one input path stood in by pages, and the run's side, task and held items stood in.
 */
class ReviewOpenPullRequestTest {

    static final String FORGE = "github.com";

    static final String ADDRESS = "https://github.com/uc/shop/pull/7";

    static final PullRequestRef REF = PullRequestRef.of(URI.create(ADDRESS));

    static final String TASK = "shop-2";

    static final String CONTAINER = "c".repeat(64);

    /** The task's container's name, which keys its run's role and its held items. */
    static final String CONTAINER_NAME = "porelo-uc_shop-2";

    static final String HEAD = "a".repeat(40);

    /** The project's repository the task works in, whose mirror its gate forwarded to. */
    static final String REPOSITORY = "shop";

    static final String BASE = "b".repeat(40);

    static final String MERGE = "e".repeat(40);

    static final ForgeAccount AUTHOR = new ForgeAccount(FORGE, "583231", "shop-bot");

    static final ForgeAccount OCTOCAT = new ForgeAccount(FORGE, "9001", "octocat");

    static final Instant AT = Instant.parse("2026-11-25T10:00:00Z");

    static final ReviewOpen.Place PLACE = new ReviewOpen.PullRequest(REF);

    PullRequestState state = PullRequestState.OPEN;

    boolean approved;

    /** When the forge merged or closed the pull request, as its adapter answers it. */
    static final Instant DECIDED = Instant.parse("2026-11-20T10:15:30Z");

    /** The adapter's pages, each read by the stood-in input path as the items given for it. */
    final List<String> pages = new ArrayList<>();

    final Map<String, ProfileRead> reads = new HashMap<>();

    @Nullable String adapterReason;

    final List<Held> held = new ArrayList<>();

    final List<String> lentEntries = new ArrayList<>();

    /** The repository each anchor check was given, by its push. */
    final List<String> checkedIn = new ArrayList<>();

    /** The task each side was asked for. */
    final List<String> sidesAsked = new ArrayList<>();

    final ForgeReview forge =
            new ForgeReview() {
                @Override
                public PullRequestFacts facts(
                        final PullRequestRef ref, final ForgeCredential credential)
                        throws PlaceUnreadable {
                    if (!ref.equals(REF)) {
                        throw new PlaceUnreadable(
                                PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST,
                                "no such pull request");
                    }
                    final boolean merged = state == PullRequestState.MERGED;
                    return new PullRequestFacts(
                            REF,
                            "https://github.com/uc/shop",
                            HEAD,
                            BASE,
                            BASE,
                            state,
                            approved,
                            merged ? MERGE : null,
                            merged ? "main" : null,
                            state == PullRequestState.OPEN ? null : DECIDED,
                            null);
                }

                @Override
                public ForgePages conversation(
                        final PullRequestRef ref, final ForgeCredential credential) {
                    return new ForgePages(
                            pages.stream().map(page -> page.getBytes(UTF_8)).toList(),
                            adapterReason);
                }

                @Override
                public WriteOutcome post(
                        final PullRequestRef ref,
                        final byte[] document,
                        final ForgeCredential credential) {
                    throw new UnsupportedOperationException("an answer writes nothing");
                }
            };

    final ReviewRole role =
            new ReviewRole() {
                @Override
                public Role role(final String task) {
                    return Role.AUTHOR;
                }

                @Override
                public boolean mayPush(final String task) {
                    return true;
                }

                @Override
                public Producer postingAccount(final String task, final ReviewPlace place) {
                    assertThat(place.name()).as("the side asked at the address").isEqualTo(ADDRESS);
                    sidesAsked.add(task);
                    return AUTHOR;
                }

                @Override
                public String postingEntry(final String task, final ReviewPlace place) {
                    throw new UnsupportedOperationException("an answer writes nothing");
                }

                @Override
                public boolean mayOpenThread(final String task, final ReviewPlace place) {
                    return false;
                }

                @Override
                public boolean mayPin(final String task, final String head) {
                    return true;
                }
            };

    final ReviewHolds holds =
            new ReviewHolds() {
                @Override
                public int startRun(final String task, final String place) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public Held hold(
                        final Run run,
                        final String task,
                        final Direction direction,
                        final ReviewItem item,
                        final @Nullable Kept received,
                        final @Nullable String rule) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public Optional<Held> incoming(
                        final String task, final String place, final String received) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public List<Held> undecided(final @Nullable String task) {
                    return held.stream().filter(h -> h.task().equals(task)).toList();
                }

                @Override
                public Optional<Held> find(final String task, final String id) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public Held decide(
                        final String task,
                        final String id,
                        final String digest,
                        final Decision decision) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public List<Held> due(final Instant now) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public Held sending(final String task, final String id) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public void mark(
                        final String task,
                        final String id,
                        final WriteOutcome outcome,
                        final Instant now) {
                    throw new UnsupportedOperationException("not used here");
                }

                @Override
                public int count(final String task) {
                    throw new UnsupportedOperationException("not used here");
                }
            };

    final ReviewOpen.PullRequestParts pullRequests =
            new ReviewOpen.PullRequestParts(
                    forge,
                    (document, channel) -> {
                        final ProfileRead read = reads.get(new String(document, UTF_8));
                        if (read == null || !(channel instanceof ProfileChannel.Adapter)) {
                            throw new ProfileRefused("schema: /runs");
                        }
                        return read;
                    },
                    (push, item) -> {
                        checkedIn.add(push.repository());
                        return CommentFields.check(item);
                    },
                    entry -> {
                        lentEntries.add(entry);
                        return new ForgeCredential(entry, "t-read");
                    },
                    name -> Optional.of(new Named(name)),
                    role,
                    (project, task) ->
                            task.equals(TASK)
                                    ? Optional.of(
                                            new ReviewOpen.Recorded(
                                                    CONTAINER_NAME, CONTAINER, REPOSITORY))
                                    : Optional.empty(),
                    holds);

    /** What the review journal counts for the pinned push; none unless a test says. */
    ReviewCounts counts = push -> new ReviewCounts.Counts(0, 0, List.of());

    /** Knows github.com only, read by the github adapter. */
    final ReviewOpen open =
            new ReviewOpen(
                    new ReviewOpen.Parts(
                            (project, repository, name) -> {
                                throw new AssertionError("no gate push is asked about");
                            },
                            new ReviewStandIns.Store(),
                            push -> counts.of(push),
                            task -> Optional.empty(),
                            url ->
                                    FORGE.equals(url.getHost())
                                            ? Optional.of(
                                                    new ForgeInstance(
                                                            FORGE,
                                                            "github",
                                                            CommentReading.EXTENDED,
                                                            null))
                                            : Optional.empty(),
                            instance -> Optional.of(pullRequests)));

    /** A place known by its name alone, the only part a side is told by. */
    record Named(String name) implements ReviewPlace {

        @Override
        public Pinned pin(final String task) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public Conversation conversation(final Pinned pinned, final ForgeCredential credential) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public WriteOutcome write(final Held held, final ForgeCredential credential) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public CommentReading reading() {
            return CommentReading.EXTENDED;
        }
    }

    static ProfileItem opening(final String id, final ForgeAccount by, final @Nullable Instant at) {
        return new ProfileItem(
                id,
                id,
                by,
                new ReviewComment(
                        new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                        new Body("This loses the order."),
                        null),
                at);
    }

    static ProfileItem reply(final String id, final String thread, final ForgeAccount by) {
        return new ProfileItem(id, thread, by, new ForgeReply(thread, new Body("Done.")), AT);
    }

    void page(final Completeness completeness, final ProfileItem... items) {
        final String name = "page-" + pages.size();
        pages.add(name);
        reads.put(name, new ProfileRead(List.of(items), completeness));
    }

    @Test
    void aCommentsAnchorIsCheckedInTheTasksRepositoryNeverAtTheForgesAddress() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));

        open.answer("uc", PLACE, null, TASK);

        assertThat(checkedIn)
                .as("the mirror of the project's repository, where the head's tree is")
                .containsOnly(REPOSITORY);
    }

    @Test
    void aThreadWhoseLastWordIsAForgeAccountsCountsForTheAuthor() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.points())
                .as("octocat wrote last, the author has not answered")
                .containsExactly(
                        new ReviewOpen.Point(
                                "101", "src/Shop.java", 3, "octocat", "2026-11-25T10:00:00Z"));
        assertThat(answer.decision())
                .as("an open pull request")
                .isEqualTo(ReviewOpen.Decision.OPEN);
        assertThat(answer.head()).as("its head").isEqualTo(HEAD);
        assertThat(lentEntries)
                .as("the forge's read credential lent by its entry")
                .containsExactly("git.token.github.com");
    }

    // An adapter's page of one open thread, octocat's comment its last word, as the forge dated it.
    static final String FORGE_PAGE =
            """
            {"version": "2.1.0", "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
             "properties": {"porelo/profileVersion": "2", "porelo/documentKind": "page"},
             "runs": [{"tool": {"driver": {"name": "Porelo"}},
               "versionControlProvenance": [{"repositoryUri": "https://github.com/uc/shop",
                                             "revisionId": "%s"}],
               "columnKind": "unicodeCodePoints",
               "properties": {"porelo/pullRequest": "%s", "porelo/baseRevisionId": "%s",
                              "porelo/completeness": "complete"},
               "results": [{"guid": "5b2e9d40-1c7a-4f3e-8d6b-2a9c4e7f1b03",
                 "kind": "review", "level": "none", "message": {"text": "This loses the order."},
                 "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Shop.java"},
                                                     "region": {"startLine": 3, "endLine": 5}}}],
                 "properties": {"porelo/anchorKind": "line", "porelo/diffSide": "new",
                                "porelo/threadId": "1834527690", "porelo/threadState": "open",
                                "porelo/sourceKind": "forge-account",
                                "porelo/forgeAccountId": "9001", "porelo/forgeAccountName": "octocat",
                                "porelo/createdAt": "2026-11-25T10:00:00Z"}}]}]}
            """
                    .formatted(HEAD, ADDRESS, BASE);

    @Test
    void anOpenThreadOnARealAdaptersPageIsCompleteAndItsLastWordCarriesTheForgesTime()
            throws Exception {
        pages.add(FORGE_PAGE);
        held.add(held("h1", ADDRESS));
        final ReviewOpen translated =
                new ReviewOpen(
                        new ReviewOpen.Parts(
                                (project, repository, name) -> {
                                    throw new AssertionError("no gate push is asked about");
                                },
                                new ReviewStandIns.Store(),
                                // The pinned push's findings, counted as at the gate: none here.
                                push -> new ReviewCounts.Counts(0, 0, List.of()),
                                task -> Optional.empty(),
                                url ->
                                        Optional.of(
                                                new ForgeInstance(
                                                        FORGE,
                                                        "github",
                                                        CommentReading.EXTENDED,
                                                        null)),
                                instance ->
                                        Optional.of(
                                                new ReviewOpen.PullRequestParts(
                                                        forge,
                                                        new ProfileTranslator(),
                                                        pullRequests.check(),
                                                        pullRequests.credentials(),
                                                        pullRequests.places(),
                                                        role,
                                                        pullRequests.containers(),
                                                        holds))));

        final ReviewOpen.Answer answer = translated.answer("uc", PLACE, null, TASK);

        assertThat(answer.points())
                .as("octocat's open thread, its last word at the time the forge gave it")
                .containsExactly(
                        new ReviewOpen.Point(
                                "1834527690",
                                "src/Shop.java",
                                3,
                                "octocat",
                                "2026-11-25T10:00:00Z"));
        assertThat(answer.held()).as("the one item held at this pull request").isEqualTo(1);
        assertThat(answer.reason()).as("no part missing").isNull();
        assertThat(answer.complete()).as("every item read with its time").isTrue();
    }

    @Test
    void aThreadTheAuthorAnsweredLastIsNoPoint() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT), reply("102", "101", AUTHOR));

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.points()).as("the author answered last").isEmpty();
    }

    @Test
    void aMergedPullRequestAnswersItsMergeCommitAndTheBranchItMergedOnto() throws Exception {
        state = PullRequestState.MERGED;
        page(Completeness.COMPLETE);

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.decision()).as("merged").isEqualTo(ReviewOpen.Decision.MERGED);
        assertThat(answer.mergedCommit()).as("the merge commit").isEqualTo(MERGE);
        assertThat(answer.mergedHead()).as("the branch it went onto").isEqualTo("main");
        assertThat(answer.head()).as("the head that merged").isEqualTo(HEAD);
    }

    @Test
    void aPartialPageAnswersIncompleteWithItsReason() throws Exception {
        page(Completeness.PARTIAL, opening("101", OCTOCAT, AT));

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.complete()).as("a page was cut short").isFalse();
        assertThat(answer.reason()).as("which part").isEqualTo("PagePartial");
        assertThat(answer.points()).as("what was read still counts").hasSize(1);
    }

    @Test
    void theFindingsOfThePinnedPushAreCountedAsAtTheGate() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));
        counts = push -> new ReviewCounts.Counts(3, 0, List.of());

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.findings()).as("the journal's open findings").isEqualTo(3);
        assertThat(answer.complete()).as("everything was read").isTrue();
    }

    @Test
    void aJournalThatCannotBeCountedAnswersNoFindingsAndIncomplete() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));
        counts =
                push -> {
                    throw new IllegalStateException("the journal does not read");
                };

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.findings()).as("nothing counted").isZero();
        assertThat(answer.complete()).as("the journal is missing").isFalse();
        assertThat(answer.reason()).as("which part").isEqualTo("JournalUnreadable");
        assertThat(answer.points()).as("the forge's threads still count").hasSize(1);
    }

    @Test
    void aRateLimitedReadAnswersIncompleteWithItsReason() throws Exception {
        page(Completeness.PARTIAL, opening("101", OCTOCAT, AT));
        adapterReason = "RateLimited";

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.complete()).as("the forge asked to wait").isFalse();
        assertThat(answer.reason()).as("which part").isEqualTo("RateLimited");
    }

    @Test
    void anUnknownHostIsRefusedByName() {
        final ReviewOpen.Place elsewhere =
                new ReviewOpen.PullRequest(
                        PullRequestRef.of(URI.create("https://forge.example.org/uc/shop/pull/7")));

        assertThatThrownBy(() -> open.answer("uc", elsewhere, null, TASK))
                .as("a host that is no forge instance this machine knows")
                .isInstanceOfSatisfying(
                        ReviewOpen.Refused.class,
                        refused -> {
                            assertThat(refused.error()).as("the error").isEqualTo("UnknownForge");
                            assertThat(refused.fields())
                                    .as("the host named")
                                    .isEqualTo(Map.of("host", "forge.example.org"));
                        });
    }

    @Test
    void aPullRequestTheForgeDoesNotKnowIsRefusedWithItsAddress() {
        final ReviewOpen.Place gone =
                new ReviewOpen.PullRequest(
                        PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/8")));

        assertThatThrownBy(() -> open.answer("uc", gone, null, TASK))
                .as("no such pull request")
                .isInstanceOfSatisfying(
                        ReviewOpen.Refused.class,
                        refused -> {
                            assertThat(refused.error())
                                    .as("the error")
                                    .isEqualTo("NoSuchPullRequest");
                            assertThat(refused.fields())
                                    .as("the address named")
                                    .isEqualTo(Map.of("url", "https://github.com/uc/shop/pull/8"));
                        });
    }

    @Test
    void aMissingTokenIsRefusedAsPlaceUnreadableWithItsReason() {
        final ReviewOpen withoutToken =
                new ReviewOpen(
                        new ReviewOpen.Parts(
                                (project, repository, name) -> {
                                    throw new AssertionError("no gate push is asked about");
                                },
                                new ReviewStandIns.Store(),
                                // The pinned push's findings, counted as at the gate: none here.
                                push -> new ReviewCounts.Counts(0, 0, List.of()),
                                task -> Optional.empty(),
                                url ->
                                        Optional.of(
                                                new ForgeInstance(
                                                        FORGE,
                                                        "github",
                                                        CommentReading.EXTENDED,
                                                        null)),
                                instance ->
                                        Optional.of(
                                                new ReviewOpen.PullRequestParts(
                                                        forge,
                                                        pullRequests.reading(),
                                                        pullRequests.check(),
                                                        entry -> {
                                                            throw new PlaceUnreadable(
                                                                    PlaceUnreadable.Reason
                                                                            .MISSING_FORGE_TOKEN,
                                                                    "the vault holds no " + entry);
                                                        },
                                                        pullRequests.places(),
                                                        role,
                                                        pullRequests.containers(),
                                                        holds))));

        assertThatThrownBy(() -> withoutToken.answer("uc", PLACE, null, TASK))
                .as("no read credential")
                .isInstanceOfSatisfying(
                        ReviewOpen.Refused.class,
                        refused -> {
                            assertThat(refused.error())
                                    .as("the error")
                                    .isEqualTo("PlaceUnreadable");
                            assertThat(refused.fields())
                                    .as("its reason")
                                    .containsEntry("reason", "MissingForgeToken");
                        });
    }

    @Test
    void itemsHeldForTheTaskAtThisPullRequestCountAsHeldNeverAsPoints() throws Exception {
        page(Completeness.COMPLETE);
        held.add(held("h1", ADDRESS));
        held.add(held("h2", "https://github.com/uc/shop/pull/9"));

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.held()).as("only the one held at this pull request").isEqualTo(1);
        assertThat(answer.points()).as("a held item is no point").isEmpty();
    }

    @Test
    void equalReadsAnswerEqualVersionsAndAnotherReadAnother() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));

        final String first = open.answer("uc", PLACE, null, TASK).version();
        final String again = open.answer("uc", PLACE, null, TASK).version();
        reads.put(
                pages.getFirst(),
                new ProfileRead(
                        List.of(opening("101", OCTOCAT, AT), reply("102", "101", AUTHOR)),
                        Completeness.COMPLETE));
        final String answered = open.answer("uc", PLACE, null, TASK).version();

        assertThat(again).as("read anew, the same answer").isEqualTo(first);
        assertThat(answered).as("an answer changes the version").isNotEqualTo(first);
    }

    @Test
    void theWireFormCarriesTheContractsFields() throws Exception {
        page(Completeness.COMPLETE, opening("101", OCTOCAT, AT));

        final Map<String, Object> fields = open.answer("uc", PLACE, null, TASK).fields();

        final Map<String, Object> point = new LinkedHashMap<>();
        point.put("thread", "101");
        point.put("path", "src/Shop.java");
        point.put("line", 3);
        point.put("lastBy", "octocat");
        point.put("lastAt", "2026-11-25T10:00:00Z");
        assertThat(fields)
                .as("the contract's names; an open pull request's decision needs no time")
                .containsOnlyKeys(
                        "head", "decision", "points", "findings", "held", "complete", "version")
                .containsEntry("points", List.of(point))
                .containsEntry("complete", true);
    }

    @Test
    void aMergedOrClosedPullRequestIsCompleteSinceTheForgeDecidedIt() throws Exception {
        page(Completeness.COMPLETE);
        for (final PullRequestState decided :
                List.of(PullRequestState.MERGED, PullRequestState.CLOSED)) {
            state = decided;

            final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

            assertThat(answer.complete())
                    .as("%s: every part was read, its time too", decided)
                    .isTrue();
            assertThat(answer.reason()).as("%s: nothing missing", decided).isNull();
            assertThat(answer.decisionSince())
                    .as("%s: since the forge decided it", decided)
                    .isEqualTo("2026-11-20T10:15:30Z");
        }
    }

    @Test
    void anOpenPullRequestTheForgeShowsApprovedIsApproved() throws Exception {
        approved = true;
        page(Completeness.COMPLETE);

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.decision())
                .as("an approving review stands")
                .isEqualTo(ReviewOpen.Decision.APPROVED);
        assertThat(answer.complete()).as("still open, so no decision time to miss").isTrue();
    }

    @Test
    void aMergedPullRequestIsMergedWhetherOrNotItWasApproved() throws Exception {
        approved = true;
        state = PullRequestState.MERGED;
        page(Completeness.COMPLETE);

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.decision())
                .as("the merge decides, not the approval before it")
                .isEqualTo(ReviewOpen.Decision.MERGED);
    }

    static Held held(final String id, final String place) {
        return new Held(
                id,
                Direction.OUTGOING,
                CONTAINER_NAME,
                1,
                place,
                HEAD,
                new ForgeReply("101", new Body("Done.")),
                null,
                "d".repeat(64),
                AT,
                HeldState.UNDECIDED,
                null,
                null);
    }

    @Test
    void aTasksSideAndHeldItemsAreReadUnderItsContainerNameNeverItsTaskName() throws Exception {
        page(Completeness.COMPLETE);
        held.add(held("h1", ADDRESS));

        final ReviewOpen.Answer answer = open.answer("uc", PLACE, null, TASK);

        assertThat(answer.held()).as("the item held under its container").isEqualTo(1);
        assertThat(sidesAsked)
                .as("its side asked under its container")
                .containsExactly(CONTAINER_NAME);
    }
}
