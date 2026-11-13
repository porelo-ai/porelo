package org.fuin.porelo.shield;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What name the container was given for an address.
 *
 * <p>A blocked connection carries an address and nothing else, so an operator is asked to judge a
 * bare number. The resolver logged what it answered, which is the only place that number can be
 * turned back into the name the agent actually asked for.
 */
public final class ResolvedNames {

    private ResolvedNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What dnsmasq logs for an answer that is a CNAME rather than an address. */
    private static final String CNAME = "<CNAME>";

    /**
     * A line of {@code log-queries=extra}: the query's serial and its client before the verb, so
     * the lines of one query are told from another's however they interleave.
     */
    private static final Pattern EXTRA =
            Pattern.compile(
                    "dnsmasq\\[\\d+\\]: (\\d+) \\S+/\\d+ (query\\[[A-Za-z0-9]+\\]|reply|cached)"
                            + " (\\S+) (?:from|is) (.+)$");

    /**
     * Returns the name most recently answered with an address: under a CNAME, the name the
     * container asked for, not the name the chain ended at.
     *
     * <p>A chain is followed only within one query, by the serial dnsmasq gives it with {@code
     * log-queries=extra}: an answer is the asked name's only when that same query's reply began
     * with the asked name as a CNAME. A line without a serial, or another query's, keeps its own
     * name, so an address never takes a grant from a name it was not the answer to.
     *
     * @param log Contents of the resolver's log.
     * @param address Address to look up.
     * @return The name, or empty when the resolver never answered it.
     */
    public static Optional<String> nameFor(String log, String address) {
        String found = null;
        // Per query serial: the name asked, and whether its reply began as a CNAME of it.
        final Map<String, String> asked = new HashMap<>();
        final Set<String> chained = new HashSet<>();
        for (final String line : log.split("\\R")) {
            final Matcher extra = EXTRA.matcher(line);
            final String serial;
            final String name;
            final String value;
            if (extra.find()) {
                serial = extra.group(1);
                name = extra.group(3);
                value = extra.group(4).strip();
                if (extra.group(2).startsWith("query[")) {
                    // A new question under this serial: what follows answers it alone.
                    asked.put(serial, name);
                    chained.remove(serial);
                    continue;
                }
            } else {
                // 'reply' is a fresh answer and 'cached' is a repeat of one; both say what the
                // container was told, which is what the operator is being asked about.
                final int marker = markerAt(line);
                final int is = line.lastIndexOf(" is ");
                if (marker < 0 || is < marker) {
                    continue;
                }
                serial = null;
                name = line.substring(marker, is).strip();
                value = line.substring(is + " is ".length()).strip();
            }
            if (name.isEmpty() || name.contains(" ")) {
                continue;
            }
            if (value.equals(CNAME)) {
                if (serial != null && name.equals(asked.get(serial))) {
                    chained.add(serial);
                }
                continue;
            }
            final String owner =
                    serial != null && chained.contains(serial) ? asked.get(serial) : name;
            if (value.equals(address)) {
                // Keep looking: the last answer is the one that led to this connection.
                found = owner;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * Returns where the name starts on a line that answers a query.
     *
     * @param line One log line.
     * @return Index after the marker, or -1 when the line answers nothing.
     */
    private static int markerAt(String line) {
        for (final String marker : List.of(" reply ", " cached ")) {
            final int at = line.indexOf(marker);
            if (at >= 0) {
                return at + marker.length();
            }
        }
        return -1;
    }

    /**
     * Reads the resolver's log and returns the name for an address.
     *
     * @param log Path of the resolver log, or {@code null} when there is none.
     * @param address Address to look up.
     * @return The name, or empty when it cannot be answered.
     */
    public static Optional<String> lookup(Path log, String address) {
        if (log == null || !Files.isReadable(log)) {
            return Optional.empty();
        }
        try {
            return nameFor(Files.readString(log, StandardCharsets.UTF_8), address);
        } catch (IOException | RuntimeException ex) {
            // Naming a destination is a courtesy; failing to do it must not stop the prompt.
            return Optional.empty();
        }
    }
}
