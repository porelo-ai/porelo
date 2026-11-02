package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;

/** Points this machine at an organization, once it is seen to answer. */
public final class OrgSetup {

    @Option(
            names = "--organization",
            paramLabel = "ADDRESS",
            description = "Points this machine at the organization kept at this address.")
    private @Nullable String address;

    @Option(names = "--replace", description = "Replaces the organization this machine has.")
    private boolean replace;

    @Option(names = "--json", description = "Answers an organization's set-up as JSON.")
    private boolean json;

    /** Replaced by a test, which has no forge to ask. */
    OrgAccess.Opener opener = OrgAccess::real;

    /**
     * Returns whether this set-up is the organization's, and not the account's.
     *
     * @return Whether an address was named.
     */
    public boolean named() {
        return address != null;
    }

    /**
     * Checks the organization and writes this machine's settings.
     *
     * @param machine The machine.
     * @param spec The command, whose streams it answers on.
     * @return The exit code.
     */
    public int run(final PoreloContext machine, final CommandSpec spec) {
        try {
            final Map<String, @Nullable Object> answer = answer(machine);
            if (json) {
                OrgOutput.answer(spec, answer);
            } else {
                OrgOutput.line(spec, "This machine's organization: " + answer.get("name"));
            }
            return 0;
        } catch (Refused refused) {
            return OrgOutput.refuse(spec, json, refused);
        } catch (IOException | RuntimeException ex) {
            OrgOutput.failed(spec, json, "the organization could not be set up");
            return 70;
        }
    }

    private Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final String named = OrgAccess.settable(String.valueOf(address));
        final String held = OrgAccess.repository(machine).orElse(null);
        if (held != null && !held.equals(named) && !replace) {
            throw new Refused(
                    Refused.EXISTS,
                    "this machine has an organization already: --replace puts this one in its"
                            + " place",
                    null);
        }
        final OrganizationQuestions state = checked(machine, named);
        OrgAccess.settle(machine, named);
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("name", state.describe().name());
        answer.put("revision", state.revision());
        answer.put(
                "address", org.fuin.porelo.organization.git.RegistryOrigin.withoutUserinfo(named));
        return answer;
    }

    /** Settings a machine could not read an organization with would only fail later. */
    private OrganizationQuestions checked(final PoreloContext machine, final String named)
            throws IOException {
        final OrgAccess.Opened opened = opener.open(machine, named);
        try {
            return opened.store().read(opened.identities());
        } catch (GitTreeStore.Unreachable ex) {
            throw new Refused(Refused.CANNOT_SAY, "the organization cannot be reached", null);
        } catch (CommandException ex) {
            throw new Refused(Refused.CANNOT_SAY, "the organization did not answer in time", null);
        } catch (OrganizationException ex) {
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), null);
        }
    }
}
