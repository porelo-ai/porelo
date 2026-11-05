package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards against selecting, resuming or reviewing a different line of development. */
class TaskBaseTest {
    @TempDir Path dir;
    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private Project project(String extra) {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: app
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                """
                                + extra),
                "test");
    }

    private String git(Path in, String... args) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "-C",
                                in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(
                        new Command(
                                all,
                                in,
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1"),
                                null))
                .standardOutput()
                .strip();
    }

    private Path checkout() throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("checkout"));
        git(checkout, "init", "-q", "-b", "main");
        Files.writeString(
                checkout.resolve("project.yml"),
                """
                project:
                  name: app
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                """);
        git(checkout, "add", ".");
        git(checkout, "commit", "-q", "-m", "initial");
        git(checkout, "branch", "develop");
        return checkout;
    }

    @Test
    void explicitThenCheckoutThenProjectThenDefaultDecideTheStartingBranch() throws Exception {
        final Path checkout = checkout();
        final Path mirror = dir.resolve("mirror.git");
        git(dir, "clone", "-q", "--mirror", checkout.toString(), mirror.toString());
        git(checkout, "checkout", "-q", "develop");
        final Project project = project("base_branch: main\n");
        final PoreloContext context = context();
        final TaskBase requested =
                TaskBase.requested(context, project, project.ownRepository(), checkout, null);
        assertThat(TaskBase.select(context, project, mirror, requested))
                .as("a checkout on develop is not redirected to main by project settings")
                .isEqualTo(new TaskBase("develop", "CHECKOUT"));
        assertThat(
                        TaskBase.select(
                                context,
                                project,
                                mirror,
                                TaskBase.requested(
                                        context,
                                        project,
                                        project.ownRepository(),
                                        checkout,
                                        "main")))
                .as("an explicit choice overrides the checkout")
                .isEqualTo(new TaskBase("main", "FROM"));
        assertThat(TaskBase.select(context, project("base_branch: develop\n"), mirror, null))
                .as("without a checkout the configured branch is used")
                .isEqualTo(new TaskBase("develop", "PROJECT"));
        assertThat(TaskBase.select(context, project(""), mirror, null))
                .as("only an unconfigured start uses the repository default")
                .isEqualTo(new TaskBase("main", "DEFAULT"));
    }

    @Test
    void detachedAndUnpublishedBranchesRefuseBeforeTheWorkspaceIsMade() throws Exception {
        final Path checkout = checkout();
        final Path mirror = dir.resolve("mirror.git");
        git(dir, "clone", "-q", "--mirror", checkout.toString(), mirror.toString());
        git(checkout, "checkout", "-q", "--detach");
        final Project project = project("");
        final PoreloContext context = context();
        assertThatThrownBy(
                        () ->
                                TaskBase.requested(
                                        context, project, project.ownRepository(), checkout, null))
                .as("a detached checkout cannot silently choose another branch")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("detached")
                .hasMessageContaining("--from");
        assertThat(
                        TaskBase.requested(
                                context, project, project.ownRepository(), checkout, "develop"))
                .as("the person can resolve a detached checkout explicitly")
                .isEqualTo(new TaskBase("develop", "FROM"));
        git(checkout, "checkout", "-q", "-b", "local-only");
        final TaskBase local =
                TaskBase.requested(context, project, project.ownRepository(), checkout, null);
        assertThatThrownBy(() -> TaskBase.select(context, project, mirror, local))
                .as("a local branch cannot silently become main")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("local-only")
                .hasMessageContaining("not in the mirror");
    }

    @Test
    void anUnrelatedCheckoutDoesNotChooseAnotherRepositoriesBranch() throws Exception {
        final Path checkout = checkout();
        git(checkout, "checkout", "-q", "develop");
        final Project other =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: other
                  security_class: guarded
                  upstream: https://example.org/other.git
                image:
                  from: ubuntu:24.04
                """),
                        "test");
        assertThat(TaskBase.requested(context(), other, other.ownRepository(), checkout, null))
                .as("the caller's unrelated checkout must not change a selected repository")
                .isNull();
    }

    @Test
    void aRestartRetainsTheReviewBase() throws Exception {
        final PoreloContext context = context();
        final String container = "porelo-app-fix";
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState(container));
        new TaskBase("develop", "CHECKOUT").writeTo(state);
        new TaskState(context).save(container);
        Files.delete(state.resolve(TaskBase.FILE));
        assertThat(TaskBase.reviewAgainst(context, project(""), "fix"))
                .as("review after a restart compares with the original branch")
                .isEqualTo("refs/heads/develop");
    }

    @Test
    void invalidBranchNamesAreRefusedRatherThanUsedAsRevisions() {
        assertThatThrownBy(() -> project("base_branch: HEAD~1\n"))
                .as("configuration accepts a branch, not an arbitrary revision")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("base_branch");
        assertThatThrownBy(() -> TaskBase.named("--upload-pack=elsewhere"))
                .as("an explicit branch cannot become a git option")
                .isInstanceOf(ProjectException.class);
    }
}
