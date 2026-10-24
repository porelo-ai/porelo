package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * What this machine connects out with, at the terminal.
 *
 * <p>The same records an interface writes over the socket, because a person with only a terminal
 * must be able to do whatever an interface can - the rule that brought {@code project unfollow}
 * back from being socket-only.
 *
 * <p>The <em>values</em> are still {@code vault put}'s business, or nobody's: a key in {@code
 * ~/.ssh} and a token in the environment are declared here and stay where they are.
 */
@Command(
        name = "credentials",
        aliases = {"credential"},
        mixinStandardHelpOptions = true,
        subcommands = {
            CredentialsListCommand.class,
            CredentialsDeclareCommand.class,
            CredentialsForgetCommand.class,
            CredentialsCheckCommand.class,
            CredentialsKeysCommand.class,
            CredentialsTrustHostCommand.class
        },
        description = "Says what this machine connects out with, and records more.")
public class CredentialsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        return new CredentialsListCommand().render(context, out);
    }
}
