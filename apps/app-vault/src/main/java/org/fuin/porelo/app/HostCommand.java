package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo host} command group. */
@Command(
        name = "host",
        mixinStandardHelpOptions = true,
        description = "Opens a host vault with a key on this machine.",
        subcommands = HostUnlockCommand.class)
public class HostCommand {}
