package org.fuin.porelo.app;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether a forge's list goes on after a page, as the forge itself says it.
 *
 * <p>A page shorter than asked is no end: a Forgejo whose {@code MAX_RESPONSE_ITEMS} is below what
 * was asked answers fewer on every page. Its {@code Link} header names a page {@code rel="next"}
 * while there is one; failing that, {@code X-Total-Count} says how many there are. Only the
 * header's word is taken, never its address: the next page is asked of the same forge, the same
 * way, by its number.
 */
final class ForgePages {

    /** Whether a forge says its list goes on after a page. */
    enum More {
        /** It names a next page. */
        YES,
        /** It names none. */
        NO,
        /** It says nothing either way: the list is read to its first empty page. */
        UNSAID
    }

    /** One link of a {@code Link} header: its address and its parameters. */
    private static final Pattern LINK = Pattern.compile("<[^>]*>((?:\\s*;[^;,]*)*)");

    /** A {@code rel} parameter, quoted or not. */
    private static final Pattern REL =
            Pattern.compile(
                    ";\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;,]+))", Pattern.CASE_INSENSITIVE);

    private ForgePages() {}

    /**
     * Returns whether a forge's list goes on after a page.
     *
     * @param headers The page's answer headers, their names in any case.
     * @param read How many of the list were read with this page and those before it.
     * @return What the forge says.
     */
    static More after(final Map<String, List<String>> headers, final long read) {
        final List<String> links = header(headers, "link");
        if (!links.isEmpty()) {
            for (final String link : links) {
                final Matcher each = LINK.matcher(link);
                while (each.find()) {
                    if (next(String.valueOf(each.group(1)))) {
                        return More.YES;
                    }
                }
            }
            return More.NO;
        }
        final List<String> totals = header(headers, "x-total-count");
        if (totals.size() == 1) {
            final Long total = count(totals.getFirst());
            if (total != null) {
                return read < total ? More.YES : More.NO;
            }
        }
        return More.UNSAID;
    }

    private static boolean next(final String parameters) {
        final Matcher rel = REL.matcher(parameters);
        while (rel.find()) {
            final String quoted = rel.group(1);
            final String value = quoted != null ? quoted : String.valueOf(rel.group(2));
            for (final String each : value.trim().split("\\s+")) {
                if (each.equalsIgnoreCase("next")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> header(final Map<String, List<String>> headers, final String name) {
        for (final Map.Entry<String, List<String>> each : headers.entrySet()) {
            if (each.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                return each.getValue();
            }
        }
        return List.of();
    }

    private static @Nullable Long count(final String text) {
        final String trimmed = text.trim();
        if (!trimmed.matches("[0-9]{1,18}")) {
            return null;
        }
        return Long.parseLong(trimmed);
    }
}
