package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.junit.jupiter.api.Test;

/** A task's writes into a push's threads: by its recorded rights, only at its recorded place. */
class RecordedTaskWritesTest {

    private static final String CONTAINER = "porelo-uc_shop-2";

    private static final String OWN_ID = "c".repeat(64);

    private static final Push OWN =
            new Push("uc", "shop", "shop-2", "a".repeat(40), "b".repeat(40), OWN_ID);

    private static final Push OTHER_PUSH =
            new Push("uc", "shop", "shop-3", "d".repeat(40), null, "e".repeat(64));

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5), new Body("Lost."), null);

    private static final ReviewReply REPLY = new ReviewReply("t-1", new Body("Fixed."));

    private static final TaskParts OWN_WORK =
            new TaskParts(
                    TaskParts.Workspace.OWN,
                    List.of(),
                    true,
                    TaskParts.Account.AUTHOR,
                    Set.of(Right.PUSH, Right.REPLY, Right.OPEN_THREADS));

    private static final TaskParts READER =
            new TaskParts(
                    TaskParts.Workspace.FOREIGN,
                    List.of(),
                    false,
                    TaskParts.Account.REVIEW,
                    Set.of(Right.REPLY, Right.OPEN_THREADS));

    private final Map<String, TaskParts> parts = new HashMap<>();
    private final Map<String, RunPlace> places = new HashMap<>();
    private final Map<String, TaskIdentity> owners = new HashMap<>();

    private final RecordedTaskWrites writes =
            new RecordedTaskWrites(
                    new Rights(parts),
                    places::get,
                    container -> Optional.ofNullable(owners.get(container)));

    /** Records the task's parts and its place in the project's repository shop, not its own. */
    private void recorded(final TaskParts recorded, final String pushName) {
        parts.put(CONTAINER, recorded);
        places.put(CONTAINER, new RunPlace(recorded, "shop", pushName, null));
        owners.put(
                CONTAINER,
                new TaskIdentity(TaskIdentity.Format.ENCODED, "uc", "shop-2", CONTAINER, OWN_ID));
    }

    /** Pins the recorded reader to the work of the task of that recorded id. */
    private void pinned(final String pushedBy) {
        places.put(
                CONTAINER,
                java.util.Objects.requireNonNull(places.get(CONTAINER))
                        .pinnedTo(new RunPlace.Pin("d".repeat(40), null, null, pushedBy)));
    }

    @Test
    void aTaskOnItsOwnWorkWritesInItsOwnPush() {
        recorded(OWN_WORK, "shop-2");

        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("a comment").isTrue();
        assertThat(writes.may(CONTAINER, OWN, REPLY)).as("a reply").isTrue();
    }

    @Test
    void aPlaceInTheProjectsOwnRepositoryNamesNoRepository() {
        // The project's own repository carries the project's name; its place names none.
        recorded(OWN_WORK, "shop-2");
        places.put(CONTAINER, new RunPlace(OWN_WORK, null, "shop-2", null));

        assertThat(
                        writes.may(
                                CONTAINER,
                                new Push("uc", "uc", "shop-2", OWN.head(), null, OWN_ID),
                                COMMENT))
                .as("the own repository's push")
                .isTrue();
        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("the repository shop's").isFalse();
    }

    @Test
    void theSameItemForAnotherPushOfTheProjectIsRefused() {
        // A task that may open threads opens them only in the push that is its place.
        recorded(OWN_WORK, "shop-2");

        assertThat(writes.may(CONTAINER, OTHER_PUSH, COMMENT)).as("another push").isFalse();
        assertThat(writes.may(CONTAINER, OTHER_PUSH, REPLY)).as("a reply there").isFalse();
    }

    @Test
    void aPushOfTheSameNameInAnotherProjectOrRepositoryIsRefused() {
        recorded(OWN_WORK, "shop-2");

        assertThat(
                        writes.may(
                                CONTAINER,
                                new Push("ux", "shop", "shop-2", OWN.head(), null, OWN_ID),
                                COMMENT))
                .as("another project")
                .isFalse();
        assertThat(
                        writes.may(
                                CONTAINER,
                                new Push("uc", "billing", "shop-2", OWN.head(), null, OWN_ID),
                                COMMENT))
                .as("another repository")
                .isFalse();
    }

    @Test
    void ownWorkPushedByAnotherContainerOfTheSameNameIsRefused() {
        // A task started later under the same name inherits no thread of the earlier one.
        recorded(OWN_WORK, "shop-2");

        assertThat(
                        writes.may(
                                CONTAINER,
                                new Push("uc", "shop", "shop-2", OWN.head(), null, "9".repeat(64)),
                                COMMENT))
                .as("another container's push")
                .isFalse();
    }

    @Test
    void aReaderWritesInThePushItReads() {
        recorded(READER, "shop-3");
        pinned(OTHER_PUSH.task());

        assertThat(writes.may(CONTAINER, OTHER_PUSH, COMMENT)).as("the push it reads").isTrue();
        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("any other").isFalse();
    }

    @Test
    void aReaderWritesNothingInAnotherTasksPushThatWaitsUnderTheNameItReads() {
        // The push it was pinned to went, and another task's unreviewed work waits as shop-3.
        recorded(READER, "shop-3");
        pinned(OTHER_PUSH.task());
        final Push anothers =
                new Push("uc", "shop", "shop-3", "f".repeat(40), null, "9".repeat(64));

        assertThat(writes.may(CONTAINER, anothers, COMMENT))
                .as("a comment in another task's push of the name")
                .isFalse();
        assertThat(writes.reads(CONTAINER, anothers)).as("read as its work").isFalse();
    }

    @Test
    void aReaderOfAPushItsTaskMovedStillWritesThereAndOneNeverPinnedWritesNothing() {
        recorded(READER, "shop-3");
        final Push moved =
                new Push("uc", "shop", "shop-3", "f".repeat(40), null, OTHER_PUSH.task());

        assertThat(writes.reads(CONTAINER, moved)).as("pinned first, to any task's push").isTrue();
        assertThat(writes.may(CONTAINER, moved, COMMENT))
                .as("a comment before any pin names whose work it reads")
                .isFalse();

        pinned(OTHER_PUSH.task());
        assertThat(writes.may(CONTAINER, moved, COMMENT))
                .as("the same task's push at a new head")
                .isTrue();
        assertThat(writes.reads(CONTAINER, moved)).as("read as its work").isTrue();
        assertThat(writes.reads(CONTAINER, OWN)).as("a push of another name").isFalse();
    }

    @Test
    void aMissingRightRefusesItsItemAlone() {
        recorded(
                new TaskParts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Set.of(Right.PUSH, Right.REPLY)),
                "shop-2");

        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("no openThreads").isFalse();
        assertThat(writes.may(CONTAINER, OWN, REPLY)).as("reply").isTrue();
    }

    @Test
    void ownWorkWhosePlaceIsItsOwnPullRequestAnswersInItsOwnPushByItsReplyRight() {
        // Its place moved to its own pull request; the threads of its own push stay its to answer.
        final TaskParts withoutReply =
                new TaskParts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Set.of(Right.PUSH));
        recorded(OWN_WORK, "shop-2");
        places.put(
                CONTAINER,
                RunPlace.atPullRequest(OWN_WORK, "shop-2", "https://github.com/uc/shop/pull/7"));

        assertThat(writes.may(CONTAINER, OWN, REPLY)).as("its own push, holding reply").isTrue();
        assertThat(writes.may(CONTAINER, OTHER_PUSH, REPLY)).as("another task's push").isFalse();
        assertThat(
                        writes.may(
                                CONTAINER,
                                new Push("uc", "shop", "shop-2", OWN.head(), null, "9".repeat(64)),
                                REPLY))
                .as("a push of its name another container pushed")
                .isFalse();

        parts.put(CONTAINER, withoutReply);
        places.put(
                CONTAINER,
                RunPlace.atPullRequest(
                        withoutReply, "shop-2", "https://github.com/uc/shop/pull/7"));
        assertThat(writes.may(CONTAINER, OWN, REPLY)).as("without reply").isFalse();
    }

    @Test
    void aTaskWithoutARecordOrAReaderAtAPullRequestWritesNothing() {
        // Fail closed: nothing recorded gives no right, and a reader at a pull request has no push.
        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("nothing recorded").isFalse();

        recorded(READER, "shop-3");
        places.put(
                CONTAINER,
                RunPlace.atPullRequest(READER, "shop-3", "https://github.com/uc/shop/pull/7"));
        assertThat(writes.may(CONTAINER, OTHER_PUSH, REPLY))
                .as("a reader at a pull request")
                .isFalse();

        recorded(OWN_WORK, "shop-2");
        owners.remove(CONTAINER);
        assertThat(writes.may(CONTAINER, OWN, COMMENT)).as("no owner recorded").isFalse();
    }

    @Test
    void aForgeReplyNeverReachesAPush() {
        recorded(OWN_WORK, "shop-2");

        assertThat(writes.may(CONTAINER, OWN, new ForgeReply("1001", new Body("Fixed."))))
                .isFalse();
    }

    /** Answers the recorded parts, and refuses a task with none as the machine's rights do. */
    private record Rights(Map<String, TaskParts> parts) implements TaskRights {

        @Override
        public TaskParts parts(final String task) {
            final TaskParts found = parts.get(task);
            if (found == null) {
                throw new IllegalStateException("task " + task + " recorded no parts");
            }
            return found;
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
        public String postingEntry(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayReject(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }
}
