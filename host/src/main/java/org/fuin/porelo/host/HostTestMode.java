package org.fuin.porelo.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;

/**
 * The test switches root's role sets on a host, in {@code /etc/porelo-host/test-mode.json}.
 *
 * <p>Root writes the file at up and removes it at down. It counts only as a regular file, not a
 * link, root's, mode {@code 0600}, in root's directories that neither group nor others can write,
 * holding {@code {"v": 1, "switches": [...], "why": "..."}}. Only names known here are read. A file
 * that is missing, of another {@code v}, unreadable or failing a check holds no switch, never all.
 */
final class HostTestMode {
    static final String FILE = "/etc/porelo-host/test-mode.json";

    /** A task may start without a certificate, for the project {@code default}. */
    static final String START_UNCERTIFIED = "start-uncertified";

    private static final Set<String> KNOWN = Set.of(START_UNCERTIFIED);

    private HostTestMode() {}

    /**
     * Returns the switches that hold.
     *
     * @param files Root's file tree.
     * @return The known switches the file names, sorted; none where it fails any check.
     */
    static List<String> switches(HostFiles files) {
        final Path path = files.path(FILE);
        try {
            if (!files.check(path, 69, false, false, 0600)) {
                return List.of();
            }
            if (!(Json.parseExact(files.read(path)) instanceof java.util.Map<?, ?> content)
                    || !Long.valueOf(1).equals(content.get("v"))
                    || !(content.get("switches") instanceof java.util.List<?> names)) {
                return List.of();
            }
            final Set<String> held = new TreeSet<>();
            for (Object name : names) {
                if (!(name instanceof String text)) {
                    return List.of();
                }
                if (KNOWN.contains(text)) {
                    held.add(text);
                }
            }
            return List.copyOf(held);
        } catch (HostRefusal | JsonException | IllegalArgumentException ex) {
            // A link, another owner or mode, a writable directory or an unreadable file: none.
            return List.of();
        }
    }

    static boolean holds(HostFiles files, String name) {
        return switches(files).contains(name);
    }
}
