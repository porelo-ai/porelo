package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Which of this machine's projects a person approved from a checkout.
 *
 * <p><strong>What was approved is the copy, not a record of it.</strong> A checkout's files change
 * under whoever reads them, so a start reads this machine's own copy, in the project's place under
 * the configuration directory, and a checkout's files only decide whether to ask again. What is
 * kept here is only that the copy is a person's yes and for which upstream: the same file from the
 * same upstream is the same project, and a stranger's repository takes over nothing.
 *
 * <p>One file per project, as a registry two processes write is.
 */
public final class ProjectApprovals {

    /** The most one file may hold to be read at a start: a repository's size proves nothing. */
    static final int MOST_BYTES = 1024 * 1024;

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
     * Returns the hash a yes names: every file under a directory, each with its relative name, so a
     * change in a file the project names asks again.
     *
     * @param place The directory, holding a project's file and what it names.
     * @return Their SHA-256, in hex.
     * @throws IOException When it cannot be read.
     */
    public static String hash(final Path place) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
        final List<Path> files;
        try (Stream<Path> walk = Files.walk(place)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (final Path file : files) {
            final byte[] name = place.relativize(file).toString().getBytes(StandardCharsets.UTF_8);
            final byte[] bytes = Files.readAllBytes(file);
            digest.update((name.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(name);
            digest.update((":" + bytes.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Keeps that a person said yes to a project for a repository.
     *
     * @param project The project.
     * @param upstream The checkout's origin, or its own path when it has no usable one.
     * @throws IOException When it cannot be kept; the yes then did not happen.
     */
    public void approve(final String project, final String upstream) throws IOException {
        Directories.create(directory);
        final Path temporary = Files.createTempFile(directory, project, ".new");
        Files.writeString(
                temporary,
                Json.write(Map.of("project", project, "upstream", upstream)),
                StandardCharsets.UTF_8);
        Files.move(
                temporary,
                directory.resolve(project),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Tells whether a project was approved for this repository. Same file, same project takes this
     * and the hash; a repository of another upstream takes over nothing.
     *
     * @param project The project.
     * @param upstream The checkout's origin, or its own path when it has no usable one.
     * @return {@code true} when a person approved it for that upstream; a file of that name a
     *     person wrote in its place, or a stranger's, gets {@code false}.
     */
    public boolean approvedFor(final String project, final String upstream) {
        final String kept = upstream(project);
        return kept != null && RepositoryAddress.same(kept, upstream);
    }

    /**
     * Returns the approved project whose repository has this upstream.
     *
     * @param upstream A repository's address.
     * @return The project and repository, or {@code null}.
     */
    public WorkOrigin.@Nullable Choice naming(final String upstream) {
        for (final String project : projects()) {
            final Path copy = copy(project);
            if (!approvedFor(project, upstream)
                    || !copy.equals(ProjectSource.resolve(context, project).file())) {
                continue;
            }
            final Repository named = repository(copy, upstream);
            if (named != null) {
                return new WorkOrigin.Choice(
                        project,
                        named.name(),
                        "'"
                                + project
                                + "', the project this machine approved, names this"
                                + " repository as '"
                                + named.name()
                                + "'");
            }
        }
        return null;
    }

    private Path copy(final String project) {
        return context.paths().projects().defaultProjectFile(project);
    }

    private List<String> projects() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> name.matches(Project.NAME_PATTERN))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            // No record read is no project found: the start goes on as it does without one.
            return List.of();
        }
    }

    private @Nullable String upstream(final String project) {
        final Path entry = directory.resolve(project);
        try {
            return Files.isRegularFile(entry)
                            && Json.parse(Files.readString(entry, StandardCharsets.UTF_8))
                                    instanceof Map<?, ?> map
                            && map.get("upstream") instanceof String upstream
                    ? upstream
                    : null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static @Nullable Repository repository(final Path file, final String upstream) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
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
}
