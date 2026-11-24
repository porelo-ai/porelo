package org.fuin.porelo.gate.review;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Checks a comment's or a reply's fields alone, without Git: the path's grammar, the line range,
 * and the bounds and characters of the text and the replacement.
 *
 * <p>The fields come from the owner account or from a task, and every rule is judged as a hostile
 * caller would try it. A refusal names its rule and never quotes what was given.
 */
public final class CommentFields {

    /** How many lines one anchor may cover. */
    public static final int MAX_ANCHOR_LINES = 100;

    /** How many UTF-8 bytes a text or a replacement may hold. */
    public static final int MAX_TEXT_BYTES = 8 * 1024;

    /** How many UTF-8 bytes a path may hold. */
    public static final int MAX_PATH_BYTES = 4096;

    /** How many Unicode scalar values one line of a replacement may hold. */
    public static final int MAX_REPLACEMENT_LINE = 400;

    /** How many lines a replacement may hold beyond the lines of its anchor. */
    public static final int EXTRA_REPLACEMENT_LINES = 10;

    private CommentFields() {}

    /**
     * Checks an item's fields: a comment's anchor, text and replacement; a reply's text.
     *
     * @param item What was given.
     * @return The refusal by rule, or empty when the fields may stand.
     */
    public static Optional<ReviewRefusal> check(final ReviewItem item) {
        return switch (item) {
            case ReviewComment comment -> comment(comment);
            case ReviewReply reply -> body(reply.body());
        };
    }

    private static Optional<ReviewRefusal> comment(final ReviewComment comment) {
        final Optional<ReviewRefusal> anchor = anchor(comment.anchor());
        if (anchor.isPresent()) {
            return anchor;
        }
        final Optional<ReviewRefusal> body = body(comment.body());
        if (body.isPresent()) {
            return body;
        }
        final Suggestion suggestion = comment.suggestion();
        return suggestion == null ? Optional.empty() : suggestion(suggestion, comment.anchor());
    }

    private static Optional<ReviewRefusal> anchor(final Anchor anchor) {
        return switch (anchor) {
            case LineAnchor line -> lineAnchor(line);
        };
    }

    private static Optional<ReviewRefusal> lineAnchor(final LineAnchor anchor) {
        final Optional<ReviewRefusal> path = path(anchor.path());
        if (path.isPresent()) {
            return path;
        }
        if (anchor.lines() > MAX_ANCHOR_LINES) {
            return refused(ReviewError.TOO_LARGE, "anchor " + MAX_ANCHOR_LINES + " lines");
        }
        return Optional.empty();
    }

    /**
     * Checks a raw repository-relative Git path, as Git names it in a tree, never percent-encoded:
     * a percent sign or a colon is an ordinary character of a name.
     */
    private static Optional<ReviewRefusal> path(final String path) {
        if (utf8Bytes(path) > MAX_PATH_BYTES) {
            return refused(ReviewError.TOO_LARGE, "path " + MAX_PATH_BYTES + " bytes");
        }
        final String character = characterRule(path, false);
        if (character != null) {
            return refused(ReviewError.TEXT_REFUSED, "path: " + character);
        }
        if (path.indexOf('\\') >= 0) {
            return refused(ReviewError.TEXT_REFUSED, "path: backslash");
        }
        if (path.startsWith("/") || path.endsWith("/")) {
            return refused(ReviewError.TEXT_REFUSED, "path: leading or trailing slash");
        }
        // The limit -1 keeps empty components, so "a//b" is seen.
        for (final String component : path.split("/", -1)) {
            if (component.isEmpty()) {
                return refused(ReviewError.TEXT_REFUSED, "path: empty component");
            }
            if (component.equals(".") || component.equals("..")) {
                return refused(ReviewError.TEXT_REFUSED, "path: dot component");
            }
        }
        return Optional.empty();
    }

    private static Optional<ReviewRefusal> body(final Body body) {
        final String text = body.text();
        if (text.isBlank()) {
            return refused(ReviewError.TEXT_REFUSED, "empty");
        }
        if (utf8Bytes(text) > MAX_TEXT_BYTES) {
            return refused(ReviewError.TOO_LARGE, "text 8 KiB");
        }
        final String character = characterRule(text, true);
        return character == null ? Optional.empty() : refused(ReviewError.TEXT_REFUSED, character);
    }

    /** An empty replacement is allowed: it deletes the anchor's lines. */
    private static Optional<ReviewRefusal> suggestion(
            final Suggestion suggestion, final Anchor anchor) {
        // The record already refuses this; judged again so the rule stands here by its own name.
        if (!anchor.takesSuggestion()) {
            return refused(ReviewError.OLD_SIDE_FIX, "old side");
        }
        final String replacement = suggestion.replacement();
        if (utf8Bytes(replacement) > MAX_TEXT_BYTES) {
            return refused(ReviewError.TOO_LARGE, "replacement 8 KiB");
        }
        final String character = characterRule(replacement, true);
        if (character != null) {
            return refused(ReviewError.TEXT_REFUSED, "replacement: " + character);
        }
        final String[] lines = lines(replacement);
        for (final String line : lines) {
            if (line.codePointCount(0, line.length()) > MAX_REPLACEMENT_LINE) {
                return refused(
                        ReviewError.TOO_LARGE,
                        "replacement line " + MAX_REPLACEMENT_LINE + " characters");
            }
        }
        final long allowed =
                switch (anchor) {
                    case LineAnchor line -> line.lines() + EXTRA_REPLACEMENT_LINES;
                };
        if (lines.length > allowed) {
            return refused(
                    ReviewError.FIX_OUTSIDE_ANCHOR, "anchor lines plus " + EXTRA_REPLACEMENT_LINES);
        }
        return Optional.empty();
    }

    /**
     * Returns a replacement's lines: a line feed ends a line, and a final one opens no further
     * line; an empty replacement has none.
     */
    private static String[] lines(final String replacement) {
        if (replacement.isEmpty()) {
            return new String[0];
        }
        final String body =
                replacement.endsWith("\n")
                        ? replacement.substring(0, replacement.length() - 1)
                        : replacement;
        return body.split("\n", -1);
    }

    /**
     * Returns the rule a character of the text breaks, or null: as a terminal judges what it shows,
     * only a line feed (where lines are allowed) and a tab pass among the controls; CR, DEL and the
     * C1 controls do not; nor does any format character (bidi overrides and isolates, zero-width
     * characters, the tag characters), a line or paragraph separator, or a lone surrogate.
     */
    private static @Nullable String characterRule(final String text, final boolean multiline) {
        for (int index = 0; index < text.length(); ) {
            final int point = text.codePointAt(index);
            index += Character.charCount(point);
            final String rule = characterRule(point, multiline);
            if (rule != null) {
                return rule;
            }
        }
        return null;
    }

    private static @Nullable String characterRule(final int point, final boolean multiline) {
        if (multiline && (point == '\n' || point == '\t')) {
            return null;
        }
        if (point < 0x20 || point >= 0x7f && point <= 0x9f) {
            return "control character";
        }
        final int type = Character.getType(point);
        if (type == Character.FORMAT || point >= 0xe0000 && point <= 0xe007f) {
            return "format character";
        }
        if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
            return "line or paragraph separator";
        }
        if (type == Character.SURROGATE) {
            return "lone surrogate";
        }
        return null;
    }

    /** Counts UTF-8 bytes without encoding: a lone surrogate counts three, as its replacement. */
    private static long utf8Bytes(final String text) {
        long bytes = 0;
        for (int index = 0; index < text.length(); ) {
            final int point = text.codePointAt(index);
            index += Character.charCount(point);
            bytes += point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
        }
        return bytes;
    }

    private static Optional<ReviewRefusal> refused(final ReviewError error, final String detail) {
        return Optional.of(new ReviewRefusal(error, detail));
    }
}
