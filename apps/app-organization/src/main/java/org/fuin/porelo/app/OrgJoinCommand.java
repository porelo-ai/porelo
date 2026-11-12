package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Files this machine's kept card, or proposes it for an organization admin to accept. */
@Command(
        name = "join",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Files your kept card, or proposes it for an organization admin to accept.")
public class OrgJoinCommand extends OrgProposing {
    @Option(names = "--withdraw", description = "Closes your open proposed card with a comment.")
    private boolean withdraw;

    @Override
    Map<String, @Nullable Object> proposed(PoreloContext machine) throws IOException {
        final String card;
        try {
            card = OrgWriting.ownCardText(machine);
        } catch (Refused ex) {
            if (!ex.reason().equals(Refused.NO_CARD)) throw ex;
            throw new Refused(
                    Refused.NO_CARD,
                    "this machine holds no card of yours: run porelo identity create first",
                    null);
        }
        final org.fuin.porelo.organization.OrganizationWrites.Acting acting;
        if (withdraw) {
            final var held = OrgWriting.ownCard(machine);
            acting =
                    new org.fuin.porelo.organization.OrganizationWrites.Acting(
                            held.login(), held.key());
        } else {
            acting = actor.of(machine);
        }
        return result(proposals.open(machine).join(acting, card, withdraw));
    }
}
