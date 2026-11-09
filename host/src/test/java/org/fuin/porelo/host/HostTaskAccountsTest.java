package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HostTaskAccountsTest {
    @TempDir Path directory;
    private HostTaskFixture host;

    @BeforeEach
    void prepare() throws Exception {
        host = new HostTaskFixture(directory);
    }

    private HostReadFixture.Answer start(String certificate) {
        return host.run(certificate, "task", "start", "t1");
    }

    private String cert(
            String signer,
            String key,
            List<String> principals,
            String valid,
            boolean type,
            String id)
            throws Exception {
        return host.certificate(signer, key, principals, valid, type, id);
    }

    // py: test_a_certificate_signed_by_the_persons_key_is_recorded_and_its_token_answered_once
    @Test
    void aCurrentCertificateIsRecordedPrivatelyAndItsGateTokenAnsweredAlone() throws Exception {
        final String certificate = host.certificate();
        final var answer = start(certificate);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).matches("[0-9a-f]{40}");
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-t1");
        final var token = host.tokens.get("porelo-alice").values().iterator().next();
        assertThat(token.secret()).isEqualTo(answer.out());
        assertThat(token.scopes()).isEqualTo("write:repository,read:package");
        final Path record = host.record("t1");
        assertThat((int) Files.getAttribute(record, "unix:mode") & 0777).isEqualTo(0600);
        assertThat((int) Files.getAttribute(record.getParent(), "unix:mode") & 0777)
                .isEqualTo(0700);
        assertThat((int) Files.getAttribute(record.getParent().getParent(), "unix:mode") & 0777)
                .isEqualTo(0700);
        assertThat(Files.readString(record))
                .contains(
                        "\"valid_after\":\"2026-10-30T00:00:00Z\"",
                        "\"valid_before\":\"2026-10-31T00:00:00Z\"",
                        "task:alpha:t1")
                .contains(certificate.strip().split(" ")[1]);
        assertThat(HostJson.object(HostJson.parse(Files.readString(record))))
                .containsEntry("fingerprint", host.fingerprint("task"))
                .containsEntry("signed_by", host.fingerprint("alice"))
                .containsEntry("key_id", "porelo task t1 by alice")
                .containsEntry("principals", List.of("task:alpha:t1"));
        host.noSecrets(answer);
    }

    // py: test_a_task_names_exactly_one_principal_by_its_project
    @ParameterizedTest
    @CsvSource({
        "task:default:t1,0",
        "task:alpha:t1,0",
        "task:beta:t1,65",
        "task:host-admin:t1,65",
        "task:t1,65",
        "task:alpha:t1;task:beta:t1,65",
        "task:Alpha:t1,65",
        "task::t1,65",
        "task:alpha:t2,65",
        "task:alpha:t1;alice,65"
    })
    void onlyOnePrincipalOfThisTaskAndAnAdmittedProjectPasses(String principals, int code)
            throws Exception {
        final var answer =
                start(
                        cert(
                                "alice",
                                "task",
                                List.of(principals.split(";")),
                                "20261030000000Z:20261031000000Z",
                                false,
                                "porelo task t1 by alice"));
        assertThat(answer.code()).as(answer.err()).isEqualTo(code);
        assertThat(Files.exists(host.record("t1"))).isEqualTo(code == 0);
        assertThat(host.calls.stream().anyMatch(call -> call.getFirst().equals("systemd-run")))
                .isEqualTo(code == 0);
    }

    // py:
    // test_a_certificate_the_persons_current_key_did_not_sign_for_this_task_now_is_refused_before_anything_is_written
    @ParameterizedTest
    @ValueSource(
            strings = {
                "signer",
                "task",
                "expired",
                "future",
                "forever",
                "host",
                "signature",
                "revoked",
                "absent",
                "plain",
                "long",
                "two",
                "empty"
            })
    void certificatesThatCannotAuthorizeThisTaskProduceNoRecordOrToken(String defect)
            throws Exception {
        String certificate =
                switch (defect) {
                    case "signer" ->
                            cert(
                                    "bob",
                                    "task",
                                    List.of("task:alpha:t1"),
                                    "20261030000000Z:20261031000000Z",
                                    false,
                                    "porelo task t1 by bob");
                    case "task" ->
                            cert(
                                    "alice",
                                    "task",
                                    List.of("task:alpha:t2"),
                                    "20261030000000Z:20261031000000Z",
                                    false,
                                    "porelo task t2 by alice");
                    case "expired" ->
                            cert(
                                    "alice",
                                    "task",
                                    List.of("task:alpha:t1"),
                                    "20261029000000Z:20261030115959Z",
                                    false,
                                    "porelo task t1 by alice");
                    case "future" ->
                            cert(
                                    "alice",
                                    "task",
                                    List.of("task:alpha:t1"),
                                    "20261030120001Z:20261031000000Z",
                                    false,
                                    "porelo task t1 by alice");
                    case "forever" ->
                            cert(
                                    "alice",
                                    "task",
                                    List.of("task:alpha:t1"),
                                    "20261030000000Z:forever",
                                    false,
                                    "porelo task t1 by alice");
                    case "host" ->
                            cert(
                                    "alice",
                                    "task",
                                    List.of("task:alpha:t1"),
                                    "20261030000000Z:20261031000000Z",
                                    true,
                                    "porelo task t1 by alice");
                    case "plain" -> Files.readString(host.keys.resolve("task.pub"));
                    case "long" -> host.certificate().strip() + " " + "x".repeat(16384);
                    case "two" -> host.certificate().repeat(2);
                    case "empty" -> "";
                    default -> host.certificate();
                };
        if (defect.equals("revoked")) host.organization(host.alice, true, false);
        if (defect.equals("absent")) host.organization(host.alice, false, true);
        if (defect.equals("signature")) {
            final String[] words = certificate.split(" ");
            final byte[] blob = java.util.Base64.getDecoder().decode(words[1]);
            blob[blob.length - 5] ^= 1;
            certificate =
                    words[0] + " " + java.util.Base64.getEncoder().encodeToString(blob) + "\n";
            host.judgementCode = 65;
        }
        final var before = host.snapshot();
        final var answer = start(certificate);
        final boolean usage = List.of("plain", "long", "two", "empty").contains(defect);
        assertThat(answer.code()).as(answer.err()).isEqualTo(usage ? 64 : 65);
        assertThat(answer.out()).isEmpty();
        assertThat(host.record("t1")).doesNotExist();
        assertThat(host.requests).isEmpty();
        assertThat(host.calls).noneMatch(call -> call.contains("generate-access-token"));
        assertThat(host.snapshot()).isEqualTo(before);
    }

    // py:
    // test_the_same_certificate_again_replaces_its_token_and_another_for_a_recorded_task_is_refused
    @Test
    void retriesRotateTheTokenAndNeverReplaceTheRecordedCertificate() throws Exception {
        final String certificate = host.certificate();
        final var first = start(certificate);
        final String record = Files.readString(host.record("t1"));
        final var second = start(certificate);
        assertThat(second.code()).as(second.err()).isZero();
        assertThat(first.out()).isNotEqualTo(second.out());
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-t1");
        final var refused =
                start(
                        cert(
                                "alice",
                                "other-task",
                                List.of("task:alpha:t1"),
                                "20261030000000Z:20261031000000Z",
                                false,
                                "porelo task t1 by alice"));
        assertThat(refused.code()).isEqualTo(65);
        assertThat(Files.readString(host.record("t1"))).isEqualTo(record);
        assertThat(host.tokens.get("porelo-alice").values())
                .extracting(HostTaskFixture.Token::secret)
                .containsExactly(second.out());
    }

    // py: test_a_task_key_recorded_for_one_task_is_refused_for_another
    @Test
    void aTaskKeyCannotBeRecordedForASecondTask() throws Exception {
        assertThat(start(host.certificate()).code()).isZero();
        final String other =
                cert(
                        "alice",
                        "task",
                        List.of("task:alpha:t2"),
                        "20261030000000Z:20261031000000Z",
                        false,
                        "porelo task t2 by alice");
        assertThat(host.run(other, "task", "start", "t2").code()).isEqualTo(65);
        assertThat(host.record("t2")).doesNotExist();
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-t1");
    }

    // py: test_task_end_deletes_that_tasks_token_alone
    @Test
    void endingOneTaskLeavesItsRecordAndEveryOtherToken() throws Exception {
        assertThat(start(host.certificate()).code()).isZero();
        host.add("porelo-alice", "porelo-task-t2");
        host.add("alice", "person");
        assertThat(host.run("", "task", "end", "t1"))
                .isEqualTo(new HostReadFixture.Answer(0, "{\"task\":\"t1\",\"ended\":true}\n", ""));
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-t2");
        assertThat(host.names("alice")).containsExactly("person");
        assertThat(host.record("t1")).exists();
        assertThat(host.run("", "task", "end", "missing").code()).isEqualTo(65);
    }

    // py: test_a_task_is_recorded_without_a_token_where_the_host_runs_no_forge
    @Test
    void aForgelessHostStillRecordsATaskAndRefusesAPersonToken() throws Exception {
        host.tree.put("/etc/porelo-host/forge.conf", "host=workshop\n");
        assertThat(start(host.certificate())).isEqualTo(new HostReadFixture.Answer(0, "", ""));
        assertThat(host.record("t1")).exists();
        assertThat(host.calls).noneMatch(call -> call.contains("podman"));
        assertThat(host.run("", "forge", "issue-token").code()).isEqualTo(69);
    }

    // py: test_task_record_links_cannot_redirect_root_writes_or_reads
    @ParameterizedTest
    @ValueSource(strings = {"root", "person", "record"})
    void taskLinksNeverRedirectAPublicationOrRead(String part) throws Exception {
        final String certificate = host.certificate();
        assertThat(start(certificate).code()).isZero();
        final Path sentinel = Files.writeString(directory.resolve("sentinel"), "untouched");
        final Path outside = Files.createDirectory(directory.resolve("outside"));
        final Path target =
                switch (part) {
                    case "root" -> host.record("t1").getParent().getParent();
                    case "person" -> host.record("t1").getParent();
                    default -> host.record("t1");
                };
        Files.move(target, target.resolveSibling(target.getFileName() + ".saved"));
        Files.createSymbolicLink(target, part.equals("record") ? sentinel : outside);
        final int calls = host.calls.size();
        assertThat(start(certificate).code()).isEqualTo(73);
        assertThat(host.run("", "task", "end", "t1").code()).isEqualTo(73);
        assertThat(Files.readString(sentinel)).isEqualTo("untouched");
        assertThat(host.calls.subList(calls, host.calls.size()))
                .noneMatch(call -> call.contains("generate-access-token"));
    }

    // py: test_a_rotated_org_key_cannot_authorize_the_previous_keys_certificate
    @Test
    void rotatingTheOrganizationKeyInvalidatesEarlierCertificates() throws Exception {
        final String certificate = host.certificate();
        host.organization(host.bob, false, false);
        final var before = host.snapshot();
        assertThat(start(certificate).code()).isEqualTo(65);
        assertThat(host.snapshot()).isEqualTo(before);
        assertThat(host.requests).isEmpty();
    }

    // py: test_porelo_judges_the_certificate_against_the_key_in_force_and_the_krl_before_any_record
    @Test
    void theIsolatedJudgeGetsOnlyTheCurrentPublicKeyKrlAndCertificateInput() throws Exception {
        final String certificate = host.certificate();
        assertThat(start(certificate).code()).isZero();
        final var call =
                host.calls.stream()
                        .filter(args -> args.getFirst().equals("systemd-run"))
                        .findFirst()
                        .orElseThrow();
        assertThat(call)
                .startsWith("systemd-run", "--pipe", "--wait", "--quiet", "--collect")
                .contains(
                        "DynamicUser=yes",
                        "PrivateNetwork=yes",
                        "ProtectHome=yes",
                        "--keys",
                        "/run/credentials/porelo-host-task-check.service/keys",
                        "--krl",
                        "/run/credentials/porelo-host-task-check.service/revoked.krl");
        assertThat(call).doesNotContain(certificate.strip().split(" ")[1]);
        assertThat(host.checkedKeys).isEqualTo(host.alice + "\n");
        assertThat(host.checkedKrl).isEqualTo("fixture KRL");
        assertThat(host.checkedInput)
                .isEqualTo(
                        String.join(" ", java.util.Arrays.copyOf(certificate.strip().split(" "), 2))
                                + "\n");
        assertThat(host.inputBuffers).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        try (var left = Files.list(host.tree.path("/run/porelo-host/sync"))) {
            assertThat(left).isEmpty();
        }
    }

    // py: test_a_certificate_porelo_refuses_or_answers_otherwise_starts_no_task
    @ParameterizedTest
    @ValueSource(strings = {"refused", "fingerprint", "signed_by", "project", "invalid", "array"})
    void noRecordOrTokenFollowsARefusedOrMismatchedJudgement(String response) throws Exception {
        final String certificate = host.certificate();
        switch (response) {
            case "refused" -> {
                host.judgementCode = 65;
                host.judgementError = "porelo: the key id names another login";
            }
            case "invalid" -> host.judgement = "not json";
            case "array" -> host.judgement = "[]";
            default -> {
                final String encoded = certificate.split(" ")[1];
                final Path path =
                        Files.writeString(
                                directory.resolve("inspection.pub"),
                                HostCertificate.KIND + " " + encoded);
                final var result =
                        new HostPrograms(
                                        java.time.Duration.ofSeconds(10),
                                        65536,
                                        org.fuin.porelo.wire.TestPrograms::find)
                                .run(
                                        List.of(
                                                "/usr/bin/env",
                                                "TZ=UTC",
                                                "/usr/bin/ssh-keygen",
                                                "-L",
                                                "-f",
                                                path.toString()));
                final var fields = HostCertificate.read(result.output());
                final var answer =
                        new java.util.HashMap<String, Object>(
                                Map.of(
                                        "fingerprint",
                                        fields.fingerprint(),
                                        "signed_by",
                                        fields.signedBy(),
                                        "project",
                                        "alpha"));
                answer.put(response, "different");
                host.judgement = org.fuin.porelo.wire.Json.write(answer);
            }
        }
        final var refused = start(certificate);
        assertThat(refused.code()).isEqualTo(response.equals("refused") ? 65 : 69);
        if (response.equals("refused"))
            assertThat(refused.err()).contains("the key id names another login");
        assertThat(host.record("t1")).doesNotExist();
        assertThat(host.calls).noneMatch(args -> args.contains("generate-access-token"));
    }

    // py: test_a_missing_krl_refuses_the_task_before_porelo_runs
    @ParameterizedTest
    @ValueSource(strings = {"missing", "link", "writable", "unreadable"})
    void anUnavailableOrUnsafeKrlStopsBeforeTheJudge(String defect) throws Exception {
        final Path krl = host.tree.path("/etc/ssh/porelo-revoked.krl");
        switch (defect) {
            case "missing" -> Files.delete(krl);
            case "link" -> {
                Files.move(krl, krl.resolveSibling("other.krl"));
                Files.createSymbolicLink(krl, Path.of("other.krl"));
            }
            case "writable" -> host.tree.overrides.put(krl, Map.of("mode", 0100666));
            default ->
                    Files.setPosixFilePermissions(
                            krl,
                            java.nio.file.attribute.PosixFilePermissions.fromString("-w-------"));
        }
        assertThat(start(host.certificate()).code()).isEqualTo(69);
        assertThat(host.calls).noneMatch(args -> args.getFirst().equals("systemd-run"));
        assertThat(host.record("t1")).doesNotExist();
    }

    // py: test_a_certificate_for_a_project_the_person_is_no_member_of_is_refused_before_porelo_runs
    @ParameterizedTest
    @ValueSource(strings = {"host-admin", "beta developer", "alphabet developer"})
    void anotherMembershipCannotAuthorizeThisProject(String member) throws Exception {
        host.tree.person("alice", "member=" + member + "\n", "");
        assertThat(start(host.certificate()).code()).isEqualTo(65);
        assertThat(host.calls).noneMatch(args -> args.getFirst().equals("systemd-run"));
    }

    // py: test_a_project_admin_starts_a_task_of_their_project
    @Test
    void aProjectAdminIsAMemberForTaskAuthorization() throws Exception {
        host.tree.person("alice", "member=alpha project-admin\n", "");
        assertThat(start(host.certificate()).code()).isZero();
        assertThat(host.record("t1")).exists();
    }

    // py: test_no_porelo_on_the_host_refuses_the_task_as_unprepared
    @ParameterizedTest
    @ValueSource(strings = {"porelo", "systemd-run"})
    void missingJudgementProgramsRefuseBeforeInspection(String command) throws Exception {
        host.missing.add(command);
        assertThat(start(host.certificate()).code()).isEqualTo(69);
        assertThat(host.calls).isEmpty();
    }

    // py: test_wrong_task_and_token_calls_change_nothing
    @ParameterizedTest
    @ValueSource(
            strings = {
                "task",
                "task start",
                "task start T1",
                "task start ../t1",
                "task start t1 extra",
                "task end",
                "task --drop t1",
                "forge issue-token alice",
                "task sweep now"
            })
    void malformedCallsNeverReachRecordsOrTheForge(String arguments) throws Exception {
        final var before = host.snapshot();
        assertThat(host.run("", arguments.split(" ")).code()).isEqualTo(64);
        assertThat(host.snapshot()).isEqualTo(before);
        assertThat(host.calls).isEmpty();
    }

    @Test
    void rootWithoutASudoPersonCannotStartOrEndATask() {
        assertThat(host.run(Map.of(), "", "task", "start", "t1").code()).isEqualTo(64);
        assertThat(host.run(Map.of(), "", "task", "end", "t1").code()).isEqualTo(64);
        assertThat(host.calls).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"AA,64", "AAA,64", "AAAA=,64", "AAAA,0", "AAA=,0", "AA==,0"})
    void certificateInputRequiresTheSameBase64PaddingAsThePredecessor(String encoded, int code) {
        final String line = HostCertificate.KIND + " " + encoded;
        if (code == 0) {
            assertThat(
                            HostCertificate.line(
                                    line.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .isEqualTo(line);
        } else {
            assertThatThrownBy(
                            () ->
                                    HostCertificate.line(
                                            line.getBytes(
                                                    java.nio.charset.StandardCharsets.US_ASCII)))
                    .isInstanceOfSatisfying(
                            HostRefusal.class, ex -> assertThat(ex.exitCode()).isEqualTo(64));
        }
    }

    @Test
    void duplicateOrImplicitlyRevokedOrganizationRowsNeverAuthorizeATask() throws Exception {
        final String certificate = host.certificate();
        for (String persons :
                List.of(
                        "[{\"login\":\"alice\",\"ssh\":\"" + host.alice + "\"}]",
                        "[{\"login\":\"alice\",\"ssh\":\""
                                + host.alice
                                + "\",\"revoked\":false},{\"login\":\"alice\",\"ssh\":\""
                                + host.alice
                                + "\",\"revoked\":false}]")) {
            host.tree.put(
                    "/etc/porelo-host/organization/applied.json",
                    "{\"commit\":\""
                            + "a".repeat(40)
                            + "\",\"projects\":[],\"persons\":"
                            + persons
                            + ",\"revoked\":[]}");
            assertThat(start(certificate).code()).isEqualTo(65);
        }
        assertThat(host.calls).isEmpty();
    }
}
