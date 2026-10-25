package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Lists the people who joined a project's conversation. */
@Command(
        name = "members",
        mixinStandardHelpOptions = true,
        description = "Lists who has joined a project's conversation.")
public class TalkMembersCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

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
        final PrintWriter out = spec.commandLine().getOut();
        final Map<String, String> members = Conversations.members(context, project);
        if (members.isEmpty()) {
            out.println("nobody has joined " + projectName);
        } else {
            out.printf("%-16s %s%n", "PERSON", "ACCOUNT");
            members.forEach((person, account) -> out.printf("%-16s %s%n", person, account));
        }
        out.flush();
        return 0;
    }
}
