package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The person's gathered plain push. */
@Command(
        name = "push",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Pushes approved local main to central using the vault.")
public final class HostGatherPushCommand extends HostGatherCommand {
    @Override
    boolean pulling() {
        return false;
    }
}
