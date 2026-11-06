package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.RepositoryImageReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.jspecify.annotations.Nullable;

/**
 * The image a task on one repository is built from, with each part's source named so a start can
 * say which.
 *
 * <p>The sources, the one that wins first: the person's local override, the repository's own {@code
 * porelo.yml} at its reviewed default branch, and the project's {@code image}. In the project
 * {@code default}, a task started from a person's checkout reads that checkout's {@code
 * .porelo.yml}, then its {@code porelo.yml}, then the default image. Each adds to the project's
 * image key by key, as the operator decided: a value it sets wins, its lines follow the project's,
 * and its variables join the project's.
 *
 * @param baseImage The image to build on.
 * @param baseFrom Where it came from.
 * @param snippet The Containerfile lines, or {@code null} when no source gives any.
 * @param snippetFrom Where they came from, or {@code null} with them; several sources joined.
 * @param lineFrom Where each of the lines came from, one entry per line.
 * @param env The variables, in the order their sources wrote them.
 * @param envFrom Where each variable came from.
 * @param removed What a repository's own files in {@code default} took out of the image, each with
 *     the file that did.
 */
public record ImageChoice(
        String baseImage,
        String baseFrom,
        @Nullable String snippet,
        @Nullable String snippetFrom,
        List<String> lineFrom,
        Map<String, String> env,
        Map<String, String> envFrom,
        List<org.fuin.porelo.core.project.file.RepositorySettings.Removal> removed) {

    /** The name of a repository's own file, and of a local override. */
    static final String FILE = "porelo.yml";

    /**
     * Constructor.
     *
     * @param baseImage The image to build on.
     * @param baseFrom Where it came from.
     * @param snippet The lines, or {@code null}.
     * @param snippetFrom Where they came from, or {@code null}.
     * @param lineFrom Where each line came from.
     * @param env The variables.
     * @param envFrom Where each came from.
     * @param removed What a repository's own files took out.
     */
    public ImageChoice {
        lineFrom = List.copyOf(lineFrom);
        env = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(env));
        envFrom = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(envFrom));
        removed = List.copyOf(removed);
    }

    /**
     * Constructor, for an image nothing was taken out of.
     *
     * @param baseImage The image to build on.
     * @param baseFrom Where it came from.
     * @param snippet The lines, or {@code null}.
     * @param snippetFrom Where they came from, or {@code null}.
     * @param lineFrom Where each line came from.
     * @param env The variables.
     * @param envFrom Where each came from.
     */
    public ImageChoice(
            final String baseImage,
            final String baseFrom,
            final @Nullable String snippet,
            final @Nullable String snippetFrom,
            final List<String> lineFrom,
            final Map<String, String> env,
            final Map<String, String> envFrom) {
        this(baseImage, baseFrom, snippet, snippetFrom, lineFrom, env, envFrom, List.of());
    }

    /**
     * Adds the sources to the project's image key by key: a value a source sets wins over the ones
     * after it, lines come in the project's order first, and variables join.
     *
     * @param order The sources, the one that wins first; the last is the project's own image.
     * @return The choice.
     */
    /**
     * Keeps only the variables the files' merge keeps: a null in a repository's file takes a
     * variable out, as everywhere else in it, where reading that file alone gives it an empty
     * value.
     */
    private static ImageChoice withoutUnset(
            final ImageChoice merged,
            final PoreloContext context,
            final Project project,
            final Repository repository) {
        final RepositoryOwnSettings.Found own =
                RepositoryOwnSettings.of(context, new DefaultProject(context).file(), repository);
        final Map<String, String> kept =
                own == null ? project.imageEnv() : own.project().imageEnv();
        final Map<String, String> env = new LinkedHashMap<>(merged.env());
        final Map<String, String> envFrom = new LinkedHashMap<>(merged.envFrom());
        env.keySet().retainAll(kept.keySet());
        envFrom.keySet().retainAll(kept.keySet());
        return new ImageChoice(
                merged.baseImage(),
                merged.baseFrom(),
                merged.snippet(),
                merged.snippetFrom(),
                merged.lineFrom(),
                env,
                envFrom,
                own == null
                        ? List.of()
                        : own.removed().stream()
                                .filter(each -> each.key().startsWith("image."))
                                .toList());
    }

    static ImageChoice merge(List<ImageSource> order) {
        if (order.isEmpty()) {
            throw new ProjectException("No source names a base image");
        }
        // The project's first, each later source adding to it: a value it sets wins, its lines
        // follow the earlier ones, its variables join theirs.
        final List<ImageSource> earliestFirst = new ArrayList<>(order).reversed();
        String base = null;
        String baseFrom = null;
        final StringBuilder lines = new StringBuilder();
        final List<String> linesFrom = new ArrayList<>();
        final List<String> lineFrom = new ArrayList<>();
        final Map<String, String> env = new LinkedHashMap<>();
        final Map<String, String> envFrom = new LinkedHashMap<>();
        for (final ImageSource source : earliestFirst) {
            if (source.baseImage() != null) {
                base = source.baseImage();
                baseFrom = source.from();
            }
            if (source.snippet() != null && !source.snippet().isBlank()) {
                if (!lines.isEmpty() && lines.charAt(lines.length() - 1) != '\n') {
                    lines.append('\n');
                }
                lines.append(source.snippet());
                linesFrom.add(source.from());
                source.snippet().lines().forEach(line -> lineFrom.add(source.from()));
            }
            source.env()
                    .forEach(
                            (name, value) -> {
                                env.put(name, value);
                                envFrom.put(name, source.from());
                            });
        }
        if (base == null || baseFrom == null) {
            throw new ProjectException(order.getFirst().from() + " names no base image");
        }
        return new ImageChoice(
                base,
                baseFrom,
                lines.isEmpty() ? null : lines.toString(),
                linesFrom.isEmpty() ? null : String.join(", then ", linesFrom),
                lineFrom,
                env,
                envFrom);
    }

    /**
     * An image preview with any refusal that a mirror refresh may repair.
     *
     * @param choice The image chosen without a refused mirror source.
     * @param mirrorRefusal The mirror's refusal, or {@code null}.
     */
    public record Preview(ImageChoice choice, @Nullable ProjectException mirrorRefusal) {}

    /**
     * Chooses the image for a task on a repository of a project.
     *
     * @param context Where the override, the mirror and git are.
     * @param project The project.
     * @param repository The repository the task works on.
     * @return The choice.
     * @throws ProjectException If a source is unreadable or names more than its image.
     */
    public static ImageChoice of(PoreloContext context, Project project, Repository repository) {
        final Preview preview = preview(context, project, repository);
        if (preview.mirrorRefusal() != null) {
            throw preview.mirrorRefusal();
        }
        return preview.choice();
    }

    /**
     * Previews an image before the gate refreshes its mirror.
     *
     * @param context Where the sources and git are.
     * @param project The project.
     * @param repository The repository.
     * @return The choice and the mirror's refusal, if any.
     * @throws ProjectException If a local source is refused.
     */
    public static Preview preview(PoreloContext context, Project project, Repository repository) {
        final List<ImageSource> order = new ArrayList<>();
        if (DefaultProject.is(project.name())) {
            // A person's own checkout, read as they see it: approved work lands on porelo/<task>,
            // never on the branch they have checked out, so a task cannot change either file.
            final String checkout =
                    new DefaultProject(context)
                            .entries().stream()
                                    .filter(entry -> entry.name().equals(repository.name()))
                                    .map(DefaultProject.Entry::checkout)
                                    .findFirst()
                                    .orElse("");
            if (!checkout.isEmpty()) {
                for (final String name : List.of("." + FILE, FILE)) {
                    final Path file = Path.of(checkout).resolve(name);
                    if (Files.isRegularFile(file)) {
                        // Settings beside the image are read, merged and allowed elsewhere.
                        order.add(
                                RepositoryImageReader.readBesideSettings(
                                        text(file), file.toString(), SnippetFiles.beside(file)));
                    }
                }
            }
            order.add(ImageSource.of(project));
            return new Preview(withoutUnset(merge(order), context, project, repository), null);
        }
        final Path override = override(context, project, repository);
        if (Files.isRegularFile(override)) {
            order.add(
                    RepositoryImageReader.read(
                            text(override),
                            override + " (local override)",
                            SnippetFiles.beside(override)));
        }
        ProjectException mirrorRefusal = null;
        try {
            final Path mirror = GateSupport.mirror(context, project, repository);
            final var branch = MirrorFile.defaultBranch(context.runner(), mirror);
            final String refusal = MirrorFile.refusal(branch, repository);
            if (refusal != null) {
                throw new ProjectException(refusal);
            }
            if (branch instanceof MirrorFile.Found found) {
                // Judged before it is read: an organization's file counts only when signed.
                RepositorySettingsSignature.check(
                        context, project.name(), repository.name(), mirror, found.ref());
                final String origin = FILE + " at " + found.ref() + " of " + repository.name();
                MirrorFile.read(context.runner(), mirror, FILE)
                        .ifPresent(
                                text ->
                                        order.add(
                                                RepositoryImageReader.read(
                                                        text,
                                                        origin,
                                                        MirrorFile.snippets(
                                                                context.runner(),
                                                                mirror,
                                                                found.ref()))));
            }
        } catch (RepositorySettingsSignature.Unsigned ex) {
            // No fetch repairs a change nobody who may sign it signed: refused as it stands.
            throw ex;
        } catch (ProjectException ex) {
            // A fetch can repair only the mirror's file, never a local source.
            mirrorRefusal = ex;
        }
        order.add(ImageSource.of(project));
        return new Preview(merge(order), mirrorRefusal);
    }

    /**
     * Returns the project as a task on the repository builds it: with this image, and named apart
     * whenever a source other than the project's own image gave a key.
     *
     * @param project The project.
     * @param repository The repository.
     * @return The copy to build and start from.
     */
    public Project applyTo(Project project, Repository repository) {
        final String own = ImageSource.of(project).from();
        final boolean projects =
                baseFrom.equals(own)
                        && (snippetFrom == null || snippetFrom.equals(own))
                        && envFrom.values().stream().allMatch(own::equals);
        return project.forImage(baseImage, withEnv(), projects ? null : repository.name());
    }

    /**
     * The snippet with each variable as an ENV line after it, so a variable can read a tool's path.
     */
    private @Nullable String withEnv() {
        if (env.isEmpty()) {
            return snippet;
        }
        final StringBuilder lines = new StringBuilder(snippet == null ? "" : snippet);
        if (!lines.isEmpty() && lines.charAt(lines.length() - 1) != '\n') {
            lines.append('\n');
        }
        env.forEach(
                (name, value) ->
                        lines.append("ENV ")
                                .append(name)
                                .append("=\"")
                                .append(
                                        value.replace("\\", "\\\\")
                                                .replace("\"", "\\\"")
                                                .replace("$", "\\$"))
                                .append("\"\n"));
        return lines.toString();
    }

    /**
     * Returns where a person's local override for a repository lies, whether or not it exists.
     *
     * @param context Where the configuration is.
     * @param project The project.
     * @param repository The repository.
     * @return The path.
     */
    static Path override(PoreloContext context, Project project, Repository repository) {
        return context.paths()
                .xdg()
                .config()
                .resolve("projects")
                .resolve(project.name())
                .resolve(repository.name())
                .resolve("." + FILE);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }
}
