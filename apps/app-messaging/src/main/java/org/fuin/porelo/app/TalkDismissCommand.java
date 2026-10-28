package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Takes another machine off this machine's conversation server: out of every project's conversation
 * there, and its registration refused.
 */
@Command(
        name = "dismiss",
        mixinStandardHelpOptions = true,
        description =
                "Takes another machine off this machine's conversation server: out of every"
                        + " project's conversation there, its token revoked.")
public class TalkDismissCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Parameters(index = "1", paramLabel = "<machine>", description = "The machine taken out.")
    private String machine;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        final Map<?, ?> said;
        try {
            said = MachineTransports.dismiss(context, project, machine);
        } catch (Conversations.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return ex.code() == 0 ? 69 : ex.code();
        }
        out.println(said(machine, said));
        out.flush();
        return 0;
    }

    /**
     * Says what a dismiss did. The transport takes the machine out of every project's conversation
     * on this server and revokes its token, so the line names the server, not the project it was
     * asked through.
     *
     * @param machine The machine taken out.
     * @param said What the transport answered.
     * @return The line.
     */
    static String said(final String machine, final Map<?, ?> said) {
        return said.get("removed") instanceof java.util.List<?> removed && !removed.isEmpty()
                ? "dismissed "
                        + machine
                        + " from every project's conversation on this server, its token revoked"
                : "nothing of " + machine + " was left on this server";
    }
}
