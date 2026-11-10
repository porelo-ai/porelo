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
            HostFollowCommand.class,
            HostCentralStateCommand.class,
            HostStopSecretsCommand.class,
            HostGatherPushCommand.class,
            HostGatherPullCommand.class
        })
public class HostCommand {}
