package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** A command that reads the organization: one answer, a refusal by its reason, or a failure. */
public abstract class OrgReading implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which has no forge to ask. */
    OrgAccess.Opener opener = OrgAccess::real;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine = Objects.requireNonNull(context, "picocli sets the context");
        try {
            final Map<String, @Nullable Object> answer = answer(machine);
            if (json) {
                OrgOutput.answer(spec, answer);
            } else {
                text(answer);
            }
            return 0;
        } catch (Refused refused) {
            return OrgOutput.refuse(spec, json, refused);
        } catch (IOException ex) {
            OrgOutput.failed(spec, json, String.valueOf(ex.getMessage()));
            throw ex;
        }
    }

    /**
     * Answers, in the order the contract names the fields.
     *
     * @param machine The machine.
     * @return The answer.
     * @throws IOException When something unexpected happened.
     * @throws Refused When it says no.
     */
    abstract Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException;

    /**
     * Says an answer to a person.
     *
     * @param answer The answer.
     */
    abstract void text(Map<String, @Nullable Object> answer);

    /** Prints a line for a person, whatever a file put into it. */
    final void line(final String line) {
        OrgOutput.line(spec, line);
    }
}
