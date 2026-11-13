package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.gate.GitHttpServer;
import org.junit.jupiter.api.Test;

/** Tests for {@link ArrivalRecords}. */
class ArrivalRecordsTest {

    private final List<String> recorded = new ArrayList<>();

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void everyArrivalIsRecordedWithItsVerdictAndTheVerdictStands() throws Exception {
        final GitHttpServer.Arrivals accepting =
                ArrivalRecords.recording(
                        (old, commit) -> null, "demo", "porelo-demo-fix", this::record, clock);
        final GitHttpServer.Arrivals refusing =
                ArrivalRecords.recording(
                        (old, commit) -> "commit " + commit + " is not signed",
                        "demo",
                        "porelo-demo-fix",
                        this::record,
                        clock);

        assertThat(accepting.judge("a1", "b2")).isNull();
        assertThat(refusing.judge("b2", "c3")).isEqualTo("commit c3 is not signed");

        assertThat(recorded)
                .containsExactly(
                        "arrived|demo|porelo-demo-fix|a1..b2 arrived 2026-10-31T03:00:00Z: accepted",
                        "arrived|demo|porelo-demo-fix|b2..c3 arrived 2026-10-31T03:00:00Z: refused,"
                                + " commit c3 is not signed");
    }

    @Test
    void aPushOfATaskWithoutACertificateIsAcceptedAndRecordedAsUnsigned() throws Exception {
        // A bare 'accepted' would read as judged by a certificate that does not exist.
        final GitHttpServer.Arrivals unjudged =
                ArrivalRecords.unjudged("demo", "porelo-demo-fix", this::record, clock);

        assertThat(unjudged.judge("a1", "b2")).as("accepted, as before").isNull();

        assertThat(recorded)
                .containsExactly(
                        "arrived|demo|porelo-demo-fix|a1..b2 arrived 2026-10-31T03:00:00Z:"
                                + " accepted, unsigned");
    }

    @Test
    void theVerdictOfAWaitingCommitIsReadBackForItsReview() {
        // Read back so a person sees an unsigned push as one before approving it.
        final java.util.List<java.util.Map<String, Object>> lines =
                java.util.List.of(
                        line("arrived", "demo", "fix", "0..b2 arrived t: accepted, unsigned"),
                        line("arrived", "demo", "fix", "b2..c3 arrived t: accepted"),
                        line("arrived", "other", "fix", "c3..d4 arrived t: accepted"),
                        line("approved", "demo", "fix", "c3..d4 arrived t: accepted"));

        assertThat(ArrivalVerdicts.of(lines, "demo", "fix", "b2"))
                .isEqualTo(ArrivalRecords.ACCEPTED_UNSIGNED);
        assertThat(ArrivalVerdicts.of(lines, "demo", "fix", "c3")).isEqualTo("accepted");
        assertThat(ArrivalVerdicts.of(lines, "demo", "fix", "d4"))
                .as("only an arrival this project's gate recorded counts")
                .isNull();
    }

    private static java.util.Map<String, Object> line(
            String kind, String project, String task, String detail) {
        return java.util.Map.of("kind", kind, "project", project, "task", task, "detail", detail);
    }

    private void record(String kind, String project, String task, String detail) {
        recorded.add(kind + "|" + project + "|" + task + "|" + detail);
    }
}
