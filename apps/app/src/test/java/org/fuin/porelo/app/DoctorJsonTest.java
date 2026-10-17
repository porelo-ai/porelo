package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests that {@code porelo doctor --json} says every finding of the text as data, for a client to
 * read.
 */
class DoctorJsonTest {

    private int exit;

    private String doctor(final Path dir, final String... arguments) {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                XdgPaths.of(name -> null, dir),
                                dir.resolve("bin"),
                                dir.resolve("libexec-hooks"),
                                dir.resolve("agents")),
                        command -> 0);
        final StringWriter out = new StringWriter();
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        final String[] all = new String[arguments.length + 1];
        all[0] = "doctor";
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        exit = cmd.execute(all);
        return out.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyFindingOfTheTextIsInTheJsonInItsOrderWithItsStateAndRemedy(@TempDir final Path dir) {
        final String text = doctor(dir, "--color", "never");
        final int textExit = exit;

        final Map<String, Object> json = (Map<String, Object>) Json.parse(doctor(dir, "--json"));

        assertThat(exit).as("the exit code is the text's").isEqualTo(textExit).isEqualTo(69);
        assertThat(json).containsKeys("version", "findings", "notes", "paths", "process");
        final List<Map<String, Object>> findings = (List<Map<String, Object>>) json.get("findings");
        assertThat(findings).isNotEmpty();
        int at = -1;
        for (final Map<String, Object> finding : findings) {
            assertThat(finding).containsKeys("check", "state", "detail", "remedy");
            assertThat(List.of("OK", "DEGRADED", "MISSING", "UNKNOWN"))
                    .contains((String) finding.get("state"));
            final int line = text.indexOf((String) finding.get("check"));
            assertThat(line)
                    .as("'%s' is a line of the text, after the one before it", finding.get("check"))
                    .isGreaterThan(at);
            at = line;
            if (!"OK".equals(finding.get("state"))) {
                assertThat((String) finding.get("remedy")).isNotBlank();
            }
        }
        assertThat(findings)
                .anySatisfy(finding -> assertThat(finding.get("state")).isEqualTo("MISSING"));
    }

    @Test
    void theJsonIsOneObjectWithNoColourWhateverColourSays(@TempDir final Path dir) {
        final String json = doctor(dir, "--json", "--color", "always");

        assertThat(json).doesNotContain("\u001b[");
        assertThat(json.strip()).startsWith("{").endsWith("}");
        assertThat(Json.parse(json)).isInstanceOf(Map.class);
    }
}
