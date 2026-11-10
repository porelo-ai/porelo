package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** Brings central main to the host for a task to reconcile. */
@Command(
        name = "pull",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Brings central main to local central/main using the vault.")
public final class HostGatherPullCommand extends HostGatherCommand {
    @Override
    boolean pulling() {
        return true;
    }
}
