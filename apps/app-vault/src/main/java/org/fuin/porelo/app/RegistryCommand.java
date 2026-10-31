package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** Checks the registry brought to a host without consulting a forge or a vault. */
@Command(
        name = "registry",
        mixinStandardHelpOptions = true,
        description = "Checks a registry brought as a Git bundle.",
        subcommands = RegistryVerifyCommand.class)
public class RegistryCommand {}
