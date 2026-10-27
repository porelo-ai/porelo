package org.fuin.porelo.app;

import java.io.PrintWriter;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.runtime.ImageLayers;
import org.fuin.porelo.runtime.Podman;
import org.jspecify.annotations.Nullable;

/**
 * Builds a project's task image without starting anything.
 *
 * <p><strong>Why this is a command of its own.</strong> A task start builds the image on its way to
 * running work, so the first task in a project waits minutes for something that has nothing to do
 * with the work. There was no way to say "get this ready, I am not starting anything yet".
 *
 * <p><strong>The depths are the feature, not the rebuild.</strong> Somebody deciding to rebuild is
 * deciding what it will cost them, and "rebuild" with no answer to "how much of it" is a button
 * people press once, wait ten minutes, and then avoid.
 */
public final class Preparation {

    /** What happened. */
    public enum Outcome {

        /** The image is built and ready to start a task from. */
        PREPARED,

        /** What would be done, having done nothing. */
        PREVIEWED,

        /** No project of that name is registered here. */
        NO_SUCH_PROJECT,

        /** The build failed. */
        FAILED
    }

    /**
     * What a preparation did.
     *
     * @param outcome What happened.
     * @param image The image name, or "" when nothing was built.
     * @param rebuild How much was discarded.
     * @param detail Why it failed, or "".
     */
    public record Result(Outcome outcome, String image, Podman.Rebuild rebuild, String detail) {}

    private Preparation() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Prepares a project's own image, as before a repository could name its own.
     *
     * @param context The machine.
     * @param projectFile The project file to build from.
     * @param agentName Whose tooling to install, or {@code null} for the only agent installed.
     * @param rebuild How much of any previous build to discard.
     * @param dryRun Says what it would do and does nothing.
     * @param out Where progress goes.
     * @return What happened.
     */
    public static Result prepare(
            PoreloContext context,
            java.nio.file.Path projectFile,
            @Nullable String agentName,
            Podman.Rebuild rebuild,
            boolean dryRun,
            PrintWriter out) {
        return prepare(context, projectFile, null, agentName, rebuild, dryRun, out);
    }

    /**
     * Prepares the image a task on one of a project's repositories is built from.
     *
     * @param repositoryName The repository, whose image is chosen as a start chooses it, or {@code
     *     null} for the project's own image.
     * @param context The machine.
     * @param projectFile The project file to build from.
     * @param agentName Whose tooling to install, or {@code null} for the repository's, else the
     *     only one installed.
     * @param rebuild How much of any previous build to discard.
     * @param dryRun Says what it would do and does nothing.
     * @param out Where progress goes, line by line, because a build takes minutes and silence is
     *     indistinguishable from a hang.
     * @return What happened.
     */
    public static Result prepare(
            PoreloContext context,
            java.nio.file.Path projectFile,
            @Nullable String repositoryName,
            @Nullable String agentName,
            Podman.Rebuild rebuild,
            boolean dryRun,
            PrintWriter out) {

        final Project declared;
        try {
            declared = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        } catch (RuntimeException ex) {
            return new Result(Outcome.NO_SUCH_PROJECT, "", rebuild, CliErrors.reason(ex));
        }
        final Project project;
        final @Nullable String snippetFrom;
        // The agent a start on this repository would take, so the image carries what it runs.
        @Nullable String wantedAgent = agentName;
        if (repositoryName == null) {
            project = declared;
            snippetFrom = org.fuin.porelo.core.project.ImageSource.of(declared).from();
        } else {
            final var repository =
                    declared.allRepositories().stream()
                            .filter(each -> each.name().equals(repositoryName))
                            .findFirst();
            if (repository.isEmpty()) {
                return new Result(
                        Outcome.FAILED,
                        "",
                        rebuild,
                        "'"
                                + declared.name()
                                + "' has no repository '"
                                + repositoryName
                                + "'; it has "
                                + declared.repositoryNames());
            }
            final ImageChoice choice;
            try {
                choice = ImageChoice.of(context, declared, repository.get());
            } catch (RuntimeException ex) {
                return new Result(Outcome.FAILED, "", rebuild, CliErrors.reason(ex));
            }
            project = choice.applyTo(declared, repository.get());
            wantedAgent = AgentChoice.wanted(agentName, repository.get()).name();
            snippetFrom = choice.snippetFrom();
            out.println("base image " + project.baseImage() + "  (from " + choice.baseFrom() + ")");
            if (choice.snippetFrom() != null) {
                out.println("lines      from " + choice.snippetFrom());
            }
            choice.envFrom()
                    .forEach((name, from) -> out.println("env        " + name + " from " + from));
        }

        try (var agents = context.agents()) {
            final var selected =
                    wantedAgent != null
                            ? agents.find(wantedAgent)
                            : agents.names().size() == 1
                                    ? agents.find(agents.names().getFirst())
                                    : java.util.Optional
                                            .<org.fuin.porelo.agent.api.InstalledAgent>empty();

            ImageLayers layers = ImageLayers.none();
            if (selected.isPresent()) {
                final var agent = selected.get();
                out.println("agent     " + agent.name());
                layers =
                        layers.and(
                                agent.definition().installAsRoot(),
                                org.fuin.porelo.agent.api.InstallScript.render(
                                        agent.definition().artifacts()));
                layers =
                        layers.and(
                                AgentStaging.stage(
                                        agent, project, context.paths(), context.runner(), out),
                                java.util.List.of());
                layers = layers.and(java.util.List.of(), agent.definition().installAsAgent());
            } else {
                // An image without an agent is a real thing to want - it is what a person working
                // in the container by hand needs - so this is reported rather than refused.
                out.println("agent     none, so the image carries no agent tooling");
            }
            if (!project.imageSnippetLines().isEmpty()) {
                out.println(
                        "snippet   "
                                + project.imageSnippetLines().size()
                                + " lines from "
                                + (snippetFrom == null ? "the image environment" : snippetFrom));
                layers = layers.and(project.imageSnippetLines(), java.util.List.of());
            }

            out.println("rebuild   " + describe(rebuild));
            if (dryRun) {
                out.println("previewed nothing was built");
                out.flush();
                return new Result(Outcome.PREVIEWED, project.imageName(), rebuild, "");
            }

            out.println("building  " + project.imageName() + " from " + project.baseImage());
            out.flush();
            final String image =
                    context.podman()
                            .buildImage(
                                    project,
                                    context.paths().tasks().buildContext(project.imageKey()),
                                    layers,
                                    rebuild);
            out.println("image     " + image);
            out.flush();
            return new Result(Outcome.PREPARED, image, rebuild, "");

        } catch (RuntimeException ex) {
            out.flush();
            return new Result(Outcome.FAILED, "", rebuild, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Says in words what a depth keeps, so a person can see the cost before paying it.
     *
     * @param rebuild The depth.
     * @return One line.
     */
    static String describe(Podman.Rebuild rebuild) {
        return switch (rebuild) {
            case CACHED -> "reuse whatever is still valid";
            case AGENT -> "replace the agent's tooling, keep the base image and its packages";
            case EVERYTHING -> "discard everything, including the base image's packages";
        };
    }
}
