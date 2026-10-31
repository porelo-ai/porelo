package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectSigners;
import org.jspecify.annotations.Nullable;

/**
 * The signers of each project this machine follows, as the commit in force names them: read from
 * the followed clone at that commit, never from its working tree.
 */
final class FollowedSigners {

    private FollowedSigners() {}

    /**
     * A followed project and its signers in force.
     *
     * @param project The project.
     * @param entries Its signers, backup keys among them.
     */
    record Signers(String project, List<ProjectSigners.Signer> entries) {}

    /**
     * Each verified follow with a commit in force and a list of signers there; a project whose file
     * does not read there is left out, as it names no signer this could answer.
     *
     * @param context The machine.
     * @return Them, in the order the follows are listed.
     * @throws IOException If the follows cannot be read.
     */
    static List<Signers> all(final PoreloContext context) throws IOException {
        final List<Signers> all = new ArrayList<>();
        for (final FollowedProjects.Followed followed :
                new FollowedProjects(context.paths().projects().followed()).all()) {
            final List<ProjectSigners.Signer> entries = inForce(context, followed);
            if (entries != null) {
                all.add(new Signers(followed.name(), entries));
            }
        }
        return all;
    }

    /**
     * The signers of one followed project at the commit in force.
     *
     * @param context The machine.
     * @param followed The follow.
     * @return Them, or {@code null} for an unverified follow, one with nothing in force, a file
     *     naming none or one that does not read.
     */
    static @Nullable List<ProjectSigners.Signer> inForce(
            final PoreloContext context, final FollowedProjects.Followed followed) {
        if (followed.unverified() || !followed.commit().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            return null;
        }
        final CommandResult shown =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        context.paths()
                                                .projects()
                                                .followedClone(followed.name())
                                                .toString(),
                                        "show",
                                        followed.commit() + ":project.yml"));
        if (!shown.successful()) {
            return null;
        }
        try {
            return ProjectSigners.entries(shown.standardOutput(), "project.yml");
        } catch (ProjectException ex) {
            return null;
        }
    }
}
