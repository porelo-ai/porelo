package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a task from before a restart offers to copy its workspace out and then to remove it, instead of printing
 * the two commands to type.
 */
class RecoverOfferTest {

    @TempDir
    private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context() {
        return new PoreloContext(runner, new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                arguments -> 0);
    }

    private Offer answering(final String... answers) {
        final ArrayDeque<String> left = new ArrayDeque<>(List.of(answers));
        return new Offer(question -> left.poll(), false, false, new PrintWriter(new StringWriter(), true));
    }

    @Test
    void theWorkspaceIsCopiedFirstAndTheTaskRemovedOnlyOnAnExplicitYes() {
        runner.answering("cp", "");

        assertThat(TaskResumeCommand.recover("porelo-uc-a", answering("", ""), context(), dir, new PrintWriter(out, true)))
                .isTrue();
        assertThat(runner.lines()).anyMatch(line -> line.contains("cp") && line.contains(":/workspace")
                && line.contains(dir.resolve("recovered").toString()));
        assertThat(runner.lines()).as("removing is no by default").noneMatch(line -> line.contains(" rm "));
        assertThat(out.toString()).contains("recovered").contains("kept");
    }

    @Test
    void anExistingRecoveredFolderIsNeverWrittenInto() throws Exception {
        Files.createDirectories(dir.resolve("recovered"));
        runner.answering("cp", "");

        TaskResumeCommand.recover("porelo-uc-a", answering("", ""), context(), dir, new PrintWriter(out, true));

        assertThat(runner.lines()).anyMatch(line -> line.contains(dir.resolve("recovered-2").toString()));
    }

    @Test
    void nobodyToAskOrANoIsTheRefusalAsBefore() {
        assertThat(TaskResumeCommand.recover("porelo-uc-a", Offer.NOBODY, context(), dir, new PrintWriter(out, true)))
                .isFalse();
        assertThat(TaskResumeCommand.recover("porelo-uc-a", answering("n"), context(), dir, new PrintWriter(out, true)))
                .isFalse();
        assertThat(runner.lines()).noneMatch(line -> line.contains("cp"));
    }
}
