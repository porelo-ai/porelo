package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixture sources under target must not become production packages or agent-name violations. */
class RepositoryFilesTest {
    @Test
    void scansTheCheckoutAndPrunesRootAndModuleBuildOutput(@TempDir Path root) throws Exception {
        final Path actual = root.resolve("module/src/main/java/Actual.java");
        for (Path file :
                java.util.List.of(
                        actual,
                        root.resolve("target/fixture/src/main/java/Generated.java"),
                        root.resolve("module/target/fixture/src/main/java/Generated.java"),
                        root.resolve(".scratch/module/src/main/java/OtherCheckout.java"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Example {}\n");
        }
        assertThat(RepositoryFiles.of(root))
                .as("only this checkout's source is inspected")
                .containsExactly(actual);
    }
}
