package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * A checkout that carries its own project file, read the way a first start reads it, so a start and
 * every task command agree on which project and repository such a checkout is.
 */
final class CheckoutProject {

    /** The file a checkout carries its project in. */
    static final String FILE_NAME = "project.yml";

    private CheckoutProject() {}

    /**
     * Returns what a checkout is known by: a usable origin, else its own path.
     *
     * @param origin The checkout's origin, without a credential, or "".
     * @param top The checkout's top directory.
     * @return The upstream.
     */
    static String upstream(final String origin, final Path top) {
        return !origin.isEmpty() && !origin.contains("?") && !origin.contains("#")
                ? origin
                : top.toString();
    }

    /**
     * Returns which of a project's repositories a checkout is.
     *
     * @param project The project its file names.
     * @param origin The checkout's origin, or "".
     * @param top The checkout's top directory.
     * @return The repository's name, or "" when none is this checkout.
     */
    static String repositoryOf(final Project project, final String origin, final Path top) {
        for (final Repository each : project.workRepositories()) {
            if ((!origin.isEmpty() && RepositoryAddress.sameUpstream(each.upstream(), origin))
                    || RepositoryAddress.sameUpstream(each.upstream(), top.toString())) {
                return each.name();
            }
        }
        return "";
    }

    /**
     * Returns the project this checkout's own file names, when this machine approved that project
     * for this checkout's upstream. Only reads: nothing is added to {@code default}.
     *
     * @param context This machine.
     * @param directory Where the person stands, inside the checkout.
     * @param top The checkout's top directory.
     * @param origin The checkout's origin, or "".
     * @return The project and repository; {@code null} when there is no such file, it cannot be
     *     read, or this machine never approved it here.
     */
    static WorkOrigin.@Nullable Choice approved(
            final PoreloContext context,
            final Path directory,
            final Path top,
            final String origin) {
        for (final Path place : List.of(realOrAsGiven(directory), top)) {
            final Path file = place.resolve(FILE_NAME);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return approvedIn(context, file, top, origin);
            }
        }
        return null;
    }

    private static WorkOrigin.@Nullable Choice approvedIn(
            final PoreloContext context, final Path file, final Path top, final String origin) {
        // A link or a directory in its place, or one larger than a start reads, names nothing: the
        // start refuses it too.
        final String name;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > ProjectApprovals.MOST_BYTES) {
                return null;
            }
            name = ProjectReader.read(file).name();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        // The checkout's file gives only the name. What the project holds is read from this
        // machine's copy, which a person approved, never from a file anyone may have edited since.
        final Path copy = context.paths().projects().defaultProjectFile(name);
        if (!copy.equals(ProjectSource.resolve(context, name).file())
                || !new ProjectApprovals(context).approvedFor(name, upstream(origin, top))) {
            return null;
        }
        final Project approved;
        try {
            approved = ProjectReader.read(copy);
        } catch (RuntimeException ex) {
            return null;
        }
        return new WorkOrigin.Choice(name, repositoryOf(approved, origin, top), "");
    }

    private static Path realOrAsGiven(final Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException ex) {
            return directory.toAbsolutePath().normalize();
        }
    }
}
