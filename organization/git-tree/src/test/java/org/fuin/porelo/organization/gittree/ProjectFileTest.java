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
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        return new ProjectFile(runner, FetchAccess.NONE, new CommitSigning(Map.of(), files));
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
        return new ProjectFile(runner, FetchAccess.NONE, CommitSigning.agent(socket.toString()));
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
}
