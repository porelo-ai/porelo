package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for measuring a project's distance from its upstream on demand.
 *
 * <p>What is asserted here is the refusals, because they are the cases that must not reach the
 * network: a triggered measurement is the one operation in this product allowed to, and everything
 * that cannot be answered has to be turned away before it tries.
 */
class UpstreamSyncTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private Path project(Path dir) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """);
        new ProjectRegistry(context(dir).paths().projects().projectRegistry())
                .remember("demo", file);
        return file;
    }

    @Test
    void aProjectNobodyRegisteredIsRefused(@TempDir Path dir) {

        assertThat(UpstreamSync.sync(context(dir), "nobody").outcome())
                .isEqualTo(UpstreamSync.Outcome.NO_SUCH_PROJECT);
    }

    @Test
    void aProjectThatHasNeverUsedTheGateHasNothingToMeasureAgainst(@TempDir Path dir)
            throws Exception {

        // Ordinary rather than a fault, and it has to be turned away before anything reaches the
        // network: there is no mirror to compare an upstream against, so there is nothing to ask.
        project(dir);

        final UpstreamSync.Result result = UpstreamSync.sync(context(dir), "demo");

        assertThat(result.outcome()).isEqualTo(UpstreamSync.Outcome.NO_MIRROR);
        assertThat(result.measured()).isFalse();
        // The listing itself asks podman things; what must not happen is a fetch.
        assertThat(runner.invocations())
                .noneSatisfy(command -> assertThat(command.arguments()).contains("fetch"));
    }

    @Test
    void aMeasurementThatMeansNothingSaysSoRatherThanAnsweringZero(@TempDir Path dir)
            throws Exception {

        // Zero is the answer for a project that is up to date and also the answer for one nothing
        // could be measured about. Only 'measured' tells them apart, which is why it is beside it.
        project(dir);

        final UpstreamSync.Result result = UpstreamSync.sync(context(dir), "demo");

        assertThat(result.behind()).isZero();
        assertThat(result.measured()).isFalse();
    }

    @Test
    void aProjectRemovedWhileItsUpstreamIsFetchedGetsNoRecordBack(@TempDir Path dir)
            throws Exception {
        // Its removal deletes the mirror, then the records; a measure written after that is a
        // record of nothing, which doctor reports as left over.
        project(dir);
        final Path mirror =
                Files.createDirectories(
                                context(dir)
                                        .paths()
                                        .xdg()
                                        .data()
                                        .resolve("mirrors/demo.git/objects"))
                        .getParent();
        final Path record = context(dir).paths().projects().upstreamRecords().resolve("demo");
        final FakeCommandRunner removing =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            final org.fuin.porelo.core.process.Command command) {
                        if (command.arguments().contains("fetch")) {
                            try (var walk = Files.walk(mirror)) {
                                for (final Path each :
                                        walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                    Files.delete(each);
                                }
                                Files.deleteIfExists(record);
                            } catch (java.io.IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        }
                        return super.run(command);
                    }
                }.answering("remote.origin.url", "https://example.org/demo.git")
                        .answering("rev-list", "3");
        final PoreloContext context =
                new PoreloContext(removing, context(dir).paths(), command -> 0);

        UpstreamSync.sync(context, "demo");

        assertThat(mirror).as("the fixture removed the mirror during the fetch").doesNotExist();
        assertThat(record).as("no record comes back for the removed project").doesNotExist();
    }
}
