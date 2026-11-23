package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ReviewPages}: a review larger than one message answers its first pages, and each
 * part is walked page by page to its end, nothing skipped or repeated, each page one message.
 */
class ReviewPagesTest {

    private static final int LIMIT = VarlinkConnection.MAX_MESSAGE;

    private static final String BASE = "b".repeat(40);

    private static final List<String> COMMAND =
            List.of("porelo", "gate", "review", "-p", "p", "-r", "r", "--", "t");

    private static String part(String path, String line, int lines) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n@@ -0,0 +1,"
                + lines
                + " @@\n"
                + ("+" + line + "\n").repeat(lines);
    }

    private static Map<String, Object> file(int i) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", "src/a-rather-long-directory-name/file-%06d.txt".formatted(i));
        row.put("status", "A");
        row.put("added", i);
        row.put("removed", 0);
        row.put("rank", "ORDINARY");
        row.put("reason", "\u0001 escaped \"reason\" " + i);
        return row;
    }

    private static Map<String, Object> commit(int i) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("commit", "%040x".formatted(i));
        row.put("subject", "commit " + i);
        row.put("author", "A <a@example.com>");
        row.put("committer", "A <a@example.com>");
        row.put("trailers", List.of("Assisted-by: x"));
        row.put("message", "commit " + i + "\n\n" + "\"".repeat(1_000));
        return row;
    }

    /** A review over the limit in every part: files, commits, log and an escape-heavy diff. */
    private static Map<String, Object> large() {
        final List<Map<String, Object>> files = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            files.add(file(i));
        }
        final List<Map<String, Object>> commits = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            commits.add(commit(i));
        }
        final StringBuilder log = new StringBuilder();
        for (int i = 0; i < 120_000; i++) {
            log.append("%040x subject é \u0002 %d\n".formatted(i, i));
        }
        final StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            diff.append(part("escaped-%03d.txt".formatted(i), "\u0001\"\\".repeat(20), 300));
        }
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("commit", "f".repeat(40));
        reply.put("diff", diff.toString());
        reply.put("log", log.toString());
        reply.put("commits", commits);
        reply.put("files", files);
        reply.put("asked", "what the task was asked");
        return reply;
    }

    /** The reply's paged, keyed by name; {@code null} when it has none. */
    private static Map<String, Object> paged(Map<String, Object> reply) {
        if (!(reply.get("paged") instanceof Map<?, ?> paged)) {
            return null;
        }
        final Map<String, Object> named = new LinkedHashMap<>();
        paged.forEach((key, value) -> named.put(String.valueOf(key), value));
        return named;
    }

    private static int utf8(Object text) {
        return String.valueOf(text).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Walks one part from a position to its end, each page within one message. */
    private static Object walk(String part, Object whole, Object first, Integer from) {
        final List<Object> entries = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        if (first instanceof List<?> list) {
            entries.addAll(list);
        } else {
            text.append(first);
        }
        Integer next = from;
        int pages = 0;
        while (next != null) {
            final Map<String, Object> page = ReviewPages.page(part, whole, next);
            assertThat(ReviewCut.bytes(page))
                    .as("page %d of %s", pages, part)
                    .isLessThanOrEqualTo(LIMIT);
            final Object content = page.get(whole instanceof List<?> ? part : "text");
            assertThat(content).as("page %d of %s has content", pages, part).isNotNull();
            if (content instanceof List<?> list) {
                assertThat(list).as("a page moves on").isNotEmpty();
                entries.addAll(list);
            } else {
                assertThat(String.valueOf(content)).as("a page moves on").isNotEmpty();
                text.append(content);
            }
            final Object said = page.get("next");
            if (said != null) {
                assertThat(((Number) said).intValue()).as("forward").isGreaterThan(next);
            }
            next = said == null ? null : ((Number) said).intValue();
            pages++;
        }
        return whole instanceof List<?> ? entries : text.toString();
    }

    @Test
    void aReviewLargerThanOneMessageAnswersItsFirstPagesAndEachPartWalksToItsEnd() {
        final Map<String, Object> reply = large();

        final Map<String, Object> fitted = ReviewCut.fitted(reply, "t", BASE, COMMAND, LIMIT);

        assertThat(ReviewCut.bytes(fitted)).isLessThanOrEqualTo(LIMIT);
        assertThat(fitted).containsEntry("commit", reply.get("commit"));
        assertThat(fitted).containsEntry("asked", reply.get("asked"));
        final Map<String, Object> paged = paged(fitted);
        assertThat(paged).as("paged says how much there is").isNotNull();
        assertThat(paged.get("files")).isEqualTo(40_000);
        assertThat(paged.get("commits")).isEqualTo(3_000);
        assertThat(paged.get("logBytes")).isEqualTo(utf8(reply.get("log")));
        assertThat(paged.get("diffBytes")).isEqualTo(utf8(reply.get("diff")));
        assertThat(paged.get("base")).as("the base the pages are read against").isEqualTo(BASE);
        for (final String part : ReviewPages.PARTS) {
            final String key =
                    switch (part) {
                        case "log" -> "logNext";
                        case "diff" -> "diffNext";
                        default -> part + "Next";
                    };
            assertThat(paged.get(key)).as("%s is paged", part).isNotNull();
            assertThat(walk(part, reply.get(part), fitted.get(part), (Integer) paged.get(key)))
                    .as("%s put together is the whole, nothing skipped or repeated", part)
                    .isEqualTo(reply.get(part));
        }
    }

    @Test
    void aPartThatFitsItsShareIsSentWholeWithoutANextPosition() {
        final Map<String, Object> reply = large();
        reply.put("commits", List.of(commit(1)));

        final Map<String, Object> fitted = ReviewCut.fitted(reply, "t", BASE, COMMAND, LIMIT);

        final Map<String, Object> paged = paged(fitted);
        assertThat(paged).containsEntry("commits", 1).doesNotContainKey("commitsNext");
        assertThat(fitted.get("commits")).isEqualTo(List.of(commit(1)));
    }

    @Test
    void aDiffCutAtAFileBoundaryNamesWhereItsNextPageStarts() {
        final String a = part("a.txt", "x".repeat(100), 20_000);
        final String b = part("b.txt", "\u0001".repeat(100), 20_000);
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("diff", a + b);
        reply.put("log", "abc first\n");
        reply.put("files", List.of(Map.of("path", "a.txt"), Map.of("path", "b.txt")));

        final Map<String, Object> fitted = ReviewCut.fitted(reply, "t", BASE, COMMAND, LIMIT);

        assertThat(fitted.get("diff")).isEqualTo(a);
        assertThat(fitted.get("cut")).as("the cut is still said").isNotNull();
        final Map<String, Object> paged = paged(fitted);
        assertThat(paged.get("diffNext")).isEqualTo(utf8(a));
        assertThat(paged.get("base")).isEqualTo(BASE);
        assertThat(paged).doesNotContainKeys("filesNext", "commitsNext", "logNext");
        assertThat(walk("diff", a + b, a, (Integer) paged.get("diffNext"))).isEqualTo(a + b);
    }

    @Test
    void aReviewThatFitsSendsNoPaged() {
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("diff", part("a.txt", "x", 3));
        reply.put("log", "abc first\n");
        reply.put("files", List.of(Map.of("path", "a.txt")));

        assertThat(ReviewCut.fitted(reply, "t", BASE, COMMAND, LIMIT))
                .isSameAs(reply)
                .doesNotContainKeys("paged", "cut");
    }

    @Test
    void oneFileLargerThanAPageIsPagedByLinesAndOneLineByCodePoints() {
        // Neither fits a page alone: one file of many lines, and one line of control characters
        // that escape to six bytes each.
        final String big = part("big.txt", "\u0001".repeat(1_000), 2_000);
        final String line = "+" + "\u0001é".repeat(LIMIT / 4) + "\n";
        final String long_ = "diff --git a/l b/l\n--- a/l\n+++ b/l\n@@ -0,0 +1 @@\n" + line;
        final String diff = part("a.txt", "x", 2) + big + long_ + part("z.txt", "z", 2);

        assertThat(walk("diff", diff, "", 0)).isEqualTo(diff);
        assertThat(walk("log", line + line, "", 0)).isEqualTo(line + line);
    }

    private static void refusedNaming(String parameter, String part, Object whole, int from) {
        assertThatThrownBy(() -> ReviewPages.page(part, whole, from))
                .as("%s from %d", part, from)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(ex.getParameters()).containsEntry("parameter", parameter);
                        });
    }

    @Test
    void aPositionNoPageGaveIsRefusedNamingFrom() {
        final String diff = part("a.txt", "x", 3) + part("b.txt", "y", 3);
        final String log = "abc first\ndef second\n";
        final List<Map<String, Object>> files = List.of(file(0), file(1));

        refusedNaming("from", "diff", diff, part("a.txt", "x", 3).length() - 2);
        refusedNaming("from", "diff", diff, "diff --git a/a.txt".length() + 1);
        refusedNaming("from", "diff", diff, diff.length());
        refusedNaming("from", "log", log, 3);
        refusedNaming("from", "log", log, log.length());
        refusedNaming("from", "log", log, -1);
        refusedNaming("from", "files", files, 2);
        refusedNaming("from", "commits", List.of(), 0);
        refusedNaming("from", "files", files, -1);
        // A line start inside a file that fits a page is not where a page of diff begins.
        refusedNaming("from", "diff", diff, diff.indexOf("--- a/b.txt"));
        // A code point inside a line that fits is not where a page of log begins.
        refusedNaming("from", "log", "é\n", 1);
    }

    @Test
    void aPartThatDoesNotExistIsRefusedNamingPart() {
        refusedNaming("part", "patch", "", 0);
    }

    @Test
    void positionsAtAnEntryALineOrAFileAreAnswered() {
        final String diff = part("a.txt", "x", 3) + part("b.txt", "y", 3);
        final String log = "abc first\ndef second\n";

        assertThat(ReviewPages.page("diff", diff, part("a.txt", "x", 3).length()))
                .containsEntry("text", part("b.txt", "y", 3))
                .doesNotContainKey("next");
        assertThat(ReviewPages.page("log", log, 10))
                .containsEntry("text", "def second\n")
                .doesNotContainKey("next");
        assertThat(ReviewPages.page("files", List.of(file(0), file(1)), 1))
                .containsEntry("files", List.of(file(1)))
                .doesNotContainKey("next");
    }
}
