package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests collection against a runtime whose task disappears during a look. */
class LoopWatchCommandTest {

    private static final String TASK = "porelo-busy-busy";

    private record Printed(int status, String text) {}

    private static Printed run(String runtime, String command) throws Exception {
        final Process process =
                new ProcessBuilder("sh", "-c", "podman() { " + runtime + "; }; " + command)
                        .redirectErrorStream(true)
                        .start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Printed(process.waitFor(), printed);
    }

    private static String command(boolean records) {
        return records
                ? LoopWatch.command(new ToolRecords(".sessions", ".jsonl"), "refused")
                : LoopWatch.COMMAND;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTaskThatDisappearsDoesNotDiscardOtherTasksHistories(boolean records) throws Exception {
        final Printed printed =
                run(
                        "if [ \"$1\" = ps ]; then printf '"
                                + TASK
                                + "\\nporelo-stopped\\n';"
                                + " elif [ \"$2\" = "
                                + TASK
                                + " ]; then"
                                + " if [ \"$3\" = tmux ]; then printf 'refused\\nrefused\\nrefused\\n'; fi;"
                                + " else return 125; fi",
                        command(records));
        assertThat(printed.status())
                .as("a failed exec must not make TaskHistories discard a successful listing")
                .isZero();
        final LoopWatch.Sources sources = LoopWatch.sources(printed.text());
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of());
        assertThat(watch.check(sources.histories(), sources.sessions()))
                .as("another task's three failures must still reach the loop watch")
                .isPresent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedContainerListingFailsTheLook(boolean records) throws Exception {
        final Printed printed =
                run("if [ \"$1\" = ps ]; then return 125; else return 0; fi", command(records));
        assertThat(printed.status())
                .as("only a failed listing makes the account's tasks unavailable")
                .isNotZero();
    }
}
