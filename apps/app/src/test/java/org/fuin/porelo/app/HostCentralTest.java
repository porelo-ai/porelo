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

    private static String taking() {
        return """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z","repositories":[],
                 "state":"taking","taking_since":"2026-11-09T10:01:00Z",
                 "taking_done":1,"taking_total":3,
                 "taking_pid":123,"taking_pid_start":456,"taking_boot":"12345678-1234-1234-1234-123456789abc"}
                """;
    }

    // Malformed progress must never create a believable copy count or a reusable carrying claim.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "done-above-total,taking_total", "negative-done,taking_done", "zero-total,taking_total",
        "negative-total,taking_total", "missing-done,taking_done", "missing-total,taking_total",
        "fractional-done,taking_done", "oversized-total,taking_total", "missing-since,taking_since",
        "malformed-since,taking_since", "non-utc-since,taking_since", "carried-done,taking_done",
        "carried-total,taking_total", "carried-since,taking_since"
    })
    void malformedTakingProgressNamesTheRejectedField(String damage, String field)
            throws Exception {
        final var value =
                new java.util.LinkedHashMap<>((Map<String, Object>) Json.parseExact(taking()));
        switch (damage) {
            case "done-above-total" -> value.put("taking_done", 4);
            case "negative-done" -> value.put("taking_done", -1);
            case "zero-total" -> value.put("taking_total", 0);
            case "negative-total" -> value.put("taking_total", -1);
            case "missing-done" -> value.remove("taking_done");
            case "missing-total" -> value.remove("taking_total");
            case "fractional-done" -> value.put("taking_done", 1.5);
            case "oversized-total" -> value.put("taking_total", 2147483648L);
            case "missing-since" -> value.remove("taking_since");
            case "malformed-since" -> value.put("taking_since", "not-an-instant");
            case "non-utc-since" -> value.put("taking_since", "2026-11-09T10:01:00+00:00");
            default -> {
                final Object kept = value.get(field);
                value.keySet()
                        .removeAll(
                                java.util.Set.of(
                                        "taking_done",
                                        "taking_total",
                                        "taking_since",
                                        "taking_pid",
                                        "taking_pid_start",
                                        "taking_boot"));
                value.put(field, kept);
                value.put("state", "carried");
            }
        }
        final var answer = reader(Json.write(value)).centralState("alpha");
        assertThat(answer)
                .as("invalid progress withholds carrying")
                .containsEntry("state", "cannot-say");
        assertThat(answer.get("why"))
                .asString()
                .as("a person can repair the rejected field")
                .contains(field);
    }

    // A dead follow must not leave a member believing copies are still being made.
    @Test
    void takingWithoutALiveFollowIsCannotSay() throws Exception {
        final var central =
                reader(
                        taking(),
                        new org.fuin.porelo.organization.gittree.LinuxProcesses(
                                path -> {
                                    throw new java.io.IOException("no such process");
                                }));
        assertThat(central.centralState("alpha"))
                .as("unconfirmed progress withholds carrying")
                .containsEntry("state", "cannot-say");
        assertThat(central.status("alpha").why())
                .as("stale progress explains the missing live follow")
                .contains("follow");
    }

    private static java.util.Map<Path, String> proc() {
        return new java.util.HashMap<>(
                Map.of(
                        Path.of("/proc/sys/kernel/random/boot_id"),
                        "12345678-1234-1234-1234-123456789abc\n",
                        Path.of("/proc/123/stat"),
                        stat("S", "456")));
    }

    private static String stat(String state, String start) {
        return "123 (follow (worker) name)) " + state + " " + "0 ".repeat(18) + start + " 0\n";
    }

    private static org.fuin.porelo.organization.gittree.LinuxProcesses processes(
            Map<Path, String> files) {
        return new org.fuin.porelo.organization.gittree.LinuxProcesses(
                path -> {
                    final String value = files.get(path);
                    if (value == null) throw new java.nio.file.NoSuchFileException(path.toString());
                    return value;
                });
    }

    // Every member entry point must reject PID reuse and carry live progress whole.
    @Test
    void liveProgressCarriesItsCountsAndExpiresWithTheFollowProcess() throws Exception {
        final var files = proc();
        final var central = reader(taking(), processes(files));
        final var status = central.status("alpha");
        assertThat(status.state()).as("a matching live process is taking").isEqualTo("taking");
        assertThat(status.takingDone()).isEqualTo(1);
        assertThat(status.takingTotal()).isEqualTo(3);
        assertThat(status.takingSince()).isEqualTo("2026-11-09T10:01:00Z");
        assertThat(central.centralState("alpha"))
                .containsEntry("taking_done", 1)
                .containsEntry("taking_total", 3)
                .doesNotContainKeys("taking_pid", "taking_pid_start", "taking_boot");
        files.put(Path.of("/proc/123/stat"), stat("S", "457"));
        assertThat(central.status("alpha").state())
                .as("PID reuse invalidates taking")
                .isEqualTo("cannot-say");
        assertThat(central.centralState("alpha"))
                .doesNotContainKeys("taking_done", "taking_total", "taking_since");
        assertThat(central.ask("alpha", Map.of("code", "code")).get("code").why())
                .contains("follow");
    }

    // Hidden, dead or different processes must not authenticate an abandoned follow.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "start",
                "boot",
                "missing",
                "unreadable",
                "Z",
                "X",
                "x",
                "malformed-stat",
                "malformed-boot"
            })
    void anUnconfirmedProcessWithholdsTaking(String damage) throws Exception {
        final var files = proc();
        switch (damage) {
            case "start" -> files.put(Path.of("/proc/123/stat"), stat("S", "457"));
            case "boot" ->
                    files.put(
                            Path.of("/proc/sys/kernel/random/boot_id"),
                            "aaaaaaaa-1234-1234-1234-123456789abc");
            case "missing" -> files.remove(Path.of("/proc/123/stat"));
            case "malformed-stat" -> files.put(Path.of("/proc/123/stat"), "123 incomplete");
            case "malformed-boot" ->
                    files.put(Path.of("/proc/sys/kernel/random/boot_id"), "unknown");
            default -> files.put(Path.of("/proc/123/stat"), stat(damage, "456"));
        }
        final var processReader =
                damage.equals("unreadable")
                        ? new org.fuin.porelo.organization.gittree.LinuxProcesses(
                                path -> {
                                    throw new java.nio.file.AccessDeniedException(path.toString());
                                })
                        : processes(files);
        final var central = reader(taking(), processReader);
        assertThat(central.centralState("alpha"))
                .containsEntry("state", "cannot-say")
                .doesNotContainKeys("taking_done", "taking_total", "taking_since");
        assertThat(central.status("alpha").why())
                .as("the reason identifies the failed follow check")
                .contains("follow");
        assertThat(central.ask("alpha", Map.of("code", "code")).get("code").state())
                .isEqualTo("cannot-say");
    }

    // The identity schema must not accept invented process identities or leak them into other
    // states.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "missing-pid,taking_pid",
        "zero-pid,taking_pid",
        "negative-pid,taking_pid",
        "large-pid,taking_pid",
        "fractional-pid,taking_pid",
        "text-pid,taking_pid",
        "missing-start,taking_pid_start",
        "negative-start,taking_pid_start",
        "fractional-start,taking_pid_start",
        "overflow-start,taking_pid_start",
        "text-start,taking_pid_start",
        "missing-boot,taking_boot",
        "uppercase-boot,taking_boot",
        "malformed-boot,taking_boot",
        "null-boot,taking_boot",
        "carried-pid,taking_pid",
        "carried-start,taking_pid_start",
        "carried-boot,taking_boot"
    })
    void malformedProcessIdentityNamesTheRejectedField(String damage, String field)
            throws Exception {
        final var value =
                new java.util.LinkedHashMap<>((Map<String, Object>) Json.parseExact(taking()));
        if (damage.startsWith("missing")) value.remove(field);
        else
            switch (damage) {
                case "zero-pid" -> value.put(field, 0);
                case "negative-pid", "negative-start" -> value.put(field, -1);
                case "large-pid" -> value.put(field, 4194305);
                case "fractional-pid", "fractional-start" -> value.put(field, 1.5);
                case "overflow-start" ->
                        value.put(field, new java.math.BigInteger("9223372036854775808"));
                case "text-pid", "text-start" -> value.put(field, "123");
                case "uppercase-boot" -> value.put(field, "12345678-1234-1234-1234-123456789ABC");
                case "malformed-boot" -> value.put(field, "1234");
                case "null-boot" -> value.put(field, null);
                default -> {
                    final Object kept = value.get(field);
                    value.keySet()
                            .removeAll(
                                    java.util.Set.of(
                                            "taking_done",
                                            "taking_total",
                                            "taking_since",
                                            "taking_pid",
                                            "taking_pid_start",
                                            "taking_boot"));
                    value.put(field, kept);
                    value.put("state", "carried");
                }
            }
        final var answer = reader(Json.write(value)).centralState("alpha");
        assertThat(answer).containsEntry("state", "cannot-say");
        assertThat(answer.get("why")).asString().contains(HostCentral.REFUSED, field);
    }

    // A live host copy must be represented without asking the person for its token again.
    @Test
    void takingProgressSurvivesTheCheckedSchema() {
        final var answer =
                HostCentral.readStandalone(
                        """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z","repositories":[],
                 "state":"taking","taking_since":"2026-11-09T10:01:00Z",
                 "taking_done":1,"taking_total":3,
                 "taking_pid":123,"taking_pid_start":456,"taking_boot":"12345678-1234-1234-1234-123456789abc"}
                """,
                        "alpha");
        assertThat(answer)
                .as("valid taking progress is part of the member contract")
                .isNotNull()
                .containsEntry("state", "taking")
                .containsEntry("taking_done", 1)
                .containsEntry("taking_total", 3)
                .containsEntry("taking_since", "2026-11-09T10:01:00Z");
    }

    // A host may announce taking before it can count the signed repositories.
    @Test
    void takingMayStillBeCounting() {
        assertThat(
                        HostCentral.readStandalone(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z","repositories":[],
                 "state":"taking","taking_since":"2026-11-09T10:01:00Z",
                 "taking_pid":123,"taking_pid_start":456,"taking_boot":"12345678-1234-1234-1234-123456789abc"}
                """,
                                "alpha"))
                .as("counting has no invented total")
                .isNotNull()
                .doesNotContainKeys("taking_done", "taking_total");
    }

    private HostCentral reader(String content) throws Exception {
        return reader(content, processes(proc()));
    }

    private HostCentral reader(
            String content, org.fuin.porelo.organization.gittree.LinuxProcesses processes)
            throws Exception {
        Files.writeString(directory.resolve("alpha.json"), content);
        return new HostCentral(
                directory,
                path -> Map.of("uid", 0, "mode", Files.isDirectory(path) ? 0040755 : 0100640),
                processes);
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

    // A repository of the Linux kernel's size holds over 1.3 million commits a copy can be ahead.
    @Test
    void aCopyAheadByMoreThanAMillionCommitsIsTaken() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"ahead","commits":1300000}]}
                """)
                        .ask("alpha", Map.of("app", "app"));
        assertThat(answer.get("app").state())
                .as("a count a real repository reaches")
                .isEqualTo("ahead");
        assertThat(answer.get("app").ahead()).isEqualTo(1_300_000);
    }

    // A count past what a forge's copy can hold is no answer.
    @Test
    void aCountPastAnIntIsRefusedNamingItsField() throws Exception {
        final var answer =
                reader(
                                """
                {"project":"alpha","read_at":"2026-11-09T10:00:00Z",
                 "repositories":[{"repository":"app","state":"ahead","commits":2147483648}]}
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
