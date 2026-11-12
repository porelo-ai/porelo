package org.fuin.porelo.app;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.gittree.CardProposals;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/** The proposal commands' credentials and output, without a dependency on the vault's commands. */
abstract class OrgProposing extends OrgReading {
    @FunctionalInterface
    interface Opening {
        CardProposals open(PoreloContext machine) throws IOException;
    }

    @FunctionalInterface
    interface Actor {
        Acting of(PoreloContext machine) throws IOException;
    }

    Opening proposals = OrgProposing::real;
    Actor actor = OrgProposing::acting;

    private static CardProposals real(PoreloContext machine) throws IOException {
        String repository =
                OrgAccess.repository(machine)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NO_ORGANIZATION,
                                                "this machine has no organization: run porelo setup first",
                                                null));
        ForgeAddress address = ForgeAddress.of(repository);
        String token = OrganizationAccess.forgeToken(machine, address);
        if (token == null)
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "no forge token can be lent: unlock the vault or declare a token for this forge, then ask again",
                    null);
        final @Nullable String trusted = OrgWriting.current(machine, OrgAccess::real);
        String socket = machine.environment().apply(OrganizationAccess.AGENT);
        return new CardProposals(
                        machine.runner(),
                        repository,
                        address.organization(),
                        OrgProposalAccess.lent(machine, address),
                        OrgAccess.real(machine, repository).identities(),
                        CommitSigning.agent(socket == null ? "" : socket),
                        new ForgeCardProposals(address, token, OrganizationAccess.http(machine)),
                        Clock.systemUTC())
                .standingAt(trusted);
    }

    private static Acting acting(PoreloContext machine) throws IOException {
        PersonCard card = OrgWriting.ownCard(machine);
        return new Acting(
                card.login(),
                PersonCard.keyLine(
                        OrgWriting.served(
                                machine.environment().apply(OrganizationAccess.AGENT),
                                card.fingerprint())));
    }

    /**
     * Answers through the proposals, an organization repository that cannot be reached or read
     * answered as one that cannot say, as every other organization command answers it.
     */
    @Override
    final Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException {
        try {
            return proposed(machine);
        } catch (GitTreeStore.Unreachable ex) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    ex instanceof GitTreeStore.NotTaken
                            ? ex.getMessage() + ": ask again"
                            : ForgeAddress.withoutUserinfoIn(String.valueOf(ex.getMessage()))
                                    + "; a change may have been made: ask again",
                    null);
        }
    }

    /**
     * Answers the command through the proposals.
     *
     * @param machine The machine's context.
     * @return The answer.
     * @throws IOException If it cannot be answered.
     */
    abstract Map<String, @Nullable Object> proposed(PoreloContext machine) throws IOException;

    static Map<String, @Nullable Object> result(CardProposals.Result result) {
        Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("state", result.state());
        answer.put("pullRequest", result.pullRequest());
        return answer;
    }

    @Override
    void text(Map<String, @Nullable Object> answer) {
        line(String.valueOf(answer.get("state")));
        if (answer.get("pullRequest") instanceof String url) line(url);
    }
}
