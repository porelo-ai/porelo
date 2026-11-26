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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        // Rebuilding the old spelling can approve another task's branch or review its base.
        final PoreloContext context = context();
        final Project project = project("");
        TaskIdentityFixture.record(context.paths(), "porelo-uc-fix", "uc", "fix", "a".repeat(64));
        TaskIdentityFixture.record(
                context.paths(), "porelo-uc_other", "uc", "other", "b".repeat(64));
        new TaskBase("release", "FROM")
                .writeTo(context.paths().tasks().taskRecord("porelo-uc-fix"));
        new TaskBase("develop", "FROM")
                .writeTo(context.paths().tasks().taskRecord("porelo-uc_other"));
        profile(context.paths().tasks().taskRecord("porelo-uc-fix"), "porelo/fix");
        profile(context.paths().tasks().taskRecord("porelo-uc_other"), "feature/other");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "other", null))
                .as("approval uses the encoded task's retained branch")
                .isEqualTo("feature/other");
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
        profile(
                Files.createDirectories(
                        context.paths()
                                .tasks()
                                .containerState(
                                        org.fuin.porelo.runtime.ContainerName.of(project, "fix"))),
                forgeBranch);
    }

    private static void profile(final Path state, final String forgeBranch) throws Exception {
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
    void withoutABranchBlockApprovalGoesToTheTasksBranchNotItsStartingBranch() throws Exception {
        // Work started on develop goes up beside it for review, never onto it by default.
        final PoreloContext context = context();
        final Project project = project("");
        recorded(context, project, "porelo/fix");
        new TaskBase("develop", "CHECKOUT")
                .writeTo(
                        context.paths()
                                .tasks()
                                .containerState(
                                        org.fuin.porelo.runtime.ContainerName.of(project, "fix")));

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .as("the task's branch, not the starting branch")
                .isEqualTo("porelo/fix");
        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", "develop"))
                .as("the starting branch is reached by naming it")
                .isEqualTo("develop");
    }

    @Test
    void aTaskStartedWithANamedBranchApprovesOntoThatBranch() throws Exception {
        final PoreloContext context = context();
        final Project project = project("");
        recorded(context, project, "feature/login");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .as("the branch the task's start recorded")
                .isEqualTo("feature/login");
    }

    // The host's own forge and any other upstream take the same default, so a plain git remote
    // never receives work on its starting branch unasked.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:3001/acme/repo.git",
                "https://forge.example.org/acme/repo.git",
                "git@example.org:acme/repo.git",
                "/srv/git/repo.git"
            })
    void everyUpstreamTakesTheTasksBranchAndANamedBranchStillWins(final String upstream)
            throws Exception {
        final PoreloContext context = context();
        final Project project =
                project("repositories:\n  repo:\n    upstream: \"" + upstream + "\"\n");
        final var repository = project.repository("repo");
        final Path conf = context.paths().hostDirectory().resolve("forge.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "git_port=3001\n");

        assertThat(ApprovalBranch.of(context, project, repository, "fix", null))
                .as("the task's branch on %s", upstream)
                .isEqualTo("porelo/fix");
        assertThat(ApprovalBranch.of(context, project, repository, "fix", "main"))
                .as("an explicit branch wins on %s", upstream)
                .isEqualTo("main");
    }

    @Test
    void aPushWhoseNameIsNoTaskNameNeedsABranchNamed() {
        // Without a task's name there is no task branch, and falling back to main would be silent.
        final PoreloContext context = context();
        final Project project = project("");

        assertThatThrownBy(
                        () ->
                                ApprovalBranch.of(
                                        context, project, project.ownRepository(), "Fix_it", null))
                .as("no default without a task's name")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("name the branch");
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
    void aProjectWithoutABlockApprovesOntoTheTasksBranchUnlessOneIsNamed() {
        // Pending work outlives its task's records: its push name is still the task's name.
        final PoreloContext context = context();
        final Project project = project("");

        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", null))
                .as("the task's branch, never main")
                .isEqualTo("porelo/fix");
        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "fix", "release"))
                .as("any branch named")
                .isEqualTo("release");
        assertThat(ApprovalBranch.offerable(project, project.ownRepository(), "main-2"))
                .as("without a block any next branch may be offered")
                .isTrue();
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
