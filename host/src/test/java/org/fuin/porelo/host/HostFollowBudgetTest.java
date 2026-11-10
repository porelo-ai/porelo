package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HostFollowBudgetTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    // py: test_the_central_questions_of_one_follow_stay_within_a_budget
    @Test
    void centralQuestionsShareOneBudgetAcrossProjects() throws Exception {
        f.migrated();
        f.run();
        final var applied = f.applied();
        final var beta = FollowFixture.project("beta");
        applied.put("projects", List.of(f.project(), beta));
        f.write(FollowFixture.ORG + "applied.json", HostJson.write(applied));
        f.project.directories("/etc/porelo-host/organization/read/projects/beta");
        f.write(
                FollowFixture.ORG + "read/projects/beta/repositories.json",
                f.read(FollowFixture.ORG + "read/projects/alpha/repositories.json"));
        f.write(FollowFixture.ORG + "read/projects/beta/code", "fixture private material");
        f.write(FollowFixture.ORG + "read/projects/beta/code.pub", ProjectFixture.KEY + "\n");
        f.write(
                FollowFixture.ORG + "host-projects.json",
                HostJson.write(
                        Map.of(
                                "alpha",
                                Map.of("state", "carried", "migrated", true),
                                "beta",
                                Map.of("state", "carried", "migrated", true))));
        final int[] calls = {0};
        f.before =
                argv -> {
                    if (argv.contains("ls-remote")) {
                        calls[0]++;
                        f.now = f.now.plusSeconds(121);
                    }
                };
        final var projects =
                new FollowProjects(
                        f.context(),
                        (origin, token) -> {
                            throw new AssertionError("measurement mints no token");
                        },
                        () -> HostJson.object(f.progress()));
        projects.measure(HostJson.object(applied));
        assertThat(calls[0]).isEqualTo(1);
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .asList()
                .isEmpty();
        assertThat(f.json(FollowFixture.ORG + "central/beta.json").get("repositories"))
                .asList()
                .isEmpty();
    }

    // A waiting project needs a published status even before there is a copy to measure.
    @Test
    void measurementsPublishUnmigratedProgressAndPruneDepartedProjects() throws Exception {
        f.run();
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json"))
                .containsEntry("state", "carried");
        f.migrated();
        f.run();
        assertThat(Files.exists(f.path(FollowFixture.ORG + "central/alpha.json"))).isTrue();
        f.write(FollowFixture.ORG + "host-projects.json", "{}");
        final var applied = HostJson.object(f.applied());
        new FollowProjects(
                        f.context(),
                        (origin, token) -> {
                            throw new AssertionError();
                        },
                        () -> HostJson.object(f.progress()))
                .measure(applied);
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .asList()
                .isEmpty();
        applied.put("projects", List.of());
        new FollowProjects(
                        f.context(),
                        (origin, token) -> {
                            throw new AssertionError();
                        },
                        () -> HostJson.object(f.progress()))
                .measure(applied);
        assertThat(Files.exists(f.path(FollowFixture.ORG + "central/alpha.json"))).isFalse();
    }

    // py: test_a_helper_that_cannot_say_leaves_no_repository_and_does_not_fail_the_follow
    @Test
    void aFailedCentralQuestionPublishesAnEmptyMeasurement() throws Exception {
        f.migrated();
        f.before =
                argv -> {
                    if (argv.contains("test") && argv.contains("-d")) {
                        throw new HostRefusal(69, "fixture central helper failed");
                    }
                };
        f.run();
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .asList()
                .isEmpty();
        assertThat(f.run()).containsEntry("refused", null);
    }

    // py: test_reading_files_shares_one_budget_and_comes_after_members_are_taken_away
    @Test
    void projectFileChildrenUseOneMinuteAfterMembershipRemoval() throws Exception {
        f.migrated();
        f.run();
        row(((List<?>) f.project().get("members")).getFirst()).put("locked", true);
        final List<Duration> seen = new ArrayList<>();
        f.before =
                argv -> {
                    if (argv.contains("ls-remote")) {
                        assertThat(Files.exists(f.path(FollowFixture.ORG + "persons/bob.json")))
                                .isFalse();
                        seen.add(f.timeouts.getLast());
                        f.now = f.now.plusSeconds(61);
                        throw new HostRefusal(69, "fixture read exceeded its timeout");
                    }
                };
        f.run();
        assertThat(seen.getFirst()).isLessThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(f.item().get("file_refused")).isNotNull();
    }

    // py: test_the_root_project_child_inherits_the_lock_and_receives_the_token_only_on_stdin
    @Test
    void inProcessProjectCallsKeepTheOuterFlockHeldWithoutLaunchingAHelper() throws Exception {
        f.migrated();
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final CountDownLatch trying = new CountDownLatch(1);
        final List<Future<Boolean>> waiters = new ArrayList<>();
        f.forgeBefore =
                call -> {
                    if (waiters.isEmpty()) {
                        waiters.add(
                                workers.submit(
                                        () -> {
                                            trying.countDown();
                                            try (var lock =
                                                    HostConfigurationLock.acquire(
                                                            f.path("/etc/porelo-host/forge.conf"),
                                                            false)) {
                                                return true;
                                            }
                                        }));
                        try {
                            assertThat(trying.await(2, TimeUnit.SECONDS)).isTrue();
                            assertThatThrownBy(
                                            () -> waiters.getFirst().get(50, TimeUnit.MILLISECONDS))
                                    .isInstanceOf(TimeoutException.class);
                        } catch (InterruptedException ex) {
                            throw new AssertionError(ex);
                        }
                    }
                };
        try {
            f.refresh(ProjectFixture.CENTRAL_TOKEN);
            assertThat(waiters.getFirst().get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            workers.shutdownNow();
        }
        assertThat(f.commands).noneMatch(argv -> argv.getFirst().contains("porelo-host"));
        assertThat(f.commands.toString()).doesNotContain(ProjectFixture.CENTRAL_TOKEN);
    }

    @Test
    void aWholeFollowDeadlineStopsFurtherChildrenWithoutRefusingTheOrganization() throws Exception {
        f.before =
                argv -> {
                    if (argv.contains("fetch")) {
                        f.now = f.now.plusSeconds(301);
                    }
                };
        assertThatThrownBy(f::run)
                .isInstanceOf(HostRefusal.class)
                .extracting("exitCode")
                .isEqualTo(69);
        assertThat(Files.exists(f.path(FollowFixture.ORG + "refused.json"))).isFalse();
        assertThat(f.commands).filteredOn(argv -> argv.contains("getent")).isEmpty();
    }

    @Test
    void anUnboundedChildStillConsumesTheRemainingWholeFollowBudget() throws Exception {
        final var bounded =
                f.runtime
                        .until(f.clock, f.now.plusSeconds(300))
                        .until(f.clock, f.now.plusSeconds(120));
        f.now = f.now.plusSeconds(110);
        bounded.run(
                List.of("/usr/bin/getent", "passwd", "alice"),
                new byte[0],
                ProjectRuntime.SAFE_ENV,
                Duration.ZERO);
        assertThat(f.timeouts.getLast())
                .as("no per-command timeout cannot bypass the enclosing budget")
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void measurementCannotExtendTheWholeFollowEnvelope() throws Exception {
        f.migrated();
        f.run();
        final var context = f.context().until(f.now.plusSeconds(20));
        final List<Duration> seen = new ArrayList<>();
        f.before =
                argv -> {
                    if (argv.contains("ls-remote")) {
                        seen.add(f.timeouts.getLast());
                    }
                };
        new FollowProjects(
                        context,
                        (origin, token) -> {
                            throw new AssertionError("measurement mints no token");
                        },
                        () -> HostJson.object(f.progress()))
                .measure(HostJson.object(f.applied()));
        assertThat(seen)
                .isNotEmpty()
                .allSatisfy(
                        timeout ->
                                assertThat(timeout)
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofSeconds(20)));
    }

    // py: test_a_repository_named_in_capitals_keeps_its_answer
    @Test
    void centralMeasurementKeepsTheForgesRepositorySpelling() throws Exception {
        f.migrated();
        f.project.repositories.clear();
        f.project.repositories.put(
                "alpha/Code",
                new LinkedHashMap<>(
                        Map.of("name", "Code", "original_url", ProjectFixture.ADDRESS)));
        f.run();
        assertThat((List<?>) f.json(FollowFixture.ORG + "central/alpha.json").get("repositories"))
                .anySatisfy(v -> assertThat(row(v)).containsEntry("repository", "Code"));
    }
}
