package org.fuin.porelo.app;

import java.util.List;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;

/**
 * A runner that answers podman as a podman holding nothing would, and runs everything else for
 * real.
 *
 * <p><strong>For a test that asks which tasks or images exist only to hear "none".</strong> Run for
 * real, the question failed where podman is not installed, as in a Porelo task, and answered with
 * the developer's own containers and images where it is, so the test depended on what the machine
 * ran. A listing answers empty; anything about one object answers 125, podman's own "no such".
 */
final class NoTasks {

    /** What {@code podman} says of an object it does not have. */
    static final int NO_SUCH = 125;

    private NoTasks() {}

    /**
     * Returns a runner that knows no container, image or volume and passes every other command on.
     *
     * @param real What runs the other commands, git among them.
     * @return The runner.
     */
    static CommandRunner over(final CommandRunner real) {
        return command -> {
            final List<String> arguments = command.arguments();
            if (arguments.isEmpty() || !arguments.get(0).endsWith("podman")) {
                return real.run(command);
            }
            return listing(arguments)
                    ? new CommandResult(command, 0, "", "")
                    : new CommandResult(command, NO_SUCH, "", "Error: no such object");
        };
    }

    /**
     * Tells whether a podman command lists what there is, rather than asking about one thing.
     *
     * @param arguments The command, the program first.
     * @return {@code true} for {@code ps}, {@code images} and any {@code ... ls}.
     */
    static boolean listing(final List<String> arguments) {
        if (arguments.size() < 2) {
            return false;
        }
        final String verb = arguments.get(1);
        return verb.equals("ps")
                || verb.equals("images")
                || (arguments.size() > 2 && arguments.get(2).equals("ls"));
    }
}
