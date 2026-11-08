package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Where a host reaches a project's repositories: on its own forge, whatever the file says, so that
 * a task's work never reaches a central address past the host's push judge and nobody refiles a
 * project that was filled before it came to a machine.
 *
 * <p>The host's follow takes a repository with a {@code central} onto its forge under the last part
 * of that address, in an organization named after the project. A row with none has no copy there,
 * and a task on it is refused.
 */
public final class HostForgeAddress {

    /** A name on the host's forge: no leading dot or dash, so never {@code ..} or an option. */
    private static final String SEGMENT = "[A-Za-z0-9_][A-Za-z0-9._-]*";

    private final Path forgeConf;

    private final Path hostFiles;

    /** Has every project read in this process reached as this machine reaches it. */
    public static void install() {
        org.fuin.porelo.core.project.ProjectReader.placedBy(new HostForgeAddress()::placed);
    }

    /** Constructor for this machine. */
    public HostForgeAddress() {
        this(HostForgeLending.FORGE_CONF);
    }

    /**
     * Constructor.
     *
     * @param forgeConf Where the host names its forge's port.
     */
    HostForgeAddress(final Path forgeConf) {
        this(forgeConf, OrganizationProjects.HOST_FILES);
    }

    /**
     * Constructor.
     *
     * @param forgeConf Where the host names its forge's port.
     * @param hostFiles Where the host's files of its projects are.
     */
    HostForgeAddress(final Path forgeConf, final Path hostFiles) {
        this.forgeConf = forgeConf;
        this.hostFiles = hostFiles;
    }

    /**
     * Returns a project as this machine reaches it.
     *
     * @param project The project as its file says.
     * @return It, with each row that has a copy on the host's forge at that copy's address.
     */
    public Project placed(final Project project) {
        final String port = port(forgeConf);
        if (port == null) {
            return project;
        }
        final List<Repository> rows = new ArrayList<>();
        for (final Repository row : project.repositories()) {
            final String address = address(project.name(), row.central(), port);
            rows.add(address == null ? row : row.reachedAt(address));
        }
        return project.withRepositories(rows);
    }

    /**
     * Says why a task may not work on a repository on this machine.
     *
     * @param project The project.
     * @param repository The repository's name, or {@code null}.
     * @param upstream The address named for this start, or {@code null}.
     * @return Why not, or {@code null} when it may.
     */
    public @Nullable String refusal(
            final Project project,
            final @Nullable String repository,
            final @Nullable String upstream) {
        final String port = port(forgeConf);
        if (port == null || repository == null) {
            return null;
        }
        final Repository row =
                project.repositories().stream()
                        .filter(each -> each.name().equals(repository))
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            return null;
        }
        final String uncarried = uncarried(project, repository);
        if (uncarried != null) {
            return uncarried;
        }
        final String address = address(project.name(), row.central(), port);
        if (address == null) {
            return "repository '"
                    + repository
                    + "' names no central address, so this host has no copy of it and a task's work"
                    + " would go past its push judge. Add 'central:' to its row in project.yml";
        }
        if (upstream != null && !upstream.isBlank() && !upstream.strip().equals(address)) {
            return "on this host a task's work goes through its own forge, "
                    + address
                    + ", and --upstream names another address";
        }
        return null;
    }

    /**
     * Says why this host does not carry a repository of a project, as its file names them: work on
     * one it does not carry has no copy on its forge to go to.
     */
    private @Nullable String uncarried(final Project project, final String repository) {
        final OrganizationProjects.Placed host;
        try {
            host = OrganizationProjects.host(hostFiles, project.name());
        } catch (final IOException ex) {
            return "this host's file of project '"
                    + project.name()
                    + "' does not read, so it cannot say whether it carries '"
                    + repository
                    + "'";
        }
        if (host == null) {
            // No file this person may read: none written, or root took the read off a locked
            // member. Either way the forge keeps no copy of it for them.
            return "this host carries no project '"
                    + project.name()
                    + "' for you, so its forge keeps no copy of '"
                    + repository
                    + "'";
        }
        if (host.repositories().isEmpty() || host.repositories().contains(repository)) {
            return null;
        }
        return "this host does not carry repository '"
                + repository
                + "' of '"
                + project.name()
                + "'; it carries "
                + String.join(", ", host.repositories());
    }

    private static @Nullable String address(
            final String project, final @Nullable String central, final String port) {
        final String copy = copyOf(project, central);
        return copy == null ? null : "http://127.0.0.1:" + port + "/" + copy + ".git";
    }

    /**
     * Returns the copy a host's forge keeps of a repository, {@code <project>/<name>}: the name is
     * the last part of its central address (or of the address it was placed at, which ends the
     * same), as the host's follow names the copy it makes.
     *
     * @param project The project's name.
     * @param central The repository's central address, or {@code null}.
     * @return The copy, or {@code null} when the address names no usable repository.
     */
    public static @Nullable String copyOf(final String project, final @Nullable String central) {
        if (central == null) {
            return null;
        }
        final String stripped = central.strip().replaceAll("/+$", "").replaceAll("\\.git$", "");
        final String name =
                stripped.substring(
                        Math.max(stripped.lastIndexOf('/'), stripped.lastIndexOf(':')) + 1);
        return name.matches(SEGMENT) ? project + "/" + name : null;
    }

    /**
     * Reads the forge's git port.
     *
     * @param forgeConf The host's file.
     * @return It, or {@code null} on a machine that is no host.
     */
    static @Nullable String port(final Path forgeConf) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(forgeConf);
        } catch (IOException ex) {
            return null;
        }
        for (final String line : lines) {
            if (line.startsWith("git_port=")) {
                final String value = line.substring("git_port=".length()).strip();
                return value.matches("[1-9][0-9]{0,4}") ? value : null;
            }
        }
        return null;
    }
}
