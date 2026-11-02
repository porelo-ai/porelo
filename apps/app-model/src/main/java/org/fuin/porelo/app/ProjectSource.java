package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where one project's file comes from.
 *
 * <p><strong>An organization's project's file is the one its last start judged.</strong> A start
 * fetches the repository and judges its file with the project's signing keys. Reading anything else
 * afterwards throws that away: a file in a working directory has been checked by nobody.
 *
 * <p><strong>This is the one place that answers it.</strong> A project's file could be in three
 * places - the directory somebody stood in, the one a person wrote, and the judged clone - and
 * three answers to one question is how a machine comes to run something nobody chose.
 */
public final class ProjectSource {

    private ProjectSource() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Why a name could not be resolved, or that it was. */
    public enum Outcome {

        /** The name is the organization's project, and the file is its last start's judged one. */
        ORGANIZATION,

        /**
         * The name is a project a person wrote in its place, or one this machine has run a task
         * for, and the file is where that is. Nothing verified it.
         */
        RECORDED,

        /** This machine has no project of that name. */
        UNKNOWN,

        /**
         * The project {@code default}, which every machine has and Porelo writes the file of
         * itself.
         */
        BUILT_IN
    }

    /**
     * What a name resolved to.
     *
     * @param outcome Where it came from.
     * @param file The project file, or {@code null} when nothing was found.
     * @param commit The revision the file was judged at, or "" when nothing judged it.
     */
    public record Found(Outcome outcome, @Nullable Path file, String commit) {}

    /**
     * Resolves a project by name.
     *
     * <p>The organization's project wins over a local one of the same name, and it is not close:
     * one was judged with the project's signing keys and the other nobody checked.
     *
     * @param context The machine.
     * @param name The project's name.
     * @return What was found.
     */
    public static Found resolve(final PoreloContext context, final String name) {
        if (DefaultProject.is(name)) {
            // Before anything else: the name is reserved, so nothing placed or recorded may
            // answer for it.
            return new Found(Outcome.BUILT_IN, new DefaultProject(context).file(), "");
        }
        try {
            final OrganizationProjects.Placed placed = OrganizationProjects.of(context).find(name);
            if (placed != null) {
                final Path file =
                        context.paths().projects().organizationClone(name).resolve("project.yml");
                if (Files.isRegularFile(file)) {
                    return new Found(Outcome.ORGANIZATION, file, placed.revision());
                }
                return new Found(Outcome.UNKNOWN, null, "");
            }
        } catch (final IOException ex) {
            // A record that cannot be read is not a reason to fall through to an unchecked file:
            // that would answer the question the wrong way round, quietly.
            return new Found(Outcome.UNKNOWN, null, "");
        }
        final Path written = written(context, name);
        if (written != null) {
            return new Found(Outcome.RECORDED, written, "");
        }
        final String recorded =
                new ProjectRegistry(context.paths().projects().projectRegistry()).all().get(name);
        if (recorded != null && Files.isRegularFile(Path.of(recorded))) {
            return new Found(Outcome.RECORDED, Path.of(recorded), "");
        }
        return new Found(Outcome.UNKNOWN, null, "");
    }

    /**
     * Returns the file a person wrote in a project's place, which is the whole of registering it.
     *
     * @param context The machine.
     * @param name The project's name; one that is no name is not read.
     * @return The file, or {@code null} when none is there.
     */
    private static @Nullable Path written(final PoreloContext context, final String name) {
        if (!name.matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)) {
            return null;
        }
        final Path file = context.paths().projects().defaultProjectFile(name);
        return Files.isRegularFile(file) ? file : null;
    }

    /** A file in the place of one name that declares another would run under the wrong project. */
    private static void refuseAnotherName(final Path file, final String name) {
        final String declared;
        try (java.io.Reader reader = Files.newBufferedReader(file)) {
            declared = org.fuin.porelo.core.project.ProjectReader.name(reader, file.toString());
        } catch (final IOException ex) {
            throw new org.fuin.porelo.core.project.ProjectException(
                    "cannot read " + file + ": " + ex.getMessage());
        }
        if (!declared.equals(name)) {
            throw new org.fuin.porelo.core.project.ProjectException(
                    file + " stands in the place of '" + name + "' and names '" + declared + "'");
        }
    }

    /**
     * Returns the projects a person wrote in their place, by name.
     *
     * @param context The machine.
     * @return Their names, sorted.
     */
    public static java.util.List<String> writtenNames(final PoreloContext context) {
        final Path root = context.paths().xdg().config().resolve("projects");
        if (!Files.isDirectory(root)) {
            return java.util.List.of();
        }
        try (java.util.stream.Stream<Path> places = Files.list(root)) {
            return places.map(place -> place.getFileName().toString())
                    .filter(name -> written(context, name) != null)
                    .sorted()
                    .toList();
        } catch (final IOException ex) {
            // A listing of names is not the place to fail over a directory.
            return java.util.List.of();
        }
    }

    /**
     * Resolves a project by name, refusing a name this machine does not have.
     *
     * @param context The machine.
     * @param name The project's name.
     * @return The file to read.
     * @throws org.fuin.porelo.core.project.ProjectException When there is no such project. The
     *     message names what there is.
     */
    public static Path require(final PoreloContext context, final String name) {
        final Found found = resolve(context, name);
        if (found.file() != null && found.file().equals(written(context, name))) {
            refuseAnotherName(found.file(), name);
        }
        if (found.file() == null) {
            final java.util.List<String> known = names(context);
            throw new org.fuin.porelo.core.project.ProjectException(
                    "no project '"
                            + name
                            + "' here. "
                            + "This machine has: "
                            + String.join(", ", known));
        }
        return found.file();
    }

    /**
     * Returns the names this machine can resolve, the organization's placed ones first.
     *
     * <p>What a refusal prints. A person who mistypes a project name should be told which projects
     * this machine has, which is a question only the machine can answer.
     *
     * @param context The machine.
     * @return Names, without duplicates.
     */
    public static java.util.List<String> names(final PoreloContext context) {
        final java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.add(DefaultProject.NAME);
        try {
            all.addAll(OrganizationProjects.of(context).names());
        } catch (final IOException ex) {
            // A listing of names is not the place to fail over a directory.
            all.clear();
        }
        all.addAll(writtenNames(context));
        all.addAll(
                new ProjectRegistry(context.paths().projects().projectRegistry()).all().keySet());
        return java.util.List.copyOf(all);
    }

    /**
     * Asks which project, from those this machine has, where a command would refuse for want of
     * {@code --project}.
     *
     * @param context The machine.
     * @param offer Who is asked.
     * @param command The command a person would type, named where nobody is asked.
     * @return The project chosen, or {@code null} when nobody chose one - the refusal stands.
     */
    public static @org.jspecify.annotations.Nullable String chosen(
            final PoreloContext context, final Offer offer, final String command) {
        if (offer == Offer.NOBODY) {
            return null;
        }
        // 'default' first, as every machine has it; the others by name, so the numbers are the same
        // each time.
        final java.util.List<String> listed = new java.util.ArrayList<>(names(context));
        final boolean hasDefault = listed.remove(DefaultProject.NAME);
        java.util.Collections.sort(listed);
        if (hasDefault) {
            listed.addFirst(DefaultProject.NAME);
        }
        return offer.choose("say which project", listed, command);
    }
}
