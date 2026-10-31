package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/**
 * Reads and files a registry checkout as its signers may write it, and checks the registry brought
 * to a host without consulting a forge or a vault.
 */
@Command(
        name = "registry",
        mixinStandardHelpOptions = true,
        description = "Reads, files and checks a registry.",
        subcommands = {
            RegistryReadCommand.class,
            RegistryFileCommand.class,
            RegistryVerifyCommand.class
        })
public class RegistryCommand {}
