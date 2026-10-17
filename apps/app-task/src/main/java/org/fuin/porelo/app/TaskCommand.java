package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo task} command group. */
@Command(
        name = "task",
        aliases = {"tasks"},
        mixinStandardHelpOptions = true,
        description = "Runs and inspects agent tasks.",
        subcommands = {
            TaskRunCommand.class,
            TaskListCommand.class,
            TaskStopCommand.class,
            TaskRefreshCommand.class,
            TaskRemoveCommand.class,
            TaskLabelCommand.class,
            TaskAttachCommand.class,
            TaskStatusCommand.class,
            TaskLogsCommand.class,
            TaskPrepareCommand.class,
            TaskClearanceCommand.class,
            TaskGiveCommand.class,
            TaskTakeBackCommand.class,
            TaskFilesCommand.class,
            TaskWatchBuildsCommand.class
        })
public class TaskCommand
        implements java.util.concurrent.Callable<Integer>, PoreloFactory.ContextAware {

    @picocli.CommandLine.Spec private picocli.CommandLine.Model.CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * Lists, when no verb was given.
     *
     * <p>Because {@code porelo tasks} should answer the question it looks like it is asking, and
     * because the same is true of {@code porelo projects}. Help for a command whose name is a noun
     * is an answer nobody wanted.
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        final TaskListCommand listing = new TaskListCommand();
        listing.setContext(context);
        return listing.render(spec.commandLine().getOut());
    }
}
