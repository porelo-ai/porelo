package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CardProposals;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Withdraws or replaces a person's card. */
@Command(
        name = "person",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Withdraws or replaces a person's card.",
        subcommands = {
            OrgPersonCommand.Revoke.class,
            OrgPersonCommand.Replace.class,
            OrgPersonCommand.Proposals.class,
            OrgPersonCommand.Accept.class,
            OrgPersonCommand.Decline.class
        })
public class OrgPersonCommand {

    /** Withdraws a card, which revokes its key. */
    @Command(
            name = "revoke",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Withdraws a card, which revokes its key.")
    public static class Revoke extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().revokeCard(change, login);
        }
    }

    /** Puts a new card in place of a lost key's. */
    @Command(
            name = "replace",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Puts a new card in place of a lost key's.")
    public static class Replace extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Option(names = "--card", required = true, description = "The person's new card.")
        private Path card = Path.of("");

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().replaceCard(change, login, file(card, "--card"));
        }
    }

    /** Lists only own-branch, own-author card proposals, judged by TreeWalk. */
    @Command(
            name = "proposals",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Judges open card proposals against a fresh main.")
    public static class Proposals extends OrgProposing {
        @Override
        Map<String, @Nullable Object> proposed(PoreloContext machine) throws IOException {
            return Map.of(
                    "proposals",
                    proposals.open(machine).proposals().stream().map(Proposals::proposal).toList());
        }

        private static Map<String, Object> proposal(CardProposals.Proposal proposal) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("login", proposal.login());
            answer.put("name", proposal.name());
            answer.put("fingerprint", proposal.fingerprint());
            answer.put("pullRequest", proposal.pullRequest().url());
            answer.put("opened", proposal.pullRequest().opened());
            answer.put("state", proposal.state());
            answer.put("reason", proposal.reason());
            return answer;
        }

        @Override
        void text(Map<String, @Nullable Object> answer) {
            if (answer.get("proposals") instanceof java.util.List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> proposal) {
                        line(
                                proposal.get("login")
                                        + ": "
                                        + proposal.get("state")
                                        + " "
                                        + proposal.get("pullRequest"));
                        if (proposal.get("reason") instanceof String reason && !reason.isEmpty())
                            line(reason);
                    }
                }
            }
        }
    }

    /** A fresh, authenticated admin decision on one person's proposal. */
    public abstract static class Decision extends OrgProposing {
        @Parameters(paramLabel = "LOGIN", description = "The person's login.")
        private String login = "";

        abstract CardProposals.Result decide(
                CardProposals proposals, OrganizationWrites.Acting acting, String login)
                throws IOException;

        @Override
        Map<String, @Nullable Object> proposed(PoreloContext machine) throws IOException {
            final var acting = actor.of(machine);
            return result(decide(proposals.open(machine), acting, login));
        }
    }

    /** Accepts the person's own commit unchanged, as a leased fast-forward. */
    @Command(
            name = "accept",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Accepts a ready proposed card as an organization admin.")
    public static class Accept extends Decision {
        @Override
        CardProposals.Result decide(
                CardProposals proposals, OrganizationWrites.Acting acting, String login)
                throws IOException {
            return proposals.accept(acting, login);
        }
    }

    /** Declines a proposal with a comment, after checking the admin's role and key. */
    @Command(
            name = "decline",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Declines a proposed card as an organization admin.")
    public static class Decline extends Decision {
        @Override
        CardProposals.Result decide(
                CardProposals proposals, OrganizationWrites.Acting acting, String login)
                throws IOException {
            return proposals.decline(acting, login);
        }
    }
}
