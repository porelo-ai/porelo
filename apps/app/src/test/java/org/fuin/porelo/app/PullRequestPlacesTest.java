package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.ThreadResolve;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pull request's place, found by its address at a forge this machine knows. */
class PullRequestPlacesTest {

    private static final String URL = "https://review.stub.invalid/shop/app/pull/42";

    private static final PullRequestRef REF = PullRequestRef.of(URI.create(URL));

    private static final String HEAD = "a".repeat(40);

    private static final Push PINNED =
            new Push("uc", "shop", "pr-42", HEAD, "b".repeat(40), "c".repeat(64));

    /** Answers a fixed list of instances. */
    private static final class Forges implements ForgeInstances {
        final List<ForgeInstance> known = new ArrayList<>();
        boolean unreadable;

        @Override
        public Optional<ForgeInstance> of(final URI url) throws ForgeFileUnreadable {
            if (unreadable) {
                throw new ForgeFileUnreadable("not owner-only");
            }
            return known.stream().filter(each -> each.host().equals(url.getHost())).findFirst();
        }

        @Override
        public List<ForgeInstance> all() {
            return known;
        }

        @Override
        public void set(final String host, final String adapter, final CommentReading reading) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Answers fixed facts and conversation pages and records what it was asked with which entry.
     */
    private static final class Forge implements ForgeReview {
        final List<String> asked = new ArrayList<>();
        final List<byte[]> pages = new ArrayList<>();
        final Map<String, List<byte[]>> pagesAt = new HashMap<>();
        String head = HEAD;

        @Override
        public PullRequestFacts facts(final PullRequestRef ref, final ForgeCredential credential) {
            asked.add("facts " + credential.entry());
            return factsAt(head);
        }

        @Override
        public ForgePages conversation(final PullRequestRef ref, final ForgeCredential credential) {
            asked.add("conversation " + credential.entry());
            return new ForgePages(pagesAt.getOrDefault(ref.url().toString(), pages), null);
        }

        @Override
        public WriteOutcome post(
                final PullRequestRef ref, final byte[] document, final ForgeCredential credential) {
            asked.add(
                    "post "
                            + credential.entry()
                            + " "
                            + new String(document, StandardCharsets.UTF_8));
            return new WriteOutcome.Delivered("9100000001");
        }

        @Override
        public WriteOutcome resolve(
                final PullRequestRef ref, final String thread, final ForgeCredential credential) {
            asked.add("resolve " + credential.entry() + " " + thread);
            return new WriteOutcome.Delivered(thread);
        }
    }

    private static PullRequestFacts factsAt(final String head) {
        return new PullRequestFacts(
                REF,
                "https://review.stub.invalid/shop/app",
                head,
                "b".repeat(40),
                "b".repeat(40),
                PullRequestState.OPEN,
                false,
                null,
                null,
                null,
                null);
    }

    /** Writes each call's kind, the head it was given and the place a submission stands at. */
    private static final class Writing implements ProfileWriting {
        @Override
        public List<byte[]> pages(final PullRequestFacts facts, final Conversation conversation) {
            return List.of(("pages " + facts.head()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public byte[] one(final PullRequestFacts facts, final Kept item, final Kept root) {
            return "one".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] submission(
                final PullRequestFacts facts, final ReviewItem item, final LineAnchor place) {
            return ("submission " + facts.head() + " " + place.path() + ":" + place.startLine())
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private final Forges forges = new Forges();
    private final Forge forge = new Forge();
    private final List<String> installed = new ArrayList<>(List.of("stub-forge"));
    private final List<String> pinnedFor = new ArrayList<>();
    private final Map<String, TaskParts> roles = new HashMap<>();
    private final List<String> authorsPinned = new ArrayList<>();
    private final Map<String, ProfileRead> readPages = new HashMap<>();

    private final PullRequestPlaces places =
            new PullRequestPlaces(
                    forges,
                    installed::contains,
                    adapter -> forge,
                    review ->
                            (ref, task) -> {
                                pinnedFor.add(task + " " + ref.url());
                                return PINNED;
                            },
                    (document, channel) -> {
                        final ProfileRead read =
                                readPages.get(new String(document, StandardCharsets.UTF_8));
                        if (read == null) {
                            throw new UnsupportedOperationException();
                        }
                        return read;
                    },
                    new ReviewStandIns.Check(),
                    new Writing(),
                    entry -> new ForgeCredential(entry, "t"),
                    task ->
                            roles.getOrDefault(
                                            task,
                                            org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                                    java.util.List.of(
                                                            org.fuin.porelo.gate.review.Right.REPLY,
                                                            org.fuin.porelo.gate.review.Right
                                                                    .OPEN_THREADS)))
                                    .worksOnItsOwn(),
                    (task, facts) -> {
                        authorsPinned.add(task + " " + facts.head());
                        if (!HEAD.equals(facts.head())) {
                            throw new PlaceUnreadable(
                                    PlaceUnreadable.Reason.NOT_IN_PROJECT,
                                    "not the head its gate forwarded");
                        }
                        return new Push(
                                "uc", "shop", "pr-42", facts.head(), facts.base(), "f".repeat(64));
                    });

    private void known(final CommentReading reading) {
        forges.known.add(new ForgeInstance("review.stub.invalid", "stub-forge", reading, null));
    }

    @Test
    void aPullRequestAtAKnownForgeIsAPlaceNamedByItsAddress() {
        known(CommentReading.EXTENDED);

        final Optional<ReviewPlace> place = places.named(URL);

        assertThat(place).as("found").isPresent();
        assertThat(place.get().name()).as("its name").isEqualTo(URL);
        assertThat(place.get().reading())
                .as("its comments read as the forge's setting says")
                .isEqualTo(CommentReading.EXTENDED);
    }

    @Test
    void aTrustedForgesPlaceReadsItsCommentsAsTrusted() {
        known(CommentReading.TRUSTED);

        assertThat(places.named(URL).orElseThrow().reading())
                .as("the setting")
                .isEqualTo(CommentReading.TRUSTED);
    }

    @Test
    void aForgeTheMachineDoesNotKnowIsUnknownForge() {
        assertThat(places.named(URL)).as("no place").isEmpty();
        assertThatThrownBy(() -> places.place(REF))
                .as("why")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the reason")
                                        .isEqualTo(PlaceUnreadable.Reason.UNKNOWN_FORGE));
    }

    @Test
    void aForgeWhoseAdapterIsNotInstalledIsAdapterMissing() {
        known(CommentReading.EXTENDED);
        installed.clear();

        assertThatThrownBy(() -> places.place(REF))
                .as("no adapter")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the reason")
                                        .isEqualTo(PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING));
    }

    @Test
    void anUnreadableForgeFileIsSaidSo() {
        forges.unreadable = true;

        assertThatThrownBy(() -> places.place(REF))
                .as("the file")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the reason")
                                        .isEqualTo(PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE));
    }

    @Test
    void aNameThatIsNoPullRequestsAddressIsNoPlace() {
        known(CommentReading.EXTENDED);

        assertThat(places.named("shop/pr-42")).as("a gate push's name").isEmpty();
    }

    @Test
    void aPinIsThePinnedPushWithTheFactsReadWithTheForgesCredential() throws Exception {
        known(CommentReading.EXTENDED);

        final Pinned pinned = places.place(REF).pin("porelo-uc-shop-2");

        assertThat(pinnedFor)
                .as("pinned into the task's gate")
                .containsExactly("porelo-uc-shop-2 " + URL);
        assertThat(pinned.push()).as("the push").isEqualTo(PINNED);
        assertThat(pinned.facts()).as("the facts").isEqualTo(factsAt(HEAD));
        assertThat(forge.asked)
                .as("read with the forge's own entry")
                .containsExactly("facts git.token.review.stub.invalid");
    }

    @Test
    void aPullRequestThatMovedWhileItWasPinnedPinsNothing() {
        known(CommentReading.EXTENDED);
        forge.head = "d".repeat(40);

        assertThatThrownBy(() -> places.place(REF).pin("porelo-uc-shop-2"))
                .as("a head that moved")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the reason")
                                        .isEqualTo(PlaceUnreadable.Reason.MOVED_SINCE_REVIEW));
    }

    @Test
    void theConversationsPagesAreWrittenAtThePinnedHead() throws Exception {
        known(CommentReading.EXTENDED);
        final PullRequestPlace place = places.place(REF);
        final Pinned pinned = place.pin("porelo-uc-shop-2");

        final List<byte[]> pages =
                place.pages(
                        pinned,
                        new Conversation(
                                List.of(),
                                Set.of(),
                                List.of(),
                                org.fuin.porelo.gate.review.Completeness.COMPLETE,
                                null));

        assertThat(pages)
                .as("the writer's pages")
                .extracting(page -> new String(page, StandardCharsets.UTF_8))
                .containsExactly("pages " + HEAD);
    }

    private static Held released(final String commit) {
        final ReviewItem item =
                new ReviewComment(
                        new LineAnchor("src/Shop.java", Side.NEW, 3, 5), new Body("Why?"), null);
        return new Held(
                "h-1",
                Direction.OUTGOING,
                "porelo-uc-shop-2",
                1,
                URL,
                commit,
                item,
                null,
                "e".repeat(64),
                Instant.parse("2026-11-25T10:00:00Z"),
                HeldState.RELEASED,
                null,
                null);
    }

    @Test
    void aReleasedItemIsPostedWithTheCredentialLentForIt() throws Exception {
        known(CommentReading.EXTENDED);

        final WriteOutcome outcome =
                places.place(REF)
                        .write(
                                released(HEAD),
                                new ForgeCredential("git.token.review.stub.invalid", "w"));

        assertThat(outcome).as("written").isEqualTo(new WriteOutcome.Delivered("9100000001"));
        assertThat(forge.asked)
                .as("the facts read, then one post against the held commit")
                .containsExactly(
                        "facts git.token.review.stub.invalid",
                        "post git.token.review.stub.invalid submission "
                                + HEAD
                                + " src/Shop.java:3");
    }

    @Test
    void anItemHeldForAHeadThePullRequestMovedPastIsNotPosted() throws Exception {
        known(CommentReading.EXTENDED);
        forge.head = "d".repeat(40);

        final WriteOutcome outcome =
                places.place(REF)
                        .write(
                                released(HEAD),
                                new ForgeCredential("git.token.review.stub.invalid", "w"));

        assertThat(outcome)
                .as("refused")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.MOVED_SINCE_REVIEW));
        assertThat(forge.asked).as("nothing posted").noneMatch(call -> call.startsWith("post"));
    }

    @Test
    void anAuthorIsPinnedToTheFactsHeadWithoutFetchingIt() throws Exception {
        known(CommentReading.EXTENDED);
        roles.put("porelo-uc-shop-1", TaskParts.ownWork());

        final Pinned pinned = places.place(REF).pin("porelo-uc-shop-1");

        assertThat(pinnedFor).as("nothing fetched into its gate").isEmpty();
        assertThat(authorsPinned)
                .as("its own gate's check asked with the facts' head")
                .containsExactly("porelo-uc-shop-1 " + HEAD);
        assertThat(pinned.push().head()).as("pinned to the facts' head").isEqualTo(HEAD);
        assertThat(pinned.facts()).as("the facts").isEqualTo(factsAt(HEAD));
    }

    @Test
    void anAuthorWhoseGateDidNotForwardTheHeadIsNotPinned() {
        known(CommentReading.EXTENDED);
        roles.put("porelo-uc-shop-1", TaskParts.ownWork());
        forge.head = "d".repeat(40);

        assertThatThrownBy(() -> places.place(REF).pin("porelo-uc-shop-1"))
                .as("a head its gate never forwarded")
                .isInstanceOf(PlaceUnreadable.class);
        assertThat(pinnedFor).as("nothing fetched").isEmpty();
    }

    private static Held releasedReply(final String thread) {
        return new Held(
                "h-2",
                Direction.OUTGOING,
                "porelo-uc-shop-2",
                1,
                URL,
                HEAD,
                new ForgeReply(thread, new Body("Fixed.")),
                null,
                "e".repeat(64),
                Instant.parse("2026-11-25T10:00:00Z"),
                HeldState.RELEASED,
                null,
                null);
    }

    private void forgeShows(final String thread, final LineAnchor at) {
        forge.pages.add("page-1".getBytes(StandardCharsets.UTF_8));
        readPages.put(
                "page-1",
                new ProfileRead(
                        List.of(
                                new ProfileItem(
                                        thread,
                                        thread,
                                        new ForgeAccount("review.stub.invalid", "7", "octocat"),
                                        new ReviewComment(at, new Body("Why?"), null),
                                        null)),
                        Set.of(),
                        Completeness.COMPLETE));
    }

    @Test
    void aReplyIsWrittenAtWhereItsThreadStandsOnTheForgeNow() throws Exception {
        known(CommentReading.EXTENDED);
        forgeShows("9100000007", new LineAnchor("src/Cart.java", Side.NEW, 11, 11));

        final WriteOutcome outcome =
                places.place(REF)
                        .write(
                                releasedReply("9100000007"),
                                new ForgeCredential("git.token.review.stub.invalid", "w"));

        assertThat(outcome).as("written").isEqualTo(new WriteOutcome.Delivered("9100000001"));
        assertThat(forge.asked)
                .as("the facts and the conversation read, then one post at the thread's place")
                .containsExactly(
                        "facts git.token.review.stub.invalid",
                        "conversation git.token.review.stub.invalid",
                        "post git.token.review.stub.invalid submission "
                                + HEAD
                                + " src/Cart.java:11");
    }

    @Test
    void aHeldResolveReachesTheForgeOnlyAfterItsReleaseOnceAndAsTheLentAccount(
            @TempDir final java.nio.file.Path dir) throws Exception {
        // The forge stand-in records the resolve call: none before the owner account releases it,
        // then one, sent with the write credential lent for it after the head was read.
        known(CommentReading.EXTENDED);
        forgeShows("9100000007", new LineAnchor("src/Cart.java", Side.NEW, 11, 11));
        final PullRequestPlace place = places.place(REF);
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"));
        final String task = "porelo-uc-shop-2";
        final Run run =
                new Run(place.name(), new Pinned(PINNED, factsAt(HEAD)), holds.startRun(task, URL));
        final Held held =
                holds.hold(
                        run, task, Direction.OUTGOING, new ThreadResolve("9100000007"), null, null);
        final Rights rights = new Rights();
        final ReleasedSender sender =
                new ReleasedSender(
                        holds,
                        name -> Optional.of(place),
                        rights,
                        entry -> new ForgeCredential(entry, "w"),
                        Clock.systemUTC());

        sender.send();
        assertThat(forge.asked).as("nothing before the release").isEmpty();

        holds.decide(task, held.id(), held.digest(), Decision.RELEASE);
        sender.send();
        sender.send();

        assertThat(forge.asked)
                .as(
                        "the head and the thread read with the forge's read credential, then one"
                                + " resolve as the account")
                .containsExactly(
                        "facts git.token.review.stub.invalid",
                        "conversation git.token.review.stub.invalid",
                        "resolve " + Rights.ENTRY + " 9100000007");
        assertThat(holds.find(task, held.id()))
                .as("delivered")
                .hasValueSatisfying(now -> assertThat(now.state()).isEqualTo(HeldState.DELIVERED));
    }

    @Test
    void aResolveOfAThreadOfAnotherPullRequestIsNeverSent(@TempDir final java.nio.file.Path dir)
            throws Exception {
        // The forge holds thread 9100000007 at pull request 42 alone; the run is at pull request
        // 43.
        // Guarded and online alike, a released resolve reaches the forge only through this write.
        known(CommentReading.EXTENDED);
        forgeShows("9100000007", new LineAnchor("src/Cart.java", Side.NEW, 11, 11));
        final String other = "https://review.stub.invalid/shop/app/pull/43";
        forge.pagesAt.put(URL, forge.pages);
        forge.pagesAt.put(other, List.of());
        final PullRequestPlace place = places.place(PullRequestRef.of(URI.create(other)));
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"));
        final String task = "porelo-uc-shop-2";
        final Run run =
                new Run(
                        place.name(),
                        new Pinned(PINNED, factsAt(HEAD)),
                        holds.startRun(task, other));
        final Held held =
                holds.hold(
                        run, task, Direction.OUTGOING, new ThreadResolve("9100000007"), null, null);
        final ReleasedSender sender =
                new ReleasedSender(
                        holds,
                        name -> Optional.of(place),
                        new Rights(),
                        entry -> new ForgeCredential(entry, "w"),
                        Clock.systemUTC());
        holds.decide(task, held.id(), held.digest(), Decision.RELEASE);

        sender.send();

        assertThat(forge.asked)
                .as("nothing resolved")
                .noneMatch(call -> call.startsWith("resolve"));
        assertThat(holds.find(task, held.id()).orElseThrow().reason())
                .as("why")
                .isEqualTo(ForgeWrite.NO_SUCH_THREAD);
    }

    @Test
    void aReleasedResolveOfATaskThatLostTheRightOrForAMovedHeadIsNeverSent(
            @TempDir final java.nio.file.Path dir) throws Exception {
        known(CommentReading.EXTENDED);
        final PullRequestPlace place = places.place(REF);
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"));
        final String task = "porelo-uc-shop-2";
        final Run run =
                new Run(place.name(), new Pinned(PINNED, factsAt(HEAD)), holds.startRun(task, URL));
        final Held lost =
                holds.hold(run, task, Direction.OUTGOING, new ThreadResolve("91"), null, null);
        final Rights rights = new Rights();
        final ReleasedSender sender =
                new ReleasedSender(
                        holds,
                        name -> Optional.of(place),
                        rights,
                        entry -> new ForgeCredential(entry, "w"),
                        Clock.systemUTC());
        holds.decide(task, lost.id(), lost.digest(), Decision.RELEASE);
        rights.resolving = false;

        sender.send();

        assertThat(forge.asked).as("no credential used, nothing asked").isEmpty();
        assertThat(rights.lent).as("no credential lent").isZero();
        assertThat(holds.find(task, lost.id()).orElseThrow().reason())
                .as("why")
                .isEqualTo("RightMissing: resolveThreads (resolve a thread)");

        rights.resolving = true;
        forge.head = "d".repeat(40);
        final Held moved =
                holds.hold(run, task, Direction.OUTGOING, new ThreadResolve("92"), null, null);
        holds.decide(task, moved.id(), moved.digest(), Decision.RELEASE);
        sender.send();

        assertThat(forge.asked)
                .as("nothing resolved")
                .noneMatch(call -> call.startsWith("resolve"));
        assertThat(holds.find(task, moved.id()).orElseThrow().reason())
                .as("why")
                .isEqualTo(ForgeWrite.MOVED_SINCE_REVIEW);
    }

    /** Names one write entry and grants a resolve or not, counting each entry it named. */
    private static final class Rights implements org.fuin.porelo.gate.review.TaskRights {
        static final String ENTRY = "git.token.review.stub.invalid.write";
        boolean resolving = true;
        int lent;

        @Override
        public TaskParts parts(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPush(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.fuin.porelo.gate.review.Producer postingAccount(
                final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String postingEntry(final String task, final ReviewPlace place) {
            lent++;
            return ENTRY;
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            return false;
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            return false;
        }

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            return resolving;
        }

        @Override
        public boolean mayReject(final String task, final ReviewPlace place) {
            return false;
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aReplyToAThreadTheForgeNoLongerShowsIsRefusedUnposted() throws Exception {
        known(CommentReading.EXTENDED);
        forgeShows("9100000007", new LineAnchor("src/Cart.java", Side.NEW, 11, 11));

        final WriteOutcome outcome =
                places.place(REF)
                        .write(
                                releasedReply("9100000008"),
                                new ForgeCredential("git.token.review.stub.invalid", "w"));

        assertThat(outcome)
                .as("refused")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.NO_SUCH_THREAD));
        assertThat(forge.asked).as("nothing posted").noneMatch(call -> call.startsWith("post"));
    }
}
