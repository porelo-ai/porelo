package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Prints the name this account goes by on a transport, which a project admin lists to let it into a
 * conversation.
 */
@Command(
        name = "machine",
        mixinStandardHelpOptions = true,
        description =
                "Prints the name this account goes by on a transport: <host>-<account>, which a"
                        + " project admin lists.")
public class TalkMachineCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final String name;
        try {
            name = context.machineName().get();
        } catch (IllegalArgumentException ex) {
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
