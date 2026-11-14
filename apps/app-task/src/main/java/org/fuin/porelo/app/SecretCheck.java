package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.SecretScan;
import org.jspecify.annotations.Nullable;

/**
 * The secret scan of a start: each tree a task will read is scanned in its mirror before the
 * container exists, each finding nobody acknowledged is said with its place and kind, and the
 * project's {@code secrets} decides whether the start goes on.
 *
 * <p>Nothing of what was found is printed: a line names a place, never its text.
 */
final class SecretCheck {

    /** The column a start's lines are written in. */
    private static final String LABEL = "secret         ";

    private static final String INDENT = "               ";

    private final CommandRunner runner;
    private final SecretsKnown known;
    private final Project project;
    private final PrintWriter out;
    private final PrintWriter err;
    private boolean refused;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param project The project, whose {@code secrets} decides.
     * @param out Where findings are said.
     * @param err Where a refusal or a scan that could not run is said.
     */
    SecretCheck(PoreloContext context, Project project, PrintWriter out, PrintWriter err) {
        this(context.runner(), SecretsKnown.of(context, project.name()), project, out, err);
    }

    SecretCheck(
            CommandRunner runner,
            SecretsKnown known,
            Project project,
            PrintWriter out,
            PrintWriter err) {
        this.runner = runner;
        this.known = known;
        this.project = project;
        this.out = out;
        this.err = err;
    }

    /**
     * Scans one repository's tree at the revision the task gets.
     *
     * @param repository The repository's name.
     * @param mirror Its bare mirror.
     * @param revision What the task checks out.
     * @param branch The branch it starts from, named in the command a finding is acknowledged with;
     *     {@code null} for the one a task starts from by default.
     * @return {@code false} when the start must end here.
     */
    boolean check(String repository, Path mirror, String revision, @Nullable String branch) {
        final List<SecretScan.Finding> found;
        try {
            found = SecretScan.scan(runner, mirror, revision);
        } catch (RuntimeException ex) {
            // A timeout or a git that could not start is no clean tree either.
            err.println("porelo: " + ex.getMessage());
            return settle(true, "could not be scanned for credentials");
        }
        List<SecretScan.Finding> unknown;
        try {
            unknown = known.unknown(repository, found);
        } catch (IOException ex) {
            err.println(
                    "porelo: the acknowledged findings could not be read, so every finding is said: "
                            + ex.getMessage());
            unknown = found;
        }
        final boolean own = repository.equals(project.name());
        for (final SecretScan.Finding finding : unknown) {
            out.println(LABEL + (own ? "" : repository + "/") + finding.said());
            out.println(
                    INDENT
                            + "if it is none: porelo project secret-known "
                            + project.name()
                            + " "
                            + finding.place()
                            + (own ? "" : " --repository " + repository)
                            + (branch == null ? "" : " --from " + branch));
        }
        out.flush();
        return settle(!unknown.isEmpty(), "holds what looks like a credential");
    }

    private boolean settle(boolean anything, String why) {
        if (anything && project.refusesSecrets()) {
            refused = true;
            err.println(
                    "porelo: nothing was created: project '"
                            + project.name()
                            + "' refuses a start whose workspace "
                            + why
                            + " (secrets: refuse)");
            err.flush();
            return false;
        }
        err.flush();
        return true;
    }

    /**
     * Says whether a check ended the start.
     *
     * @return {@code true} once one did.
     */
    boolean refused() {
        return refused;
    }
}
