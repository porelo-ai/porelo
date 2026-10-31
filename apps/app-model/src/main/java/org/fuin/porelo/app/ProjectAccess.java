package org.fuin.porelo.app;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;

/**
 * The one place a transport's settings for a project are taken from: the project file's, or for a
 * project that names an access file, the applied access file's and nothing else.
 *
 * <p>Every lifecycle verb is handed what this returns, so a project naming {@code access:} never
 * reaches a transport with empty settings - which would set up the account's own homeserver - nor
 * with settings from a file nobody verified.
 */
public final class ProjectAccess {

    /** Why a transport may not be run for a project yet, in a person's words. */
    public static final class Withheld extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message Why, naming what to do.
         */
        public Withheld(final String message) {
            super(message);
        }
    }

    private ProjectAccess() {}

    /**
     * Returns what a project says to one transport.
     *
     * @param context The machine.
     * @param project The project.
     * @param scheme The transport.
     * @return The settings, as written; empty only for a project with no access file that names
     *     none.
     * @throws Withheld For a project naming an access file that is not applied, cannot be read, or
     *     names nothing for this transport.
     */
    public static Map<String, Object> settings(
            final PoreloContext context, final Project project, final String scheme)
            throws Withheld {
        final Object said =
                project.access() == null
                        ? project.mail().transports().get(scheme)
                        : applied(context, project).transports().get(scheme);
        // An empty mapping says as little as none: it too would set up the account's own server.
        if (project.access() != null && !(said instanceof Map<?, ?> named && !named.isEmpty())) {
            throw new Withheld(
                    "the access file of "
                            + project.name()
                            + " names no settings for the "
                            + scheme
                            + " transport; add them under 'transports."
                            + scheme
                            + "' in "
                            + Access.FILE);
        }
        final Map<String, Object> settings = new LinkedHashMap<>();
        if (said instanceof Map<?, ?> declared) {
            declared.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        return settings;
    }

    /**
     * Returns the access file in force for a project that names one.
     *
     * @param context The machine.
     * @param project A project whose {@link Project#access()} is not {@code null}.
     * @return What it says.
     * @throws Withheld When none is applied, or the applied one cannot be read.
     */
    public static AccessFile applied(final PoreloContext context, final Project project)
            throws Withheld {
        final Access access =
                java.util.Objects.requireNonNull(
                        project.access(), "only a project naming an access file has one applied");
        final FollowedProjects.Followed followed;
        try {
            followed =
                    new FollowedProjects(context.paths().projects().followed())
                            .find(project.name());
        } catch (final IOException ex) {
            throw new Withheld(
                    "cannot tell whether the access file of "
                            + project.name()
                            + " is applied: "
                            + ex.getMessage());
        }
        final Path file;
        if (followed == null) {
            if (access instanceof Access.InRepository repository) {
                throw new Withheld(
                        project.name()
                                + " is not followed, so its access file in "
                                + repository.url()
                                + " was never fetched or verified; follow the project with 'porelo"
                                + " project follow'");
            }
            // A project file of this machine's own is no more verified than the file beside it.
            try {
                file = ProjectSource.require(context, project.name()).resolveSibling(Access.FILE);
            } catch (final ProjectException ex) {
                throw new Withheld(
                        "the access file of "
                                + project.name()
                                + " cannot be found: "
                                + ex.getMessage());
            }
        } else {
            refuseUnapplied(project, followed);
            return inForce(context, project, access, followed.accessCommit());
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return AccessFile.read(reader, file.toString());
        } catch (final IOException | ProjectException ex) {
            throw new Withheld(
                    "the access file of " + project.name() + " cannot be read: " + ex.getMessage());
        }
    }

    /**
     * Resolves persons only from the access commit in force, never from local card files.
     *
     * @param context The machine.
     * @param project The project naming an access file.
     * @return Its persons, including locked and revoked persons.
     * @throws Withheld When no verified access commit is in force.
     */
    public static java.util.List<org.fuin.porelo.vault.AccessPersons.Person> persons(
            final PoreloContext context, final Project project) throws Withheld {
        final FollowedProjects.Followed followed;
        try {
            followed =
                    new FollowedProjects(context.paths().projects().followed())
                            .find(project.name());
        } catch (final IOException ex) {
            throw new Withheld(
                    "cannot read the access commit of " + project.name() + ": " + ex.getMessage());
        }
        if (followed == null) {
            throw new Withheld(
                    "the persons of "
                            + project.name()
                            + " have no verified access commit; follow the project with 'porelo project follow'");
        }
        if (followed.unverified()) {
            return java.util.List.of();
        }
        if (followed.commit().isEmpty()) {
            throw new Withheld(
                    "the persons of " + project.name() + " have no verified project commit");
        }
        refuseUnapplied(project, followed);
        final AccessFile access =
                inForce(
                        context,
                        project,
                        java.util.Objects.requireNonNull(
                                project.access(),
                                "only a project naming an access file has persons"),
                        followed.accessCommit());
        final Path clone =
                project.access() instanceof Access.InRepository
                        ? context.paths().projects().followedAccessClone(project.name())
                        : context.paths().projects().followedClone(project.name());
        return org.fuin.porelo.vault.AccessPersons.read(
                context.runner(), clone, followed.accessCommit(), access);
    }

    /**
     * Returns logins admitted by their cards and project locks, for room membership and words.
     *
     * @param context The machine.
     * @param project The project naming an access file.
     * @return Admitted logins in access-file order.
     * @throws Withheld When no verified access commit is in force.
     */
    public static java.util.Set<String> admitted(final PoreloContext context, final Project project)
            throws Withheld {
        final java.util.Set<String> admitted = new java.util.LinkedHashSet<>();
        for (final org.fuin.porelo.vault.AccessPersons.Person person : persons(context, project)) {
            if (person.admitted()) {
                admitted.add(person.login());
            }
        }
        return java.util.Collections.unmodifiableSet(admitted);
    }

    /**
     * Refuses a login before any account is created or reset unless the access file admits it.
     *
     * @param context The machine.
     * @param project The project, with or without an access file.
     * @param login The person asking to join.
     * @throws Withheld When the project does not admit the login now.
     */
    public static void requireAdmitted(
            final PoreloContext context, final Project project, final String login)
            throws Withheld {
        if (project.access() == null) {
            return;
        }
        final java.util.List<org.fuin.porelo.vault.AccessPersons.Person> persons;
        try {
            persons = persons(context, project);
        } catch (final Withheld ex) {
            throw new Withheld(
                    "'"
                            + login
                            + "' is not admitted to "
                            + project.name()
                            + ": "
                            + ex.getMessage());
        }
        for (final org.fuin.porelo.vault.AccessPersons.Person person : persons) {
            if (person.login().equals(login)) {
                if (person.admitted()) {
                    return;
                }
                throw new Withheld(
                        "'"
                                + login
                                + "' is "
                                + (person.locked() ? "locked" : "revoked")
                                + " in "
                                + project.name());
            }
        }
        throw new Withheld(
                "'"
                        + login
                        + "' is not admitted to "
                        + project.name()
                        + " by its verified access file");
    }

    /**
     * Reads the access file at the commit in force, never the working tree: what lies there may be
     * a later commit that did not read as an access file, or an edit nobody signed.
     */
    private static AccessFile inForce(
            final PoreloContext context,
            final Project project,
            final Access access,
            final String commit)
            throws Withheld {
        final Path clone =
                access instanceof Access.InRepository
                        ? context.paths().projects().followedAccessClone(project.name())
                        : context.paths().projects().followedClone(project.name());
        final String at = Access.FILE + " at " + commit;
        try {
            final CommandResult shown =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            clone.toString(),
                                            "show",
                                            commit + ":" + Access.FILE));
            if (!shown.successful()) {
                throw new Withheld(
                        "the access file of "
                                + project.name()
                                + " cannot be read: "
                                + at
                                + ": "
                                + shown.standardError().strip());
            }
            return AccessFile.read(new java.io.StringReader(shown.standardOutput()), at);
        } catch (final RuntimeException ex) {
            throw new Withheld(
                    "the access file of " + project.name() + " cannot be read: " + ex.getMessage());
        }
    }

    /**
     * Refuses a followed project whose access file has no commit in force, or waits for a person.
     *
     * <p>An access file that could not be fetched again keeps the one applied before, as the
     * project file does; a rewritten history is not guessed past.
     */
    private static void refuseUnapplied(
            final Project project, final FollowedProjects.Followed followed) throws Withheld {
        if (followed.accessCommit().isEmpty()) {
            throw new Withheld(
                    "the access file of "
                            + project.name()
                            + " is not applied"
                            + (followed.accessOutcome().isEmpty()
                                    ? " yet; it is taken at the next follow"
                                    : " ("
                                            + followed.accessOutcome()
                                                    .toLowerCase(java.util.Locale.ROOT)
                                            + ")")
                            + "; see 'porelo project following'");
        }
        if ("REWRITTEN".equals(followed.accessOutcome())) {
            throw new Withheld(
                    "the access file of "
                            + project.name()
                            + " waits for a person: its history was rewritten; see 'porelo project"
                            + " following'");
        }
    }
}
