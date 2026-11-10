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

    @Test
    void historicalOwnershipCannotAuthorizeASurvivingDifferentIdWithoutASidecar() throws Exception {
        // A claim must not lend a replacement's base branch when ordinary ownership is incomplete.
        final PoreloContext context = context();
        final String container = "porelo-uc-fix";
        TaskIdentityFixture.record(context.paths(), container, "uc", "fix", "a".repeat(64));
        new TaskReservations(context).mutate(container, () -> Boolean.TRUE);
        final Path state = context.paths().tasks().taskRecord(container);
        Files.delete(state.resolve("sidecar.json"));
        org.fuin.porelo.wire.Sidecar.recordId(state, "b".repeat(64));
        new TaskBase("replacement", "FROM").writeTo(state);

        assertThatThrownBy(
                        () ->
                                ApprovalBranch.of(
                                        context,
                                        project(""),
                                        project("").ownRepository(),
                                        "fix",
                                        null))
                .as("historical metadata cannot override a contradictory surviving id")
                .isInstanceOf(ProjectException.class);
        assertThat(state.resolve(TaskBase.FILE)).as("refusal changes no task files").exists();
    }

    @Test
    void approvalAndReviewUseTheRetainedActualOwner() throws Exception {
        // Rebuilding the old spelling can approve another project's starting branch.
        final PoreloContext context = context();
        final Project project = project("");
        TaskIdentityFixture.record(context.paths(), "porelo-uc-fix", "uc", "fix", "a".repeat(64));
        TaskIdentityFixture.record(
                context.paths(), "porelo-uc_other", "uc", "other", "b".repeat(64));
        new TaskBase("release", "FROM")
                .writeTo(context.paths().tasks().taskRecord("porelo-uc-fix"));
        new TaskBase("develop", "FROM")
                .writeTo(context.paths().tasks().taskRecord("porelo-uc_other"));

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "other", null))
                .as("approval uses the encoded task's retained branch")
                .isEqualTo("develop");
        assertThat(TaskBase.reviewAgainst(context, project, "fix"))
                .as("the old task still uses its original branch")
                .isEqualTo("refs/heads/release");
        assertThat(TaskBase.reviewAgainst(context, project, "other"))
                .as("review reads the same actual record as approval")
                .isEqualTo("refs/heads/develop");
    }

    private static final String HEAD =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
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
        TaskIdentityFixture.record(context.paths(), "porelo-uc-fix", "uc", "fix", "a".repeat(64));
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
    void withoutABranchSchemeApprovalFollowsTheRecordedStartingBranch() throws Exception {
        // Work started on develop must not be forwarded onto main by default.
        final PoreloContext context = context();
        final Project project = project("");
        TaskIdentityFixture.record(context.paths(), "porelo-uc-fix", "uc", "fix", "a".repeat(64));
        final Path state =
                Files.createDirectories(
                        context.paths()
                                .tasks()
                                .containerState(
                                        org.fuin.porelo.runtime.ContainerName.of(project, "fix")));
        new TaskBase("develop", "CHECKOUT").writeTo(state);
        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .as("approval retains the line of development selected at start")
                .isEqualTo("develop");
    }

    // On the host, a direct approval to the starting branch would bypass the forge's review.
    @Test
    void aHostUsesTheTasksBranchOnlyForItsOwnForgeAndAnExplicitBranchStillWins() throws Exception {
        final PoreloContext context = context();
        final Project project =
                project(
                        """
                repositories:
                  repo:
                    upstream: "http://127.0.0.1:3001/acme/repo.git"
                """);
        TaskIdentityFixture.record(context.paths(), "porelo-uc-fix", "uc", "fix", "a".repeat(64));
        final var repository = project.repository("repo");
        final Path state =
                Files.createDirectories(
                        context.paths()
                                .tasks()
                                .containerState(
                                        org.fuin.porelo.runtime.ContainerName.of(project, "fix")));
        new TaskBase("develop", "FROM").writeTo(state);
        final Path conf = dir.resolve("forge.conf");
        Files.writeString(conf, "git_port=3001\n");

        assertThat(ApprovalBranch.of(context, project, repository, "fix", null, conf))
                .as("the host's own forge takes a task branch for review")
                .isEqualTo("porelo/fix");
        assertThat(ApprovalBranch.of(context, project, repository, "fix", "release", conf))
                .as("an explicit branch wins")
                .isEqualTo("release");
        Files.writeString(conf, "git_port=3002\n");
        assertThat(ApprovalBranch.of(context, project, repository, "fix", null, conf))
                .as("another loopback port is not the host's forge")
                .isEqualTo("develop");
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
