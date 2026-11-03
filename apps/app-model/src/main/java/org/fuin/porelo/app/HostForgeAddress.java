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
        this.forgeConf = forgeConf;
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

    private static @Nullable String address(
            final String project, final @Nullable String central, final String port) {
        if (central == null) {
            return null;
        }
        final String stripped = central.strip().replaceAll("/+$", "").replaceAll("\\.git$", "");
        final String name =
                stripped.substring(
                        Math.max(stripped.lastIndexOf('/'), stripped.lastIndexOf(':')) + 1);
        return name.matches(SEGMENT)
                ? "http://127.0.0.1:" + port + "/" + project + "/" + name + ".git"
                : null;
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
