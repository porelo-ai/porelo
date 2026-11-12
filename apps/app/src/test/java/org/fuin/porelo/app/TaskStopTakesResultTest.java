package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A stopped task answers the file it handed back, whoever asks first. */
class TaskStopTakesResultTest {

    private static final String ID = "c0ffee".repeat(10) + "abcd";

    private final StoppingCommandRunner runner = new StoppingCommandRunner();

    private PoreloContext context(final Path dir) {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void anAttendedTaskStoppedAnswersItsResultThoughItsStartFoundItMissing(@TempDir final Path dir)
            throws IOException {
        // A listing in the second before its container ran took the file as missing, and nothing
        // looked again: the person's discussion ended, and its idea stayed missing.
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), "porelo-uc-all", "uc", "all", ID);
        final TaskResult result = new TaskResult(context.paths());
        result.prepare("porelo-uc-all", "idea.json");
        assertThat(result.takeOnce("porelo-uc-all").state())
                .as("taken at its start, before the agent wrote")
                .isEqualTo(TaskResult.State.MISSING);
        Files.writeString(result.box("porelo-uc-all").resolve("idea.json"), "{\"idea\":2}");
        runner.answering(
                "ps",
                "porelo-uc-all\tUp 4 minutes\t1700000000\t0\tuc\tguarded\t\t\t" + ID + "\tall\n");

        new TaskControl(context).stop("porelo-uc-all");

        final TaskResult.Answer answer = result.answer("porelo-uc-all");
        assertThat(answer).isNotNull();
        assertThat(answer.taken().state())
                .as("taken again at its stop")
                .isEqualTo(TaskResult.State.HANDED);
        assertThat(answer.content()).isEqualTo("{\"idea\":2}");
    }

    @Test
    void anAttendedTaskStoppedAnswersItsResultBeforeAnyListing(@TempDir final Path dir)
            throws IOException {
        // An attended session runs no prompt run that takes the file, and only a listing took it
        // from a stopped task: a Result asked first answered it missing.
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), "porelo-uc-all", "uc", "all", ID);
        final TaskResult result = new TaskResult(context.paths());
        result.prepare("porelo-uc-all", "idea.json");
        Files.writeString(result.box("porelo-uc-all").resolve("idea.json"), "{\"idea\":1}");
        runner.answering(
                "ps",
                "porelo-uc-all\tUp 4 minutes\t1700000000\t0\tuc\tguarded\t\t\t" + ID + "\tall\n");

        new TaskControl(context).stop("porelo-uc-all");

        final TaskResult.Answer answer = result.answer("porelo-uc-all");
        assertThat(answer).as("answered without a listing first").isNotNull();
        assertThat(answer.taken().state()).isEqualTo(TaskResult.State.HANDED);
        assertThat(answer.content()).isEqualTo("{\"idea\":1}");
    }
}
