package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo service} command group. */
@Command(
        name = "service",
        mixinStandardHelpOptions = true,
        description = "Reaches a service this host serves, by its role.",
        subcommands = {ServiceConnectCommand.class})
public class ServiceCommand {}
