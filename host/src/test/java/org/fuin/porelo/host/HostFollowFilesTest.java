package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostFollowFilesTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
        f.migrated();
    }

    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    private void repositories(Map<String, Object> rows) {
        f.verified.put("repositories", rows);
    }

    private Map<String, Object> file() {
        return row(f.item().get("file"));
    }

    private Map<String, Object> required() {
        return new LinkedHashMap<>(Map.of("review", "required", "central", ProjectFixture.ADDRESS));
    }

    private void item(String field, Object value) throws Exception {
        final var entries = f.progress();
        row(entries.get("alpha")).put(field, value);
        f.write(FollowFixture.ORG + "host-projects.json", HostJson.write(entries));
    }

    @Test
    void signedReviewValuesSetTheLocalForgeRule() throws Exception {
        f.run();
        assertThat(f.rules.get("main"))
                .containsEntry("required_approvals", 0L)
                .containsEntry("enable_push", false)
                .containsEntry("apply_to_admins", true);
    }

    @Test
    void verificationReadsAPrivateCopyAsItsOwnerWithOnlySignersExposed() throws Exception {
        f.run();
        assertThat(f.verifyCount).isEqualTo(1);
        final var command =
                f.commands.stream().filter(a -> a.contains("verify")).findFirst().orElseThrow();
        assertThat(command)
                .contains(
                        "User=forge",
                        "PrivateNetwork=yes",
                        "ProtectHome=yes",
                        "NoNewPrivileges=yes",
                        "ProtectSystem=strict");
        assertThat(command.get(command.indexOf("--repository") + 1))
                .startsWith(f.path("/run/porelo-host-project-copy").toString());
        assertThat(command).noneMatch(a -> a.startsWith("LoadCredential=organization"));
        try (var copies = Files.list(f.path("/run/porelo-host-project-copy"))) {
            assertThat(copies).isEmpty();
        }
    }

    @Test
    void unchangedRulesAreNotRewrittenAndAChangedValueIsSetOnce() throws Exception {
        f.run();
        long before = mutations();
        f.run();
        assertThat(mutations()).isEqualTo(before);
        f.project.centralMain = "b".repeat(40);
        f.verified.put("project_review", "required");
        f.run();
        assertThat(mutations()).isEqualTo(before + 1);
        assertThat(f.rules.get("main")).containsEntry("required_approvals", 1L);
    }

    private long mutations() {
        return f.project.calls.stream()
                .filter(
                        call ->
                                call.path().contains("branch_protections")
                                        && !call.method().equals("GET"))
                .count();
    }

    @Test
    void manualRuleChangesAreRestoredFromTheSignedFile() throws Exception {
        f.run();
        f.rules.get("main").put("enable_push", true);
        f.run();
        assertThat(f.rules.get("main")).containsEntry("enable_push", false);
    }

    @Test
    void caseVariantsUseTheRuleAlreadyInForce() throws Exception {
        f.run();
        final var main = f.rules.remove("main");
        main.put("rule_name", "MAIN");
        main.put("enable_push", true);
        f.rules.put("MAIN", main);
        final long before = mutations();
        f.run();
        assertThat(f.rules).containsOnlyKeys("MAIN");
        assertThat(mutations()).isEqualTo(before + 1);
        assertThat(f.rules.get("MAIN")).containsEntry("enable_push", false);
    }

    @Test
    void theOldestMainRuleIsRepairedBeforeAnExtraRuleIsDeleted() throws Exception {
        f.run();
        final var older = new LinkedHashMap<>(f.rules.get("main"));
        older.put("rule_name", "MAIN");
        older.put("created_at", "2020");
        older.put("enable_push", true);
        f.rules.get("main").put("created_at", "2021");
        f.rules.put("MAIN", older);
        f.project.calls.clear();
        f.run();
        final var changes =
                f.project.calls.stream()
                        .filter(
                                call ->
                                        call.path().contains("branch_protections")
                                                && !call.method().equals("GET"))
                        .toList();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).method()).isEqualTo("PATCH");
        assertThat(changes.get(1).method()).isEqualTo("DELETE");
    }

    @Test
    void protectingMainNeverDeletesAnotherBranchRule() throws Exception {
        f.run();
        f.rules.put(
                "release",
                new LinkedHashMap<>(Map.of("rule_name", "release", "enable_push", true)));
        f.run();
        assertThat(f.rules).containsKey("release");
    }

    @Test
    void onlyAChangedCommitOrSignerSetRequiresReverification() throws Exception {
        f.run();
        f.run();
        assertThat(f.verifyCount).isEqualTo(1);
        row(((List<?>) f.project().get("signing_keys")).getFirst()).put("valid_from", "2026-11-01");
        f.run();
        assertThat(f.verifyCount).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"verify", "central", "shape"})
    void aRefusedReadKeepsTheLastSignedReview(String reason) throws Exception {
        f.run();
        final var known = new LinkedHashMap<>(file());
        f.project.centralMain = "b".repeat(40);
        if (reason.equals("verify")) {
            f.verifyCode = 65;
        } else if (reason.equals("central")) {
            f.project.gitFailure = 1;
        } else {
            f.verified.put("project_review", "broken");
        }
        f.run();
        assertThat(file()).isEqualTo(known);
        assertThat(f.item().get("file_refused")).asString().startsWith("the project file:");
        assertThat(f.rules.get("main")).containsEntry("required_approvals", 0L);
    }

    @Test
    void anUnreadableFirstFileRequiresReviewForEveryRepository() throws Exception {
        f.verifyCode = 65;
        f.run();
        assertThat(f.rules.get("main")).containsEntry("required_approvals", 1L);
    }

    @Test
    void aProjectWithNoAdminSignerReadsNoCopy() throws Exception {
        f.project().put("signing_keys", List.of());
        f.run();
        assertThat(f.verifyCount).isZero();
        assertThat(f.copyCount).isZero();
        assertThat(f.item().get("file_refused")).asString().contains("no project admin's key");
    }

    @Test
    void developerAndRevokedKeysNeverSignTheProjectFile() {
        final var revoked =
                fields(
                        "login",
                        "former",
                        "role",
                        "admin",
                        "key",
                        FollowFixture.key(7),
                        "valid_from",
                        null,
                        "valid_until",
                        null,
                        "revoked_since",
                        "2026-01-01");
        final var developer =
                fields(
                        "login",
                        "charlie",
                        "role",
                        "developer",
                        "key",
                        FollowFixture.key(3),
                        "valid_from",
                        null,
                        "valid_until",
                        null,
                        "revoked_since",
                        null);
        final var p = object(f.project());
        final List<Object> keys = new ArrayList<>(array(p.get("signing_keys")));
        keys.add(revoked);
        keys.add(developer);
        p.put("signing_keys", keys);
        assertThat(FollowProjects.fileSigners(p))
                .contains("bob valid-after=\"20261102\",valid-before=\"20281102\"")
                .doesNotContain("charlie", "former");
    }

    @Test
    void anAddressChangeDropsTheOldFileEvenWhenTheNewReadFails() throws Exception {
        f.run();
        f.project().put("address", "https://forge.example.org/acme/other.git");
        f.run();
        assertThat(f.item()).doesNotContainKey("file");
        assertThat(f.item().get("file_refused")).isNotNull();
    }

    @Test
    void aVerifiedFileIsAdvancedOnTheLocalForge() throws Exception {
        f.run();
        assertThat(f.item()).containsEntry("advanced", f.project.centralMain);
        assertThat(f.commands)
                .anyMatch(
                        a ->
                                a.contains("fetch")
                                        && a.contains("+refs/heads/main:refs/porelo/central"));
    }

    @Test
    void anUnchangedAdvancedMainNeedsNoNewCopyOrVerification() throws Exception {
        f.run();
        final int count = f.copyCount;
        f.run();
        assertThat(f.copyCount).isEqualTo(count);
        assertThat(f.verifyCount).isEqualTo(1);
    }

    @Test
    void aRejectedFileIsNeverAdvanced() throws Exception {
        f.verifyCode = 65;
        f.run();
        assertThat(f.item()).doesNotContainKey("advanced");
        assertThat(f.commands).noneMatch(a -> a.contains("+refs/heads/main:refs/porelo/central"));
    }

    @Test
    void localMainAheadOfCentralCountsAsAdvanced() throws Exception {
        f.project.localMain = "b".repeat(40);
        f.advanceState = "ahead";
        f.run();
        assertThat(f.item())
                .containsEntry("advanced", f.project.centralMain)
                .doesNotContainKey("advance_refused");
    }

    @Test
    void otherCentralChangesNameThePersonsPullRequest() throws Exception {
        f.project.localMain = "b".repeat(40);
        f.advanceState = "other-changes";
        f.run();
        assertThat(f.item().get("advance_refused"))
                .asString()
                .contains("person's pull request from central/main");
    }

    @Test
    void aFailedAdvanceIsRetriedEvenWithTheSameVerifiedCommit() throws Exception {
        f.project.localMain = "b".repeat(40);
        f.advanceState = "diverged";
        f.run();
        assertThat(f.item().get("advance_refused"))
                .asString()
                .contains("commits the central forge lacks");
        final int reads = f.verifyCount;
        f.project.localMain = f.project.centralMain;
        f.run();
        assertThat(f.verifyCount).isEqualTo(reads + 1);
        assertThat(f.item())
                .containsEntry("advanced", f.project.centralMain)
                .doesNotContainKey("advance_refused");
    }

    @Test
    void failedMovesDoNotCacheAChangedSignerSetAsAdvanced() throws Exception {
        f.project.localMain = "b".repeat(40);
        f.advanceState = "diverged";
        f.run();
        row(((List<?>) f.project().get("signing_keys")).getFirst()).put("valid_from", "2026-11-01");
        f.run();
        f.run();
        assertThat(f.verifyCount).isEqualTo(3);
    }

    @Test
    void aNewNamedRepositoryWaitsWithoutAMigrationToken() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"))));
        f.run();
        assertThat(row(f.item().get("repositories_why")))
                .containsEntry("extra", "waits for a central token");
        assertThat(f.item()).containsEntry("state", "waiting-for-token");
    }

    @Test
    void noCentralIsSaidWithoutAttemptingToMigrate() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of("local", fields("review", "required", "central", null))));
        f.run();
        assertThat(row(f.item().get("repositories_why")))
                .containsEntry("local", FollowProjects.NO_CENTRAL);
        assertThat(f.item()).containsEntry("why", null);
    }

    @Test
    void oneRefreshTokenTakesEachRepositoryNamedByTheSignedFile() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"),
                                "third",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/third.git"))));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories).containsKeys("alpha/extra", "alpha/third");
        assertThat(f.item().get("taken")).asList().hasSize(2);
    }

    @Test
    void aFailedMigrationDoesNotPreventTheNextNamedRepository() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"),
                                "third",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/third.git"))));
        final boolean[] once = {true};
        f.forgeBefore =
                call -> {
                    if (call.path().equals("/api/v1/repos/migrate") && once[0]) {
                        once[0] = false;
                        throw new HostRefusal(69, "fixture repository refused");
                    }
                };
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories).hasSize(2);
        assertThat(row(f.item().get("repositories_why"))).hasSize(1);
    }

    @Test
    void aProjectListingOtherRepositoriesClosesItsOwnMergeTeam() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"))));
        f.run();
        assertThat(f.rules.get("main").get("merge_whitelist_teams")).asList().isEmpty();
    }

    @Test
    void aProjectWithNoRepositoryRowsKeepsItsOwnAsAPlaceOfWork() throws Exception {
        f.run();
        assertThat(f.rules.get("main").get("merge_whitelist_teams"))
                .asList()
                .containsExactly("Owners", "developers");
    }

    @Test
    void removingTheRepositoryListRestoresMergesInTheProjectsOwn() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"))));
        f.run();
        f.project.centralMain = "b".repeat(40);
        repositories(new LinkedHashMap<>());
        f.run();
        assertThat(f.rules.get("main").get("merge_whitelist_teams"))
                .asList()
                .containsExactly("Owners", "developers");
    }

    @Test
    void aRowWithTheOwnCentralRepositoryKeepsOwnMerges() throws Exception {
        repositories(new LinkedHashMap<>(Map.of("own", required())));
        f.run();
        assertThat(f.rules.get("main").get("merge_whitelist_teams"))
                .asList()
                .containsExactly("Owners", "developers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code", "own", "different"})
    void theCentralAddressDeterminesWhetherARowIsOwn(String name) {
        final var p = object(f.project());
        final var file =
                fields(
                        "repositories",
                        fields(
                                name,
                                fields(
                                        "central",
                                        name.equals("different")
                                                ? "https://forge.example.org/acme/other.git"
                                                : name.equals("code")
                                                        ? null
                                                        : ProjectFixture.ADDRESS)));
        assertThat(FollowProjects.noTaskInOwn(p, file)).isEqualTo(!name.equals("own"));
    }

    @Test
    void theHostCardsSelectionLimitsMigrations() throws Exception {
        f.project().put("repositories", List.of("extra"));
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"),
                                "third",
                                Map.of(
                                        "review",
                                        "required",
                                        "central",
                                        "https://forge.example.org/acme/third.git"))));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories)
                .containsKey("alpha/extra")
                .doesNotContainKey("alpha/third");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void absentOrEmptySelectionsCarryAllRepositories(boolean empty) throws Exception {
        if (empty) {
            f.project().put("repositories", List.of());
        }
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "extra",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"))));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.project.repositories).containsKey("alpha/extra");
    }

    @Test
    void aSelectionMissingFromTheSignedFileIsNamed() throws Exception {
        f.project().put("repositories", List.of("missing"));
        f.run();
        assertThat(f.item().get("why"))
                .asString()
                .contains("host's card names repositories the project file does not list: missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"string", "duplicate", "capital"})
    void malformedSelectionsRefuseOnlyTheirProject(String damage) throws Exception {
        f.project()
                .put(
                        "repositories",
                        damage.equals("string")
                                ? "extra"
                                : damage.equals("duplicate")
                                        ? List.of("extra", "extra")
                                        : List.of("Extra"));
        f.run();
        assertThat(row(f.applied().get("project_refusals"))).containsKey("alpha");
    }

    @Test
    void reviewUsesTheCentralRepositoryNameRatherThanItsAlias() throws Exception {
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "alias",
                                Map.of("review", "required", "central", ProjectFixture.ADDRESS))));
        f.run();
        assertThat(f.rules.get("main")).containsEntry("required_approvals", 1L);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "push_whitelist_usernames",
                "merge_whitelist_usernames",
                "approvals_whitelist_username"
            })
    void signedPolicyRemovesIndividualBypasses(String field) throws Exception {
        f.run();
        f.rules.get("main").put(field, List.of("stranger"));
        f.run();
        assertThat(f.rules.get("main").get(field)).asList().isEmpty();
    }

    @Test
    void forgeNullAndEmptyUserListsAreEquivalent() throws Exception {
        f.run();
        for (String field :
                List.of(
                        "push_whitelist_usernames",
                        "merge_whitelist_usernames",
                        "approvals_whitelist_username")) {
            f.rules.get("main").put(field, null);
        }
        final long before = mutations();
        f.run();
        assertThat(mutations()).isEqualTo(before);
    }

    @Test
    void verifyRunsAsTheCopyOwnerWithNoNetwork() throws Exception {
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains("verify")))
                .singleElement()
                .satisfies(
                        a ->
                                assertThat(a)
                                        .contains(
                                                "User=forge",
                                                "PrivateNetwork=yes",
                                                "PrivateTmp=yes",
                                                "Environment=HOME=/tmp"));
    }

    @Test
    void movingMainWithoutTheForgeNotificationStillCountsAsAdvanced() throws Exception {
        f.project.localMain = "b".repeat(40);
        f.advanceState = "moved-unseen";
        f.run();
        assertThat(f.item())
                .containsEntry("advanced", f.project.centralMain)
                .doesNotContainKey("advance_refused");
        assertThat(f.journal.toString())
                .contains("main moved, and the forge's pages may show it late");
    }

    @Test
    void aNewOptionalReviewChangesTheRuleAndIsSaidOnce() throws Exception {
        f.verified.put("project_review", "required");
        f.run();
        final long before = mutations();
        f.project.centralMain = "b".repeat(40);
        f.verified.put("project_review", "optional");
        f.journal.getBuffer().setLength(0);
        f.run();
        f.run();
        assertThat(mutations()).isEqualTo(before + 1);
        assertThat(
                        f.journal
                                .toString()
                                .lines()
                                .filter(line -> line.contains("main's review is optional")))
                .hasSize(1);
    }

    @Test
    void aFailedProtectionNamesItsRepositoryAndDoesNotSpareTheNext() throws Exception {
        f.project.repositories.put(
                "alpha/extra",
                new LinkedHashMap<>(
                        Map.of(
                                "name",
                                "extra",
                                "original_url",
                                "https://forge.example.org/acme/extra.git")));
        f.forgeBefore =
                call -> {
                    if (call.path().contains("/repos/alpha/code/branch_protections")) {
                        throw new HostRefusal(69, "fixture protection unavailable");
                    }
                };
        assertThatThrownBy(f::run)
                .hasMessageContaining("alpha/code: main's protection could not be set");
        assertThat(f.project.calls)
                .anyMatch(
                        call ->
                                call.method().equals("POST")
                                        && call.path()
                                                .contains("/repos/alpha/extra/branch_protections"));
    }

    @Test
    void anOptionalAliasOpensOnlyItsCentralRepository() throws Exception {
        f.project.repositories.put(
                "alpha/extra",
                new LinkedHashMap<>(
                        Map.of(
                                "name",
                                "extra",
                                "original_url",
                                "https://forge.example.org/acme/extra.git")));
        f.verified.put("project_review", "required");
        repositories(
                new LinkedHashMap<>(
                        Map.of(
                                "alias",
                                Map.of(
                                        "review",
                                        "optional",
                                        "central",
                                        "https://forge.example.org/acme/extra.git"))));
        f.run();
        assertThat(
                        f.project.calls.stream()
                                .filter(
                                        call ->
                                                call.path()
                                                                .contains(
                                                                        "/repos/alpha/code/branch_protections")
                                                        && !call.method().equals("GET")))
                .anySatisfy(
                        call ->
                                assertThat(
                                                ((Number)
                                                                ((Map<?, ?>) call.body())
                                                                        .get("required_approvals"))
                                                        .intValue())
                                        .isEqualTo(1));
        assertThat(
                        f.project.calls.stream()
                                .filter(
                                        call ->
                                                call.path()
                                                                .contains(
                                                                        "/repos/alpha/extra/branch_protections")
                                                        && !call.method().equals("GET")))
                .anySatisfy(
                        call ->
                                assertThat(
                                                ((Number)
                                                                ((Map<?, ?>) call.body())
                                                                        .get("required_approvals"))
                                                        .intValue())
                                        .isZero());
    }

    @Test
    void aFractionalApprovalCountCannotSatisfyTheSignedInteger() throws Exception {
        f.run();
        f.rules.get("main").put("required_approvals", 0.5d);
        f.run();
        assertThat(((Number) f.rules.get("main").get("required_approvals")).intValue()).isZero();
        assertThat(f.rules.get("main").get("required_approvals")).isEqualTo(0L);
    }

    @Test
    void aFractionalGitPortNamesNoLocalCopy() throws Exception {
        ((Map<String, Object>)
                        ((Map<?, ?>) ((Map<?, ?>) f.answer.get("host_card")).get("services"))
                                .get("forge"))
                .put("git_port", 3000.5d);
        f.run();
        assertThat(f.json(FollowFixture.ORG + "projects/alpha.json"))
                .containsEntry("fetch_from", null);
        assertThat(f.item().get("why")).asString().contains("no forge git port");
    }

    @Test
    void aFractionalTeamIdIsRefusedBeforeAddressingItsMembers() throws Exception {
        f.project.pages.put(
                "/orgs/alpha/teams",
                List.of(List.of(Map.of("id", 1.5d, "name", "Owners")), List.of()));
        assertThatThrownBy(f::run).hasMessageContaining("invalid or repeated page entry");
        assertThat(f.project.calls).noneMatch(call -> call.path().contains("/teams/1.5/"));
    }
}
