package org.fuin.porelo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A kind is missing only where a request's format reports it and the reply left it out, so a task
 * whose every request answered in full reads as exact, whatever its agent's format.
 */
class UsageSumsTest {

    private static final String MESSAGES =
            "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"messages\",\"input\":10,"
                    + "\"cache_read\":2,\"cache_write\":3,\"output\":4,\"reasoning\":null,"
                    + "\"end\":\"complete\"}\n";
    private static final String RESPONSES =
            "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"responses\",\"input\":10,"
                    + "\"cache_read\":2,\"cache_write\":null,\"output\":4,\"reasoning\":1,"
                    + "\"end\":\"complete\"}\n";

    private static UsageSums.Totals totals(Path dir, String record) throws Exception {
        final Path file = dir.resolve("usage.jsonl");
        Files.writeString(file, record);
        return UsageSums.read(file, ZoneId.of("UTC")).totals();
    }

    @Test
    void completeRequestsMissNoKindTheirFormatDoesNotReport(@TempDir Path dir) throws Exception {
        final var totals = totals(dir, MESSAGES.repeat(3) + RESPONSES.repeat(3));

        assertThat(totals.fields())
                .as("input, cache read, cache write, output, reasoning")
                .containsExactly(
                        new UsageSums.Count(60L, 0),
                        new UsageSums.Count(12L, 0),
                        new UsageSums.Count(9L, 0),
                        new UsageSums.Count(24L, 0),
                        new UsageSums.Count(3L, 0));
    }

    @Test
    void aKindNoRequestReportedStaysUnknownWithoutBeingMissing(@TempDir Path dir) throws Exception {
        final var totals = totals(dir, MESSAGES.repeat(3));

        assertThat(totals.fields().get(4))
                .as("reasoning of messages alone")
                .isEqualTo(new UsageSums.Count(null, 0));
    }

    @Test
    void aCutRequestStillMissesAKindItsFormatReports(@TempDir Path dir) throws Exception {
        final String cut =
                "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"messages\",\"input\":7,"
                        + "\"cache_read\":null,\"cache_write\":null,\"output\":null,"
                        + "\"reasoning\":null,\"end\":\"aborted\"}\n";

        final var totals = totals(dir, MESSAGES + cut);

        assertThat(totals.fields())
                .containsExactly(
                        new UsageSums.Count(17L, 0),
                        new UsageSums.Count(2L, 1),
                        new UsageSums.Count(3L, 1),
                        new UsageSums.Count(4L, 1),
                        new UsageSums.Count(null, 0));
    }

    @Test
    void aFormatPoreloDoesNotKnowMissesEveryKindItLeftOut(@TempDir Path dir) throws Exception {
        final String unknown =
                "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"other\",\"input\":5}\n"
                        + "{\"time\":\"2026-01-01T10:00:00Z\",\"input\":5}\n";

        final var totals = totals(dir, unknown);

        assertThat(totals.fields())
                .containsExactly(
                        new UsageSums.Count(10L, 0),
                        new UsageSums.Count(null, 2),
                        new UsageSums.Count(null, 2),
                        new UsageSums.Count(null, 2),
                        new UsageSums.Count(null, 2));
    }

    @Test
    void geminiAndChatCompletionsReportNoCacheWrite(@TempDir Path dir) throws Exception {
        final String lines =
                "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"gemini\",\"input\":1,"
                        + "\"cache_read\":0,\"output\":2,\"reasoning\":1}\n"
                        + "{\"time\":\"2026-01-01T10:00:00Z\",\"format\":\"chat_completions\","
                        + "\"input\":1,\"cache_read\":0,\"output\":2,\"reasoning\":0}\n";

        final var totals = totals(dir, lines);

        assertThat(totals.fields().get(2))
                .as("cache write")
                .isEqualTo(new UsageSums.Count(null, 0));
        assertThat(totals.fields().stream().mapToLong(UsageSums.Count::missing).sum())
                .as("missing over every kind")
                .isZero();
        assertThat(totals.requests()).as("requests").isEqualTo(2);
    }
}
