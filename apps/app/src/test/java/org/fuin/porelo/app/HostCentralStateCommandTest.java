package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.RootOwnedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Tests for {@link HostCentralStateCommand} ({@code porelo host central-state <project> [--json]}).
 */
class HostCentralStateCommandTest {

    private static final String VALID_STATE =
            """
            {"project":"porelo","read_at":"2026-11-09T10:00:00Z","repositories":[
              {"repository":"stand-in","state":"ahead","commits":3},
              {"repository":"docs","state":"up-to-date"},
              {"repository":"notes","state":"moved-on"},
              {"repository":"ops","state":"cannot-say","why":"unreachable"}
            ]}
            """;

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private CommandLine cli(final Path centralDir, final RootOwnedFile.Attributes attributes) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir.resolve("home"));
        final PoreloContext context =
                new PoreloContext(
                        runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0, name -> null);
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        final HostCentralStateCommand command =
                (HostCentralStateCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("central-state")
                                .getCommand();
        command.directory = centralDir;
        command.attributes = attributes;
        return line;
    }

    private static RootOwnedFile.Attributes rootOwnedAttributes() {
        return path -> {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(path.toString());
            }
            return Map.of(
                    "uid",
                    0,
                    "mode",
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? 0040755 : 0100640);
        };
    }

    @Test
    void readsThePublishedCentralStateFileDirectlyInJsonAndTextWithoutRunningAProgram()
            throws Exception {
        final Path centralDir = Files.createDirectories(dir.resolve("central"));
        Files.writeString(centralDir.resolve("porelo.json"), VALID_STATE);

        final int jsonExit =
                cli(centralDir, rootOwnedAttributes())
                        .execute("host", "central-state", "porelo", "--json");

        assertThat(jsonExit).as("exit code for valid JSON read").isZero();
        assertThat(err.toString()).as("no error output").isEmpty();
        assertThat(runner.invocations())
                .as("reads the file directly without spawning a child process")
                .isEmpty();
        final Map<?, ?> parsed = (Map<?, ?>) Json.parse(out.toString());
        assertThat(parsed.get("project")).as("project name").isEqualTo("porelo");
        assertThat(parsed.get("read_at"))
                .as("measurement timestamp")
                .isEqualTo("2026-11-09T10:00:00Z");
        assertThat(out.toString()).contains("\"commits\":3");
        assertThat(parsed.get("repositories"))
                .as("validated repository states")
                .isEqualTo(
                        List.of(
                                Map.of("repository", "stand-in", "state", "ahead", "commits", 3.0),
                                Map.of("repository", "docs", "state", "up-to-date"),
                                Map.of("repository", "notes", "state", "moved-on"),
                                Map.of(
                                        "repository",
                                        "ops",
                                        "state",
                                        "cannot-say",
                                        "why",
                                        "unreachable")));

        out.getBuffer().setLength(0);
        final int textExit =
                cli(centralDir, rootOwnedAttributes()).execute("host", "central-state", "porelo");

        assertThat(textExit).as("exit code for plain text read").isZero();
        assertThat(out.toString().lines().toList())
                .as("plain text lists each repository state")
                .containsExactly(
                        "stand-in  ahead  3",
                        "docs  up-to-date",
                        "notes  moved-on",
                        "ops  cannot-say  unreachable");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "file-not-root",
                "file-group-writable",
                "file-other-writable",
                "file-symlink",
                "parent-not-root",
                "parent-group-writable",
                "parent-symlink"
            })
    void aMissingOrUntrustedCentralFileAnswersCannotSayWithHostCommandFailedNeverUpToDate(
            final String flaw) throws Exception {
        final Path centralDir = Files.createDirectories(dir.resolve("central"));
        final Path file = centralDir.resolve("porelo.json");
        if (!"missing".equals(flaw)) {
            Files.writeString(
                    file,
                    "{\"project\":\"porelo\",\"read_at\":\"2026-11-09T10:00:00Z\","
                            + "\"repositories\":[{\"repository\":\"docs\",\"state\":\"up-to-date\"}]}");
        }
        final RootOwnedFile.Attributes base = rootOwnedAttributes();
        final RootOwnedFile.Attributes attributes =
                path ->
                        switch (flaw) {
                            case "file-not-root" ->
                                    path.equals(file)
                                            ? Map.of("uid", 1000, "mode", 0100640)
                                            : base.read(path);
                            case "file-group-writable" ->
                                    path.equals(file)
                                            ? Map.of("uid", 0, "mode", 0100660)
                                            : base.read(path);
                            case "file-other-writable" ->
                                    path.equals(file)
                                            ? Map.of("uid", 0, "mode", 0100642)
                                            : base.read(path);
                            case "file-symlink" ->
                                    path.equals(file)
                                            ? Map.of("uid", 0, "mode", 0120777)
                                            : base.read(path);
                            case "parent-not-root" ->
                                    path.equals(centralDir)
                                            ? Map.of("uid", 1000, "mode", 0040755)
                                            : base.read(path);
                            case "parent-group-writable" ->
                                    path.equals(centralDir)
                                            ? Map.of("uid", 0, "mode", 0040775)
                                            : base.read(path);
                            case "parent-symlink" ->
                                    path.equals(centralDir)
                                            ? Map.of("uid", 0, "mode", 0120777)
                                            : base.read(path);
                            default -> base.read(path);
                        };

        final int exit =
                cli(centralDir, attributes).execute("host", "central-state", "porelo", "--json");

        assertThat(exit).as("answers cannot-say as JSON").isZero();
        assertThat(out.toString())
                .as("never reports up-to-date when the file is missing or untrusted")
                .doesNotContain("up-to-date");
        assertThat(Json.parse(out.toString()))
                .as("reports cannot-say with HostCentral's failed reason")
                .isEqualTo(
                        Map.of(
                                "project",
                                "porelo",
                                "state",
                                "cannot-say",
                                "why",
                                HostCentral.FAILED,
                                "repositories",
                                List.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"project\":\"other\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[]}",
                "{\"project\":\"porelo\",\"read_at\":\"not-a-timestamp\",\"repositories\":[]}",
                "{\"project\":\"porelo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[{\"repository\":\"docs\",\"state\":\"behind\"}]}",
                "{\"project\":\"porelo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[{\"repository\":\"docs\",\"state\":\"ahead\",\"commits\":1000000}]}",
                "{\"project\":\"porelo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[{\"repository\":\"docs\",\"state\":\"up-to-date\"},{\"repository\":\"DOCS\",\"state\":\"up-to-date\"}]}"
            })
    void anUnparsableOrDishonestCentralFileAnswersCannotSayWithHostAnswerRefused(
            final String content) throws Exception {
        final Path centralDir = Files.createDirectories(dir.resolve("central"));
        Files.writeString(centralDir.resolve("porelo.json"), content);

        final int exit =
                cli(centralDir, rootOwnedAttributes())
                        .execute("host", "central-state", "porelo", "--json");

        assertThat(exit).as("answers cannot-say as JSON").isZero();
        assertThat(out.toString())
                .as("never reports up-to-date for a refused file")
                .doesNotContain("up-to-date");
        assertThat(Json.parse(out.toString()))
                .as("reports cannot-say with HostCentral's refused reason")
                .isEqualTo(
                        Map.of(
                                "project",
                                "porelo",
                                "state",
                                "cannot-say",
                                "why",
                                HostCentral.REFUSED,
                                "repositories",
                                List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../porelo", "Not/A/Project", "bad-", "UPPER"})
    void anInvalidProjectNameIsRefusedWithExit64(final String badProject) throws Exception {
        final Path centralDir = Files.createDirectories(dir.resolve("central"));

        final int exit =
                cli(centralDir, rootOwnedAttributes()).execute("host", "central-state", badProject);

        assertThat(exit).as("invalid project name exits 64").isEqualTo(64);
        assertThat(err.toString())
                .as("names the usage refusal")
                .contains("central-state names one project");
        assertThat(out.toString()).as("nothing written to stdout").isEmpty();
    }
}
