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
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.organization.OrganizationWrites.Written;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The organization's changes, each read back by a fresh reader. Harm guarded: a change that looks
 * made but that every reader refuses, a change asked for again made twice, or one person's id
 * answered by another's commit.
 */
class GitTreeWritesTest {

    @TempDir Path dir;

    private Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, SigningKey> keys = new HashMap<>();

    private final Forge forge = new Forge();

    private static final class Forge implements IdentitySource {
        final Map<String, List<String>> keys = new HashMap<>();
        final Map<String, Boolean> owners = new HashMap<>();

        @Override
        public Optional<Keys> keys(final String login) {
            return Optional.ofNullable(keys.get(login)).map(listed -> new Keys(listed, true));
        }

        @Override
        public Optional<Boolean> owns(final String organisation, final String login) {
            return Optional.ofNullable(owners.get(login));
        }
    }

    @BeforeEach
    void founded() throws Exception {
        repo = Files.createDirectory(dir.resolve("organization"));
        for (final String login : List.of("mia", "alice", "alice-new", "backup", "mallory")) {
            final Path file = dir.resolve(login);
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            keyFiles.put(login, file);
            keys.put(
                    login,
                    new SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test"));
        }
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Organization");
        git("config", "user.email", "organization@example.org");
        git("config", "gpg.format", "ssh");
        git("config", "receive.denyCurrentBranch", "updateInstead");
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("mia")));
        forge.keys.put("alice", List.of(key("alice")));
        write("owners.yml", "owners:\n  - login: mia\n    key: \"" + key("mia") + "\"\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mia"), "commit", "-q", "-S", "-m", "found");
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

    private void write(final String path, final String text) throws IOException {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String tip() {
        return git("rev-parse", "HEAD");
    }

    private String key(final String holder) {
        return PersonCard.keyLine(keys.get(holder));
    }

    private String card(final String login, final String own) {
        return PersonCard.write(
                login,
                login,
                "",
                keys.get(own),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                null);
    }

    private GitTreeWrites as(final String holder) {
        return new GitTreeWrites(
                runner,
                repo.toString(),
                "acme",
                FetchAccess.NONE,
                forge,
                new CommitSigning(Map.of(), keyFiles.get(holder)));
    }

    private Change change(final String login, final String holder, final String id) {
        return new Change(new Acting(login, key(holder)), tip(), id);
    }

    private OrganizationQuestions read() throws IOException {
        return GitTreeStore.at(
                        runner,
                        repo.toString(),
                        "acme",
                        "forge.example",
                        FetchAccess.NONE,
                        dir.resolve("state-" + System.nanoTime()))
                .read(forge);
    }

    private OrganizationQuestions.Person person(final String login) throws IOException {
        return read().getPersons().stream()
                .filter(each -> each.login().equals(login))
                .findFirst()
                .orElseThrow();
    }

    private static void refusedAs(
            final String reason, final org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        Refused.class, refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    private String aliceFiled() throws IOException {
        return as("alice")
                .addCard(change("alice", "alice", "first"), card("alice", "alice"))
                .revision();
    }

    @Test
    void aFirstCardIsReadBackAsTrustedByAFreshReader() throws Exception {
        final Written written =
                as("alice").addCard(change("alice", "alice", "first"), card("alice", "alice"));

        assertThat(written.again()).isFalse();
        assertThat(written.revision()).isEqualTo(tip());
        assertThat(person("alice").state()).isEqualTo(Organization.TRUSTED);
    }

    @Test
    void askedAgainTheChangeIsAnsweredAndNotMadeTwiceThoughTheStoreMovedOn() throws Exception {
        final Change first = change("alice", "alice", "first");
        final String made = as("alice").addCard(first, card("alice", "alice")).revision();
        as("mia").addBackupKey(change("mia", "mia", "other"), null, key("backup"));

        final Written again = as("alice").addCard(first, card("alice", "alice"));

        assertThat(again.again()).as("the id is looked for before the base").isTrue();
        assertThat(again.revision()).isEqualTo(made);
    }

    @Test
    void anotherPersonsCommitUnderTheSameIdIsNotThisPersonsChange() throws Exception {
        as("mia").addBackupKey(change("mia", "mia", "same-id"), null, key("backup"));

        final Written written =
                as("alice").addCard(change("alice", "alice", "same-id"), card("alice", "alice"));

        assertThat(written.again()).isFalse();
        assertThat(person("alice").state()).isEqualTo(Organization.TRUSTED);
    }

    @Test
    void aBaseTheStoreMovedPastIsRefusedAndNothingIsMade() throws Exception {
        final Change late = change("alice", "alice", "late");
        as("mia").addBackupKey(change("mia", "mia", "other"), null, key("backup"));
        final String before = tip();

        refusedAs("moved-on", () -> as("alice").addCard(late, card("alice", "alice")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void aSecondFirstCardIsExists() throws Exception {
        aliceFiled();

        refusedAs(
                "exists",
                () -> as("alice").addCard(change("alice", "alice", "two"), card("alice", "alice")));
    }

    @Test
    void aCardOfAnotherLoginIsUsage() {
        refusedAs(
                "usage",
                () ->
                        as("mallory")
                                .addCard(
                                        change("mallory", "mallory", "x"),
                                        card("alice", "mallory")));
    }

    @Test
    void aCardOfAnotherKeyThanTheActingOneIsUsage() {
        refusedAs(
                "usage",
                () -> as("alice").addCard(change("alice", "alice", "x"), card("alice", "mallory")));
    }

    @Test
    void aFirstCardTheForgeDeniesIsRefusedAndNothingIsPushed() throws Exception {
        forge.keys.put("mallory", List.of(key("alice")));
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("mallory")
                                .addCard(
                                        change("mallory", "mallory", "x"),
                                        card("mallory", "mallory")));

        assertThat(tip()).as("no reader has to refuse what was never filed").isEqualTo(before);
    }

    @Test
    void aCardHandedOnToANewKeyStandsOnANewReader() throws Exception {
        aliceFiled();
        forge.keys.put("alice", List.of(key("alice-new")));
        final String next =
                PersonCard.write(
                        "alice",
                        "alice",
                        "",
                        keys.get("alice-new"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        keys.get("alice"));

        as("alice-new").changeCard(change("alice", "alice-new", "rotate"), next);

        assertThat(person("alice").fingerprint())
                .isEqualTo(PersonCard.fingerprint(key("alice-new")));
    }

    @Test
    void changingACardThatIsNotThereIsNoCard() {
        refusedAs(
                "no-card",
                () ->
                        as("alice")
                                .changeCard(change("alice", "alice", "x"), card("alice", "alice")));
    }

    @Test
    void anOwnersBackupKeyIsReadBackAsABackupAndIsNoOwner() throws Exception {
        as("mia").addBackupKey(change("mia", "mia", "backup"), null, key("backup"));

        final OrganizationQuestions read = read();

        assertThat(read.getRefusals()).isEmpty();
        assertThat(read.getOrganizationOwners())
                .filteredOn(owner -> owner.backup())
                .singleElement()
                .satisfies(
                        owner -> {
                            assertThat(owner.login()).isEqualTo("mia");
                            assertThat(owner.fingerprint())
                                    .isEqualTo(PersonCard.fingerprint(key("backup")));
                        });
    }

    @Test
    void anOwnersBackupIsNotAnybodysToFile() throws Exception {
        aliceFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () -> as("alice").addBackupKey(change("alice", "alice", "x"), null, key("backup")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void anOwnersNameUsedByAnotherKeyIsRefusedByTheWalkBeforeAnythingIsPushed() throws Exception {
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("mallory")
                                .addBackupKey(change("mia", "mallory", "x"), null, key("backup")));

        assertThat(tip()).as("the walk stopped it, with no reader having to").isEqualTo(before);
    }

    @Test
    void aHostAdminsBackupIsReadBackOnTheHost() throws Exception {
        aliceFiled();
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mia"), "commit", "-q", "-S", "-m", "host");

        as("alice").addBackupKey(change("alice", "alice", "backup"), "build", key("backup"));

        assertThat(read().getRefusals()).isEmpty();
        assertThat(Files.readString(repo.resolve("hosts/build/host.yml"))).contains(key("backup"));
    }

    @Test
    void aBackupForAHostThatIsNotThereIsNotFound() {
        refusedAs(
                "not-found",
                () -> as("mia").addBackupKey(change("mia", "mia", "x"), "gone", key("backup")));
    }

    @Test
    void aKeyThatIsNoKeyIsUsage() {
        refusedAs(
                "usage",
                () -> as("mia").addBackupKey(change("mia", "mia", "x"), null, "not a key"));
    }
}
