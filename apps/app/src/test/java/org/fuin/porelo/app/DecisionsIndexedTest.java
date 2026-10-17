package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds the index that opens {@code doc/decisions.md} to the decisions below it.
 *
 * <p>The index is how a decision is found; one written without its row is one nobody looks up, and
 * a row whose decision was renamed or removed sends a reader to nothing. So every decision heading
 * has exactly one row linking to it, and every row's link has its heading.
 */
@Tag("documents")
class DecisionsIndexedTest {

    /** A row's link to its entry. */
    private static final Pattern LINK = Pattern.compile("\\[entry\\]\\(#([^)]+)\\)");

    private static List<String> lines() throws IOException {
        return Files.readAllLines(Path.of("../../doc/decisions.md"), StandardCharsets.UTF_8);
    }

    /**
     * Returns the anchor the site gives a heading: lower case, everything but letters, digits,
     * hyphens and spaces dropped, spaces as hyphens.
     */
    private static String anchor(final String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_\\- ]", "")
                .replace(' ', '-');
    }

    private static List<String> headings() throws IOException {
        return lines().stream()
                .filter(line -> line.startsWith("## "))
                .map(line -> anchor(line.substring(3).strip()))
                .toList();
    }

    private static List<String> links() throws IOException {
        return lines().stream()
                .filter(line -> line.startsWith("| "))
                .flatMap(
                        line -> {
                            final Matcher matcher = LINK.matcher(line);
                            return matcher.find()
                                    ? java.util.stream.Stream.of(matcher.group(1))
                                    : java.util.stream.Stream.empty();
                        })
                .toList();
    }

    @Test
    void everyDecisionHasExactlyOneRow() throws IOException {
        final List<String> links = links();

        // A guard that finds nothing passes forever: assert what it found, then what it checks.
        assertThat(headings()).as("decision headings").hasSizeGreaterThan(50);
        for (final String heading : headings()) {
            assertThat(links.stream().filter(heading::equals).count())
                    .as("rows linking to #%s", heading)
                    .isEqualTo(1);
        }
    }

    @Test
    void everyRowHasItsDecision() throws IOException {
        assertThat(links()).as("rows").hasSizeGreaterThan(50);
        assertThat(links()).as("rows linking to no heading").allMatch(headings()::contains);
    }
}
