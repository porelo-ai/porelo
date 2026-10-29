package org.fuin.porelo.app;

import java.util.Map;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;

/**
 * The machines this machine lets into a project's conversation on its server: one implementation,
 * which the command line and the daemon only render.
 */
public final class MachineTransports {

    private MachineTransports() {}

    /**
     * Lets another machine into a project's conversation on this machine's server.
     *
     * @param context The machine.
     * @param project The project.
     * @param machine The machine let in.
     * @return The transport's answer: {@code admission}, and {@code secrets}, which no log and no
     *     terminal may show.
     * @throws Conversations.Refused For an offline project, whose messages never leave this
     *     machine, or when the transport refuses.
     */
    public static Map<?, ?> admit(PoreloContext context, Project project, String machine)
            throws Conversations.Refused {
        refuseOffline(project, "let another machine in");
        refuseMachineName(machine);
        final String scheme = scheme(project);
        try {
            return lifecycle(context)
                    .admit(
                            scheme,
                            project.name(),
                            machine,
                            ProjectAccess.settings(context, project, scheme));
        } catch (TransportLifecycle.Refused ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        } catch (ProjectAccess.Withheld ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), 78, null);
        } catch (CommandException ex) {
            throw unrun(scheme, "admit", ex);
        }
    }

    /**
     * Takes another machine off this machine's conversation server, asked through one project.
     *
     * @param context The machine.
     * @param project The project.
     * @param machine The machine taken out.
     * @return The transport's answer: what it removed.
     * @throws Conversations.Refused When the transport refuses.
     */
    public static Map<?, ?> dismiss(PoreloContext context, Project project, String machine)
            throws Conversations.Refused {
        refuseOffline(project, "have another machine to take out");
        refuseMachineName(machine);
        final String scheme = scheme(project);
        try {
            return lifecycle(context)
                    .dismiss(
                            scheme,
                            project.name(),
                            machine,
                            ProjectAccess.settings(context, project, scheme));
        } catch (TransportLifecycle.Refused ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        } catch (ProjectAccess.Withheld ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), 78, null);
        } catch (CommandException ex) {
            throw unrun(scheme, "dismiss", ex);
        }
    }

    /**
     * A transport that could not be run - timed out or not started - is a refusal too, so a caller
     * renders it as every other; only its cause is said, which holds nothing the transport printed.
     */
    private static Conversations.Refused unrun(String scheme, String verb, CommandException ex) {
        final Throwable cause = ex.getCause();
        return new Conversations.Refused(
                "the "
                        + scheme
                        + " transport could not "
                        + verb
                        + (cause == null || cause.getMessage() == null
                                ? ""
                                : ": " + StartStep.cleanCause(cause.getMessage())),
                69,
                null);
    }

    private static void refuseMachineName(String machine) throws Conversations.Refused {
        if (!MachineName.valid(machine)) {
            throw new Conversations.Refused(
                    "'"
                            + machine.replaceAll("\\p{Cntrl}", "?")
                            + "' is not a machine's name: "
                            + MachineName.RULE,
                    64,
                    null);
        }
    }

    private static void refuseOffline(Project project, String what) throws Conversations.Refused {
        if (project.securityClass() == SecurityClass.OFFLINE) {
            throw new Conversations.Refused(
                    "'"
                            + project.name()
                            + "' is offline: its messages never leave this machine, so it cannot "
                            + what,
                    77,
                    null);
        }
    }

    private static String scheme(Project project) throws Conversations.Refused {
        if (project.mail().conversations().isEmpty()) {
            throw new Conversations.Refused(
                    "'"
                            + project.name()
                            + "' has no conversation: no peer is reached through a transport's"
                            + " '<scheme>:' address",
                    78,
                    null);
        }
        return project.mail().conversations().iterator().next();
    }

    private static TransportLifecycle lifecycle(PoreloContext context) {
        return new TransportLifecycle(context, context.paths().messaging().transportDirectory());
    }
}
