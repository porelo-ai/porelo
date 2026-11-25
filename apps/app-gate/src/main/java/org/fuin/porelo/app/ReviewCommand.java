package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo review} command group. */
@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        description = "Says what is open in a review and how it was decided.",
        subcommands = {ReviewOpenCommand.class})
public class ReviewCommand {}
