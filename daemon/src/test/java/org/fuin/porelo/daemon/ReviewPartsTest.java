package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.app.PullRequestPins;
import org.fuin.porelo.app.PullRequestPlace;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.TaskReviewRole;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An author's pin at its own pull request, as the daemon assembles it: the real role over a
 * stand-in of what the task's gate forwarded, and a stand-in of where the task stands.
 */
class ReviewPartsTest {

    private static final String TASK = "porelo-uc-shop-1";

    private static final String ID = "c".repeat(64);

    private static final String HEAD = "a".repeat(40);

    private static final String UPSTREAM = "https://review.stub.invalid/uc/shop.git";

    private static final String BRANCH = "porelo/shop-1";

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://review.stub.invalid/uc/shop/pull/7"));

    @TempDir Path dir;

    private final List<String> asked = new ArrayList<>();

    private TaskReviewRole role(final Optional<String> forwarded) {
        return new TaskReviewRole(
                task -> dir.resolve("state").resolve(task),
                new TaskReviewRole.Entries() {
                    @Override
                    public boolean onHost() {
                        return false;
                    }

                    @Override
                    public String taskAccount(final String task) {
                        return TaskReviewRole.taskForgeEntry(task);
                    }

                    @Override
                    public Optional<String> projectBuilds(final String task) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean held(final String entry) {
                        return false;
                    }
                },
                new ForgeInstances() {
                    @Override
                    public Optional<ForgeInstance> of(final URI url) {
                        return Optional.empty();
                    }

                    @Override
                    public List<ForgeInstance> all() {
                        return List.of();
                    }

                    @Override
                    public void set(
                            final String host, final String adapter, final CommentReading reading) {
                        throw new UnsupportedOperationException();
                    }
                },
                (forge, entry) -> {
                    throw new UnsupportedOperationException();
                },
                task -> Optional.empty(),
                (task, upstream, branch) -> {
                    asked.add(task + " " + upstream + " " + branch);
                    return forwarded;
                });
    }

    private ReviewParts.Author author() throws Exception {
        final Path state = Files.createDirectories(dir.resolve("state").resolve(TASK));
        return new ReviewParts.Author(
                new PullRequestPins.TaskGate(
                        dir.resolve("mirror"),
                        state,
                        dir.resolve("record"),
                        dir.resolve("pinned").resolve("shop-1.git"),
                        "shop-1",
                        "uc",
                        "shop",
                        "shop-1"),
                ID,
                UPSTREAM,
                BRANCH);
    }

    private static PullRequestFacts factsAt(final String head) {
        return new PullRequestFacts(
                REF,
                UPSTREAM,
                head,
                "b".repeat(40),
                "d".repeat(40),
                PullRequestState.OPEN,
                false,
                null,
                null,
                null,
                null);
    }

    @Test
    void anAuthorWhoseGateForwardedTheHeadIsPinnedThereAsItsOwnPush() throws Exception {
        final ReviewParts.Author at = author();
        final PullRequestPlace.AuthorPins pins =
                ReviewParts.authorPins(role(Optional.of(HEAD)), task -> at);

        final Push pinned = pins.pin(TASK, factsAt(HEAD));

        assertThat(asked)
                .as("the task's gate asked for its upstream and branch")
                .containsExactly(TASK + " " + UPSTREAM + " " + BRANCH);
        assertThat(pinned)
                .as("its own push at the head, against the merge base")
                .isEqualTo(new Push("uc", "shop", "shop-1", HEAD, "d".repeat(40), ID));
    }

    @Test
    void anAuthorWhoseGateForwardedAnotherHeadIsRefusedAsNotTheTasksPush() throws Exception {
        final ReviewParts.Author at = author();
        final PullRequestPlace.AuthorPins pins =
                ReviewParts.authorPins(role(Optional.of("e".repeat(40))), task -> at);

        assertThatThrownBy(() -> pins.pin(TASK, factsAt(HEAD)))
                .as("a head its gate never forwarded")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.getCause())
                                        .as("the contract's refusal kept as its cause")
                                        .isInstanceOfSatisfying(
                                                RunPlaceCheck.Refused.class,
                                                refused ->
                                                        assertThat(refused.refusal().error())
                                                                .as("its error")
                                                                .isEqualTo(
                                                                        "org.fuin.porelo.Tasks1"
                                                                                + ".NotTheTasksPush")));
    }

    @Test
    void anAuthorWhoseTaskRecordsNoBranchIsNotPinnedAndItsGateIsNotAsked() {
        final PullRequestPlace.AuthorPins pins =
                ReviewParts.authorPins(
                        role(Optional.of(HEAD)),
                        task -> {
                            throw new PlaceUnreadable(
                                    PlaceUnreadable.Reason.NOT_IN_PROJECT, "no branch recorded");
                        });

        assertThatThrownBy(() -> pins.pin(TASK, factsAt(HEAD)))
                .as("nothing to ask the gate about")
                .isInstanceOf(PlaceUnreadable.class);
        assertThat(asked).as("its gate not asked").isEmpty();
    }
}
