package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.gate.review.TaskParts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The state a reviewer's run is pinned to is kept beside its role and place, through a stop and a
 * reboot, and a pin that does not read leaves the whole record unread.
 */
class RunPlacePinTest {

    private static final String HEAD = "a".repeat(40);

    private static final String BASE = "b".repeat(40);

    private static final String REPOSITORY = "https://github.com/acme/shop";

    /** The recorded container id of the task whose push is read. */
    private static final String PUSHER = "d".repeat(12);

    @TempDir Path state;

    @Test
    void aReviewersPinnedStateReadsBackAsItWasKept() throws IOException {
        final RunPlace pinned =
                new RunPlace(
                                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                        java.util.List.of(
                                                org.fuin.porelo.gate.review.Right.REPLY,
                                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                                null,
                                "shop-1")
                        .pinnedTo(new RunPlace.Pin(HEAD, BASE, REPOSITORY));
        pinned.writeTo(state);
        assertThat(RunPlace.readFrom(state)).as("the record read back").isEqualTo(pinned);
        assertThat(RunPlace.readFrom(state).pinned())
                .as("its pinned state")
                .isEqualTo(new RunPlace.Pin(HEAD, BASE, REPOSITORY));
    }

    @Test
    void aPinWithoutBaseOrRepositoryReadsBack() throws IOException {
        final RunPlace pinned =
                new RunPlace(
                                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                        java.util.List.of(
                                                org.fuin.porelo.gate.review.Right.REPLY,
                                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                                null,
                                "shop-1")
                        .pinnedTo(new RunPlace.Pin(HEAD, null, null, PUSHER));
        pinned.writeTo(state);
        assertThat(RunPlace.readFrom(state)).as("the record read back").isEqualTo(pinned);
    }

    @Test
    void aGatePushsPinThatNamesNoPushingTaskDoesNotRead() throws IOException {
        // Never taken as the work of whichever task pushes under the name next.
        new RunPlace(
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(org.fuin.porelo.gate.review.Right.REPLY)),
                        null,
                        "shop-1")
                .pinnedTo(new RunPlace.Pin(HEAD, null, null))
                .writeTo(state);

        assertThat(RunPlace.readFrom(state)).as("the record").isNull();
    }

    @Test
    void aPlaceAtAPullRequestReadsBackWithItsTaskAndPin() throws IOException {
        final RunPlace at =
                RunPlace.atPullRequest(
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                        "shop-1",
                        REPOSITORY + "/pull/7");
        at.writeTo(state);
        assertThat(RunPlace.readFrom(state)).as("the record read back").isEqualTo(at);
        final RunPlace pinned = at.pinnedTo(new RunPlace.Pin(HEAD, BASE, REPOSITORY));
        pinned.writeTo(state);
        assertThat(RunPlace.readFrom(state)).as("the pinned record read back").isEqualTo(pinned);
    }

    @Test
    void aPullRequestPlaceOfAnotherShapeIsRefused() {
        for (final String url :
                List.of("http://github.com/acme/shop/pull/7", "https://github.com/a/b?x=1")) {
            assertThatThrownBy(() -> RunPlace.atPullRequest(TaskParts.ownWork(), "shop-1", url))
                    .as("a place at '%s'", url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aRecordWrittenWithoutAPinReadsAsPinnedNowhere() throws IOException {
        RunPlace.ownPush("shop", null, "shop-1").writeTo(state);
        assertThat(RunPlace.readFrom(state).pinned()).as("an author's pinned state").isNull();
    }

    @Test
    void aPinThatDoesNotReadLeavesTheRecordUnread() throws IOException {
        for (final String pin :
                List.of(
                        "{\"head\": \"abc\"}",
                        "{\"head\": \"" + HEAD + "\", \"repository\": \"http://github.com/a\"}",
                        "{\"base\": \"" + BASE + "\"}",
                        "\"" + HEAD + "\"")) {
            Files.writeString(
                    state.resolve(RunPlace.FILE),
                    "{\"role\": \"REVIEWER\", \"place\": {\"gatePush\": {\"name\": \"shop-1\"}},"
                            + " \"pinned\": "
                            + pin
                            + "}",
                    StandardCharsets.UTF_8);
            assertThat(RunPlace.readFrom(state)).as("a record pinned as %s", pin).isNull();
        }
    }

    @Test
    void aPinNamesFullCommitsAndAnHttpsRepository() {
        assertThatThrownBy(() -> new RunPlace.Pin("abc", null, null))
                .as("a short head")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunPlace.Pin(HEAD, "HEAD", null))
                .as("a base that is no commit id")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunPlace.Pin(HEAD, null, "git@github.com:acme/shop"))
                .as("a repository not on https")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunPlace.Pin(HEAD, null, null, "porelo-shop-1"))
                .as("a pushing task named other than by its recorded id")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new RunPlace.Pin(HEAD, null, "https://github.com/acme/shop", PUSHER))
                .as("a pushing task named for a pull request")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
