package org.fuin.porelo.shield;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
     * Returns the name most recently answered with an address: under a CNAME, the name the
     * container asked for, not the name the chain ended at.
     *
     * @param log Contents of the resolver's log.
     * @param address Address to look up.
     * @return The name, or empty when the resolver never answered it.
     */
    public static Optional<String> nameFor(String log, String address) {
        String found = null;
        // The name a chain of CNAMEs started at, while its lines last.
        String asked = null;
        // The name the chain ended at, whose addresses are the asked name's.
        String target = null;
        boolean inChain = false;
        for (final String line : log.split("\\R")) {
            // 'reply' is a fresh answer and 'cached' is a repeat of one; both say what the
            // container was told, which is what the operator is being asked about.
            if (line.contains(" query[")) {
                // A new question: what follows answers it, not the chain before.
                asked = null;
                target = null;
                inChain = false;
                continue;
            }
            final int marker = markerAt(line);
            final int is = line.lastIndexOf(" is ");
            if (marker < 0 || is < marker) {
                continue;
            }
            final String name = line.substring(marker, is).strip();
            final String value = line.substring(is + " is ".length()).strip();
            if (name.isEmpty() || name.contains(" ")) {
                continue;
            }
            if (value.equals(CNAME)) {
                // dnsmasq logs a chain as consecutive lines, the name asked for first.
                if (!inChain) {
                    asked = name;
                }
                inChain = true;
                continue;
            }
            final String owner;
            if (inChain) {
                target = name;
                owner = asked;
            } else if (name.equals(target) && asked != null) {
                owner = asked;
            } else {
                asked = null;
                target = null;
                owner = name;
            }
            inChain = false;
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
