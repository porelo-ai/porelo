package org.fuin.porelo.app;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.project.Project;

/**
 * Who a project of the organization has, and what it says to a transport.
 *
 * <p>The people are the project's signing keys, as the last start placed them or as a host says
 * them now. A locked member and a person with no accepted card have no row there, so a current row
 * is what being admitted means. A project of this machine alone has nobody listed and no check.
 */
public final class ProjectMembers {

    /** The transport a host's homeserver is for. */
    private static final String MATRIX = "matrix";

    private static final String HOMESERVER = "homeserver";

    /** Why a transport gets no settings, or a person is not let in. */
    public static final class Withheld extends Exception {

        private static final long serialVersionUID = 1L;

        Withheld(final String message) {
            super(message);
        }
    }

    private ProjectMembers() {}

    /**
     * Returns what a project says to one transport: its {@code project.yml}, and for a project of
     * the organization that names no homeserver, the host's.
     *
     * @param context The machine.
     * @param project The project.
     * @param scheme The transport.
     * @return The settings.
     * @throws Withheld For a project of the organization with a room and no homeserver from either
     *     place: empty settings would start the account's own homeserver.
     */
    public static Map<String, Object> settings(
            final PoreloContext context, final Project project, final String scheme)
            throws Withheld {
        final Map<String, Object> settings = new LinkedHashMap<>();
        if (project.mail().transports().get(scheme) instanceof Map<?, ?> declared) {
            declared.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        if (!MATRIX.equals(scheme) || settings.containsKey(HOMESERVER)) {
            return settings;
        }
        final OrganizationProjects.Placed placed;
        try {
            placed = OrganizationProjects.of(context).find(project.name());
        } catch (final IOException ex) {
            throw new Withheld(
                    "what is placed of " + project.name() + " does not read: " + ex.getMessage());
        }
        if (placed == null) {
            return settings;
        }
        if (placed.homeserver() == null) {
            throw new Withheld(
                    project.name()
                            + " names no homeserver under 'mail.transports."
                            + MATRIX
                            + "', and this machine is no host running one");
        }
        settings.put(HOMESERVER, placed.homeserver());
        if (placed.room() != null) {
            settings.put("room", placed.room());
        }
        return settings;
    }

    /**
     * Returns the logins with a current key in a project.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return Them; none for a project of this machine alone.
     * @throws Withheld When what is placed of it does not read.
     */
    public static Set<String> admitted(final PoreloContext context, final String project)
            throws Withheld {
        final Set<String> admitted = new LinkedHashSet<>();
        for (final OrganizationProjects.Key key : current(context, project)) {
            admitted.add(key.login());
        }
        return admitted;
    }

    /**
     * Tells whether a project is the organization's, as a start placed it here.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return {@code true} when a start placed it.
     * @throws Withheld When what is placed of it does not read.
     */
    public static boolean listed(final PoreloContext context, final String project)
            throws Withheld {
        return placed(context, project) != null;
    }

    /**
     * Refuses a login before any account is made or reset unless the project has them.
     *
     * @param context The machine.
     * @param project The project's name.
     * @param login The person asking to join.
     * @throws Withheld When a project of the organization has no current key of theirs.
     */
    public static void requireAdmitted(
            final PoreloContext context, final String project, final String login) throws Withheld {
        if (listed(context, project) && !admitted(context, project).contains(login)) {
            throw new Withheld("'" + login + "' is not admitted to " + project);
        }
    }

    /**
     * Returns the keys allowed to certify a project's tasks.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return Its current keys; none for a project of this machine alone, or one that does not
     *     read.
     */
    public static List<String> certifiers(final PoreloContext context, final String project) {
        final List<String> keys = new ArrayList<>();
        try {
            for (final OrganizationProjects.Key key : current(context, project)) {
                keys.add(key.key());
            }
        } catch (final Withheld ex) {
            return List.of();
        }
        return List.copyOf(keys);
    }

    private static List<OrganizationProjects.Key> current(
            final PoreloContext context, final String project) throws Withheld {
        final OrganizationProjects.Placed placed = placed(context, project);
        return placed == null ? List.of() : placed.holding(LocalDate.now(ZoneOffset.UTC));
    }

    private static OrganizationProjects.@org.jspecify.annotations.Nullable Placed placed(
            final PoreloContext context, final String project) throws Withheld {
        try {
            return OrganizationProjects.of(context).find(project);
        } catch (final IOException ex) {
            throw new Withheld(
                    "what is placed of " + project + " does not read: " + ex.getMessage());
        }
    }
}
