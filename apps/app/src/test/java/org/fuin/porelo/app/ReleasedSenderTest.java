package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewRole;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Guards the pass that hands released items to their places: each is written once and its answer
 * marked, a rate limit is waited out, an unknown outcome is never sent again, a missing place or
 * credential is marked with its reason, and one item's failure never keeps the others back.
 */
class ReleasedSenderTest {

    private static final String TASK = "shop-2";

    private static final String PLACE = "https://forge.example/shop/app/pull/7";

    private static final String OTHER_PLACE = "https://forge.example/shop/app/pull/8";

    private static final String EMPTY_PLACE = "https://forge.example/shop/app/pull/10";

    private static final String EMPTY_ENTRY = "git.token.empty.example";

    private static final String GONE_PLACE = "https://forge.example/shop/app/pull/9";

    private static final String COMMIT = "1".repeat(40);

    private static final String ENTRY = "git.token.forge.example";

    private static final Instant START = Instant.parse("2026-11-25T12:00:00Z");

    @Test
    void anItemNotReleasedIsNeverHandedToItsPlace() {
        final Fixture fixture = new Fixture();
        for (final HeldState state :
                List.of(
                        HeldState.UNDECIDED,
                        HeldState.REFUSED,
                        HeldState.LAPSED,
                        HeldState.DELIVERED,
                        HeldState.OUTCOME_UNKNOWN)) {
            fixture.holds.add(held(state.name().toLowerCase(), PLACE, state, null, null));
        }
        fixture.holds.add(held("waiting", PLACE, HeldState.RETRY, null, START.plusSeconds(1)));
        fixture.holds.add(held("refused", PLACE, HeldState.NOT_DELIVERED, "TooLong", null));
        // A hold that lists more than it should is still not obeyed.
        fixture.holds.everything = true;

        assertThat(fixture.sender().send()).as("items written").isZero();
        assertThat(fixture.place.written).as("items handed to the place").isEmpty();
        assertThat(fixture.holds.marks).as("items marked").isEmpty();
    }

    @Test
    void aReleasedItemIsWrittenOnceAndASecondPassWritesNothing() {
        final Fixture fixture = new Fixture();
        final Held released = fixture.holds.add(held("a", PLACE, HeldState.RELEASED, null, null));

        assertThat(fixture.sender().send()).as("items written in the first pass").isEqualTo(1);
        assertThat(fixture.sender().send()).as("items written in the second pass").isZero();

        assertThat(fixture.place.written)
                .as("what the place was handed, against the item's own commit")
                .containsExactly(new Write(released, ENTRY));
        assertThat(fixture.holds.state("a")).as("the item's state").isEqualTo(HeldState.DELIVERED);
    }

    @Test
    void aRateLimitedItemIsWrittenAgainOnlyAfterItsTime() {
        final Fixture fixture = new Fixture();
        fixture.holds.add(held("a", PLACE, HeldState.RELEASED, null, null));
        fixture.place.answer("a", new WriteOutcome.RateLimited(Duration.ofSeconds(60)));
        fixture.place.answer("a", new WriteOutcome.Delivered("t-1"));

        fixture.sender().send();

        assertThat(fixture.holds.state("a"))
                .as("the state after the limit")
                .isEqualTo(HeldState.RETRY);
        assertThat(fixture.holds.get("a").retryAt())
                .as("the retry time")
                .isEqualTo(START.plusSeconds(60));

        fixture.now = START.plusSeconds(59);
        assertThat(fixture.sender().send()).as("items written before the retry time").isZero();

        fixture.now = START.plusSeconds(60);
        assertThat(fixture.sender().send()).as("items written at the retry time").isEqualTo(1);
        assertThat(fixture.place.written).as("writes of the item").hasSize(2);
        assertThat(fixture.holds.state("a"))
                .as("the state after the retry")
                .isEqualTo(HeldState.DELIVERED);
    }

    @Test
    void anUnknownOutcomeOrAFailedWriteIsMarkedSoAndNeverSentAgain() {
        final Fixture fixture = new Fixture();
        fixture.holds.add(held("unknown", PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("thrown", PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("next", PLACE, HeldState.RELEASED, null, null));
        fixture.place.answer("unknown", new WriteOutcome.OutcomeUnknown());
        fixture.place.failing.add("thrown");

        assertThat(fixture.sender().send()).as("items handed over").isEqualTo(3);
        fixture.now = START.plus(Duration.ofDays(1));
        assertThat(fixture.sender().send()).as("items handed over a day later").isZero();

        assertThat(fixture.holds.state("unknown"))
                .as("an item whose place gave no answer")
                .isEqualTo(HeldState.OUTCOME_UNKNOWN);
        assertThat(fixture.holds.state("thrown"))
                .as("an item whose write failed, which may have gone out")
                .isEqualTo(HeldState.OUTCOME_UNKNOWN);
        assertThat(fixture.holds.state("next"))
                .as("the item after them")
                .isEqualTo(HeldState.DELIVERED);
        assertThat(fixture.place.written).as("writes in both passes").hasSize(3);
    }

    @Test
    void aMissingPlaceOrCredentialIsMarkedWithItsReasonAndTheNextItemStillGoes() {
        final Fixture fixture = new Fixture();
        fixture.holds.add(held("gone", GONE_PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("no entry", OTHER_PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("no token", EMPTY_PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("goes", PLACE, HeldState.RELEASED, null, null));
        fixture.role.missing.add(OTHER_PLACE);
        fixture.lent =
                entry -> {
                    if (entry.equals(EMPTY_ENTRY)) {
                        throw new PlaceUnreadable(
                                PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                                "no token in the vault");
                    }
                    return new ForgeCredential(entry, "secret");
                };

        assertThat(fixture.sender().send()).as("items written").isEqualTo(1);

        assertThat(fixture.holds.get("gone"))
                .as("the item whose place cannot be found")
                .extracting(Held::state, Held::reason)
                .containsExactly(HeldState.NOT_DELIVERED, "PlaceUnreachable");
        assertThat(fixture.holds.get("no entry"))
                .as("the item whose account has no entry")
                .extracting(Held::state, Held::reason)
                .containsExactly(HeldState.NOT_DELIVERED, "MissingForgeToken");
        assertThat(fixture.holds.get("no token"))
                .as("the item whose token the vault lacks")
                .extracting(Held::state, Held::reason)
                .containsExactly(HeldState.NOT_DELIVERED, "MissingForgeToken");
        assertThat(fixture.holds.state("goes"))
                .as("the item after them")
                .isEqualTo(HeldState.DELIVERED);
        assertThat(fixture.place.written)
                .as("what the place was handed")
                .extracting(write -> write.held().id())
                .containsExactly("goes");
    }

    @Test
    void aFailureBeforeTheWriteKeepsTheItemDueLetsTheOthersGoAndIsThrownAfterThePass() {
        final Fixture fixture = new Fixture();
        fixture.holds.add(held("broken", OTHER_PLACE, HeldState.RELEASED, null, null));
        fixture.holds.add(held("goes", PLACE, HeldState.RELEASED, null, null));
        fixture.places.broken.add(OTHER_PLACE);

        assertThatThrownBy(() -> fixture.sender().send())
                .as("the pass with a broken lookup")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");

        assertThat(fixture.holds.state("broken"))
                .as("the item nothing was sent for")
                .isEqualTo(HeldState.RELEASED);
        assertThat(fixture.holds.state("goes"))
                .as("the item after it")
                .isEqualTo(HeldState.DELIVERED);
    }

    // Stand-ins ---------------------------------------------------------------------------------

    private static Held held(
            final String id,
            final String place,
            final HeldState state,
            final @Nullable String reason,
            final @Nullable Instant retryAt) {
        final ReviewItem item = new ReviewReply("t-1", new Body("said " + id));
        return new Held(
                id,
                Direction.OUTGOING,
                TASK,
                1,
                place,
                COMMIT,
                item,
                null,
                HoldJournal.digest(item),
                START.minusSeconds(60),
                state,
                reason,
                retryAt);
    }

    /** One write as the place was handed it. */
    private record Write(Held held, String entry) {}

    /** Credentials lent for an entry. */
    @FunctionalInterface
    private interface Lent {

        ForgeCredential lend(String entry) throws PlaceUnreadable;
    }

    private static final class Fixture {

        final MemoryHolds holds = new MemoryHolds();

        final FakePlace place = new FakePlace(PLACE);

        final FakePlaces places =
                new FakePlaces(place, new FakePlace(OTHER_PLACE), new FakePlace(EMPTY_PLACE));

        final FakeRole role = new FakeRole();

        Lent lent = entry -> new ForgeCredential(entry, "secret");

        Instant now = START;

        ReleasedSender sender() {
            final Clock clock =
                    new Clock() {
                        @Override
                        public ZoneId getZone() {
                            return ZoneOffset.UTC;
                        }

                        @Override
                        public Clock withZone(final ZoneId zone) {
                            return this;
                        }

                        @Override
                        public Instant instant() {
                            return now;
                        }
                    };
            return new ReleasedSender(holds, places, role, entry -> lent.lend(entry), clock);
        }
    }

    /** Held items in memory, due and marked as the contract says. */
    private static final class MemoryHolds implements ReviewHolds {

        @Override
        public Held sending(final String task, final String id) {
            throw new UnsupportedOperationException("not used here");
        }

        final Map<String, Held> items = new LinkedHashMap<>();

        final List<String> marks = new ArrayList<>();

        boolean everything;

        Held add(final Held held) {
            items.put(held.id(), held);
            return held;
        }

        Held get(final String id) {
            return items.get(id);
        }

        HeldState state(final String id) {
            return items.get(id).state();
        }

        @Override
        public List<Held> due(final Instant now) {
            return items.values().stream()
                    .filter(
                            held ->
                                    everything
                                            || held.state() == HeldState.RELEASED
                                            || held.state() == HeldState.RETRY
                                                    && !held.retryAt().isAfter(now))
                    .toList();
        }

        @Override
        public void mark(
                final String task, final String id, final WriteOutcome outcome, final Instant now) {
            final Held held = items.get(id);
            if (held == null
                    || !task.equals(held.task())
                    || held.state() != HeldState.RELEASED && held.state() != HeldState.RETRY) {
                throw new IllegalStateException("not a released item: " + id);
            }
            marks.add(id);
            items.put(
                    id,
                    switch (outcome) {
                        case WriteOutcome.Delivered delivered ->
                                held.in(HeldState.DELIVERED, null, null);
                        case WriteOutcome.Refused refused ->
                                held.in(HeldState.NOT_DELIVERED, refused.reason(), null);
                        case WriteOutcome.RateLimited limited ->
                                held.in(HeldState.RETRY, null, now.plus(limited.retryAfter()));
                        case WriteOutcome.OutcomeUnknown unknown ->
                                held.in(HeldState.OUTCOME_UNKNOWN, null, null);
                    });
        }

        @Override
        public int startRun(final String task, final String place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Held hold(
                final Run run,
                final String task,
                final Direction direction,
                final ReviewItem item,
                final @Nullable Kept received,
                final @Nullable String rule) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Held> incoming(
                final String task, final String place, final String received) {
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
        public int count(final String task) {
            throw new UnsupportedOperationException();
        }
    }

    /** A place that records what it was handed and answers scripted outcomes, else delivered. */
    private static final class FakePlace implements ReviewPlace {

        final String name;

        final List<Write> written = new ArrayList<>();

        final Map<String, Deque<WriteOutcome>> answers = new HashMap<>();

        final Set<String> failing = new HashSet<>();

        FakePlace(final String name) {
            this.name = name;
        }

        void answer(final String id, final WriteOutcome outcome) {
            answers.computeIfAbsent(id, key -> new ArrayDeque<>()).add(outcome);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public WriteOutcome write(final Held held, final ForgeCredential credential) {
            written.add(new Write(held, credential.entry()));
            if (failing.contains(held.id())) {
                throw new IllegalStateException("the connection broke mid-write");
            }
            final Deque<WriteOutcome> scripted = answers.get(held.id());
            return scripted == null || scripted.isEmpty()
                    ? new WriteOutcome.Delivered("t-1")
                    : scripted.poll();
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
        public CommentReading reading() {
            throw new UnsupportedOperationException();
        }
    }

    /** Fixed places; a broken one throws on its lookup. */
    private static final class FakePlaces implements ReviewPlaces {

        final Map<String, ReviewPlace> known = new HashMap<>();

        final Set<String> broken = new HashSet<>();

        FakePlaces(final ReviewPlace... places) {
            for (final ReviewPlace place : places) {
                known.put(place.name(), place);
            }
        }

        @Override
        public Optional<ReviewPlace> named(final String name) {
            if (broken.contains(name)) {
                throw new IllegalStateException("the forge file is broken");
            }
            return Optional.ofNullable(known.get(name));
        }
    }

    /** One posting entry for every place, another for the empty one, none for the missing ones. */
    private static final class FakeRole implements ReviewRole {

        final Set<String> missing = new HashSet<>();

        @Override
        public String postingEntry(final String task, final ReviewPlace place)
                throws PlaceUnreadable {
            if (missing.contains(place.name())) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.MISSING_FORGE_TOKEN, "no entry for the account");
            }
            return place.name().equals(EMPTY_PLACE) ? EMPTY_ENTRY : ENTRY;
        }

        @Override
        public Role role(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPush(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Producer postingAccount(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }
}
