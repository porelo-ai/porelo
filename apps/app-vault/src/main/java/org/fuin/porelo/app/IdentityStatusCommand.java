package org.fuin.porelo.app;

import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Reads a key change back as it was last kept, changing nothing. */
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Shows a key change and where its card arrived, changing nothing.")
public class IdentityStatusCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--id", required = true, paramLabel = "ID", description = "The operation.")
    private String id = "";

    @Option(names = "--json", description = "Answers the operation and its places as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            IdentityChange.print(spec, json, required(machine, id));
            return 0;
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        }
    }

    /**
     * The operation kept under an id.
     *
     * @param context The machine.
     * @param id The id.
     * @return It.
     * @throws IdentityChange.Refused 64 for an id none could carry, 66 {@code no-operation} when
     *     none is kept under it or it cannot be read.
     */
    static IdentityOperation required(final PoreloContext context, final String id)
            throws IdentityChange.Refused {
        IdentityChange.checkId(id);
        final IdentityOperation kept = IdentityChange.kept(context, id, null);
        if (kept == null) {
            throw new IdentityChange.Refused(
                    66, "no-operation", "no identity operation " + id + " is kept here");
        }
        return kept;
    }
}
