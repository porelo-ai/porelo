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
import org.fuin.porelo.gate.PinnedRepository;
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

    /** What rev-parse peels an id to, where it is not the id itself. */
    private final java.util.Map<String, String> peeled = new java.util.HashMap<>();

    /** Runs before git answers a command that names this, as a fault at that step. */
    private final java.util.Map<String, Runnable> at = new java.util.HashMap<>();

    private final CommandRunner git =
            command -> {
                asked.add(command.arguments());
                at.forEach(
                        (word, fault) -> {
                            if (command.arguments().contains(word)) {
                                fault.run();
                            }
                        });
                String out = "";
                if (command.arguments().contains("rev-parse")) {
                    final String asked = command.arguments().getLast().replace("^{commit}", "");
                    out = peeled.getOrDefault(asked, asked) + "\n";
                }
                return new CommandResult(command, exitCode, out, "");
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

    @Test
    void pinningBuildsTheReviewersOwnRepositoryInItsState() throws IOException {
        reviewer();
        ReviewerPin.pin(git, mirror, state, TASK, new RunPlace.Pin(HEAD, BASE, null));
        final Path built = state.resolve(PinnedRepository.DIRECTORY + ".new");
        assertThat(asked)
                .as("what git was asked")
                .contains(List.of("git", "init", "--bare", "-q", built.toString()));
        assertThat(asked)
                .as("the fetch of the pinned head and base alone")
                .anySatisfy(
                        each ->
                                assertThat(each)
                                        .contains("fetch", "--no-tags")
                                        .endsWith(
                                                HEAD + ":" + GitGate.PINNED + TASK,
                                                BASE + ":" + PinnedRepository.BASE + TASK));
        assertThat(PinnedRepository.in(state)).as("the served repository").isDirectory();
    }

    @Test
    void aTagIsNeverPinnedOnlyTheCommitItself() throws IOException {
        reviewer();
        peeled.put(HEAD, LATER);
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a pin of an id that names a tag")
                .isInstanceOf(IOException.class);
        assertThat(asked)
                .as("what git was asked")
                .noneMatch(each -> each.contains("update-ref") || each.contains("init"));
        assertThat(RunPlace.readFrom(state).pinned()).as("the recorded state").isNull();
    }

    @Test
    void aRecordThatCannotBeWrittenPutsBackTheEarlierRepositoryAndRef() throws IOException {
        reviewer();
        ReviewerPin.pin(git, mirror, state, TASK, new RunPlace.Pin(HEAD, BASE, null));
        java.nio.file.Files.writeString(PinnedRepository.in(state).resolve("earlier"), "");
        at.put(
                "update-ref",
                () -> {
                    try {
                        // The record's place taken by a directory: its replacement cannot land.
                        final Path record = state.resolve(RunPlace.FILE);
                        if (java.nio.file.Files.isRegularFile(record)) {
                            java.nio.file.Files.delete(record);
                            java.nio.file.Files.createDirectory(record);
                            java.nio.file.Files.writeString(record.resolve("in-the-way"), "");
                        }
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        TASK,
                                        new RunPlace.Pin(LATER, BASE, null)))
                .as("a pin whose record could not be written")
                .isInstanceOf(IOException.class);
        assertThat(PinnedRepository.in(state).resolve("earlier"))
                .as("the earlier served repository's mark")
                .exists();
        assertThat(asked.getLast())
                .as("the last git the pin ran")
                .containsExactly(
                        "git", "-C", mirror.toString(), "update-ref", GitGate.PINNED + TASK, HEAD);
    }

    private void reviewer() throws IOException {
        new RunPlace(RunPlace.REVIEWER, null, "shop-2").writeTo(state);
    }
}
