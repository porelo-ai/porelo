package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the record summary where the file stays inside the container. */
class ToolRecordsTest {

    @ParameterizedTest
    @ValueSource(strings = {"refused", "-refused", "a'b; $(printf injected)", "literal.*[failure]"})
    void onlyThePathAndLiteralMarkerCountLeaveTheContainer(String marker, @TempDir Path home)
            throws Exception {
        final Path directory = Files.createDirectories(home.resolve(".sessions/nested"));
        final Path file =
                Files.writeString(
                        directory.resolve("run.jsonl"),
                        "private record text\n".repeat(1000)
                                + LoopWatch.SEPARATOR
                                + marker
                                + "\n"
                                + LoopWatch.SESSION_SEPARATOR
                                + marker
                                + "\n"
                                + marker
                                + " "
                                + marker);
        final ProcessBuilder builder =
                new ProcessBuilder(
                        "sh", "-c", new ToolRecords(".sessions", ".jsonl").command(marker));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("LC_ALL", "C");
        final Process process = builder.redirectErrorStream(true).start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor())
                .as("counting a safely quoted literal marker must succeed")
                .isZero();
        assertThat(printed)
                .as(
                        "record text and separator-looking lines must stay in the container; each literal occurrence counts")
                .isEqualTo(LoopWatch.SESSION_SEPARATOR + file.toString() + "\t4\n");
    }
}
