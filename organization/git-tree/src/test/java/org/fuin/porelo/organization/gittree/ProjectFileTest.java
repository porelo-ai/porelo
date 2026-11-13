package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.organization.OrganizationWrites.Written;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's own file. Harm guarded: a host and its people act on the repositories this names, so
 * a file nobody allowed must not be read as the project's, and a failure must not read as empty.
 */
class ProjectFileTest {

    private static final String UPSTREAM = "git@github.com:acme/app.git";

    private static final String TEXT =
            """
            project:
              name: "demo"
              description: "A project"
              security_class: "online"
              upstream: "git@github.com:acme/demo.git"

            image:
              from: "ubuntu:24.04"

            repositories:
              first:
                upstream: "git@github.com:acme/first.git"
            """;

    @TempDir private Path dir;

    private Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, org.fuin.porelo.vault.SigningKey> keys = new HashMap<>();

    @BeforeEach
    void repository() throws Exception {
        repo = Files.createDirectory(dir.resolve("demo"));
        for (final String login : List.of("alice", "carol", "mallory", "gone")) {
            final Path file = dir.resolve(login);
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            keyFiles.put(login, file);
            keys.put(
                    login,
                    new org.fuin.porelo.vault.SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test"));
        }
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Project");
        git("config", "user.email", "project@example.org");
        git("config", "gpg.format", "ssh");
        git("config", "receive.denyCurrentBranch", "updateInstead");
        commit("project.yml", TEXT, "alice");
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
                                                "1")))
                .trimmedOutput();
    }

    private String commit(final String path, final String text, final String signer)
            throws IOException {
        Files.writeString(repo.resolve(path), text);
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get(signer),
                "commit",
                "-q",
                "-S",
                "-m",
                "a change");
        return git("rev-parse", "HEAD");
    }

    /** A commit that changes no file, signed by this key or, for {@code null}, by none. */
    private String emptyCommit(final @Nullable String signer, final String message) {
        final List<String> args = new ArrayList<>();
        if (signer != null) {
            args.addAll(List.of("-c", "user.signingkey=" + keyFiles.get(signer)));
        }
        args.addAll(List.of("commit", "-q", "--allow-empty", "-m", message));
        if (signer != null) {
            args.add("-S");
        }
        git(args.toArray(String[]::new));
        return git("rev-parse", "HEAD");
    }

    private static final String SIGNS_ANEW = "a change\n\nPorelo-Signs-Anew: project.yml\n";

    private String key(final String holder) {
        return PersonCard.keyLine(keys.get(holder));
    }

    private SigningKey row(final String holder, final String role) {
        return new SigningKey(
                holder,
                role,
                key(holder),
                LocalDate.of(2000, 1, 1),
                LocalDate.of(2999, 1, 1),
                null);
    }

    private List<SigningKey> rows() {
        return List.of(row("alice", "admin"), row("carol", "developer"));
    }

    private ProjectFile file() {
        return file(dir.resolve("memory"));
    }

    private ProjectFile file(final Path memory) {
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        return new ProjectFile(
                runner, FetchAccess.NONE, new CommitSigning(Map.of(), files), memory);
    }

    private ProjectFile.Read read() throws IOException {
        return file().read(repo.toString(), rows());
    }

    private Change change(final String holder, final String id) {
        return new Change(new Acting(holder, key(holder)), null, id);
    }

    private void refusedAs(final String reason, final ThrowingWrite write) {
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(
                        Refused.class, refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    @FunctionalInterface
    private interface ThrowingWrite {
        void run() throws IOException;
    }

    @Test
    void anAdminsFileIsReadAtTheRepositorysTip() throws Exception {
        final ProjectFile.Read read = read();

        assertThat(read.state()).as(String.valueOf(read.why())).isEqualTo(ProjectFile.READ);
        assertThat(read.revision()).isEqualTo(git("rev-parse", "HEAD"));
        assertThat(read.project().repositories()).extracting("name").containsExactly("first");
    }

    @Test
    void anotherFileByADeveloperLeavesTheAdminsProjectFileRead() throws Exception {
        final String tip = commit("notes.txt", "by a developer", "carol");

        final ProjectFile.Read read = read();

        assertThat(read.state()).isEqualTo(ProjectFile.READ);
        assertThat(read.revision()).isEqualTo(tip);
    }

    @Test
    void aFileADeveloperSignedIsRefused() throws Exception {
        commit("project.yml", TEXT.replace("first", "theirs"), "carol");

        final ProjectFile.Read read = read();

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.project()).as("nothing of it is read").isNull();
        assertThat(read.revision()).isNull();
        assertThat(read.why()).contains(PersonCard.fingerprint(key("carol")));
    }

    @Test
    void aFileAStrangerSignedIsRefused() throws Exception {
        commit("project.yml", TEXT.replace("first", "theirs"), "mallory");

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aFileAnUnsignedCommitChangedIsRefused() throws Exception {
        Files.writeString(repo.resolve("project.yml"), TEXT.replace("first", "theirs"));
        git("add", "-A");
        git("commit", "-q", "-m", "unsigned");

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void anOlderSignedFileDoesNotHoldUpWhatARefusedOneChanged() throws Exception {
        commit("project.yml", TEXT.replace("first", "theirs"), "mallory");
        commit("notes.txt", "a later commit", "alice");

        assertThat(read().state())
                .as("signing an unrelated commit on top must not bless the file under it")
                .isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aWithdrawnAdminsFileIsRefusedAndOutsideItsDaysToo() throws Exception {
        final List<SigningKey> revoked =
                List.of(
                        new SigningKey(
                                "alice",
                                "admin",
                                key("alice"),
                                LocalDate.of(2000, 1, 1),
                                LocalDate.of(2999, 1, 1),
                                "abc"));
        final List<SigningKey> before =
                List.of(
                        new SigningKey(
                                "alice",
                                "admin",
                                key("alice"),
                                LocalDate.of(2998, 1, 1),
                                LocalDate.of(2999, 1, 1),
                                null));
        final List<SigningKey> after =
                List.of(
                        new SigningKey(
                                "alice",
                                "admin",
                                key("alice"),
                                LocalDate.of(2000, 1, 1),
                                LocalDate.of(2001, 1, 1),
                                null));

        assertThat(file().read(repo.toString(), revoked).state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(file().read(repo.toString(), before).state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(file().read(repo.toString(), after).state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aFileRemovedOrNeverThereIsRefusedAndNotAnEmptyProject() throws Exception {
        Files.delete(repo.resolve("project.yml"));
        Files.writeString(repo.resolve("notes.txt"), "x");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("alice"), "commit", "-q", "-S", "-m", "off");

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aFileThatDoesNotReadIsRefusedWithoutQuotingIt() throws Exception {
        commit("project.yml", "hunter2: x\nproject: [hunter2\n", "alice");

        final ProjectFile.Read read = read();

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).doesNotContain("hunter2").contains("at line 3");
    }

    @Test
    void aSignedFileThatDoesNotReadNamesTheKeyAPersonChanges() throws Exception {
        commit("project.yml", TEXT.replace("online\"", "online\"\n  review: \"maybe\""), "alice");

        final ProjectFile.Read read = read();

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).contains("does not read").contains("project.review");
    }

    @Test
    void aRepositoryThatCannotBeReachedCannotSayAndIsNeverEmpty() throws Exception {
        final ProjectFile.Read read = file().read(dir.resolve("gone").toString(), rows());

        assertThat(read.state()).isEqualTo(ProjectFile.CANNOT_SAY);
        assertThat(read.project()).isNull();
    }

    private ProjectFile throughAgent(final Path socket) {
        return new ProjectFile(
                runner,
                FetchAccess.NONE,
                CommitSigning.agent(socket.toString()),
                dir.resolve("memory"));
    }

    @Test
    void aChangeIsSignedThroughTheAgentThatServesTheKey() throws Exception {
        final Path bare = empty();
        final Path socket = dir.resolve("agent.sock");
        try (org.fuin.porelo.vault.SshAgentServer agent =
                new org.fuin.porelo.vault.SshAgentServer(socket, List.of(keys.get("alice")))) {
            Thread.ofVirtual().start(agent);

            throughAgent(socket).found(change("alice", "one"), bare.toString(), TEXT);
        }

        final ProjectFile.Read read = file().read(bare.toString(), rows());
        assertThat(read.state())
                .as("a reader verifies what the agent signed")
                .isEqualTo(ProjectFile.READ);
        assertThat(read.project().repositories()).extracting("name").contains("first");
    }

    @Test
    void anAgentThatDoesNotServeTheKeyIsNoKeyAndNothingIsPushed() throws Exception {
        final Path bare = empty();
        final Path socket = dir.resolve("agent.sock");
        try (org.fuin.porelo.vault.SshAgentServer agent =
                new org.fuin.porelo.vault.SshAgentServer(socket, List.of(keys.get("carol")))) {
            Thread.ofVirtual().start(agent);

            refusedAs(
                    Refused.NO_KEY,
                    () ->
                            throughAgent(socket)
                                    .found(change("alice", "one"), bare.toString(), TEXT));
        }

        assertThat(
                        runner.run(
                                        Command.of(
                                                "git",
                                                "--git-dir",
                                                bare.toString(),
                                                "rev-parse",
                                                "--verify",
                                                "-q",
                                                "HEAD"))
                                .successful())
                .as("nothing was pushed")
                .isFalse();
    }

    @Test
    void noAgentAtAllIsNoKey() {
        final Path bare = empty();
        refusedAs(
                Refused.NO_KEY,
                () ->
                        throughAgent(dir.resolve("no-agent.sock"))
                                .found(change("alice", "one"), bare.toString(), TEXT));
    }

    private Path empty() {
        final Path bare = dir.resolve("empty.git");
        runner.runOrFail(Command.of("git", "init", "-q", "--bare", "-b", "main", bare.toString()));
        return bare;
    }

    @Test
    void anEmptyRepositoryIsFoundedWithASignedFileAReaderBelieves() throws Exception {
        final Path bare = empty();

        final Written first = file().found(change("alice", "one"), bare.toString(), TEXT);

        assertThat(first.again()).isFalse();
        final ProjectFile.Read read = file().read(bare.toString(), rows());
        assertThat(read.state()).as(String.valueOf(read.why())).isEqualTo(ProjectFile.READ);
        assertThat(read.revision()).isEqualTo(first.revision());
    }

    @Test
    void foundingAskedAgainUnderItsIdAnswersWhatItMadeAndAnotherIdIsExists() throws Exception {
        final Path bare = empty();
        final Written first = file().found(change("alice", "one"), bare.toString(), TEXT);

        final Written again = file().found(change("alice", "one"), bare.toString(), TEXT);

        assertThat(again.revision()).as("the first answer").isEqualTo(first.revision());
        assertThat(again.again()).isTrue();
        refusedAs(
                Refused.EXISTS, () -> file().found(change("alice", "two"), bare.toString(), TEXT));
        refusedAs(
                Refused.EXISTS, () -> file().found(change("carol", "one"), bare.toString(), TEXT));
    }

    @Test
    void foundingWhatIsNotThereIsNoRepository() {
        refusedAs(
                Refused.NO_REPOSITORY,
                () -> file().found(change("alice", "one"), dir.resolve("none").toString(), TEXT));
    }

    private List<SigningKey> aliceGone() {
        return List.of(row("carol", "admin"));
    }

    private Change changeOn(final String holder, final String id, final String base) {
        return new Change(new Acting(holder, key(holder)), base, id);
    }

    private Written signed(final String holder, final String id) throws IOException {
        return file().sign(
                        changeOn(holder, id, git("rev-parse", "HEAD")),
                        repo.toString(),
                        aliceGone(),
                        "demo");
    }

    @Test
    void anAdminSinceRemovedLeavesARefusedFileACurrentAdminSignsAnew() throws Exception {
        assertThat(file().read(repo.toString(), aliceGone()).state())
                .isEqualTo(ProjectFile.REFUSED);

        final Written written = signed("carol", "again");

        assertThat(written.again()).isFalse();
        assertThat(written.revision()).isEqualTo(git("rev-parse", "HEAD"));
        final ProjectFile.Read read = file().read(repo.toString(), aliceGone());
        assertThat(read.state()).as(String.valueOf(read.why())).isEqualTo(ProjectFile.READ);
        assertThat(read.signer()).isEqualTo(key("carol"));
        assertThat(Files.readString(repo.resolve("project.yml")))
                .as("what the file said stays, every byte")
                .isEqualTo(TEXT);
        assertThat(git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD~1", "HEAD"))
                .as("an empty commit")
                .isEmpty();
        assertThat(git("log", "-1", "--format=%(trailers:key=Porelo-Signs-Anew,valueonly)"))
                .isEqualTo("project.yml");
    }

    @Test
    void aSigningAnewByOneWhoIsNoAdminIsRefusedLikeTheirChange() throws Exception {
        emptyCommit("carol", SIGNS_ANEW);

        final ProjectFile.Read read = read();

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).contains(PersonCard.fingerprint(key("carol")));
    }

    @Test
    void anUnsignedSigningAnewIsRefused() throws Exception {
        emptyCommit(null, SIGNS_ANEW);

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aSigningAnewNamedTwiceIsJudgedAsOnce() throws Exception {
        emptyCommit(
                null,
                "a change\n\nPorelo-Signs-Anew: project.yml\nPorelo-Signs-Anew: project.yml\n");

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void onlyAValueThatIsTheWholePathSignsItAnew() throws Exception {
        commit("project.yml", TEXT + "# mallory's\n", "mallory");
        emptyCommit("alice", "a change\n\nPorelo-Signs-Anew: README.md,project.yml\n");
        emptyCommit("alice", "a change\n\nPorelo-Signs-Anew: project.\nPorelo-Signs-Anew: yml\n");

        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void anEmptyCommitThatNamesNoSigningAnewLeavesTheFileRead() throws Exception {
        emptyCommit("mallory", "a change\n\nPorelo-Signs-Anew: README.md\n");
        emptyCommit(null, "nothing at all");

        assertThat(read().state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void aSigningAskedAgainUnderItsIdAnswersWhatItMade() throws Exception {
        final Written first = signed("carol", "again");

        final Written again =
                file().sign(
                                changeOn("carol", "again", "0".repeat(40)),
                                repo.toString(),
                                aliceGone(),
                                "demo");

        assertThat(again.revision()).as("the first answer").isEqualTo(first.revision());
        assertThat(again.again()).isTrue();
        assertThat(git("rev-parse", "HEAD")).isEqualTo(first.revision());
    }

    @Test
    void aPersonWhoIsNoAdminNowSignsNothing() throws Exception {
        final String tip = git("rev-parse", "HEAD");

        refusedAs(Refused.NOT_ALLOWED, () -> signed("mallory", "again"));
        refusedAs(Refused.NOT_ALLOWED, () -> signed("alice", "again"));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void aSigningOfWhatIsNoLongerThereIsRefused() throws Exception {
        final String read = git("rev-parse", "HEAD");
        commit("project.yml", TEXT + "# changed since it was read\n", "mallory");
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.MOVED_ON,
                () ->
                        file().sign(
                                        changeOn("carol", "again", read),
                                        repo.toString(),
                                        aliceGone(),
                                        "demo"));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void aFileThatReadsHereIsSignedForTheMachineThatRefusesItAsOlder() throws Exception {
        final ProjectFile host = file(dir.resolve("host-memory"));
        aChangeDated(TEXT.replace("first", "older"), "2020-01-01T00:00:00Z");
        final String older = git("rev-parse", "HEAD");
        aChangeDated(TEXT.replace("first", "newer"), "2021-01-01T00:00:00Z");
        assertThat(host.read(repo.toString(), rows()).state()).isEqualTo(ProjectFile.READ);
        servedOlderByAMerge(older, "mallory");
        assertThat(host.read(repo.toString(), rows()).state()).isEqualTo(ProjectFile.REFUSED);
        final String tip = git("rev-parse", "HEAD");
        assertThat(read().state())
                .as("this machine never accepted the newer one")
                .isEqualTo(ProjectFile.READ);

        final Written written =
                file().sign(changeOn("alice", "again", tip), repo.toString(), rows(), "demo");

        assertThat(written.again()).isFalse();
        assertThat(written.revision()).isNotEqualTo(tip);
        assertThat(host.read(repo.toString(), rows()).state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void aSigningGetsThisMachineOutOfAFileRefusedAsOlder() throws Exception {
        final String older = git("rev-parse", "HEAD");
        aLaterChange("2090-01-01T00:00:00Z");
        read();
        servedOlderByAMerge(older, "mallory");
        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);

        file().sign(
                        changeOn("alice", "again", git("rev-parse", "HEAD")),
                        repo.toString(),
                        rows(),
                        "demo");

        assertThat(read().state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void aFileAnAdminSignedThatNamesAnotherProjectIsNotAnsweredAsDone() throws Exception {
        commit("project.yml", TEXT.replace("\"demo\"", "\"other\""), "alice");
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.REFUSED_STATE,
                () ->
                        file().sign(
                                        changeOn("alice", "again", tip),
                                        repo.toString(),
                                        rows(),
                                        "demo"));
    }

    private void aSymlinkInsteadOfTheFile() throws IOException {
        Files.delete(repo.resolve("project.yml"));
        Files.createSymbolicLink(repo.resolve("project.yml"), Path.of("elsewhere"));
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get("mallory"),
                "commit",
                "-q",
                "-S",
                "-m",
                "a link");
    }

    @Test
    void aLookAtALinkInPlaceOfTheFileSaysSoAndASignatureIsRefused() throws Exception {
        aSymlinkInsteadOfTheFile();

        final ProjectFile.Look look = file().look(repo.toString(), rows(), "demo");

        assertThat(look.read().state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(look.text()).isNull();
        assertThat(look.blocked()).contains("does not read");
        refusedAs(
                Refused.REFUSED_STATE,
                () ->
                        file().sign(
                                        changeOn("alice", "again", git("rev-parse", "HEAD")),
                                        repo.toString(),
                                        rows(),
                                        "demo"));
    }

    @Test
    void aFileAtTheLimitIsSignedAnewWithNoByteAdded() throws Exception {
        final String padding = "# " + "x".repeat(1024 * 1024 - TEXT.length() - 3) + "\n";
        commit("project.yml", TEXT + padding, "mallory");
        final String tip = git("rev-parse", "HEAD");
        assertThat(Files.size(repo.resolve("project.yml"))).isEqualTo(1024 * 1024);

        assertThat(file().look(repo.toString(), rows(), "demo").blocked()).isNull();
        file().sign(changeOn("alice", "again", tip), repo.toString(), rows(), "demo");

        assertThat(Files.size(repo.resolve("project.yml"))).isEqualTo(1024 * 1024);
        assertThat(read().state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void aRefusalNamesTheVerbOnlyWhereASignatureWouldDo() throws Exception {
        commit("project.yml", TEXT + "# mallory's\n", "mallory");
        final ProjectFile.Look signable = file().look(repo.toString(), rows(), "demo");
        assertThat(signable.read().why()).contains("porelo project sign");
        assertThat(signable.blocked()).isNull();

        commit("project.yml", "not: [a project", "mallory");
        final ProjectFile.Look broken = file().look(repo.toString(), rows(), "demo");
        assertThat(broken.read().why()).doesNotContain("porelo project sign").contains("mends");
        assertThat(broken.blocked()).contains("does not read");
        assertThat(broken.text()).isEqualTo("not: [a project");

        commit("project.yml", TEXT.replace("\"demo\"", "\"other\""), "mallory");
        final ProjectFile.Look named = file().look(repo.toString(), rows(), "demo");
        assertThat(named.blocked()).contains("another project");
        assertThat(named.read().why())
                .as("a read does not know the name, so it promises no signature")
                .contains("shows an admin whether");
    }

    @Test
    void anUnsignedChangeTheAdminHasReadReadsOnceSigned() throws Exception {
        Files.writeString(repo.resolve("project.yml"), TEXT + "# nobody signed this\n");
        git("add", "-A");
        git("commit", "-q", "-m", "unsigned");
        assertThat(file().read(repo.toString(), rows()).state()).isEqualTo(ProjectFile.REFUSED);

        file().sign(
                        changeOn("alice", "again", git("rev-parse", "HEAD")),
                        repo.toString(),
                        rows(),
                        "demo");

        assertThat(file().read(repo.toString(), rows()).state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void aSignatureDoesNotMakeAFileReadThatCannotBeRead() throws Exception {
        commit("project.yml", "not: [a project", "alice");
        final String broken = git("rev-parse", "HEAD");
        refusedAs(Refused.REFUSED_STATE, () -> signed("carol", "again"));
        assertThat(git("rev-parse", "HEAD")).isEqualTo(broken);

        commit("project.yml", TEXT.replace("\"demo\"", "\"other\""), "alice");
        refusedAs(Refused.REFUSED_STATE, () -> signed("carol", "again"));

        Files.delete(repo.resolve("project.yml"));
        git("add", "-A");
        git("commit", "-q", "-m", "removed");
        refusedAs(Refused.REFUSED_STATE, () -> signed("carol", "again"));
    }

    @Test
    void aSignatureDoesNotBringBackASnippetFileThatIsGone() throws Exception {
        commit(
                "project.yml",
                TEXT.replace(
                        "from: \"ubuntu:24.04\"",
                        "from: \"ubuntu:24.04\"\n  run_file: \"gone.txt\""),
                "alice");
        final String tip = git("rev-parse", "HEAD");

        refusedAs(Refused.REFUSED_STATE, () -> signed("carol", "again"));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void aLookNamesTheSignerAndTheFileAndChangesNothing() throws Exception {
        final String tip = git("rev-parse", "HEAD");

        final ProjectFile.Look look = file().look(repo.toString(), aliceGone(), "demo");

        assertThat(look.read().state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(look.read().signer()).isEqualTo(key("alice"));
        assertThat(look.read().why()).contains("no key of an admin");
        assertThat(look.revision()).isEqualTo(tip);
        assertThat(look.text()).isEqualTo(TEXT);
        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void aLookAtWhatCannotBeReachedSaysSo() throws Exception {
        final ProjectFile.Look look =
                file().look(dir.resolve("none").toString(), aliceGone(), "demo");

        assertThat(look.read().state()).isEqualTo(ProjectFile.CANNOT_SAY);
        assertThat(look.text()).isNull();
    }

    /**
     * A commit whose first parent is the older state and whose second is the tip: a plain
     * fast-forward anyone with write access can push, showing no change against its first parent.
     */
    private void servedOlderByAMerge(final String older, final String signer) {
        final String tip = git("rev-parse", "HEAD");
        final String tree = git("rev-parse", older + "^{tree}");
        final String merge =
                git(
                        "-c",
                        "user.signingkey=" + keyFiles.get(signer),
                        "commit-tree",
                        "-S",
                        "-p",
                        older,
                        "-p",
                        tip,
                        "-m",
                        "back",
                        tree);
        git("reset", "-q", "--hard", merge);
    }

    private void aLaterChange(final String seconds) throws IOException {
        aChangeDated(TEXT.replace("first", "later"), seconds);
    }

    private void aChangeDated(final String text, final String seconds) throws IOException {
        Files.writeString(repo.resolve("project.yml"), text);
        git("add", "-A");
        runner.runOrFail(
                Command.of(
                                "git",
                                "-c",
                                "user.signingkey=" + keyFiles.get("alice"),
                                "commit",
                                "-q",
                                "-S",
                                "-m",
                                "later")
                        .in(repo)
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1",
                                        "GIT_COMMITTER_DATE",
                                        seconds)));
    }

    @Test
    void aRepositoryReachedOverSshKeepsTheFloorItsHttpsAddressSet() {
        // A person who switches to the organization's ssh form must not lose the newest
        // revision accepted, or an older file would be taken once.
        assertThat(ProjectFile.oneForm("git@forge.example:acme/foo.git"))
                .isEqualTo(ProjectFile.oneForm("https://forge.example/acme/foo.git"))
                .isEqualTo(ProjectFile.oneForm("ssh://git@forge.example:2222/acme/foo.git"));
        assertThat(ProjectFile.oneForm("git@other.example:acme/foo.git"))
                .isNotEqualTo(ProjectFile.oneForm("https://forge.example/acme/foo.git"));
    }

    @Test
    void everySpellingOfOneRepositoryKeepsOneFloor() {
        // A forge answers to these as one repository; a floor kept per spelling is lost by a
        // respelling.
        final String one = ProjectFile.oneForm("https://forge.example/acme/foo.git");
        assertThat(ProjectFile.oneForm("git@FORGE.example:acme/foo.git")).isEqualTo(one);
        assertThat(ProjectFile.oneForm("https://Forge.Example:443/acme/foo/")).isEqualTo(one);
        assertThat(ProjectFile.oneForm("https://forge.example/acme/foo")).isEqualTo(one);
        assertThat(ProjectFile.oneForm("https://forge.example/acme/bar.git")).isNotEqualTo(one);
        assertThat(ProjectFile.oneForm("https://forge.example:8443/acme/foo.git"))
                .as("another port is another server")
                .isNotEqualTo(one);
    }

    @Test
    void aFloorKeptUnderTheAddressAsGivenBeforeStillHolds(@TempDir final Path memory)
            throws IOException {
        // Accepted over ssh before the floor was kept in one form: an upgrade must not drop it.
        final String address = "git@forge.example:acme/foo.git";
        Files.createDirectories(memory);
        Files.writeString(
                memory.resolve(TreeRepository.digest(address)),
                "2090-01-01T00:00:00Z\n",
                java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(
                memory.resolve(TreeRepository.digest(ProjectFile.oneForm(address))),
                "2080-01-01T00:00:00Z\n",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(ProjectFile.accepted(memory, address))
                .isEqualTo(java.time.Instant.parse("2090-01-01T00:00:00Z"));
    }

    @Test
    void aFloorKeptUnderTheFiledAddressHoldsOnceTheRepositoryIsReachedOverSsh(
            @TempDir final Path memory) throws IOException {
        // A machine kept its floor under the https address as filed; the organization now reaches
        // the same repository over ssh, so that floor is read under another spelling.
        Files.createDirectories(memory);
        Files.writeString(
                memory.resolve(TreeRepository.digest("https://forge.example/acme/foo.git")),
                "2090-01-01T00:00:00Z\n",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(ProjectFile.accepted(memory, "git@forge.example:acme/foo.git"))
                .isEqualTo(java.time.Instant.parse("2090-01-01T00:00:00Z"));
        assertThat(ProjectFile.accepted(memory, "ssh://git@forge.example:2222/acme/foo.git"))
                .isEqualTo(java.time.Instant.parse("2090-01-01T00:00:00Z"));
    }

    @Test
    void aMergeThatBringsBackAnOlderFileIsRefusedOnceANewerWasAccepted() throws Exception {
        final String older = git("rev-parse", "HEAD");
        aLaterChange("2090-01-01T00:00:00Z");
        assertThat(read().project().repositories()).extracting("name").containsExactly("later");

        servedOlderByAMerge(older, "mallory");

        final ProjectFile.Read read = read();
        assertThat(read.state())
                .as("a host's repositories and where a token is lent would go back with it")
                .isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).contains("older");
    }

    @Test
    void anAdminSigningAnewGetsOutOfARefusedOlderFile() throws Exception {
        final String older = git("rev-parse", "HEAD");
        aLaterChange("2090-01-01T00:00:00Z");
        read();
        servedOlderByAMerge(older, "mallory");
        assertThat(read().state()).isEqualTo(ProjectFile.REFUSED);

        aLaterChange("2090-01-02T00:00:00Z");

        assertThat(read().state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void theSameFileReadAgainAndAnotherFilesChangeStayRead() throws Exception {
        assertThat(read().state()).isEqualTo(ProjectFile.READ);
        assertThat(read().state()).isEqualTo(ProjectFile.READ);

        commit("notes.txt", "by a developer", "carol");

        assertThat(read().state()).isEqualTo(ProjectFile.READ);
    }

    @Test
    void whatARefusalDidNotAcceptIsNotRemembered() throws Exception {
        aLaterChange("2090-01-01T00:00:00Z");

        final ProjectFile unknowing =
                new ProjectFile(
                        runner,
                        FetchAccess.NONE,
                        new CommitSigning(Map.of(), Map.of()),
                        dir.resolve("memory"));
        assertThat(unknowing.read(repo.toString(), List.of()).state())
                .isEqualTo(ProjectFile.REFUSED);

        assertThat(dir.resolve("memory")).doesNotExist();
    }

    @Test
    void aRepositoryIsRememberedApartFromAnother() throws Exception {
        aLaterChange("2090-01-01T00:00:00Z");
        read();
        final Path bare = empty();
        file().found(change("alice", "one"), bare.toString(), TEXT);

        assertThat(file().read(bare.toString(), rows()).state())
                .as("an older file in another repository goes back from nothing")
                .isEqualTo(ProjectFile.READ);
    }

    @Test
    void aKeyFileThatDoesNotOpenIsNoKey() throws Exception {
        final Path locked = dir.resolve("locked");
        runner.runOrFail(
                Command.of(
                        "ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "secret",
                        "-f",
                        locked.toString()));
        final String key =
                Files.readString(dir.resolve("locked.pub")).strip().replaceAll(" [^ ]*$", "");
        final ProjectFile file =
                new ProjectFile(
                        runner,
                        FetchAccess.NONE,
                        new CommitSigning(Map.of(), Map.of(key, locked)),
                        dir.resolve("memory"));

        final Path bare = empty();
        refusedAs(
                Refused.NO_KEY,
                () ->
                        file.found(
                                new Change(new Acting("locked", key), null, "one"),
                                bare.toString(),
                                TEXT));
    }

    @Test
    void aRepositoryHoldingAnyOtherBranchIsNotEmpty() throws Exception {
        final Path bare = dir.resolve("other-branch.git");
        git("clone", "-q", "--bare", repo.toString(), bare.toString());
        runner.runOrFail(
                Command.of("git", "--git-dir=" + bare, "branch", "-q", "-m", "main", "master"));

        refusedAs(
                Refused.EXISTS, () -> file().found(change("alice", "one"), bare.toString(), TEXT));
    }

    @Test
    void aFoundingPushTheRepositoryRefusesIsNotAnsweredAsMade() throws Exception {
        final Path bare = empty();
        final Path hook = bare.resolve("hooks/pre-receive");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);

        assertThatThrownBy(() -> file().found(change("alice", "one"), bare.toString(), TEXT))
                .isInstanceOf(GitTreeStore.Unreachable.class);
    }

    @Test
    void aRepositoryMissingOverHttpsIsNoRepositoryAndNotAForgeThatDoesNotAnswer() throws Exception {
        // Git words it so over HTTPS only, and `project open` would then never make the repository.
        final CommandRunner answering =
                command ->
                        command.arguments().contains("ls-remote")
                                ? new CommandResult(
                                        command,
                                        128,
                                        "",
                                        "fatal: repository 'https://forge.example/acme/demo.git/'"
                                                + " not found\n")
                                : runner.run(command);
        final ProjectFile file =
                new ProjectFile(
                        answering,
                        FetchAccess.NONE,
                        new CommitSigning(Map.of(), Map.of()),
                        dir.resolve("memory"));

        refusedAs(
                Refused.NO_REPOSITORY,
                () -> file.found(change("alice", "one"), "https://forge.example/acme/demo", TEXT));
    }

    @Test
    void aFileIsReadWhereItStandsAndTheCopyIsLeftAlone() throws Exception {
        final Path git = repo.resolve(".git");
        final String tip = git("rev-parse", "HEAD");

        final ProjectFile.Read read =
                ProjectFile.standing(runner, git, List.of(row("alice", "admin")));

        assertThat(read.state()).isEqualTo(ProjectFile.READ);
        assertThat(read.revision()).isEqualTo(tip);
        assertThat(read.project().name()).isEqualTo("demo");
        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
        assertThat(git).isDirectory();
    }

    @Test
    void aFileReadWhereItStandsSignedByNoSignerIsRefused() throws Exception {
        final ProjectFile.Read read =
                ProjectFile.standing(runner, repo.resolve(".git"), List.of(row("carol", "admin")));

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.project()).isNull();
    }

    @Test
    void aFileReadWhereItStandsWithABadValueNamesTheKey() throws Exception {
        commit("project.yml", TEXT.replace("online\"", "online\"\n  review: \"maybe\""), "alice");

        final ProjectFile.Read read =
                ProjectFile.standing(runner, repo.resolve(".git"), List.of(row("alice", "admin")));

        assertThat(read.state()).isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).contains("project.review");
    }

    @Test
    void aFileReadWhereItStandsWhenThereIsNoRepositoryIsRefused() throws Exception {
        final ProjectFile.Read read =
                ProjectFile.standing(runner, dir.resolve("empty"), List.of(row("alice", "admin")));

        assertThat(read.state()).isNotEqualTo(ProjectFile.READ);
    }

    private ProjectFile.Read readBack() throws IOException {
        return file().read(repo.toString(), rows());
    }
}
