package org.fuin.porelo.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Each comment a run reads is judged by its place's reading setting before the run sees it. */
class CommentGateCheckTest {

    private static final String TASK = "shop-2";

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/7"));

    private static final PullRequestFacts FACTS =
            new PullRequestFacts(
                    REF,
                    "https://github.com/uc/shop",
                    SHOP.head(),
                    "f".repeat(40),
                    "f".repeat(40),
                    PullRequestState.OPEN,
                    false,
                    null,
                    null,
                    null,
                    null);

    private static final Run RUN = new Run(REF.url().toString(), new Pinned(SHOP, FACTS), 1);

    private static final ForgeAccount OCTOCAT = new ForgeAccount("github.com", "583231", "octocat");

    private static final Kept COMMENT =
            new Kept(
                    "101",
                    "101",
                    Origin.of(OCTOCAT),
                    new ReviewComment(
                            new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                            new Body("Looks fine to me."),
                            null),
                    Instant.parse("2026-11-25T10:00:00Z"));

    private static final Kept REPLY =
            new Kept(
                    "102",
                    "101",
                    Origin.of(OCTOCAT),
                    new ForgeReply("101", new Body("Agreed.")),
                    Instant.parse("2026-11-25T10:05:00Z"));

    private final Writing writing = new Writing();
    private final Filter filter = new Filter();
    private final Holds holds = new Holds();
    private final Place place = new Place();
    private final CommentGateCheck gate = new CommentGateCheck(writing, filter, holds, TASK);

    @Test
    void aReplyIsWrittenForTheFilterWithItsThreadsOpeningComment() {
        final Optional<String> judged = gate.judge(place, RUN, REPLY, COMMENT);

        assertThat(judged).as("a reply the filter passes").isEmpty();
        assertThat(writing.roots)
                .as("the opening comment whose place the reply is written at")
                .containsExactly(COMMENT);
        assertThat(filter.checked)
                .as("the filter given the reply's page")
                .singleElement()
                .isEqualTo(Writing.page(REPLY));
    }

    @Test
    void aCommentTheFilterRefusesIsWithheldAndHeldWithItsRule() {
        // Whoever wrote it cannot be told apart, so it is judged as an external peer's message.
        filter.rule = "zero-width-characters";

        final Optional<String> judged = gate.judge(place, RUN, COMMENT, COMMENT);

        assertThat(judged).as("withheld by the filter's rule").contains("zero-width-characters");
        assertThat(filter.checked)
                .as("the filter given the comment as a one-result page")
                .singleElement()
                .isEqualTo(Writing.page(COMMENT));
        assertThat(filter.peers)
                .as("from the forge's host as the peer")
                .containsExactly("github.com");
        assertThat(writing.facts).as("written with the run's pinned facts").containsExactly(FACTS);
        assertThat(holds.held)
                .as("held as incoming for the owner account, within the run, with the rule")
                .singleElement()
                .satisfies(
                        held -> {
                            assertThat(held.direction())
                                    .as("incoming")
                                    .isEqualTo(Direction.INCOMING);
                            assertThat(held.task()).as("the run's task").isEqualTo(TASK);
                            assertThat(held.run()).as("the run").isEqualTo(RUN.number());
                            assertThat(held.reason())
                                    .as("the rule")
                                    .isEqualTo("zero-width-characters");
                            assertThat(held.received())
                                    .as("as the place gave it")
                                    .isEqualTo(COMMENT);
                        });
        assertThat(holds.received)
                .as("the producer unchanged")
                .singleElement()
                .extracting(Kept::origin)
                .isEqualTo(Origin.of(OCTOCAT));
    }

    @Test
    void aFilterThatCannotRunWithholdsTheComment() {
        // A machine that cannot check is not a machine that may skip checking.
        filter.broken = true;

        final Optional<String> judged = gate.judge(place, RUN, COMMENT, COMMENT);

        assertThat(judged)
                .as("withheld, the rule naming that the filter could not run")
                .contains(CommentGateCheck.FILTER_UNAVAILABLE);
        assertThat(holds.rules)
                .as("held with that rule")
                .containsExactly(CommentGateCheck.FILTER_UNAVAILABLE);
    }

    @Test
    void aCommentTheFilterPassesReachesTheRunUnheld() {
        final Optional<String> judged = gate.judge(place, RUN, COMMENT, COMMENT);

        assertThat(judged).as("it passes").isEmpty();
        assertThat(filter.checked).as("the filter asked once").hasSize(1);
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void underTrustedTheSameCommentPassesWithoutTheFilter() {
        // The format and size checks already ran on the input path.
        place.reading = CommentReading.TRUSTED;
        filter.rule = "zero-width-characters";

        final Optional<String> judged = gate.judge(place, RUN, COMMENT, COMMENT);

        assertThat(judged).as("it passes").isEmpty();
        assertThat(filter.checked).as("the filter never asked").isEmpty();
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void underExtendedAPlaceWithoutPullRequestFactsWithholdsTheComment() {
        // No page can be written for the filter, so nothing passes unchecked.
        final Run atGate = new Run("shop/shop-2", new Pinned(SHOP, null), 1);

        final Optional<String> judged = gate.judge(place, atGate, COMMENT, COMMENT);

        assertThat(judged).as("withheld").contains(CommentGateCheck.NO_PAGE);
        assertThat(filter.checked).as("the filter never asked").isEmpty();
        assertThat(holds.rules).as("held with that rule").containsExactly(CommentGateCheck.NO_PAGE);
    }

    @Test
    void aCommentWithheldInOneRunIsJudgedAgainInTheNext() {
        // Everything is read again each run, so an earlier verdict decides nothing.
        filter.rule = "zero-width-characters";
        gate.judge(place, RUN, COMMENT, COMMENT);
        filter.rule = null;
        final Run next = new Run(RUN.place(), RUN.pinned(), 2);

        final Optional<String> judged = gate.judge(place, next, COMMENT, COMMENT);

        assertThat(judged).as("it passes in the next run").isEmpty();
        assertThat(filter.checked).as("the filter asked in both runs").hasSize(2);
        assertThat(holds.held).as("held in the first run only").hasSize(1);
    }

    /** Writes a fixed page per item and records the facts it was given. */
    private static final class Writing implements ProfileWriting {
        final List<PullRequestFacts> facts = new ArrayList<>();
        final List<Kept> roots = new ArrayList<>();

        static byte[] page(final Kept item) {
            return ("page of " + item.guid()).getBytes(UTF_8);
        }

        @Override
        public List<byte[]> pages(final PullRequestFacts facts, final Conversation conversation) {
            throw new UnsupportedOperationException("no pages here");
        }

        @Override
        public byte[] one(final PullRequestFacts facts, final Kept item, final Kept root) {
            this.facts.add(facts);
            roots.add(root);
            return page(item);
        }

        @Override
        public byte[] submission(
                final PullRequestFacts facts, final ReviewItem item, final LineAnchor place) {
            throw new UnsupportedOperationException("no submission here");
        }
    }

    /** Answers a fixed rule, none, or that it cannot run, and records what it checked. */
    private static final class Filter implements InboundFilter {
        final List<byte[]> checked = new ArrayList<>();
        final List<String> peers = new ArrayList<>();
        @Nullable String rule;
        boolean broken;

        @Override
        public Optional<String> refuse(final String peer, final byte[] data) throws IOException {
            checked.add(data);
            peers.add(peer);
            if (broken) {
                throw new IOException("no message filter installed");
            }
            return Optional.ofNullable(rule);
        }
    }

    /** Records each hold; nothing else is asked here. */
    private static final class Holds implements ReviewHolds {

        @Override
        public Held sending(final String task, final String id) {
            throw new UnsupportedOperationException("not used here");
        }

        final List<Held> held = new ArrayList<>();
        final List<Kept> received = new ArrayList<>();
        final List<String> rules = new ArrayList<>();

        @Override
        public Held hold(
                final Run run,
                final String task,
                final Direction direction,
                final ReviewItem item,
                final @Nullable Kept received,
                final @Nullable String rule) {
            this.received.add(received);
            rules.add(rule);
            final Held one =
                    new Held(
                            "h" + held.size(),
                            direction,
                            task,
                            run.number(),
                            run.place(),
                            run.push().head(),
                            item,
                            received,
                            "0".repeat(64),
                            Instant.parse("2026-11-25T10:00:00Z"),
                            HeldState.UNDECIDED,
                            rule,
                            null);
            held.add(one);
            return one;
        }

        @Override
        public int startRun(final String task, final String place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Held> incoming(final String task, final String place, final String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Held> undecided(final @Nullable String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Held> find(final String task, final String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Held decide(
                final String task, final String id, final String digest, final Decision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Held> due(final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mark(
                final String task, final String id, final WriteOutcome outcome, final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int count(final String task) {
            throw new UnsupportedOperationException();
        }
    }

    /** A place that answers only its reading setting, extended unless set trusted. */
    private static final class Place implements ReviewPlace {
        CommentReading reading = CommentReading.EXTENDED;

        @Override
        public String name() {
            return REF.url().toString();
        }

        @Override
        public Pinned pin(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Conversation conversation(final Pinned pinned, final ForgeCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteOutcome write(final Held held, final ForgeCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommentReading reading() {
            return reading;
        }
    }
}
