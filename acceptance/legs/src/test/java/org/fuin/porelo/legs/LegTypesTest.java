package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds that the server type a leg rents can be chosen for a run without a commit.
 *
 * <p>A tag's Build runs the tag's own workflow, and its legs rented the shared-vCPU types alone: on
 * a busy host a build took five times its time and the release's leg ran out of its step
 * (2026-10-17), with nothing to set but a new commit.
 */
@Tag("documents")
class LegTypesTest {

    @Test
    void theBuildsLegTakesItsServerTypesFromTheRepositoryVariable() throws IOException {
        final List<String> legLines =
                Files.readAllLines(
                                Path.of("../../.github/workflows/build.yml"),
                                StandardCharsets.UTF_8)
                        .stream()
                        .filter(line -> line.contains("-Dlegs.args=\"leg "))
                        .toList();

        // A guard that finds nothing passes forever: the line is there, then what it says.
        assertThat(legLines).hasSize(1);
        assertThat(legLines.getFirst()).contains("vars.LEG_TYPES").contains("--type");
    }
}
