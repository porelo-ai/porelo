package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Which {@code project.yml} in a checkout this machine approved, by its path and its hash.
 *
 * <p><strong>A person's yes, and no judgement of the file.</strong> A repository's file says what a
 * task may reach, and nobody signed it: it counts only while it is byte for byte what a person
 * answered yes to on this machine. An edit, a pull or a checkout of another branch asks again.
 *
 * <p>One file per approval, as a registry two processes write is.
 */
public final class ProjectApprovals {

    /** The most a file may hold to be read at a start: a repository's size is not to be trusted. */
    static final long MOST_BYTES = 1024L * 1024L;

    /**
     * One approval.
     *
     * @param project The project the file named.
     * @param file Its absolute path.
     * @param hash The hash of the bytes approved.
     */
    public record Approval(String project, Path file, String hash) {}

    private final Path directory;

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context This machine.
     */
    public ProjectApprovals(final PoreloContext context) {
        this.context = context;
        this.directory = context.paths().projects().projectApprovals();
    }

    /**
     * Returns the hash an approval names.
     *
     * @param bytes A file's bytes.
     * @return Their SHA-256, in hex.
     */
    public static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
    }

    /**
     * Keeps a person's yes.
     *
     * @param project The project the file names.
     * @param file Its absolute path.
     * @param hash The hash they answered to.
     * @throws IOException When it cannot be kept; the yes then did not happen.
     */
    public void approve(final String project, final Path file, final String hash)
            throws IOException {
        Directories.create(directory);
        final String name = entry(file);
        final Path temporary = Files.createTempFile(directory, name, ".new");
        Files.writeString(
                temporary,
                Json.write(Map.of("project", project, "file", file.toString(), "hash", hash)),
                StandardCharsets.UTF_8);
        Files.move(
                temporary,
                directory.resolve(name),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Returns what was approved at a path.
     *
     * @param file The file's absolute path.
     * @return The approval, or {@code null} for none.
     */
    public @Nullable Approval at(final Path file) {
        return read(directory.resolve(entry(file)));
    }

    /**
     * Returns every approval this machine holds.
     *
     * @return They, in no meaningful order; empty when there are none.
     */
    public List<Approval> all() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<Approval> all = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(entry -> !entry.getFileName().toString().endsWith(".new"))
                    .sorted()
                    .forEach(
                            entry -> {
                                final Approval read = read(entry);
                                if (read != null) {
                                    all.add(read);
                                }
                            });
        } catch (IOException ex) {
            // No record read is no project found: the start goes on as it does without one.
            return List.of();
        }
        return List.copyOf(all);
    }

    /**
     * Tells whether a project's file is still what was approved and still what its name finds.
     *
     * @param approval The approval.
     * @return {@code true} when a start may take it without asking.
     */
    public boolean holds(final Approval approval) {
        final byte[] bytes = bytes(approval.file());
        return bytes != null
                && hash(bytes).equals(approval.hash())
                && approval.file()
                        .equals(ProjectSource.resolve(context, approval.project()).file());
    }

    /**
     * Returns the approved project whose repository has this upstream, its file unchanged.
     *
     * @param upstream A repository's address.
     * @return The project and repository, or {@code null}.
     */
    public WorkOrigin.@Nullable Choice naming(final String upstream) {
        for (final Approval approval : all()) {
            if (!holds(approval)) {
                continue;
            }
            final Repository named = repository(approval.file(), upstream);
            if (named != null) {
                return new WorkOrigin.Choice(
                        approval.project(),
                        named.name(),
                        "'"
                                + approval.project()
                                + "', the project.yml this machine approved, names this"
                                + " repository as '"
                                + named.name()
                                + "'");
            }
        }
        return null;
    }

    /**
     * Returns an approval whose file names this upstream and has changed since.
     *
     * @param upstream A repository's address.
     * @return The approval, or {@code null}.
     */
    public @Nullable Approval changedNaming(final String upstream) {
        for (final Approval approval : all()) {
            final byte[] bytes = bytes(approval.file());
            if (bytes != null
                    && !hash(bytes).equals(approval.hash())
                    && repository(approval.file(), upstream) != null) {
                return approval;
            }
        }
        return null;
    }

    private static @Nullable Repository repository(final Path file, final String upstream) {
        final Project project;
        try {
            project = ProjectReader.read(file);
        } catch (ProjectException ex) {
            return null;
        }
        for (final Repository repository : project.allRepositories()) {
            if (RepositoryAddress.same(repository.upstream(), upstream)) {
                return repository;
            }
        }
        return null;
    }

    /** A link, a directory or a very large file is no file a person approved. */
    static byte @Nullable [] bytes(final Path file) {
        try {
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file)
                    || Files.size(file) > MOST_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String entry(final Path file) {
        return hash(file.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable Approval read(final Path entry) {
        try {
            if (!Files.isRegularFile(entry)
                    || !(Json.parse(Files.readString(entry, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> map)
                    || !(map.get("project") instanceof String project)
                    || !(map.get("file") instanceof String file)
                    || !(map.get("hash") instanceof String hash)) {
                return null;
            }
            return new Approval(project, Path.of(file), hash);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
