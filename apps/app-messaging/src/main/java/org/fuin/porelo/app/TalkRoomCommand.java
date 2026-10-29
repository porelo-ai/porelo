package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Makes a project's room on its server, as the project admin's machine. From then on this account
 * keeps the room's machines to the applied developer list after each applied commit.
 */
@Command(
        name = "room",
        mixinStandardHelpOptions = true,
        description =
                "Makes a project's room on its server, as the project admin's machine; after each"
                        + " applied commit this account keeps the room's machines to the list.")
public class TalkRoomCommand implements Callable<Integer>, PoreloFactory.ContextAware {

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
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        final Map<String, String> made;
        try {
            made = MachineTransports.createRoom(context, project);
        } catch (Conversations.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return ex.code() == 0 ? 69 : ex.code();
        }
        out.println("room       " + made.get("room") + "  for " + project.name());
        out.println("made by    " + made.get("machine"));
        Map<?, ?> kept;
        try {
            // Kept to the list at once, not only after the next applied commit.
            kept = MachineTransports.keepMembers(context, project);
        } catch (Conversations.Refused ex) {
            err.println(
                    "porelo: the room is made, and its machines are not kept yet: "
                            + ex.getMessage());
            err.flush();
            out.flush();
            return 0;
        }
        if (kept != null) {
            out.println("invited    " + listed(kept.get("invited")));
            out.println("removed    " + listed(kept.get("removed")));
        }
        out.flush();
        return 0;
    }

    /** The machines a list names, comma separated, or "-" for none. */
    private static String listed(final @Nullable Object machines) {
        if (!(machines instanceof java.util.List<?> list) || list.isEmpty()) {
            return "-";
        }
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }
}
