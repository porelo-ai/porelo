package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostFollowSelectionTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
        f.migrated();
    }

    private void rows() {
        f.verified.put(
                "repositories",
                new LinkedHashMap<>(
                        Map.of(
                                "one",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/one.git"),
                                "two",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/two.git"))));
    }

    @Test
    void pushConfigurationKeepsTheVerifiedProjectKeyBesideItsForgeName() throws Exception {
        // Losing a verified alias can make a person release an unintended repository.
        f.verified.put(
                "repositories",
                Map.of(
                        "component",
                        Map.of(
                                "review",
                                "required",
                                "central",
                                "https://forge.example.org/acme/engine.git")));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(
                        f.inputs.stream()
                                .filter(text -> text.startsWith("# Written by porelo-host-person"))
                                .toList()
                                .getLast())
                .as("the follow carries both identities from the verified file")
                .contains("repository engine component https://forge.example.org/acme/engine.git");
    }

    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aProjectKeyCannotNameAnotherCentralsRepositoryWithTheSameBasename() throws Exception {
        // A stale migration must not silently bind a project key to the wrong central destination.
        f.verified.put(
                "repositories",
                Map.of(
                        "component",
                        Map.of(
                                "review",
                                "required",
                                "central",
                                "https://forge.example.org/elsewhere/code.git")));
        assertThatThrownBy(() -> f.refresh(ProjectFixture.CENTRAL_TOKEN))
                .as("the verified address must match the forge's recorded migration")
                .isInstanceOf(HostRefusal.class);
        assertThat(f.inputs)
                .as("no member receives a misleading alias")
                .noneMatch(
                        text ->
                                text.startsWith("# Written by porelo-host-person")
                                        && text.contains("repository code component "));
    }

    private Map<String, Object> status(String project) {
        return row(
                ((List<?>) new HostOrganization(f.host).status().get("projects"))
                        .stream()
                                .filter(v -> project.equals(row(v).get("project")))
                                .findFirst()
                                .orElseThrow());
    }

    // py: test_a_members_push_config_is_written_as_that_member_and_goes_with_the_membership
    @Test
    void pushConfigurationsRunAsEachMemberAndAreRemovedWithMembership() throws Exception {
        f.run();
        final var writes =
                f.commands.stream()
                        .filter(
                                a ->
                                        a.stream()
                                                .anyMatch(
                                                        v ->
                                                                v.contains(
                                                                        "cat > \"$d/$1.conf.new\"")))
                        .toList();
        assertThat(writes).hasSize(2);
        assertThat(writes)
                .allSatisfy(
                        a ->
                                assertThat(a)
                                        .containsSequence("runuser", "-u")
                                        .doesNotContain("root"));
        f.answer.put("projects", List.of());
        f.run();
        assertThat(f.commands)
                .anyMatch(
                        a ->
                                a.stream()
                                        .anyMatch(
                                                v ->
                                                        v.startsWith(
                                                                "rm -f \"$HOME/.config/porelo-host/push")));
    }

    // py: test_a_repository_with_no_central_address_writes_no_push_config
    @ParameterizedTest
    @ValueSource(strings = {"", "file:///local"})
    void invalidCentralUrlsNeverBecomeMemberPushConfigurations(String address) throws Exception {
        f.project.repositories.get("alpha/code").put("original_url", address);
        assertThatThrownBy(f::run).hasMessageContaining("no valid central address");
        assertThat(f.inputs).noneMatch(text -> text.startsWith("# Written by porelo-host-person"));
    }

    // py: test_a_project_taken_off_writes_no_config_and_takes_each_away
    @Test
    void aRemovedProjectDeletesEveryPreviousMembersPushConfiguration() throws Exception {
        f.run();
        f.answer.put("projects", List.of());
        f.run();
        final var removals =
                f.commands.stream()
                        .filter(
                                a ->
                                        a.stream()
                                                .anyMatch(
                                                        v ->
                                                                v.startsWith(
                                                                        "rm -f \"$HOME/.config/porelo-host/push")))
                        .toList();
        assertThat(removals).hasSize(2);
        assertThat(removals).anyMatch(a -> a.contains("bob")).anyMatch(a -> a.contains("charlie"));
    }

    // py: test_a_token_gives_the_members_their_new_repositories_at_once
    @Test
    void newlyMigratedRepositoriesReachMembersPushConfigurationsImmediately() throws Exception {
        rows();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        final var configurations =
                f.inputs.stream()
                        .filter(text -> text.startsWith("# Written by porelo-host-person"))
                        .toList();
        assertThat(configurations.getLast()).contains("repository one ", "repository two ");
    }

    // py: test_a_repository_the_card_no_longer_names_is_taken_off
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRepositoryNoLongerSelectedIsTakenOffWithOrWithoutAToken(boolean token) throws Exception {
        rows();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("repositories", List.of("one"));
        if (token) {
            f.refresh(ProjectFixture.CENTRAL_TOKEN);
        } else {
            f.run();
        }
        assertThat(f.project.repositories).containsKey("alpha/one").doesNotContainKey("alpha/two");
        assertThat(f.item().get("taken"))
                .asList()
                .doesNotContain("https://forge.example.org/acme/two.git");
        assertThat(
                        f.inputs.stream()
                                .filter(text -> text.startsWith("# Written by porelo-host-person"))
                                .toList()
                                .getLast())
                .doesNotContain("repository two ");
    }

    // py: test_a_repository_with_work_not_at_the_central_forge_is_held_and_tried_again
    @Test
    void unpushedWorkHoldsARepositoryAndTheNextFollowRetriesIt() throws Exception {
        rows();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("repositories", List.of("one"));
        f.project.localCommits = "d".repeat(40) + "\n";
        f.run();
        assertThat(f.project.repositories).containsKey("alpha/two");
        assertThat(f.item().get("why")).asString().contains("held on this host");
        f.project.localCommits = "a".repeat(40) + "\n";
        f.run();
        assertThat(f.project.repositories).doesNotContainKey("alpha/two");
    }

    // py: test_the_projects_own_repository_is_never_taken_off_however_the_file_spells_it
    @Test
    void aProjectsOwnCentralNameStaysEvenWhenItsAliasIsNoLongerSelected() throws Exception {
        rows();
        final var map = row(f.verified.get("repositories"));
        map.put("own", Map.of("review", "required", "central", ProjectFixture.ADDRESS));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("repositories", List.of("one"));
        f.run();
        assertThat(f.project.repositories).containsKey("alpha/code");
    }

    // py: test_a_repository_another_named_row_carries_is_not_taken_off
    @Test
    void anotherSelectedAliasKeepsTheSameCentralRepository() throws Exception {
        rows();
        row(f.verified.get("repositories"))
                .put(
                        "alias",
                        Map.of(
                                "review",
                                "required",
                                "central",
                                "https://forge.example.org/acme/two.git"));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("repositories", List.of("one", "alias"));
        f.run();
        assertThat(f.project.repositories).containsKey("alpha/two");
    }

    // py: test_off_says_in_the_journal_which_key_stays_at_the_central_forge
    @Test
    void takingOffWithoutATokenNamesTheDeployKeyLeftAtCentral() throws Exception {
        rows();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("repositories", List.of("one"));
        f.run();
        assertThat(f.journal.toString()).contains("read-only deploy key", "stays at", "two.git");
    }

    // py: test_an_unpushed_project_is_held_then_removed_after_the_push
    @Test
    void removalHoldsEveryRefUntilItsCommitsReachCentral() throws Exception {
        f.run();
        f.project.localCommits = "d".repeat(40) + "\n";
        f.answer.put("projects", List.of());
        f.run();
        assertThat(f.item()).containsEntry("state", "held");
        assertThat(f.item().get("why")).asString().contains("unpushed commits");
        f.project.localCommits = "a".repeat(40) + "\n";
        f.run();
        assertThat(f.progress()).doesNotContainKey("alpha");
        assertThat(Files.exists(f.path("/etc/porelo-host/projects/alpha.conf"))).isFalse();
    }

    // py: test_follow_reports_an_unfinished_removal_after_revoking_the_projects_access
    // py: test_a_removal_refused_for_another_reason_than_commits_gives_its_words_and_stays_held
    @Test
    void anUnverifiableRemovalKeepsTheProjectAndItsSafeReason() throws Exception {
        f.run();
        f.project.gitFailure = 1;
        f.answer.put("projects", List.of());
        f.run();
        assertThat(f.progress()).containsKey("alpha");
        assertThat(f.item().get("why")).isNotNull();
        assertThat(Files.exists(f.path("/etc/porelo-host/projects/alpha.conf"))).isTrue();
    }

    // py: test_a_held_project_revokes_its_room_members_before_its_forge_can_go
    @Test
    void aHeldProjectsRoomIsEmptiedBeforeItsRemovalComparison() throws Exception {
        f.write("/etc/systemd/system/porelo-matrix-gate.socket", "gate");
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
        f.run();
        f.project.localCommits = "d".repeat(40) + "\n";
        f.answer.put("projects", List.of());
        f.run();
        final var keep =
                f.commands.stream().filter(a -> a.contains("keep-room")).toList().getLast();
        assertThat(keep.get(keep.indexOf("--persons") + 1)).isEmpty();
        assertThat(f.item()).containsEntry("state", "held");
    }

    // py: test_a_project_put_on_again_is_migrated_though_its_record_outlived_the_removal
    @Test
    void aRetriedCarryRespectsThePhysicalForgeRecord() throws Exception {
        f.write("/etc/systemd/system/porelo-matrix-gate.socket", "gate");
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
        f.run();
        final var project = f.project();
        f.override = argv -> argv.contains("drop-room") ? new HostPrograms.Result(75, "") : null;
        f.answer.put("projects", List.of());
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/projects/alpha.conf"))).isFalse();
        assertThat(f.progress()).containsKey("alpha");
        f.override = argv -> null;
        f.answer.put("projects", List.of(project));
        f.run();
        assertThat(f.item())
                .containsEntry("state", "waiting-for-token")
                .doesNotContainKey("migrated");
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories).containsKey("alpha/code");
    }

    // py: test_a_new_forge_organization_takes_the_named_repositories_again
    @Test
    void recreatedForgeOrganizationsDiscardOldMigrationProgress() throws Exception {
        rows();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        Files.delete(f.path("/etc/porelo-host/projects/alpha.conf"));
        f.project.organizations.clear();
        f.project.repositories.clear();
        f.project.teams.clear();
        f.project.users.remove("porelo-publisher-alpha");
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories).containsKeys("alpha/code", "alpha/one", "alpha/two");
    }

    // py: test_org_status_names_each_repository_taken_waiting_or_with_no_central
    @Test
    void statusShowsTakenWaitingAndLocalRepositories() throws Exception {
        rows();
        row(f.verified.get("repositories"))
                .put("local", fields("review", "required", "central", null));
        f.run();
        final var rows = (List<?>) status("alpha").get("repositories");
        assertThat(rows)
                .anySatisfy(
                        v ->
                                assertThat(row(v))
                                        .containsEntry("repository", "one")
                                        .containsEntry("state", "waiting"))
                .anySatisfy(
                        v ->
                                assertThat(row(v))
                                        .containsEntry("repository", "local")
                                        .containsEntry("state", "no-central"));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat((List<?>) status("alpha").get("repositories"))
                .anySatisfy(
                        v ->
                                assertThat(row(v))
                                        .containsEntry("repository", "one")
                                        .containsEntry("state", "taken"));
    }

    // py: test_org_status_gives_no_why_for_a_project_whose_rest_has_no_central
    @Test
    void choosingALocalOnlyRepositoryDoesNotGiveTheProjectAFailure() throws Exception {
        f.verified.put(
                "repositories", fields("local", fields("review", "required", "central", null)));
        f.run();
        assertThat(status("alpha")).containsEntry("why", null);
    }

    // py: test_org_status_gathers_like_reasons_of_repositories_in_the_projects_why
    @Test
    void identicalWaitingReasonsAreGroupedOnceInProjectStatus() throws Exception {
        rows();
        f.run();
        assertThat(status("alpha").get("why")).isEqualTo("one, two: waits for a central token");
    }

    // py: test_org_status_says_the_projects_own_repository_is_taken
    @Test
    void ownRepositoryRowsCountAsTakenByTheProjectMigration() throws Exception {
        f.verified.put(
                "repositories",
                fields("own", fields("review", "required", "central", ProjectFixture.ADDRESS)));
        f.run();
        assertThat((List<?>) status("alpha").get("repositories"))
                .anySatisfy(
                        v ->
                                assertThat(row(v))
                                        .containsEntry("repository", "own")
                                        .containsEntry("state", "taken"));
    }

    // py: test_org_status_names_no_repository_before_a_file_was_read
    @Test
    void anUnreadProjectFileNamesNoRepositoryRows() throws Exception {
        f.verifyCode = 65;
        f.run();
        assertThat(status("alpha").get("repositories")).asList().isEmpty();
    }

    // py: test_org_status_says_how_a_taken_repositorys_main_stands_against_central
    @Test
    void statusUsesThePublishedCentralMeasurement() throws Exception {
        f.verified.put(
                "repositories",
                fields("own", fields("review", "required", "central", ProjectFixture.ADDRESS)));
        f.run();
        final var rows = (List<?>) status("alpha").get("repositories");
        assertThat(row(row(rows.getFirst()).get("central"))).containsEntry("state", "up-to-date");
    }

    // py: test_project_lock_removes_person_gate_and_agent_only_from_its_teams
    @Test
    void oneProjectsLockRemovesItsIdentitiesWithoutTouchingAnotherProject() throws Exception {
        f.run();
        final var beta = FollowFixture.project("beta");
        beta.put("members", List.of(fields("login", "bob", "role", "developer", "locked", false)));
        f.answer.put("projects", List.of(f.project(), beta));
        f.run();
        ((List<String>) f.project.teams.get(1L).get("members")).add("stranger");
        row(((List<?>) f.project().get("members")).getFirst()).put("locked", true);
        f.project.calls.clear();
        f.run();
        assertThat(f.project.calls)
                .anyMatch(
                        call ->
                                call.method().equals("DELETE")
                                        && call.path().equals("/api/v1/teams/1/members/bob"));
        assertThat(f.project.calls)
                .anyMatch(
                        call ->
                                call.method().equals("DELETE")
                                        && call.path()
                                                .equals("/api/v1/teams/2/members/porelo-bob"));
        assertThat((List<?>) f.project.teams.get(1L).get("members"))
                .anySatisfy(v -> assertThat(v).isEqualTo("stranger"));
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
    }

    // py: test_follow_admits_a_new_card_without_inventing_legacy_agent_accounts
    @Test
    void admissionCreatesOnlyThePersonAndTaskGateIdentities() throws Exception {
        f.write(
                "/etc/porelo-host/forge.conf",
                f.read("/etc/porelo-host/forge.conf") + "agents=legacy\n");
        final var persons = new ArrayList<Object>((List<?>) f.answer.get("persons"));
        persons.add(FollowFixture.card("carol", 8));
        f.answer.put("persons", persons);
        final var members = new ArrayList<Object>((List<?>) f.project().get("members"));
        members.add(fields("login", "carol", "role", "developer", "locked", false));
        f.project().put("members", members);
        f.run();
        assertThat(f.project.users)
                .contains("carol", "porelo-carol")
                .doesNotContain("legacy-carol");
    }

    // py: test_follow_arms_the_projects_repositories_on_its_forge
    @Test
    void armingRecordsTheExistingHistoryAfterInstallingTheJudge() throws Exception {
        f.override =
                argv ->
                        argv.stream().anyMatch(v -> v.contains("custom_hooks_dir"))
                                ? new HostPrograms.Result(
                                        0, "repository code\n" + "a".repeat(40) + "\n")
                                : null;
        f.run();
        final String log = f.read("/var/lib/porelo-host/arrivals/alpha/code.jsonl");
        assertThat(log).contains("\"verdict\":\"migrated\"", "\"verdict\":\"armed\"");
        assertThat(f.commands)
                .anyMatch(
                        argv ->
                                argv.stream()
                                        .anyMatch(
                                                v ->
                                                        v.contains(
                                                                "/usr/local/libexec/porelo/pre-receive")));
    }
}
