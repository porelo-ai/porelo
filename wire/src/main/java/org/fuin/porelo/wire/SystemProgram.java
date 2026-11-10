package org.fuin.porelo.wire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Finds distribution programs without trusting the caller's PATH. */
public final class SystemProgram {

    private static final List<Path> DIRECTORIES =
            List.of(Path.of("/usr/sbin"), Path.of("/usr/bin"), Path.of("/sbin"), Path.of("/bin"));

    private static final long UID_LIMIT = 4294967295L;

    /** A checked search found no program, distinct from an installed but unsafe program. */
    public static final class Missing extends IOException {
        /** Names the program and fixed directories searched, without any caller arguments. */
        public Missing(String name, List<Path> searched) {
            super("Program " + name + " not found at " + searched);
        }
    }

    private SystemProgram() {}

    /**
     * Returns a checked absolute program path.
     *
     * <p>Root is the host's root as this process's user namespace sees it: a hook runs inside
     * rootless podman's namespace, where the caller itself is uid 0. Unmapped owners share the
     * kernel's overflow uid; only programs directly in the fixed distribution directories may use
     * that owner, and only when the map contains no outside uid 0.
     *
     * @param name Program name without directory components.
     * @return Root-owned executable in a root-owned directory.
     * @throws UncheckedIOException If the program is absent or unsafe, or the namespace's map
     *     cannot be read.
     */
    public static Path find(String name) {
        return found(name, DIRECTORIES);
    }

    /**
     * Searches a caller's fixed system directories with the same ownership and executable checks.
     * The directory list must come from program constants, never from the environment. Unmapped
     * ownership is refused outside the fixed distribution directories.
     */
    public static Path find(String name, List<Path> directories) {
        return found(name, List.copyOf(directories));
    }

    /**
     * Returns a root's program at the one absolute path it is installed at, checked as {@link
     * #find(String)} checks a distribution's.
     *
     * @param program The program's absolute path.
     * @return Root-owned executable in a root-owned directory.
     * @throws UncheckedIOException If the program is absent or unsafe.
     */
    public static Path at(Path program) {
        final Path directory = program.getParent();
        if (!program.isAbsolute() || directory == null || program.getFileName() == null) {
            throw new IllegalArgumentException("Expected an absolute program path: " + program);
        }
        return found(program.getFileName().toString(), List.of(directory));
    }

    private static Path found(String name, List<Path> directories) {
        try {
            final int root = rootAsSeenHere();
            return find(
                    name, directories, path -> Files.readAttributes(path, "unix:uid,mode"), root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        }
    }

    static int rootAsSeenHere() throws IOException {
        try {
            return rootAsSeenHere(
                    proc("/proc/self/uid_map"),
                    Integer.parseInt(proc("/proc/sys/kernel/overflowuid").strip()));
        } catch (NumberFormatException ex) {
            throw new IOException("Cannot establish root ownership: unsupported overflow uid", ex);
        }
    }

    /**
     * Reads a procfs file to its end: procfs reports a size of 0, which a sized read takes as all.
     */
    private static String proc(String file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(Path.of(file))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    /**
     * Identifies the initial namespace's root or the limited distribution-program exception.
     *
     * @param uidMap The namespace's {@code uid_map}: inside, outside and count per line.
     * @param overflow The uid an unmapped owner reads as.
     * @return Zero for the initial namespace's full identity map, or {@code overflow} when no
     *     outside uid 0 is mapped. The latter is not proof of root ownership.
     * @throws IOException If the map is uncertain or maps root outside the initial namespace.
     */
    static int rootAsSeenHere(String uidMap, int overflow) throws IOException {
        final List<UidRange> ranges = new ArrayList<>();
        for (final String line : uidMap.lines().toList()) {
            if (line.isBlank()) continue;
            final UidRange range = UidRange.parse(line);
            for (UidRange previous : ranges) {
                if (range.overlaps(previous)) throw invalidMap();
            }
            ranges.add(range);
        }
        if (ranges.isEmpty()) throw invalidMap();
        // A child's short identity map names its parent, not the host's initial namespace.
        if (ranges.size() == 1 && ranges.getFirst().equals(new UidRange(0, 0, UID_LIMIT))) {
            return 0;
        }
        if (ranges.stream().anyMatch(range -> range.outside() == 0)) {
            throw new IOException(
                    "Cannot establish root ownership: uid_map maps outside uid 0 without the"
                            + " initial namespace's full identity range");
        }
        if (overflow <= 0) {
            throw new IOException(
                    "Cannot establish root ownership: an unmapped owner needs a positive overflow uid");
        }
        return overflow;
    }

    private static IOException invalidMap() {
        return new IOException(
                "Cannot establish root ownership: uid_map has missing, invalid or overlapping uid ranges");
    }

    private record UidRange(long inside, long outside, long count) {
        static UidRange parse(String line) throws IOException {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length != 3
                    || !fields[0].matches("[0-9]+")
                    || !fields[1].matches("[0-9]+")
                    || !fields[2].matches("[0-9]+")) {
                throw invalidMap();
            }
            final UidRange range;
            try {
                range =
                        new UidRange(
                                Long.parseLong(fields[0]),
                                Long.parseLong(fields[1]),
                                Long.parseLong(fields[2]));
            } catch (NumberFormatException ex) {
                throw new IOException(
                        "Cannot establish root ownership: invalid uid_map number", ex);
            }
            if (range.inside >= UID_LIMIT
                    || range.outside >= UID_LIMIT
                    || range.count < 1
                    || range.count > UID_LIMIT
                    || range.inside + range.count > UID_LIMIT
                    || range.outside + range.count > UID_LIMIT) {
                throw invalidMap();
            }
            return range;
        }

        boolean overlaps(UidRange other) {
            return inside < other.inside + other.count && other.inside < inside + count
                    || outside < other.outside + other.count && other.outside < outside + count;
        }
    }

    @FunctionalInterface
    interface Attributes {
        Map<String, Object> read(Path path) throws IOException;

        default void exists(Path path) throws IOException {
            Files.readAttributes(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        }

        default Path realPath(Path path) throws IOException {
            return path.toRealPath();
        }

        default boolean executable(Path path) {
            return Files.isExecutable(path);
        }
    }

    static Path find(String name, List<Path> directories, Attributes attributes)
            throws IOException {
        return find(name, directories, attributes, 0);
    }

    static Path find(String name, List<Path> directories, Attributes attributes, int root)
            throws IOException {
        if (name.isBlank() || !Path.of(name).getFileName().toString().equals(name)) {
            throw new IllegalArgumentException(
                    "Expected a program name without directories: " + name);
        }
        final ArrayList<Path> searched = new ArrayList<>();
        for (Path directory : directories) {
            final Path candidate = directory.resolve(name).toAbsolutePath();
            checkDistributionPath(candidate, root);
            searched.add(candidate);
            try {
                attributes.exists(candidate);
            } catch (NoSuchFileException ex) {
                continue;
            }
            checkDirectories(candidate.getParent(), attributes, root);
            final Path resolved = attributes.realPath(candidate);
            checkDistributionPath(resolved, root);
            checkDirectories(resolved.getParent(), attributes, root);
            final int mode = checkOwnerAndMode(resolved, attributes, root);
            if ((mode & 0170000) != 0100000 || !attributes.executable(resolved)) {
                throw new IOException("Refusing " + resolved + ": not a regular executable file");
            }
            return resolved;
        }
        throw new Missing(name, searched);
    }

    private static void checkDirectories(@Nullable Path directory, Attributes attributes, int root)
            throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            final int mode = checkOwnerAndMode(current, attributes, root);
            if ((mode & 0170000) != 0040000) {
                throw new IOException("Refusing " + current + ": not a directory");
            }
        }
    }

    static int check(Path path, Attributes attributes, int root) throws IOException {
        if (root != 0) {
            throw new IOException(
                    "Refusing "
                            + path
                            + ": not owned by root with certainty; unmapped owners share the overflow uid");
        }
        return checkOwnerAndMode(path, attributes, root);
    }

    private static void checkDistributionPath(Path path, int root) throws IOException {
        if (root != 0
                && (!path.isAbsolute()
                        || !path.equals(path.normalize())
                        || path.getParent() == null
                        || !DIRECTORIES.contains(path.getParent()))) {
            throw new IOException(
                    "Refusing "
                            + path
                            + ": unmapped owners cannot be distinguished from root outside the fixed distribution directories");
        }
    }

    private static int checkOwnerAndMode(Path path, Attributes attributes, int root)
            throws IOException {
        final Map<String, Object> values = attributes.read(path);
        if (!Integer.valueOf(root).equals(values.get("uid"))) {
            throw new IOException(
                    "Refusing "
                            + path
                            + ": not owned by root (owner uid "
                            + values.get("uid")
                            + ", root is uid "
                            + root
                            + " here)");
        }
        final int mode =
                ((Number)
                                java.util.Objects.requireNonNull(
                                        values.get("mode"),
                                        "Unix file attributes must include mode"))
                        .intValue();
        if ((mode & 0022) != 0) {
            throw new IOException("Refusing " + path + ": writable by group or others");
        }
        return mode;
    }
}
