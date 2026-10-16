package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that no tracked file of this repository says the product's old name, but where it must.
 * <p>
 * The repository was renamed by a script; a word it left behind, or one written later out of habit, names something
 * that is no longer there. The old name is spelled here in two pieces, so that this file does not say it.
 */
@Tag("documents")
class NoOldNameTest {

    private static final String OLD = "so" + "kar";

    /** The old name as a sentence writes it. */
    private static final String OLD_NAME = "So" + "kar";

    /**
     * Paths that keep the old name, name and text: the released changelog entries and the changelog made of them,
     * the entry that says the product was renamed, and the issue of the rename.
     */
    private static final Pattern KEPT_PATHS = Pattern.compile("CHANGELOG\\.md|changelog/v[^/]+/.*|"
            + "changelog/unreleased/renamed-porelo\\.yml|"
            + "issues/base/B195-[^/]+\\.md"); // not-a-citation: the issue's own file

    /**
     * Lines that keep it: the rename's own row in the issue index, and the sentence on what the old name left at a
     * forge.
     */
    private static final Pattern KEPT_LINES = Pattern.compile(".*<a id=\"b195\"></a>.*"
            + "|A deploy key made before 0\\.5\\.0, while Porelo was called " + OLD_NAME + ", .*"
            + "|its forge\\. Porelo reads nothing of " + OLD_NAME + "'s, .*");

    private static List<String> tracked() throws IOException, InterruptedException {
        final Process git = new ProcessBuilder("git", "ls-files", "-z").directory(Path.of("../..").toFile())
                .redirectErrorStream(true).start();
        final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git ls-files: %s", out).isZero();
        return List.of(out.split("\0"));
    }

    private static List<String> lines(final Path path) {
        try {
            return Files.readAllLines(path);
        } catch (final MalformedInputException ex) {
            // Not text: nothing in it is read as a word.
            return List.of();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void noTrackedFileSaysTheOldName() throws Exception {
        final List<String> files = tracked();
        final List<String> found = new ArrayList<>();
        for (final String file : files) {
            if (KEPT_PATHS.matcher(file).matches()) {
                continue;
            }
            if (file.toLowerCase(Locale.ROOT).contains(OLD)) {
                found.add(file + ": its path");
            }
            final List<String> lines = lines(Path.of("../..", file));
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (!KEPT_LINES.matcher(line).matches() && line.toLowerCase(Locale.ROOT).contains(OLD)) {
                    found.add(file + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }

        // A guard that reads nothing passes forever: assert what it read, then what it checks.
        assertThat(files).contains("pom.xml", "AGENTS.md", "issues/base/README.md");
        assertThat(found).as("tracked files that say the old name").isEmpty();
    }
}
