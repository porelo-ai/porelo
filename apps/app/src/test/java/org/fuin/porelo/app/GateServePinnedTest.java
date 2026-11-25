package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reviewer's gate given a {@code --pinned} value that is no plain ref is refused with exit 2,
 * naming the option, before anything is read or bound; it never throws.
 */
class GateServePinnedTest {

    @TempDir Path dir;

    @Test
    void aMalformedPinnedRefExitsTwoNamingTheOption() {
        for (final String pinned : List.of("refs/porelo/*", "HEAD", "refs/a/../b", "refs/a b")) {
            final StringWriter err = new StringWriter();
            final picocli.CommandLine cli = PoreloCli.commandLine(context());
            cli.setOut(new PrintWriter(new StringWriter()));
            cli.setErr(new PrintWriter(err));
            final int exit =
                    cli.execute(
                            "gate",
                            "serve",
                            "--project",
                            "shop",
                            "--port",
                            "0",
                            "--pinned",
                            pinned);
            assertThat(exit).as("the exit of a gate pinned to '%s'", pinned).isEqualTo(2);
            assertThat(err.toString())
                    .as("what a gate pinned to '%s' says", pinned)
                    .contains("--pinned");
        }
    }

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
        // Nothing is run: a refused value stops the command before any program starts.
        return new PoreloContext(
                command -> new CommandResult(command, 1, "", "not run here"),
                new PoreloPaths(xdg, dir.resolve("bin")),
                args -> 0);
    }
}
