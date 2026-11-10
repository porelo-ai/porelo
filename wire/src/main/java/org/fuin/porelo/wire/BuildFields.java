package org.fuin.porelo.wire;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The bounds and printable metadata shared by a build reader and Tasks1's stored build answer. */
public final class BuildFields {
    private BuildFields() {}

    /** The most runs a build names. */
    public static final int MOST_RUNS = 50;

    /** The most code points of a name, result or failed-test name. */
    public static final int MOST_TEXT = 200;

    /** The most code points of a run trigger or id. */
    public static final int MOST_ID = 64;

    /** The most failed-test names one job carries. */
    public static final int MOST_FAILED_TESTS = 50;

    /** The most ASCII characters of a job page URL. */
    public static final int MOST_URL = 2048;

    /** The most jobs a build names. */
    public static final int MOST_JOBS = 50;

    /**
     * Returns a reader's optional text as one line of at most so many code points, or {@code null}
     * when it is absent or blank.
     */
    public static @Nullable String cut(final @Nullable String text, final int most) {
        final String stripped = flat(text);
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.codePointCount(0, stripped.length()) <= most
                ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, most));
    }

    /**
     * Returns a reader's text as one line Porelo may write: control and format characters, line
     * breaks among them, become a space; stripped and cut at {@link #MOST_TEXT} code points.
     *
     * <p>The name is the repository's to write: a workflow named {@code x\nverdict: success} wrote
     * a forged line into the file a task reads.
     *
     * @param text What the reader said.
     * @return It as one line.
     */
    public static String line(final @Nullable String text) {
        final String stripped = flat(text);
        return stripped.codePointCount(0, stripped.length()) <= MOST_TEXT
                ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, MOST_TEXT));
    }

    /**
     * Returns a text as one line, uncut: control and format characters, line breaks among them,
     * become a space.
     *
     * @param text What the reader said.
     * @return It as one line, stripped.
     */
    public static String flat(final @Nullable String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder kept = new StringBuilder(Math.min(text.length(), MOST_TEXT * 2));
        text.codePoints()
                .map(
                        point ->
                                Character.isISOControl(point)
                                                || Character.getType(point) == Character.FORMAT
                                                || Character.getType(point)
                                                        == Character.LINE_SEPARATOR
                                                || Character.getType(point)
                                                        == Character.PARAGRAPH_SEPARATOR
                                        ? ' '
                                        : point)
                .forEach(kept::appendCodePoint);
        return kept.toString().strip();
    }

    /**
     * Returns a job's page address when it is one Porelo may show: {@code https}, with a host, no
     * user part, no space or control character, at most {@link #MOST_URL} characters.
     */
    public static @Nullable String page(final @Nullable String url) {
        if (url == null || url.isEmpty() || url.length() > MOST_URL) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                return null;
            }
        }
        try {
            final java.net.URI address = new java.net.URI(url);
            return "https".equals(address.getScheme())
                            && address.getHost() != null
                            && address.getRawUserInfo() == null
                    ? url
                    : null;
        } catch (final java.net.URISyntaxException ex) {
            return null;
        }
    }

    /** Returns at most fifty bounded, nonblank failed-test names, or null when none remain. */
    public static @Nullable List<String> failedTests(final @Nullable List<String> tests) {
        if (tests == null) {
            return null;
        }
        final List<String> named = new ArrayList<>();
        for (final String test : tests) {
            final String each = cut(test, MOST_TEXT);
            if (each != null && named.size() < MOST_FAILED_TESTS) {
                named.add(each);
            }
        }
        return named.isEmpty() ? null : List.copyOf(named);
    }

    /** Keeps a reference only when its run is among the build's bounded runs. */
    public static @Nullable String knownRun(
            final @Nullable String run, final java.util.Set<String> ids) {
        return run == null || ids.contains(run) ? run : null;
    }
}
