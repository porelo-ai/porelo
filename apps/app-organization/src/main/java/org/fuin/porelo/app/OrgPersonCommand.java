package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Withdraws or replaces a person's card. */
@Command(
        name = "person",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Withdraws or replaces a person's card.",
        subcommands = {OrgPersonCommand.Revoke.class, OrgPersonCommand.Replace.class})
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
}
