package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Names what is in a task's inbox that Porelo did not deliver: once each, in the message record,
 * where {@code task status} reads it.
 *
 * <p>The inbox is writable from inside the container, so a dependency's install script can put a
 * file there that reads like a person's message. The host never reads such a file; a person should
 * still learn that one is there, because whatever wrote it is running in the task.
 */
final class InboxPlants {

    /**
     * How many are ever named for one mailbox: a process that writes files without end must not
     * write the record without end too.
     */
    static final int MOST = 100;

    /** How many entries of the inbox {@link #present} looks at, at most. */
    static final int LOOKED = 1000;

    /** Why a planted file is in the record. */
    static final String DETAIL = "not delivered by Porelo, so not read";

    private InboxPlants() {}

    /**
     * Records each file in {@code inbox/new} and {@code inbox/cur} that Porelo did not deliver and
     * that is not recorded yet.
     *
     * @param mailbox The task's mailbox.
     * @return The names recorded now.
     * @throws IOException If the inbox or the record cannot be read or written.
     */
    static List<String> note(final Mailbox mailbox) throws IOException {
        mailbox.refuseLinks();
        final MessageRecord record = new MessageRecord(mailbox);
        final Set<String> known = new LinkedHashSet<>(planted(record));
        final List<String> found = new ArrayList<>();
        for (final Path directory : List.of(mailbox.inboxNew(), mailbox.inboxCur())) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> listed = Files.list(directory)) {
                for (final Path path : (Iterable<Path>) listed::iterator) {
                    final String name = path.getFileName().toString();
                    if (known.size() >= MOST) {
                        break;
                    }
                    if (!mailbox.delivered(path) && known.add(name)) {
                        found.add(name);
                    }
                }
            }
        }
        for (final String name : found) {
            record.append(MessageRecord.PLANTED, name, "", "", DETAIL, MessageRecord.IN);
        }
        return found;
    }

    /**
     * Returns the names in {@code inbox/new} and {@code inbox/cur} now that Porelo did not deliver,
     * looking at no more than {@link #LOOKED} entries, so a person sees what is there today and not
     * what the record named once.
     *
     * @param mailbox The task's mailbox.
     * @return The names, at most {@link #LOOKED}.
     * @throws IOException If the inbox cannot be listed.
     */
    static List<String> present(final Mailbox mailbox) throws IOException {
        mailbox.refuseLinks();
        final List<String> found = new ArrayList<>();
        int looked = 0;
        for (final Path directory : List.of(mailbox.inboxNew(), mailbox.inboxCur())) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> listed = Files.list(directory)) {
                for (final Path path : (Iterable<Path>) listed::iterator) {
                    if (++looked > LOOKED) {
                        return found;
                    }
                    if (!mailbox.delivered(path)) {
                        found.add(path.getFileName().toString());
                    }
                }
            }
        }
        return found;
    }

    /**
     * Returns the names already recorded as planted, in the order they were found.
     *
     * @param record The task's message record.
     * @return The names.
     * @throws IOException If the record cannot be read.
     */
    static List<String> planted(final MessageRecord record) throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        for (final Map<String, Object> line : record.entries()) {
            if (MessageRecord.PLANTED.equals(line.get("event"))) {
                names.add(String.valueOf(line.get("message")));
            }
        }
        return List.copyOf(names);
    }
}
