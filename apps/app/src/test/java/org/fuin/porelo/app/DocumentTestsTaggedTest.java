package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that every test reading the repository's documents carries the {@code documents} tag.
 *
 * <p>A change to documents or issues runs only the tests so tagged; one that reads a document
 * without the tag would be left out of that run, and the run would stay green while the document it
 * checks is broken.
 */
@Tag("documents")
class DocumentTestsTaggedTest {

    /**
     * How a test reaches the documents: a page under {@code doc/}, the site's navigation, or every
     * Markdown page.
     */
    private static final Pattern READS_DOCUMENTS =
            Pattern.compile(
                    "\"(\\.\\./)+doc|\"\\.\\.\",\\s*(\"\\.\\.\",\\s*)*\"doc\"|mkdocs\\.yml|endsWith\\(\"\\.md\"\\)");

    private static final String TAG = "@Tag(\"documents\")";

    private static List<Path> testsReadingDocuments() throws Exception {
        // Not into a build's output: a report another test's run is writing can go as it is walked.
        final List<Path> sources = new java.util.ArrayList<>();
        Files.walkFileTree(
                Path.of("..", ".."),
                new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(
                            final Path directory,
                            final java.nio.file.attribute.BasicFileAttributes attributes) {
                        return "target".equals(String.valueOf(directory.getFileName()))
                                ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                : java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            final Path file,
                            final java.nio.file.attribute.BasicFileAttributes attributes) {
                        sources.add(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
        return sources.stream()
                .filter(path -> path.toString().contains("/src/test/java/"))
                .filter(path -> path.getFileName().toString().endsWith("Test.java"))
                .filter(path -> READS_DOCUMENTS.matcher(read(path)).find())
                .sorted()
                .toList();
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void everyTestThatReadsADocumentIsTagged() throws Exception {
        final List<Path> found = testsReadingDocuments();

        // A guard that finds nothing passes forever: assert what it found, then what it checks.
        assertThat(found)
                .extracting(path -> path.getFileName().toString())
                .as("tests found reading documents")
                .contains(
                        "ReachDocumentTest.java",
                        "CommandsDocumentedTest.java",
                        "MailboxGuideTest.java",
                        "FileExamplesTest.java");
        assertThat(found)
                .filteredOn(path -> !read(path).contains(TAG))
                .as("tests that read a document without %s", TAG)
                .isEmpty();
    }
}
