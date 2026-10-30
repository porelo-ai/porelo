package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo card} command group: a person's card and its check. */
@Command(
        name = "card",
        mixinStandardHelpOptions = true,
        description = "Checks a person's card.",
        subcommands = {CardVerifyCommand.class})
public class CardCommand {}
