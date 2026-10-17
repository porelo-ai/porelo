package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo daemon} command group. */
@Command(
        name = "daemon",
        mixinStandardHelpOptions = true,
        description = "Reaches the daemon on this machine.",
        subcommands = {DaemonConnectCommand.class})
public class DaemonCommand {}
