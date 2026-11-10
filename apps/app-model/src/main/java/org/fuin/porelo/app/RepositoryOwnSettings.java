package org.fuin.porelo.app;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.RepositoryImageReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.fuin.porelo.core.project.file.RepositorySettings.Asked;
import org.jspecify.annotations.Nullable;

/**
 * What a repository's own files in the default project add to it: {@code porelo.yml} as checked in,
 * then the person's {@code .porelo.yml} beside it, each on top of the project's file.
 *
 * <p><strong>Read from the checkout, as the person sees it.</strong> Approved work lands on a
 * branch of its own, never on the one checked out, so a task cannot change either file; a file that
 * came with a pull still asks this machine before a start runs under it.
 *
 * <p><strong>The image is asked like the rest.</strong> Its lines run as root while the image is
 * built, so its base image, each line it adds with the text of the files they are read from, and
 * each variable are asked for and allowed as every other setting is: a No or a start nobody answers
 * builds nothing from them.
 */
public final class RepositoryOwnSettings {

    /** What the person's own file is called in a source list. */
    public static final String OVERRIDE = "the local override";

    /** The most one file may hold to be read at a start. */
    private static final int MOST_BYTES = 1024 * 1024;

    private RepositoryOwnSettings() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * What the files make.
     *
     * @param project The project a task on the repository runs under, image aside.
     * @param asked What they set otherwise than the project, the image's lines as they reach it.
     * @param hash The hash a yes names: of all of {@code asked}, the state allowed, though a person
     *     is shown only what is new, changed or removed since the last yes.
     * @param sources Each value's path to the file that set it.
     * @param removed What the files took away, each with the file that did.
     */
    public record Found(
            org.fuin.porelo.core.project.Project project,
            List<Asked> asked,
            String hash,
            Map<String, String> sources,
            List<RepositorySettings.Removal> removed) {

        /**
         * Returns what a person is to be shown and allow before a start runs under them.
         *
         * @param context This machine.
         * @param repository The repository's name.
         * @return All of it the first time, then what is new, changed or removed since the last
         *     yes; empty when a start may run.
         */
        public List<Asked> toAllow(final PoreloContext context, final String repository) {
            return new RepositoryAllowance(context).notAllowed(repository, asked);
        }

        /**
         * Returns settings as plain values, for a wire.
         *
         * @param settings The settings.
         * @return One map per setting, with key, value, was and removed.
         */
        public static List<Map<String, Object>> asMaps(final List<Asked> settings) {
            return settings.stream()
                    .map(
                            each ->
                                    Map.<String, Object>of(
                                            "key",
                                            each.key(),
                                            "value",
                                            each.value(),
                                            "was",
                                            each.was(),
                                            "removed",
                                            each.removed()))
                    .toList();
        }
    }

    /**
     * Reads what a repository's own files add to the default project.
     *
     * @param context This machine.
     * @param projectFile The default project's file.
     * @param repository The repository the task works on.
     * @return What they make, or {@code null} when the repository has no checkout or neither file.
     * @throws ProjectException If a file cannot be read, says what only the project may, or makes
     *     no valid project.
     */
    public static @Nullable Found of(
            final PoreloContext context, final Path projectFile, final Repository repository) {
        final String checkout =
                new DefaultProject(context)
                        .entries().stream()
                                .filter(entry -> entry.name().equals(repository.name()))
                                .map(DefaultProject.Entry::checkout)
                                .findFirst()
                                .orElse("");
        if (checkout.isEmpty()) {
            return null;
        }
        final List<RepositorySettings.Layer> layers = new ArrayList<>();
        final Path top = Path.of(checkout);
        add(layers, top.resolve(ImageChoice.FILE), ImageChoice.FILE + " of " + repository.name());
        add(layers, top.resolve("." + ImageChoice.FILE), OVERRIDE);
        if (layers.isEmpty()) {
            return null;
        }
        final ProjectReader.WithRepository read =
                ProjectReader.read(
                        new StringReader(text(projectFile)),
                        projectFile.toString(),
                        SnippetFiles.beside(projectFile),
                        layers);
        final List<Asked> asked = new ArrayList<>(read.asked());
        final Map<String, String> sources = new LinkedHashMap<>(read.sources());
        asked.addAll(
                image(
                        ProjectReader.read(projectFile),
                        read.project().imageEnv().keySet(),
                        checkoutImages(top),
                        sources));
        return new Found(
                read.project(),
                List.copyOf(asked),
                RepositoryAllowance.hash(asked),
                java.util.Collections.unmodifiableMap(sources),
                read.removed());
    }

    /**
     * Reads the image each of a checkout's own files gives, the one that wins first: the person's
     * {@code .porelo.yml}, then the repository's {@code porelo.yml}. Read as their settings are, so
     * what is built is what was asked.
     *
     * @param checkout The checkout.
     * @return One source per file there.
     * @throws ProjectException If a file cannot be read or its image is wrong.
     */
    static List<ImageSource> checkoutImages(final Path checkout) {
        final List<ImageSource> order = new ArrayList<>();
        for (final String name : List.of("." + ImageChoice.FILE, ImageChoice.FILE)) {
            final Path file = checkout.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                // Settings beside the image are merged and allowed with the project's.
                order.add(
                        RepositoryImageReader.readBesideSettings(
                                text(file), file.toString(), SnippetFiles.beside(file)));
            }
        }
        return List.copyOf(order);
    }

    /**
     * What a repository's files do to the image, as the build takes it: a base image other than the
     * project's, each line they add, numbered among theirs, and each variable they set otherwise or
     * take out.
     *
     * @param declared The project as its file declares it.
     * @param kept The variables the files' merge keeps, or {@code null} when none takes one out.
     * @param added The files' images, the one that wins first.
     * @param sources Where each key asked for is put with the file that gave it.
     * @return What they ask, in the order of the build.
     */
    static List<Asked> image(
            final Project declared,
            final java.util.@Nullable Set<String> kept,
            final List<ImageSource> added,
            final Map<String, String> sources) {
        if (added.isEmpty()) {
            return List.of();
        }
        final ImageSource own = ImageSource.of(declared);
        final List<ImageSource> order = new ArrayList<>(added);
        order.add(own);
        final ImageChoice choice = ImageChoice.merge(order);
        final List<Asked> asked = new ArrayList<>();
        if (!Objects.equals(choice.baseImage(), declared.baseImage())) {
            asked.add(
                    new Asked(
                            "image.from",
                            choice.baseImage(),
                            Objects.requireNonNullElse(declared.baseImage(), ""),
                            false));
            sources.put("image.from", choice.baseFrom());
        }
        if (choice.snippet() != null) {
            final List<String> lines = choice.snippet().lines().toList();
            int number = 0;
            for (int at = 0; at < lines.size(); at++) {
                final String from = choice.lineFrom().get(at);
                if (!from.equals(own.from()) && !lines.get(at).isBlank()) {
                    number++;
                    final String key = "image.run[" + number + "]";
                    asked.add(new Asked(key, lines.get(at), "", false));
                    sources.put(key, from);
                }
            }
        }
        // A null in a file takes a variable out: the merged project keeps only what is set.
        final java.util.Set<String> set = kept == null ? choice.env().keySet() : kept;
        final Map<String, String> was = declared.imageEnv();
        choice.env()
                .forEach(
                        (name, value) -> {
                            final String key = "image.env." + name;
                            if (set.contains(name) && !Objects.equals(was.get(name), value)) {
                                asked.add(new Asked(key, value, was.getOrDefault(name, ""), false));
                                sources.put(key, choice.envFrom().get(name));
                            }
                        });
        was.forEach(
                (name, value) -> {
                    if (!set.contains(name)) {
                        asked.add(new Asked("image.env." + name, "", value, true));
                    }
                });
        return asked;
    }

    /**
     * Reads what a repository's {@code porelo.yml} adds to an organization's project, as its
     * mirror's default branch holds it. Nothing asks this machine: every change to it is signed by
     * a project admin or a developer, or it is refused.
     *
     * @param context This machine.
     * @param projectFile The project's file.
     * @param project The project's name.
     * @param repository The repository the task works on.
     * @return What it makes, or {@code null} when the project was not taken from an organization,
     *     the mirror holds no default branch yet, or the file is not there.
     * @throws RepositorySettingsSignature.Unsigned When a change to it may not be taken.
     * @throws ProjectException If it says what only the project may, or makes no valid project.
     */
    public static @Nullable Found ofOrganization(
            final PoreloContext context,
            final Path projectFile,
            final String project,
            final Repository repository) {
        if (!fromOrganization(context, project)) {
            // A local project's repository gives its image alone, as before: nobody signs it.
            return null;
        }
        final Path mirror = GateSupport.mirror(context, project, repository.name());
        if (!(MirrorFile.defaultBranch(context.runner(), mirror)
                instanceof MirrorFile.Found found)) {
            // No mirror yet, or none a start would take: the fetch makes it, and the next start
            // reads it.
            return null;
        }
        RepositorySettingsSignature.check(context, project, repository.name(), mirror, found.ref());
        final String text =
                MirrorFile.read(context.runner(), mirror, ImageChoice.FILE).orElse(null);
        if (text == null) {
            return null;
        }
        final ProjectReader.WithRepository read =
                ProjectReader.read(
                        new StringReader(text(projectFile)),
                        projectFile.toString(),
                        SnippetFiles.beside(projectFile),
                        List.of(
                                new RepositorySettings.Layer(
                                        ImageChoice.FILE
                                                + " at "
                                                + found.ref()
                                                + " of "
                                                + repository.name(),
                                        text)));
        return new Found(
                read.project(),
                read.asked(),
                RepositoryAllowance.hash(read.asked()),
                read.sources(),
                read.removed());
    }

    /**
     * Tells whether this machine took a project from an organization, whose repositories' files may
     * add settings beside their image.
     */
    static boolean fromOrganization(final PoreloContext context, final String project) {
        try {
            return OrganizationProjects.of(context).find(project) != null;
        } catch (IOException ex) {
            throw new ProjectException("Cannot read what this machine took of " + project, ex);
        }
    }

    /**
     * Allows what a repository's own files ask for now, when it is what a person was shown.
     *
     * @param context This machine.
     * @param repository The repository's name in the default project.
     * @param hash The hash of the settings shown.
     * @return Whether they were allowed; {@code false} when the files ask for something else now,
     *     for nothing, or the repository is not in the default project.
     * @throws ProjectException If a file cannot be read or makes no valid project.
     */
    public static boolean allowIfUnchanged(
            final PoreloContext context, final String repository, final String hash) {
        final Path file = new DefaultProject(context).file();
        final Repository chosen = ProjectReader.read(file).repository(repository);
        if (chosen == null) {
            return false;
        }
        final Found found = of(context, file, chosen);
        if (found == null || found.asked().isEmpty() || !found.hash().equals(hash)) {
            return false;
        }
        new RepositoryAllowance(context).allow(repository, found.asked());
        return true;
    }

    private static void add(
            final List<RepositorySettings.Layer> layers, final Path file, final String source) {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            layers.add(new RepositorySettings.Layer(source, text(file)));
        }
    }

    private static String text(final Path file) {
        try {
            if (Files.size(file) > MOST_BYTES) {
                throw new ProjectException(file + " is larger than " + MOST_BYTES + " bytes");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }
}
