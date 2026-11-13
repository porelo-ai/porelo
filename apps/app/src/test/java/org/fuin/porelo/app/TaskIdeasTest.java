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
    void aTakenIdeaIsForgottenAndItsNumberNotGivenAgain() throws Exception {
        // The bound counts what the task proposed: taking must not let it propose without end.
        final TaskIdeas ideas = ideas();
        ideas.propose(TASK, "First", "One.", 2);
        ideas.propose(TASK, "Second", "Two.", 2);
        assertThat(ideas.take(TASK, "idea-1")).as("the person took it").isTrue();
        assertThat(ideas.take(TASK, "idea-1")).as("taken once").isFalse();
        assertThat(ideas.take(TASK, "idea-9")).as("never proposed").isFalse();
        assertThat(ideas.read(TASK)).extracting(TaskIdeas.Idea::id).containsExactly("idea-2");
        assertThatThrownBy(() -> ideas.propose(TASK, "Third", "Three.", 2))
                .as("a taken idea frees no room")
                .isInstanceOf(TaskIdeas.BoundReached.class);
        assertThat(ideas.take(TASK, "idea-2")).isTrue();
        assertThat(ideas.propose(TASK, "Third", "Three.", 3).id())
                .as("numbered past every idea taken")
                .isEqualTo("idea-3");
        assertThat(ideas.take("porelo-uc-none", "idea-1"))
                .as("a task without a record holds none, and none is made")
                .isFalse();
        assertThat(ideas.paths().tasks().taskRecord("porelo-uc-none")).doesNotExist();
    }

    @Test
    void aRecordFromBeforeIdeasWereTakenCountsByItsNewest() throws Exception {
        final TaskIdeas ideas = ideas();
        ideas.propose(TASK, "Kept", "It is.", 5);
        final Path record = ideas.paths().tasks().taskRecord(TASK).resolve("ideas.json");
        Files.writeString(
                record,
                "{\"ideas\":[{\"id\":\"idea-2\",\"title\":\"Old\",\"whatMustBeTrue\":\"It"
                        + " is.\",\"at\":\"2026-11-13T00:00:00Z\"}]}");
        assertThat(ideas.propose(TASK, "Next", "It is.", 5).id()).isEqualTo("idea-3");
        Files.writeString(
                record,
                "{\"proposed\":1,\"ideas\":[{\"id\":\"idea-2\",\"title\":\"Old\","
                        + "\"whatMustBeTrue\":\"It is.\",\"at\":\"2026-11-13T00:00:00Z\"}]}");
        assertThat(ideas.read(TASK))
                .as("a count below an id it holds is no record of the host's")
                .isEmpty();
    }

    private static TaskIdeas.Idea sized(final int number, final long bytes) {
        final String id = "idea-" + number;
        final long base = written(new TaskIdeas.Idea(id, "T", "x", "2026-11-13T00:00:00Z")) - 1;
        return new TaskIdeas.Idea(
                id, "T", "x".repeat(Math.toIntExact(bytes - base)), "2026-11-13T00:00:00Z");
    }

    private static long written(final TaskIdeas.Idea idea) {
        return written((Object) idea.asMap());
    }

    private static long written(final Object value) {
        return org.fuin.porelo.wire.Json.write(value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length;
    }

    @Test
    void aReplysIdeasAreCountedWithTheirArraysBrackets() {
        // Ideas whose array, written, is one byte past the allowance: counted without its brackets
        // they fitted exactly.
        final long most = TaskInventory.MOST_REPLY_IDEAS;
        final List<TaskIdeas.Idea> ideas = new ArrayList<>();
        // The array so far, "[]" and the commas between its ideas.
        long array = 2;
        while (most + 1 - array > 2 * 1000) {
            final TaskIdeas.Idea idea = sized(ideas.size() + 1, 1000);
            array += written(idea) + (ideas.isEmpty() ? 0 : 1);
            ideas.add(idea);
        }
        final long left = most + 1 - array - 2;
        ideas.add(sized(ideas.size() + 1, left / 2));
        ideas.add(sized(ideas.size() + 1, left - left / 2));
        assertThat(written(ideas.stream().map(TaskIdeas.Idea::asMap).toList()))
                .as("the whole array is one byte past the allowance")
                .isEqualTo(most + 1);
        final List<java.util.Map<String, Object>> carried =
                TaskInventory.carried(List.of(ideas)).getFirst();
        assertThat(written(carried)).as("what a reply carries").isLessThanOrEqualTo(most);
        assertThat(carried).as("only the newest is left out").hasSize(ideas.size() - 1);
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
