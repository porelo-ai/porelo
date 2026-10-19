package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo vault} command group. */
@Command(
        name = "vault",
        mixinStandardHelpOptions = true,
        description = "Manages stored credentials.",
        subcommands = {
            VaultInitCommand.class,
            VaultPutCommand.class,
            VaultImportCommand.class,
            VaultListCommand.class,
            VaultRemoveCommand.class,
            VaultServeCommand.class,
            VaultRelayCommand.class,
            VaultAgentCommand.class,
            VaultCredentialCommand.class,
            VaultUnlockCommand.class,
            VaultLockCommand.class,
            VaultStatusCommand.class,
            VaultPassphraseCommand.class,
            VaultLoginCommand.class,
            VaultAuthorizeCommand.class,
            VaultDevicesCommand.class,
            VaultRevokeCommand.class
        })
public class VaultCommand {}
