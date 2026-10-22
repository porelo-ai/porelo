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

    private SystemProgram() {}

    /**
     * Returns a checked absolute program path.
     *
     * <p>Root is the host's root as this process's user namespace sees it: a hook runs inside
     * rootless podman's namespace, where the caller itself is uid 0 and the host's root reads as
     * the kernel's overflow uid.
     *
     * @param name Program name without directory components.
     * @return Root-owned executable in a root-owned directory.
     * @throws UncheckedIOException If the program is absent or unsafe, or the namespace's map
     *     cannot be read.
     */
    public static Path find(String name) {
        try {
            final int root =
                    rootAsSeenHere(
                            proc("/proc/self/uid_map"),
                            Integer.parseInt(proc("/proc/sys/kernel/overflowuid").strip()));
            return find(
                    name, DIRECTORIES, path -> Files.readAttributes(path, "unix:uid,mode"), root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
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
     * Returns the uid the host's root has in a user namespace.
     *
     * @param uidMap The namespace's {@code uid_map}: inside, outside and count per line.
     * @param overflow The uid an unmapped owner reads as.
     * @return Where host uid 0 is mapped to, or {@code overflow} when it is not mapped.
     */
    static int rootAsSeenHere(String uidMap, int overflow) {
        for (final String line : uidMap.lines().toList()) {
            final java.util.StringTokenizer fields = new java.util.StringTokenizer(line);
            if (fields.countTokens() != 3) {
                continue;
            }
            final long inside = Long.parseLong(fields.nextToken());
            final long outside = Long.parseLong(fields.nextToken());
            final long count = Long.parseLong(fields.nextToken());
            if (outside <= 0 && 0 < outside + count) {
                return Math.toIntExact(inside - outside);
            }
        }
        return overflow;
    }

    @FunctionalInterface
    interface Attributes {
        Map<String, Object> read(Path path) throws IOException;
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
            searched.add(candidate);
            try {
                Files.readAttributes(candidate, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException ex) {
                continue;
            }
            checkDirectories(candidate.getParent(), attributes, root);
            final Path resolved = candidate.toRealPath();
            checkDirectories(resolved.getParent(), attributes, root);
            final int mode = check(resolved, attributes, root);
            if ((mode & 0170000) != 0100000 || !Files.isExecutable(resolved)) {
                throw new IOException("Refusing " + resolved + ": not a regular executable file");
            }
            return resolved;
        }
        throw new IOException("Program " + name + " not found at " + searched);
    }

    private static void checkDirectories(@Nullable Path directory, Attributes attributes, int root)
            throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            final int mode = check(current, attributes, root);
            if ((mode & 0170000) != 0040000) {
                throw new IOException("Refusing " + current + ": not a directory");
            }
        }
    }

    private static int check(Path path, Attributes attributes, int root) throws IOException {
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
