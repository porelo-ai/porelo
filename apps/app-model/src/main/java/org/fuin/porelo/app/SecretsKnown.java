package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.gate.SecretScan;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;

/**
 * The findings of a workspace's secret scan that a person acknowledged as false, for one project.
 *
 * <p>Kept on the host under the state directory, owner-only, never in the repository: a list the
 * agent could write would let it silence the scan. Each entry is a repository, a place and the
 * digest of the line found there, so it holds only while that line stays as it was.
 */
public final class SecretsKnown {

    private static final String STAGED = ".tmp";

    private final Path file;

    /**
     * Constructor.
     *
     * @param file The list's file.
     */
    public SecretsKnown(final Path file) {
        this.file = file;
    }

    /**
     * Returns the list of one project on this machine.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return The list.
     */
    public static SecretsKnown of(final PoreloContext context, final String project) {
        return new SecretsKnown(context.paths().projects().secretsKnown(project));
    }

    /**
     * Returns the findings nobody acknowledged.
     *
     * @param repository The repository they were found in.
     * @param found What the scan found.
     * @return Those not on the list, in their order.
     * @throws IOException If the list cannot be read.
     */
    public List<SecretScan.Finding> unknown(
            final String repository, final List<SecretScan.Finding> found) throws IOException {
        final Map<String, String> known = read().getOrDefault(repository, Map.of());
        return found.stream()
                .filter(finding -> !finding.digest().equals(known.get(finding.place())))
                .toList();
    }

    /**
     * Acknowledges a finding: it is not said again while its line stays.
     *
     * @param repository The repository it was found in.
     * @param finding The finding.
     * @throws IOException If the list cannot be written.
     */
    public void add(final String repository, final SecretScan.Finding finding) throws IOException {
        final Map<String, Map<String, String>> all = read();
        all.computeIfAbsent(repository, name -> new TreeMap<>())
                .put(finding.place(), finding.digest());
        write(all);
    }

    private Map<String, Map<String, String>> read() throws IOException {
        final Map<String, Map<String, String>> all = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return all;
        }
        if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                && root.get("known") instanceof Map<?, ?> repositories) {
            repositories.forEach(
                    (repository, places) -> {
                        if (places instanceof Map<?, ?> each) {
                            final Map<String, String> kept = new TreeMap<>();
                            each.forEach(
                                    (place, digest) ->
                                            kept.put(
                                                    String.valueOf(place), String.valueOf(digest)));
                            all.put(String.valueOf(repository), kept);
                        }
                    });
        }
        return all;
    }

    private void write(final Map<String, Map<String, String>> all) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("known", new TreeMap<>(all));
        Directories.create(file.getParent());
        final Path staged = file.resolveSibling(file.getFileName() + STAGED);
        Files.deleteIfExists(staged);
        Files.createFile(
                staged,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(staged, Json.write(root), StandardCharsets.UTF_8);
        Files.move(
                staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
