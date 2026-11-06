package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.RepositoryFileJudge;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * Whether a repository's own {@code porelo.yml} in an organization's project may be taken: every
 * commit that changed it since the last one this machine took is signed by a project admin or a
 * developer, valid at the commit's date; before any was taken, its newest change.
 *
 * <p><strong>Nothing here can allow an unsigned change.</strong> A person signs with a commit of
 * their own; this machine only remembers how far it has judged, so a commit judged once is not
 * judged again and a history rewritten under it is refused.
 */
public final class RepositorySettingsSignature {

    /** A name a file may carry: no separator, no leading dot. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /** A commit as git names it in full. */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private RepositorySettingsSignature() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** A change to the file that nobody who may sign it signed, or a history rewritten under it. */
    public static final class Unsigned extends ProjectException {

        private static final long serialVersionUID = 1L;

        private final String file;

        private final String commit;

        /**
         * Constructor.
         *
         * @param file The file, relative to the repository.
         * @param commit The commit that changed it unsigned, or "" for a rewritten history.
         * @param message Why, for a person.
         */
        public Unsigned(final String file, final String commit, final String message) {
            super(message);
            this.file = file;
            this.commit = commit;
        }

        /**
         * Returns the file, relative to the repository.
         *
         * @return It.
         */
        public String file() {
            return file;
        }

        /**
         * Returns the commit that changed it unsigned.
         *
         * @return Its full hash, or "" for a rewritten history.
         */
        public String commit() {
            return commit;
        }
    }

    /**
     * Judges the file at a mirror's default branch before a start reads it, and remembers how far
     * it was judged when it may be taken. A project this machine did not take from an organization
     * is not judged here.
     *
     * @param context This machine.
     * @param project The project's name.
     * @param repository The repository's name in it.
     * @param mirror The repository's mirror.
     * @param branch Its default branch.
     * @throws Unsigned When a change to the file may not be taken.
     * @throws ProjectException When the history cannot be read or judged.
     */
    public static void check(
            final PoreloContext context,
            final String project,
            final String repository,
            final Path mirror,
            final String branch) {
        final OrganizationProjects.Placed placed;
        try {
            placed = OrganizationProjects.of(context).find(project);
        } catch (IOException ex) {
            throw new ProjectException("Cannot read what this machine took of " + project, ex);
        }
        if (placed == null) {
            return;
        }
        final List<OrganizationQuestions.SigningKey> keys =
                placed.keys().stream()
                        .map(
                                key ->
                                        new OrganizationQuestions.SigningKey(
                                                key.login(),
                                                key.role(),
                                                key.key(),
                                                key.validFrom(),
                                                key.validUntil(),
                                                key.revokedSince()))
                        .toList();
        final Path taken = takenFile(context, project, repository);
        final RepositoryFileJudge.Verdict verdict;
        try {
            verdict =
                    RepositoryFileJudge.judgeStanding(
                            mirror,
                            context.runner(),
                            branch,
                            ImageChoice.FILE,
                            lastTaken(taken),
                            keys);
        } catch (IOException ex) {
            throw new ProjectException(
                    "Cannot judge the signatures of "
                            + ImageChoice.FILE
                            + " of "
                            + repository
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
        switch (verdict.state()) {
            case RepositoryFileJudge.UNSIGNED -> {
                final String commit = verdict.commit() == null ? "" : verdict.commit();
                throw new Unsigned(
                        ImageChoice.FILE,
                        commit,
                        ImageChoice.FILE
                                + " of "
                                + repository
                                + " was changed in "
                                + commit
                                + (verdict.signer().isEmpty()
                                        ? " without a signature"
                                        : " under the key " + verdict.signer())
                                + ", which is no project admin's or developer's of "
                                + project
                                + " on that day. A person who may sign it signs it anew with a"
                                + " commit of their own");
            }
            case RepositoryFileJudge.REWRITTEN ->
                    throw new Unsigned(
                            ImageChoice.FILE,
                            "",
                            "the history of "
                                    + repository
                                    + " no longer holds the change of "
                                    + ImageChoice.FILE
                                    + " this machine took last: it was rewritten. A person who"
                                    + " may sign it signs the file anew with a commit of their"
                                    + " own");
            case RepositoryFileJudge.TAKEN -> {
                if (verdict.commit() != null) {
                    remember(taken, verdict.commit());
                }
            }
            default -> {
                // No file at the tip: nothing to take, and what was taken stays remembered.
            }
        }
    }

    /** Where the last commit taken of one repository's file is kept. */
    private static Path takenFile(
            final PoreloContext context, final String project, final String repository) {
        if (!NAME.matcher(project).matches() || !NAME.matcher(repository).matches()) {
            throw new ProjectException(
                    "not a name a file may carry: " + project + "/" + repository);
        }
        return context.paths()
                .xdg()
                .state()
                .resolve("organization")
                .resolve("settings-taken")
                .resolve(project)
                .resolve(repository);
    }

    /** The commit taken last; none when nothing reads, so the newest change is judged again. */
    private static @Nullable String lastTaken(final Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 256) {
                return null;
            }
            final String commit = Files.readString(file, StandardCharsets.UTF_8).strip();
            return COMMIT.matcher(commit).matches() ? commit : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void remember(final Path file, final String commit) {
        try {
            Directories.create(file.getParent());
            final Path staged =
                    Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".new");
            Files.writeString(staged, commit + "\n", StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // Not remembered: the next start judges from the newest change again, never less.
        }
    }
}
