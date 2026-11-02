package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Moves a task's messages along once: out through the filter and a transport, in through the
 * signature check, and the filter's answers into the agent's inbox.
 *
 * <p>By hand, for now. Nothing runs this on a schedule yet, which is worth knowing rather than
 * discovering: a message sits in the outbox until somebody - or later the daemon - moves it.
 */
@Command(
        name = "pass",
        mixinStandardHelpOptions = true,
        description = "Moves this task's messages along once.")
public class TalkPassCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            description =
                    "The project whose peers apply, as 'porelo project list' prints it."
                            + " Default: the task's own.")
    private @Nullable String projectName;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        // By the container's name or the task's own, as every task command takes it; with the
        // project given, that project's task, never another one's of the same task name.
        if (projectName == null) {
            container = TaskTarget.spelled(context, container);
            if (!isTaskContainer(container)) {
                // It names the mailbox's directory, so a path would open whatever it reaches.
                err.println("porelo: " + container + " is no task's container name");
                err.flush();
                return 64;
            }
        } else {
            if (!projectName.matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)) {
                // It names the mailbox's directory, so nothing else may stand where it does.
                err.println("porelo: the project given is no project name");
                err.flush();
                return 64;
            }
            final String taskName =
                    org.fuin.porelo.runtime.ContainerName.taskFrom(projectName, container);
            final java.util.Optional<String> refusal =
                    org.fuin.porelo.runtime.ContainerName.refusal(projectName, taskName);
            if (refusal.isPresent()) {
                err.println("porelo: " + refusal.get());
                err.flush();
                return 64;
            }
            container = org.fuin.porelo.runtime.ContainerName.PREFIX + projectName + "-" + taskName;
            final String named = container;
            final String owner = ProjectNames.labels(context).getOrDefault(named, projectName);
            if (!projectName.equals(owner)) {
                err.println(
                        "porelo: "
                                + container
                                + " is no task of project "
                                + projectName
                                + " but of "
                                + owner);
                err.flush();
                return 66;
            }
        }
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("porelo: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        // The task knows its own project, recorded on the container; asking for it again would be
        // asking somebody to repeat what the machine already knows.
        final String known =
                projectName != null ? projectName : new TaskInventory(context).projectOf(container);
        final String project =
                known != null
                        ? known
                        : ProjectSource.chosen(
                                context,
                                Offer.atTerminal()
                                        ? new Offer(Offer.Asker.terminal(), false, false, err)
                                        : Offer.NOBODY,
                                "porelo talk pass --project <name>");
        if (project == null) {
            err.println("porelo: " + container + " names no project; say which, with --project");
            err.flush();
            return 2;
        }
        final var pass =
                new MessagePass(
                        context.runner(),
                        context.paths().messaging().messageFilter(),
                        context.paths().messaging().transportDirectory());
        // As the daemon's pass does it: the project's conversation first, the task's siblings as
        // its peers, and sending as the task - so a pass by hand and one by the timer cannot
        // disagree. Read first, so a project whose file is gone stops here and is named, rather
        // than passing with no peers.
        // The project given, never one derived again from the container's name, which may name
        // none: a removed task of a project whose name begins another's, say.
        final org.fuin.porelo.core.project.Project resolved = GateSupport.byName(context, project);
        final MessageWatch watch = new MessageWatch(context, java.time.Duration.ZERO);
        watch.conversations(pass, java.util.List.of(mailbox.root()), resolved)
                .forEach(failure -> err.println("porelo: " + TalkReadCommand.shown(failure)));
        final MessageSigners keys = MessageSigners.of(context, container, resolved);
        final MessagePass.Report report =
                pass.run(
                        mailbox,
                        watch.withSiblings(resolved, container),
                        keys::own,
                        keys,
                        Moderation.of(context, resolved));

        report.polled()
                .failures()
                .forEach(
                        (transport, why) ->
                                err.println(
                                        "transport "
                                                + transport
                                                + " - "
                                                + TalkReadCommand.shown(why)));
        if (!report.polled().arrivals().isEmpty()) {
            out.println(
                    "fetched   "
                            + report.polled().arrivals().size()
                            + " by "
                            + String.join(
                                    ", ",
                                    new java.util.TreeSet<>(report.polled().arrivals().values())));
        }
        out.println("taken     " + report.taken().size());
        if (!report.filtered().ran()) {
            // Not a warning among others: this is the reason nothing left, and it is the state an
            // operator has to act on.
            err.println("porelo: " + TalkReadCommand.shown(report.filtered().detail()));
            err.flush();
        } else if (!report.filtered().detail().isEmpty()) {
            err.println("porelo: " + TalkReadCommand.shown(report.filtered().detail()));
            err.flush();
        }
        out.println(
                "queued    "
                        + report.dispatched().queued().size()
                        + (report.dispatched().held().isEmpty()
                                ? ""
                                : ", held " + report.dispatched().held().size()));
        report.sent()
                .forEach(
                        (transport, result) ->
                                out.println(
                                        "sent      "
                                                + result.sent().size()
                                                + " by "
                                                + transport
                                                + (result.deferred().isEmpty()
                                                        ? ""
                                                        : ", "
                                                                + result.deferred().size()
                                                                + " waiting")
                                                + (result.refused().isEmpty()
                                                        ? ""
                                                        : ", "
                                                                + result.refused().size()
                                                                + " refused")));
        out.println(
                "delivered "
                        + report.delivered().delivered().size()
                        + (report.delivered().held().isEmpty()
                                ? ""
                                : ", held " + report.delivered().held().size()));
        report.delivered()
                .held()
                .forEach(
                        held ->
                                err.println(
                                        "held      "
                                                + TalkReadCommand.shown(held.message())
                                                + " - "
                                                + TalkReadCommand.shown(held.reason())));
        report.delivered()
                .duplicates()
                .forEach(
                        repeat ->
                                out.println(
                                        "dropped   "
                                                + repeat.message()
                                                + " - "
                                                + repeat.id()
                                                + " was delivered before"));
        out.println("answers   " + report.bounced().size());
        out.flush();
        err.flush();
        return 0;
    }

    /** A task's container name: the prefix, then a project's and a task's alphabet alone. */
    private static boolean isTaskContainer(final String name) {
        return org.fuin.porelo.runtime.ContainerName.isTask(name)
                && name.length() <= org.fuin.porelo.runtime.ContainerName.MAX_LENGTH
                && name.substring(org.fuin.porelo.runtime.ContainerName.PREFIX.length())
                        .matches(org.fuin.porelo.core.project.Project.TASK_NAME_PATTERN);
    }
}
