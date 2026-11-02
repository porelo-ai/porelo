package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Answers a host's card, as filed. */
@Command(
        name = "host-card",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "A host's card, as filed.")
public class OrgHostCardCommand extends OrgReading {

    @Parameters(index = "0", paramLabel = "HOST", description = "Whose card.")
    private String name = "";

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
        final OrganizationQuestions state = reading.answers();
        final String card =
                state.getHostCard(name)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NOT_FOUND,
                                                "no card of that name is held",
                                                state.revision()));
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", state.revision());
        answer.put("stale", reading.stale());
        answer.put("host", name);
        answer.put("card", card);
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        staleness(answer);
        String.valueOf(answer.get("card")).lines().forEach(this::line);
    }
}
