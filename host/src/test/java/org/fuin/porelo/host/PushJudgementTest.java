package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards task-account authorization and refuses unverifiable worker answers without any IO. */
class PushJudgementTest {
    static final String OLD = "a".repeat(40);
    static final String NEXT = "b".repeat(40);
    static final String ZERO = "0".repeat(40);

    static Map<String, Object> commit() {
        return Map.of(
                "commit",
                NEXT,
                "ref",
                "refs/heads/main",
                "verdict",
                "accepted",
                "signer",
                fields(
                        "kind",
                        "person",
                        "fingerprint",
                        "SHA256:" + "A".repeat(43),
                        "principal",
                        "person:workshop:alice",
                        "certificate",
                        null,
                        "signed_by",
                        null));
    }

    static HostPrograms.Result accepted() {
        return result(
                Map.of(
                        "accepted",
                        true,
                        "checked_at",
                        "2026-10-30T12:00:00Z",
                        "commits",
                        List.of(commit())));
    }

    static HostPrograms.Result result(Object value) {
        return new HostPrograms.Result(0, write(value));
    }

    static PushJudgement.Answer verified(HostPrograms.Result result) {
        return PushJudgement.verified(result, Set.of("refs/heads/main"));
    }

    static HostPrograms.Result accepted(String ref) {
        final var row = new java.util.LinkedHashMap<>(commit());
        row.put("ref", ref);
        return result(
                Map.of(
                        "accepted",
                        true,
                        "checked_at",
                        "2026-10-30T12:00:00Z",
                        "commits",
                        List.of(row)));
    }

    // Prevent a purged person's task token from becoming an unrestricted forge identity.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "porelo-unknown",
                "porelo-publisher",
                "porelo-publisher-",
                "porelo-admin-other"
            })
    void aTaskAccountWithoutItsPersonCannotReachVerificationEvenForADeletion(String pusher)
            throws Exception {
        final var answer =
                PushJudgement.judge(
                        PushRequest.updates(line(OLD, ZERO, "refs/heads/main")),
                        pusher,
                        login -> false,
                        login -> Set.of(),
                        updates -> accepted());
        assertThat(answer.status())
                .as("a purged task identity must not inherit person ref rules")
                .isEqualTo(403);
        assertThat(answer.entries()).as("a purged person's push records nothing").isEmpty();
    }

    // Prevent forged provenance and unbounded worker data from reaching the root ledger.
    static Stream<Arguments> untrustedProvenance() {
        final var cases = new java.util.ArrayList<Arguments>();
        for (Object ref : java.util.Arrays.asList(null, 17, "refs/heads/foreign")) {
            final var row = new java.util.LinkedHashMap<>(commit());
            row.put("ref", ref);
            cases.add(Arguments.of("foreign or invalid ref " + ref, List.of(row)));
        }
        final var signer = HostJson.object(commit().get("signer"));
        for (String key : signer.keySet()) {
            final var missing = new java.util.LinkedHashMap<>(signer);
            missing.remove(key);
            final var row = new java.util.LinkedHashMap<>(commit());
            row.put("signer", missing);
            cases.add(Arguments.of("missing signer " + key, List.of(row)));
            for (Object value :
                    java.util.Arrays.asList(
                            17,
                            List.of("nested"),
                            Map.of("nested", true),
                            "x".repeat(16385),
                            null)) {
                if (value == null && !Set.of("kind", "fingerprint").contains(key)) continue;
                final var changed = new java.util.LinkedHashMap<>(signer);
                changed.put(key, value);
                final var changedRow = new java.util.LinkedHashMap<>(commit());
                changedRow.put("signer", changed);
                cases.add(
                        Arguments.of(
                                "invalid signer "
                                        + key
                                        + " "
                                        + (value == null
                                                ? "null"
                                                : value.getClass().getSimpleName()),
                                List.of(changedRow)));
            }
        }
        for (var change :
                List.of(
                        Map.of("kind", "alien"),
                        Map.of("certificate", "invented"),
                        Map.of("signed_by", "not a fingerprint"))) {
            final var bad = new java.util.LinkedHashMap<>(signer);
            bad.putAll(change);
            final var changed = new java.util.LinkedHashMap<>(commit());
            changed.put("signer", bad);
            cases.add(Arguments.of("invalid signer value " + change, List.of(changed)));
        }
        final var extra = new java.util.LinkedHashMap<>(signer);
        extra.put("injected", Map.of("nested", true));
        final var row = new java.util.LinkedHashMap<>(commit());
        row.put("signer", extra);
        cases.add(Arguments.of("extra signer key", List.of(row)));
        cases.add(Arguments.of("duplicate commit", List.of(commit(), commit())));
        final var many = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i <= 10000; i++) {
            final var next = new java.util.LinkedHashMap<>(commit());
            next.put("commit", String.format("%040x", i));
            many.add(next);
        }
        cases.add(Arguments.of("more than 10000 commits", many));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untrustedProvenance")
    void workerProvenanceCannotInventRefsFieldsOrAdditionalCommits(String defect, List<?> rows)
            throws Exception {
        final var answer =
                PushJudgement.judge(
                        PushRequest.updates(line(OLD, NEXT, "refs/heads/main")),
                        "alice",
                        login -> true,
                        login -> Set.of(),
                        updates ->
                                result(
                                        Map.of(
                                                "accepted",
                                                true,
                                                "checked_at",
                                                "2026-10-30T12:00:00Z",
                                                "commits",
                                                rows)));
        assertThat(answer.status())
                .as("untrusted worker provenance must refuse: " + defect)
                .isEqualTo(500);
        assertThat(answer.entries())
                .as("a bad worker answer records none of its commits: " + defect)
                .isEmpty();
    }

    @Test
    void aVerifierDiagnosticHasOnlyOnePoreloPrefix() {
        assertThat(
                        verified(new HostPrograms.Result(65, "", "porelo: the push is refused\n"))
                                .text())
                .as("the hook receives the verifier's diagnostic once")
                .isEqualTo("porelo: the push is refused\n");
    }

    private PushJudgement.Answer judge(String pusher, String lines) throws Exception {
        return PushJudgement.judge(
                PushRequest.updates(lines),
                pusher,
                Set.of("alice", "bob")::contains,
                login -> login.equals("alice") ? Set.of("fix-1", "fix-2") : Set.of("bobs-task"),
                updates -> accepted(updates.getLast().ref()));
    }

    static String line(String old, String next, String ref) {
        return old + " " + next + " " + ref + "\n";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "refs/heads/porelo/missing",
                "refs/heads/porelo/bobs-task",
                "refs/heads/main",
                "refs/heads/feature",
                "refs/heads/porelo/fix-1/more",
                "refs/tags/v1"
            })
    void aTaskAccountCannotWriteAnyRefOutsideItsPersonsRecordedTasks(String ref) throws Exception {
        final var answer =
                PushJudgement.judge(
                        PushRequest.updates(
                                line(OLD, NEXT, "refs/heads/porelo/fix-1") + line(OLD, NEXT, ref)),
                        "porelo-alice",
                        login -> true,
                        login -> Set.of("fix-1"),
                        updates -> {
                            throw new AssertionError(
                                    "A foreign task ref must never reach the verifier");
                        });
        assertThat(answer.status())
                .as("a task token must not bypass task ownership")
                .isEqualTo(403);
        assertThat(answer.text())
                .as("the hook must identify the forbidden ref")
                .isEqualTo(
                        "porelo: porelo-alice pushes only porelo/<task> of alice's own tasks, and "
                                + ref
                                + " is none\n");
        assertThat(answer.entries()).as("a refused push creates no arrivals").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "", "porelo-publisher-alpha", "porelo-admin"})
    void personForgeAndNonPersonAccountsKeepTheForgesOwnRefRules(String pusher) throws Exception {
        assertThat(judge(pusher, line(OLD, NEXT, "refs/tags/v1")).status())
                .as("only a recorded person's task account is held to task branches")
                .isEqualTo(200);
    }

    @Test
    void allOfOnePersonsTaskBranchesCanBeUpdatedTogetherIncludingAForcePush() throws Exception {
        final var updates =
                PushRequest.updates(
                        line(OLD, NEXT, "refs/heads/porelo/fix-1")
                                + line(ZERO, NEXT, "refs/heads/porelo/fix-2"));
        final var answer =
                PushJudgement.judge(
                        updates,
                        "porelo-alice",
                        login -> true,
                        login -> Set.of("fix-1", "fix-2"),
                        given -> {
                            assertThat(given)
                                    .as("old SHA and zero old SHA must reach the library unchanged")
                                    .isEqualTo(updates);
                            return accepted(updates.getFirst().ref());
                        });
        assertThat(answer.status())
                .as("a task can amend or create its own branches")
                .isEqualTo(200);
    }

    @Test
    void aDeletionCannotEraseAnotherPersonsTask() throws Exception {
        assertThat(judge("porelo-alice", line(OLD, ZERO, "refs/heads/porelo/bobs-task")).text())
                .as("deletions still obey task ownership")
                .contains("bobs-task is none");
    }

    @Test
    void anOwnDeletionNeedsNeitherAQuarantineNorAVerifier() throws Exception {
        final var answer =
                PushJudgement.judge(
                        PushRequest.updates(line(OLD, ZERO, "refs/heads/porelo/fix-1")),
                        "porelo-alice",
                        login -> true,
                        login -> Set.of("fix-1"),
                        updates -> {
                            throw new AssertionError("A deletion has no objects to verify");
                        });
        assertThat(answer)
                .as("only-deletion pushes pass unrecorded")
                .isEqualTo(
                        new PushJudgement.Answer(200, "porelo: the push brings no new commit\n"));
    }

    @Test
    void aDeletionBesideAnUpdateDoesNotBypassSignatureVerification() throws Exception {
        final var answer =
                PushJudgement.judge(
                        PushRequest.updates(
                                line(OLD, ZERO, "refs/heads/old")
                                        + line(ZERO, NEXT, "refs/heads/new")),
                        "alice",
                        login -> true,
                        login -> Set.of(),
                        updates ->
                                new HostPrograms.Result(65, "", NEXT + " carries no signature\n"));
        assertThat(answer)
                .as("an unsigned commit must be refused even beside a deletion")
                .isEqualTo(
                        new PushJudgement.Answer(
                                403, "porelo: " + NEXT + " carries no signature\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a\n", "a/b", "a@b"})
    void anInvalidPusherCannotReachAnyTaskOrSignatureLookup(String pusher) {
        assertThatThrownBy(() -> judge(pusher, line(OLD, NEXT, "refs/heads/main")))
                .as("untrusted identities cannot reach the host records")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("the hook names no valid pusher");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "carries no signature",
                "its signature does not verify by the key in force SHA256:abc",
                "signed by SHA256:abc, which is no signer in force and no task key with a recorded certificate"
            })
    void everySignatureRefusalKeepsTheLibrarysReasonAndHasNoArrival(String reason) {
        final var answer = verified(new HostPrograms.Result(65, "", NEXT + " " + reason + "\n"));
        assertThat(answer)
                .as("the hook must fail closed with the signature checker's reason")
                .isEqualTo(new PushJudgement.Answer(403, "porelo: " + NEXT + " " + reason + "\n"));
    }

    @Test
    void refusedWorkerDiagnosticsArePrintableBoundedAndNeverTakenFromStdout() {
        final String safe = "a".repeat(512);
        final var answer =
                verified(
                        new HostPrograms.Result(
                                65,
                                "untrusted stdout",
                                "\u001b[31mcolor\n"
                                        + "b".repeat(513)
                                        + "\n"
                                        + (safe + "\n").repeat(101)));
        assertThat(answer.text())
                .as("terminal controls and oversized diagnostics cannot reach the hook")
                .isEqualTo(("porelo: " + safe + "\n").repeat(100));
    }

    @Test
    void aRefusalWithNoSafeDiagnosticsStillRefuses() {
        assertThat(verified(new HostPrograms.Result(65, "", "\u001b\n")).text())
                .as("discarding every diagnostic must not discard the refusal")
                .isEqualTo("porelo: the push is refused\n");
    }

    static Stream<Arguments> malformedAnswers() {
        final Map<String, Object> base =
                Map.of(
                        "accepted",
                        true,
                        "checked_at",
                        "2026-10-30T12:00:00Z",
                        "commits",
                        List.of(commit()));
        final List<Arguments> cases = new java.util.ArrayList<>();
        cases.add(Arguments.of("worker crash", new HostPrograms.Result(1, write(base))));
        cases.add(Arguments.of("not JSON", new HostPrograms.Result(0, "not json")));
        cases.add(Arguments.of("not object", result(List.of())));
        for (String key : base.keySet()) {
            final var missing = new java.util.HashMap<>(base);
            missing.remove(key);
            cases.add(Arguments.of("missing " + key, result(missing)));
        }
        for (Object time :
                List.of(
                        "yesterdayZ",
                        "2026-02-30T12:00:00Z",
                        "0000-01-01T12:00:00Z",
                        "+10000-01-01T12:00:00Z",
                        "2026-10-30T12:00:00",
                        17)) {
            final var changed = new java.util.HashMap<String, Object>(base);
            changed.put("checked_at", time);
            cases.add(Arguments.of("bad time " + time, result(changed)));
        }
        for (var change :
                List.of(
                        Map.of("accepted", false),
                        Map.of("accepted", "true"),
                        Map.of("commits", "none"))) {
            final var changed = new java.util.HashMap<String, Object>(base);
            changed.putAll(change);
            cases.add(Arguments.of("bad envelope " + change, result(changed)));
        }
        for (Object item :
                List.of(
                        "not object",
                        fields("verdict", "refused"),
                        fields(
                                "verdict",
                                "accepted",
                                "commit",
                                "not hex",
                                "signer",
                                Map.of("fingerprint", "SHA256:" + "A".repeat(43))),
                        fields(
                                "verdict",
                                "accepted",
                                "commit",
                                NEXT,
                                "signer",
                                Map.of("fingerprint", "nope")),
                        fields("verdict", "accepted", "commit", NEXT, "signer", null))) {
            final var changed = new java.util.HashMap<String, Object>(base);
            changed.put("commits", List.of(commit(), item));
            cases.add(Arguments.of("bad commit " + item, result(changed)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedAnswers")
    void aWorkerAnswerThatCannotProveAcceptanceNeverRecordsEvenItsGoodCommits(
            String defect, HostPrograms.Result result) {
        assertThat(verified(result))
                .as("an incomplete signature verdict must refuse: " + defect)
                .isEqualTo(
                        new PushJudgement.Answer(
                                500, "porelo: the host could not judge this push\n"));
    }

    @Test
    void acceptedCommitsKeepTheSignerAndArrivalWithoutTheWorkersReasonField() {
        final var answer = verified(accepted());
        assertThat(answer.entries())
                .as("arrival provenance must persist the verifier's signer")
                .containsExactly(
                        fields(
                                "commit",
                                NEXT,
                                "ref",
                                "refs/heads/main",
                                "verdict",
                                "accepted",
                                "arrived",
                                "2026-10-30T12:00:00Z",
                                "signer",
                                commit().get("signer")));
        assertThat(answer.text())
                .as("the hook's success count must match the ledger")
                .isEqualTo("porelo: 1 new commit(s) judged and recorded\n");
    }

    @Test
    void aPushWithNoNewCommitHasAnEmptyLedger() {
        final var answer =
                verified(
                        result(
                                Map.of(
                                        "accepted",
                                        true,
                                        "checked_at",
                                        "2026-10-30T12:00:00Z",
                                        "commits",
                                        List.of())));
        assertThat(answer)
                .as("a force push to already-known commits is still accepted")
                .isEqualTo(
                        new PushJudgement.Answer(
                                200, "porelo: 0 new commit(s) judged and recorded\n"));
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 65, 69, 73})
    void callerRefusalsAndHostFailuresKeepTheirDifferentHttpStatuses(int code) {
        final var answer = PushJudgement.refusal(new HostRefusal(code, "reason"));
        assertThat(answer)
                .as("a host failure must not look like a judged signature refusal")
                .isEqualTo(new PushJudgement.Answer(code < 69 ? 403 : 500, "porelo: reason\n"));
    }
}
