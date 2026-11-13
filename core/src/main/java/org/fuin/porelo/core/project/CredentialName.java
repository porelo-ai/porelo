package org.fuin.porelo.core.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A credential's name as a project file or a run gives it: the vault entry a task holds, which
 * becomes the route's token file and the task's {@code PORELO_TOKEN_<NAME>} and {@code
 * PORELO_URL_<NAME>}.
 */
public final class CredentialName {

    /** Lower-case letters, digits, '_' and '-', a letter or digit first, at most 31. */
    public static final String PATTERN = "[a-z0-9][a-z0-9_-]{0,30}";

    private CredentialName() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns whether a name can become a file name and a variable's name.
     *
     * @param name The name as written.
     * @return {@code true} when it matches {@link #PATTERN}.
     */
    public static boolean allowed(final String name) {
        return name.matches(PATTERN);
    }

    /**
     * Returns what a name becomes in a variable's name: upper case, '-' as '_'.
     *
     * @param name The name.
     * @return The suffix of {@code PORELO_TOKEN_} and {@code PORELO_URL_}.
     */
    public static String variable(final String name) {
        return name.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Returns each pair of names that become one variable, the earlier name first.
     *
     * @param names The names, in the order written.
     * @return The pairs; empty when every name has a variable of its own.
     */
    public static List<List<String>> clashes(final Collection<String> names) {
        final Map<String, String> first = new LinkedHashMap<>();
        final List<List<String>> pairs = new ArrayList<>();
        for (final String name : names) {
            final String earlier = first.putIfAbsent(variable(name), name);
            if (earlier != null && !earlier.equals(name)) {
                pairs.add(List.of(earlier, name));
            }
        }
        return pairs;
    }
}
