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
 * The image a task on one repository is built from, chosen key by key along a fixed order, with the
 * source of each key so a start can name it.
 *
 * <p>The order, first that sets a key wins: the person's local override, the project file's entry
 * for the repository, the repository's own {@code porelo.yml} at its reviewed default branch, and
 * the project's {@code image}. In the project {@code default}, a task started from a person's
 * checkout reads that checkout's {@code .porelo.yml}, then its {@code porelo.yml}, then the default
 * image. {@code base_image} and the snippet are each taken whole from one source; {@code env} per
 * variable.
 *
 * @param baseImage The image to build on.
 * @param baseFrom Where it came from.
 * @param snippet The Containerfile lines, or {@code null} when no source gives any.
 * @param snippetFrom Where they came from, or {@code null} with them.
 * @param env The variables, in the order their sources wrote them.
 * @param envFrom Where each variable came from.
 */
public record ImageChoice(
        String baseImage,
        String baseFrom,
        @Nullable String snippet,
        @Nullable String snippetFrom,
        Map<String, String> env,
        Map<String, String> envFrom) {

    /** The name of a repository's own file, and of a local override. */
    static final String FILE = "porelo.yml";

    /**
     * Constructor.
     *
     * @param baseImage The image to build on.
     * @param baseFrom Where it came from.
     * @param snippet The lines, or {@code null}.
     * @param snippetFrom Where they came from, or {@code null}.
     * @param env The variables.
     * @param envFrom Where each came from.
     */
    public ImageChoice {
        env = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(env));
        envFrom = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(envFrom));
    }

    /**
     * Chooses along sources given in the order they win in.
     *
     * @param order The sources, first wins; the last is the project's own image, which always names
     *     a base image.
     * @return The choice.
     */
    static ImageChoice merge(List<ImageSource> order) {
        String base = null;
        String baseFrom = null;
        String snippet = null;
        String snippetFrom = null;
        final Map<String, String> env = new LinkedHashMap<>();
        final Map<String, String> envFrom = new LinkedHashMap<>();
        for (final ImageSource source : order) {
            if (base == null && source.baseImage() != null) {
                base = source.baseImage();
                baseFrom = source.from();
            }
            if (snippet == null && source.snippet() != null) {
                snippet = source.snippet();
                snippetFrom = source.from();
            }
            source.env()
                    .forEach(
                            (name, value) -> {
                                if (!env.containsKey(name)) {
                                    env.put(name, value);
                                    envFrom.put(name, source.from());
                                }
                            });
        }
        if (base == null || baseFrom == null) {
            throw new ProjectException("No source names a base image");
        }
        return new ImageChoice(base, baseFrom, snippet, snippetFrom, env, envFrom);
    }

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
                        order.add(
                                RepositoryImageReader.read(
                                        text(file), file.toString(), SnippetFiles.beside(file)));
                    }
                }
            }
            order.add(ImageSource.of(project));
            return merge(order);
        }
        final Path override = override(context, project, repository);
        if (Files.isRegularFile(override)) {
            order.add(
                    RepositoryImageReader.read(
                            text(override),
                            override + " (local override)",
                            SnippetFiles.beside(override)));
        }
        if (repository.image() != null) {
            order.add(repository.image());
        }
        final Path mirror = GateSupport.mirror(context, project, repository);
        final var branch = MirrorFile.defaultBranch(context.runner(), mirror);
        if (branch.isPresent()) {
            final String origin = FILE + " at " + branch.get() + " of " + repository.name();
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
                                                            branch.get()))));
        }
        order.add(ImageSource.of(project));
        return merge(order);
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
                        && env.isEmpty();
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
