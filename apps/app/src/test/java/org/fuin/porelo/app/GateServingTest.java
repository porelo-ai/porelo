package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;

class GateServingTest {

    private static Project project(final String securityClass) {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: "uc"
                  security_class: "%s"
                  upstream: "https://forge.example/uc.git"
                image:
                  from: "ubuntu:24.04"
                """
                                .formatted(securityClass)),
                "test");
    }

    @Test
    void anOnlineTasksGatePassesItsPushOnAndAGuardedOnesKeepsIt() {
        // Checked on the command itself: read back from a start's record, it was there only where a
        // 'porelo' could be started, and a runner without one recorded no gate at all.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"), "127.0.0.1", 9418, state, "shell", "uc", null))
                .contains("--pass-on")
                .containsSequence("--ref", "refs/porelo/incoming/shell")
                .containsSequence("--repository", "uc")
                .noneMatch(word -> word.contains("ssh-agent"));
        assertThat(
                        GateWiring.serving(
                                project("guarded"), "127.0.0.1", 9418, state, "shell", null, null))
                .doesNotContain("--pass-on")
                .doesNotContain("--repository");
    }

    @Test
    void anOnlineTasksGateIsToldWhenNothingFollowsTheBuildsOfWhatItPassesOn() {

        // On the recorded command line, so a resumed task's pushes are answered as its first.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                false))
                .contains("--pass-on", "--no-builds");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                true))
                .as("the build watch reports on it")
                .doesNotContain("--no-builds");
        assertThat(
                        GateWiring.serving(
                                project("guarded"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                false))
                .as("a held push is answered as held, whatever follows the builds")
                .doesNotContain("--no-builds");
    }

    @Test
    void anOnlineTasksGateWhoseBuildWatchDidNotStartIsToldThatNothingReportsItsPushes() {

        // The project named builds, the watch did not start, and the gate still let the agent
        // wait for a report that never came.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                true,
                                false))
                .contains("--pass-on", GateWiring.BUILDS_UNWATCHED)
                .doesNotContain("--no-builds");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                true,
                                true))
                .as("the build watch reports on it")
                .doesNotContain(GateWiring.BUILDS_UNWATCHED, "--no-builds");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                false,
                                false))
                .as("a project that follows no builds is told so, and nothing more")
                .contains("--no-builds")
                .doesNotContain(GateWiring.BUILDS_UNWATCHED);
        assertThat(
                        GateWiring.serving(
                                project("guarded"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                true,
                                false))
                .as("a held push is answered as held, whatever follows the builds")
                .doesNotContain(GateWiring.BUILDS_UNWATCHED);
    }

    @Test
    void aResumedTasksGateIsToldWhetherItsBuildWatchComesBack() {

        // On a resume the same as at a start: the gate follows the watch the resume starts, which a
        // reader deferred at the start may have joined, and which a task started before may lack.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");
        final java.util.List<String> followed =
                GateWiring.serving(
                        project("online"),
                        "127.0.0.1",
                        9418,
                        state,
                        "shell",
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        true,
                        true);
        final java.util.List<String> unwatched =
                GateWiring.serving(
                        project("online"),
                        "127.0.0.1",
                        9418,
                        state,
                        "shell",
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        true,
                        false);
        final TaskHelpers.Helper watch =
                new TaskHelpers.Helper(
                        TaskBuilds.HELPER,
                        java.util.List.of("porelo", "task", "watch-builds"),
                        java.util.Map.of(),
                        TaskHelpers.AFTER);

        assertThat(gate(TaskControl.buildsWatched(helpers(followed))))
                .as("a gate recorded with a watch that is not")
                .contains(GateWiring.BUILDS_UNWATCHED);
        assertThat(gate(TaskControl.buildsWatched(helpers(unwatched, watch))))
                .as("a reader deferred at the start comes back with this resume")
                .doesNotContain(GateWiring.BUILDS_UNWATCHED);
        assertThat(gate(TaskControl.buildsWatched(helpers(followed, watch))))
                .as("a gate whose watch comes back is left as it was")
                .isEqualTo(followed);
        final java.util.List<String> none =
                GateWiring.serving(
                        project("online"),
                        "127.0.0.1",
                        9418,
                        state,
                        "shell",
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        false,
                        true);
        assertThat(gate(TaskControl.buildsWatched(helpers(none))))
                .as("a project that follows no builds is told so, and nothing more")
                .isEqualTo(none);
        final java.util.List<String> held =
                GateWiring.serving(
                        project("guarded"), "127.0.0.1", 9418, state, "shell", null, null);
        assertThat(gate(TaskControl.buildsWatched(helpers(held))))
                .as("a held push is answered as held")
                .isEqualTo(held);
    }

    private static TaskHelpers helpers(
            final java.util.List<String> gate, final TaskHelpers.Helper... others) {
        final java.util.List<TaskHelpers.Helper> all = new java.util.ArrayList<>();
        all.add(
                new TaskHelpers.Helper(
                        "gate",
                        gate,
                        java.util.Map.of("PORELO_GATE_TOKEN", "x"),
                        TaskHelpers.AFTER));
        all.addAll(java.util.List.of(others));
        return new TaskHelpers(java.util.List.copyOf(all));
    }

    private static java.util.List<String> gate(final TaskHelpers helpers) {
        return helpers.helpers().stream()
                .filter(helper -> "gate".equals(helper.name()))
                .findFirst()
                .orElseThrow()
                .command();
    }

    @Test
    void aCertifiedTasksGateJudgesItsArrivalsAndAnUncertifiedOnesIsNotToldTo() {
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                new GateWiring.Certified(
                                        "task:uc:shell", state.resolve("certifiers"))))
                .containsSequence("--principal", "task:uc:shell")
                .containsSequence("--signers", state.resolve("certifiers").toString())
                .containsSequence("--ref", "refs/porelo/incoming/shell");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                null,
                                null))
                .doesNotContain("--principal")
                .doesNotContain("--signers");
    }

    @Test
    void onAHostTheHostPushesTheTasksBranchWhateverTheSchemeNamesIt() {
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "bugfix/porelo/shell",
                                new GateWiring.Certified(
                                        "task:uc:shell", state.resolve("certifiers"), "uc/uc")))
                .contains("--pass-on")
                .containsSequence("--host-push", "uc/uc")
                .doesNotContain("--branch");
    }

    @Test
    void anOnlineTasksGateIsToldItsBranchOnlyWhenItIsNotTodays() {

        // On the recorded command line, so a resumed task passes on where it did; a task started
        // before keeps the command line it had.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "bugfix/porelo/shell"))
                .containsSequence("--branch", "bugfix/porelo/shell");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "porelo/shell"))
                .doesNotContain("--branch");
        assertThat(
                        GateWiring.serving(
                                project("guarded"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "bugfix/porelo/shell"))
                .as("a guarded task's work waits at the gate; nothing is passed on")
                .doesNotContain("--branch");
    }

    @Test
    void anOnlineTasksGateIsToldHowLongItWaitsForTheForge() {

        // As the start settled it, with the repository's own file merged: the gate reads only the
        // project's file, and a resumed task waits as it did.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");
        final Project ninety =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: "uc"
                                  security_class: "online"
                                  upstream: "https://forge.example/uc.git"
                                image:
                                  from: "ubuntu:24.04"
                                limits:
                                  pass_on: "90s"
                                """),
                        "test");

        assertThat(GateWiring.serving(ninety, "127.0.0.1", 9418, state, "shell", null, null))
                .containsSequence("--pass-on-within", "90");
        assertThat(
                        GateWiring.serving(
                                project("online"), "127.0.0.1", 9418, state, "shell", null, null))
                .as("two minutes unless the project file says otherwise")
                .containsSequence("--pass-on-within", "120");
        assertThat(
                        GateWiring.serving(
                                project("guarded"), "127.0.0.1", 9418, state, "shell", null, null))
                .doesNotContain("--pass-on-within");
    }
}
