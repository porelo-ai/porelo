package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Another task's waiting push as a reading task's place, with stand-ins for the waiting pushes, the
 * review store, its writer and the pin: the push's threads in the store are the run's conversation,
 * and the task's items go into them as the task itself.
 */
class WaitingPushPlaceTest {

    private static final String READER = "demo_reader";

    private static final String HEAD = "1".repeat(40);

    private static final String MOVED = "2".repeat(40);

    private static final String BASE = "3".repeat(40);

    /** The recorded container id of the task that pushed the work. */
    private static final String PUSHER = "0123456789ab";

    /** The recorded container id of another task, which pushed under the same name later. */
    private static final String ANOTHER = "ba9876543210";

    private final StandInStore store = new StandInStore();

    private final List<Push> written = new ArrayList<>();

    private final List<String> pinned = new ArrayList<>();

    private @Nullable Push waitingNow = push(HEAD);

    /** Whom the task's recorded rights and place were asked for, at which push. */
    private final List<String> judged = new ArrayList<>();

    /** What the task's recorded rights and place answer. */
    private boolean allowed = true;

    private final CommentIntake.Parts parts =
            new CommentIntake.Parts(
                    (project, repository, name) -> {
                        final Push now = waitingNow;
                        if (now == null) {
                            throw new ReviewRefused(
                                    new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
                        }
                        return now;
                    },
                    (push, item) -> Optional.empty(),
                    store,
                    (push, threads, cursor) -> {
                        written.add(push);
                        return new ReviewWriter.Page("{\"threads\":" + threads.size() + "}", null);
                    },
                    push -> new ReviewCounts.Counts(0, 0, List.of()),
                    new TaskWrites() {
                        @Override
                        public boolean may(
                                final String container, final Push push, final ReviewItem item) {
                            judged.add(container + " " + push.name());
                            return allowed;
                        }

                        @Override
                        public boolean reads(final String container, final Push push) {
                            // The reading task's record names the work of the pushing task.
                            return push.task().equals(PUSHER);
                        }
                    });

    private final WaitingPushPlace place =
            new WaitingPushPlace(
                    "demo", null, "foo-1", parts, (task, push) -> pinned.add(task + " " + push));

    @Test
    void theStoresTwoThreadsOfThePushAreTheRunsConversation() throws Exception {
        final String first = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        store.answer(push(HEAD), first, new Task("demo_foo-1"), "It reads the pages.");
        final String second = store.open(push(HEAD), new OwnerAccount(), "Rename it.");
        store.resolve(second);

        final Pinned at = place.pin(READER);
        final Conversation read = place.conversation(at, WaitingPushPlace.NO_ACCOUNT);

        assertThat(pinned)
                .as("the push pinned into the reading task's gate, as it waits")
                .containsExactly(READER + " " + push(HEAD));
        assertThat(at.push())
                .as("the pinned push, bound to the task that pushed it")
                .isEqualTo(push(HEAD));
        assertThat(read.items())
                .as("both threads, each opening comment before its reply")
                .extracting(kept -> text(kept.item()))
                .containsExactly("Why this loop?", "It reads the pages.", "Rename it.");
        assertThat(read.resolved()).as("the resolved thread").containsExactly(second);
        assertThat(read.completeness())
                .as("read whole from this machine")
                .isEqualTo(Completeness.COMPLETE);

        final List<byte[]> pages = place.pages(at, read);
        assertThat(pages)
                .as("the pages the run is handed")
                .extracting(page -> new String(page, StandardCharsets.UTF_8))
                .containsExactly("{\"threads\":2}");
        assertThat(written).as("written for the pinned push").containsExactly(push(HEAD));
    }

    @Test
    void theReadingTasksItemIsTakenIntoThePushsThreadAsTheTaskItself() throws Exception {
        final String thread = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        final Pinned at = place.pin(READER);

        final Kept kept =
                place.take(READER, at.push(), new ReviewReply(thread, new Body("Agreed.")));

        assertThat(kept.origin().producer())
                .as("who the item is stamped with")
                .isEqualTo(new Task(READER));
        assertThat(kept.origin().producer().words())
                .as("as the push's threads show it")
                .isEqualTo("task " + READER);
        assertThat(kept.thread()).as("the thread it answers").isEqualTo(thread);
        assertThat(store.keptFor.getLast()).as("the push it was kept for").isEqualTo(push(HEAD));
        assertThat(judged)
                .as("judged by the task's recorded rights and place, by its container")
                .containsExactly(READER + " foo-1");
    }

    @Test
    void anItemTheTasksRecordDoesNotAllowIsKeptNowhere() throws Exception {
        // The place has no way in of its own: the intake of a task's items judges it.
        final String thread = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        final Pinned at = place.pin(READER);
        allowed = false;
        final int before = store.keptFor.size();

        assertThatThrownBy(
                        () ->
                                place.take(
                                        READER,
                                        at.push(),
                                        new ReviewReply(thread, new Body("Agreed."))))
                .as("a reply its record does not allow")
                .isInstanceOf(ReviewRefused.class);
        assertThat(store.keptFor).as("nothing kept").hasSize(before);
    }

    @Test
    void aPushThatMovesWhileATaskReadsItLeavesTheRunAtItsPinnedCommit() throws Exception {
        final String before = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        final Pinned at = place.pin(READER);
        waitingNow = push(MOVED);
        store.open(push(MOVED), new OwnerAccount(), "And this new file?");

        final Conversation read = place.conversation(at, WaitingPushPlace.NO_ACCOUNT);
        place.pages(at, read);
        final Kept kept = place.take(READER, at.push(), new ReviewReply(before, new Body("Seen.")));

        assertThat(at.push().head()).as("the commit the run stays pinned to").isEqualTo(HEAD);
        assertThat(read.items())
                .as("the push's threads, whatever head each was written for")
                .extracting(each -> text(each.item()))
                .containsExactly("Why this loop?", "And this new file?");
        assertThat(written)
                .as("the pages are written for the pinned head")
                .extracting(Push::head)
                .containsExactly(HEAD);
        assertThat(store.keptFor)
                .as("the reading task's item is kept against its pinned head")
                .extracting(Push::head)
                .containsExactly(HEAD, MOVED, HEAD);
        assertThat(kept.origin().producer()).as("its producer").isEqualTo(new Task(READER));
    }

    @Test
    void aPushThatNoLongerWaitsIsNotPinned() {
        waitingNow = null;

        final PlaceUnreadable refused =
                catchThrowableOfType(PlaceUnreadable.class, () -> place.pin(READER));

        assertThat(refused.reason()).as("why").isEqualTo(PlaceUnreadable.Reason.NO_SUCH_PUSH);
        assertThat(pinned).as("what was pinned").isEmpty();
    }

    @Test
    void aWaitingPushIsFoundByItsNameAndAPullRequestAmongThePullRequests() {
        final List<String> asked = new ArrayList<>();
        final var places =
                WaitingPushPlace.places(
                        parts,
                        (task, push) -> {},
                        name -> {
                            asked.add(name);
                            return Optional.empty();
                        });

        assertThat(places.named(place.name()).map(ReviewPlace::name))
                .as("the place found again by its name")
                .contains("demo/demo/foo-1");
        assertThat(places.named("demo/../foo-1"))
                .as("a name that could leave the mirrors")
                .isEmpty();
        assertThat(places.named("demo/foo-1")).as("a name without its project").isEmpty();
        places.named("https://forge.example.org/uc/web-app/pull/7");
        assertThat(asked)
                .as("asked of the pull requests")
                .containsExactly("https://forge.example.org/uc/web-app/pull/7");
    }

    @Test
    void aReleasedItemIsWrittenIntoTheThreadAgainstItsOwnCommit() throws Exception {
        final String thread = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        waitingNow = push(MOVED);

        final WriteOutcome outcome =
                place.write(
                        new org.fuin.porelo.gate.review.Held(
                                "h-1",
                                org.fuin.porelo.gate.review.Direction.OUTGOING,
                                READER,
                                1,
                                place.name(),
                                HEAD,
                                new ReviewReply(thread, new Body("Agreed.")),
                                null,
                                "a".repeat(64),
                                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                                org.fuin.porelo.gate.review.HeldState.RELEASED,
                                null,
                                null,
                                false),
                        WaitingPushPlace.NO_ACCOUNT);

        assertThat(outcome).as("the write").isEqualTo(new WriteOutcome.Delivered(thread));
        assertThat(store.keptFor.getLast().head())
                .as("the commit it is kept against")
                .isEqualTo(HEAD);
        assertThat(store.originFor.getLast().producer())
                .as("its producer")
                .isEqualTo(new Task(READER));
    }

    @Test
    void aReleasedItemIsRefusedWhenAnotherTasksPushWaitsUnderTheName() throws Exception {
        // The push read went, and another task's unreviewed work waits as foo-1.
        final String thread = store.open(push(HEAD), new OwnerAccount(), "Why this loop?");
        waitingNow = new Push("demo", "demo", "foo-1", MOVED, BASE, ANOTHER);
        final int before = store.keptFor.size();

        final WriteOutcome outcome =
                place.write(
                        released(new ReviewReply(thread, new Body("Agreed."))),
                        WaitingPushPlace.NO_ACCOUNT);

        assertThat(outcome)
                .as("the write")
                .isEqualTo(new WriteOutcome.Refused(ReviewError.NO_SUCH_PUSH.wireName()));
        assertThat(store.keptFor).as("nothing kept in its threads").hasSize(before);
        assertThat(judged).as("never judged as a write there").isEmpty();
    }

    @Test
    void aResumeIsRefusedWhenAnotherTasksPushWaitsUnderTheName() throws Exception {
        place.pin(READER);
        waitingNow = new Push("demo", "demo", "foo-1", MOVED, BASE, ANOTHER);

        final PlaceUnreadable refused =
                catchThrowableOfType(PlaceUnreadable.class, () -> place.pin(READER));

        assertThat(refused.reason()).as("why").isEqualTo(PlaceUnreadable.Reason.NO_SUCH_PUSH);
        assertThat(pinned).as("the pin unchanged").containsExactly(READER + " " + push(HEAD));
    }

    @Test
    void aResumeAfterItsTaskPushedANewHeadPinsItAsBefore() throws Exception {
        place.pin(READER);
        waitingNow = push(MOVED);

        place.pin(READER);

        assertThat(pinned)
                .as("the same task's push, at its new head")
                .containsExactly(READER + " " + push(HEAD), READER + " " + push(MOVED));
    }

    private static org.fuin.porelo.gate.review.Held released(final ReviewItem item) {
        return new org.fuin.porelo.gate.review.Held(
                "h-1",
                org.fuin.porelo.gate.review.Direction.OUTGOING,
                READER,
                1,
                "demo/demo/foo-1",
                HEAD,
                item,
                null,
                "a".repeat(64),
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                org.fuin.porelo.gate.review.HeldState.RELEASED,
                null,
                null,
                false);
    }

    private static Push push(final String head) {
        return new Push("demo", "demo", "foo-1", head, BASE, PUSHER);
    }

    private static String text(final ReviewItem item) {
        return switch (item) {
            case ReviewComment comment -> comment.body().text();
            case ReviewReply reply -> reply.body().text();
            case org.fuin.porelo.gate.review.ForgeReply reply -> reply.body().text();
            case org.fuin.porelo.gate.review.ThreadResolve resolve -> "";
        };
    }

    /** Keeps threads in memory, the push's whatever its head, as the journals do. */
    private static final class StandInStore implements ReviewStore {

        private final Map<String, List<Kept>> threads = new LinkedHashMap<>();

        private final List<String> resolved = new ArrayList<>();

        private final List<Push> keptFor = new ArrayList<>();

        private final List<Origin> originFor = new ArrayList<>();

        private int next;

        String open(
                final Push push, final org.fuin.porelo.gate.review.Producer by, final String text)
                throws ReviewRefused {
            return keep(
                            push,
                            new ReviewComment(
                                    new LineAnchor("src/Main.java", Side.NEW, 3, 3),
                                    new Body(text),
                                    null),
                            Origin.of(by),
                            null)
                    .thread();
        }

        void answer(
                final Push push,
                final String thread,
                final org.fuin.porelo.gate.review.Producer by,
                final String text)
                throws ReviewRefused {
            keep(push, new ReviewReply(thread, new Body(text)), Origin.of(by), null);
        }

        void resolve(final String thread) {
            resolved.add(thread);
        }

        @Override
        public Kept keep(
                final Push push,
                final ReviewItem item,
                final Origin origin,
                final @Nullable String id)
                throws ReviewRefused {
            keptFor.add(push);
            originFor.add(origin);
            final String guid = "g-" + ++next;
            final String thread;
            if (item instanceof ReviewReply reply) {
                if (!threads.containsKey(reply.thread())) {
                    throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
                }
                thread = reply.thread();
            } else {
                thread = "t-" + next;
                threads.put(thread, new ArrayList<>());
            }
            final Kept kept = new Kept(guid, thread, origin, item);
            threads.get(thread).add(kept);
            return kept;
        }

        @Override
        public List<ReviewThread> threads(final Push push) {
            final List<ReviewThread> all = new ArrayList<>();
            threads.forEach(
                    (id, items) ->
                            all.add(
                                    new ReviewThread(
                                            id,
                                            resolved.contains(id)
                                                    ? ThreadState.RESOLVED
                                                    : ThreadState.OPEN,
                                            items)));
            return all;
        }

        @Override
        public Optional<Push> recorded(
                final String project,
                final String repository,
                final String name,
                final String task) {
            return Optional.empty();
        }

        @Override
        public ReviewThread state(
                final Push push,
                final String thread,
                final ThreadState state,
                final org.fuin.porelo.gate.review.Origin by) {
            throw new UnsupportedOperationException("no thread's state is set here");
        }
    }
}
