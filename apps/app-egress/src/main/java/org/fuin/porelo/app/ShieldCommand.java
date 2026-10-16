package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/**
 * The {@code porelo shield} command group.
 */
@Command(name = "shield",
        mixinStandardHelpOptions = true,
        description = "Inspects and changes what a task may reach.",
        subcommands = { ShieldReadCommand.class, ShieldWatchCommand.class,
                ShieldDnsCommand.class, ShieldSubscribeCommand.class,
                ShieldSetsCommand.class, ShieldEgressCommand.class })
public class ShieldCommand {

}
