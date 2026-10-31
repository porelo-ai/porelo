package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;
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
     * Makes a project's room on its server, as the project admin's machine, and records that this
     * account made it: only the account that made the room keeps its machines to the list.
     *
     * @param context The machine.
     * @param project The project.
     * @return {@code conversation}, {@code room} and {@code machine}, as the transport said them.
     * @throws Conversations.Refused For an offline project, when the transport does not take it, or
     *     when it refuses.
     */
    public static Map<String, String> createRoom(PoreloContext context, Project project)
            throws Conversations.Refused {
        refuseOffline(project, "have a room on a server elsewhere");
        final String scheme = scheme(project);
        try {
            final TransportLifecycle.Conversation made =
                    lifecycle(context)
                            .createRoom(
                                    scheme,
                                    project.name(),
                                    ProjectAccess.settings(context, project, scheme));
            final Path marker = madeRoom(context, scheme, project.name());
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, made.conversation() + "\n");
            return Map.of(
                    "conversation", made.conversation(),
                    "room", made.room(),
                    "machine", made.machine());
        } catch (TransportLifecycle.Refused ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        } catch (ProjectAccess.Withheld ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), 78, null);
        } catch (CommandException ex) {
            throw unrun(scheme, "setup", ex);
        } catch (IOException ex) {
            throw new Conversations.Refused(
                    "the room of "
                            + project.name()
                            + " was made, and that this account made it cannot be recorded: "
                            + ex.getMessage(),
                    74,
                    null);
        }
    }

    /**
     * Keeps a project's room to its admitted persons list, where this account made the room: as
     * machines, each admitted person's login on this host by its machine's name, and this machine;
     * as persons, each admitted person's login, where the transport takes them. Nothing is asked of
     * the transport anywhere else.
     *
     * @param context The machine.
     * @param project The project.
     * @return What the transport said it invited and removed, or {@code null} when this account did
     *     not make the room.
     * @throws Conversations.Refused When the list is not applied, or the transport refuses.
     */
    public static @org.jspecify.annotations.Nullable Map<?, ?> keepMembers(
            PoreloContext context, Project project) throws Conversations.Refused {
        if (project.securityClass() == SecurityClass.OFFLINE
                || project.mail().conversations().isEmpty()
                || project.access() == null) {
            return null;
        }
        final String scheme = scheme(project);
        if (!Files.isRegularFile(madeRoom(context, scheme, project.name()))) {
            return null;
        }
        try {
            java.util.Set<String> persons;
            try {
                persons = ProjectAccess.admitted(context, project);
            } catch (final ProjectAccess.Withheld ex) {
                // A room whose settings remain readable must remove persons it cannot admit.
                persons = java.util.Set.of();
            }
            final TreeSet<String> machines = new TreeSet<>();
            machines.add(context.machineName().get());
            for (final String account : persons) {
                machines.add(MachineName.of(MachineName.hostName(), account));
            }
            return lifecycle(context)
                    .members(
                            scheme,
                            project.name(),
                            new ArrayList<>(machines),
                            new ArrayList<>(new TreeSet<>(persons)),
                            ProjectAccess.settings(context, project, scheme));
        } catch (TransportLifecycle.Refused ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        } catch (ProjectAccess.Withheld ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), 78, null);
        } catch (IllegalArgumentException ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), 65, null);
        } catch (CommandException ex) {
            throw unrun(scheme, "members", ex);
        }
    }

    /** Where it is recorded that this account made a project's room. */
    private static Path madeRoom(PoreloContext context, String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve(project + ".room");
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
