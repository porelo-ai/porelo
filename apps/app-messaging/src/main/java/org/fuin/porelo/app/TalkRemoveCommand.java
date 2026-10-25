package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Removes a person from a project's conversation through its transport. */
@Command(
        name = "remove",
        mixinStandardHelpOptions = true,
        description = "Removes a person from a project's conversation.")
public class TalkRemoveCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Parameters(index = "1", paramLabel = "<person>", description = "Whom to remove.")
    private String person;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final Project project = GateSupport.byName(context, projectName);
        final PrintWriter err = spec.commandLine().getErr();
        if (TalkJoinCommand.noConversation(project, err)) {
            return 64;
        }
        final Conversations.Removal removed;
        try {
            removed = Conversations.remove(context, project, person);
        } catch (Conversations.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return ex.missing() != null ? 64 : ex.code() == 17 ? 1 : 69;
        }
        final PrintWriter out = spec.commandLine().getOut();
        if (removed.kept()) {
            out.println(
                    "removed   "
                            + person
                            + " from "
                            + projectName
                            + "; their account "
                            + removed.user()
                            + " stays: "
                            + removed.why());
        } else {
            out.println(
                    "removed   "
                            + person
                            + (removed.user().isEmpty()
                                    ? " (nothing was left in the room)"
                                    : " (" + removed.user() + ")"));
        }
        out.flush();
        return 0;
    }
}
