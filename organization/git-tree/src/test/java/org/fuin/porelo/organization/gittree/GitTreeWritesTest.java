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
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.organization.OrganizationWrites.Written;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
        for (final String login :
                List.of("mia", "alice", "alice-new", "backup", "mallory", "carol", "bob")) {
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
        return with(FetchAccess.NONE);
    }

    private GitTreeWrites with(final FetchAccess access) {
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        return new GitTreeWrites(
                runner, repo.toString(), "acme", access, forge, new CommitSigning(Map.of(), files));
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

    private void secondOwner() throws IOException {
        write(
                "owners.yml",
                "owners:\n  - login: mia\n    key: \""
                        + key("mia")
                        + "\"\n  - login: carol\n    key: \""
                        + key("carol")
                        + "\"\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mia"), "commit", "-q", "-S", "-m", "owner");
    }

    private void hostOfAliceAndCarol() throws IOException {
        forge.keys.put("carol", List.of(key("carol")));
        as("carol").addCard(change("carol", "carol", "carol"), card("carol", "carol"));
        aliceFiled();
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mia"), "commit", "-q", "-S", "-m", "host");
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: carol\nprojects: []\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("alice"), "commit", "-q", "-S", "-m", "admin");
    }

    private boolean readerHas(final String login) throws IOException {
        return read().getPersons().stream()
                .anyMatch(
                        each ->
                                each.login().equals(login)
                                        && !Organization.REFUSED.equals(each.state()));
    }

    @Test
    void aWithdrawnCardIsGoneAndItsKeyCannotFileItAgain() throws Exception {
        aliceFiled();

        as("alice").revokeCard(change("alice", "alice", "go"), "alice");

        assertThat(readerHas("alice")).isFalse();
        refusedAs(
                "not-allowed",
                () ->
                        as("alice")
                                .addCard(change("alice", "alice", "back"), card("alice", "alice")));
    }

    @Test
    void anOwnerWithdrawsAnothersCard() throws Exception {
        aliceFiled();

        as("mia").revokeCard(change("mia", "mia", "go"), "alice");

        assertThat(readerHas("alice")).isFalse();
    }

    @Test
    void aStrangerCannotWithdrawACard() throws Exception {
        aliceFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () -> as("mallory").revokeCard(change("mallory", "mallory", "x"), "alice"));

        assertThat(tip()).isEqualTo(before);
        assertThat(readerHas("alice")).isTrue();
    }

    @Test
    void withdrawingACardThatIsNotThereIsNotFound() {
        refusedAs("not-found", () -> as("mia").revokeCard(change("mia", "mia", "x"), "alice"));
    }

    @Test
    void theOnlyOwnersCardCannotBeWithdrawn() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));

        refusedAs(
                "last-of-its-kind", () -> as("mia").revokeCard(change("mia", "mia", "go"), "mia"));
    }

    @Test
    void oneOfTwoOwnersCardsCanBeWithdrawn() throws Exception {
        secondOwner();
        forge.keys.put("carol", List.of(key("carol")));
        as("carol").addCard(change("carol", "carol", "card"), card("carol", "carol"));

        as("mia").revokeCard(change("mia", "mia", "go"), "carol");

        assertThat(readerHas("carol")).isFalse();
    }

    @Test
    void anOwnersReplacementStandsAndTheLostKeySignsNothingMore() throws Exception {
        aliceFiled();
        forge.keys.put("alice", List.of(key("alice-new")));

        as("mia").replaceCard(change("mia", "mia", "swap"), "alice", card("alice", "alice-new"));

        assertThat(person("alice").fingerprint())
                .isEqualTo(PersonCard.fingerprint(key("alice-new")));
        assertThat(read().getRefusals()).as("past commits of the old key stay accepted").isEmpty();
        refusedAs(
                "not-allowed",
                () -> as("alice").addBackupKey(change("alice", "alice", "x"), null, key("backup")));
    }

    @Test
    void onlyAnOwnerReplacesACard() throws Exception {
        aliceFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("mallory")
                                .replaceCard(
                                        change("mallory", "mallory", "x"),
                                        "alice",
                                        card("alice", "mallory")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void replacingACardOfAnotherLoginIsUsage() throws Exception {
        aliceFiled();

        refusedAs(
                "usage",
                () ->
                        as("mia")
                                .replaceCard(
                                        change("mia", "mia", "x"),
                                        "alice",
                                        card("mia", "alice-new")));
    }

    @Test
    void replacingACardThatIsNotThereIsNotFound() {
        refusedAs(
                "not-found",
                () ->
                        as("mia")
                                .replaceCard(
                                        change("mia", "mia", "x"),
                                        "alice",
                                        card("alice", "alice")));
    }

    private String handedOn(final String login, final String own, final String from) {
        return PersonCard.write(
                login,
                login,
                "",
                keys.get(own),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                keys.get(from));
    }

    @Test
    void anOwnerHandingOnAKeyMakesTheNewKeyTheOwnerAndTheOldOneNone() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));
        forge.keys.put("mia", List.of(key("mia"), key("carol")));
        final String before = tip();

        final Written written =
                as("carol")
                        .changeCard(
                                change("mia", "carol", "hand-on"), handedOn("mia", "carol", "mia"));

        assertThat(git("rev-list", "--count", before + "..HEAD")).isEqualTo("2");
        assertThat(read().getRefusals()).isEmpty();
        assertThat(read().getOrganizationOwners())
                .singleElement()
                .satisfies(owner -> assertThat(owner.key()).isEqualTo(key("carol")));
        final Written again =
                as("carol")
                        .changeCard(
                                change("mia", "carol", "hand-on"), handedOn("mia", "carol", "mia"));
        assertThat(again.again()).isTrue();
        assertThat(again.revision()).isEqualTo(written.revision());
        refusedAs(
                "not-allowed",
                () ->
                        as("mia")
                                .addBackupKey(
                                        new Change(new Acting("mia", key("mia")), tip(), "old"),
                                        null,
                                        key("backup")));
    }

    @Test
    void aPushTheRepositoryRefusesLeavesNeitherCommitOfAChange() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));
        forge.keys.put("mia", List.of(key("mia"), key("carol")));
        final Path hook = repo.resolve(".git/hooks/pre-receive");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);
        final String before = tip();

        assertThatThrownBy(
                        () ->
                                as("carol")
                                        .changeCard(
                                                change("mia", "carol", "hand-on"),
                                                handedOn("mia", "carol", "mia")))
                .as("the same id may be asked again")
                .isInstanceOf(GitTreeStore.Unreachable.class);

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void aStoreThatMovesOnWhileTheChangeIsMadeAnswersMovedOn() throws Exception {
        final Change change = change("alice", "alice", "first");
        final int[] lent = {0};
        final FetchAccess racing =
                address -> {
                    if (++lent[0] == 2) {
                        try {
                            write("elsewhere.txt", "someone else's commit");
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                        git("add", "-A");
                        git("commit", "-q", "-m", "elsewhere");
                    }
                    return FetchAccess.Lease.EMPTY;
                };

        refusedAs("moved-on", () -> with(racing).addCard(change, card("alice", "alice")));
    }

    @Test
    void aPushThatLandedThoughGitReportedFailureIsTheChangeMade() throws Exception {
        final CommandRunner lostAnswer =
                command -> {
                    final CommandResult result = runner.run(command);
                    return command.arguments().contains("push")
                            ? new CommandResult(command, 1, "", "the connection was lost")
                            : result;
                };
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        final GitTreeWrites writes =
                new GitTreeWrites(
                        lostAnswer,
                        repo.toString(),
                        "acme",
                        FetchAccess.NONE,
                        forge,
                        new CommitSigning(Map.of(), files));

        final Written written =
                writes.addCard(change("alice", "alice", "first"), card("alice", "alice"));

        assertThat(written.revision()).isEqualTo(tip());
        assertThat(written.again()).isFalse();
    }

    @Test
    void aHostBackupNamingAPredecessorIsNoReplacement() throws Exception {
        hostOfAliceAndCarol();
        as("alice").addBackupKey(change("alice", "alice", "backup"), "build", key("backup"));
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("backup")
                                .replaceKeyWithBackup(
                                        change("alice", "backup", "swap"),
                                        "build",
                                        handedOn("alice", "alice-new", "mallory")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void aKeyThatBacksNoOwnerCannotPutItselfInAnOwnersPlace() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("mallory")
                                .replaceKeyWithBackup(
                                        change("mia", "mallory", "take"),
                                        null,
                                        card("mia", "mallory")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void anOwnersBackupKeySignsNoCardOnItsOwn() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));
        as("mia").addBackupKey(change("mia", "mia", "backup"), null, key("backup"));
        forge.keys.put("mia", List.of(key("mia"), key("carol")));
        write("people/mia.card", card("mia", "carol"));
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("backup"), "commit", "-q", "-S", "-m", "card");

        assertThat(read().getRefusals())
                .as("without the owners file changing, the old key would stay an owner's")
                .anyMatch(each -> each.path().equals("people/mia.card"));
    }

    @Test
    void anOwnersBackupKeyReplacesTheirKeyWhichThenIsNoOwner() throws Exception {
        as("mia").addCard(change("mia", "mia", "card"), card("mia", "mia"));
        as("mia").addBackupKey(change("mia", "mia", "backup"), null, key("backup"));
        forge.keys.put("mia", List.of(key("mia"), key("carol")));

        as("backup")
                .replaceKeyWithBackup(change("mia", "backup", "swap"), null, card("mia", "carol"));

        final OrganizationQuestions read = read();
        assertThat(read.getRefusals()).isEmpty();
        assertThat(person("mia").fingerprint()).isEqualTo(PersonCard.fingerprint(key("carol")));
        assertThat(read.getOrganizationOwners())
                .filteredOn(owner -> !owner.backup())
                .singleElement()
                .satisfies(owner -> assertThat(owner.key()).isEqualTo(key("carol")));
    }

    @Test
    void aHostAdminsBackupKeyReplacesTheirCard() throws Exception {
        hostOfAliceAndCarol();
        as("alice").addBackupKey(change("alice", "alice", "backup"), "build", key("backup"));
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));

        as("backup")
                .replaceKeyWithBackup(
                        change("alice", "backup", "swap"), "build", card("alice", "alice-new"));

        assertThat(person("alice").fingerprint())
                .isEqualTo(PersonCard.fingerprint(key("alice-new")));
    }

    @Test
    void aBackupKeyReplacesOnlyTheCardOfWhoeverFiledIt() throws Exception {
        hostOfAliceAndCarol();
        as("alice").addBackupKey(change("alice", "alice", "backup"), "build", key("backup"));
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("backup")
                                .replaceKeyWithBackup(
                                        change("carol", "backup", "swap"),
                                        "build",
                                        card("carol", "mallory")));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void aHostAdminNamingABackupForAnotherAdminIsRefusedByEveryReader() throws Exception {
        hostOfAliceAndCarol();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: carol\n  - login: carol\n    key: \""
                        + key("mallory")
                        + "\"\n    backup: true\nprojects: []\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("alice"), "commit", "-q", "-S", "-m", "plant");

        assertThat(read().getRefusals())
                .as("that key could otherwise take carol's card")
                .anyMatch(each -> each.path().equals("hosts/build/host.yml"));
        refusedAs(
                "not-allowed",
                () ->
                        as("mallory")
                                .replaceKeyWithBackup(
                                        change("carol", "mallory", "take"),
                                        "build",
                                        card("carol", "alice-new")));
    }

    private void carolFiled() throws IOException {
        forge.keys.put("carol", List.of(key("carol")));
        as("carol").addCard(change("carol", "carol", "carol-card"), card("carol", "carol"));
    }

    private void demo() throws IOException {
        aliceFiled();
        as("mia").addProject(change("mia", "mia", "demo"), "demo", "alice");
    }

    private void build() throws IOException {
        aliceFiled();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
    }

    @Test
    void anOwnerIsAddedWithTheKeyOnTheirCardAndMayAct() throws Exception {
        aliceFiled();

        as("mia").addOrganizationOwner(change("mia", "mia", "owner"), "alice");

        assertThat(read().isOrganizationOwner("alice")).isTrue();
        as("alice").addHost(change("alice", "alice", "host"), "build", "alice");
        assertThat(read().getRefusals()).isEmpty();
    }

    @Test
    void anOwnerWithNoCardOrTwiceIsRefused() throws Exception {
        refusedAs(
                "no-card",
                () -> as("mia").addOrganizationOwner(change("mia", "mia", "x"), "alice"));
        aliceFiled();
        as("mia").addOrganizationOwner(change("mia", "mia", "owner"), "alice");

        refusedAs(
                "exists", () -> as("mia").addOrganizationOwner(change("mia", "mia", "y"), "alice"));
    }

    @Test
    void onlyAnOwnerAddsAnOwner() throws Exception {
        aliceFiled();
        carolFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () -> as("alice").addOrganizationOwner(change("alice", "alice", "x"), "carol"));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void theLastOwnerIsNotRemovedAndABackupKeyDoesNotCountAsOne() throws Exception {
        as("mia").addBackupKey(change("mia", "mia", "backup"), null, key("backup"));

        refusedAs(
                "last-of-its-kind",
                () -> as("mia").removeOrganizationOwner(change("mia", "mia", "x"), "mia"));
    }

    @Test
    void aRemovedOwnerGoesWithTheirBackupKeys() throws Exception {
        aliceFiled();
        as("mia").addOrganizationOwner(change("mia", "mia", "owner"), "alice");
        as("alice").addBackupKey(change("alice", "alice", "backup"), null, key("backup"));

        as("mia").removeOrganizationOwner(change("mia", "mia", "off"), "alice");

        assertThat(read().getOrganizationOwners())
                .as("a backup key left behind could still take the place")
                .allMatch(owner -> owner.login().equals("mia"));
    }

    @Test
    void removingSomeoneWhoIsNoOwnerIsNotFound() {
        refusedAs(
                "not-found",
                () -> as("mia").removeOrganizationOwner(change("mia", "mia", "x"), "alice"));
    }

    @Test
    void aProjectIsAddedWithItsFirstAdminAsAMember() throws Exception {
        demo();

        final OrganizationQuestions read = read();
        assertThat(read.getRefusals()).isEmpty();
        assertThat(read.isProjectMember("demo", "alice")).contains("admin");
    }

    @Test
    void aProjectNamedTheReservedNameOrTwiceOrWithNoCardIsRefused() throws Exception {
        refusedAs(
                "reserved-name",
                () -> as("mia").addProject(change("mia", "mia", "a"), "default", "mia"));
        refusedAs(
                "no-card", () -> as("mia").addProject(change("mia", "mia", "b"), "demo", "alice"));
        refusedAs(
                "usage",
                () -> as("mia").addProject(change("mia", "mia", "c"), "not a name!", "mia"));
        demo();

        refusedAs("exists", () -> as("mia").addProject(change("mia", "mia", "d"), "demo", "alice"));
    }

    @Test
    void onlyAnOwnerAddsAProject() throws Exception {
        aliceFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () -> as("alice").addProject(change("alice", "alice", "x"), "demo", "alice"));

        assertThat(tip()).isEqualTo(before);
    }

    @Test
    void aProjectRemovedGoesWithAllItsFilesAndItsHost() throws Exception {
        demo();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        as("alice")
                .changeProjectRepository(
                        change("alice", "alice", "address"),
                        "demo",
                        "ssh://git@forge.example/acme/demo");
        as("alice").addProjectToHost(change("alice", "alice", "carry"), "build", "demo");

        as("mia").removeProject(change("mia", "mia", "off"), "demo");

        final OrganizationQuestions read = read();
        assertThat(read.getRefusals()).isEmpty();
        assertThat(read.getProjects()).isEmpty();
        assertThat(read.hostOf("demo")).isEmpty();
        assertThat(repo.resolve("projects/demo")).doesNotExist();
    }

    @Test
    void removingAProjectThatIsNotThereIsNotFound() {
        refusedAs("not-found", () -> as("mia").removeProject(change("mia", "mia", "x"), "demo"));
    }

    @Test
    void aNewFirstAdminBecomesAnAdminMember() throws Exception {
        demo();
        carolFiled();

        as("mia").changeProjectAdmin(change("mia", "mia", "admin"), "demo", "carol");

        final OrganizationQuestions read = read();
        assertThat(read.getRefusals()).isEmpty();
        assertThat(read.isProjectMember("demo", "carol")).contains("admin");
        assertThat(read.isProjectMember("demo", "alice"))
                .as("an admin stays one")
                .contains("admin");
    }

    @Test
    void aFirstAdminNamedWhoIsAlreadyAMemberIsListedOnce() throws Exception {
        demo();
        carolFiled();
        as("alice").addProjectMember(change("alice", "alice", "add"), "demo", "carol", "developer");

        as("mia").changeProjectAdmin(change("mia", "mia", "admin"), "demo", "carol");

        assertThat(read().getRefusals()).isEmpty();
        assertThat(read().isProjectMember("demo", "carol")).contains("admin");
    }

    @Test
    void aProjectsAddressIsItsAdminsToFileAndAnAddressThatIsNoneIsUsage() throws Exception {
        demo();
        final String address = "ssh://git@forge.example/acme/demo";

        refusedAs(
                "not-allowed",
                () ->
                        as("mia")
                                .changeProjectRepository(
                                        change("mia", "mia", "x"), "demo", address));
        refusedAs(
                "usage",
                () ->
                        as("alice")
                                .changeProjectRepository(
                                        change("alice", "alice", "y"), "demo", "no\"address"));

        as("alice").changeProjectRepository(change("alice", "alice", "z"), "demo", address);

        assertThat(read().getProjectRepository("demo")).contains(address);
    }

    /** A host could only wait for such a project, with a token that cannot help. */
    @Test
    void aProjectNeverOpenedGoesOnNoHostAndTheWordsNameOpeningIt() throws Exception {
        demo();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        final String before = tip();

        assertThatThrownBy(
                        () ->
                                as("alice")
                                        .addProjectToHost(
                                                change("alice", "alice", "c"), "build", "demo"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        ex -> {
                            assertThat(ex.reason()).isEqualTo("not-opened");
                            assertThat(ex.getMessage())
                                    .as("project address is refused here too, so it is not named")
                                    .contains("porelo project create-repository demo")
                                    .doesNotContain("project address");
                        });
        assertThat(tip()).as("nothing was written").isEqualTo(before);
    }

    /** A new reader judges from the start, so the address a lost key filed counts as none. */
    @Test
    void aProjectWhoseAddressStandsRefusedGoesOnNoHostAndTheWordsNameFilingItAgain()
            throws Exception {
        demo();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        as("alice")
                .changeProjectRepository(
                        change("alice", "alice", "open"),
                        "demo",
                        "ssh://git@forge.example/acme/demo");
        forge.keys.put("alice", List.of(key("alice-new")));
        as("mia").replaceCard(change("mia", "mia", "swap"), "alice", card("alice", "alice-new"));
        final String before = tip();

        assertThatThrownBy(
                        () ->
                                as("alice-new")
                                        .addProjectToHost(
                                                change("alice", "alice-new", "c"), "build", "demo"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        ex -> {
                            assertThat(ex.reason()).isEqualTo("refused-state");
                            assertThat(ex.getMessage()).contains("porelo project address demo");
                        });
        assertThat(tip()).as("nothing was written").isEqualTo(before);
    }

    /** A lost key's address stays refused for a new reader until an admin files it again. */
    @Test
    void anAddressALostKeyFiledIsFiledAgainByANewAdmin() throws Exception {
        demo();
        final String address = "ssh://git@forge.example/acme/demo";
        as("alice").changeProjectRepository(change("alice", "alice", "open"), "demo", address);
        forge.keys.put("alice", List.of(key("alice-new")));
        as("mia").replaceCard(change("mia", "mia", "swap"), "alice", card("alice", "alice-new"));
        assertThat(read().getProjectRepository("demo")).as("refused for a new reader").isEmpty();
        assertThat(read().getRefusals())
                .extracting(Organization.Refusal::path)
                .contains("projects/demo/repository.yml");

        as("alice-new")
                .changeProjectRepository(change("alice", "alice-new", "again"), "demo", address);

        assertThat(read().getProjectRepository("demo")).contains(address);
        assertThat(read().getRefusals())
                .extracting(Organization.Refusal::path)
                .doesNotContain("projects/demo/repository.yml");
    }

    @Test
    void aMemberIsAddedAndChangedAndLockedAndRemovedAndEveryReaderAgrees() throws Exception {
        demo();
        carolFiled();

        as("alice").addProjectMember(change("alice", "alice", "add"), "demo", "carol", "developer");
        assertThat(read().isProjectMember("demo", "carol")).contains("developer");

        as("alice")
                .changeProjectMemberRole(
                        change("alice", "alice", "role"), "demo", "carol", "admin");
        assertThat(read().isProjectMember("demo", "carol")).contains("admin");

        as("alice").lockProjectMember(change("alice", "alice", "lock"), "demo", "carol");
        assertThat(read().getProjectMembers("demo").orElseThrow())
                .filteredOn(each -> each.login().equals("carol"))
                .singleElement()
                .satisfies(each -> assertThat(each.locked()).isTrue());

        as("alice").unlockProjectMember(change("alice", "alice", "unlock"), "demo", "carol");
        assertThat(read().getProjectMembers("demo").orElseThrow())
                .filteredOn(each -> each.login().equals("carol"))
                .singleElement()
                .satisfies(each -> assertThat(each.locked()).isFalse());

        as("alice").removeProjectMember(change("alice", "alice", "off"), "demo", "carol");
        assertThat(read().isProjectMember("demo", "carol")).isEmpty();
        assertThat(read().getRefusals()).isEmpty();
    }

    @Test
    void anOwnerNamesAnAdminButNoDeveloper() throws Exception {
        demo();
        carolFiled();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () ->
                        as("mia")
                                .addProjectMember(
                                        change("mia", "mia", "x"), "demo", "carol", "developer"));

        assertThat(tip()).isEqualTo(before);
        as("mia").addProjectMember(change("mia", "mia", "y"), "demo", "carol", "admin");
        assertThat(read().isProjectMember("demo", "carol")).contains("admin");
    }

    @Test
    void theLastAdminWhoMayActStaysForItsAdminsButNotForAnOwner() throws Exception {
        demo();

        refusedAs(
                "last-of-its-kind",
                () ->
                        as("alice")
                                .removeProjectMember(
                                        change("alice", "alice", "a"), "demo", "alice"));
        refusedAs(
                "last-of-its-kind",
                () ->
                        as("alice")
                                .lockProjectMember(change("alice", "alice", "b"), "demo", "alice"));
        refusedAs(
                "last-of-its-kind",
                () ->
                        as("alice")
                                .changeProjectMemberRole(
                                        change("alice", "alice", "c"),
                                        "demo",
                                        "alice",
                                        "developer"));

        as("mia").removeProjectMember(change("mia", "mia", "d"), "demo", "alice");

        assertThat(read().isProjectMember("demo", "alice")).isEmpty();
    }

    @Test
    void memberRefusalsAreNamed() throws Exception {
        demo();
        carolFiled();

        refusedAs(
                "usage",
                () ->
                        as("alice")
                                .addProjectMember(
                                        change("alice", "alice", "a"), "demo", "carol", "boss"));
        refusedAs(
                "no-card",
                () ->
                        as("alice")
                                .addProjectMember(
                                        change("alice", "alice", "b"),
                                        "demo",
                                        "mallory",
                                        "developer"));
        refusedAs(
                "exists",
                () ->
                        as("alice")
                                .addProjectMember(
                                        change("alice", "alice", "c"), "demo", "alice", "admin"));
        refusedAs(
                "not-found",
                () ->
                        as("alice")
                                .removeProjectMember(
                                        change("alice", "alice", "d"), "demo", "carol"));
        refusedAs(
                "not-found",
                () ->
                        as("alice")
                                .addProjectMember(
                                        change("alice", "alice", "e"), "gone", "carol", "admin"));
    }

    @Test
    void aHostIsAddedByAnOwnerAndChangedByItsAdmins() throws Exception {
        build();
        carolFiled();

        refusedAs("exists", () -> as("mia").addHost(change("mia", "mia", "a"), "build", "alice"));
        refusedAs(
                "no-card", () -> as("mia").addHost(change("mia", "mia", "b"), "other", "mallory"));
        refusedAs("usage", () -> as("mia").addHost(change("mia", "mia", "c"), "No Name", "alice"));
        refusedAs(
                "not-allowed",
                () -> as("alice").addHost(change("alice", "alice", "d"), "other", "alice"));
        refusedAs(
                "not-allowed",
                () -> as("mia").addHostAdmin(change("mia", "mia", "e"), "build", "carol"));

        as("alice").addHostAdmin(change("alice", "alice", "f"), "build", "carol");

        assertThat(read().isHostAdmin("build", "carol")).isTrue();
        assertThat(read().getRefusals()).isEmpty();
        refusedAs(
                "exists",
                () -> as("alice").addHostAdmin(change("alice", "alice", "g"), "build", "carol"));
    }

    @Test
    void aHostsAdminGoesWithTheirBackupsAndTheLastStays() throws Exception {
        build();
        carolFiled();
        as("alice").addHostAdmin(change("alice", "alice", "add"), "build", "carol");
        as("carol").addBackupKey(change("carol", "carol", "backup"), "build", key("backup"));

        as("alice").removeHostAdmin(change("alice", "alice", "off"), "build", "carol");

        assertThat(read().isHostAdmin("build", "carol")).isFalse();
        assertThat(Files.readString(repo.resolve("hosts/build/host.yml")))
                .as("a backup left behind could still take carol's card")
                .doesNotContain(key("backup"));
        refusedAs(
                "last-of-its-kind",
                () -> as("alice").removeHostAdmin(change("alice", "alice", "x"), "build", "alice"));
        refusedAs(
                "not-found",
                () -> as("alice").removeHostAdmin(change("alice", "alice", "y"), "build", "carol"));
    }

    @Test
    void aProjectIsPutOnOneHostAndTakenOff() throws Exception {
        demo();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        as("mia").addHost(change("mia", "mia", "other"), "other", "alice");

        refusedAs(
                "not-found",
                () -> as("alice").addProjectToHost(change("alice", "alice", "a"), "build", "gone"));
        refusedAs(
                "not-found",
                () ->
                        as("alice")
                                .removeProjectFromHost(
                                        change("alice", "alice", "b"), "build", "demo"));
        as("alice")
                .changeProjectRepository(
                        change("alice", "alice", "open"),
                        "demo",
                        "ssh://git@forge.example/acme/demo");
        as("alice").addProjectToHost(change("alice", "alice", "c"), "build", "demo");
        assertThat(read().hostOf("demo")).contains("build");
        refusedAs(
                "exists",
                () -> as("alice").addProjectToHost(change("alice", "alice", "d"), "build", "demo"));
        refusedAs(
                "on-another-host",
                () -> as("alice").addProjectToHost(change("alice", "alice", "e"), "other", "demo"));

        as("alice").removeProjectFromHost(change("alice", "alice", "f"), "build", "demo");

        assertThat(read().hostOf("demo")).isEmpty();
        assertThat(read().getRefusals()).isEmpty();
    }

    private void twoHostsAndAnOpenedProject() throws Exception {
        demo();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        as("mia").addHost(change("mia", "mia", "other"), "other", "alice");
        as("alice")
                .changeProjectRepository(
                        change("alice", "alice", "open"),
                        "demo",
                        "ssh://git@forge.example/acme/demo");
    }

    private Map<String, List<String>> carriedOn(final String host) throws Exception {
        return read().getHosts().stream()
                .filter(each -> each.host().equals(host))
                .findFirst()
                .orElseThrow()
                .repositories();
    }

    @Test
    void repositoriesCarriedTwiceAddUpAndTakingOffTheLastTakesOffTheProject() throws Exception {
        twoHostsAndAnOpenedProject();

        as("alice")
                .addProjectToHost(change("alice", "alice", "a"), "build", "demo", List.of("api"));
        as("alice")
                .addProjectToHost(change("alice", "alice", "b"), "build", "demo", List.of("web"));
        assertThat(carriedOn("build")).isEqualTo(Map.of("demo", List.of("api", "web")));
        assertThat(Files.readString(repo.resolve("hosts/build/host.yml")))
                .as("written back in the mapping form")
                .contains("  - name: demo\n    repositories:\n      - api\n      - web\n");

        as("alice")
                .removeProjectFromHost(
                        change("alice", "alice", "c"), "build", "demo", List.of("api"));
        assertThat(carriedOn("build")).isEqualTo(Map.of("demo", List.of("web")));
        as("alice")
                .removeProjectFromHost(
                        change("alice", "alice", "d"), "build", "demo", List.of("web"));

        assertThat(read().hostOf("demo")).as("the last one took the project off").isEmpty();
        assertThat(read().getRefusals()).isEmpty();
    }

    @Test
    void aRepositoryCarriedElsewhereOrUnderAWholeProjectIsRefused() throws Exception {
        twoHostsAndAnOpenedProject();
        as("alice")
                .addProjectToHost(change("alice", "alice", "a"), "build", "demo", List.of("api"));

        refusedAs(
                "on-another-host",
                () ->
                        as("alice")
                                .addProjectToHost(
                                        change("alice", "alice", "b"),
                                        "other",
                                        "demo",
                                        List.of("web", "api")));
        refusedAs(
                "on-another-host",
                () -> as("alice").addProjectToHost(change("alice", "alice", "c"), "other", "demo"));
        refusedAs(
                "exists",
                () ->
                        as("alice")
                                .addProjectToHost(
                                        change("alice", "alice", "d"),
                                        "build",
                                        "demo",
                                        List.of("api")));
        refusedAs(
                "not-found",
                () ->
                        as("alice")
                                .removeProjectFromHost(
                                        change("alice", "alice", "e"),
                                        "build",
                                        "demo",
                                        List.of("web")));

        as("alice")
                .addProjectToHost(change("alice", "alice", "f"), "other", "demo", List.of("web"));
        assertThat(carriedOn("other")).isEqualTo(Map.of("demo", List.of("web")));
        assertThat(read().getRefusals()).isEmpty();
    }

    @Test
    void aRepositoryCannotBeTakenOffAProjectCarriedWhole() throws Exception {
        twoHostsAndAnOpenedProject();
        as("alice").addProjectToHost(change("alice", "alice", "a"), "build", "demo");

        refusedAs(
                "refused-state",
                () ->
                        as("alice")
                                .removeProjectFromHost(
                                        change("alice", "alice", "b"),
                                        "build",
                                        "demo",
                                        List.of("api")));
        assertThat(read().hostOf("demo")).contains("build");
    }

    @Test
    void aHostIsRemovedByAnOwnerAlone() throws Exception {
        build();
        final String before = tip();

        refusedAs(
                "not-allowed",
                () -> as("alice").removeHost(change("alice", "alice", "a"), "build"));
        assertThat(tip()).isEqualTo(before);
        refusedAs("not-found", () -> as("mia").removeHost(change("mia", "mia", "b"), "gone"));

        as("mia").removeHost(change("mia", "mia", "c"), "build");

        assertThat(read().getHosts()).isEmpty();
    }

    @Test
    void aHostCardThatIsNoneIsUsageAndHoldsNothingInWords() throws Exception {
        build();

        assertThatThrownBy(
                        () ->
                                as("alice")
                                        .changeHost(
                                                change("alice", "alice", "x"),
                                                "build",
                                                "secret-looking"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo("usage");
                            assertThat(refused.getMessage()).doesNotContain("secret-looking");
                        });
    }

    private Path emptyRepository() throws IOException {
        final Path bare = Files.createDirectory(dir.resolve("empty.git"));
        runner.runOrFail(Command.of("git", "init", "-q", "--bare", "-b", "main", bare.toString()));
        return bare;
    }

    private GitTreeWrites founding(final Path bare) {
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        return new GitTreeWrites(
                runner,
                bare.toString(),
                "acme",
                FetchAccess.NONE,
                forge,
                new CommitSigning(Map.of(), files));
    }

    @Test
    void whatARepositoryIsSaidToHoldIsWhatFoundingFinds() throws Exception {
        final Path bare = emptyRepository();
        final Change first = new Change(new Acting("mia", key("mia")), null, "found");

        assertThat(GitTreeWrites.repositoryState(bare.toString(), runner, FetchAccess.NONE))
                .as("nothing in it")
                .isEqualTo(GitTreeWrites.RepositoryState.EMPTY);
        assertThat(
                        runner.runOrFail(
                                        org.fuin.porelo.core.process.Command.of(
                                                "git", "-C", bare.toString(), "for-each-ref"))
                                .standardOutput())
                .as("asking pushed nothing")
                .isEmpty();
        founding(bare).createOrganization(first, card("mia", "mia"));

        assertThat(GitTreeWrites.repositoryState(bare.toString(), runner, FetchAccess.NONE))
                .as("the founding filled it")
                .isEqualTo(GitTreeWrites.RepositoryState.HOLDS);
        assertThatThrownBy(
                        () ->
                                founding(bare)
                                        .createOrganization(
                                                new Change(
                                                        new Acting("mia", key("mia")),
                                                        null,
                                                        "another"),
                                                card("mia", "mia")))
                .as("and a second founding finds it held")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.EXISTS));
        final Path gone = dir.resolve("gone.git");
        assertThat(GitTreeWrites.repositoryState(gone.toString(), runner, FetchAccess.NONE))
                .as("nothing there")
                .isEqualTo(GitTreeWrites.RepositoryState.NOT_THERE);
        assertThatThrownBy(() -> founding(gone).createOrganization(first, card("mia", "mia")))
                .as("and a founding finds nothing there")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.NO_REPOSITORY));
    }

    @Test
    void aHostKeyNotTrustedIsSaidSoItsWayOutCanBeNamed() throws Exception {
        final org.fuin.porelo.core.process.CommandRunner strict =
                command ->
                        command.arguments().contains("ls-remote")
                                ? new org.fuin.porelo.core.process.CommandResult(
                                        command, 128, "", "Host key verification failed.\n")
                                : runner.run(command);

        assertThatThrownBy(
                        () ->
                                GitTreeWrites.repositoryState(
                                        "git@forge.example:acme/organization.git",
                                        strict,
                                        FetchAccess.NONE))
                .as("what tells it from any other failure to read")
                .isInstanceOf(GitTreeStore.Unreachable.class)
                .hasMessageContaining("Host key verification failed");
    }

    @Test
    void anEmptyRepositoryIsFoundedWithItsOnlyOwnerAndCardAndAFreshReaderTrustsIt()
            throws Exception {
        final Path bare = emptyRepository();
        final Change first = new Change(new Acting("mia", key("mia")), null, "found");

        final Written written = founding(bare).createOrganization(first, card("mia", "mia"));

        final OrganizationQuestions read =
                GitTreeStore.at(
                                runner,
                                bare.toString(),
                                "acme",
                                "forge.example",
                                FetchAccess.NONE,
                                dir.resolve("state-founded"))
                        .read(forge);
        assertThat(read.getRefusals()).isEmpty();
        assertThat(read.isOrganizationOwner("mia")).isTrue();
        assertThat(read.getOrganizationAdmins()).contains(List.of("mia"));
        assertThat(read.isOrganizationAdmin("mia")).isTrue();
        assertThat(read.getPersons())
                .extracting(OrganizationQuestions.Person::login)
                .contains("mia");
        final Written again = founding(bare).createOrganization(first, card("mia", "mia"));
        assertThat(again.again()).isTrue();
        assertThat(again.revision()).isEqualTo(written.revision());
    }

    @Test
    void aRepositoryHoldingAnythingIsNotFoundedAgain() {
        refusedAs(
                "exists",
                () ->
                        as("mia")
                                .createOrganization(
                                        new Change(new Acting("mia", key("mia")), null, "again"),
                                        card("mia", "mia")));
    }

    @Test
    void someoneTheForgeSaysOwnsNothingFoundsNothing() throws Exception {
        final Path bare = emptyRepository();
        forge.owners.put("mallory", false);
        forge.keys.put("mallory", List.of(key("mallory")));

        refusedAs(
                "not-allowed",
                () ->
                        founding(bare)
                                .createOrganization(
                                        new Change(
                                                new Acting("mallory", key("mallory")), null, "x"),
                                        card("mallory", "mallory")));

        assertThat(
                        runner.run(
                                        Command.of(
                                                "git",
                                                "--git-dir=" + bare,
                                                "rev-parse",
                                                "--verify",
                                                "main"))
                                .successful())
                .as("nothing was pushed")
                .isFalse();
    }

    private String hostCard(final String name, final String signer) {
        return HostCard.write(
                name, "build.example.org", 22, key("carol"), 3000, 2222, 8448, keys.get(signer));
    }

    @Test
    void aBackupKeyOfSomeoneElseDoesNotCountAsAnOwnerLeft() throws Exception {
        write(
                "owners.yml",
                "owners:\n  - login: mia\n    key: \""
                        + key("mia")
                        + "\"\n  - login: carol\n    key: \""
                        + key("backup")
                        + "\"\n    backup: true\n");
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mia"), "commit", "-q", "-S", "-m", "backup");

        refusedAs(
                "last-of-its-kind",
                () -> as("mia").removeOrganizationOwner(change("mia", "mia", "x"), "mia"));
    }

    @Test
    void aRemovedHostLeavesNoCardForOneMadeAgainUnderItsName() throws Exception {
        build();
        as("alice")
                .changeHost(change("alice", "alice", "card"), "build", hostCard("build", "alice"));
        assertThat(repo.resolve("hosts/build/card")).exists();

        as("mia").removeHost(change("mia", "mia", "off"), "build");

        assertThat(repo.resolve("hosts/build/card")).doesNotExist();
    }

    @Test
    void aCardOfAnotherHostIsUsage() throws Exception {
        build();

        refusedAs(
                "usage",
                () ->
                        as("alice")
                                .changeHost(
                                        change("alice", "alice", "x"),
                                        "build",
                                        hostCard("other", "alice")));
    }

    @Test
    void aCardTheForgeNoLongerStandsByHoldsNoRole() throws Exception {
        aliceFiled();
        forge.keys.put("alice", List.of(key("mallory")));

        refusedAs("no-card", () -> as("mia").addHost(change("mia", "mia", "x"), "build", "alice"));
    }

    @Test
    void aRepositoryThatIsNotThereIsNoRepositoryAndAFailureIsNot() {
        final Change first = new Change(new Acting("mia", key("mia")), null, "found");

        refusedAs(
                "no-repository",
                () ->
                        founding(dir.resolve("never-made"))
                                .createOrganization(first, card("mia", "mia")));

        final GitTreeWrites answering =
                new GitTreeWrites(
                        command -> {
                            if (command.arguments().contains("ls-remote")) {
                                return new org.fuin.porelo.core.process.CommandResult(
                                        command, 128, "", "fatal: unable to access: timed out");
                            }
                            return runner.run(command);
                        },
                        dir.resolve("hanging").toString(),
                        "acme",
                        FetchAccess.NONE,
                        forge,
                        new CommitSigning(Map.of(), Map.of()));
        assertThatThrownBy(() -> answering.createOrganization(first, card("mia", "mia")))
                .as("a forge that says nothing has said no repository is there")
                .isInstanceOf(GitTreeStore.Unreachable.class);
    }

    @Test
    void anIdAskedAgainForAnotherChangeIsRefusedRatherThanAnsweredAsMade() throws Exception {
        forge.keys.put("carol", List.of(key("carol")));
        aliceFiled();
        as("mia").addOrganizationOwner(change("mia", "mia", "one"), "alice");
        final String tip = tip();

        refusedAs(
                "usage",
                () -> as("mia").addOrganizationOwner(change("mia", "mia", "one"), "carol"));
        refusedAs(
                "usage",
                () -> as("mia").removeOrganizationOwner(change("mia", "mia", "one"), "alice"));

        assertThat(tip()).isEqualTo(tip);
        assertThat(as("mia").addOrganizationOwner(change("mia", "mia", "one"), "alice").again())
                .as("the same change asked again stays the same answer")
                .isTrue();
    }

    @Test
    void aBackupKeyAskedAgainUnderItsIdWithAnotherKeyIsNotAnsweredAsFiled() throws Exception {
        final Change first = change("mia", "mia", "one");
        as("mia").addBackupKey(first, null, key("backup"));

        refusedAs("usage", () -> as("mia").addBackupKey(first, null, key("carol")));
        assertThat(as("mia").addBackupKey(first, null, key("backup")).again()).isTrue();
    }

    @Test
    void aRepositoryWithNoBranchSaysItIsEmptyRatherThanThatItCannotBeReached() throws Exception {
        final Path bare = dir.resolve("bare.git");
        runner.runOrFail(Command.of("git", "init", "-q", "--bare", "-b", "main", bare.toString()));

        assertThatThrownBy(
                        () ->
                                GitTreeStore.at(
                                                runner,
                                                bare.toString(),
                                                "acme",
                                                "forge.example",
                                                FetchAccess.NONE,
                                                dir.resolve("empty-state"))
                                        .read(forge))
                .as("a first card can be prepared for it")
                .isInstanceOf(GitTreeStore.Empty.class);
        assertThatThrownBy(
                        () ->
                                GitTreeStore.at(
                                                runner,
                                                dir.resolve("gone").toString(),
                                                "acme",
                                                "forge.example",
                                                FetchAccess.NONE,
                                                dir.resolve("gone-state"))
                                        .read(forge))
                .isInstanceOf(GitTreeStore.Unreachable.class)
                .isNotInstanceOf(GitTreeStore.Empty.class);
    }

    @Test
    void aLockedAdminIsToldTheyAreLockedAndNotThatTheyLackTheRole() throws Exception {
        demo();
        carolFiled();
        as("alice").addProjectMember(change("alice", "alice", "add"), "demo", "carol", "admin");
        as("carol").lockProjectMember(change("carol", "carol", "lock"), "demo", "alice");
        forge.keys.put("mallory", List.of(key("mallory")));
        write("people/mallory.card", card("mallory", "mallory"));
        git("add", "-A");
        git("-c", "user.signingkey=" + keyFiles.get("mallory"), "commit", "-q", "-S", "-m", "card");

        refusedAs(
                "locked",
                () ->
                        as("alice")
                                .addProjectMember(
                                        change("alice", "alice", "x"),
                                        "demo",
                                        "mallory",
                                        "developer"));
        refusedAs(
                "locked",
                () ->
                        as("alice")
                                .unlockProjectMember(
                                        change("alice", "alice", "y"), "demo", "alice"));
    }

    @Test
    void aPushThatLandedAndWasOvertakenIsAnsweredAsMadeAndNotAsMovedOn() throws Exception {
        aliceFiled();
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        final GitTreeWrites answeringLate =
                new GitTreeWrites(
                        command -> {
                            final CommandResult real = runner.run(command);
                            if (command.arguments().contains("push")) {
                                try {
                                    write("people/late.txt", "another writer's change");
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                                git("add", "-A");
                                git(
                                        "-c",
                                        "user.signingkey=" + keyFiles.get("mia"),
                                        "commit",
                                        "-q",
                                        "-S",
                                        "-m",
                                        "late");
                                return new CommandResult(command, 1, "", "the line broke");
                            }
                            return real;
                        },
                        repo.toString(),
                        "acme",
                        FetchAccess.NONE,
                        forge,
                        new CommitSigning(Map.of(), files));

        final Written written =
                answeringLate.addOrganizationOwner(change("mia", "mia", "one"), "alice");

        assertThat(written.id())
                .as("exit 65 tells a person nothing was made, and they would make it again")
                .isEqualTo("one");
    }

    @Test
    void aRepositoryHoldingAnyOtherBranchIsNotFilledAgain() throws Exception {
        final Path bare = dir.resolve("other-branch.git");
        git("clone", "-q", "--bare", repo.toString(), bare.toString());
        runner.runOrFail(
                Command.of("git", "--git-dir=" + bare, "branch", "-q", "-m", "main", "master"));

        refusedAs(
                "exists",
                () ->
                        founding(bare)
                                .createOrganization(
                                        new Change(new Acting("mia", key("mia")), null, "found"),
                                        card("mia", "mia")));
    }

    @Test
    void aChangeThatWouldChangeNoFileIsRefusedAndNotAnsweredAsMade() throws Exception {
        demo();
        carolFiled();
        as("alice").addProjectMember(change("alice", "alice", "add"), "demo", "carol", "developer");
        forge.keys.put("alice", List.of(key("alice-new")));
        as("mia").replaceCard(change("mia", "mia", "swap"), "alice", card("alice", "alice-new"));
        final String before = tip();

        // The refused commit's text is still in the file, so writing it again changes nothing.
        assertThatThrownBy(
                        () ->
                                as("alice")
                                        .addProjectMember(
                                                change("alice", "alice-new", "again"),
                                                "demo",
                                                "carol",
                                                "developer"))
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo("refused-state"))
                .hasMessageContaining("in a commit no reader accepts");

        assertThat(tip())
                .as("no reader has a member an empty commit claims to have added")
                .isEqualTo(before);
        assertThat(read().isProjectMember("demo", "carol")).isEmpty();
    }

    @Test
    void aChangeNoReaderRefusedSaysSoAndNamesNoRefusedCommit() throws Exception {
        aliceFiled();
        as("mia").addHost(change("mia", "mia", "build"), "build", "alice");
        final String card = hostCard("build", "alice");
        as("alice").changeHost(change("alice", "alice", "card"), "build", card);
        final String before = tip();

        assertThatThrownBy(
                        () ->
                                as("alice")
                                        .changeHost(
                                                change("alice", "alice", "repeat"), "build", card))
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo("refused-state"))
                .hasMessage("the file says this already");

        assertThat(tip()).isEqualTo(before);
        assertThat(read().getRefusals()).isEmpty();
    }

    private void listed() throws Exception {
        demo();
        carolFiled();
        as("mia").addCard(change("mia", "mia", "owner-card"), card("mia", "mia"));
        forge.keys.put("bob", List.of(key("bob")));
        as("bob").addCard(change("bob", "bob", "developer-card"), card("bob", "bob"));
        as("mia").addOrganizationAdmin(change("mia", "mia", "delegate"), "carol");
        as("carol").addHost(change("carol", "carol", "machine"), "build", "alice");
        as("carol").addHostAdmin(change("carol", "carol", "second-machine"), "build", "carol");
        forge.keys.put("mallory", List.of(key("mallory")));
        as("mallory").addCard(change("mallory", "mallory", "person"), card("mallory", "mallory"));
        as("carol")
                .addProjectMember(
                        change("carol", "carol", "second-admin"), "demo", "mallory", "admin");
    }

    @Test
    void onlyOwnersChangeTheOrganizationAdminListAndTheLastMayLeave() throws Exception {
        listed();
        final String before = tip();
        refusedAs(
                Refused.NOT_ALLOWED,
                () ->
                        as("carol")
                                .addOrganizationAdmin(
                                        change("carol", "carol", "denied-add"), "alice"));
        refusedAs(
                Refused.NOT_ALLOWED,
                () ->
                        as("carol")
                                .removeOrganizationAdmin(
                                        change("carol", "carol", "denied-remove"), "carol"));
        refusedAs(
                Refused.EXISTS,
                () -> as("mia").addOrganizationAdmin(change("mia", "mia", "duplicate"), "carol"));
        refusedAs(
                Refused.NOT_FOUND,
                () -> as("mia").removeOrganizationAdmin(change("mia", "mia", "missing"), "alice"));
        assertThat(tip()).isEqualTo(before);
        as("mia").removeOrganizationAdmin(change("mia", "mia", "remove"), "carol");
        assertThat(read().getOrganizationAdmins()).contains(List.of());
        assertThat(read().isOrganizationAdmin("mia")).isFalse();
        assertThat(read().isOrganizationAdmin("carol")).isFalse();
        assertThat(Files.readString(repo.resolve("organization-admins.yml")))
                .contains("admins: []");
        as("mia").addOrganizationAdmin(change("mia", "mia", "self"), "mia");
        assertThat(read().getOrganizationAdmins()).contains(List.of("mia"));
        assertThat(read().getRefusals()).isEmpty();
    }

    static Stream<String> roleWrites() {
        return Stream.of(
                "project-add",
                "project-remove",
                "project-admin",
                "host-add",
                "host-remove",
                "host-admin-add",
                "host-admin-remove",
                "host-change",
                "carry",
                "uncarry",
                "address",
                "replace",
                "developer-add",
                "developer-remove",
                "developer-lock",
                "developer-unlock",
                "admin-add",
                "admin-remove",
                "admin-lock",
                "admin-unlock",
                "promote",
                "demote");
    }

    private void roleWrite(final String operation, final String actor, final String id)
            throws Exception {
        final GitTreeWrites writes = as(actor);
        final Change c = change(actor, actor, id);
        switch (operation) {
            case "project-add" -> writes.addProject(c, "other", "alice");
            case "project-remove" -> writes.removeProject(c, "demo");
            case "project-admin" -> writes.changeProjectAdmin(c, "demo", "carol");
            case "host-add" -> writes.addHost(c, "other", "alice");
            case "host-remove" -> writes.removeHost(c, "build");
            case "host-admin-add" -> writes.addHostAdmin(c, "build", "mallory");
            case "host-admin-remove" -> writes.removeHostAdmin(c, "build", "carol");
            case "host-change" -> writes.changeHost(c, "build", hostCard("build", actor));
            case "carry" -> writes.addProjectToHost(c, "build", "demo");
            case "uncarry" -> writes.removeProjectFromHost(c, "build", "demo");
            case "address" ->
                    writes.changeProjectRepository(
                            c, "demo", "https://forge.example/acme/next.git");
            case "replace" -> writes.replaceCard(c, "mallory", card("mallory", "alice-new"));
            case "developer-add" -> writes.addProjectMember(c, "demo", "bob", "developer");
            case "developer-remove" -> writes.removeProjectMember(c, "demo", "bob");
            case "developer-lock" -> writes.lockProjectMember(c, "demo", "bob");
            case "developer-unlock" -> writes.unlockProjectMember(c, "demo", "bob");
            case "admin-add" -> writes.addProjectMember(c, "demo", "bob", "admin");
            case "admin-remove" -> writes.removeProjectMember(c, "demo", "mallory");
            case "admin-lock" -> writes.lockProjectMember(c, "demo", "mallory");
            case "admin-unlock" -> writes.unlockProjectMember(c, "demo", "mallory");
            case "promote" -> writes.changeProjectMemberRole(c, "demo", "bob", "admin");
            case "demote" -> writes.changeProjectMemberRole(c, "demo", "mallory", "developer");
            default -> throw new AssertionError(operation);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roleWrites")
    void eachWriteBelongsToItsRole(final String operation) throws Exception {
        listed();
        as("alice")
                .changeProjectRepository(
                        change("alice", "alice", "open"),
                        "demo",
                        "https://forge.example/acme/demo.git");
        if (operation.equals("uncarry")) {
            as("alice").addProjectToHost(change("alice", "alice", "carry"), "build", "demo");
        }
        if (List.of("developer-remove", "developer-lock", "developer-unlock", "promote")
                .contains(operation)) {
            as("alice")
                    .addProjectMember(
                            change("alice", "alice", "member"), "demo", "bob", "developer");
        }
        if (operation.equals("developer-unlock")) {
            as("alice").lockProjectMember(change("alice", "alice", "lock"), "demo", "bob");
        }
        if (operation.equals("admin-unlock")) {
            as("carol").lockProjectMember(change("carol", "carol", "lock"), "demo", "mallory");
        }
        if (operation.equals("replace")) {
            forge.keys.put("mallory", List.of(key("mallory"), key("alice-new")));
        }
        final boolean project = operation.startsWith("developer-") || operation.equals("address");
        final boolean machine = List.of("host-change", "carry", "uncarry").contains(operation);
        // Carol has no machine role for these checks.
        if (machine || operation.equals("host-remove")) {
            as("carol").removeHostAdmin(change("carol", "carol", "separate"), "build", "carol");
        }
        final String allowed = project || machine ? "alice" : "carol";
        final String denied = project || machine ? "carol" : "alice";
        final String before = tip();
        refusedAs(Refused.NOT_ALLOWED, () -> roleWrite(operation, "mia", "owner-denied"));
        if (!operation.equals("host-remove")) {
            refusedAs(Refused.NOT_ALLOWED, () -> roleWrite(operation, denied, "denied"));
        }
        assertThat(tip()).isEqualTo(before);
        roleWrite(operation, allowed, "allowed");
        assertThat(tip()).as("the authorized change was pushed").isNotEqualTo(before);
        final OrganizationQuestions state = read();
        assertThat(state.getRefusals()).as("a fresh reader accepts the change").isEmpty();
        switch (operation) {
            case "project-add" ->
                    assertThat(state.projectAdmins("other")).contains(List.of("alice"));
            case "project-remove" -> assertThat(state.projectAdmins("demo")).isEmpty();
            case "project-admin" ->
                    assertThat(state.isProjectMember("demo", "carol")).contains("admin");
            case "host-add" -> assertThat(state.isHostAdmin("other", "alice")).isTrue();
            case "host-remove" -> assertThat(state.getHosts()).isEmpty();
            case "host-admin-add" -> assertThat(state.isHostAdmin("build", "mallory")).isTrue();
            case "host-admin-remove" -> assertThat(state.isHostAdmin("build", "carol")).isFalse();
            case "host-change" ->
                    assertThat(state.getHostCard("build")).contains(hostCard("build", "alice"));
            case "carry" -> assertThat(state.hostOf("demo")).contains("build");
            case "uncarry" -> assertThat(state.hostOf("demo")).isEmpty();
            case "address" ->
                    assertThat(state.getProjectRepository("demo"))
                            .contains("https://forge.example/acme/next.git");
            case "replace" ->
                    assertThat(state.getPersons())
                            .filteredOn(person -> person.login().equals("mallory"))
                            .extracting(OrganizationQuestions.Person::key)
                            .containsExactly(key("alice-new"));
            case "developer-add", "developer-unlock" ->
                    assertThat(state.isProjectMember("demo", "bob")).contains("developer");
            case "developer-remove", "developer-lock" ->
                    assertThat(state.isProjectMember("demo", "bob")).isEmpty();
            case "admin-add", "promote" ->
                    assertThat(state.isProjectMember("demo", "bob")).contains("admin");
            case "admin-remove", "admin-lock" ->
                    assertThat(state.isProjectMember("demo", "mallory")).isEmpty();
            case "admin-unlock" ->
                    assertThat(state.isProjectMember("demo", "mallory")).contains("admin");
            case "demote" ->
                    assertThat(state.isProjectMember("demo", "mallory")).contains("developer");
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void aMachineAdminRemovesTheHostWithItsCardAfterTheListExists() throws Exception {
        listed();
        as("alice")
                .changeHost(change("alice", "alice", "card"), "build", hostCard("build", "alice"));
        as("alice").removeHost(change("alice", "alice", "remove"), "build");
        assertThat(read().getHosts()).isEmpty();
        assertThat(repo.resolve("hosts/build/card")).doesNotExist();
        assertThat(read().getRefusals()).isEmpty();
    }
}
