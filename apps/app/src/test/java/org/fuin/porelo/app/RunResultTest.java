package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.Waiting;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunResultTest {

    private static final Waiting.LastMessage DECLARED =
            new Waiting.LastMessage(Map.of("kind", "result"), "said");

    /** Shows every line with a mark, hiding the session records, as an agent's Format does. */
    private static List<@Nullable String> formatted(final List<String> lines) {
        final List<@Nullable String> shown = new ArrayList<>();
        for (final String line : lines) {
            shown.add(line.contains("\"session\"") ? null : "> " + line);
        }
        return shown;
    }

    @Test
    void aRunWhoseAgentDeclaresItsLastMessageAnswersWithIt(@TempDir final Path dir)
            throws Exception {
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        """
                        {"kind":"session","id":"s-1"}
                        {"kind":"result","said":"an early turn"}
                        not a record
                        {"kind":"result","said":"main, 5 files"}
                        """);

        final RunResult result = RunResult.of(log, DECLARED, RunResultTest::formatted);

        assertThat(result).isEqualTo(new RunResult("main, 5 files", RunResult.Kind.ANSWER, false));
    }

    @Test
    void aRunWithoutADeclarationOrWithoutTheRecordAnswersWithItsLastFormattedLines(
            @TempDir final Path dir) throws Exception {
        final StringBuilder text = new StringBuilder("{\"kind\":\"session\",\"id\":\"s-1\"}\n");
        for (int i = 1; i <= 60; i++) {
            text.append("line ").append(i).append('\n');
        }
        final Path log = Files.writeString(dir.resolve("task.log"), text);

        final RunResult undeclared = RunResult.of(log, null, RunResultTest::formatted);
        final RunResult timedOut = RunResult.of(log, DECLARED, RunResultTest::formatted);

        assertThat(undeclared.kind()).isEqualTo(RunResult.Kind.LAST_LINES);
        assertThat(undeclared.text().lines().toList())
                .as("the last lines, as the agent shows them, hidden ones left out")
                .hasSize(RunResult.LAST_LINES)
                .endsWith("> line 60")
                .noneMatch(line -> line.contains("session"));
        assertThat(timedOut).isEqualTo(undeclared);
    }

    @Test
    void anAnswerLongerThanTheBoundIsCutAtACodePointAndSaysSo(@TempDir final Path dir)
            throws Exception {
        // Three bytes each in UTF-8; a cut by bytes or by chars would split one.
        final String said = "語".repeat(RunResult.MOST_BYTES);
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        "{\"kind\":\"result\",\"said\":\"" + said + "\"}\n",
                        StandardCharsets.UTF_8);

        final RunResult result = RunResult.of(log, DECLARED, RunResultTest::formatted);

        assertThat(result.cut()).isTrue();
        assertThat(result.text().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(RunResult.MOST_BYTES);
        assertThat(result.text()).isEqualTo("語".repeat(RunResult.MOST_BYTES / 3));
    }

    @Test
    void aLogThatIsALinkOrMissingAnswersNothing(@TempDir final Path dir) throws Exception {
        final Path elsewhere =
                Files.writeString(dir.resolve("secret"), "{\"kind\":\"result\",\"said\":\"x\"}\n");
        final Path link = Files.createSymbolicLink(dir.resolve("task.log"), elsewhere);

        assertThat(RunResult.of(link, DECLARED, RunResultTest::formatted)).isNull();
        assertThat(RunResult.of(dir.resolve("none.log"), DECLARED, RunResultTest::formatted))
                .isNull();
    }

    @Test
    void onlyTheEndOfAHugeLogIsRead(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("task.log");
        try (var out = Files.newBufferedWriter(log)) {
            // One line far longer than what is read, then the answer.
            out.write("x".repeat(RunResult.MOST_READ * 2));
            out.write("\n{\"kind\":\"result\",\"said\":\"done\"}\n");
        }

        assertThat(RunResult.of(log, DECLARED, RunResultTest::formatted))
                .isEqualTo(new RunResult("done", RunResult.Kind.ANSWER, false));
    }

    @Test
    void aRunThatShowedNothingAnswersNothing(@TempDir final Path dir) throws Exception {
        final Path empty = Files.writeString(dir.resolve("task.log"), "");
        final Path hidden =
                Files.writeString(dir.resolve("hidden.log"), "{\"kind\":\"session\"}\n");

        assertThat(RunResult.of(empty, null, RunResultTest::formatted)).isNull();
        assertThat(RunResult.of(hidden, null, RunResultTest::formatted)).isNull();
    }
}
