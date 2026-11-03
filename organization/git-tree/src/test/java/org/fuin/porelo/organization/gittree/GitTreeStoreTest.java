package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards against a right taken from a signer the path's rule does not allow: an owner, an admin or
 * a member who is one only because somebody with write access to the repository wrote so. Every key
 * is made in the run, and the attacker plays raw commits.
 */
class GitTreeStoreTest {

    private static final String ORGANISATION = "acme";

    @TempDir Path dir;

    private Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, SigningKey> keys = new HashMap<>();

    private final Forge forge = new Forge();

    /** An identity source that answers what a test sets, and "could not be asked" otherwise. */
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
    void repository() throws Exception {
        repo = Files.createDirectory(dir.resolve("organization"));
        for (final String login :
                List.of("mia", "alice", "alice-new", "bob", "carol", "carol-new", "mallory")) {
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

    /** Commits everything, signed by this login's key, or unsigned for {@code null}. */
    private String commit(final @Nullable String signer) {
        git("add", "-A");
        if (signer == null) {
            git("commit", "-q", "--allow-empty", "-m", "change");
        } else {
            git(
                    "-c",
                    "user.signingkey=" + keyFiles.get(signer),
                    "commit",
                    "-q",
                    "--allow-empty",
                    "-S",
                    "-m",
                    "change");
        }
        return git("rev-parse", "HEAD");
    }

    private String key(final String login) {
        return PersonCard.keyLine(keys.get(login));
    }

    private String owners(final String... logins) {
        final StringBuilder text = new StringBuilder("owners:\n");
        for (final String login : logins) {
            text.append("  - login: ")
                    .append(login)
                    .append("\n    key: \"")
                    .append(key(login))
                    .append("\"\n");
        }
        return text.toString();
    }

    private String founded() throws IOException {
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", owners("mia"));
        return commit("mia");
    }

    /** Files a person's own card, whose key the identity source lists. */
    private void person(final String login) throws IOException {
        forge.keys.put(login, List.of(key(login)));
        write("people/" + login + ".card", card(login, login));
        commit(login);
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

    /** A project whose card names alice, with alice as admin and bob as developer. */
    private void project() throws IOException {
        person("alice");
        person("bob");
        write("projects/demo/card", "name: demo\nadmin: alice\n");
        commit("mia");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n");
        commit("alice");
    }

    private GitTreeStore store(final String address) {
        return new GitTreeStore(
                runner, address, ORGANISATION, "github.com", FetchAccess.NONE, null);
    }

    private OrganizationQuestions read() throws IOException {
        return store(repo.toString()).read(forge);
    }

    private static List<String> refusedPaths(final OrganizationQuestions answers) {
        return answers.getRefusals().stream().map(Organization.Refusal::path).toList();
    }

    @Test
    void aFounderTheIdentitySourceVouchesForIsAnOwnerAndTheAnswerNamesItsRevision()
            throws Exception {
        final String head = founded();

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mia")).as("the founder owns it").isTrue();
        assertThat(answers.isOrganizationOwner("alice")).as("nobody else does").isFalse();
        assertThat(answers.revision()).as("the answers rest on the tip").isEqualTo(head);
        assertThat(answers.getRefusals()).as("nothing was refused").isEmpty();
    }

    @Test
    void aFounderTheIdentitySourceDeniesIsNoOwner() throws Exception {
        forge.owners.put("mia", false);
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", owners("mia"));
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mia")).as("the source said no").isFalse();
        assertThat(refusedPaths(answers)).as("the file is refused").containsExactly("owners.yml");
    }

    @Test
    void aFounderNobodyCanVouchForStopsTheWalkBeforeThatCommit() throws Exception {
        write("owners.yml", owners("mia"));
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mia")).as("nothing is accepted yet").isFalse();
        assertThat(answers.revision()).as("the next read judges the commit again").isNull();
    }

    @Test
    void aFounderWhoseKeyLeftTheForgeIsRefusedOnANewMachineNamingThatKey() throws Exception {
        founded();
        final String fingerprint = PersonCard.fingerprint(key("mia"));
        assertThat(keeping().read(forge).isOrganizationOwner("mia")).as("in force").isTrue();

        forge.keys.put("mia", List.of(key("alice")));

        assertThat(keeping().read(forge).isOrganizationOwner("mia"))
                .as("a machine that saw it in force keeps its record")
                .isTrue();
        assertThatThrownBy(this::read)
                .as("a list without the key is a plain no; answering no owners would look empty")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo("refused-state");
                            assertThat(refused.getMessage()).contains(fingerprint);
                        });
    }

    @Test
    void aForgedFirstFileDoesNotStopTheRealFounderFromFounding() throws Exception {
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", "owners:\n  - login: mia\n    key: \"" + key("mallory") + "\"\n");
        commit("mallory");
        assertThatThrownBy(this::read)
                .as("nothing is accepted yet")
                .isInstanceOf(OrganizationQuestions.Refused.class);

        write("owners.yml", owners("mia"));
        commit("mia");

        assertThat(read().isOrganizationOwner("mia"))
                .as("a stranger's commit must not lock the founder out")
                .isTrue();
    }

    @Test
    void aFounderNobodyCanVouchForAfterAForgedFileIsNotYetARefusal() throws Exception {
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", "owners:\n  - login: mia\n    key: \"" + key("mallory") + "\"\n");
        final String forged = commit("mallory");
        write("owners.yml", owners("alice"));
        commit("alice");

        assertThat(read().revision())
                .as("the next read judges alice's commit again, so nothing is lost for good yet")
                .isEqualTo(forged);
    }

    @Test
    void aFirstOwnersFileSignedBySomebodyItDoesNotNameIsRefused() throws Exception {
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", owners("mia"));
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mia")).as("mallory founded nothing").isFalse();
        assertThat(refusedPaths(answers)).containsExactly("owners.yml");
    }

    @Test
    void aBackupKeyHoldsNoOwnersPlace() throws Exception {
        founded();
        write(
                "owners.yml",
                owners("mia")
                        + "  - login: carol\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\n");
        commit("mia");

        assertThat(read().isOrganizationOwner("carol")).as("only a backup key").isFalse();
    }

    @Test
    void anOwnersFileChangedByANonOwnerKeepsTheOwners() throws Exception {
        founded();
        write("owners.yml", owners("mallory"));
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mallory")).as("a stranger took nothing").isFalse();
        assertThat(answers.isOrganizationOwner("mia")).as("the owner stays").isTrue();
        assertThat(refusedPaths(answers)).containsExactly("owners.yml");
    }

    @Test
    void anOwnersSignatureCopiedOntoAnotherChangeIsRefused() throws Exception {
        founded();
        write("people/mia.card", card("mia", "mia"));
        forge.keys.put("mia", List.of(key("mia")));
        final String signed = commit("mia");
        final String parent = git("rev-parse", signed + "^");
        write("owners.yml", owners("mallory"));
        git("add", "-A");
        final String tree = git("write-tree");
        final String raw = git("cat-file", "commit", signed);
        final String forged =
                raw.replaceFirst("tree [0-9a-f]+", "tree " + tree)
                                .replace("parent " + parent, "parent " + signed)
                        + "\n";
        final String object =
                runner.runOrFail(
                                Command.of("git", "hash-object", "-t", "commit", "-w", "--stdin")
                                        .in(repo)
                                        .withInput(forged))
                        .trimmedOutput();
        git("update-ref", "refs/heads/main", object);
        assertThat(raw).as("the copied commit carries mia's signature").contains("gpgsig ");

        final OrganizationQuestions answers = read();

        assertThat(answers.revision()).as("the forged commit is the tip").isEqualTo(object);
        assertThat(answers.isOrganizationOwner("mallory"))
                .as("the signature is not on this change")
                .isFalse();
        assertThat(answers.isOrganizationOwner("mia")).as("the owner stays").isTrue();
        assertThat(refusedPaths(answers)).containsExactly("owners.yml");
    }

    @Test
    void anUnsignedChangeToTheOwnersIsRefused() throws Exception {
        founded();
        write("owners.yml", owners("mallory"));
        commit(null);

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mallory")).as("no signature, no owner").isFalse();
        assertThat(refusedPaths(answers)).containsExactly("owners.yml");
    }

    @Test
    void theOwnersFileCannotBeRemoved() throws Exception {
        founded();
        Files.delete(repo.resolve("owners.yml"));
        commit("mia");

        assertThat(read().isOrganizationOwner("mia"))
                .as("the next file would found it anew")
                .isTrue();
    }

    @Test
    void anAdminsChangeToTheMembersIsAnswered() throws Exception {
        founded();
        project();

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice")).contains("admin");
        assertThat(answers.isProjectMember("demo", "bob")).contains("developer");
        assertThat(answers.isProjectMember("demo", "carol")).as("not listed").isEmpty();
        assertThat(answers.isProjectMember("other", "alice")).as("no such project").isEmpty();
    }

    @Test
    void aLockedMemberIsListedButHoldsNoRole() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n");
        commit("alice");

        assertThat(read().isProjectMember("demo", "bob")).as("may nothing while locked").isEmpty();
    }

    @Test
    void aDeveloperCannotMakeThemselvesAnAdmin() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: admin\n");
        commit("bob");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "bob")).contains("developer");
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void aLockedAdminChangesNothing() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: admin\n    locked: true\n");
        commit("alice");
        write("projects/demo/members.yml", "members:\n  - login: bob\n    role: admin\n");
        commit("bob");

        assertThat(read().isProjectMember("demo", "alice"))
                .as("the locked admin removed nobody")
                .contains("admin");
    }

    @Test
    void aStrangerWithAFiledCardChangesNoMembers() throws Exception {
        founded();
        project();
        person("mallory");
        write("projects/demo/members.yml", "members:\n  - login: mallory\n    role: admin\n");
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "mallory")).as("no right").isEmpty();
        assertThat(answers.isProjectMember("demo", "alice")).contains("admin");
    }

    @Test
    void aCardFiledInAnAdminsNameUnderAnotherKeyGivesNoRight() throws Exception {
        founded();
        person("alice");
        write("projects/demo/card", "name: demo\nadmin: carol\n");
        commit("mia");
        forge.keys.put("carol", List.of(key("mallory")));
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");
        write("projects/demo/members.yml", "members:\n  - login: carol\n    role: admin\n");
        commit("carol");

        assertThat(read().isProjectMember("demo", "carol"))
                .as("the commit's key is not the one the login's card holds")
                .isEmpty();
    }

    @Test
    void aCardTheIdentitySourceDeniesGivesNoAdminRight() throws Exception {
        founded();
        write("projects/demo/card", "name: demo\nadmin: mallory\n");
        commit("mia");
        forge.keys.put("mallory", List.of(key("alice")));
        write("people/mallory.card", card("mallory", "mallory"));
        commit("mallory");
        write("projects/demo/members.yml", "members:\n  - login: mallory\n    role: admin\n");
        commit("mallory");

        assertThat(read().isProjectMember("demo", "mallory")).as("the card is refused").isEmpty();
    }

    @Test
    void aSignerWhoseCardIsNotVouchedForYetStopsTheWalkUntilTheNextRead() throws Exception {
        founded();
        write("projects/demo/card", "name: demo\nadmin: alice\n");
        commit("mia");
        write("people/alice.card", card("alice", "alice"));
        final String before = commit("alice");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        final String tip = commit("alice");

        final OrganizationQuestions first = read();

        assertThat(first.isProjectMember("demo", "alice")).as("not vouched for yet").isEmpty();
        assertThat(first.revision()).as("stopped before the commit").isEqualTo(before);

        forge.keys.put("alice", List.of(key("alice")));
        final OrganizationQuestions second = read();

        assertThat(second.isProjectMember("demo", "alice")).contains("admin");
        assertThat(second.revision()).isEqualTo(tip);
    }

    @Test
    void theCardsAdminWritesTheFirstMembersOnceAndHasNoLastingPower() throws Exception {
        founded();
        project();
        write("projects/demo/members.yml", "members:\n  - login: bob\n    role: admin\n");
        commit("alice");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice"))
                .as("once out of the members, the card gives alice nothing")
                .isEmpty();
        assertThat(answers.isProjectMember("demo", "bob")).contains("admin");
    }

    @Test
    void theFirstMembersMustNameTheCardsAdminAsAdmin() throws Exception {
        founded();
        person("alice");
        write("projects/demo/card", "name: demo\nadmin: alice\n");
        commit("mia");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: developer\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice")).isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void anOwnerMayAddAProjectsAdmin() throws Exception {
        founded();
        project();
        person("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: admin\n");
        commit("mia");

        assertThat(read().isProjectMember("demo", "carol")).contains("admin");
    }

    @Test
    void anOwnerMayRemoveAProjectsAdmin() throws Exception {
        founded();
        project();
        write("projects/demo/members.yml", "members:\n  - login: bob\n    role: developer\n");
        commit("mia");

        assertThat(read().isProjectMember("demo", "alice")).isEmpty();
    }

    @Test
    void anOwnerMayChangeNothingElseInTheMembers() throws Exception {
        founded();
        project();
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "bob")).as("a developer stays").isPresent();
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void anOwnerMayMakeADeveloperAnAdminAndUnmakeOne() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: developer\n  - login: bob\n    role: admin\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "bob")).as("made an admin").contains("admin");
        assertThat(answers.isProjectMember("demo", "alice"))
                .as("unmade as an admin")
                .contains("developer");
    }

    @Test
    void anOwnerCannotLockAnAdmin() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n    locked: true\n"
                        + "  - login: bob\n    role: developer\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice")).as("not locked").contains("admin");
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void anOwnerCannotUnlockAMember() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n");
        commit("alice");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n");
        commit("mia");

        assertThat(read().isProjectMember("demo", "bob")).as("the lock stays").isEmpty();
    }

    @Test
    void anOwnerCannotAddADeveloperOrALockedAdmin() throws Exception {
        founded();
        project();
        final String kept =
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n";
        write("projects/demo/members.yml", kept + "  - login: carol\n    role: developer\n");
        commit("mia");
        write(
                "projects/demo/members.yml",
                kept + "  - login: carol\n    role: admin\n    locked: true\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "carol")).as("not added").isEmpty();
        assertThat(refusedPaths(answers)).hasSize(2);
    }

    @Test
    void aProjectsCardCountsFromAnOwnerAlone() throws Exception {
        founded();
        person("mallory");
        write("projects/demo/card", "name: demo\nadmin: mallory\n");
        commit("mallory");
        write("projects/demo/members.yml", "members:\n  - login: mallory\n    role: admin\n");
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "mallory")).as("no project was made").isEmpty();
        assertThat(refusedPaths(answers))
                .containsExactly("projects/demo/card", "projects/demo/members.yml");
    }

    @Test
    void aProjectNamedAsTheOneEachMachineHasIsRefused() throws Exception {
        founded();
        person("alice");
        write("projects/default/card", "name: default\nadmin: alice\n");
        commit("mia");

        assertThat(refusedPaths(read())).containsExactly("projects/default/card");
    }

    @Test
    void aRightIsJudgedAgainstTheStateBeforeTheCommitThatClaimsIt() throws Exception {
        founded();
        person("mallory");
        write("owners.yml", owners("mia", "mallory"));
        write("projects/demo/card", "name: demo\nadmin: mallory\n");
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("mallory")).isFalse();
        assertThat(refusedPaths(answers))
                .containsExactlyInAnyOrder("owners.yml", "projects/demo/card");
    }

    @Test
    void aCardFromSomebodyElsesCommitIsRefusedThoughTheCardItselfIsSigned() throws Exception {
        founded();
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice"));
        commit("mallory");

        assertThat(refusedPaths(read()))
                .as("a card once signed could be played back by anybody")
                .containsExactly("people/alice.card");
    }

    @Test
    void aCardFiledInTheCommitThatClaimsItsRightGivesNoRightThere() throws Exception {
        founded();
        write("projects/demo/card", "name: demo\nadmin: mallory\n");
        commit("mia");
        forge.keys.put("mallory", List.of(key("mallory")));
        write("people/mallory.card", card("mallory", "mallory"));
        write("projects/demo/members.yml", "members:\n  - login: mallory\n    role: admin\n");
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "mallory"))
                .as("every path is judged against the state before the commit")
                .isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void aFileThatIsNoRegularFileIsRefused() throws Exception {
        founded();
        project();
        Files.delete(repo.resolve("projects/demo/members.yml"));
        // A link's target is its blob, so this one reads as a well-formed members file.
        Files.createSymbolicLink(
                repo.resolve("projects/demo/members.yml"),
                Path.of("{members: [{login: alice, role: admin}]}"));
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "bob"))
                .as("the link changed nothing")
                .isPresent();
        assertThat(refusedPaths(answers)).containsExactly("projects/demo/members.yml");
    }

    @Test
    void aRefusalNeverQuotesWhatTheFileHolds() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: \"s3cret\u001b[2J\"\n    role: x\n");
        commit("alice");
        write("owners.yml", "s3cret: [unclosed\n");
        commit("mia");

        assertThat(read().getRefusals())
                .as("the files are anybody's, and the text reaches a terminal")
                .hasSize(2)
                .allSatisfy(refusal -> assertThat(refusal.why()).doesNotContain("s3cret"));
    }

    @Test
    void aYamlAliasOrAnUnknownFieldIsRefused() throws Exception {
        founded();
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - &a {login: alice, role: admin}\n  - *a\n");
        commit("alice");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n    fingerprint: x\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(refusedPaths(answers))
                .as("each file is held to its fields")
                .containsExactly("projects/demo/members.yml", "projects/demo/members.yml");
        assertThat(answers.isProjectMember("demo", "bob")).as("the members stay").isPresent();
    }

    @Test
    void aKeyNobodyHandedOnTakesNoCard() throws Exception {
        founded();
        project();
        write("people/alice.card", card("alice", "mallory"));
        commit("mallory");
        write("projects/demo/members.yml", "members:\n  - login: mallory\n    role: admin\n");
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice")).as("alice stays").contains("admin");
        assertThat(refusedPaths(answers))
                .containsExactly("people/alice.card", "projects/demo/members.yml");
    }

    @Test
    void aKeyHandedOnCarriesTheRightsAndTheOldKeyHoldsNoneAgain() throws Exception {
        founded();
        project();
        write("people/alice.card", handedOn("alice", "alice-new", "alice"));
        commit("alice-new");
        final String removedBob = "members:\n  - login: alice\n    role: admin\n";
        write("projects/demo/members.yml", removedBob + "  - login: carol\n    role: developer\n");
        commit("alice");

        final OrganizationQuestions old = read();

        assertThat(old.isProjectMember("demo", "bob"))
                .as("the old key changed nothing")
                .isPresent();

        write("projects/demo/members.yml", removedBob);
        commit("alice-new");

        assertThat(read().isProjectMember("demo", "bob")).as("the new key holds it").isEmpty();
    }

    @Test
    void aCardNamingAPredecessorThatIsNotTheOldKeyIsRefused() throws Exception {
        founded();
        person("alice");
        write("people/alice.card", handedOn("alice", "alice-new", "mallory"));
        commit("alice-new");

        assertThat(refusedPaths(read())).containsExactly("people/alice.card");
    }

    @Test
    void anOwnersReplacementRevokesTheOldKeyEverywhere() throws Exception {
        founded();
        project();
        forge.keys.put("carol", List.of(key("alice")));
        write("people/carol.card", card("carol", "alice"));
        commit("alice");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", card("alice", "alice-new"));
        final String replaced = commit("mia");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.revision()).as("the walk went on").isNotEqualTo(replaced);
        assertThat(answers.isProjectMember("demo", "bob"))
                .as("the lost key signs nothing any more")
                .isPresent();
        assertThat(answers.getRefusals())
                .as("it is refused for being revoked")
                .anySatisfy(refusal -> assertThat(refusal.why()).contains("revoked"));
        assertThat(refusedPaths(answers))
                .as("nothing else in the project was refused")
                .containsExactly("projects/demo/members.yml");

        // Other text than the refused commit left in the tree, or there is no change to judge.
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: carol\n    role: developer\n");
        commit("alice-new");

        assertThat(read().isProjectMember("demo", "bob")).as("the replacement holds it").isEmpty();
    }

    @Test
    void aCardHandedOnFromARevokedKeyIsRefusedUnderAnyLogin() throws Exception {
        founded();
        person("alice");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", card("alice", "alice-new"));
        commit("mia");
        forge.keys.put("carol", List.of(key("mallory")));
        write("people/carol.card", handedOn("carol", "mallory", "alice"));
        commit("mallory");

        assertThat(refusedPaths(read()))
                .as("the revoked key vouches for nothing, whatever the source lists")
                .containsExactly("people/carol.card");
    }

    @Test
    void anOwnersReplacementTheSourceDoesNotListHoldsNoRight() throws Exception {
        founded();
        project();
        write("people/alice.card", card("alice", "alice-new"));
        commit("mia");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice-new");

        assertThat(read().isProjectMember("demo", "bob"))
                .as("an owner vouches for the login, not the key")
                .isPresent();
    }

    @Test
    void anOwnerCannotPutACardWhereNoneWasHeld() throws Exception {
        founded();
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice"));
        commit("mia");

        assertThat(refusedPaths(read())).containsExactly("people/alice.card");
    }

    @Test
    void aFirstSeenCardGivesWayToTheLoginsOwnListedCard() throws Exception {
        founded();
        write("people/alice.card", card("alice", "mallory"));
        commit("mallory");
        write("projects/demo/card", "name: demo\nadmin: alice\n");
        commit("mia");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice"));
        commit("alice");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "alice"))
                .as("the card filed first was only holding the login")
                .contains("admin");
    }

    @Test
    void aCardsRemovalCountsFromItsKeyOrAnOwnerAlone() throws Exception {
        founded();
        project();
        Files.delete(repo.resolve("people/alice.card"));
        commit("mallory");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        assertThat(read().isProjectMember("demo", "bob"))
                .as("the card stands, so alice still acts")
                .isEmpty();
    }

    @Test
    void aBackupKeyReplacesOnlyTheKeyItBacks() throws Exception {
        founded();
        final String backup =
                "  - login: mia\n    key: \"" + key("carol") + "\"\n    backup: true\n";
        write("owners.yml", owners("mia", "bob") + backup);
        commit("mia");
        write(
                "owners.yml",
                owners("bob") + "  - login: mia\n    key: \"" + key("alice") + "\"\n" + backup);
        final String replaced = commit("carol");

        final OrganizationQuestions answers = read();

        assertThat(answers.revision()).isEqualTo(replaced);
        assertThat(refusedPaths(answers)).as("it replaced the key it backs").isEmpty();
        assertThat(answers.isOrganizationOwner("mia")).isTrue();
    }

    @Test
    void aBackupKeyCannotTouchAnotherOwner() throws Exception {
        founded();
        final String backup =
                "  - login: mia\n    key: \"" + key("carol") + "\"\n    backup: true\n";
        write("owners.yml", owners("mia", "bob") + backup);
        commit("mia");
        // A proper replacement of the key it backs, with another owner gone beside it.
        write(
                "owners.yml",
                "owners:\n  - login: mia\n    key: \"" + key("alice") + "\"\n" + backup);
        commit("carol");

        final OrganizationQuestions answers = read();

        assertThat(answers.isOrganizationOwner("bob")).as("bob stays an owner").isTrue();
        assertThat(refusedPaths(answers)).containsExactly("owners.yml");
    }

    @Test
    void aGivenRevisionAnswersAsItsStateStood() throws Exception {
        founded();
        project();
        final String earlier = git("rev-parse", "HEAD");
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = store(repo.toString()).read(forge, earlier);

        assertThat(answers.revision()).isEqualTo(earlier);
        assertThat(answers.isProjectMember("demo", "bob")).as("bob was a member then").isPresent();
        assertThat(read().isProjectMember("demo", "bob")).as("and is not now").isEmpty();
    }

    @Test
    void aRevisionTheWalkCouldNotReachIsRefusedNamingWhereItStopped() throws Exception {
        founded();
        project();
        final String reached = git("rev-parse", "HEAD");
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write("projects/demo/card", "name: demo\nadmin: carol\n");
        commit("mia");
        write("hosts/build/host.yml", "admins:\n  - login: carol\nprojects: []\n");
        commit("mia");
        write("hosts/build/host.yml", "admins:\n  - login: bob\nprojects: []\n");
        final String undecided = commit("carol");
        final String stoppedAt = git("rev-parse", "HEAD~1");

        assertThat(read().revision()).as("the tip answers where it stopped").isEqualTo(stoppedAt);
        assertThat(reached).as("the walk went past this").isNotEqualTo(stoppedAt);
        assertThatThrownBy(() -> store(repo.toString()).read(forge, undecided))
                .as("a caller who named a revision is not answered about another")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo("cannot-say");
                            assertThat(refused.revision()).isEqualTo(stoppedAt);
                        });
    }

    @Test
    void anOldKeyHandedOnKeepsItsPastCommitsWhenOnlyTheNewOneIsListed() throws Exception {
        founded();
        project();
        write("people/alice.card", handedOn("alice", "alice-new", "alice"));
        commit("alice-new");
        forge.keys.put("alice", List.of(key("alice-new")));

        final OrganizationQuestions answers = read();

        assertThat(answers.getRefusals()).as("a new machine agrees with an old one").isEmpty();
        assertThat(answers.isProjectMember("demo", "bob"))
                .as("alice's old key added bob")
                .contains("developer");
    }

    /** A project with carol as a member, whose card comes from the test. */
    private void projectWithCarol() throws IOException {
        project();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: carol\n    role: developer\n");
        commit("alice");
        forge.keys.put("carol", List.of(key("carol-new")));
    }

    @Test
    void aKeyIsNotVouchedForByACardItCopiedInACommitOfItsOwn() throws Exception {
        founded();
        projectWithCarol();
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");
        write("people/carol.card", handedOn("carol", "carol-new", "mallory"));
        commit("mallory");

        assertThat(read().isProjectMember("demo", "carol"))
                .as("the listed key signed no commit handing anything on")
                .isEmpty();
    }

    @Test
    void aKeyHandedOnUnderOneLoginIsNotVouchedForUnderAnother() throws Exception {
        founded();
        projectWithCarol();
        write("people/mallory.card", card("mallory", "mallory"));
        commit("mallory");
        write("people/mallory.card", handedOn("mallory", "carol-new", "mallory"));
        commit("carol-new");
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");

        assertThat(read().isProjectMember("demo", "carol"))
                .as("a step counts for the login whose card it is")
                .isEmpty();
    }

    @Test
    void afterALostKeyItsPastCommitsStayRefusedUntilAnOwnerAddsTheAdminsAgain() throws Exception {
        founded();
        project();
        write("people/alice.card", card("alice", "alice-new"));
        commit("mia");
        forge.keys.put("alice", List.of(key("alice-new")));

        final OrganizationQuestions lost = read();

        assertThat(lost.projectAdmins("demo"))
                .as("the lost key's commits count for nothing on a new machine")
                .contains(List.of());

        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("mia");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n");
        commit("alice-new");

        final OrganizationQuestions recovered = read();

        assertThat(recovered.projectAdmins("demo"))
                .as("the owner added the admin again in a new signed commit")
                .contains(List.of("alice"));
        assertThat(recovered.isProjectMember("demo", "bob"))
                .as("and the new key adds the rest")
                .contains("developer");
    }

    @Test
    void aRevisionOffTheLineIsRefused() throws Exception {
        founded();
        git("checkout", "-q", "-b", "aside");
        write("owners.yml", owners("mia", "mallory"));
        final String aside = commit("mia");
        git("checkout", "-q", "main");
        write("people/mia.card", card("mia", "mia"));
        commit("mia");

        assertThatThrownBy(() -> store(repo.toString()).read(forge, aside))
                .as("a state nobody walked to is no answer")
                .isInstanceOf(OrganizationException.class)
                .hasMessageContaining("not on the organization's line");
    }

    @Test
    void anAddressIsReadForItsOrganisationAndTheAccessIsLentForTheFetchAlone() throws Exception {
        founded();
        final String address = "https://example.invalid/acme/organization";
        final boolean[] open = {false};
        final List<String> asked = new ArrayList<>();
        final FetchAccess access =
                lent -> {
                    asked.add(lent);
                    open[0] = true;
                    return new FetchAccess.Lease() {
                        @Override
                        public List<String> arguments() {
                            return List.of("-c", "url." + repo + ".insteadOf=" + address);
                        }

                        @Override
                        public void close() {
                            open[0] = false;
                        }
                    };
                };
        final boolean[] openWhileWalking = {false};
        final IdentitySource watching =
                new IdentitySource() {
                    @Override
                    public Optional<Keys> keys(final String login) {
                        return forge.keys(login);
                    }

                    @Override
                    public Optional<Boolean> owns(final String organisation, final String login) {
                        openWhileWalking[0] |= open[0];
                        return Optional.of(
                                "acme".equals(organisation)
                                        && forge.owns(organisation, login).orElse(false));
                    }
                };

        final OrganizationQuestions answers =
                GitTreeStore.open(runner, address, access).read(watching);

        assertThat(answers.isOrganizationOwner("mia")).as("read through what was lent").isTrue();
        assertThat(asked).as("asked by its address").containsExactly(address);
        assertThat(open[0]).as("returned after the fetch").isFalse();
        assertThat(openWhileWalking[0]).as("not held while the walk asks others").isFalse();
    }

    @Test
    void aLeaseLendsItsEnvironmentToTheFetch() throws Exception {
        founded();
        final String address = "https://example.invalid/acme/organization";
        final FetchAccess access =
                lent ->
                        new FetchAccess.Lease() {
                            @Override
                            public Map<String, String> environment() {
                                return Map.of(
                                        "GIT_CONFIG_COUNT",
                                        "1",
                                        "GIT_CONFIG_KEY_0",
                                        "url." + repo + ".insteadOf",
                                        "GIT_CONFIG_VALUE_0",
                                        address);
                            }
                        };

        assertThat(
                        GitTreeStore.open(runner, address, access)
                                .read(forge)
                                .isOrganizationOwner("mia"))
                .isTrue();
    }

    @Test
    void aLeaseIsReturnedWhenTheFetchFails() {
        final boolean[] closed = {false};
        final FetchAccess access =
                lent ->
                        new FetchAccess.Lease() {
                            @Override
                            public void close() {
                                closed[0] = true;
                            }
                        };
        final GitTreeStore store =
                new GitTreeStore(
                        runner,
                        dir.resolve("absent").toString(),
                        ORGANISATION,
                        "github.com",
                        access,
                        null);

        assertThatThrownBy(() -> store.read(forge)).isInstanceOf(IOException.class);

        assertThat(closed[0]).as("nothing stays lent").isTrue();
    }

    @Test
    void anAddressThatNamesNoForgeAndOrganisationIsRefusedAtTheOpening() {
        assertThatThrownBy(() -> GitTreeStore.open(runner, "ext::sh -c id", FetchAccess.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aShallowHistoryIsRefusedWhole() throws Exception {
        founded();
        person("alice");
        final Path shallow = dir.resolve("shallow");
        runner.runOrFail(
                Command.of(
                        "git",
                        "clone",
                        "-q",
                        "--depth",
                        "1",
                        "--branch",
                        "main",
                        "file://" + repo,
                        shallow.toString()));

        assertThatThrownBy(() -> store(shallow.toString()).read(forge))
                .as("a shallow history proves nothing")
                .isInstanceOf(OrganizationException.class)
                .hasMessageContaining("shallow");
    }

    @Test
    void aRepositoryThatCannotBeFetchedIsAnError() {
        assertThatThrownBy(() -> store(dir.resolve("absent").toString()).read(forge))
                .as("an empty answer would read as no owners at all")
                .isInstanceOf(IOException.class);
    }

    /** A host made by the owner with alice as its first admin. */
    private void host() throws IOException {
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        commit("mia");
    }

    private void carried() throws IOException {
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: [demo]\n");
        commit("alice");
    }

    private static OrganizationQuestions.SigningKey row(
            final List<OrganizationQuestions.SigningKey> rows, final String key) {
        return rows.stream().filter(each -> each.key().equals(key)).findFirst().orElseThrow();
    }

    private String hostCard(final String name, final String signer) {
        return HostCard.write(
                name, "build.example.org", 22, key("carol"), 3000, 2222, 8448, keys.get(signer));
    }

    @Test
    void anOwnerMakesAHostWithItsFirstAdmin() throws Exception {
        founded();
        project();
        host();

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "alice")).as("the owner named alice").isTrue();
        assertThat(answers.isHostAdmin("build", "bob")).as("nobody else").isFalse();
        assertThat(answers.isHostAdmin("other", "alice")).as("no such host").isFalse();
    }

    @Test
    void aHostFiledByAnyoneButAnOwnerIsRefused() throws Exception {
        founded();
        project();
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "alice")).as("alice made herself one").isFalse();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/host.yml");
    }

    @Test
    void anOwnerCannotPutAProjectOnAHostWhenMakingIt() throws Exception {
        founded();
        project();
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: [demo]\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.hostOf("demo")).as("carrying is the host admin's").isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/host.yml");
    }

    @Test
    void anOwnerChangesNothingOnAHostThatStands() throws Exception {
        founded();
        project();
        host();
        write("hosts/build/host.yml", "admins:\n  - login: bob\nprojects: []\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "bob")).as("only its admins change it").isFalse();
        assertThat(answers.isHostAdmin("build", "alice")).isTrue();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/host.yml");
    }

    @Test
    void aHostAdminCarriesAProjectAndItsMembersFindTheHost() throws Exception {
        founded();
        project();
        host();
        carried();

        final OrganizationQuestions answers = read();

        assertThat(answers.hostOf("demo")).as("the one host").contains("build");
        assertThat(answers.hostsFor("bob")).as("a member's host").containsExactly("build");
        assertThat(answers.hostsFor("mia")).as("no member, no host").isEmpty();
    }

    @Test
    void aLockedMemberFindsNoHost() throws Exception {
        founded();
        project();
        host();
        carried();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n");
        commit("alice");

        assertThat(read().hostsFor("bob")).as("listed, but may nothing").isEmpty();
    }

    @Test
    void aProjectIsOnOneHostAtATime() throws Exception {
        founded();
        project();
        host();
        carried();
        write("hosts/other/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        commit("mia");
        write("hosts/other/host.yml", "admins:\n  - login: alice\nprojects: [demo]\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.hostOf("demo")).as("the first host keeps it").contains("build");
        assertThat(refusedPaths(answers)).containsExactly("hosts/other/host.yml");
    }

    @Test
    void aHostCannotCarryAProjectThatDoesNotExist() throws Exception {
        founded();
        project();
        host();
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: [ghost]\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.hostOf("ghost")).as("no such project").isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/host.yml");
    }

    @Test
    void aHostMustKeepAnAdminWithAKeyOfTheirOwn() throws Exception {
        founded();
        project();
        host();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n    key: \""
                        + key("bob")
                        + "\"\n    backup: true\nprojects: []\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "alice")).as("the file is refused whole").isTrue();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/host.yml");
    }

    @Test
    void aHostAdminsBackupKeyChangesNothingOnTheHost() throws Exception {
        founded();
        project();
        host();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: alice\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\nprojects: []\n");
        commit("alice");
        write("hosts/build/host.yml", "admins:\n  - login: bob\nprojects: []\n");
        commit("carol");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "bob")).as("a backup key places nobody").isFalse();
        assertThat(answers.isHostAdmin("build", "alice")).isTrue();
    }

    @Test
    void aLoginNamedOnlyWithABackupKeyHoldsNoHostAdminsPlace() throws Exception {
        founded();
        project();
        host();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: bob\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\nprojects: []\n");
        commit("alice");
        write("hosts/build/host.yml", "admins:\n  - login: bob\nprojects: []\n");
        commit("bob");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "bob"))
                .as("bob's own card key is no place")
                .isFalse();
        assertThat(answers.isHostAdmin("build", "alice")).isTrue();
    }

    @Test
    void aHostAdminWhoseCardIsNotVouchedForYetStopsTheWalk() throws Exception {
        founded();
        write("projects/demo/card", "name: demo\nadmin: bob\n");
        commit("mia");
        write("people/alice.card", card("alice", "alice"));
        commit("alice");
        final String before = git("rev-parse", "HEAD");
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        commit("mia");
        final String made = git("rev-parse", "HEAD");
        write("hosts/build/host.yml", "admins:\n  - login: bob\nprojects: []\n");
        commit("alice");

        assertThat(read().revision())
                .as("anybody could have filed that card, so the commit waits")
                .isEqualTo(made)
                .isNotEqualTo(before);
    }

    @Test
    void aHostAdminsCardSaysWhereTheHostIsReached() throws Exception {
        founded();
        project();
        host();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");

        assertThat(read().reach("build"))
                .as("the card's own values")
                .contains(new OrganizationQuestions.Reach("build.example.org", 22, key("carol")));
    }

    @Test
    void aHostsCardSignedOrCommittedByAnyoneElseSaysNothing() throws Exception {
        founded();
        project();
        host();
        write("hosts/build/card", hostCard("build", "bob"));
        commit("bob");
        write("hosts/build/card", hostCard("build", "mallory"));
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.reach("build")).as("neither counts").isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/card", "hosts/build/card");
    }

    @Test
    void aHostsCardNamingAnotherHostSaysNothing() throws Exception {
        founded();
        project();
        host();
        write("hosts/build/card", hostCard("other", "alice"));
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.reach("build")).as("copied from another host").isEmpty();
        assertThat(answers.reach("other")).as("nor there").isEmpty();
        assertThat(refusedPaths(answers)).containsExactly("hosts/build/card");
    }

    @Test
    void anOwnerRemovesAHostWithItsCardAndNobodyElseCan() throws Exception {
        founded();
        project();
        host();
        carried();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        Files.delete(repo.resolve("hosts/build/host.yml"));
        commit("alice");
        assertThat(read().isHostAdmin("build", "alice")).as("its admin cannot").isTrue();
        Files.delete(repo.resolve("hosts/build/card"));
        commit("mia");
        assertThat(read().reach("build"))
                .as("the owner takes no card off a host that stays")
                .isPresent();
        assertThat(refusedPaths(read())).as("and is told so").contains("hosts/build/card");

        carried();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        Files.delete(repo.resolve("hosts/build/host.yml"));
        Files.delete(repo.resolve("hosts/build/card"));
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.isHostAdmin("build", "alice")).as("the owner can").isFalse();
        assertThat(answers.reach("build")).as("its card went with it").isEmpty();
        assertThat(answers.hostOf("demo")).as("and what it carried").isEmpty();
    }

    @Test
    void aProjectsAdminFilesItsAddress() throws Exception {
        founded();
        project();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");

        assertThat(read().getProjectRepository("demo")).contains("git@github.com:acme/demo.git");
    }

    @Test
    void anAddressFromADeveloperOrAnOwnerIsRefused() throws Exception {
        founded();
        project();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("bob");
        write("projects/demo/repository.yml", "address: git@github.com:acme/other.git\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.getProjectRepository("demo")).as("only its admins").isEmpty();
        assertThat(refusedPaths(answers))
                .containsExactly("projects/demo/repository.yml", "projects/demo/repository.yml");
    }

    @Test
    void anAddressHoldingACredentialOrAProgramIsRefusedAndNotQuoted() throws Exception {
        founded();
        project();
        write(
                "projects/demo/repository.yml",
                "address: https://bob:tok-4711@example.org/demo.git\n");
        commit("alice");
        write("projects/demo/repository.yml", "address: \"ext::sh -c tok-4712\"\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.getProjectRepository("demo")).as("neither is an address").isEmpty();
        assertThat(answers.getRefusals())
                .as("a refusal must not carry the token it refused")
                .hasSize(2)
                .noneMatch(refusal -> refusal.why().contains("tok-471"));
    }

    @Test
    void aFiledAddressCannotBeRemovedWhileItsProjectStands() throws Exception {
        founded();
        project();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        Files.delete(repo.resolve("projects/demo/repository.yml"));
        commit("alice");

        assertThat(read().getProjectRepository("demo"))
                .as("a change of address replaces it")
                .contains("git@github.com:acme/demo.git");
    }

    @Test
    void anOwnerRemovesAProjectWithItsFilesAndItLeavesItsHost() throws Exception {
        founded();
        project();
        host();
        carried();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        Files.delete(repo.resolve("projects/demo/card"));
        Files.delete(repo.resolve("projects/demo/members.yml"));
        Files.delete(repo.resolve("projects/demo/repository.yml"));
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.getRefusals()).as("one change, whole").isEmpty();
        assertThat(answers.projectAdmins("demo")).as("no such project").isEmpty();
        assertThat(answers.hostOf("demo")).as("it left its host").isEmpty();
        assertThat(answers.getProjectRepository("demo")).as("and its address").isEmpty();
    }

    @Test
    void aRemovedProjectMadeAgainStartsWithoutItsOldAddressOrHost() throws Exception {
        founded();
        project();
        host();
        carried();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        Files.delete(repo.resolve("projects/demo/card"));
        Files.delete(repo.resolve("projects/demo/members.yml"));
        Files.delete(repo.resolve("projects/demo/repository.yml"));
        commit("mia");
        write("projects/demo/card", "name: demo\nadmin: bob\n");
        commit("mia");

        final OrganizationQuestions answers = read();

        assertThat(answers.hostOf("demo")).as("no host carries it by its old name").isEmpty();
        assertThat(answers.getProjectRepository("demo")).as("no old address").isEmpty();
    }

    @Test
    void projectAdminsAreTheMembersWithThatRoleAndNoLock() throws Exception {
        founded();
        project();
        person("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: admin\n    locked: true\n");
        commit("alice");

        assertThat(read().projectAdmins("demo"))
                .as("a developer and a locked admin may not act as one")
                .contains(List.of("alice"));
    }

    @Test
    void aMemberWhoseCardIsRefusedOrAbsentHoldsNoRole() throws Exception {
        founded();
        project();
        host();
        carried();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: admin\n");
        commit("alice");
        forge.keys.put("bob", List.of(key("mallory")));
        write("people/bob.card", card("bob", "bob"));
        commit("bob");

        final OrganizationQuestions answers = read();

        assertThat(answers.isProjectMember("demo", "carol")).as("no card at all").isEmpty();
        assertThat(answers.projectAdmins("demo")).as("nor as admin").contains(List.of("alice"));
        assertThat(answers.hostsFor("carol")).as("nor a host").isEmpty();
    }

    private Path state() {
        return dir.resolve("state");
    }

    private Path trustRecord() {
        return state().resolve("trust-record.json");
    }

    private GitTreeStore keeping() {
        return new GitTreeStore(
                runner, repo.toString(), ORGANISATION, "github.com", FetchAccess.NONE, state());
    }

    @Test
    void aReadKeepsARecordOwnerOnlyAndWhole() throws Exception {
        founded();
        project();

        keeping().read(forge);

        assertThat(Files.getPosixFilePermissions(trustRecord()))
                .as("the record is the person's alone")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(state()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        try (var left = Files.list(state())) {
            assertThat(left).as("nothing staged stays").containsExactly(trustRecord());
        }
    }

    @Test
    void aKeyThatLeavesTheForgeLaterKeepsItsRightsOnAMachineThatTrustedIt() throws Exception {
        founded();
        project();
        keeping().read(forge);
        forge.keys.put("alice", List.of());
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.isProjectMember("demo", "bob"))
                .as("the record said alice's key was trusted, and she removed bob")
                .isEmpty();
        assertThat(answers.getWarnings()).as("nothing was read afresh").isEmpty();
    }

    @Test
    void anOlderStateServedAgainIsRefusedAndTheRecordStays() throws Exception {
        founded();
        project();
        final String earlier = git("rev-parse", "HEAD~1");
        keeping().read(forge);
        final String kept = Files.readString(trustRecord());
        git("reset", "-q", "--hard", earlier);

        assertThatThrownBy(() -> keeping().read(forge))
                .as("a rollback hides a removal or a revocation")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo("refused-state"));
        assertThat(Files.readString(trustRecord())).as("the record stays").isEqualTo(kept);
    }

    @Test
    void aForkOfTheLineIsRefused() throws Exception {
        founded();
        person("alice");
        keeping().read(forge);
        git("reset", "-q", "--hard", "HEAD~1");
        write("people/bob.card", card("bob", "bob"));
        commit("bob");

        assertThatThrownBy(() -> keeping().read(forge))
                .as("what the record trusted is gone from the line")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo("refused-state"));
    }

    @Test
    void anUnreadableRecordIsDiscardedSaidSoAndReadAfresh() throws Exception {
        founded();
        project();
        keeping().read(forge);
        Files.writeString(trustRecord(), "{\"address\": \"tok-4711\"");

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.isProjectMember("demo", "bob")).as("read afresh").isPresent();
        assertThat(answers.getWarnings())
                .as("it says so, and quotes nothing")
                .hasSize(1)
                .noneMatch(warning -> warning.contains("tok-4711"));
        assertThat(TreeRecord.read(Files.readString(trustRecord()), repo.toString()).commit())
                .as("and keeps a good one")
                .isEqualTo(answers.revision());
    }

    @Test
    void aRecordOfAnotherAddressSpeaksForNothing() throws Exception {
        founded();
        project();
        keeping().read(forge);
        final Path other = dir.resolve("other");
        Files.copy(trustRecord(), other);
        Files.writeString(
                trustRecord(),
                Files.readString(other).replace(repo.toString(), "git@example.org:acme/other"));

        assertThat(keeping().read(forge).getWarnings())
                .as("another organization's trust says nothing of this one")
                .hasSize(1);
    }

    @Test
    void aRecordWhoseCardIsNotTheOneItNamesIsDiscarded() throws Exception {
        founded();
        project();
        keeping().read(forge);
        Files.writeString(
                trustRecord(),
                Files.readString(trustRecord())
                        .replace(
                                PersonCard.fingerprint(key("alice")),
                                PersonCard.fingerprint(key("mallory"))));

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.getWarnings()).as("the card says another key").hasSize(1);
        assertThat(answers.isProjectMember("demo", "alice")).as("read afresh").contains("admin");
    }

    @Test
    void aRecordOthersCanWriteOrALinkIsDiscarded() throws Exception {
        founded();
        keeping().read(forge);
        Files.setPosixFilePermissions(trustRecord(), PosixFilePermissions.fromString("rw-rw----"));

        assertThat(keeping().read(forge).getWarnings()).as("group may write it").hasSize(1);

        final Path elsewhere = dir.resolve("elsewhere");
        Files.move(trustRecord(), elsewhere);
        Files.createSymbolicLink(trustRecord(), elsewhere);

        assertThat(keeping().read(forge).getWarnings()).as("a link is no record").hasSize(1);
    }

    @Test
    void aCommitThatWaitedOnACardIsJudgedOnceTheSourceCanSay() throws Exception {
        founded();
        project();
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: admin\n");
        commit("alice");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: carol\n    role: admin\n");
        final String waited = commit("carol");
        final String stopped = git("rev-parse", "HEAD~1");

        assertThat(keeping().read(forge).revision()).as("carol is first-seen").isEqualTo(stopped);

        forge.keys.put("carol", List.of(key("carol")));
        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.revision()).as("the next read judges it").isEqualTo(waited);
        assertThat(answers.isProjectMember("demo", "bob")).as("carol removed bob").isEmpty();
    }

    @Test
    void aFirstSeenCardTheSourceNowDeniesIsAskedAboutAgainOnAContinuedWalk() throws Exception {
        founded();
        project();
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: carol\n    role: developer\n");
        commit("alice");
        assertThat(keeping().read(forge).isProjectMember("demo", "carol"))
                .as("a listed member who is first-seen holds their role")
                .contains("developer");

        forge.keys.put("carol", List.of(key("mallory")));

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.isProjectMember("demo", "carol")).as("the source says no").isEmpty();
        assertThat(answers.getWarnings()).as("read afresh, and said").hasSize(1);
    }

    @Test
    void aRefusedCardTheSourceNowListsCountsWithTheCommitsItSigned() throws Exception {
        founded();
        project();
        forge.keys.put("carol", List.of(key("mallory")));
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write("projects/demo/card", "name: demo\nadmin: carol\n");
        commit("mia");
        write("projects/other/card", "name: other\nadmin: carol\n");
        commit("mia");
        write("projects/other/members.yml", "members:\n  - login: carol\n    role: admin\n");
        commit("carol");
        assertThat(keeping().read(forge).projectAdmins("other"))
                .as("carol's card was refused, and her commit with it")
                .contains(List.of());

        forge.keys.put("carol", List.of(key("carol")));

        assertThat(keeping().read(forge).projectAdmins("other"))
                .as("a continued walk answers as a fresh one does")
                .contains(List.of("carol"));
    }

    @Test
    void aReadAtARevisionKeepsNothing() throws Exception {
        founded();
        project();
        final String earlier = git("rev-parse", "HEAD~1");

        keeping().read(forge, earlier);

        assertThat(trustRecord()).as("a state asked for is no state trusted").doesNotExist();
    }

    @Test
    void aReadAtARevisionAroundTheRecordAnswersThatRevisionAndLeavesIt() throws Exception {
        founded();
        project();
        final String before = git("rev-parse", "HEAD~1");
        final String at = git("rev-parse", "HEAD");
        keeping().read(forge);
        final String kept = Files.readString(trustRecord());
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        final String later = commit("alice");

        final OrganizationQuestions earlier = keeping().read(forge, before);
        final OrganizationQuestions same = keeping().read(forge, at);
        final OrganizationQuestions after = keeping().read(forge, later);

        assertThat(earlier.revision()).isEqualTo(before);
        assertThat(earlier.isProjectMember("demo", "bob"))
                .as("the first members were not written yet")
                .isEmpty();
        assertThat(same.revision()).isEqualTo(at);
        assertThat(same.isProjectMember("demo", "bob")).as("as the record has it").isPresent();
        assertThat(after.isProjectMember("demo", "bob")).as("walked on").isEmpty();
        assertThat(Files.readString(trustRecord())).as("none of them keeps").isEqualTo(kept);
    }

    @Test
    void aRevokedKeyStaysRevokedAcrossARecord() throws Exception {
        founded();
        project();
        write("people/alice.card", card("alice", "alice-new"));
        commit("mia");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        keeping().read(forge);
        write("projects/demo/members.yml", "members:\n  - login: alice\n    role: admin\n");
        commit("alice");

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.isProjectMember("demo", "bob"))
                .as("the record remembers what no file says")
                .contains("developer");
        assertThat(refusedPaths(answers)).contains("projects/demo/members.yml");
    }

    @Test
    void theLastStateIsAnsweredWholeWithoutTheRepository() throws Exception {
        founded();
        project();
        person("carol");
        host();
        carried();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        write("owners.yml", owners("mallory"));
        commit("mallory");
        final OrganizationQuestions read = keeping().read(forge);
        git("reset", "-q", "--hard", "HEAD~1");
        Files.walk(repo.resolve(".git"))
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());

        final OrganizationQuestions last = keeping().last().orElseThrow();

        assertThat(last.revision()).isEqualTo(read.revision());
        assertThat(last.isOrganizationOwner("mia")).isTrue();
        assertThat(last.isProjectMember("demo", "bob")).contains("developer");
        assertThat(last.projectAdmins("demo")).contains(List.of("alice"));
        assertThat(last.isHostAdmin("build", "alice")).isTrue();
        assertThat(last.hostOf("demo")).contains("build");
        assertThat(last.reach("build")).isEqualTo(read.reach("build")).isPresent();
        assertThat(last.getProjectRepository("demo")).contains("git@github.com:acme/demo.git");
        assertThat(last.getRefusals()).isEqualTo(read.getRefusals()).isNotEmpty();
    }

    @Test
    void aRecordKeepsEveryFieldAWalkLeaves() throws Exception {
        founded();
        project();
        person("carol");
        write(
                "owners.yml",
                owners("mia")
                        + "  - login: mia\n    key: \""
                        + key("mallory")
                        + "\"\n    backup: true\n");
        commit("mia");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n"
                        + "  - login: carol\n    role: developer\n");
        commit("alice");
        write("people/carol.card", handedOn("carol", "carol-new", "carol"));
        commit("carol-new");
        host();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: alice\n    key: \""
                        + key("bob")
                        + "\"\n    backup: true\nprojects: [demo]\n");
        commit("alice");
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        write("people/bob.card", card("bob", "alice-new"));
        commit("mia");

        keeping().read(forge);

        final String written = Files.readString(trustRecord());
        assertThat(TreeRecord.read(written, repo.toString()).toJson(repo.toString()))
                .as(
                        "what it reads is what it wrote, so a continued walk starts from the same state")
                .isEqualTo(written);
        assertThat(written)
                .as("this state holds what a test of the fields must see")
                .contains(
                        "\"locked\":true", "backup_key", "\"backup\":true", "handed_on", "revoked");
    }

    @Test
    void aFreshWalkTheSourceStopsEarlierLeavesTheRecordAtItsCommit() throws Exception {
        founded();
        project();
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        keeping().read(forge);
        final String kept = Files.readString(trustRecord());
        forge.keys.put("carol", List.of(key("mallory")));
        forge.keys.remove("alice");

        final OrganizationQuestions answers = keeping().read(forge);

        assertThat(answers.isProjectMember("demo", "bob"))
                .as("alice's commit waits on the source again")
                .isEmpty();
        assertThat(Files.readString(trustRecord()))
                .as("a record never goes back, or a rollback to there would pass")
                .isEqualTo(kept);
    }

    @Test
    void aRecordThatCannotBeWrittenFailsTheReadAndLeavesNothingStaged() throws Exception {
        founded();
        Files.createDirectories(trustRecord().resolve("in-the-way"));

        assertThatThrownBy(() -> keeping().read(forge))
                .as("a read that cannot keep its trust says so")
                .isInstanceOf(IOException.class);

        try (var left = Files.list(state())) {
            assertThat(left).as("no half record stays").containsExactly(trustRecord());
        }
    }

    @Test
    void aRefusalStaysVisibleOnAContinuedWalk() throws Exception {
        founded();
        write("owners.yml", owners("mallory"));
        commit("mallory");
        keeping().read(forge);
        write("people/mia.card", card("mia", "mia"));
        commit("mia");

        assertThat(refusedPaths(keeping().read(forge)))
                .as("a change somebody tried and was refused is not forgotten by the next read")
                .contains("owners.yml");
    }

    @Test
    void signingKeysAreTheActingMembersKeysWithTheirDates() throws Exception {
        founded();
        project();

        final List<OrganizationQuestions.SigningKey> rows = read().signingKeys("demo");

        assertThat(rows)
                .extracting(OrganizationQuestions.SigningKey::login)
                .containsExactly("alice", "bob");
        assertThat(row(rows, key("alice")))
                .isEqualTo(
                        new OrganizationQuestions.SigningKey(
                                "alice",
                                "admin",
                                key("alice"),
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2030, 1, 1),
                                null));
        assertThat(read().signingKeys("ghost")).as("no such project").isEmpty();
    }

    @Test
    void anEarlierKeyGetsARowSayingSinceWhenItNoLongerHolds() throws Exception {
        founded();
        project();
        write("people/alice.card", handedOn("alice", "alice-new", "alice"));
        final String handed = commit("alice-new");
        final String date = git("show", "-s", "--format=%cI", handed);

        final List<OrganizationQuestions.SigningKey> rows = read().signingKeys("demo");

        assertThat(row(rows, key("alice")).revokedSince())
                .as("the commit that replaced it says when")
                .isEqualTo(date);
        assertThat(row(rows, key("alice-new")).revokedSince()).as("in force").isNull();
    }

    @Test
    void aLockedMemberAndOneWithNoAcceptedCardGetNoSigningKey() throws Exception {
        founded();
        project();
        person("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n"
                        + "  - login: carol\n    role: developer\n"
                        + "  - login: mallory\n    role: developer\n");
        commit("alice");
        forge.keys.put("carol", List.of(key("mallory")));

        assertThat(read().signingKeys("demo"))
                .as("only a key that may sign is handed on to judge commits with")
                .extracting(OrganizationQuestions.SigningKey::login)
                .containsExactly("alice");
    }

    @Test
    void aHostAdminsBackupKeyIsNoSigningKey() throws Exception {
        founded();
        project();
        write("people/bob.card", card("bob", "bob"));
        commit("bob");
        host();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: alice\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\nprojects: []\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.signingKeys("demo"))
                .extracting(OrganizationQuestions.SigningKey::key)
                .doesNotContain(key("carol"));
        assertThat(answers.getHosts().getFirst().admins())
                .as("one row per key, the pin of a host's first start")
                .containsExactly(
                        new Organization.Owner(
                                "alice", key("alice"), PersonCard.fingerprint(key("alice")), false),
                        new Organization.Owner(
                                "alice", key("carol"), PersonCard.fingerprint(key("carol")), true));
    }

    @Test
    void trustsSaysYesNoOrCannotSayAndNeverGuesses() throws Exception {
        founded();
        project();
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        forge.keys.put("mallory", List.of(key("alice")));
        write("people/mallory.card", card("mallory", "mallory"));
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.trusts("alice", key("alice")).answer()).isEqualTo("yes");
        assertThat(answers.trusts("alice", key("bob")).answer()).as("another key").isEqualTo("no");
        assertThat(answers.trusts("ghost", key("bob")).answer()).as("no card").isEqualTo("no");
        assertThat(answers.trusts("alice", "not a key").answer()).as("no key").isEqualTo("no");
        assertThat(answers.trusts("carol", key("carol")).answer())
                .as("the source has not said")
                .isEqualTo("cannot-say");
        assertThat(answers.trusts("mallory", key("mallory")))
                .as("the source said no, and why comes along")
                .satisfies(trust -> assertThat(trust.answer()).isEqualTo("no"))
                .satisfies(trust -> assertThat(trust.why()).isNotBlank());
    }

    @Test
    void keyHolderIsWhoHoldsAKeyOnAnAcceptedCard() throws Exception {
        founded();
        project();
        forge.keys.put("mallory", List.of(key("alice")));
        write("people/mallory.card", card("mallory", "mallory"));
        commit("mallory");

        final OrganizationQuestions answers = read();

        assertThat(answers.keyHolder(key("alice"))).contains("alice");
        assertThat(answers.keyHolder(key("mallory"))).as("a refused card holds none").isEmpty();
        assertThat(answers.keyHolder(key("carol"))).as("nobody").isEmpty();
        assertThat(answers.keyHolder("not a key")).isEmpty();
    }

    @Test
    void personsAreAnsweredARefusedOneSayingWhy() throws Exception {
        founded();
        project();
        forge.keys.put("mallory", List.of(key("alice")));
        write("people/mallory.card", card("mallory", "mallory"));
        commit("mallory");

        final List<OrganizationQuestions.Person> persons = read().getPersons();

        assertThat(persons)
                .extracting(OrganizationQuestions.Person::login)
                .containsExactly("alice", "bob", "mallory");
        assertThat(persons.getFirst().state()).isEqualTo(Organization.TRUSTED);
        assertThat(persons.getFirst().expires()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(persons.getLast().state()).isEqualTo(Organization.REFUSED);
        assertThat(persons.getLast().why()).as("a refused card says why").isNotBlank();
    }

    @Test
    void aRevokedKeyIsSaidOfItsCard() throws Exception {
        founded();
        project();
        forge.keys.put("carol", List.of(key("alice")));
        write("people/carol.card", card("carol", "alice"));
        commit("alice");
        forge.keys.put("alice", List.of(key("alice-new")));
        write("people/alice.card", card("alice", "alice-new"));
        commit("mia");

        final OrganizationQuestions.Person carol =
                read().getPersons().stream()
                        .filter(each -> each.login().equals("carol"))
                        .findFirst()
                        .orElseThrow();

        assertThat(carol.revoked()).as("carol's card held the key the owner replaced").isTrue();
        assertThat(carol.state())
                .as("a new machine agrees with one that saw the key in force")
                .isEqualTo(Organization.REFUSED);
    }

    @Test
    void projectsAndTheirMembersAreAnsweredAsListed() throws Exception {
        founded();
        project();
        host();
        carried();
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n"
                        + "  - login: bob\n    role: developer\n    locked: true\n");
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.getProjects())
                .containsExactly(
                        new OrganizationQuestions.Project(
                                "demo", List.of("alice"), List.of("bob"), "alice", true, "build"));
        assertThat(answers.getProjectMembers("demo"))
                .as("a locked member stays listed")
                .contains(
                        List.of(
                                new OrganizationQuestions.Member("alice", "admin", false),
                                new OrganizationQuestions.Member("bob", "developer", true)));
        assertThat(answers.getProjectMembers("ghost")).isEmpty();
    }

    @Test
    void cardsAreAnsweredAsTheyWereFiled() throws Exception {
        founded();
        project();
        host();
        final String text = hostCard("build", "alice");
        write("hosts/build/card", text);
        commit("alice");

        final OrganizationQuestions answers = read();

        assertThat(answers.getCard("alice")).contains(card("alice", "alice"));
        assertThat(answers.getCard("ghost")).isEmpty();
        assertThat(answers.getHostCard("build")).contains(text);
        assertThat(answers.getHostCard("ghost")).isEmpty();
        assertThat(answers.getHosts().getFirst().card().matrixPort()).isEqualTo(8448);
    }

    @Test
    void ownersAreAnsweredWithTheirBackupKeys() throws Exception {
        founded();
        write(
                "owners.yml",
                owners("mia")
                        + "  - login: mia\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\n");
        commit("mia");

        assertThat(read().getOrganizationOwners())
                .extracting(Organization.Owner::key, Organization.Owner::backup)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(key("mia"), false),
                        org.assertj.core.groups.Tuple.tuple(key("carol"), true));
    }

    @Test
    void aPlaceHeldByOneKeyWithNoBackupIsNamed() throws Exception {
        founded();
        project();
        host();

        assertThat(read().getSoleWithoutBackup())
                .containsExactly(
                        new Organization.Sole("owner", null, "mia"),
                        new Organization.Sole("host-admin", "build", "alice"));

        write(
                "owners.yml",
                owners("mia")
                        + "  - login: mia\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\n");
        commit("mia");
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: alice\n    key: \""
                        + key("bob")
                        + "\"\n    backup: true\nprojects: []\n");
        commit("alice");

        assertThat(read().getSoleWithoutBackup()).as("both now have a way back").isEmpty();
    }

    @Test
    void theOrganizationIsDescribedInWords() throws Exception {
        founded();

        assertThat(read().describe())
                .isEqualTo(
                        new OrganizationQuestions.Description(
                                ORGANISATION, "the GitHub organization acme", "GitHub"));
    }

    @Test
    void noRecordMeansNoLastState() throws Exception {
        assertThat(keeping().last()).isEmpty();
    }

    @Test
    void aProgramAsTheRepositorysAddressIsNotRun() throws Exception {
        final Path planted = dir.resolve("planted");

        assertThatThrownBy(() -> store("ext::sh -c touch% " + planted).read(forge))
                .isInstanceOf(IOException.class);

        assertThat(planted).as("ext:: stays forbidden").doesNotExist();
    }

    /** Asks the store as a host does, with the pin as fingerprints of these logins. */
    private Map<String, Object> fetch(final String since, final String... pinned)
            throws IOException {
        final java.util.Set<String> pin = new java.util.HashSet<>();
        for (final String login : pinned) {
            pin.add(PersonCard.fingerprint(key(login)));
        }
        return new GitTreeOffline(store(repo.toString()), forge).check("build", pin, since);
    }

    /** A host with its card, as a first fetch finds it: alice, carrying demo with bob. */
    private String carryingHost() throws IOException {
        founded();
        project();
        host();
        carried();
        write("hosts/build/card", hostCard("build", "alice"));
        return commit("alice");
    }

    private static void refusedAs(
            final String reason, final org.assertj.core.api.ThrowableAssert.ThrowingCallable read) {
        assertThatThrownBy(read)
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    @Test
    void aHostGetsItsStateAndOnlyThePersonsItNeeds() throws Exception {
        final String tip = carryingHost();
        person("carol");

        final Map<String, Object> answer = fetch("", "alice");

        assertThat(answer.get("revision")).as("carol's card is later").isNotEqualTo(tip);
        assertThat((List<?>) answer.get("persons"))
                .as("carol is in no project here")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .containsExactly("alice", "bob");
        final Map<?, ?> project = (Map<?, ?>) ((List<?>) answer.get("projects")).getFirst();
        assertThat(project.get("project")).isEqualTo("demo");
        assertThat((List<?>) project.get("signing_keys")).isNotEmpty();
        assertThat(((Map<?, ?>) answer.get("host_card")).get("address"))
                .isEqualTo("build.example.org");
        assertThat((List<?>) answer.get("admins")).hasSize(1);
    }

    @Test
    void aChangeOfTheHostsFilesNotSignedForThePinIsRefused() throws Exception {
        final String taken = carryingHost();
        write("hosts/build/card", hostCard("build", "mia"));
        final String changed = commit("mia");

        assertThatThrownBy(() -> fetch(taken, "alice"))
                .as("the host's admin must see what was changed and by whom, to judge it")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo("refused-state");
                            assertThat(refused.getMessage())
                                    .contains(changed)
                                    .contains(PersonCard.fingerprint(key("mia")));
                        });
    }

    @Test
    void anUnsignedChangeOfTheHostsFilesIsRefused() throws Exception {
        final String taken = carryingHost();
        write("hosts/build/card", "not signed\n");
        commit(null);

        refusedAs("refused-state", () -> fetch(taken, "alice"));
    }

    @Test
    void anOlderStateServedAgainIsRefused() throws Exception {
        final String taken = carryingHost();
        final String before = git("rev-parse", "HEAD~1");
        git("reset", "-q", "--hard", before);

        refusedAs("refused-state", () -> fetch(taken, "alice"));
    }

    @Test
    void aHistoryNothingSignedForThePinVouchesForIsRefusedOnAFirstFetch() throws Exception {
        carryingHost();

        refusedAs("refused-state", () -> fetch("", "mallory"));
    }

    @Test
    void aNewAdminIsAVerifiedHandoverOfThePin() throws Exception {
        final String taken = carryingHost();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: bob\nprojects: [demo]\n");
        commit("alice");
        write("hosts/build/card", hostCard("build", "bob"));
        final String last = commit("bob");

        final Map<String, Object> answer = fetch(taken, "alice");

        assertThat(answer.get("revision")).as("bob now signs for the pin").isEqualTo(last);
        assertThat((List<?>) answer.get("admins")).hasSize(2);
    }

    @Test
    void aFounderWhoseKeyLeftTheForgeStandsOnAHostUnderItsPin() throws Exception {
        final String tip = carryingHost();
        forge.keys.put("mia", List.of(key("mallory")));
        forge.owners.clear();

        assertThat(fetch("", "alice").get("revision"))
                .as("a commit signed for the pin signs every commit before it")
                .isEqualTo(tip);
    }

    @Test
    void aStrangersFirstCardForAMemberIsNoCardOnAHost() throws Exception {
        founded();
        project();
        host();
        carried();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        forge.keys.put("carol", List.of(key("carol")));
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: developer\n");
        commit("alice");
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");

        assertThat((List<?>) fetch("", "alice").get("persons"))
                .as("the forge lists another key for carol")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .doesNotContain("carol");
    }

    @Test
    void aFirstCardTheForgeCannotSayOfWaitsAndTheCommitIsJudgedAgain() throws Exception {
        carryingHost();
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: admin\n");
        final String named = commit("alice");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: carol\n    role: admin\n");
        commit("carol");

        assertThat(fetch("", "alice").get("revision"))
                .as("the forge says nothing of carol, so what needs her right waits")
                .isEqualTo(named);
    }

    @Test
    void aWalkStoppedBeforeTheCommitTakenCannotSayRatherThanGoBack() throws Exception {
        final String taken = carryingHost();
        forge.keys.remove("alice");

        refusedAs("cannot-say", () -> fetch(taken, "alice"));
    }

    @Test
    void aRecordLaterThanTheCommitTakenDoesNotPassCommitsNoPinJudged() throws Exception {
        final String taken = carryingHost();
        write("hosts/build/card", hostCard("build", "mia"));
        commit("mia");
        keeping().read(forge);
        final java.util.Set<String> pin = java.util.Set.of(PersonCard.fingerprint(key("alice")));

        refusedAs(
                "refused-state",
                () -> new GitTreeOffline(keeping(), forge).check("build", pin, taken));
    }

    @Test
    void aHostNotInTheOrganizationIsNotFound() throws Exception {
        final String taken = carryingHost();
        final java.util.Set<String> pin = java.util.Set.of(PersonCard.fingerprint(key("alice")));

        refusedAs(
                "not-found",
                () -> new GitTreeOffline(store(repo.toString()), forge).check("other", pin, taken));
    }

    @Test
    void theSecondFetchFromTheCommitTakenAsksTheForgeNothingNew() throws Exception {
        final String taken = carryingHost();
        final java.util.concurrent.atomic.AtomicInteger asked =
                new java.util.concurrent.atomic.AtomicInteger();
        final IdentitySource counting =
                new IdentitySource() {
                    @Override
                    public Optional<Keys> keys(final String login) {
                        asked.incrementAndGet();
                        return forge.keys(login);
                    }

                    @Override
                    public Optional<Boolean> owns(final String organisation, final String login) {
                        return forge.owns(organisation, login);
                    }
                };
        final java.util.Set<String> pin = java.util.Set.of(PersonCard.fingerprint(key("alice")));
        new GitTreeOffline(keeping(), counting).check("build", pin, "");
        asked.set(0);

        new GitTreeOffline(keeping(), counting).check("build", pin, taken);

        assertThat(asked.get())
                .as("an anonymous forge allows 60 calls an hour, and this runs every five minutes")
                .isZero();
    }

    /**
     * A card filed for a listed member, of a key the forge has not been able to say anything of.
     */
    private void firstSeenCarol() throws IOException {
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: developer\n");
        commit("alice");
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");
    }

    @Test
    void aFirstSeenCardGivesAHostNoKeyThoughTheRoleStaysAnswered() throws Exception {
        carryingHost();
        firstSeenCarol();

        final Map<String, Object> answer = fetch("", "alice");

        assertThat((List<?>) answer.get("persons"))
                .as(
                        "anyone with write access could have filed that card while the forge was silent")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .doesNotContain("carol");
        final Map<?, ?> project = (Map<?, ?>) ((List<?>) answer.get("projects")).get(0);
        assertThat((List<?>) project.get("signing_keys"))
                .as("commit verify would take their pushes as carol's")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .doesNotContain("carol");
        assertThat((List<?>) project.get("members"))
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .contains("carol");
    }

    @Test
    void aCardThatDoesNotReadNeverReachesAHostsPersonsAndTheOthersStay() throws Exception {
        carryingHost();
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: broken\n    role: developer\n");
        write("people/broken.card", "login: broken\nkey: not a key\n");
        commit("alice");

        final List<?> persons = (List<?>) fetch("", "alice").get("persons");

        assertThat(persons)
                .as("a host stops revoking for everyone if one person's row cannot be read")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .doesNotContain("broken")
                .contains("alice");
        for (final Object each : persons) {
            final Map<?, ?> row = (Map<?, ?>) each;
            assertThat(String.valueOf(row.get("login"))).matches("[a-z][a-z0-9-]{0,31}");
            assertThat(String.valueOf(row.get("key"))).startsWith("ssh-ed25519 ");
            assertThat(java.time.LocalDate.parse(String.valueOf(row.get("expires")))).isNotNull();
            assertThat(row.get("revoked")).isInstanceOf(Boolean.class);
        }
    }

    @Test
    void aFirstSeenAdminIsNotAnsweredWithAKeyForRootsLogin() throws Exception {
        carryingHost();
        write("people/carol.card", card("carol", "mallory"));
        commit("mallory");
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: carol\nprojects: [demo]\n");
        commit("alice");

        assertThat((List<?>) fetch("", "alice").get("admins"))
                .as("whoever filed that card would get in as the host's admin")
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .containsExactly("alice");
    }

    @Test
    void anOwnerWhoseCardIsRefusedIsAnsweredAsNoOwner() throws Exception {
        carryingHost();
        forge.keys.put("carol", List.of(key("carol")));
        write("people/carol.card", card("carol", "carol"));
        commit("carol");
        write("owners.yml", owners("mia", "carol"));
        commit("mia");
        assertThat(read().isOrganizationOwner("carol")).isTrue();

        forge.keys.put("carol", List.of(key("mallory")));

        final OrganizationQuestions now = read();
        assertThat(now.isOrganizationOwner("carol"))
                .as("me.owner beside me.card: refused says they may do what they may not")
                .isFalse();
        assertThat(now.isOrganizationOwner("mia")).isTrue();
    }

    @Test
    void aFirstSeenCardJudgesNoProjectFile() throws Exception {
        carryingHost();
        firstSeenCarol();

        assertThat(read().signingKeys("demo"))
                .as("whoever filed that card under an admin's login could set the project's file")
                .extracting(OrganizationQuestions.SigningKey::login)
                .contains("alice")
                .doesNotContain("carol");
    }

    @Test
    void aFirstSeenCardBecomesAHostsKeyOnceTheForgeVouchesForIt() throws Exception {
        carryingHost();
        firstSeenCarol();
        forge.keys.put("carol", List.of(key("mallory")));

        final Map<?, ?> project = (Map<?, ?>) ((List<?>) fetch("", "alice").get("projects")).get(0);

        assertThat((List<?>) project.get("signing_keys"))
                .extracting(row -> (Object) ((Map<?, ?>) row).get("login"))
                .contains("carol");
    }

    @Test
    void aFirstSeenAdminsKeyNeverJoinsThePin() throws Exception {
        final TreeModel model = TreeModel.empty();
        model.people()
                .put(
                        "carol",
                        new TreeModel.Person(
                                PersonCard.fingerprint(key("mallory")),
                                Organization.FIRST_SEEN,
                                List.of(),
                                "",
                                card("carol", "mallory"),
                                List.of()));
        model.hosts()
                .put(
                        "build",
                        new TreeModel.HostState(
                                new TreeFiles.HostFile(
                                        List.of(new TreeFiles.HostAdmin("carol", null)), List.of()),
                                null));
        final String alice = PersonCard.fingerprint(key("alice"));
        final TreePin pin =
                new TreePin(
                        "build",
                        java.util.Set.of(alice),
                        "",
                        false,
                        new OrganizationQuestions.Description("acme", "", ""));

        pin.passed("c1", alice, List.of("hosts/build/host.yml"), model);

        refusedAs(
                "refused-state",
                () ->
                        pin.passed(
                                "c2",
                                PersonCard.fingerprint(key("mallory")),
                                List.of("hosts/build/host.yml"),
                                model));
    }

    @Test
    void anOwnersReplacementOfAnAdminsCardLeavesTheHostAtRefusedState() throws Exception {
        final String taken = carryingHost();
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", card("alice", "alice-new"));
        final String replaced = commit("mia");

        assertThatThrownBy(() -> fetch(taken, "alice"))
                .as("a stolen owner's key must not rewrite a host's root of trust")
                .isInstanceOfSatisfying(
                        OrganizationQuestions.Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo("refused-state");
                            assertThat(refused.getMessage()).contains(replaced).contains("alice");
                        });
    }

    @Test
    void aKeyTheAdminHandedOnFollowsThePin() throws Exception {
        final String taken = carryingHost();
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", handedOn("alice", "alice-new", "alice"));
        commit("alice-new");
        write("hosts/build/card", hostCard("build", "alice-new"));
        commit("alice-new");

        assertThat(fetch(taken, "alice").get("revision")).isEqualTo(git("rev-parse", "HEAD"));
    }

    @Test
    void aBackupTheAdminFiledMovesThePinToTheReplacement() throws Exception {
        final String taken = carryingHost();
        write(
                "hosts/build/host.yml",
                "admins:\n  - login: alice\n  - login: alice\n    key: \""
                        + key("carol")
                        + "\"\n    backup: true\nprojects:\n  - demo\n");
        commit("alice");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", card("alice", "alice-new"));
        commit("carol");
        write("hosts/build/card", hostCard("build", "alice-new"));
        commit("alice-new");

        assertThat(fetch(taken, "alice").get("revision")).isEqualTo(git("rev-parse", "HEAD"));
    }

    @Test
    void aHostsRecordDoesNotLetTheRepositoryGoBack() throws Exception {
        final String taken = carryingHost();
        write("hosts/build/card", hostCard("build", "alice"));
        commit("alice");
        final GitTreeOffline host = new GitTreeOffline(keeping(), forge);
        final java.util.Set<String> pin = java.util.Set.of(PersonCard.fingerprint(key("alice")));
        host.check("build", pin, "");
        git("reset", "-q", "--hard", taken);

        refusedAs("refused-state", () -> host.check("build", pin, ""));
    }

    @Test
    void anAnswerGitsOutputWasCutFromIsRefusedRatherThanRead() throws Exception {
        founded();
        final org.fuin.porelo.core.process.CommandRunner cutting =
                command -> {
                    final org.fuin.porelo.core.process.CommandResult result = runner.run(command);
                    return command.arguments().contains("rev-list")
                            ? new org.fuin.porelo.core.process.CommandResult(
                                    command,
                                    result.exitCode(),
                                    "[1024 bytes cut before this]\n" + result.standardOutput(),
                                    result.standardError())
                            : result;
                };

        refusedAs(
                "cannot-say",
                () ->
                        GitTreeStore.at(
                                        cutting,
                                        repo.toString(),
                                        "acme",
                                        "forge.example",
                                        FetchAccess.NONE,
                                        dir.resolve("state-cut"))
                                .read(forge));
    }

    @Test
    void aMergesSecondParentVouchesForNothing() throws Exception {
        founded();
        final String main = git("rev-parse", "HEAD");
        git("checkout", "-q", "-b", "side");
        write("owners.yml", owners("mia", "mallory"));
        commit("mia");
        git("checkout", "-q", "main");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get("mallory"),
                "merge",
                "-q",
                "-S",
                "--no-ff",
                "-m",
                "merge",
                "side");

        final OrganizationQuestions read = store(repo.toString()).read(forge);

        assertThat(main).isNotBlank();
        assertThat(read.isOrganizationOwner("mallory"))
                .as("the owner's signature on the side says nothing of the merge's signer")
                .isFalse();
        assertThat(read.getRefusals()).anyMatch(each -> each.path().equals("owners.yml"));
    }

    @Test
    void aPathDifferingInCaseOrNotInUtf8IsNoFileOfTheOrganization() throws Exception {
        founded();
        write("OWNERS.YML", owners("mia", "mallory"));
        write("People/mallory.card", card("mallory", "mallory"));
        Files.createDirectories(repo.resolve("people"));
        runner.runOrFail(
                Command.of("sh", "-c", "printf x > \"$(printf 'people/caf\\351.card')\"")
                        .in(repo)
                        .withEnvironment(Map.of()));
        commit("mallory");

        final OrganizationQuestions read = store(repo.toString()).read(forge);

        assertThat(read.isOrganizationOwner("mallory")).isFalse();
        assertThat(read.getPersons()).isEmpty();
    }

    @Test
    void aSubmoduleInACardsPlaceIsNoCard() throws Exception {
        final String founding = founded();
        git("update-index", "--add", "--cacheinfo", "160000," + founding + ",people/mallory.card");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get("mallory"),
                "commit",
                "-q",
                "-S",
                "-m",
                "submodule");

        final OrganizationQuestions read = store(repo.toString()).read(forge);

        assertThat(read.getPersons()).isEmpty();
        assertThat(read.getRefusals()).anyMatch(each -> each.path().equals("people/mallory.card"));
    }

    @Test
    void oneRefusedPathOfACommitLeavesItsOtherPathsJudgedOnTheirOwn() throws Exception {
        founded();
        forge.keys.put("mallory", List.of(key("mallory")));
        write("people/mallory.card", card("mallory", "mallory"));
        write("owners.yml", owners("mia", "mallory"));
        commit("mallory");

        final OrganizationQuestions read = store(repo.toString()).read(forge);

        assertThat(read.isOrganizationOwner("mallory")).isFalse();
        assertThat(read.getRefusals())
                .extracting(Organization.Refusal::path)
                .containsExactly("owners.yml");
        assertThat(read.getPersons())
                .extracting(OrganizationQuestions.Person::login)
                .containsExactly("mallory");
    }

    @Test
    void aForgeThatHangsIsUnreachableAndNoRefusalToJudge() throws Exception {
        final String taken = carryingHost();
        final org.fuin.porelo.core.process.CommandRunner hanging =
                command -> {
                    if (command.arguments().contains("fetch")) {
                        throw new org.fuin.porelo.core.process.CommandException(
                                command, new IOException("Timed out after 60 s"), "");
                    }
                    return runner.run(command);
                };
        final GitTreeStore store =
                GitTreeStore.at(
                        hanging,
                        repo.toString(),
                        "acme",
                        "forge.example",
                        FetchAccess.NONE,
                        dir.resolve("state-hanging"));

        assertThatThrownBy(
                        () ->
                                new GitTreeOffline(store, forge)
                                        .check(
                                                "build",
                                                java.util.Set.of(
                                                        PersonCard.fingerprint(key("alice"))),
                                                taken))
                .as("a host's admin must not be shown a slow forge as something to judge")
                .isInstanceOf(GitTreeStore.Unreachable.class);
        assertThatThrownBy(() -> store.read(forge)).isInstanceOf(GitTreeStore.Unreachable.class);
    }

    @org.junit.jupiter.api.Test
    void what_a_forge_said_through_git_is_left_out_of_git_s_words() {
        org.assertj.core.api.Assertions.assertThat(
                        TreeRepository.gitsOwnWords(
                                "remote: your token is secret-of-the-person\n"
                                        + "fatal: unable to access the repository\n"))
                .isEqualTo("fatal: unable to access the repository");
        org.assertj.core.api.Assertions.assertThat(
                        TreeRepository.gitsOwnWords(
                                "fatal: remote error: secret-of-the-person\n"
                                        + "fatal: unable to access the repository\n"))
                .as("a forge's protocol error goes the same way")
                .isEqualTo("fatal: unable to access the repository");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "ERROR: Repository not found.",
                "remote: Forgejo: Cannot find repository: acme/demo",
                "fatal: repository 'https://forge.example/acme/demo/' not found",
                "fatal: '/none' does not appear to be a git repository"
            })
    void eachForgesWordsForARepositoryNotThereAreKnown(final String said) {
        assertThat(TreeRepository.notThere(said)).isTrue();
    }

    @Test
    void aFailureOfAnotherKindIsNotCalledARepositoryNotThere() {
        assertThat(TreeRepository.notThere("Host key verification failed.")).isFalse();
        assertThat(TreeRepository.notThere("ssh: connect to host forge port 22: refused"))
                .isFalse();
    }
}
