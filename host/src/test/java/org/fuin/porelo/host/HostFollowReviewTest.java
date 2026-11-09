package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostFollowReviewTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    // py: test_the_matrix_transport_runs_without_its_accounts_bus
    @Test
    void aStoppedAccountNeedsNoBusForShellOrMatrixCleanup() throws Exception {
        assertThat(f.host.isSocket(f.path("/run/user/" + f.accounts.get("bob") + "/bus")))
                .isFalse();
        f.context()
                .as(
                        "bob",
                        List.of("sh", "-c", "rm -f \"$HOME/.config/porelo-host/push/unused.conf\""),
                        new byte[0]);
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
        FollowRooms.expel(f.context(), "bob");
        assertThat(f.commands).anyMatch(a -> a.contains("expel"));
    }

    // py: test_a_service_accounts_podman_without_its_session_bus_is_refused_by_name
    @Test
    void podmanStillRequiresAnExplicitLiveBus() {
        assertThatThrownBy(() -> f.context().as("bob", List.of("podman", "ps"), new byte[0]))
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("no session bus");
    }

    private void dueForPurge() throws Exception {
        f.run();
        f.answer.put("projects", List.of());
        f.run();
        f.now = f.now.plusSeconds(7 * 86400);
    }

    // py: test_vault_cleanup_restores_expiry_and_manager_on_any_exception
    @ParameterizedTest
    @ValueSource(strings = {"expire", "stop"})
    void failedSafetyRestorationNeverClaimsTheRunningAccountIsLocked(String failure)
            throws Exception {
        dueForPurge();
        final boolean[] lifted = {false};
        final boolean[] clearing = {false};
        f.override =
                argv -> {
                    if (argv.contains("--expiredate")
                            && argv.contains("")
                            && argv.getLast().equals("bob")) {
                        lifted[0] = true;
                    }
                    if (argv.contains("clear")) clearing[0] = true;
                    if (clearing[0]
                            && (argv.contains("clear")
                                    || failure.equals("expire")
                                            && argv.contains("--expiredate")
                                            && argv.contains("1")
                                    || failure.equals("stop")
                                            && argv.contains("systemctl")
                                            && argv.contains("stop"))) {
                        return new HostPrograms.Result(1, "");
                    }
                    return null;
                };
        assertThatThrownBy(f::run).isInstanceOf(HostRefusal.class);
        assertThat(lifted[0]).isTrue();
        assertThat(f.read("/etc/porelo-host/people/bob.conf"))
                .contains("locked=false\n", "lock=pending\n");
    }

    // py: test_purge_locks_first_clears_vault_then_removes_account_and_ranges
    @Test
    void purgePreservesPrivateSubordinateIdFileModes() throws Exception {
        dueForPurge();
        for (String kind : List.of("subuid", "subgid")) {
            f.write("/etc/" + kind, "bob:100000:65536\nother:200000:65536\n");
            Files.setAttribute(f.path("/etc/" + kind), "unix:mode", 0600);
        }
        f.run();
        for (String kind : List.of("subuid", "subgid")) {
            assertThat((int) Files.getAttribute(f.path("/etc/" + kind), "unix:mode") & 07777)
                    .isEqualTo(0600);
            assertThat(f.read("/etc/" + kind)).isEqualTo("other:200000:65536\n");
        }
    }

    // py: test_a_members_push_config_is_written_as_that_member_and_goes_with_the_membership
    @Test
    void oneFailedPushConfigRemovalStillAttemptsEveryDepartedMember() throws Exception {
        f.migrated();
        f.run();
        for (Object member : (List<?>) f.project().get("members")) {
            ((Map<String, Object>) member).put("locked", true);
        }
        final List<String> removals = new ArrayList<>();
        f.override =
                argv -> {
                    if (argv.stream().anyMatch(a -> a.startsWith("rm -f"))) {
                        final String login = argv.get(argv.indexOf("-u") + 1);
                        removals.add(login);
                        if (login.equals("bob")) return new HostPrograms.Result(1, "");
                    }
                    return null;
                };
        assertThatThrownBy(f::run)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("could not be removed");
        assertThat(removals).contains("bob", "charlie");
    }

    // py: test_purge_deletes_the_persons_forge_users_after_their_tokens
    // py: test_purge_expels_the_login_even_when_already_locked
    @Test
    void purgeWithoutAMatrixBusDeletesMatrixAndForgeIdentitiesBeforeTheOsAccount()
            throws Exception {
        dueForPurge();
        f.migrated();
        f.accounts.put("porelo-matrix", f.accounts.get("alice") + 1);
        assertThat(f.host.isSocket(f.path("/run/user/" + f.accounts.get("porelo-matrix") + "/bus")))
                .isFalse();
        final List<String> acts = new ArrayList<>();
        f.before =
                argv -> {
                    if (argv.contains("expel")) acts.add("matrix:" + argv.getLast());
                    if (argv.contains("userdel")) acts.add("userdel:" + argv.getLast());
                };
        f.forgeBefore =
                call -> {
                    if (call.method().equals("DELETE")
                            && call.path().matches("/api/v1/admin/users/[^/]+")) {
                        acts.add(
                                "forge:" + call.path().substring(call.path().lastIndexOf('/') + 1));
                    }
                };
        new FollowPeople(f.context()).memberships(HostJson.object(f.applied()));
        assertThat(acts)
                .containsSubsequence("matrix:bob", "forge:bob", "forge:porelo-bob", "userdel:bob");
        assertThat(f.project.users).doesNotContain("bob", "porelo-bob");
        assertThat(f.accounts).doesNotContainKey("bob");
    }

    // py: test_forge_admits_each_identity_once_without_making_or_delivering_tokens
    @Test
    void admissionReadsTheForgeNameWrittenByTheRole() throws Exception {
        f.forge();
        Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        f.accounts.remove("bob");
        f.override =
                argv ->
                        argv.contains("forgejo") && argv.contains("admin") && argv.contains("list")
                                ? new HostPrograms.Result(
                                        0, "ID Username Email\n1 alice alice@host\n")
                                : null;
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
        assertThat(f.commands).anyMatch(a -> a.contains("bob@forge.example.org"));
    }

    // py: test_the_central_questions_of_one_follow_stay_within_a_budget
    @Test
    void anExpiredMeasurementStartsNoFurtherChildAcrossManyRepositories() throws Exception {
        f.migrated();
        f.write(
                FollowFixture.ORG + "applied.json",
                HostJson.write(Map.of("projects", List.of(f.project()))));
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            final String name = "repo" + n;
            entries.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "name",
                                    name,
                                    "address",
                                    ProjectFixture.ADDRESS,
                                    "key_id",
                                    1,
                                    "title",
                                    "porelo host build-host project alpha")));
            f.write(FollowFixture.ORG + "read/projects/alpha/" + name, "private fixture material");
            f.write(
                    FollowFixture.ORG + "read/projects/alpha/" + name + ".pub",
                    ProjectFixture.KEY + "\n");
        }
        f.write(
                FollowFixture.ORG + "read/projects/alpha/repositories.json",
                HostJson.write(entries));
        final var start = f.now;
        final List<List<String>> afterBudget = new ArrayList<>();
        f.before =
                argv -> {
                    if (!f.now.isBefore(start.plusSeconds(120))
                            && !Path.of(argv.getFirst()).getFileName().toString().equals("setfacl"))
                        afterBudget.add(argv);
                    if (argv.contains("ls-remote")) f.now = f.now.plusSeconds(30);
                };
        f.project.gitFailure = 1;
        new ProjectMeasurements(f.project.files, f.runtime, f.clock).measure("forge", "alpha");
        assertThat(Duration.between(start, f.now)).isEqualTo(Duration.ofSeconds(120));
        assertThat(afterBudget).isEmpty();
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .asList()
                .isEmpty();
    }

    // py: test_a_repositorys_main_is_told_against_the_central_forge
    @Test
    void aRepositoryGitTimeoutStillPublishesThePredecessorsCannotSayRow() throws Exception {
        f.migrated();
        f.write(
                FollowFixture.ORG + "applied.json",
                HostJson.write(Map.of("projects", List.of(f.project()))));
        final var timingOut =
                new ProjectRuntime(
                        f.project.files,
                        (argv, input, env, timeout) -> {
                            if (argv.contains("ls-remote"))
                                throw new IOException("fixture Git timeout");
                            return f.runtime.run(argv, input, env, timeout);
                        },
                        Path::of,
                        f.sessionBuses::contains,
                        (path, token) -> {
                            throw new AssertionError("measurement mints no token");
                        });
        new ProjectMeasurements(f.project.files, timingOut, f.clock).measure("forge", "alpha");
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("state", "cannot-say")
                .containsEntry("why", "unreachable");
    }

    @Test
    void aRuntimeTimeoutIsRecordedAndLaterFollowStepsStillRun() throws Exception {
        final List<String> errors = new ArrayList<>();
        final List<String> later = new ArrayList<>();
        HostFollow.attempt(
                errors,
                () -> {
                    throw new IOException("timeout with unsafe child text");
                });
        HostFollow.attempt(errors, () -> later.add("published"));
        assertThat(errors).hasSize(1).noneMatch(s -> s.contains("unsafe child text"));
        assertThat(later).containsExactly("published");
    }

    @Test
    void standaloneMeasurementBoundsTheReaderLookup() throws Exception {
        f.migrated();
        f.run();
        final List<Duration> lookups = new ArrayList<>();
        f.before =
                argv -> {
                    if (Path.of(argv.getFirst()).getFileName().toString().equals("getent"))
                        lookups.add(f.timeouts.getLast());
                };
        new ProjectMeasurements(f.project.files, f.runtime, f.clock).measure("forge", "alpha");
        assertThat(lookups)
                .isNotEmpty()
                .allSatisfy(
                        t ->
                                assertThat(t)
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofSeconds(60)));
    }
}
