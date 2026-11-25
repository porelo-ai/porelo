package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo forge} command group. */
@Command(
        name = "forge",
        mixinStandardHelpOptions = true,
        description = "Lists and sets the forge instances this machine reads pull requests from.",
        subcommands = {ForgeAddCommand.class, ForgeCommentsCommand.class, ForgeListCommand.class})
public class ForgeCommand {}
