package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a command needing a project asks which, from those this machine has, instead of refusing for want of
 * {@code --project}.
 */
class ProjectChoiceTest {

    @TempDir
    private Path dir;

    @Test
    void theProjectsHereAreOfferedByNumber() throws Exception {
        final PoreloContext context = new PoreloContext(new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")), arguments -> 0);
        final Path registry = Files.createDirectories(context.paths().projects().projectRegistry());
        for (final String name : new String[] {"alpha", "beta"}) {
            final Path file = Files.createDirectories(dir.resolve(name)).resolve("project.yml");
            Files.writeString(file, "project:\n  name: \"" + name + "\"\n  security_class: \"guarded\"\n"
                    + "image:\n  base_image: \"ubuntu:24.04\"\n");
            Files.writeString(registry.resolve(name), file + "\n");
        }
        final StringWriter said = new StringWriter();

        assertThat(ProjectSource.chosen(context, new Offer(question -> "3", false, false,
                new PrintWriter(said, true)), "porelo shield egress --project <name>")).isEqualTo("beta");
        assertThat(said.toString()).contains("1  default").contains("2  alpha").contains("3  beta");
        assertThat(ProjectSource.chosen(context, Offer.NOBODY, "x")).isNull();
    }
}
