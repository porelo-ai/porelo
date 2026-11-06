package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repository settings must never be taken through an unapproved change, a revoked key, or a
 * rewritten history. Real SSH signatures keep the fixture independent of the judge's assumptions.
 */
class RepositoryFileJudgeTest {

    private static final String BRANCH = "trunk";

    private static final String PATH = "porelo.yml";

    private static final LocalDate DAY = LocalDate.of(2026, 4, 12);

    private static final String SIGNS_ANEW = "Sign anew\n\nPorelo-Signs-Anew: porelo.yml\n";

    @TempDir private Path dir;

    private Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, org.fuin.porelo.vault.SigningKey> keys = new HashMap<>();

    @BeforeEach
    void repository() throws IOException {
        repo = Files.createDirectory(dir.resolve("repository"));
        git("init", "-q", "-b", BRANCH);
        git("config", "user.name", "Repository");
        git("config", "user.email", "alice@repo.example.org");
        git("config", "gpg.format", "ssh");
        git("config", "commit.gpgsign", "false");
        change("README.md", "A repository\n", null);
    }

    private String git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(
                        Command.of(all)
                                .in(repo)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1",
                                                "GIT_AUTHOR_DATE",
                                                "2026-04-12T23:30:00-03:00",
                                                "GIT_COMMITTER_DATE",
                                                "2026-04-12T23:30:00-03:00")))
                .trimmedOutput();
    }

    private String key(final String holder) throws IOException {
        if (!keys.containsKey(holder)) {
            final Path file = dir.resolve(holder);
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            keyFiles.put(holder, file);
            keys.put(
                    holder,
                    new org.fuin.porelo.vault.SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test"));
        }
        return PersonCard.keyLine(keys.get(holder));
    }

    private String commit(final @Nullable String signer, final String message) throws IOException {
        git("add", "-A");
        final List<String> args = new ArrayList<>();
        if (signer != null) {
            key(signer);
            args.addAll(List.of("-c", "user.signingkey=" + keyFiles.get(signer)));
        }
        args.addAll(List.of("commit", "-q", "--allow-empty", "-m", message));
        if (signer != null) {
            args.add("-S");
        }
        git(args.toArray(String[]::new));
        return git("rev-parse", "HEAD");
    }

    private String change(final String path, final String text, final @Nullable String signer)
            throws IOException {
        Files.writeString(repo.resolve(path), text);
        return commit(signer, "Change a file");
    }

    private String merge(
            final String first, final String second, final String treeOf, final String signer)
            throws IOException {
        key(signer);
        final String merge =
                git(
                        "-c",
                        "user.signingkey=" + keyFiles.get(signer),
                        "commit-tree",
                        "-S",
                        "-p",
                        first,
                        "-p",
                        second,
                        "-m",
                        "Merge settings",
                        git("rev-parse", treeOf + "^{tree}"));
        git("reset", "-q", "--hard", merge);
        return merge;
    }

    private SigningKey row(final String holder, final String role) throws IOException {
        return new SigningKey(holder, role, key(holder), DAY.minusYears(1), DAY.plusYears(1), null);
    }

    private List<SigningKey> rows() throws IOException {
        return List.of(row("alice", "admin"), row("carol", "developer"));
    }

    private RepositoryFileJudge.Verdict judge(final @Nullable String lastTaken) throws IOException {
        return judge(lastTaken, rows());
    }

    private RepositoryFileJudge.Verdict judge(
            final @Nullable String lastTaken, final List<SigningKey> allowed) throws IOException {
        try (TreeRepository repository = TreeRepository.standing(repo.resolve(".git"), runner)) {
            return RepositoryFileJudge.judge(repository, BRANCH, PATH, lastTaken, allowed);
        }
    }

    private void unsigned(
            final RepositoryFileJudge.Verdict verdict,
            final String commit,
            final @Nullable String signer)
            throws IOException {
        assertThat(verdict)
                .as("refuse the offending commit and identify only its signing fingerprint")
                .isEqualTo(
                        new RepositoryFileJudge.Verdict(
                                RepositoryFileJudge.UNSIGNED,
                                commit,
                                signer == null ? "" : PersonCard.fingerprint(key(signer))));
    }

    @Test
    void theNewestChangeByADeveloperIsTakenOnFirstRead() throws Exception {
        change(PATH, "settings: old\n", null);
        final String newest = change(PATH, "settings: current\n", "carol");
        change("notes.txt", "An unrelated unsigned change\n", null);

        assertThat(judge(null))
                .as("the first read judges only the newest file change and records it, not the tip")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void theNewestUnsignedChangeIsNamedOnFirstRead() throws Exception {
        change(PATH, "settings: old\n", "alice");
        final String newest = change(PATH, "settings: unapproved\n", null);
        change("notes.txt", "An admin signed only this file\n", "alice");

        unsigned(judge(null), newest, null);
    }

    @Test
    void aStrangersChangeAfterAnAdminsChangeIsRefused() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        change(PATH, "settings: allowed\n", "alice");
        final String refused = change(PATH, "settings: unapproved\n", "mallory");

        unsigned(judge(taken), refused, "mallory");
    }

    @Test
    void aStrangersChangeBeforeAnAdminsChangeIsStillRefused() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        final String refused = change(PATH, "settings: unapproved\n", "mallory");
        change(PATH, "settings: allowed\n", "alice");

        unsigned(judge(taken), refused, "mallory");
    }

    @Test
    void theFirstOfSeveralUnapprovedChangesIsNamed() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        final String refused = change(PATH, "settings: unsigned\n", null);
        change(PATH, "settings: strangers\n", "mallory");

        unsigned(judge(taken), refused, null);
    }

    @Test
    void changesOnlyToOtherFilesAreNotJudged() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        change("notes.txt", "A stranger's notes\n", "mallory");
        final String newest = change(PATH, "settings: current\n", "carol");
        change("notes.txt", "Unsigned notes\n", null);

        assertThat(judge(taken))
                .as("unrelated signatures cannot block the developer's settings change")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void aRevokedKeyIsRefusedEvenForACommitBeforeItsRevocation() throws Exception {
        final String refused = change(PATH, "settings: revoked\n", "carol");
        final SigningKey revoked =
                new SigningKey(
                        "carol",
                        "developer",
                        key("carol"),
                        DAY.minusYears(1),
                        DAY.plusYears(1),
                        DAY.plusDays(1).toString());

        unsigned(judge(null, List.of(revoked)), refused, "carol");
    }

    @Test
    void aKeyNotYetValidOnTheCommitDateIsRefused() throws Exception {
        final String refused = change(PATH, "settings: early\n", "alice");
        final SigningKey early =
                new SigningKey(
                        "alice", "admin", key("alice"), DAY.plusDays(1), DAY.plusYears(1), null);

        unsigned(judge(null, List.of(early)), refused, "alice");
    }

    @Test
    void aKeyAtItsValidityEndIsRefused() throws Exception {
        final String refused = change(PATH, "settings: expired\n", "carol");
        final SigningKey expired =
                new SigningKey("carol", "developer", key("carol"), DAY.minusYears(1), DAY, null);

        unsigned(judge(null, List.of(expired)), refused, "carol");
    }

    @Test
    void aKeyAtItsValidityStartIsTakenUsingTheCommittersLocalDate() throws Exception {
        final String newest = change(PATH, "settings: current\n", "carol");
        final SigningKey valid =
                new SigningKey("carol", "developer", key("carol"), DAY, DAY.plusDays(1), null);

        assertThat(judge(null, List.of(valid)))
                .as("the validity start is inclusive and the commit's own date is used")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void aMatchingKeyInAnotherRoleIsRefused() throws Exception {
        final String refused = change(PATH, "settings: unapproved\n", "alice");

        unsigned(judge(null, List.of(row("alice", "owner"))), refused, "alice");
    }

    @Test
    void historyWithoutTheLastTakenCommitIsRewritten() throws Exception {
        final String older = change(PATH, "settings: older\n", "alice");
        final String taken = change(PATH, "settings: taken\n", "alice");
        git("reset", "-q", "--hard", older);
        change(PATH, "settings: replacement\n", "carol");

        assertThat(judge(taken))
                .as("an allowed newest signature cannot excuse discarding the accepted history")
                .isEqualTo(
                        new RepositoryFileJudge.Verdict(RepositoryFileJudge.REWRITTEN, null, ""));
    }

    @Test
    void anUnknownLastTakenCommitIsRewritten() throws Exception {
        change(PATH, "settings: current\n", "alice");

        assertThat(judge("0".repeat(40)))
                .as("losing the accepted object must not fall back to judging the newest change")
                .isEqualTo(
                        new RepositoryFileJudge.Verdict(RepositoryFileJudge.REWRITTEN, null, ""));
    }

    @Test
    void aMergeIsJudgedByItsSignerRatherThanTheMergedBranchesSigner() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        git("checkout", "-q", "-b", "topic");
        final String topic = change(PATH, "settings: merged\n", "mallory");
        git("checkout", "-q", BRANCH);
        final String first = change("notes.txt", "Unrelated unsigned work\n", null);
        final String newest = merge(first, topic, topic, "carol");

        assertThat(judge(taken))
                .as("the merge's first-parent difference is the change the developer signed")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void lastTakenThroughASecondParentIsStillAnAncestor() throws Exception {
        final String older = change(PATH, "settings: older\n", "alice");
        final String taken = change(PATH, "settings: taken\n", "alice");
        git("reset", "-q", "--hard", older);
        final String first = change(PATH, "settings: another branch\n", "carol");
        final String newest = merge(first, taken, taken, "alice");

        assertThat(judge(taken))
                .as("ancestry includes every parent even though changes follow the first parent")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void aFileNeverThereIsAbsent() throws Exception {
        assertThat(judge(null))
                .as("no repository settings are taken when the branch has no file")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.ABSENT, null, ""));
    }

    @Test
    void aRemovedFileIsAbsent() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        Files.delete(repo.resolve(PATH));
        commit(null, "Remove settings");

        assertThat(judge(taken))
                .as("a removed file must never reuse settings from an older commit")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.ABSENT, null, ""));
    }

    @Test
    void noChangeSinceLastTakenKeepsThatCommitWithoutJudgingItAgain() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        change("notes.txt", "Unrelated unsigned work\n", null);

        assertThat(judge(taken, List.of()))
                .as("an unchanged accepted file keeps its commit even when its signer is gone")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, taken, ""));
    }

    @Test
    void aDeveloperSigningAnewIsTheNewestChangeOnFirstRead() throws Exception {
        change(PATH, "settings: unapproved\n", "mallory");
        final String newest = commit("carol", SIGNS_ANEW);

        assertThat(judge(null))
                .as("a developer may take responsibility for the file on its first read")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void signingAnewAfterLastTakenIsJudgedAndRecorded() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        final String newest = commit("carol", SIGNS_ANEW);

        assertThat(judge(taken))
                .as("a signature naming the file advances the taken commit without changing bytes")
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
    }

    @Test
    void aStrangerSigningAnewAfterLastTakenIsRefused() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        final String refused = commit("mallory", SIGNS_ANEW);

        unsigned(judge(taken), refused, "mallory");
    }

    @Test
    void aSignatureAnewByAPersonWhoMaySignTakesOnAnEarlierUnsignedChangeAfterLastTaken()
            throws Exception {
        // The signer read the file as it stands and vouches for it; refusing for good left a
        // machine that took the file once with no way to take it again.
        final String taken = change(PATH, "settings: taken\n", "alice");
        change(PATH, "settings: unapproved\n", null);
        final String anew = commit("alice", SIGNS_ANEW);

        assertThat(judge(taken))
                .isEqualTo(new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, anew, ""));
    }

    @Test
    void aSignatureAnewByAStrangerLeavesAnEarlierUnsignedChangeRefused() throws Exception {
        final String taken = change(PATH, "settings: taken\n", "alice");
        final String refused = change(PATH, "settings: unapproved\n", null);
        commit("mallory", SIGNS_ANEW);

        unsigned(judge(taken), refused, null);
    }

    @Test
    void aMirrorIsJudgedOnTheGivenBranchRatherThanMainOrHead() throws Exception {
        final String newest = change(PATH, "settings: current\n", "carol");
        git("checkout", "-q", "-b", "main");
        change(PATH, "settings: unapproved\n", "mallory");
        final Path mirror = dir.resolve("mirror.git");
        git("clone", "-q", "--mirror", "--no-hardlinks", "--", repo.toString(), mirror.toString());

        try (TreeRepository repository = TreeRepository.standing(mirror, runner)) {
            assertThat(RepositoryFileJudge.judge(repository, BRANCH, PATH, null, rows()))
                    .as("a mirror's default branch may differ from main and its symbolic HEAD")
                    .isEqualTo(
                            new RepositoryFileJudge.Verdict(RepositoryFileJudge.TAKEN, newest, ""));
        }
    }
}
