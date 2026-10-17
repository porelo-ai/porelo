package org.fuin.porelo.app;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the projects this machine follows, and what each holds.
 *
 * <p>The same rendering {@code porelo project} does with no verb. It exists so the verb can be
 * written where a reader expects one, beside {@code delete}.
 */
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the projects this machine follows, and what each holds.")
public class ProjectListCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final ProjectsCommand listing = new ProjectsCommand();
        listing.setContext(context);
        return listing.render(spec.commandLine().getOut());
    }
}
