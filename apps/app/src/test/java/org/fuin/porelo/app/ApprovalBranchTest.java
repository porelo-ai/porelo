package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a guarded task's approved work goes, and the branch a task records: in a project with a
 * branch block only onto branches the forge's rules cover.
 */
class ApprovalBranchTest {

    private static final String HEAD =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static final String BLOCK =
            """
            branch:
              template: "bugfix/porelo/{task}"
              pattern: "^(feature|bugfix)/porelo/[a-z0-9-]+$"
            """;

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(final String more) {
        return ProjectReader.read(new StringReader(HEAD + more), "test");
    }

    private void recorded(
            final PoreloContext context, final Project project, final String forgeBranch)
            throws Exception {
        final Path state =
                Files.createDirectories(
                        context.paths()
                                .tasks()
                                .containerState(
                                        org.fuin.porelo.runtime.ContainerName.of(project, "fix")));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "a",
                        TaskMode.SHELL,
                        null,
                        "refs/porelo/incoming/fix",
                        "2026-10-28T00:00:00Z",
                        "deny",
                        null,
                        null,
                        null,
                        forgeBranch)
                .writeTo(state);
    }

    @Test
    void inAProjectWithABlockAnApprovalWithoutABranchGoesToTheTasksOwn() throws Exception {
        final PoreloContext context = context();
        final Project project = project(BLOCK);
        recorded(context, project, "bugfix/porelo/fix");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .isEqualTo("bugfix/porelo/fix");
    }

    @Test
    void inAProjectWithABlockAnApprovalOntoMainIsRefusedNamingThePattern() {
        final PoreloContext context = context();
        final Project project = project(BLOCK);

        assertThatThrownBy(
                        () ->
                                ApprovalBranch.of(
                                        context, project, project.ownRepository(), "fix", "main"))
                .isInstanceOf(ApprovalBranch.Refused.class)
                .hasMessageContaining("(feature|bugfix)");
        assertThat(
                        ApprovalBranch.of(
                                context,
                                project,
                                project.ownRepository(),
                                "fix",
                                "feature/porelo/other"))
                .isEqualTo("feature/porelo/other");
        assertThat(ApprovalBranch.offerable(project, project.ownRepository(), "main-2"))
                .as("the next free branch is offered only where the pattern accepts it")
                .isFalse();
    }

    @Test
    void inAProjectWithABlockATaskWhoseBranchIsNotRecordedNeedsOneNamed() {
        final PoreloContext context = context();
        final Project project = project(BLOCK);

        assertThatThrownBy(
                        () ->
                                ApprovalBranch.of(
                                        context, project, project.ownRepository(), "fix", ""))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("name the branch");
    }

    @Test
    void aBlockThatSaysWhatTheDefaultSaysIsABlockStill() throws Exception {

        // Written, it means what the file says: approvals go to the task's own branch, and a
        // branch named must match. Read as no block, 'main' would go through unchecked.
        final PoreloContext context = context();
        final Project project =
                project(
                        """
                        branch:
                          template: "porelo/{task}"
                          pattern: "^porelo/.+$"
                        """);
        recorded(context, project, "porelo/fix");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .isEqualTo("porelo/fix");
        assertThatThrownBy(
                        () ->
                                ApprovalBranch.of(
                                        context, project, project.ownRepository(), "fix", "main"))
                .isInstanceOf(ApprovalBranch.Refused.class);
    }

    @Test
    void aProjectWithoutABlockApprovesAsBefore() {
        final PoreloContext context = context();
        final Project project = project("");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .as("the caller's own default, main, applies")
                .isNull();
        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", "release"))
                .as("any branch named")
                .isEqualTo("release");
        assertThat(ApprovalBranch.offerable(project, project.ownRepository(), "main-2")).isTrue();
    }

    @Test
    void aTaskStartedBeforeTheBranchWasRecordedReadsAsTodaysName() throws Exception {

        // Its gate passes on, and its approval goes, under the name it had when it started.
        final Path state = Files.createDirectories(dir.resolve("old"));
        new TaskProfile(
                        5,
                        "a",
                        TaskMode.SHELL,
                        null,
                        "refs/porelo/incoming/fix",
                        "2026-10-27T00:00:00Z",
                        "deny")
                .writeTo(state);

        final TaskProfile read = java.util.Objects.requireNonNull(TaskProfile.readFrom(state));
        assertThat(read.forgeBranch()).isNull();
        assertThat(read.forgeBranchOrToday()).isEqualTo("porelo/fix");
    }
}
