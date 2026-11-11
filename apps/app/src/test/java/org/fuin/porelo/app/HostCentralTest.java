package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostCentralTest {
    @TempDir Path directory;

    private HostCentral reader(String content) throws Exception {
        Files.writeString(directory.resolve("alpha.json"), content);
        return new HostCentral(
                directory,
                path -> Map.of("uid", 0, "mode", Files.isDirectory(path) ? 0040755 : 0100640));
    }

    // A schema mismatch must explain why all repository answers were withheld.
    @Test
    void aWaitingFollowStatusNamesTheRefusedField() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","state":"waiting-for-token","why":null,"since":null,
                 "repositories":[{"repository":"app","state":"waiting",
                 "why":"waits for alpha to be on this host's forge"}]}
                """)
                        .centralState("alpha");
        assertThat(answer).containsEntry("state", "cannot-say");
        assertThat(answer.get("why")).asString().contains("host-answer-refused", "read_at");
    }

    // A malformed field must not hide behind a generic refusal that cannot be repaired.
    @Test
    void aRefusedMeasurementNamesItsField() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"ahead","commits":-1}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer.get("app").why()).contains("repositories[0].commits");
    }

    // A host's waiting state must survive the checked file boundary.
    @Test
    void aWaitingPublishedProjectIsReadable() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "state":"waiting-for-token","why":"app: waits for a central token",
                 "repositories":[]}
                """)
                        .centralState("alpha");
        assertThat(answer)
                .containsEntry("state", "waiting-for-token")
                .containsEntry("why", "app: waits for a central token");
    }

    // Refused values must never inject terminal controls or unbounded text into diagnostics.
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "two\nlines", "\u202e", "LONG"})
    void anUnknownProjectStateIsRefusedPrintably(String value) throws Exception {
        final String state = value.equals("LONG") ? "x".repeat(500) : value;
        final var answer =
                reader(
                                "{\"project\":\"alpha\",\"read_at\":\"2026-11-09T10:00:00Z\","
                                        + "\"state\":"
                                        + Json.write(state)
                                        + ",\"repositories\":[]}")
                        .centralState("alpha");
        assertThat(answer).containsEntry("state", "cannot-say");
        assertThat(answer.get("why"))
                .asString()
                .contains("state")
                .hasSizeLessThanOrEqualTo(200)
                .doesNotContain("\n", "\u202e");
    }

    // Upgrading the reader must not erase a valid earlier host's central measurement.
    @Test
    void anOldCentralFileStillReads() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"ahead","commits":3}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer.get("app").state()).isEqualTo("ahead");
        assertThat(answer.get("app").ahead()).isEqualTo(3);
    }

    // A host's forge also keeps the project's own repository, which no person carries, and lists
    // it as one it holds no read key for; that row must not withhold the carried ones' answers.
    @Test
    void aRowForARepositoryNotCarriedIsPassedOver() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"up-to-date"},
                 {"repository":"alpha","state":"cannot-say","why":"no-read-key"}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer).containsOnlyKeys("app");
        assertThat(answer.get("app").state()).isEqualTo("up-to-date");
    }

    // Two carried repositories whose copies share one name cannot be told apart by a row.
    @Test
    void aRowMatchingTwoCarriedRepositoriesIsStillRefused() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"up-to-date"}]}
                """)
                        .ask("alpha", Map.of("app", "app", "App", "APP"));
        assertThat(answer.get("app").why()).contains("ambiguous app");
    }

    // A state measured for a repository no one carries is one no host may give.
    @Test
    void aMeasuredRowForARepositoryNotCarriedIsStillRefused() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"up-to-date"},
                 {"repository":"alpha","state":"up-to-date"}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer.get("app").why()).contains("unmatched or ambiguous alpha");
    }

    // A row passed over says nothing of the carried ones, so with nothing else the host said
    // nothing of them: not that it could not reach central for them.
    @Test
    void anAnswerOfOnlyRowsPassedOverSaysNothingOfTheCarried() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"alpha","state":"cannot-say","why":"no-read-key"}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer.get("app").state()).isEqualTo("cannot-say");
        assertThat(answer.get("app").why()).isNull();
    }

    // Passing over is for a row no carried repository matches; one matching two stays refused.
    @Test
    void anUnsureRowMatchingTwoCarriedRepositoriesIsStillRefused() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"cannot-say","why":"no-read-key"}]}
                """)
                        .ask("alpha", Map.of("app", "app", "App", "APP"));
        assertThat(answer.get("app").why()).contains("ambiguous app");
    }
}
