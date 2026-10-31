package org.fuin.porelo.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;

/**
 * The registries this machine read, as {@code registry read} keeps them under {@code
 * $XDG_STATE_HOME/porelo/registry/}: a trust record {@code <sha256 of the origin>.json} and beside
 * it {@code <sha256>.where.json}, naming the origin and the checkout read last.
 *
 * <p>Every record is taken, or the whole answer is refused: a record left out may be the one naming
 * a key revoked.
 */
public final class TrustRecords {

    /** A trust record's name: the SHA-256 of its origin URL. */
    private static final Pattern RECORD = Pattern.compile("([0-9a-f]{64})\\.json");

    /** The record of where that origin was read. */
    private static final Pattern WHERE = Pattern.compile("([0-9a-f]{64})\\.where\\.json");

    /** The longest record read. */
    private static final long LIMIT = 64L * 1024 * 1024;

    private TrustRecords() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A registry this machine read.
     *
     * @param origin Its origin URL, exactly as the checkout named it.
     * @param checkout The checkout read last for that origin.
     * @param file The trust record.
     * @param state What its last read trusted.
     */
    public record Read(String origin, Path checkout, Path file, RegistryState state) {}

    /**
     * Where the trust record of an origin is kept.
     *
     * @param state Porelo's directory in {@code $XDG_STATE_HOME}.
     * @param origin The origin URL.
     * @return Its path, whether or not it exists.
     */
    public static Path file(final Path state, final String origin) {
        try {
            return state.resolve("registry")
                    .resolve(
                            HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(
                                                                    origin.getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))
                                    + ".json");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Every registry this machine read, in the order of their records' names.
     *
     * @param state Porelo's directory in {@code $XDG_STATE_HOME}.
     * @return Each; none when nothing was read.
     * @throws IOException When a directory on the way is a link or another's, or a record is
     *     missing beside its pair, a link, no regular file, too large, or does not read: naming the
     *     file, never quoting it.
     */
    public static List<Read> read(final Path state) throws IOException {
        final Path directory = state.resolve("registry");
        if (!PrivateDirectory.found(state, directory)) {
            return List.of();
        }
        final SortedSet<String> names = new TreeSet<>();
        try (var listed = Files.list(directory)) {
            for (final Path each : listed.toList()) {
                final String name = each.getFileName().toString();
                final Matcher record = RECORD.matcher(name);
                final Matcher where = WHERE.matcher(name);
                if (record.matches()) {
                    names.add(record.group(1));
                } else if (where.matches()) {
                    names.add(where.group(1));
                }
            }
        }
        final List<Read> read = new ArrayList<>();
        for (final String name : names) {
            final Path record = directory.resolve(name + ".json");
            final Path where = directory.resolve(name + ".where.json");
            final Object located;
            try {
                located = Json.parse(text(where));
            } catch (RuntimeException ex) {
                throw new IOException(where + " does not read as where a registry was read");
            }
            // A record of another origin's name would answer for an address it is not.
            if (!(located instanceof Map<?, ?> map)
                    || !(map.get("origin") instanceof String origin)
                    || !(map.get("checkout") instanceof String checkout)
                    || !file(state, origin).equals(record)) {
                throw new IOException(where + " does not read as where a registry was read");
            }
            final RegistryState trusted;
            try {
                trusted = RegistryState.fromJson(text(record));
            } catch (CardException ex) {
                throw new IOException(record + " does not read as a trust record");
            }
            final Path at;
            try {
                at = Path.of(checkout);
            } catch (java.nio.file.InvalidPathException ex) {
                throw new IOException(where + " does not read as where a registry was read");
            }
            read.add(new Read(origin, at, record, trusted));
        }
        return List.copyOf(read);
    }

    /** A record's text, read only from a regular file that is no link, bounded. */
    private static String text(final Path file) throws IOException {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT) {
                throw new IOException("not a regular file of a record's size");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException ex) {
            throw new IOException(
                    "cannot read the registry trust record "
                            + file
                            + " (missing, a link or not a regular file)");
        }
    }
}
