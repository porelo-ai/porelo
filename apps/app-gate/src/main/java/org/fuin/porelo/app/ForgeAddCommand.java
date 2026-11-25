package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Adds a forge instance, or changes its adapter; its comments are then read extended, also where
 * they were trusted, so adding never loosens a reading.
 */
@Command(
        name = "add",
        mixinStandardHelpOptions = true,
        description = "Adds a forge instance by its host; its comments are read extended.")
public class ForgeAddCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<host>", description = "The forge's host, a DNS name.")
    private String host = "";

    @Option(
            names = "--adapter",
            required = true,
            paramLabel = "<name>",
            description = "The installed forge adapter that reads it, such as github.")
    private String adapter = "";

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final ForgeInstanceFile forges = ForgeInstanceFile.of(context);
        try {
            forges.set(host, adapter, CommentReading.EXTENDED);
        } catch (AdapterNotInstalled ex) {
            err.println(
                    "porelo: "
                            + ex.getMessage()
                            + "; installed: "
                            + String.join(", ", ForgeAdapters.of(context).names()));
            err.flush();
            return 2;
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        } catch (ForgeFileUnreadable ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        out.println(
                "added     "
                        + host
                        + " read by "
                        + adapter
                        + ", its comments extended; 'porelo forge comments "
                        + host
                        + " trusted' trusts them");
        out.flush();
        return 0;
    }
}
