package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo card} command group: a person's card and its check, a host's card and its. */
@Command(
        name = "card",
        mixinStandardHelpOptions = true,
        description = "Checks a person's card; makes and checks a host's.",
        subcommands = {
            CardVerifyCommand.class,
            CardMakeHostCommand.class,
            CardVerifyHostCommand.class
        })
public class CardCommand {}
