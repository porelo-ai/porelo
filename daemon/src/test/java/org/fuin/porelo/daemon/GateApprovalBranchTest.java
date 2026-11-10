package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branch the daemon's {@code Approve} and {@code PendingBundle} approve onto: in a project with
 * a branch block, one the forge's pattern accepts, and the task's own when none is named.
 */
class GateApprovalBranchTest {

    @TempDir Path dir;

    // Clients need an additive field they can read without requiring every upstream to be a host.
    @Test
    void aDaemonPreflightRefusesChangingAnExistingShortSpelling() throws Exception {
        // Preflight must not promise that resuming can change an immutable spelling.
        final PoreloContext context = context("");
        final String id = "a".repeat(64);
        ((FakeCommandRunner) context.runner())
                .answering(
                        "ps", "porelo-uc-work\tUp 1 minute\t\t\tuc\tguarded\tuc\t\t" + id + "\n");
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-work"), id);
        final var result =
                TaskMethods.canStart(
                        context,
                        Map.of("task", "work", "short", "x", "noGate", true),
                        dir.resolve("project.yml"));
        assertThat(result.outcome())
                .as("existing tasks keep their original spelling")
                .isEqualTo(org.fuin.porelo.app.StartCheck.Outcome.BAD_TASK_NAME);
        assertThat(result.detail()).as("a new task is the available remedy").contains("new task");
    }

    @Test
    void aDaemonPreflightRefusesTheSameInvalidShortNameAsStart() throws Exception {
        // CanStart must not offer a start whose spelling will be refused after side effects.
        final PoreloContext context = context("");
        final var result =
                TaskMethods.canStart(
                        context,
                        Map.of("task", "work", "short", "Upper", "noGate", true),
                        dir.resolve("project.yml"));
        assertThat(result.outcome())
                .as("the shared rule refuses the spelling")
                .isEqualTo(org.fuin.porelo.app.StartCheck.Outcome.BAD_TASK_NAME);
        assertThat(result.detail())
                .as("the answer identifies the field and remedy")
                .contains("short", "lowercase");
    }

    @Test
    void aDaemonStartPassesTheShortSpellingToTheSharedRequest() throws Exception {
        // A daemon start must preserve the same immutable spelling as a CLI start.
        final var request =
                PoreloDaemon.request(
                        Map.of("project", "uc", "task", "work", "short", "x", "repository", "uc"),
                        context(""));
        assertThat(request.shortName())
                .as("the wire spelling reaches the common start request")
                .isEqualTo("x");
    }

    @Test
    void theApprovalContractCanReturnAPullRequestWithoutRequiringOne() {
        final String description = PoreloDaemon.description();
        final String approval =
                description.substring(
                        description.indexOf("method Approve("),
                        description.indexOf("method PendingBundle("));

        assertThat(approval)
                .as("the URL extends the Approve reply without breaking old clients")
                .contains("forwarded: string", "branch: string", "pullRequest: ?string");
    }

    private PoreloContext context(final String branchBlock) throws Exception {
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
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n"
                        + branchBlock);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), file + "\n");
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aStartCanNameItsStartingBranchIndependentlyOfItsForgeBranch() throws Exception {
        final var request =
                PoreloDaemon.request(
                        Map.of(
                                "project",
                                "uc",
                                "task",
                                "t",
                                "repository",
                                "uc",
                                "from",
                                "develop",
                                "branch",
                                "porelo/t"),
                        context(""));
        assertThat(request.base())
                .as("the daemon forwards the person's starting branch")
                .isEqualTo(new org.fuin.porelo.app.TaskBase("develop", "FROM"));
        assertThat(request.branch()).as("the forge branch remains separate").isEqualTo("porelo/t");
    }

    @Test
    void anUnnamedStartByARepositorysFullNameIsNamedAsByItsKey() throws Exception {
        // The window shows full names; a task named from one fails the name check CanStart passed.
        final PoreloContext context =
                context(
                        "repositories:\n  api:\n    upstream: \"https://forge.example.org/uc/Shop_Api.git\"\n");

        final var request =
                PoreloDaemon.request(Map.of("project", "uc", "repository", "Shop_Api"), context);

        assertThat(request.repository())
                .as("the start goes on with the repository's key")
                .isEqualTo("api");
        assertThat(request.task())
                .as("an unnamed task is named after the key, as CanStart names it")
                .isEqualTo(org.fuin.porelo.app.TaskNames.fromRepository(context, "uc", "api"));
    }

    @Test
    void inAProjectWithABlockAnApprovalOntoMainIsRefusedNamingThePattern() throws Exception {
        final PoreloContext context =
                context("branch:\n  template: \"fix/{task}\"\n  pattern: \"^fix/.+$\"\n");

        assertThatThrownBy(
                        () ->
                                GateMethods.approvalBranch(
                                        Map.of("project", "uc", "name", "t", "branch", "main"),
                                        context))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".BranchRefused");
                            assertThat(ex.getParameters())
                                    .containsEntry("branch", "main")
                                    .containsEntry("pattern", "^fix/.+$");
                        });
        assertThatThrownBy(
                        () ->
                                GateMethods.approvalBranch(
                                        Map.of("project", "uc", "name", "t"), context))
                .as("no branch named and none recorded")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".BranchRequired"));
    }

    @Test
    void aProjectWithoutABlockApprovesOntoWhatIsNamedAsBefore() throws Exception {
        final PoreloContext context = context("");

        assertThat(
                        GateMethods.approvalBranch(
                                Map.of("project", "uc", "name", "t", "branch", "release"), context))
                .isEqualTo("release");
        assertThat(GateMethods.approvalBranch(Map.of("project", "uc", "name", "t"), context))
                .isNull();
    }
}
