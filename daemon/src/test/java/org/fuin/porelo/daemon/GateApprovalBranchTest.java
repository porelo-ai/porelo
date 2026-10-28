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
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + branchBlock);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), file + "\n");
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
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
