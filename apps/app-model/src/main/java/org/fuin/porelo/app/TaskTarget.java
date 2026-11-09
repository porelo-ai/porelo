package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.gate.LocalRepository;
import org.fuin.porelo.runtime.ContainerSummary;
import org.jspecify.annotations.Nullable;

/**
 * Which task a command means: the one it names - by its container's name or by its own - or, named
 * by none, the one started from the checkout the person stands in.
 *
 * <p>{@code porelo approve} found its task from the checkout, and the task commands wanted the
 * container's name typed out; one way now serves both.
 */
public final class TaskTarget {

    /** Exit: no task can be meant. */
    public static final int NONE = 66;

    /** Exit: several could be, and nobody can be asked. */
    public static final int SEVERAL = 64;

    /** Asks the person which task they mean. */
    @FunctionalInterface
    public interface Asker {

        /**
         * Asks.
         *
         * @param question The question, without a line break.
         * @return The answer, or {@code null} when nobody answers.
         */
        @Nullable String ask(String question);
    }

    /**
     * What was found.
     *
     * @param container The task's container, or {@code null} when none is meant.
     * @param refusal Why none is, or "".
     * @param code The exit to end with when none is.
     */
    public record Found(@Nullable String container, String refusal, int code) {}

    private TaskTarget() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a task's container from either of its names: the container's, or the task's own when
     * one task has it.
     *
     * @param context The machine.
     * @param given What the person typed.
     * @return The container's name, or {@code given} as it is when no task has it.
     */
    public static String spelled(final PoreloContext context, final String given) {
        return spelled(context, given, Path.of("."));
    }

    /**
     * Returns a task's container from either of its names, the task's own taken in the project of
     * the checkout the person stands in where tasks of several projects have it.
     *
     * @param context The machine.
     * @param given What the person typed.
     * @param directory Where the person stands.
     * @return The container's name, or {@code given} as it is when no task has it or several do and
     *     the checkout settles none of them.
     */
    public static String spelled(
            final PoreloContext context, final String given, final Path directory) {
        final List<ContainerSummary> tasks = context.podman().poreloTasks();
        if (tasks.stream().anyMatch(task -> task.name().equals(given))) {
            return given;
        }
        final List<String> own =
                tasks.stream()
                        .filter(
                                task ->
                                        task.project() != null
                                                && task.name()
                                                        .equals(
                                                                "porelo-"
                                                                        + task.project()
                                                                        + "-"
                                                                        + given))
                        .map(ContainerSummary::name)
                        .toList();
        if (own.size() == 1) {
            return own.getFirst();
        }
        if (own.isEmpty()) {
            return given;
        }
        // Two projects' tasks of one name: the one this checkout's project has, as a start here
        // would take it.
        final Checkout standing = checkout(context, directory);
        final String here = standing == null ? null : "porelo-" + standing.project() + "-" + given;
        return here != null && own.contains(here) ? here : given;
    }

    /** The project and repository a checkout belongs to, as its start takes them. */
    private record Checkout(String project, @Nullable String repository) {}

    /**
     * Returns the tasks started from the checkout a directory is in.
     *
     * @param context The machine.
     * @param directory Where the person stands.
     * @return Their containers, possibly none; {@code null} when the directory is in no checkout a
     *     repository is known by.
     */
    public static @Nullable List<String> fromCheckout(
            final PoreloContext context, final Path directory) {
        final Checkout found = checkout(context, directory);
        if (found == null) {
            return null;
        }
        final String inProject = found.project();
        final String ofRepository = found.repository();
        return context.podman().poreloTasks().stream()
                .filter(
                        task ->
                                inProject.equals(task.project())
                                        && ofRepository != null
                                        && ofRepository.equals(task.repository()))
                .map(ContainerSummary::name)
                .toList();
    }

    private static @Nullable Checkout checkout(final PoreloContext context, final Path directory) {
        final Path top = LocalRepository.topLevel(context.runner(), directory);
        if (top == null) {
            return null;
        }
        // In the start's order: a project placed for the origin, then the checkout's own approved
        // project file, then 'default'. A checkout once recorded in 'default' and later given an
        // approved file of its own is that project's, as its start takes it.
        final String origin = originOf(context, top);
        WorkOrigin.Choice found =
                origin.isEmpty() ? null : WorkOrigin.placedNaming(context, origin);
        if (found == null) {
            found = CheckoutProject.approved(context, directory, top, origin);
        }
        String project = found == null ? null : found.project();
        String repository = found == null ? null : found.repository();
        if (project == null) {
            final DefaultProject entries = new DefaultProject(context);
            DefaultProject.Entry own = entries.withUpstream(top.toString());
            if (own == null && !origin.isEmpty()) {
                own = entries.withUpstream(origin);
            }
            if (own == null) {
                return null;
            }
            project = DefaultProject.NAME;
            repository = own.name();
        }
        return new Checkout(project, repository);
    }

    private static String originOf(final PoreloContext context, final Path top) {
        final CommandResult remote =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        top.toString(),
                                        "remote",
                                        "get-url",
                                        "origin"));
        return RepositoryAddress.withoutCredential(
                remote.successful() ? remote.standardOutput().strip() : "");
    }

    /**
     * Returns the task a command means.
     *
     * @param context The machine.
     * @param given What the person typed, or {@code null}.
     * @param directory Where the person stands.
     * @param asker How the person is asked when several could be meant, or {@code null} where
     *     nobody can be.
     * @return What was found.
     */
    public static Found resolve(
            final PoreloContext context,
            final @Nullable String given,
            final Path directory,
            final @Nullable Asker asker) {
        if (given != null && !given.isBlank()) {
            return new Found(spelled(context, given.strip(), directory), "", 0);
        }
        final List<String> started = fromCheckout(context, directory);
        if (started == null) {
            return new Found(
                    null,
                    "name a task; 'porelo task list' lists them, and in a checkout a task was started"
                            + " from no name is needed",
                    SEVERAL);
        }
        final Path top =
                java.util.Objects.requireNonNull(
                        LocalRepository.topLevel(context.runner(), directory));
        if (started.isEmpty()) {
            return new Found(
                    null,
                    "no task was started from " + top + "; 'porelo task start' starts one",
                    NONE);
        }
        if (started.size() == 1) {
            return new Found(started.getFirst(), "", 0);
        }
        final String named = String.join(", ", started);
        final String answer =
                asker == null
                        ? null
                        : asker.ask(
                                "Several tasks were started from "
                                        + top
                                        + ": "
                                        + named
                                        + ". Which one?");
        if (answer == null || answer.isBlank()) {
            return new Found(
                    null,
                    "several tasks were started from " + top + ": " + named + "; name one",
                    SEVERAL);
        }
        final String chosen = spelled(context, answer.strip(), directory);
        return started.contains(chosen)
                ? new Found(chosen, "", 0)
                : new Found(null, "'" + answer.strip() + "' is none of " + named, SEVERAL);
    }

    /**
     * Returns an asker on the person's terminal, or {@code null} when there is none to ask on.
     *
     * @param out Where the question is shown.
     * @return The asker, or {@code null}.
     */
    public static @Nullable Asker console(final java.io.PrintWriter out) {
        final java.io.Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return null;
        }
        return question -> {
            out.print(question + " ");
            out.flush();
            return console.readLine();
        };
    }
}
