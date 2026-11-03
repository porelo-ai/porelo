package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Fills an organization's empty repository and makes this machine's person its first owner. */
@Command(
        name = "create",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Fills an organization's empty repository, made on the forge first, and makes you"
                        + " its first owner.")
public class OrgCreateCommand extends OrgReading {

    /** Opens the changes at an address; replaced by a test, which has no agent or forge. */
    @FunctionalInterface
    interface Founder {
        OrgWriting.Session open(PoreloContext machine, String address) throws IOException;
    }

    @Parameters(paramLabel = "ADDRESS", description = "The organization's empty repository.")
    private String address = "";

    @Option(names = "--id", description = "Names the change: asked again, the same answer.")
    private @Nullable String id;

    Founder founder = OrgWriting::at;

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final String settable = OrgAccess.settable(address);
        // The same address goes on: the store says whether this is the same change asked again.
        final String held = OrgAccess.repository(machine).orElse(null);
        if (held != null && !held.equals(settable)) {
            throw new Refused(Refused.EXISTS, "this machine has an organization already", null);
        }
        final String card = OrgWriting.ownCardText(machine);
        final OrgWriting.Session opened = founder.open(machine, settable);
        final String named = id == null ? "org-" + UUID.randomUUID() : id;
        final OrganizationWrites.Change change;
        try {
            change = new OrganizationWrites.Change(opened.acting(), null, named);
        } catch (IllegalArgumentException ex) {
            throw new Refused(Refused.USAGE, String.valueOf(ex.getMessage()), null);
        }
        final OrganizationWrites.Written written;
        try {
            written = opened.writes().createOrganization(change, card);
        } catch (Refused ex) {
            throw needingToken(machine, settable, ex);
        } catch (GitTreeStore.Unreachable ex) {
            throw OrgAccess.unreached(
                    ex,
                    "the organization cannot be reached, and it may have been filled: ask"
                            + " again with --id "
                            + named,
                    settable);
        }
        OrgAccess.settle(machine, settable);
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", written.revision());
        answer.put("id", written.id());
        answer.put("again", written.again());
        answer.put("of", "organization");
        answer.put("address", ForgeAddress.withoutUserinfo(settable));
        return answer;
    }

    /** The founder's ownership is the forge's to say, and it says so only to a token. */
    private static Refused needingToken(
            final PoreloContext machine, final String address, final Refused refused) {
        final ForgeAddress origin = ForgeAddress.of(address);
        final String revision = refused.revision();
        final String standing = revision == null || revision.isEmpty() ? null : revision;
        if (!Refused.CANNOT_SAY.equals(refused.reason())
                || OrganizationAccess.forgeToken(machine, origin) != null) {
            return new Refused(refused.reason(), String.valueOf(refused.getMessage()), standing);
        }
        return new Refused(
                Refused.CANNOT_SAY,
                "the forge must say that you own this organization, and that takes a token:"
                        + " porelo credentials declare "
                        + origin.forge()
                        + " --kind token --vault NAME, porelo vault put NAME, then ask again",
                standing);
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line("Filled at " + answer.get("revision") + ", under the id " + answer.get("id"));
        line(
                "Your key must stay on the forge until a card is handed on, so keep a second owner"
                        + " or a backup key.");
    }
}
