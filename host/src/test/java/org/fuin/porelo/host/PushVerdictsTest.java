package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.PushJudgementTest.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PushVerdictsTest {
    @TempDir Path directory;

    private PushVerdicts reader() throws IOException {
        return new PushVerdicts(directory, Set.of("refs/heads/main"), Duration.ofSeconds(30));
    }

    private static void line(PushVerdicts reader, Object value) throws IOException {
        reader.write(
                (org.fuin.porelo.wire.Json.write(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void end(PushVerdicts reader, long count) throws IOException {
        line(
                reader,
                HostJson.fields(
                        "accepted",
                        true,
                        "count",
                        count,
                        "checked_at",
                        "2026-10-30T12:00:00Z",
                        "reason",
                        ""));
    }

    @Test
    void aLargePushIsStagedWithoutRetainingItsWholeAnswer() throws Exception {
        try (var reader = reader()) {
            final var row = new LinkedHashMap<>(commit());
            final var signer = HostJson.object(row.get("signer"));
            signer.put("principal", "a".repeat(8192));
            row.put("signer", signer);
            for (int index = 1; index <= 10001; index++) {
                row.put("commit", "%040x".formatted(index));
                line(reader, row);
            }
            end(reader, 10001);
            final var answer = reader.finish(new HostPrograms.Result(0, ""));
            assertThat(answer.status()).isEqualTo(200);
            assertThat(answer.text()).contains("10001 new commit(s)");
            assertThat(answer.entries()).as("verdicts stay on disk").isEmpty();
            assertThat(Files.size(reader.entries())).isGreaterThan(64L * 1024 * 1024);
            try (var rows = Files.lines(reader.entries())) {
                assertThat(rows.count()).isEqualTo(10001);
            }
        }
    }

    @Test
    void duplicateCommitsInDifferentDiskRunsFailClosed() throws Exception {
        try (var reader = reader()) {
            for (int index = 1; index <= 8200; index++) {
                final var row = new LinkedHashMap<>(commit());
                row.put("commit", "%040x".formatted(index));
                line(reader, row);
            }
            final var duplicate = new LinkedHashMap<>(commit());
            duplicate.put("commit", "%040x".formatted(1));
            line(reader, duplicate);
            end(reader, 8201);
            assertThatThrownBy(() -> reader.finish(new HostPrograms.Result(0, "")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Duplicate");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "partial", "wrong-count", "extra", "bad-time", "refused"})
    void anIncompleteOrRefusedStreamNeverProvesAcceptance(String defect) throws Exception {
        try (var reader = reader()) {
            line(reader, commit());
            if (!defect.equals("missing") && !defect.equals("partial")) {
                line(
                        reader,
                        HostJson.fields(
                                "accepted",
                                !defect.equals("refused"),
                                "count",
                                defect.equals("wrong-count") ? 2 : 1,
                                "checked_at",
                                defect.equals("bad-time") ? "yesterday" : "2026-10-30T12:00:00Z",
                                "reason",
                                ""));
            }
            if (defect.equals("partial")) reader.write('{');
            if (defect.equals("extra")) {
                assertThatThrownBy(() -> line(reader, commit())).isInstanceOf(IOException.class);
            } else {
                assertThat(reader.finish(new HostPrograms.Result(0, "")).status()).isEqualTo(500);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-ref", "oversized", "nested", "invalid-utf8"})
    void hostileWorkerOutputIsStoppedWhileItIsRead(String defect) throws Exception {
        try (var reader = reader()) {
            assertThatThrownBy(
                            () -> {
                                if (defect.equals("foreign-ref")) {
                                    final var row = new LinkedHashMap<>(commit());
                                    row.put("ref", "refs/heads/foreign");
                                    line(reader, row);
                                } else if (defect.equals("oversized")) {
                                    reader.write(new byte[128 * 1024 + 1]);
                                } else if (defect.equals("nested")) {
                                    reader.write(
                                            ("[".repeat(100) + "]".repeat(100) + "\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                                } else reader.write(new byte[] {(byte) 0xff, '\n'});
                            })
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void aVerifierDeadlineAlsoCoversLocalOutputProcessing() throws Exception {
        try (var reader = new PushVerdicts(directory, Set.of("refs/heads/main"), Duration.ZERO)) {
            assertThatThrownBy(() -> line(reader, commit()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("timed out");
        }
    }
}
