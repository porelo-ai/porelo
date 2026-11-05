package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.runtime.ImageSettings;
import org.jspecify.annotations.Nullable;

/** Says what a task image records about its build, with where each thing came from. */
final class ImageRecording {

    /** The source of what is Porelo's own: the project default's settings, and every tool. */
    static final String PORELO = ImageSettings.PORELO;

    /** The source of what a project's file says. */
    static final String PROJECT_FILE = "project.yml";

    /** The source of an agent and its version, whichever way a start came to choose it. */
    static final String AGENT_PACKAGE = "agent package";

    private ImageRecording() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * What an image built from a project's own image section records.
     *
     * @param project The project as it is built.
     * @param agent The agent it carries, or {@code null}.
     * @return What to record.
     */
    static ImageSettings of(final Project project, final @Nullable InstalledAgent agent) {
        final String own = ownSource(project);
        return settings(
                project, own, Collections.nCopies(project.imageSnippetLines().size(), own), agent);
    }

    /**
     * What an image built from a repository's choice records.
     *
     * @param project The project as it is built.
     * @param choice The image chosen for the repository.
     * @param agent The agent it carries, or {@code null}.
     * @return What to record.
     */
    static ImageSettings of(
            final Project project, final ImageChoice choice, final @Nullable InstalledAgent agent) {
        return settings(
                project, named(project, choice.baseFrom()), lineSources(project, choice), agent);
    }

    /** The snippet's lines, then one ENV line per variable, as the choice builds them. */
    private static List<String> lineSources(final Project project, final ImageChoice choice) {
        final List<String> sources = new ArrayList<>();
        final String snippet = choice.snippet();
        final String snippetFrom = choice.snippetFrom();
        if (snippet != null && snippetFrom != null) {
            sources.addAll(
                    Collections.nCopies(
                            (int) snippet.lines().count(), named(project, snippetFrom)));
        }
        choice.env()
                .keySet()
                .forEach(
                        name ->
                                sources.add(
                                        named(project, choice.envFrom().getOrDefault(name, ""))));
        return sources;
    }

    private static ImageSettings settings(
            final Project project,
            final String baseFrom,
            final List<String> linesFrom,
            final @Nullable InstalledAgent agent) {
        final String version = agent == null ? null : agent.definition().version();
        return ImageSettings.of(
                project,
                baseFrom,
                linesFrom,
                ownSource(project),
                agent == null ? "" : agent.name(),
                version == null ? "" : version);
    }

    /** A project's own image section is its file, or Porelo's settings for the built-in one. */
    private static String named(final Project project, final String from) {
        return from.equals(ImageSource.of(project).from()) ? ownSource(project) : from;
    }

    private static String ownSource(final Project project) {
        return DefaultProject.is(project.name()) ? PORELO : PROJECT_FILE;
    }
}
