package org.fuin.porelo.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The host's kept data and the accounts whose commands can copy it. */
final class StoreEntries {
    enum Schedule {
        HOURLY,
        DOWN
    }

    record Entry(
            String name,
            String account,
            List<String> backup,
            List<String> restore,
            int keep,
            Schedule schedule) {
        Entry {
            backup = List.copyOf(backup);
            restore = List.copyOf(restore);
        }

        List<String> backup(Path directory) {
            return command(backup, directory);
        }

        List<String> restore(Path directory) {
            return command(restore, directory);
        }

        private static List<String> command(List<String> words, Path directory) {
            return words.stream()
                    .map(word -> word.equals("<dir>") ? directory.toString() : word)
                    .toList();
        }
    }

    private StoreEntries() {}

    static List<Entry> read(HostFiles files) {
        final List<Entry> entries = new ArrayList<>();
        entries.add(
                new Entry(
                        "room",
                        "porelo-matrix",
                        List.of("porelo-message-matrix", "backup", "<dir>"),
                        List.of("porelo-message-matrix", "restore", "<dir>"),
                        48,
                        Schedule.HOURLY));
        for (final String login : new HostRecords(files).read().keySet()) {
            entries.add(
                    new Entry(
                            "usage-" + login,
                            login,
                            List.of("porelo", "keep", "<dir>"),
                            List.of("porelo", "keep", "--restore", "<dir>"),
                            48,
                            Schedule.HOURLY));
        }
        return List.copyOf(entries);
    }

    static List<Entry> due(List<Entry> entries, boolean all) {
        return entries.stream()
                .filter(entry -> all || entry.schedule() == Schedule.HOURLY)
                .toList();
    }
}
