package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.app.HeldRuns;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run begun in the daemon for a start made outside it, at the place the start recorded. */
class BeginRunTest {

    private static final String TASK = "porelo-uc_shop-2";

    @TempDir Path dir;

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
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static void refusedAs(final Runnable call, final String error, final String said) {
        assertThatThrownBy(call::run)
                .as(said)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> assertThat(ex.getErrorName()).as("the error").isEqualTo(error));
    }

    @Test
    void aTaskWhoseStartRecordedNoPullRequestIsAnInvalidTask() throws Exception {
        final PoreloContext context = context();
        RunPlace.ownPush("uc", null, "shop-2")
                .writeTo(Files.createDirectories(context.paths().tasks().containerState(TASK)));
        final ReviewParts parts = ReviewParts.of(context, new HeldRuns());

        refusedAs(
                () -> parts.begun(TASK),
                "org.varlink.service.InvalidParameter",
                "a gate push begins no run here");
        refusedAs(
                () -> parts.begun("porelo-uc_other"),
                "org.varlink.service.InvalidParameter",
                "a task with no record");
    }

    @Test
    void aPullRequestAtAForgeTheMachineDoesNotKnowIsRefusedTyped() throws Exception {
        final PoreloContext context = context();
        RunPlace.atPullRequest(
                        RunPlace.REVIEWER, "shop-2", "https://review.stub.invalid/uc/shop/pull/7")
                .writeTo(Files.createDirectories(context.paths().tasks().containerState(TASK)));
        final HeldRuns runs = new HeldRuns();
        final ReviewParts parts = ReviewParts.of(context, runs);

        refusedAs(
                () -> parts.begun(TASK),
                "org.fuin.porelo.Tasks1.UnknownForge",
                "the contract's own refusal");
        assertThat(runs.of(TASK)).as("no run").isEmpty();
    }
}
