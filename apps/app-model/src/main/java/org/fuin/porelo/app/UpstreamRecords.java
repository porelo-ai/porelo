package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.fuin.porelo.gate.UpstreamDistance;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * What was last measured about each project's distance from its upstream.
 *
 * <p>Measuring reaches the network, so nothing does it on the way to answering a question. This is
 * where the answer waits in between: written by whatever measured, read by anything reporting, and
 * always carrying the time it was taken so an interface can say how much it is worth.
 *
 * <p><strong>One file per project, like the project registry.</strong> A single shared file was
 * measured losing entries under concurrent writers, and this is written from a timer while it is
 * read by every listing - so the same shape is used for the same reason. A unique temporary name
 * per write, because two writers sharing one would trade halves of each other's content.
 */
final class UpstreamRecords {

    private final Path directory;

    /**
     * Constructor with the directory to keep.
     *
     * @param directory Where one file per project lives.
     */
    UpstreamRecords(Path directory) {
        this.directory = directory;
    }

    /**
     * Returns the key one repository's measurement is stored under.
     *
     * <p><strong>The project's own repository keeps the project's own key</strong>, for the reason
     * its mirror keeps its own path: what was measured before repositories existed was this, and a
     * rename would throw it away for no gain. The others are {@code <project>.<repository>}, which
     * cannot collide - both halves are lower-case letters, digits and hyphens, so the dot belongs
     * to neither.
     *
     * @param project Project name.
     * @param repository Repository name.
     * @return A file name.
     */
    static String key(String project, String repository) {
        return GateSupport.recordKey(project, repository);
    }

    /**
     * Records what the mirror's own measure found, keeping what central's last answer said.
     *
     * @param key The repository's key.
     * @param distance What was measured, whose own central part is not read.
     * @param measured The mirror it was measured in; once removed, nothing is recorded.
     */
    void putMeasured(String key, UpstreamDistance.Distance distance, Path measured) {
        merging(key, measured, () -> put(key, distance.withCentral(get(key).central())));
    }

    /**
     * Records what central's answer says, keeping what the mirror's own measure found.
     *
     * @param key The repository's key.
     * @param central What the host answered of it.
     * @param read The project's file it was asked for; once removed, nothing is recorded.
     */
    void putCentral(String key, UpstreamDistance.Central central, Path read) {
        merging(key, read, () -> put(key, get(key).withCentral(central)));
    }

    /**
     * Removes a record under its lock, so a half being written now does not bring it back.
     *
     * @param key The repository's key.
     * @throws IOException If it cannot be removed.
     */
    void remove(String key) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        FileLocks.holding(
                directory.resolve("." + key + ".lock"),
                () -> Files.deleteIfExists(directory.resolve(key)));
    }

    /**
     * Writes one half of a record under its lock: the timer and SyncUpstream write the two halves
     * from two threads, and a half read before the other's write would drop it. A project removed
     * while it was measured is removed before its record, under this lock; its record is not
     * written back.
     */
    private void merging(final String key, final Path stillThere, final Runnable write) {
        try {
            Directories.create(directory);
            FileLocks.holding(
                    directory.resolve("." + key + ".lock"),
                    () -> {
                        if (Files.exists(stillThere)) {
                            write.run();
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot record the upstream distance of " + key, ex);
        }
    }

    /**
     * Records what was measured for one project.
     *
     * @param project Project name.
     * @param distance What was measured.
     */
    void put(String project, UpstreamDistance.Distance distance) {
        try {
            Directories.create(directory);
            final Path temporary =
                    directory.resolve(
                            "."
                                    + project
                                    + "."
                                    + ProcessHandle.current().pid()
                                    + "."
                                    + Thread.currentThread().threadId());
            Files.writeString(temporary, line(distance), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    directory.resolve(project),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // A measurement nobody can store is not a reason to stop measuring the others, and it
            // is certainly not a reason to fail whatever asked.
            throw new UncheckedIOException("Cannot record the upstream distance of " + project, ex);
        }
    }

    /**
     * Returns what was last measured for a project.
     *
     * @param project Project name.
     * @return The distance, or a never-checked answer when nothing has been recorded or the record
     *     cannot be read. An unreadable record reads as never checked on purpose: it is the one
     *     answer that is true whatever the file said.
     */
    UpstreamDistance.Distance get(String project) {
        final Path file = directory.resolve(project);
        if (!Files.isRegularFile(file)) {
            return UpstreamDistance.Distance.neverChecked();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return UpstreamDistance.Distance.neverChecked();
        }
    }

    private static String line(UpstreamDistance.Distance distance) {
        return String.join(
                        "\t",
                        distance.reason().name(),
                        String.valueOf(distance.behind()),
                        distance.measured() == null ? "" : distance.measured().toString(),
                        distance.detail() == null ? "" : distance.detail().replace('\t', ' '),
                        // Comma-separated in a tab-separated line: a ref name cannot contain
                        // either, so neither separator can be produced by the content.
                        String.join(",", distance.alreadyUpstream()),
                        centralColumns(distance.central()))
                + "\n";
    }

    /** Four columns, all empty where no host answered: a reader must not take that for 0. */
    private static String centralColumns(UpstreamDistance.@Nullable Central central) {
        if (central == null) {
            return "\t\t\t";
        }
        return String.join(
                "\t",
                central.ahead() == null ? "" : String.valueOf(central.ahead()),
                central.aheadMeasured() == null ? "" : central.aheadMeasured().toString(),
                central.state(),
                central.why() == null ? "" : central.why().replace('\t', ' '));
    }

    /** The answer central's columns hold, or {@code null} for a record that has none. */
    private static UpstreamDistance.@Nullable Central central(String[] parts) {
        if (parts.length <= 7 || parts[7].isEmpty()) {
            // Five or six columns: written before a host's answer was recorded, which is no answer.
            return null;
        }
        return new UpstreamDistance.Central(
                parts[7],
                parts[5].isEmpty() ? null : Integer.valueOf(parts[5]),
                instant(parts[6]),
                parts.length > 8 && !parts[8].isEmpty() ? parts[8] : null);
    }

    private static UpstreamDistance.Distance parse(String text) {
        final String[] parts = text.strip().split("\t", -1);
        final UpstreamDistance.Reason reason = UpstreamDistance.Reason.valueOf(parts[0]);
        return new UpstreamDistance.Distance(
                parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                instant(parts.length > 2 ? parts[2] : ""),
                reason,
                parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null,
                // A record written before this field existed has no fifth column, and reads as
                // "nothing is known to be upstream" - which leaves every entry in the queue, the
                // safe direction.
                parts.length > 4 && !parts[4].isEmpty()
                        ? java.util.List.of(parts[4].split(","))
                        : java.util.List.of(),
                central(parts));
    }

    @Nullable
    private static Instant instant(String text) {
        return text.isEmpty() ? null : Instant.parse(text);
    }
}
