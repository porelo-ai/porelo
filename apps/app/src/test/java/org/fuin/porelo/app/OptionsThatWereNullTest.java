package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Commands that read an option nobody gave, found when nullness began to be checked.
 * <p>
 * Each once went on with {@code null} - looking up a project called "null", or writing a token to
 * no file at all - rather than saying what was missing.
 */
class OptionsThatWereNullTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void talkPassAsksWhichProjectWhenTheTaskNamesNone(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox("porelo-p-shell-1")).create();

        final int code = execute(context, "talk", "pass", "porelo-p-shell-1");

        assertThat(code).as(err.toString()).isEqualTo(2);
        assertThat(err.toString()).contains("porelo-p-shell-1 names no project; say which, with --project");
    }

    @Test
    void talkPassTakesTheProjectTheTaskRecordsWhenNoneIsGiven(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox("porelo-uc-shell-1")).create();
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = Files.createDirectories(dir.resolve("run/porelo/porelo-uc-shell-1"));
        new org.fuin.porelo.wire.Sidecar(org.fuin.porelo.wire.Sidecar.VERSION, "uc", "guarded",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(), "/usr/bin/porelo",
                state.toString()).writeTo(state.resolve("sidecar.json"));

        execute(context, "talk", "pass", "porelo-uc-shell-1");

        // No project file for 'uc' here, so it stops - naming the project it took from the task.
        assertThat(err.toString()).contains("'uc'").doesNotContain("names no project");
    }

    @Test
    void talkHoldTakesTheProjectTheTaskRecordsNotAFileInTheCurrentDirectory(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox("porelo-uc-shell-1")).create();
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = Files.createDirectories(dir.resolve("run/porelo/porelo-uc-shell-1"));
        new org.fuin.porelo.wire.Sidecar(org.fuin.porelo.wire.Sidecar.VERSION, "uc", "guarded",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(), "/usr/bin/porelo",
                state.toString()).writeTo(state.resolve("sidecar.json"));

        execute(context, "talk", "hold", "porelo-uc-shell-1", "somebody");

        // It read ./project.yml once, wherever it was run; now it names the task's project, 'uc'.
        assertThat(err.toString()).contains("'uc'").doesNotContain("project.yml");
    }

    @Test
    void vaultServeRefusesToStartWithoutAFileForItsToken(@TempDir Path dir) {

        final int code = execute(context(dir), "vault", "serve", "--socket", dir.resolve("s").toString(),
                "--credential", "c", "--upstream", "https://example.test", "--pid-file",
                dir.resolve("p").toString());

        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("--token-file");
    }

}
