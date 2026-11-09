package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo host} command group. */
@Command(
        name = "host",
        mixinStandardHelpOptions = true,
        description = "Handles this person's vault, forge token and central state on a host.",
        subcommands = {
            HostUnlockCommand.class,
            HostForgeTokenCommand.class,
            HostCentralStateCommand.class,
            HostStopSecretsCommand.class
        })
public class HostCommand {}
