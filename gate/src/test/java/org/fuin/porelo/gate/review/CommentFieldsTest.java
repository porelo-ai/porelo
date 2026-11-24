package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Guards the checks of a comment's fields alone, before any Git: hostile paths, lines, text and
 * replacements from the owner account or a task are refused by the rule they break.
 */
class CommentFieldsTest {

    @Test
    void aPlainCommentWithLineFeedsTabsAndNonAsciiTextPasses() {
        assertThat(check(comment("src/main/Ünïcode file.java", 1, 3, "Why?\n\tSee 😀 é.", null)))
                .isEmpty();
        assertThat(check(comment("a.txt", 2, 2, "ok", "instead\n"))).isEmpty();
    }

    @Test
    void aPathWithALeadingOrTrailingSlashIsRefused() {
        assertPathRefused("/etc/passwd", "path: leading or trailing slash");
        assertPathRefused("a/b/", "path: leading or trailing slash");
    }

    @Test
    void aPathWithABackslashIsRefused() {
        assertPathRefused("a\\b.txt", "path: backslash");
        assertPathRefused("C:\\Windows\\x", "path: backslash");
    }

    @Test
    void aPathWithAnEmptyComponentIsRefused() {
        assertPathRefused("a//b", "path: empty component");
        assertPathRefused("file:///etc/passwd", "path: empty component");
    }

    @Test
    void aPathWithADotOrDotDotComponentIsRefused() {
        assertPathRefused("../outside", "path: dot component");
        assertPathRefused("a/../../b", "path: dot component");
        assertPathRefused("a/./b", "path: dot component");
        assertPathRefused(".", "path: dot component");
        assertPathRefused("a/..", "path: dot component");
    }

    @Test
    void dotsInsideANameAndPercentSignsAreOrdinaryCharactersOfARawPath() {
        assertThat(check(comment("a/..b/.c/x..y/...", 1, 1, "ok", null))).isEmpty();
        assertThat(check(comment(".github/workflows/a%2Fb%00.yml", 1, 1, "ok", null))).isEmpty();
    }

    @Test
    void aPathWithAControlCharacterIsRefused() {
        for (final String hostile :
                new String[] {"a\u0000b", "a\nb", "a\tb", "a\u001b[8mb", "a\u007fb", "a\u0085b"}) {
            assertPathRefused(hostile, "path: control character");
        }
    }

    @Test
    void aPathWithAFormatCharacterIsRefused() {
        for (final String hostile :
                new String[] {"a\u202eb", "a\u200bb", "a\u2066b", "a" + tag(0xe0041) + "b"}) {
            assertPathRefused(hostile, "path: format character");
        }
    }

    @Test
    void aPathWithASeparatorOrALoneSurrogateIsRefused() {
        assertPathRefused("a\u2028b", "path: line or paragraph separator");
        assertPathRefused("a\u2029b", "path: line or paragraph separator");
        assertPathRefused("a\ud800b", "path: lone surrogate");
        assertPathRefused("a\udc00", "path: lone surrogate");
    }

    @Test
    void aPathIsAtMost4096Utf8Bytes() {
        assertThat(check(comment("é".repeat(2048), 1, 1, "ok", null))).isEmpty();
        assertThat(check(comment("é".repeat(2048) + "x", 1, 1, "ok", null)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "path 4096 bytes"));
    }

    @Test
    void anAnchorCoversAtMostOneHundredLines() {
        assertThat(check(comment("a.txt", 5, 104, "ok", null))).isEmpty();
        assertThat(check(comment("a.txt", 5, 105, "ok", null)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "anchor 100 lines"));
        assertThat(check(comment("a.txt", 1, Integer.MAX_VALUE, "ok", null)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "anchor 100 lines"));
    }

    @Test
    void aBlankTextIsRefusedAsEmpty() {
        assertThat(check(comment("a.txt", 1, 1, "", null)))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "empty"));
        assertThat(check(comment("a.txt", 1, 1, " \n\t ", null)))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "empty"));
    }

    @Test
    void aTextIsAtMost8KiBInUtf8() {
        assertThat(check(comment("a.txt", 1, 1, "x".repeat(8192), null))).isEmpty();
        // 2731 euro signs are 8193 bytes in UTF-8, though only 2731 characters.
        assertThat(check(comment("a.txt", 1, 1, "€".repeat(2731), null)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "text 8 KiB"));
        assertThat(check(comment("a.txt", 1, 1, "x".repeat(8193), null)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "text 8 KiB"));
    }

    @Test
    void aTextWithABidiCharacterIsRefused() {
        for (final int bidi : new int[] {0x202e, 0x202a, 0x2066, 0x2069, 0x200f, 0x061c}) {
            assertTextRefused("fine " + Character.toString(bidi) + " text", "format character");
        }
    }

    @Test
    void aTextWithAZeroWidthOrTagCharacterIsRefused() {
        for (final int hidden :
                new int[] {0x200b, 0x200d, 0xfeff, 0x2060, 0xe0001, 0xe0041, 0xe007f}) {
            assertTextRefused("a" + Character.toString(hidden) + "b", "format character");
        }
    }

    @Test
    void aTextWithAControlCharacterOtherThanLineFeedAndTabIsRefused() {
        for (final String hostile :
                new String[] {
                    "a\r\nb", "a\u001b[2Kb", "a\u0007", "a\u007f", "a\u0085", "a\u009b31m", "\u0000"
                }) {
            assertTextRefused(hostile, "control character");
        }
    }

    @Test
    void aTextWithASeparatorOrALoneSurrogateIsRefusedAndAPairPasses() {
        assertTextRefused("a\u2028b", "line or paragraph separator");
        assertTextRefused("a\u2029b", "line or paragraph separator");
        assertTextRefused("a\ud83d", "lone surrogate");
        assertTextRefused("\ude00a", "lone surrogate");
        assertThat(check(comment("a.txt", 1, 1, "a\ud83d\ude00", null))).isEmpty();
    }

    @Test
    void anEmptyReplacementIsAllowedSinceItDeletesTheLines() {
        assertThat(check(comment("a.txt", 1, 3, "remove these", ""))).isEmpty();
    }

    @Test
    void aReplacementReachesAtMostTheAnchorsLinesPlusTen() {
        // The anchor covers three lines, so thirteen lines may replace them; a final line feed
        // ends the last line and opens no new one.
        assertThat(check(comment("a.txt", 1, 3, "ok", "x\n".repeat(13)))).isEmpty();
        assertThat(check(comment("a.txt", 1, 3, "ok", "x\n".repeat(12) + "x"))).isEmpty();
        assertThat(check(comment("a.txt", 1, 3, "ok", "x\n".repeat(14))))
                .contains(
                        new ReviewRefusal(ReviewError.FIX_OUTSIDE_ANCHOR, "anchor lines plus 10"));
        assertThat(check(comment("a.txt", 1, 3, "ok", "\n".repeat(14))))
                .contains(
                        new ReviewRefusal(ReviewError.FIX_OUTSIDE_ANCHOR, "anchor lines plus 10"));
    }

    @Test
    void aReplacementLineIsAtMost400Characters() {
        // An emoji is one Unicode scalar value though two Java chars.
        assertThat(check(comment("a.txt", 1, 1, "ok", "😀".repeat(400) + "\nx"))).isEmpty();
        assertThat(check(comment("a.txt", 1, 1, "ok", "x\n" + "y".repeat(401))))
                .contains(
                        new ReviewRefusal(
                                ReviewError.TOO_LARGE, "replacement line 400 characters"));
    }

    @Test
    void aReplacementIsAtMost8KiBInUtf8() {
        final String line = "€".repeat(100) + "\n"; // 301 bytes
        assertThat(check(comment("a.txt", 1, 100, "ok", line.repeat(27)))).isEmpty();
        assertThat(check(comment("a.txt", 1, 100, "ok", line.repeat(28))))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "replacement 8 KiB"));
    }

    @Test
    void aReplacementIsJudgedByTheTextsCharacterRules() {
        assertThat(check(comment("a.txt", 1, 1, "ok", "if (admin\u202e) {")))
                .contains(
                        new ReviewRefusal(
                                ReviewError.TEXT_REFUSED, "replacement: format character"));
        assertThat(check(comment("a.txt", 1, 1, "ok", "a\r\n")))
                .contains(
                        new ReviewRefusal(
                                ReviewError.TEXT_REFUSED, "replacement: control character"));
        assertThat(check(comment("a.txt", 1, 1, "ok", "\tindented\n"))).isEmpty();
    }

    @Test
    void aReplacementOnTheOldSideCannotEvenBeBuilt() {
        final LineAnchor old = new LineAnchor("a.txt", Side.OLD, 1, 1);
        assertThatThrownBy(() -> new ReviewComment(old, new Body("why"), new Suggestion("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReplysTextIsJudgedByTheSameRules() {
        assertThat(check(new ReviewReply("thread-1", new Body("Done, thanks.\n")))).isEmpty();
        assertThat(check(new ReviewReply("thread-1", new Body("ok \u202e"))))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "format character"));
        assertThat(check(new ReviewReply("thread-1", new Body(" "))))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "empty"));
        assertThat(check(new ReviewReply("thread-1", new Body("x".repeat(8193)))))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "text 8 KiB"));
    }

    @Test
    void aRefusalNeverQuotesWhatWasGiven() {
        final String marker = "MARKER-7f3a";
        for (final ReviewItem hostile :
                new ReviewItem[] {
                    comment(marker + "/\u202e", 1, 1, "ok", null),
                    comment("a.txt", 1, 1, marker + "\u001b", null),
                    comment("a.txt", 1, 1, "ok", marker + "\u2066"),
                    new ReviewReply("t", new Body(marker + "\u200b"))
                }) {
            final Optional<ReviewRefusal> refusal = check(hostile);
            assertThat(refusal).isPresent();
            assertThat(refusal.orElseThrow().detail()).doesNotContain(marker);
        }
    }

    private static Optional<ReviewRefusal> check(final ReviewItem item) {
        return CommentFields.check(item);
    }

    private static ReviewComment comment(
            final String path,
            final int start,
            final int end,
            final String text,
            final @Nullable String replacement) {
        return new ReviewComment(
                new LineAnchor(path, Side.NEW, start, end),
                new Body(text),
                replacement == null ? null : new Suggestion(replacement));
    }

    private static void assertPathRefused(final String path, final String rule) {
        assertThat(check(comment(path, 1, 1, "ok", null)))
                .as("path %s", path.codePoints().mapToObj(Integer::toHexString).toList())
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, rule));
    }

    private static void assertTextRefused(final String text, final String rule) {
        assertThat(check(comment("a.txt", 1, 1, text, null)))
                .as("text %s", text.codePoints().mapToObj(Integer::toHexString).toList())
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, rule));
    }

    private static String tag(final int point) {
        return Character.toString(point);
    }
}
