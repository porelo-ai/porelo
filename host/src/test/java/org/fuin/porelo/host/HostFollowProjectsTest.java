package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostFollowProjectsTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private void gate() throws Exception {
        f.write("/etc/systemd/system/porelo-matrix-gate.socket", "fixture gate");
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
    }

    private Map<String, Object> publication() {
        return f.json(FollowFixture.ORG + "projects/alpha.json");
    }

    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    private void item(Map<String, Object> change) throws Exception {
        final var entries = f.progress();
        final var item = row(entries.get("alpha"));
        item.putAll(change);
        f.write(FollowFixture.ORG + "host-projects.json", HostJson.write(entries));
    }

    // py: test_a_host_without_services_admits_a_project_without_running_service_commands
    @Test
    void aHostWithoutServicesCarriesItsProjectWithoutAForgeOrGate() throws Exception {
        f.run();
        assertThat(f.item()).containsEntry("state", "carried");
        assertThat(f.commands)
                .noneMatch(a -> a.contains("podman") || a.contains(FollowRooms.TRANSPORT));
    }

    // py: test_a_new_project_registers_its_own_admin_and_waits_for_refresh
    @Test
    void aNewForgeProjectRegistersItsCurrentProjectAdminAndWaitsForAToken() throws Exception {
        f.forge();
        f.run();
        assertThat(f.read("/etc/porelo-host/projects/alpha.conf"))
                .contains("admin=bob", "signed_by=" + FollowFixture.key(2));
        assertThat(f.item()).containsEntry("state", "waiting-for-token");
        assertThat(f.project.centralCalls).isEmpty();
    }

    // py: test_an_interrupted_registration_is_retried_from_the_physical_record
    @Test
    void aPhysicalProjectRecordPreventsDuplicateRegistration() throws Exception {
        f.forge();
        f.run();
        final long count =
                f.project.calls.stream()
                        .filter(
                                call ->
                                        call.method().equals("POST")
                                                && call.path().endsWith("/orgs"))
                        .count();
        f.run();
        assertThat(
                        f.project.calls.stream()
                                .filter(
                                        call ->
                                                call.method().equals("POST")
                                                        && call.path().endsWith("/orgs")))
                .hasSize((int) count);
    }

    // py: test_refresh_can_put_on_a_project_before_the_timer_has_seen_it
    @Test
    void refreshCanRegisterAndMigrateBeforeTheFirstTimerPass() throws Exception {
        f.forge();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item()).containsEntry("migrated", true).containsEntry("state", "carried");
        assertThat(f.project.repositories).containsKey("alpha/code");
    }

    // py: test_refresh_consumes_the_token_only_for_waiting_migrations
    @Test
    void aCompletedMigrationIsNotRepeatedByRefresh() throws Exception {
        f.migrated();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        final long before =
                f.project.calls.stream()
                        .filter(call -> call.path().equals("/api/v1/repos/migrate"))
                        .count();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(
                        f.project.calls.stream()
                                .filter(call -> call.path().equals("/api/v1/repos/migrate"))
                                .count())
                .isEqualTo(before);
        assertThat(f.commands.toString() + f.journal).doesNotContain(ProjectFixture.CENTRAL_TOKEN);
    }

    // py: test_a_failed_migration_keeps_nobody_out_of_the_room
    @Test
    void migrationFailureStillKeepsTheRoomForCurrentMembers() throws Exception {
        f.forge();
        gate();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item().get("why")).asString().contains("exit 69");
        assertThat(f.commands).anyMatch(a -> a.contains("keep-room"));
        assertThat(publication()).containsEntry("room", "!alpha:host");
    }

    // py: test_a_refused_migrations_why_lasts_until_a_later_try_ends
    @Test
    void timerPassesPreserveTheLastMigrationRefusalUntilRetrySucceeds() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        final Object refusal = f.item().get("migrate_refused");
        f.run();
        assertThat(f.item()).containsEntry("migrate_refused", refusal);
        f.project.replies.remove("POST /repos/migrate");
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item()).doesNotContainKey("migrate_refused");
    }

    // py: test_a_kept_refusal_hides_nothing_the_organization_says_now_and_ends_with_the_forge
    @Test
    void anOldMigrationRefusalIsCombinedWithCurrentOrganizationReasons() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("address", "bad-address");
        f.run();
        assertThat(f.item().get("why")).asString().contains("exit 69", "unusable address");
        f.write("/etc/porelo-host/forge.conf", "host=build-host\naccount=\n");
        f.run();
        assertThat(f.item()).doesNotContainKey("migrate_refused");
    }

    // py: test_an_expired_project_signing_key_cannot_register_a_forge_project
    @Test
    void projectRegistrationRequiresAnAdminSigningKeyThatStandsNow() throws Exception {
        f.forge();
        row(((List<?>) f.project().get("signing_keys")).getFirst())
                .put("valid_until", "2026-11-03");
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/projects/alpha.conf"))).isFalse();
        assertThat(f.item().get("why")).asString().contains("no unlocked admin");
    }

    // py: test_room_and_homeserver_stay_null_together_until_the_room_is_kept
    @Test
    void aGateWithoutAConfirmedRoomPublishesNeitherRoomNorHomeserver() throws Exception {
        gate();
        f.roomCode = 75;
        f.run();
        assertThat(publication()).containsEntry("room", null).containsEntry("homeserver", null);
    }

    // py: test_a_room_loses_a_locked_member_on_the_same_follow
    @Test
    void roomMembershipIsComputedAfterCardLocks() throws Exception {
        gate();
        f.run();
        f.person(1).put("revoked", true);
        f.next("b");
        f.run();
        final var command =
                f.commands.stream().filter(a -> a.contains("keep-room")).toList().getLast();
        assertThat(command.get(command.indexOf("--persons") + 1)).isEqualTo("charlie");
    }

    // py: test_room_failures_retry_only_transient_failures
    @ParameterizedTest
    @CsvSource({"75, keep-room", "78, keep-room", "75, drop-room", "78, drop-room"})
    void onlyTransientRoomRefusalsAreRetried(int code, String verb) throws Exception {
        gate();
        if (verb.equals("drop-room")) {
            f.run();
            f.answer.put("projects", List.of());
        }
        f.roomCode = code;
        f.run();
        final int before = (int) f.commands.stream().filter(a -> a.contains(verb)).count();
        f.roomCode = 0;
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains(verb)))
                .hasSize(before + (code == 75 ? 1 : 0));
        if (code == 78) {
            assertThat(f.item())
                    .containsEntry("why", "the gate refused the room (exit 78); left alone");
        }
    }

    // py: test_a_passing_room_refusal_does_not_last_like_a_migrations
    @Test
    void aTransientRoomErrorEndsAfterTheNextSuccessfulKeep() throws Exception {
        gate();
        f.roomCode = 75;
        f.run();
        assertThat(f.item().get("why")).isNotNull();
        f.roomCode = 0;
        f.run();
        assertThat(f.item()).containsEntry("why", null);
    }

    // py: test_room_commands_run_as_the_gate_account_with_credentials_on_stdin
    @ParameterizedTest
    @ValueSource(ints = {0, 75, 78})
    void roomCommandsSwitchAccountAndCarryNoSecretInArguments(int code) throws Exception {
        gate();
        f.roomCode = code;
        if (code == 0) {
            FollowRooms.room(f.context(), "keep-room", "alpha", List.of("bob", "charlie"));
        } else {
            assertThatThrownBy(
                            () ->
                                    FollowRooms.room(
                                            f.context(),
                                            "keep-room",
                                            "alpha",
                                            List.of("bob", "charlie")))
                    .isInstanceOf(HostRefusal.class)
                    .extracting("exitCode")
                    .isEqualTo(code);
        }
        final int index =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(i -> f.commands.get(i).contains("keep-room"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.commands.get(index)).containsSequence("runuser", "-u", "porelo-matrix", "--");
        assertThat(f.inputs.get(index))
                .contains("PORELO_MATRIX_ADMIN_TOKEN", "matrix-private-token");
        assertThat(f.commands.toString() + f.journal).doesNotContain("matrix-private-token");
    }

    // py: test_a_room_that_lost_someone_is_named_in_the_journal
    @Test
    void removedRoomMembersAreNamedInTheJournal() throws Exception {
        gate();
        f.override =
                argv ->
                        argv.contains("keep-room")
                                ? new HostPrograms.Result(
                                        0, "{\"room\":\"!alpha:host\",\"removed\":[\"bob\"]}")
                                : null;
        FollowRooms.room(f.context(), "keep-room", "alpha", List.of());
        assertThat(f.journal.toString()).contains("alpha: out of its room: bob");
    }

    // py: test_a_removed_gate_is_not_called_for_a_departing_project
    @Test
    void anUninstalledGateIsNeverAskedToDropItsOldRoom() throws Exception {
        gate();
        f.run();
        final long before = f.commands.stream().filter(a -> a.contains("keep-room")).count();
        Files.delete(f.path("/etc/systemd/system/porelo-matrix-gate.socket"));
        f.answer.put("projects", List.of());
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains("keep-room"))).hasSize((int) before);
        assertThat(f.commands).noneMatch(a -> a.contains("drop-room"));
    }

    // py: test_one_projects_bad_address_does_not_stop_a_revocation
    @Test
    void aBadAddressIsAProjectReasonAndDoesNotSpareARevokedCard() throws Exception {
        f.project().put("address", "bad");
        f.person(1).put("revoked", true);
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isTrue();
        assertThat(publication()).containsEntry("address", null);
        assertThat(f.item().get("why")).asString().contains("unusable address");
    }

    // py: test_a_bad_project_cannot_stop_another_projects_revocations
    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "members", "signing_keys", "repositories", "null"})
    void aMalformedNeighborCannotPreventAccessRemoval(String damage) throws Exception {
        final var beta = FollowFixture.project("beta");
        beta.put("members", List.of());
        if (damage.equals("duplicate")) {
            f.answer.put("projects", List.of(f.project(), beta, beta));
        } else if (damage.equals("null")) {
            f.answer.put("projects", Arrays.asList(f.project(), null));
        } else {
            beta.put(damage, "invalid");
            f.answer.put("projects", List.of(f.project(), beta));
        }
        f.person(1).put("revoked", true);
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isTrue();
        assertThat(f.applied().get("project_refusals"))
                .asString()
                .contains(damage.equals("null") ? "a project" : "beta");
    }

    // py: test_signers_for_a_valid_project_never_receive_an_unusable_project_entry
    @Test
    void signersSeeOnlyTheAcceptedProjectContracts() throws Exception {
        f.answer.put("projects", Arrays.asList(f.project(), null));
        f.run();
        final int index =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(i -> f.commands.get(i).contains("signers"))
                        .findFirst()
                        .orElseThrow();
        final String credential =
                f.commands.get(index).stream()
                        .filter(a -> a.startsWith("LoadCredential=state:"))
                        .findFirst()
                        .orElseThrow();
        assertThat(credential).contains("porelo-org-signers");
        assertThat(f.read("/etc/porelo-host/signers/alpha.certifiers")).isNotEmpty();
    }

    // py: test_project_file_names_the_hosts_forge_by_the_project_and_the_central_repository
    @Test
    void projectPublicationUsesTheCentralNameUnderTheProjectsOrganization() throws Exception {
        f.migrated();
        f.run();
        assertThat(publication())
                .containsEntry("fetch_from", "http://127.0.0.1:3000/alpha/code.git");
        assertThat(row(publication().get("builds")))
                .containsEntry("credential", "forge.example.org.person");
    }

    // py: test_the_builds_go_with_the_copy_when_the_projects_address_goes
    @Test
    void noCentralAddressMeansNoForgeCopyOrBuilds() throws Exception {
        f.migrated();
        f.run();
        f.project().put("address", null);
        f.run();
        assertThat(publication()).containsEntry("fetch_from", null).doesNotContainKey("builds");
    }

    // py: test_a_forge_conf_naming_no_forge_leaves_the_builds_out_and_says_so
    @Test
    void anUnusableForgeDomainLeavesTheCopyReadableWithoutBuilds() throws Exception {
        f.migrated();
        f.write(
                "/etc/porelo-host/forge.conf",
                "host=build-host\naccount=forge\ngit_port=3000\nname=\n");
        f.run();
        assertThat(publication())
                .containsEntry("fetch_from", "http://127.0.0.1:3000/alpha/code.git")
                .doesNotContainKey("builds");
        assertThat(f.journal.toString()).contains("no builds named");
    }

    // py: test_a_host_with_no_forge_writes_certifiers_and_no_allowed_signers
    @Test
    void aHostWithoutAForgePublishesOnlyCertifiers() throws Exception {
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/signers/alpha.allowed"))).isFalse();
        assertThat(f.read("/etc/porelo-host/signers/alpha.certifiers")).isNotEmpty();
    }

    // py: test_a_project_not_yet_on_the_hosts_forge_does_not_fail_the_follow
    @Test
    void aProjectWaitingForItsFirstMigrationStillPublishes() throws Exception {
        f.forge();
        f.run();
        assertThat(Files.exists(f.path(FollowFixture.ORG + "projects/alpha.json"))).isTrue();
        assertThat(f.item()).containsEntry("state", "waiting-for-token");
    }

    // py: test_a_helpers_refusal_is_given_in_its_own_words_with_its_exit
    @Test
    void inProcessProjectRefusalsKeepTheirSafeDiagnosticAndOriginalExit() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(403, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item().get("migrate_refused")).asString().contains("exit 65");
        assertThat(f.journal.toString()).contains("(exit 65)");
    }

    // py: test_no_token_in_a_helpers_output_reaches_why_or_the_journal
    @ParameterizedTest
    @ValueSource(strings = {"porelo-host-project: SECRET", "porelo-host-project: x\u001bSECRET"})
    void aHelperCannotSmuggleTheCentralTokenInItsDiagnostic(String stderr) {
        assertThat(
                        FollowProjects.helperWords(
                                stderr.replace("SECRET", "private-token"),
                                fields("token", "private-token")))
                .isNull();
    }

    // py: test_a_last_line_not_the_helpers_own_is_never_given
    @Test
    void foreignDiagnosticLastLinesAreDiscarded() {
        assertThat(
                        FollowProjects.helperWords(
                                "porelo-host-project: safe\nforeign diagnostic", null))
                .isNull();
    }

    // py: test_a_helpers_words_are_bounded_and_carry_no_control_characters
    @Test
    void helperWordsArePrintableAndBounded() {
        assertThat(
                        FollowProjects.helperWords(
                                "porelo-host-project: \u001b" + "x".repeat(400), null))
                .hasSize(299)
                .doesNotContain("\u001b");
    }

    // py: test_every_forge_session_token_can_delete_itself
    @Test
    void everyFollowSessionIncludesTheScopeForItsOwnDeletion() throws Exception {
        f.migrated();
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains("generate-access-token")))
                .allSatisfy(
                        a -> assertThat(a.get(a.indexOf("--scopes") + 1)).contains("write:admin"));
        assertThat(f.project.calls).anyMatch(call -> call.path().contains("/tokens?"));
    }
}
