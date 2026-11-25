package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The machine's forge instances, kept in {@code forges.yml} in the configuration directory beside
 * {@code credentials.yml}: one record per host, naming the adapter that reads it and how its
 * comments are read.
 *
 * <pre>
 * forges:
 *   - host: github.com
 *     adapter: github
 *     comments: trusted
 * </pre>
 *
 * <p>A record's comments are read trusted only when it says {@code trusted} and the file is this
 * account's alone: a file another account owns, or one others may write, is read with every record
 * extended, since anyone could have written its {@code trusted}. A record that cannot be read
 * stands apart, extended and saying why, and the others stand; a file that cannot be read as a
 * whole is refused, so nothing is ever read as trusted from it. Only the owner account changes it,
 * through {@code porelo forge} or the daemon's socket.
 */
public final class ForgeInstanceFile implements ForgeInstances {

    /** The file's name in the configuration directory. */
    public static final String NAME = "forges.yml";

    /** A DNS name, lower case, as a record's host is kept. */
    private static final Pattern HOST =
            Pattern.compile(
                    "(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
                            + "(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    /** An adapter's name, as an installed one is named. */
    private static final Pattern ADAPTER = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    /** Writers in this process; the file lock keeps out those of another. */
    private static final Object WRITERS = new Object();

    private final Path file;

    private final ForgeAdapters adapters;

    /** One record as read: the instance, and what the file said, written back unchanged. */
    private record Entry(ForgeInstance instance, @Nullable Object written) {}

    /**
     * Reads and writes one file.
     *
     * @param file The file, which need not exist.
     * @param adapters Where a record's adapter is looked for when it is set.
     */
    public ForgeInstanceFile(final Path file, final ForgeAdapters adapters) {
        this.file = file;
        this.adapters = adapters;
    }

    /**
     * Returns this account's file.
     *
     * @param context The machine.
     * @return Its forge file, beside {@code credentials.yml}.
     */
    public static ForgeInstanceFile of(final PoreloContext context) {
        return new ForgeInstanceFile(
                context.paths().xdg().config().resolve(NAME), ForgeAdapters.of(context));
    }

    /**
     * Returns where the file is.
     *
     * @return The path.
     */
    public Path file() {
        return file;
    }

    /**
     * Returns why every record is read as extended whatever it says, for the log and {@code porelo
     * doctor}.
     *
     * @return The reason, naming the file; empty when the file is this account's alone, or absent,
     *     or cannot be looked at, which reading it then says.
     */
    public Optional<String> loosened() {
        try {
            return Optional.ofNullable(loosenedReason());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ForgeInstance> of(final URI url) throws ForgeFileUnreadable {
        final String host = GitCredentialNames.hostOf(url.toString());
        if (host == null) {
            return Optional.empty();
        }
        final String wanted = host.toLowerCase(Locale.ROOT);
        return all().stream().filter(instance -> instance.host().equals(wanted)).findFirst();
    }

    @Override
    public List<ForgeInstance> all() throws ForgeFileUnreadable {
        return read().stream().map(Entry::instance).toList();
    }

    @Override
    public void set(final String host, final String adapter, final CommentReading reading)
            throws ForgeFileUnreadable {
        final String named = host.strip().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(named).matches()) {
            throw new IllegalArgumentException("'" + host + "' is not a DNS name");
        }
        if (!adapters.installed(adapter)) {
            throw new AdapterNotInstalled(adapter);
        }
        synchronized (WRITERS) {
            try {
                org.fuin.porelo.wire.Directories.create(file.getParent());
                try (FileChannel channel =
                                FileChannel.open(
                                        file.resolveSibling(NAME + ".lock"),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    write(replaced(read(), named, adapter, reading));
                }
            } catch (IOException ex) {
                throw new ForgeFileUnreadable(file + " cannot be written: " + ex.getMessage());
            }
        }
    }

    /** The records to write: the host's in its place or last, every other as it was read. */
    private static List<Object> replaced(
            final List<Entry> entries,
            final String host,
            final String adapter,
            final CommentReading reading) {
        final List<Object> records = new ArrayList<>();
        boolean placed = false;
        for (final Entry entry : entries) {
            final ForgeInstance instance = entry.instance();
            if (instance.host().equals(host)) {
                if (!placed) {
                    records.add(record(host, adapter, reading));
                    placed = true;
                }
            } else if (instance.unreadable() != null) {
                // Kept for its owner to mend: dropping it would lose what they wrote.
                records.add(entry.written());
            } else {
                // As read, so a loosened file's trusted is not made genuine by being written again.
                records.add(
                        record(
                                instance.host(),
                                java.util.Objects.requireNonNull(
                                        instance.adapter(), "a readable record names its adapter"),
                                instance.reading()));
            }
        }
        if (!placed) {
            records.add(record(host, adapter, reading));
        }
        return records;
    }

    private static Map<String, Object> record(
            final String host, final String adapter, final CommentReading reading) {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("host", host);
        record.put("adapter", adapter);
        record.put("comments", reading.name().toLowerCase(Locale.ROOT));
        return record;
    }

    /** Writes the whole file at once, this account's alone: a reader sees the old or the new. */
    private void write(final List<Object> records) throws IOException {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        final String text =
                "# This machine's forge instances; change them with 'porelo forge'.\n"
                        + new Yaml(options).dump(Map.of("forges", records));
        final Path temporary =
                Files.createTempFile(
                        file.getParent(),
                        "." + NAME,
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Entry> read() throws ForgeFileUnreadable {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        final String loosened;
        final String text;
        try {
            loosened = loosenedReason();
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ForgeFileUnreadable(file + " cannot be read: " + ex.getMessage());
        }
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException ex) {
            throw new ForgeFileUnreadable(file + " is not YAML: " + firstLine(ex.getMessage()));
        }
        if (loaded == null) {
            return List.of();
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new ForgeFileUnreadable(file + " is not a mapping with a list of forges");
        }
        final Object forges = root.get("forges");
        if (forges == null) {
            return List.of();
        }
        if (!(forges instanceof List<?> records)) {
            throw new ForgeFileUnreadable(file + " holds forges that are not a list");
        }
        final List<Entry> entries = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < records.size(); index++) {
            final Object written = records.get(index);
            entries.add(new Entry(instance(written, index, loosened != null, seen), written));
        }
        return List.copyOf(entries);
    }

    /** One record, or why it cannot be read. */
    private static ForgeInstance instance(
            final @Nullable Object written,
            final int index,
            final boolean loosened,
            final Set<String> seen) {
        final String label = "entry " + (index + 1);
        if (!(written instanceof Map<?, ?> record)) {
            return unreadable(label, "it is not a mapping of host, adapter and comments");
        }
        final String host =
                record.get("host") instanceof String given
                        ? given.strip().toLowerCase(Locale.ROOT)
                        : "";
        if (!HOST.matcher(host).matches()) {
            return unreadable(host.isBlank() ? label : host, "its host is not a DNS name");
        }
        if (!seen.add(host)) {
            return unreadable(host, "its host is listed before");
        }
        if (!(record.get("adapter") instanceof String adapter)
                || !ADAPTER.matcher(adapter).matches()) {
            return unreadable(host, "its adapter is not an adapter's name");
        }
        final Object comments = record.get("comments");
        final CommentReading reading;
        if (comments == null || "extended".equals(comments)) {
            reading = CommentReading.EXTENDED;
        } else if ("trusted".equals(comments)) {
            reading = loosened ? CommentReading.EXTENDED : CommentReading.TRUSTED;
        } else {
            return unreadable(host, "its comments are neither extended nor trusted");
        }
        return new ForgeInstance(host, adapter, reading, null);
    }

    private static ForgeInstance unreadable(final String host, final String why) {
        return new ForgeInstance(host, null, CommentReading.EXTENDED, why);
    }

    /** Why another account could have written the file; null when only this one could. */
    private @Nullable String loosenedReason() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        final PosixFileAttributes attributes =
                Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            return file + " is a symbolic link, so every forge's comments are read as extended";
        }
        if (!attributes.owner().equals(Files.getOwner(Path.of("/proc/self")))) {
            return file
                    + " belongs to another account, so every forge's comments are read as extended";
        }
        final Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            return file
                    + " can be written by others than this account, so every forge's comments are"
                    + " read as extended; 'chmod 600' it";
        }
        return null;
    }

    private static String firstLine(final @Nullable String message) {
        if (message == null) {
            return "it cannot be parsed";
        }
        final int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
