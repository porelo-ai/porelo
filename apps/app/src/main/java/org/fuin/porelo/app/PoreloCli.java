package org.fuin.porelo.app;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Entry point of the {@code porelo} binary.
 *
 * <p>Picocli instantiates the command object, so this is a normal class rather than a utility
 * class.
 */
@Command(
        name = "porelo",
        mixinStandardHelpOptions = true,
        versionProvider = PoreloVersion.class,
        description = "Runs AI agent tasks inside hardened, rootless containers.",
        subcommands = {
            TaskCommand.class,
            BuildCommand.class,
            ShieldCommand.class,
            VaultCommand.class,
            CredentialsCommand.class,
            GateCommand.class,
            AgentsCommand.class,
            ProvidersCommand.class,
            TalkCommand.class,
            SetupCommand.class,
            DoctorCommand.class,
            PruneCommand.class,
            PanicCommand.class,
            DaemonCommand.class,
            ProjectsCommand.class,
            ClearCommand.class,
            CompletionCommand.class,
            CompleteCommand.class,
            ApproveCommand.class
        })
public class PoreloCli implements Callable<Integer> {

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        // Invoked without a subcommand. Usage on stderr and a non-zero exit, so that a script that
        // forgets the subcommand fails instead of looking successful.
        spec.commandLine().usage(spec.commandLine().getErr());
        return 2;
    }

    /**
     * Returns the command line, wired the way the binary wires it.
     *
     * <p><strong>One place, because a test that builds its own is testing something else.</strong>
     * The parameter exception handler is most of what somebody sees when they get a command wrong,
     * and it used to be installed only here in {@code main} - so every test that constructed a
     * {@code CommandLine} of its own exercised picocli's default messages and nothing could tell
     * that Porelo's own were missing.
     *
     * @param context What the commands run against.
     * @return The command line.
     */
    public static CommandLine commandLine(PoreloContext context) {
        final CommandLine line = new CommandLine(new PoreloCli(), new PoreloFactory(context));
        // Added here: clearing the vault reaches transports and tasks, which the vault's area does
        // not depend on.
        java.util.Objects.requireNonNull(
                        line.getSubcommands().get("vault"), "the vault group is named above")
                .addSubcommand(VaultClearCommand.class);
        return line.setParameterExceptionHandler(CliErrors.handler())
                .setExecutionExceptionHandler(CliErrors.failures(context.paths()));
    }

    /**
     * Runs the command line.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        quietLogging();
        try {
            System.exit(commandLine(PoreloContext.real()).execute(args));
        } catch (final Error ex) {
            // Anything else is rethrown untouched.
            if (!missingWorkingDirectory(ex)) {
                throw ex;
            }
            System.err.println(
                    "porelo: the current directory no longer exists - cd to one that "
                            + "does. Nothing was read or written.");
            System.exit(2);
        }
    }

    /**
     * Keeps the logging facade a library brings in from warning that nothing is bound to it.
     *
     * <p>The D-Bus client of the clearance watcher logs through SLF4J, and nothing here binds it:
     * nobody reads what it would log. Unsilenced, every {@code clearance.log} began with three
     * warnings about a missing provider, and a log that opens with warnings teaches a person to
     * skip its first lines (found on 2026-10-13). SLF4J's own switch, set before anything logs; a
     * value somebody set is left alone.
     */
    static void quietLogging() {
        if (System.getProperty("slf4j.internal.verbosity") == null) {
            System.setProperty("slf4j.internal.verbosity", "ERROR");
        }
    }

    /**
     * Says whether a failure is the shell sitting in a deleted directory.
     *
     * <p>Caught rather than checked for: the check would itself be the file operation that fails.
     *
     * @param error The error that escaped.
     * @return {@code true} when the working directory is gone.
     */
    static boolean missingWorkingDirectory(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            final String message = cause.getMessage();
            if (message != null && message.contains("current working directory")) {
                return true;
            }
        }
        return false;
    }
}
