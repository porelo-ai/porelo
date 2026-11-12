package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ideas a task proposed are the host's record, numbered by the task's own counter. */
class TaskIdeasTest {
    private static final String TASK = "porelo-uc-work";
    @TempDir Path dir;

    private TaskIdeas ideas() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new TaskIdeas(new PoreloPaths(xdg, dir.resolve("bin")));
    }

    @Test
    void eachIdeaIsNumberedAndKeptInOrder() throws Exception {
        final TaskIdeas ideas = ideas();
        assertThat(ideas.read(TASK)).as("a task with no record has none").isEmpty();
        assertThat(ideas.propose(TASK, "First", "One.", 3).id()).isEqualTo("idea-1");
        assertThat(ideas.propose(TASK, "Second", "Two.\nStill two.", 3).id()).isEqualTo("idea-2");
        final List<TaskIdeas.Idea> read = ideas.read(TASK);
        assertThat(read).extracting(TaskIdeas.Idea::id).containsExactly("idea-1", "idea-2");
        assertThat(read.get(1).whatMustBeTrue()).isEqualTo("Two.\nStill two.");
        assertThat(read.get(0).asMap())
                .containsOnlyKeys("id", "title", "whatMustBeTrue", "at")
                .containsEntry("title", "First");
    }

    @Test
    void aProposalPastTheBoundIsRefusedAndKeepsNothing() throws Exception {
        final TaskIdeas ideas = ideas();
        ideas.propose(TASK, "Only", "One.", 1);
        assertThatThrownBy(() -> ideas.propose(TASK, "Past", "Two.", 1))
                .isInstanceOfSatisfying(
                        TaskIdeas.BoundReached.class, ex -> assertThat(ex.most()).isEqualTo(1));
        assertThatThrownBy(() -> ideas().propose("porelo-uc-none", "None", "Zero.", 0))
                .isInstanceOf(TaskIdeas.BoundReached.class);
        assertThat(ideas.read(TASK)).hasSize(1);
    }

    @Test
    void proposalsAtOnceAreEachCountedOnce() throws Exception {
        // Two calls at once must not both take the last place, or the same number.
        final TaskIdeas ideas = ideas();
        final List<String> ids = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newFixedThreadPool(8)) {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        ids.add(ideas.propose(TASK, "Idea " + n, "It is.", 5).id());
                                    } catch (TaskIdeas.BoundReached refused) {
                                        // Past the bound.
                                    }
                                    return null;
                                }));
            }
            for (final Future<?> future : futures) {
                future.get();
            }
        }
        assertThat(ids).containsExactlyInAnyOrder("idea-1", "idea-2", "idea-3", "idea-4", "idea-5");
        assertThat(ideas.read(TASK)).hasSize(5);
    }

    @Test
    void theRecordIsPrivateAndAnUnreadableOneIsNeitherShownNorOverwritten() throws Exception {
        final TaskIdeas ideas = ideas();
        ideas.propose(TASK, "Kept", "It is.", 2);
        final Path directory = ideas.paths().tasks().taskRecord(TASK);
        final Path record = directory.resolve("ideas.json");
        assertThat(Files.getPosixFilePermissions(record))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(directory))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Files.writeString(record, "{not json");
        assertThat(ideas.read(TASK)).as("an unreadable record shows nothing").isEmpty();
        assertThatThrownBy(() -> ideas.propose(TASK, "Again", "It is.", 2))
                .as("an unreadable record is not taken for an empty one, which would renumber")
                .isInstanceOf(java.io.IOException.class);
        assertThat(record).hasContent("{not json");
    }

    @Test
    void theTitleIsOneBoundedLineAndTheStatementBoundedText() {
        assertThat(TaskIdeas.validTitle("A title")).isTrue();
        assertThat(TaskIdeas.validTitle("x".repeat(120))).isTrue();
        assertThat(TaskIdeas.validTitle("x".repeat(121))).isFalse();
        assertThat(TaskIdeas.validTitle("")).isFalse();
        assertThat(TaskIdeas.validTitle(" ")).isFalse();
        assertThat(TaskIdeas.validTitle("a\nb")).isFalse();
        assertThat(TaskIdeas.validTitle("a b")).isFalse();
        assertThat(TaskIdeas.validTitle("\ud800")).as("no lone surrogate").isFalse();
        assertThat(TaskIdeas.validWhatMustBeTrue("It is.\r\nAnd so.")).isTrue();
        assertThat(TaskIdeas.validWhatMustBeTrue("x".repeat(1000))).isTrue();
        assertThat(TaskIdeas.validWhatMustBeTrue("x".repeat(1001))).isFalse();
        assertThat(TaskIdeas.validWhatMustBeTrue("\n")).isFalse();
        assertThat(TaskIdeas.validWhatMustBeTrue("a\tb")).isFalse();
        assertThat(TaskIdeas.validWhatMustBeTrue("a\u001bb")).isFalse();
    }
}
