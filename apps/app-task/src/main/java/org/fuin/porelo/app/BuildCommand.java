package org.fuin.porelo.app;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** The commands that ask what a project's forge built. */
@Command(
        name = "build",
        mixinStandardHelpOptions = true,
        description = "Asks a project's forge what a branch or a commit built.",
        subcommands = BuildStatusCommand.class)
public class BuildCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;

    /**
     * Shows the available build commands.
     *
     * @return An invalid-input exit code when no verb was given.
     */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return 64;
    }
}
