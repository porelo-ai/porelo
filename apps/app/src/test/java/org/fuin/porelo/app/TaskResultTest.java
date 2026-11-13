package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The one file a task hands back: taken from its box at its end, bounded, never through a link, and
 * kept on the host for its reader until the next start of the name.
 */
class TaskResultTest {
    private static final String TASK = "porelo-uc-work";
    private static final String ID = "c0ffee".repeat(10) + "abcd";
    private static final String NAME = "idea.json";
    private final StoppingCommandRunner runner = new StoppingCommandRunner();
    @TempDir Path dir;

    private PoreloContext context() {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0)
                .recognizingTasks();
    }

    /** A stand-in task, stopped or running, started with {@link #NAME} to hand back. */
    private TaskResult task(final PoreloContext context, final boolean running) throws IOException {
        return task(context, running ? "Up 4 minutes" : "Exited (0) 1 minute ago");
    }

    /** A stand-in task in the runtime's own words for its state, started with {@link #NAME}. */
    private TaskResult task(final PoreloContext context, final String status) throws IOException {
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "work", ID);
        listedAs(status);
        runner.answering("{{.Id}}", ID);
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        org.fuin.porelo.wire.Sidecar.recordId(state, ID);
        final TaskResult result = new TaskResult(context.paths());
        result.prepare(TASK, NAME);
        return result;
    }

    private void listedAs(final String status) {
        runner.answering(
                "ps",
                TASK
                        + "\t"
                        + status
                        + "\t1788500000\t1788509999\tuc\tguarded\t\t\t"
                        + ID
                        + "\twork\n");
    }

    private Path written(final TaskResult result, final byte[] content) throws IOException {
        return Files.write(result.box(TASK).resolve(NAME), content);
    }

    @Test
    void aFileWrittenIntoTheBoxIsHandedBackWhole() throws IOException {
        // The window reads this text on another machine; a cut or changed byte is lost work.
        final TaskResult result = task(context(), true);
        final String idea = "{\"idea\": \"Grüße 😀\"}\n\ttabbed\r\n";
        final Path file = written(result, idea.getBytes(StandardCharsets.UTF_8));

        assertThat(result.take(TASK))
                .as("taken whole, with what was read")
                .isEqualTo(
                        new TaskResult.Taken(
                                NAME,
                                TaskResult.State.HANDED,
                                idea.getBytes(StandardCharsets.UTF_8).length));
        assertThat(result.answer(TASK))
                .as("the reader is answered its text")
                .isEqualTo(
                        new TaskResult.Answer(
                                new TaskResult.Taken(
                                        NAME,
                                        TaskResult.State.HANDED,
                                        idea.getBytes(StandardCharsets.UTF_8).length),
                                idea));
        assertThat(file).as("the agent's own file stays in its box").hasContent(idea);
    }

    @Test
    void aFileOfExactlyTheBoundIsHandedBackAndOneByteMoreIsTooLargeWithItsSize()
            throws IOException {
        // An unbounded read lets one task fill the host's disk and every reply of the daemon.
        final TaskResult result = task(context(), true);
        written(result, "a".repeat(TaskResult.MOST).getBytes(StandardCharsets.UTF_8));
        assertThat(result.take(TASK).state())
                .as("1 MiB is still handed back")
                .isEqualTo(TaskResult.State.HANDED);

        result.clear(TASK);
        written(result, "a".repeat(3 * TaskResult.MOST + 7).getBytes(StandardCharsets.UTF_8));
        assertThat(result.take(TASK))
                .as("too large, with the size its attributes give")
                .isEqualTo(
                        new TaskResult.Taken(
                                NAME, TaskResult.State.TOO_LARGE, 3L * TaskResult.MOST + 7));
        assertThat(result.answer(TASK).content()).as("no part of it is kept").isNull();
        assertThat(context().paths().tasks().result(TASK).resolve("taken"))
                .as("nothing of it is kept on the host")
                .doesNotExist();
    }

    @Test
    void aLinkIsUnreadableAndWhatItPointsAtIsNeverRead() throws IOException {
        // A link in the box would hand a host file the agent cannot see to whoever reads results.
        final TaskResult result = task(context(), true);
        final Path secret = Files.writeString(dir.resolve("secret"), "the host's own words");
        final Path link = Files.createSymbolicLink(result.box(TASK).resolve(NAME), secret);

        assertThat(result.take(TASK))
                .as("a link is not a file handed back")
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.UNREADABLE, 0));
        assertThat(result.answer(TASK).content()).as("nothing is answered").isNull();
        try (Stream<Path> kept = Files.walk(context().paths().tasks().result(TASK))) {
            assertThat(
                            kept.filter(
                                            path ->
                                                    Files.isRegularFile(
                                                            path,
                                                            java.nio.file.LinkOption
                                                                    .NOFOLLOW_LINKS))
                                    .map(TaskResultTest::read)
                                    .filter(text -> text.contains("the host's own words")))
                    .as("the target's content is nowhere in what the host keeps")
                    .isEmpty();
        }
        assertThat(Files.isSymbolicLink(link)).as("the link is put back as it was").isTrue();
        assertThat(secret).as("its target is untouched").hasContent("the host's own words");
    }

    @Test
    void textThatIsNotUtf8OrHoldsAControlCharacterIsUnreadable() throws IOException {
        // A reply the window cannot decode, or one six times its size on the wire, answers nothing.
        final TaskResult result = task(context(), true);
        written(result, new byte[] {'o', 'k', (byte) 0xC3, (byte) 0x28});
        assertThat(result.take(TASK))
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.UNREADABLE, 4));
        written(result, "nul\0here".getBytes(StandardCharsets.UTF_8));
        assertThat(result.take(TASK))
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.UNREADABLE, 8));
    }

    @Test
    void noFileIsMissingAndOneWrittenLaterIsTakenAtTheNextEnd() throws IOException {
        // Saying missing for a file that exists, or never looking again, loses the task's answer.
        final TaskResult result = task(context(), true);
        assertThat(result.answer(TASK))
                .as("nothing was taken yet")
                .isEqualTo(
                        new TaskResult.Answer(
                                new TaskResult.Taken(NAME, TaskResult.State.MISSING, 0), null));
        assertThat(result.take(TASK))
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.MISSING, 0));
        written(result, "late".getBytes(StandardCharsets.UTF_8));
        assertThat(result.take(TASK).state()).isEqualTo(TaskResult.State.HANDED);
    }

    @Test
    void aFileHandedBackIsKeptWhenALaterEndFindsItGoneOrChanged() throws IOException {
        // The end a task declared is what it handed back; a later end must not overwrite it.
        final TaskResult result = task(context(), true);
        final Path file = written(result, "first".getBytes(StandardCharsets.UTF_8));
        result.take(TASK);
        Files.delete(file);
        assertThat(result.take(TASK).state()).isEqualTo(TaskResult.State.HANDED);
        written(result, "second".getBytes(StandardCharsets.UTF_8));
        result.take(TASK);
        assertThat(result.answer(TASK).content()).isEqualTo("first");
    }

    @Test
    void aStoppedTaskIsListedWithWhatItHandedBack() throws IOException {
        // A window learns of the end from the listing; a result taken only on request is missed.
        final PoreloContext context = context();
        final TaskResult result = task(context, false);
        written(result, "done".getBytes(StandardCharsets.UTF_8));

        final Map<?, ?> listed =
                (Map<?, ?>) new TaskInventory(context).tasks().getFirst().asMap().get("result");

        assertThat(listed)
                .as("the listing took it and says so")
                .isEqualTo(Map.of("name", NAME, "state", "handed", "bytes", 4L));
    }

    @Test
    void aCreatedTaskNotYetRunningIsListedWithoutTakingAndItsEndHandsTheFile() throws IOException {
        // A created container is not running, yet nothing ran in it: a "missing" taken then is read
        // as final and the file its agent writes half a second later is dropped.
        final PoreloContext context = context();
        final TaskResult result = task(context, "Created");

        new TaskInventory(context).tasks();

        assertThat(result.taken(TASK)).as("a listing of a created task takes nothing").isNull();

        written(result, "done".getBytes(StandardCharsets.UTF_8));
        listedAs("Exited (0) 1 second ago");
        final Map<?, ?> listed =
                (Map<?, ?>) new TaskInventory(context).tasks().getFirst().asMap().get("result");

        assertThat(listed)
                .as("its end hands the file")
                .isEqualTo(Map.of("name", NAME, "state", "handed", "bytes", 4L));
    }

    @Test
    void aKeptMissingIsTakenAgainOnceAndAKeptHandedNever() throws IOException {
        // A missing a listing took is provisional; only a file handed back is the task's answer.
        final TaskResult result = task(context(), false);
        assertThat(result.takeOnce(TASK))
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.MISSING, 0));
        final Path file = written(result, "late".getBytes(StandardCharsets.UTF_8));

        assertThat(result.takeOnce(TASK))
                .as("the kept missing is taken again")
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.HANDED, 4));

        Files.writeString(file, "changed");
        assertThat(result.takeOnce(TASK))
                .as("a kept handed is never taken again")
                .isEqualTo(new TaskResult.Taken(NAME, TaskResult.State.HANDED, 4));
        assertThat(result.answer(TASK).content()).isEqualTo("late");
    }

    @Test
    void itIsStillAnsweredAfterTheContainerAndTheTaskAreRemoved() throws IOException {
        // The window reads the result after the task's end, which may have removed everything.
        final PoreloContext context = context();
        final TaskResult result = task(context, false);
        written(result, "kept".getBytes(StandardCharsets.UTF_8));

        new TaskControl(context).remove(TASK, false, true);

        assertThat(context.paths().tasks().taskRecord(TASK))
                .as("the task's record went with it")
                .doesNotExist();
        assertThat(result.box(TASK)).as("its box went with it").doesNotExist();
        assertThat(new TaskResult(context.paths()).answer(TASK))
                .as("taken at the removal and answered after it")
                .isEqualTo(
                        new TaskResult.Answer(
                                new TaskResult.Taken(NAME, TaskResult.State.HANDED, 4), "kept"));
    }

    @Test
    void theNextStartForgetsWhatWasTakenAndKeepsTheName() throws IOException {
        // An answer left from the run before reads as this run's, which handed nothing yet.
        final PoreloContext context = context();
        final TaskResult result = task(context, false);
        final Path file = written(result, "old".getBytes(StandardCharsets.UTF_8));
        result.take(TASK);

        assertThat(new TaskControl(context).resume(TASK).outcome())
                .isEqualTo(TaskControl.Outcome.NO_HELPERS_RECORDED);

        assertThat(result.taken(TASK)).as("what was taken is forgotten").isNull();
        assertThat(result.name(TASK)).as("a task brought back keeps its name").isEqualTo(NAME);
        assertThat(file).as("the agent's own file is its own").hasContent("old");
    }

    static Stream<String> refusedNames() {
        return Stream.of(
                "",
                ".",
                "..",
                "dir/idea.json",
                "/idea.json",
                "line\nbreak",
                "nul\0here",
                "escape\u001bhere",
                "x".repeat(TaskResult.MOST_NAME + 1),
                "€".repeat(86));
    }

    @ParameterizedTest
    @MethodSource("refusedNames")
    void aNameThatIsNoSingleFilesIsRefused(final String name) {
        // A directory part or a dot-dot would take a file from outside the box.
        assertThat(TaskResult.refusal(name)).as("refused: %s", name).isNotNull();
    }

    @Test
    void aSingleFilesNameIsTaken() {
        assertThat(TaskResult.refusal(NAME)).isNull();
        assertThat(TaskResult.refusal("x".repeat(TaskResult.MOST_NAME))).isNull();
        assertThat(TaskResult.refusal("..idea")).isNull();
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void aFileTheAgentWroteWhileTheOldOneWasTakenStays() throws IOException {
        // A rename back would replace the new file with the one just taken.
        final Path held = Files.writeString(dir.resolve("held"), "taken");
        final Path file = Files.writeString(dir.resolve("idea.json"), "written meanwhile");

        TaskResult.putBack(held, file);

        assertThat(file).hasContent("written meanwhile");
        assertThat(held).doesNotExist();
    }

    @Test
    void aTakenFileGoesBackWhereNothingWasWritten() throws IOException {
        final Path held = Files.writeString(dir.resolve("held"), "taken");
        final Path file = dir.resolve("idea.json");

        TaskResult.putBack(held, file);

        assertThat(file).hasContent("taken");
        assertThat(held).doesNotExist();
    }
}
