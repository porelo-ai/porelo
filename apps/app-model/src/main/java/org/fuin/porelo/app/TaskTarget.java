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
    public record Found(
            @Nullable String container,
            String refusal,
            int code,
            TaskIdentities.@Nullable Reason reason) {
        public Found(@Nullable String container, String refusal, int code) {
            this(container, refusal, code, null);
        }
    }

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
        final TaskIdentities.Result result = identified(context, given, directory);
        return result instanceof TaskIdentities.Found found ? found.identity().container() : given;
    }

    private static TaskIdentities.Result identified(
            PoreloContext context, String given, Path directory) {
        final TaskIdentities identities = new TaskIdentities(context);
        final TaskIdentities.Result result = identities.lookup(given, null);
        if (result instanceof TaskIdentities.Refused refused
                && refused.reason() == TaskIdentities.Reason.AMBIGUOUS) {
            final Checkout standing = checkout(context, directory);
            if (standing != null) {
                return identities.lookup(given, standing.project());
            }
        }
        return result;
    }

    private static Found found(TaskIdentities.Result result) {
        if (result instanceof TaskIdentities.Found found) {
            return new Found(found.identity().container(), "", 0);
        }
        final TaskIdentities.Refused refused = (TaskIdentities.Refused) result;
        return new Found(
                null,
                "cannot resolve task '"
                        + refused.name()
                        + "': "
                        + refused.said()
                        + "; 'porelo task list' lists tasks by their actual names",
                refused.reason() == TaskIdentities.Reason.AMBIGUOUS ? SEVERAL : NONE,
                refused.reason());
    }

    /** The project and repository a checkout belongs to, as its start takes them. */
    private record Checkout(String project, @Nullable String repository) {}

    /**
     * A task started from a checkout: its own name, its container's and whether it runs.
     *
     * @param project The task's project.
     * @param task The task's name, as a person gives it to {@code porelo task start}.
     * @param container Its container's name, which is spelled differently.
     * @param running Whether it runs now.
     */
    public record CheckoutTask(String project, String task, String container, boolean running) {}

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
        final List<CheckoutTask> started = startedFrom(context, directory);
        return started == null ? null : started.stream().map(CheckoutTask::container).toList();
    }

    /**
     * Returns the tasks started from the checkout a directory is in, each by the name its own
     * identity records, since a container's name is not the task's name with a prefix.
     *
     * @param context The machine.
     * @param directory Where the person stands.
     * @return The tasks, the running ones first, possibly none; {@code null} when the directory is
     *     in no checkout a repository is known by.
     */
    public static @Nullable List<CheckoutTask> tasksOfCheckout(
            final PoreloContext context, final Path directory) {
        final List<CheckoutTask> started = startedFrom(context, directory);
        if (started == null) {
            return null;
        }
        final List<CheckoutTask> runningFirst = new java.util.ArrayList<>(started);
        runningFirst.sort(java.util.Comparator.comparing(each -> !each.running()));
        return List.copyOf(runningFirst);
    }

    private static @Nullable List<CheckoutTask> startedFrom(
            final PoreloContext context, final Path directory) {
        final Checkout found = checkout(context, directory);
        if (found == null) {
            return null;
        }
        final String inProject = found.project();
        final String ofRepository = found.repository();
        if (ofRepository == null) {
            return List.of();
        }
        final TaskIdentities identities = new TaskIdentities(context);
        final List<CheckoutTask> started = new java.util.ArrayList<>();
        for (final ContainerSummary task : context.podman().poreloTasks()) {
            if (identities.read(task) instanceof TaskIdentities.Found verified
                    && inProject.equals(verified.identity().project())
                    && ofRepository.equals(task.repository())) {
                started.add(
                        new CheckoutTask(
                                inProject,
                                verified.identity().task(),
                                task.name(),
                                task.running()));
            }
        }
        return started;
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
            return found(identified(context, given.strip(), directory));
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
            return found(identified(context, started.getFirst(), directory));
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
        final Found chosen = found(identified(context, answer.strip(), directory));
        return started.contains(chosen.container())
                ? chosen
                : new Found(null, "'" + answer.strip() + "' is none of " + named, SEVERAL);
    }

    /** Resolves a read that also accepts an absent task's actual name as its history key. */
    public static Found resolveRead(
            PoreloContext context, @Nullable String given, Path directory, @Nullable Asker asker) {
        final Found found = resolve(context, given, directory, asker);
        if (found.reason() == null || given == null || given.isBlank()) {
            return found;
        }
        // A name no task has goes on to the command's own answer, as before identities: not a task
        // of this machine, no task called it, or an absent task's history.
        if (found.reason() == TaskIdentities.Reason.MISSING) {
            return new Found(given.strip(), "", 0, found.reason());
        }
        return found.reason() != TaskIdentities.Reason.AMBIGUOUS
                        && org.fuin.porelo.runtime.ContainerName.isTask(given)
                        && given.matches("porelo-[a-z0-9_-]+")
                ? new Found(given, "", 0, found.reason())
                : found;
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
