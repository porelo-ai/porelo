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

    private void record(String kind, String project, String task, String detail) {
        recorded.add(kind + "|" + project + "|" + task + "|" + detail);
    }
}
