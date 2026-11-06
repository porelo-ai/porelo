package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * Judges repository settings by every change since this machine last took them, or only their
 * newest change on a first read. A signature names responsibility without reading the file's text.
 */
public final class RepositoryFileJudge {

    /** A verdict's state. */
    public static final String TAKEN = "taken";

    /** A verdict's state. */
    public static final String UNSIGNED = "unsigned";

    /** A verdict's state. */
    public static final String ABSENT = "absent";

    /** A verdict's state. */
    public static final String REWRITTEN = "rewritten";

    /**
     * What the signatures and history prove.
     *
     * @param state {@link #TAKEN}, {@link #UNSIGNED}, {@link #ABSENT} or {@link #REWRITTEN}.
     * @param commit The commit taken or refused, or {@code null} for absent or rewritten history.
     * @param signer The refused signer's fingerprint, or "" when there is no verified signature or
     *     no refusal of a signature.
     */
    public record Verdict(String state, @Nullable String commit, String signer) {}

    private RepositoryFileJudge() {}

    /**
     * Judges a file in a repository on this machine, read where it stands; closing leaves it.
     *
     * @param gitDirectory The repository's git directory, a mirror.
     * @param runner How git runs.
     * @param branch Its default branch.
     * @param path The file at the repository's top.
     * @param lastTaken The last change this machine took, or {@code null} for its first read.
     * @param keys The project's signing keys.
     * @return What may be taken, or the first refusal.
     * @throws IOException When the history cannot be read or judged.
     */
    public static Verdict judgeStanding(
            final java.nio.file.Path gitDirectory,
            final org.fuin.porelo.core.process.CommandRunner runner,
            final String branch,
            final String path,
            final @Nullable String lastTaken,
            final List<SigningKey> keys)
            throws IOException {
        try (TreeRepository repository = TreeRepository.standing(gitDirectory, runner)) {
            return judge(repository, branch, path, lastTaken, keys);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * Judges a file on the given branch without fetching or reading its content.
     *
     * @param repository The repository's mirror.
     * @param branch Its default branch.
     * @param path The file at the repository's top.
     * @param lastTaken The last change this machine took, or {@code null} for its first read.
     * @param keys The project's signing keys.
     * @return What may be taken, or the first refusal on the first-parent line, oldest first.
     * @throws IOException When a signature's allowed-signers file cannot be written.
     * @throws CardException When the repository's history cannot be read whole.
     */
    static Verdict judge(
            final TreeRepository repository,
            final String branch,
            final String path,
            final @Nullable String lastTaken,
            final List<SigningKey> keys)
            throws IOException {
        final String tip = repository.tip(branch);
        if (lastTaken != null && !repository.hasAncestor(tip, lastTaken)) {
            return new Verdict(REWRITTEN, null, "");
        }
        if (repository.object(tip, path).isEmpty()) {
            return new Verdict(ABSENT, null, "");
        }
        final List<Map.Entry<String, String>> line = repository.lineAfter(tip, lastTaken);
        final Set<String> signedAnew = repository.signingAnew(tip, path);
        Verdict verdict = new Verdict(lastTaken == null ? ABSENT : TAKEN, lastTaken, "");
        for (final Map.Entry<String, String> step : lastTaken == null ? line.reversed() : line) {
            final String commit = step.getKey();
            if (signedAnew.contains(commit)
                    || repository.changed(commit, step.getValue()).contains(path)) {
                verdict = signed(repository, commit, keys);
                if (lastTaken == null || UNSIGNED.equals(verdict.state())) {
                    return verdict;
                }
            }
        }
        return verdict;
    }

    private static Verdict signed(
            final TreeRepository repository, final String commit, final List<SigningKey> keys)
            throws IOException {
        final String signer;
        try {
            signer = repository.signer(commit);
        } catch (CardException ex) {
            return new Verdict(UNSIGNED, commit, "");
        }
        final LocalDate day = OffsetDateTime.parse(repository.dated(commit)).toLocalDate();
        final boolean allowed =
                keys.stream()
                        .anyMatch(
                                row ->
                                        ("admin".equals(row.role())
                                                        || "developer".equals(row.role()))
                                                && row.key().equals(signer)
                                                && row.revokedSince() == null
                                                && !day.isBefore(row.validFrom())
                                                && day.isBefore(row.validUntil()));
        return allowed
                ? new Verdict(TAKEN, commit, "")
                : new Verdict(UNSIGNED, commit, PersonCard.fingerprint(signer));
    }
}
