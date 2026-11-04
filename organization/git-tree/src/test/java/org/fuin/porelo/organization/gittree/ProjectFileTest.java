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
              base_image: "ubuntu:24.04"

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
        assertThat(read.why()).doesNotContain("hunter2");
    }

    @Test
    void aRepositoryThatCannotBeReachedCannotSayAndIsNeverEmpty() throws Exception {
        final ProjectFile.Read read = file().read(dir.resolve("gone").toString(), rows());

        assertThat(read.state()).isEqualTo(ProjectFile.CANNOT_SAY);
        assertThat(read.project()).isNull();
    }

    private static final String ROW =
            TEXT
                    + "  app:\n"
                    + "    upstream: https://forge.example/acme/app.git\n"
                    + "    description: the app\n"
                    + "    issues: [\"B\"]\n"
                    + "    agent: claude\n";

    private Written changeCentral(final String id, final String central) throws IOException {
        return file().changeRow(
                        new Change(new Acting("alice", key("alice")), git("rev-parse", "HEAD"), id),
                        repo.toString(),
                        rows(),
                        "app",
                        null,
                        central,
                        null);
    }

    @Test
    void aChangedRowKeepsAllItDoesNotName() throws Exception {
        commit("project.yml", ROW, "alice");

        changeCentral("give-central", "ssh://git@host.example/foo/app.git");

        final String written = git("show", "HEAD:project.yml");
        assertThat(written)
                .as("what the change named is there")
                .contains("central: ssh://git@host.example/foo/app.git");
        assertThat(written)
                .as("what it did not name stays")
                .contains("upstream: https://forge.example/acme/app.git")
                .contains("description: the app")
                .contains("- B")
                .contains("agent: claude");
    }

    @Test
    void askedAgainUnderItsIdAChangeIsTheSameAndNoSecondCommit() throws Exception {
        commit("project.yml", ROW, "alice");
        final Written first = changeCentral("once", "ssh://git@host.example/foo/app.git");

        final Written again =
                file().changeRow(
                                new Change(new Acting("alice", key("alice")), null, "once"),
                                repo.toString(),
                                rows(),
                                "app",
                                null,
                                "ssh://git@host.example/foo/app.git",
                                null);

        assertThat(again.again()).as("it was made before").isTrue();
        assertThat(again.revision()).isEqualTo(first.revision());
        assertThat(git("rev-parse", "HEAD").strip()).isEqualTo(first.revision());
    }

    @Test
    void anIdOfAnotherChangeIsRefused() throws Exception {
        commit("project.yml", ROW, "alice");
        changeCentral("once", "ssh://git@host.example/foo/app.git");

        assertThatThrownBy(
                        () ->
                                file().changeRow(
                                                new Change(
                                                        new Acting("alice", key("alice")),
                                                        null,
                                                        "once"),
                                                repo.toString(),
                                                rows(),
                                                "app",
                                                null,
                                                "ssh://git@host.example/bar/app.git",
                                                null))
                .as("a retry under that id asks for something else")
                .hasMessageContaining("named another change");
    }

    @Test
    void aCentralEndingInAnotherNameWritesNothing() throws Exception {
        commit("project.yml", ROW, "alice");
        final String before = git("rev-parse", "HEAD");

        assertThatThrownBy(() -> changeCentral("wrong", "ssh://git@host.example/foo/other.git"))
                .as("a host's forge names a repository after its central one")
                .hasMessageContaining("must end in");

        assertThat(git("rev-parse", "HEAD")).isEqualTo(before);
    }

    @Test
    void aChangeAlteringNothingWritesNoCommitAndSaysSo() throws Exception {
        commit("project.yml", ROW, "alice");
        changeCentral("once", "ssh://git@host.example/foo/app.git");
        final String before = git("rev-parse", "HEAD");

        assertThatThrownBy(() -> changeCentral("twice", "ssh://git@host.example/foo/app.git"))
                .as("under a new id, and nothing to do")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.EXISTS))
                .hasMessageContaining("says that already");

        assertThat(git("rev-parse", "HEAD")).isEqualTo(before);
    }

    @Test
    void aRowTheFileDoesNotHoldIsNotFound() throws Exception {
        commit("project.yml", ROW, "alice");

        assertThatThrownBy(
                        () ->
                                file().changeRow(
                                                new Change(
                                                        new Acting("alice", key("alice")),
                                                        git("rev-parse", "HEAD"),
                                                        "nowhere"),
                                                repo.toString(),
                                                rows(),
                                                "other",
                                                null,
                                                "ssh://git@host.example/foo/other.git",
                                                null))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.NOT_FOUND));
    }

    @Test
    void aProposedRowIsOneSignedChangeThatAFreshReaderAccepts() throws Exception {
        final String base = git("rev-parse", "HEAD");
        final Map<String, Object> proposed =
                Map.of(
                        "upstream",
                        UPSTREAM,
                        "review",
                        "optional",
                        "egress",
                        Map.of("domains", List.of("repo.example.org")),
                        "image",
                        Map.of("base_image", "ubuntu:24.04", "snippet", "RUN true"));
        final Written written =
                file().propose(
                                new Change(new Acting("alice", key("alice")), base, "proposal"),
                                repo.toString(),
                                rows(),
                                "app",
                                proposed);
        final ProjectFile.Read fresh = file(dir.resolve("fresh")).read(repo.toString(), rows());
        assertThat(fresh.state())
                .as("a fresh reader verifies the signed file")
                .isEqualTo(ProjectFile.READ);
        final var repository = fresh.project().repository("app");
        assertThat(repository.upstream())
                .as("the proposed upstream survives filing")
                .isEqualTo(UPSTREAM);
        assertThat(repository.egress().domains())
                .as("the detected domains survive filing")
                .containsExactly("repo.example.org");
        assertThat(repository.image()).as("the detected image survives filing").isNotNull();
        assertThat(repository.review())
                .as("the requested review is on the row")
                .isEqualTo("optional");
        assertThat(git("rev-list", "--count", base + "..HEAD"))
                .as("filing makes one commit")
                .isEqualTo("1");
        assertThat(git("diff-tree", "--no-commit-id", "--name-only", "-r", written.revision()))
                .as("filing changes only the project file")
                .isEqualTo("project.yml");
    }

    @Test
    void askingForTheSameProposalWithAnotherMapOrderAnswersTheSameCommit() throws Exception {
        final Map<String, Object> image = new java.util.LinkedHashMap<>();
        image.put("base_image", "ubuntu:24.04");
        image.put("snippet", "RUN true");
        final Written first =
                file().propose(
                                change("alice", "same"),
                                repo.toString(),
                                rows(),
                                "app",
                                Map.of("upstream", UPSTREAM, "image", image));
        final Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("snippet", "RUN true");
        reordered.put("base_image", "ubuntu:24.04");
        final Written again =
                file().propose(
                                change("alice", "same"),
                                repo.toString(),
                                rows(),
                                "app",
                                Map.of("upstream", UPSTREAM, "image", reordered));
        assertThat(again.revision())
                .as("the id names the same proposal regardless of map order")
                .isEqualTo(first.revision());
        assertThat(again.again()).as("a retry files no additional commit").isTrue();
    }

    @Test
    void aProposalAgainstAnOlderRevisionCannotChangeTheFile() throws Exception {
        final String base = git("rev-parse", "HEAD");
        commit("another", "work", "alice");
        final String before = git("rev-parse", "HEAD");
        refusedAs(
                Refused.MOVED_ON,
                () ->
                        file().propose(
                                        new Change(
                                                new Acting("alice", key("alice")), base, "stale"),
                                        repo.toString(),
                                        rows(),
                                        "other",
                                        Map.of("upstream", UPSTREAM)));
        assertThat(git("rev-parse", "HEAD"))
                .as("a stale proposal changes no commit")
                .isEqualTo(before);
    }

    @Test
    void aProposalCannotReplaceAnExistingRepository() throws Exception {
        final String before = git("rev-parse", "HEAD");
        refusedAs(
                Refused.EXISTS,
                () ->
                        file().propose(
                                        change("alice", "exists"),
                                        repo.toString(),
                                        rows(),
                                        "first",
                                        Map.of("upstream", UPSTREAM)));
        assertThat(git("rev-parse", "HEAD"))
                .as("an existing row is not overwritten")
                .isEqualTo(before);
    }

    @Test
    void aProposalSignedByADeveloperCannotChangeProjectPolicy() throws Exception {
        final String before = git("rev-parse", "HEAD");
        refusedAs(
                Refused.NOT_ALLOWED,
                () ->
                        file().propose(
                                        change("carol", "denied"),
                                        repo.toString(),
                                        rows(),
                                        "other",
                                        Map.of("upstream", UPSTREAM)));
        assertThat(git("rev-parse", "HEAD"))
                .as("only an admin can file the proposal")
                .isEqualTo(before);
    }

    @Test
    void anAdminNamesARepositoryAndTheFreshReadSeesItBesideTheOthers() throws Exception {
        final Written written =
                file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);

        assertThat(written.revision()).isEqualTo(git("rev-parse", "HEAD"));
        assertThat(written.again()).isFalse();
        final ProjectFile.Read read = read();
        assertThat(read.state()).as(String.valueOf(read.why())).isEqualTo(ProjectFile.READ);
        assertThat(read.project().repositories())
                .extracting("name")
                .containsExactlyInAnyOrder("first", "app");
        assertThat(read.project().name())
                .as("what the change did not name stays")
                .isEqualTo("demo");
        assertThat(read.project().description()).isEqualTo("A project");
    }

    @Test
    void anAdminTakesARepositoryOut() throws Exception {
        file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);

        file().remove(change("alice", "two"), repo.toString(), rows(), "first");

        assertThat(read().project().repositories()).extracting("name").containsExactly("app");
    }

    @Test
    void askedAgainUnderItsIdTheSameCommitIsAnswered() throws Exception {
        final Written first =
                file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);

        final Written again =
                file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);

        assertThat(again.revision()).isEqualTo(first.revision());
        assertThat(again.again()).isTrue();
    }

    @Test
    void aNameThereAlreadyIsExistsAndOneThatIsNotIsNotFound() {
        refusedAs(
                Refused.EXISTS,
                () -> file().add(change("alice", "x"), repo.toString(), rows(), "first", UPSTREAM));
        refusedAs(
                Refused.NOT_FOUND,
                () -> file().remove(change("alice", "y"), repo.toString(), rows(), "none"));
    }

    @Test
    void anAddressThatIsNoneIsUsage() {
        refusedAs(
                Refused.USAGE,
                () ->
                        file().add(
                                        change("alice", "x"),
                                        repo.toString(),
                                        rows(),
                                        "app",
                                        "no address"));
    }

    @Test
    void aNameTheFileCannotHoldIsUsageAndNothingIsCommitted() {
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.USAGE,
                () -> file().add(change("alice", "x"), repo.toString(), rows(), "../up", UPSTREAM));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void aBaseTheRepositoryMovedOnFromIsMovedOn() {
        final Change stale = new Change(new Acting("alice", key("alice")), "a".repeat(40), "x");

        refusedAs(
                Refused.MOVED_ON,
                () -> file().add(stale, repo.toString(), rows(), "app", UPSTREAM));
    }

    @Test
    void aChangeOverARefusedFileIsRefusedStateRatherThanBlessingIt() throws Exception {
        commit("project.yml", TEXT.replace("first", "theirs"), "mallory");

        refusedAs(
                Refused.REFUSED_STATE,
                () -> file().add(change("alice", "x"), repo.toString(), rows(), "app", UPSTREAM));
    }

    @Test
    void aRepositoryThatDoesNotTakeThePushSaysSoRatherThanThatItIsMade() throws Exception {
        git("config", "receive.denyCurrentBranch", "refuse");

        assertThatThrownBy(
                        () ->
                                file().add(
                                                change("alice", "x"),
                                                repo.toString(),
                                                rows(),
                                                "app",
                                                UPSTREAM))
                .isInstanceOf(GitTreeStore.Unreachable.class);
    }

    @Test
    void anotherPersonsIdIsNotTheirAnswer() throws Exception {
        final Written first =
                file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);
        final List<SigningKey> bothAdmins = List.of(row("alice", "admin"), row("gone", "admin"));

        final Written theirs =
                file().add(change("gone", "one"), repo.toString(), bothAdmins, "other", UPSTREAM);

        assertThat(theirs.revision())
                .as("anybody with write access can write the words of an id into a commit")
                .isNotEqualTo(first.revision());
        assertThat(theirs.again()).isFalse();
    }

    @Test
    void anIdAskedAgainForAnotherChangeIsRefusedRatherThanAnsweredAsMade() throws Exception {
        file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.USAGE,
                () ->
                        file().add(
                                        change("alice", "one"),
                                        repo.toString(),
                                        rows(),
                                        "other",
                                        UPSTREAM));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
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
        final Path socket = dir.resolve("agent.sock");
        try (org.fuin.porelo.vault.SshAgentServer agent =
                new org.fuin.porelo.vault.SshAgentServer(socket, List.of(keys.get("alice")))) {
            Thread.ofVirtual().start(agent);

            throughAgent(socket)
                    .add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);
        }

        assertThat(read().state())
                .as("a reader verifies what the agent signed")
                .isEqualTo(ProjectFile.READ);
        assertThat(read().project().repositories())
                .extracting("name")
                .containsExactlyInAnyOrder("first", "app");
    }

    @Test
    void anAgentThatDoesNotServeTheKeyIsNoKeyAndNothingIsPushed() throws Exception {
        final String tip = git("rev-parse", "HEAD");
        final Path socket = dir.resolve("agent.sock");
        try (org.fuin.porelo.vault.SshAgentServer agent =
                new org.fuin.porelo.vault.SshAgentServer(socket, List.of(keys.get("carol")))) {
            Thread.ofVirtual().start(agent);

            refusedAs(
                    Refused.NO_KEY,
                    () ->
                            throughAgent(socket)
                                    .add(
                                            change("alice", "one"),
                                            repo.toString(),
                                            rows(),
                                            "app",
                                            UPSTREAM));
        }

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void noAgentAtAllIsNoKey() {
        refusedAs(
                Refused.NO_KEY,
                () ->
                        throughAgent(dir.resolve("no-agent.sock"))
                                .add(
                                        change("alice", "one"),
                                        repo.toString(),
                                        rows(),
                                        "app",
                                        UPSTREAM));
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

        assertThat(again.revision()).isEqualTo(first.revision());
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
                .isEqualTo(TEXT + "# signed anew by again\n");
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

        assertThat(again.revision()).isEqualTo(first.revision());
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
    void aFileASignatureWouldTakeOverTheLimitIsRefusedAndNothingPushed() throws Exception {
        final String padding = "# " + "x".repeat(1024 * 1024 - TEXT.length() - 3) + "\n";
        commit("project.yml", TEXT + padding, "mallory");
        final String tip = git("rev-parse", "HEAD");
        assertThat(Files.size(repo.resolve("project.yml"))).isEqualTo(1024 * 1024);

        assertThat(file().look(repo.toString(), rows(), "demo").blocked()).contains("limit");
        refusedAs(
                Refused.REFUSED_STATE,
                () ->
                        file().sign(
                                        changeOn("alice", "again", tip),
                                        repo.toString(),
                                        rows(),
                                        "demo"));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
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
                        "base_image: \"ubuntu:24.04\"",
                        "base_image: \"ubuntu:24.04\"\n  snippet_file: \"gone.txt\""),
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
        final List<SigningKey> theirs =
                List.of(
                        row("alice", "admin"),
                        new SigningKey(
                                "locked",
                                "admin",
                                key,
                                LocalDate.of(2000, 1, 1),
                                LocalDate.of(2999, 1, 1),
                                null));
        final ProjectFile file =
                new ProjectFile(
                        runner,
                        FetchAccess.NONE,
                        new CommitSigning(Map.of(), Map.of(key, locked)),
                        dir.resolve("memory"));

        refusedAs(
                Refused.NO_KEY,
                () ->
                        file.add(
                                new Change(new Acting("locked", key), null, "one"),
                                repo.toString(),
                                theirs,
                                "app",
                                UPSTREAM));
    }

    @Test
    void aKeyThatIsNoAdminsChangesNothingAndIsNotAnsweredAsDone() throws Exception {
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.NOT_ALLOWED,
                () -> file().add(change("carol", "x"), repo.toString(), rows(), "app", UPSTREAM));

        assertThat(git("rev-parse", "HEAD"))
                .as("the next reader would refuse the file for a change answered as made")
                .isEqualTo(tip);
    }

    @Test
    void aStrangersCommitCarryingAnAdminsIdIsNoAnswerToThatId() throws Exception {
        file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM);
        Files.writeString(repo.resolve("project.yml"), TEXT.replace("first", "theirs"));
        git("add", "-A");
        git("commit", "-q", "-m", "Name a repository\n\nPorelo-Change-Id: one\n");

        refusedAs(
                Refused.REFUSED_STATE,
                () -> file().add(change("alice", "one"), repo.toString(), rows(), "app", UPSTREAM));
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

    @Test
    void anAdminNamesARepositoryWithItsCentralSourceAndReview() throws Exception {
        file().add(
                        change("alice", "one"),
                        repo.toString(),
                        rows(),
                        "app",
                        "git@forge.example:demo/app.git",
                        "git@github.com:acme/app.git",
                        "optional");

        final org.fuin.porelo.core.project.Repository named =
                readBack().project().repositories().stream()
                        .filter(each -> each.name().equals("app"))
                        .findFirst()
                        .orElseThrow();
        assertThat(named.central()).isEqualTo("git@github.com:acme/app.git");
        assertThat(named.review()).isEqualTo("optional");
    }

    @Test
    void aCentralSourceOfAnotherNameMakesNoChange() throws Exception {
        final String tip = git("rev-parse", "HEAD");

        refusedAs(
                Refused.USAGE,
                () ->
                        file().add(
                                        change("alice", "one"),
                                        repo.toString(),
                                        rows(),
                                        "app",
                                        "git@forge.example:demo/app.git",
                                        "git@github.com:acme/other.git",
                                        null));

        assertThat(git("rev-parse", "HEAD")).isEqualTo(tip);
    }

    @Test
    void anAdminSaysAReviewForTheProjectAndForARepositoryAndItStaysChangeable() throws Exception {
        file().review(change("alice", "one"), repo.toString(), rows(), null, "optional");
        file().review(change("alice", "two"), repo.toString(), rows(), "first", "optional");

        final org.fuin.porelo.core.project.Project read = readBack().project();
        assertThat(read.review()).isEqualTo("optional");
        assertThat(read.repositories().getFirst().review()).isEqualTo("optional");

        file().review(change("alice", "three"), repo.toString(), rows(), "first", "required");

        assertThat(readBack().project().repositories().getFirst().review()).isEqualTo("required");
    }

    @Test
    void aReviewForARepositoryNotNamedIsNotFound() throws Exception {
        refusedAs(
                Refused.NOT_FOUND,
                () ->
                        file().review(
                                        change("alice", "one"),
                                        repo.toString(),
                                        rows(),
                                        "x",
                                        "optional"));
    }

    @Test
    void aReviewIsNoOnesButAnAdmins() throws Exception {
        refusedAs(
                Refused.NOT_ALLOWED,
                () ->
                        file().review(
                                        change("carol", "one"),
                                        repo.toString(),
                                        rows(),
                                        null,
                                        "optional"));
    }
}
