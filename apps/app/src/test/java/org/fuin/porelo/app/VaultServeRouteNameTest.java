package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A route's name becomes its token file, so the broker refuses one that is no credential name. */
class VaultServeRouteNameTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** Harm prevented: a route's token is written outside the directory its task reads. */
    @Test
    void aRouteNamedOutOfItsDirectoryIsRefusedAndWritesNothing(@TempDir Path dir)
            throws IOException {
        final Path routes = Files.createDirectories(dir.resolve("task/routes"));
        final CommandLine cmd = PoreloCli.commandLine(context(dir));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        final int code =
                cmd.execute(
                        "vault",
                        "serve",
                        "--socket",
                        dir.resolve("s").toString(),
                        "--credential",
                        "c",
                        "--upstream",
                        "https://example.test",
                        "--token-file",
                        dir.resolve("t").toString(),
                        "--pid-file",
                        dir.resolve("p").toString(),
                        "--route",
                        "../x=https://api.example.org|Authorization|Bearer |",
                        "--route-tokens",
                        routes.toString());

        assertThat(code).as("refused: %s", err).isEqualTo(2);
        assertThat(err.toString()).as("says why").contains("no credential name");
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.filter(f -> f.getFileName().toString().endsWith(".token")))
                    .as("no token file anywhere")
                    .isEmpty();
        }
    }
}
