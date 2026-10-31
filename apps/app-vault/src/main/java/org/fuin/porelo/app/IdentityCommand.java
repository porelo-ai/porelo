package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/**
 * The {@code porelo identity} command group: the person's own key and card, made, shown, changed
 * and replaced.
 */
@Command(
        name = "identity",
        mixinStandardHelpOptions = true,
        description = "Makes, shows, changes and replaces your own key and card.",
        subcommands = {
            IdentityShowCommand.class,
            IdentityKeysCommand.class,
            IdentityCreateCommand.class,
            IdentityRotateCommand.class,
            IdentityRevokeCommand.class,
            IdentityPublishCommand.class,
            IdentityStatusCommand.class,
            IdentityBackupKeyCommand.class
        })
public class IdentityCommand {}
