package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/** The {@code porelo commit} command group: what a host's forge asks of the commits it receives. */
@Command(
        name = "commit",
        mixinStandardHelpOptions = true,
        description = "Judges the commits a push brings to a host's forge.",
        subcommands = {CommitVerifyCommand.class})
public class CommitCommand {}
