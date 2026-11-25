package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Sets how a known forge instance's comments are read before a run sees them. */
@Command(
        name = "comments",
        mixinStandardHelpOptions = true,
        description =
                "Sets whether a forge's comments go through the extended check or are trusted.")
public class ForgeCommentsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<host>", description = "A forge's host, added before.")
    private String host = "";

    @Parameters(
            index = "1",
            paramLabel = "extended|trusted",
            description = "extended: every comment through the inbound check; trusted: none.")
    private String reading = "";

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
        final String named = host.strip().toLowerCase(Locale.ROOT);
        if (!"extended".equals(reading) && !"trusted".equals(reading)) {
            err.println("porelo: comments are extended or trusted, not '" + reading + "'");
            err.flush();
            return 2;
        }
        try {
            final Optional<ForgeInstance> known =
                    forges.all().stream()
                            .filter(instance -> instance.host().equals(named))
                            .findFirst();
            if (known.isEmpty() || known.get().adapter() == null) {
                err.println(
                        "porelo: "
                                + (known.isEmpty()
                                        ? "no forge " + named + " is known"
                                        : "the record of " + named + " cannot be read")
                                + "; 'porelo forge add "
                                + named
                                + " --adapter <name>' adds it");
                err.flush();
                return 2;
            }
            forges.set(
                    named,
                    java.util.Objects.requireNonNull(known.get().adapter(), "checked above"),
                    "trusted".equals(reading) ? CommentReading.TRUSTED : CommentReading.EXTENDED);
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        } catch (ForgeFileUnreadable ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        out.println("set       " + named + " comments " + reading);
        forges.loosened().ifPresent(why -> out.println("note      " + why));
        out.flush();
        return 0;
    }
}
