package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@link PanicCommand}: the one action that stops everything, and removes nothing. */
class PanicCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final StoppingCommandRunner runner = new StoppingCommandRunner();

    private Path root;

    private PoreloContext context(Path dir) {
        root = dir;
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private void stateOf(String container) throws IOException {
        final Path state = root.resolve("run/porelo").resolve(container);
        Files.createDirectories(state);
        Sidecar.recordId(state, container.endsWith("2") ? "222222222222" : "111111111111");
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    @Test
    void stopsEveryRunningTaskWithoutBeingToldTheirNames(@TempDir Path dir) throws IOException {

        // Somebody reaching for this is not in a position to list what is running first.
        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\trunning\n222222222222\trunning\n");
        stateOf("porelo-uc-shell-1");
        stateOf("porelo-uc-build-2");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines())
                .anyMatch(line -> line.startsWith("podman stop") && line.endsWith("111111111111"));
        assertThat(runner.lines())
                .anyMatch(line -> line.startsWith("podman stop") && line.endsWith("222222222222"));
        assertThat(out.toString()).contains("stopped   2 of 2 tasks");
    }

    @Test
    void removesNothing(@TempDir Path dir) throws IOException {

        // The reason for reaching for this is that something is going wrong and nobody yet knows
        // what, which is exactly when destroying the evidence is worst.
        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\trunning\n");
        stateOf("porelo-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
        // 'task resume' is gone; what brings a task back is attaching to it, which offers to start
        // it. The message named a verb the CLI refuses until 2026-09-12.
        assertThat(out.toString()).contains("kept").contains("task attach");
    }

    @Test
    void leavesAStoppedTaskAlone(@TempDir Path dir) throws IOException {

        // It is already stopped; touching it again would only produce noise in the one report an
        // operator is reading to find out what is still up.
        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\texited\n");
        runner.answering("{{.State.Status}} 111111111111", "exited");
        stateOf("porelo-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(out.toString()).contains("nothing is running");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman stop"));
    }

    @Test
    void saysSoWhenThereIsNothingToStop(@TempDir Path dir) {

        assertThat(execute(context(dir), "panic")).isZero();
        assertThat(out.toString()).contains("nothing is running");
    }

    @Test
    void listsWhatItWouldStopWithoutStoppingIt(@TempDir Path dir) throws IOException {

        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\trunning\n");
        stateOf("porelo-uc-shell-1");

        assertThat(execute(context, "panic", "--dry-run")).isZero();

        assertThat(out.toString()).contains("would stop porelo-uc-shell-1");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman stop"));
    }

    @Test
    void touchesNothingThatIsNotATask(@TempDir Path dir) throws IOException {

        // A container somebody else started is not Porelo's to stop, whatever is going wrong.
        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\trunning\nffffffffffff\trunning\n");
        stateOf("porelo-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines()).noneMatch(line -> line.contains("not-porelo-at-all"));
        assertThat(out.toString()).contains("stopped   1 of 1 tasks");
    }

    @Test
    void taskStopAllStopsEveryRunningTaskAndJournalsOneLineWithWhoAndWhen(@TempDir Path dir)
            throws IOException {

        // A stop of everything left no record of who stopped what, when: only each task's own logs,
        // which say nothing of the others.
        final PoreloContext context = context(dir);
        runner.answering("ps", "111111111111\trunning\n222222222222\trunning\n");
        stateOf("porelo-uc-shell-1");
        stateOf("porelo-uc-build-2");

        assertThat(execute(context, "task", "stop", "--all")).isZero();

        assertThat(runner.lines())
                .anyMatch(line -> line.startsWith("podman stop") && line.endsWith("111111111111"))
                .anyMatch(line -> line.startsWith("podman stop") && line.endsWith("222222222222"));
        final java.util.List<String> journal = Files.readAllLines(context.paths().stopJournal());
        assertThat(journal)
                .singleElement()
                .asString()
                .contains("via=cli")
                .contains("porelo-uc-shell-1")
                .contains("porelo-uc-build-2")
                .contains("user=" + System.getProperty("user.name"))
                .containsPattern("^\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void aStopWithNothingRunningIsJournalledToo(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", "");

        assertThat(execute(context, "panic")).isZero();

        assertThat(Files.readAllLines(context.paths().stopJournal()))
                .singleElement()
                .asString()
                .contains("stopped=none");
    }

    @Test
    void allBesideATaskNameIsRefused(@TempDir Path dir) {
        final PoreloContext context = context(dir);
        runner.answering("ps", "");

        assertThat(execute(context, "task", "stop", "porelo-uc-shell-1", "--all")).isEqualTo(2);
        assertThat(context.paths().stopJournal()).doesNotExist();
    }
}
