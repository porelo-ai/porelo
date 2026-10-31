package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Prints the name this account goes by on a transport, its login, which a project admin lists to
 * let it into a conversation.
 */
@Command(
        name = "machine",
        mixinStandardHelpOptions = true,
        description =
                "Prints the name this account goes by on a transport: its login, which a project"
                        + " admin lists.")
public class TalkMachineCommand implements Callable<Integer> {

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        final String name;
        try {
            name = TransportLifecycle.login();
        } catch (TransportLifecycle.Refused ex) {
            final PrintWriter err = spec.commandLine().getErr();
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 78;
        }
        final PrintWriter out = spec.commandLine().getOut();
        out.println(name);
        out.flush();
        return 0;
    }
}
