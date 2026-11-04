package org.fuin.porelo.app;

import picocli.CommandLine.Command;

/**
 * The name {@link ProjectCreateRepositoryCommand} had, kept for a release: an interface and a
 * Porelo of different builds meet.
 */
@Command(
        name = "open",
        hidden = true,
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Creates the project's Git repository, as create-repository does.")
public class ProjectOpenCommand extends ProjectCreateRepositoryCommand {}
