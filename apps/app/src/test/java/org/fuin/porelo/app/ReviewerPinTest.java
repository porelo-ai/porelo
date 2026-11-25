package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pinning a reviewer moves its one ref in the task's mirror and records the state beside its role,
 * with git stood in; an author is never pinned.
 */
class ReviewerPinTest {

    private static final String TASK = "shop-1";

    private static final String HEAD = "a".repeat(40);

    private static final String LATER = "c".repeat(40);

    private static final String BASE = "b".repeat(40);

    @TempDir Path state;

    @TempDir Path mirror;

    private final List<List<String>> asked = new ArrayList<>();

    private int exitCode;

    private final CommandRunner git =
            command -> {
                asked.add(command.arguments());
                return new CommandResult(command, exitCode, "", "");
            };

    @Test
    void pinningMovesTheReviewersRefAndRecordsTheState() throws IOException {
        reviewer();
        ReviewerPin.pin(git, mirror, state, TASK, new RunPlace.Pin(HEAD, BASE, null));
        assertThat(asked.getLast())
                .as("the last git the pin ran")
                .containsExactly(
                        "git", "-C", mirror.toString(), "update-ref", GitGate.PINNED + TASK, HEAD);
        assertThat(RunPlace.readFrom(state).pinned())
                .as("the recorded state")
                .isEqualTo(new RunPlace.Pin(HEAD, BASE, null));
    }

    @Test
    void pinningAfreshReplacesTheRefAndTheRecord() throws IOException {
        reviewer();
        ReviewerPin.pin(git, mirror, state, TASK, new RunPlace.Pin(HEAD, BASE, null));
        ReviewerPin.pin(git, mirror, state, TASK, new RunPlace.Pin(LATER, BASE, null));
        assertThat(asked.getLast()).as("the last git the pin ran").endsWith(LATER);
        assertThat(RunPlace.readFrom(state).pinned().head())
                .as("the recorded head")
                .isEqualTo(LATER);
        assertThat(RunPlace.readFrom(state).role()).as("the role").isEqualTo(RunPlace.REVIEWER);
    }

    @Test
    void aHeadTheMirrorDoesNotHoldIsPinnedNowhere() throws IOException {
        reviewer();
        exitCode = 128;
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a pin git refused")
                .isInstanceOf(IOException.class);
        assertThat(RunPlace.readFrom(state).pinned()).as("the recorded state").isNull();
    }

    @Test
    void anAuthorIsNeverPinned() throws IOException {
        RunPlace.ownPush("shop", null, TASK).writeTo(state);
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("an author pinned")
                .isInstanceOf(IllegalStateException.class);
        assertThat(asked).as("what git was asked").isEmpty();
    }

    @Test
    void aTaskWithoutARecordedRoleIsNeverPinned() {
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a task without a record pinned")
                .isInstanceOf(IllegalStateException.class);
        assertThat(asked).as("what git was asked").isEmpty();
    }

    private void reviewer() throws IOException {
        new RunPlace(RunPlace.REVIEWER, null, "shop-2").writeTo(state);
    }
}
