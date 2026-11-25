package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the one way a review document crosses into Porelo's records and the one way out. Harm: a
 * second reader that skips the profile's check, or a second writer whose documents drift from the
 * profile, so that what Porelo keeps and shows depends on which path a document took.
 */
class ProfileTranslationBoundaryTest {

    /** A call of the hardened reader. */
    private static final Pattern READS = Pattern.compile("SarifReading(\\.read\\(|::read\\b)");

    /** Code that builds a profile document: its version as a key, or SARIF's model made anew. */
    private static final Pattern BUILDS =
            Pattern.compile("\"porelo/profileVersion\"\\s*,|new SarifSchema210\\(");

    private static Path root() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }

    private static List<String> matching(final Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(root())) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(
                            path -> {
                                try {
                                    return pattern.matcher(
                                                    Files.readString(path, StandardCharsets.UTF_8))
                                            .find();
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            })
                    .map(path -> String.valueOf(path.getFileName()))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void onlyTheProfileTranslatorReadsADocumentFromOutside() throws IOException {
        assertThat(matching(READS))
                .as("the sources that call SarifReading.read")
                .containsExactly("ProfileTranslator.java");
    }

    @Test
    void onlyReviewSarifBuildsAProfileDocument() throws IOException {
        assertThat(matching(BUILDS))
                .as("the sources that build a profile document")
                .containsExactly("ReviewSarif.java");
    }
}
