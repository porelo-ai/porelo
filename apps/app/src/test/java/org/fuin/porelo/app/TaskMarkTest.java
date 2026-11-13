package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a task running unsigned or of a project nothing verified is named so wherever it is
 * listed: work that runs unchecked unmarked reads as checked.
 */
class TaskMarkTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private PoreloContext context(final Path dir) {
        root = dir;
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A task the runtime lists with its project and the commit its file was verified at. */
    private Path task(final String container, final String project, final String commit)
            throws IOException {
        runner.answering(
                "ps",
                container
                        + "\tUp 4 minutes\t1788500000\t0\t"
                        + project
                        + "\tguarded\t\t"
                        + commit
                        + "\n");
        final Path state = root.resolve("run/porelo").resolve(container);
        Files.createDirectories(state);
        new Sidecar(
                        Sidecar.VERSION,
                        project,
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        return state;
    }

    private static void certify(final Path state) throws IOException {
        Files.writeString(state.resolve(TaskState.CERTIFIERS), "task:uc:* cert-authority k\n");
    }

    private TaskInventory.Task only(final PoreloContext context) {
        return new TaskInventory(context).tasks().getFirst();
    }

    private String listed(final PoreloContext context) {
        final TaskListCommand list = new TaskListCommand();
        list.setContext(context);
        final StringWriter out = new StringWriter();
        list.render(new PrintWriter(out, true));
        return out.toString();
    }

    @Test
    void aTaskOfAProjectNothingVerifiedAnswersUnverified(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        certify(task("porelo-uc-shell-1", "uc", ""));

        assertThat(only(context).asMap())
                .as("an empty commit is a file nothing verified")
                .containsEntry("verified", false)
                .containsEntry("certified", true);
        assertThat(listed(context))
                .as("task list names it in its line")
                .containsPattern("(?m)^shell\\S* .*unverified$");
    }

    @Test
    void anUncertifiedTaskIsListedUnsignedAndACertifiedOneOfAVerifiedProjectIsNotMarked(
            @TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "uc", "0123abcd");

        assertThat(only(context).asMap()).containsEntry("verified", true);
        assertThat(listed(context))
                .as("no certificate reads as unsigned, never as certified")
                .containsPattern("(?m)^shell\\S* .* unsigned$");

        certify(state);

        assertThat(only(context).mark()).as("certified and verified").isEmpty();
        assertThat(listed(context)).doesNotContain("unsigned").doesNotContain("unverified");
    }

    @Test
    void theDefaultProjectIsMarkedUnsignedOnlyAsItHasNoFileToVerify(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        task("porelo-default-shell-1", "default", "");

        assertThat(only(context).asMap()).containsEntry("verified", true);
        assertThat(only(context).mark()).isEqualTo(TrustMark.UNSIGNED);
        assertThat(listed(context)).doesNotContain("unverified");
    }
}
