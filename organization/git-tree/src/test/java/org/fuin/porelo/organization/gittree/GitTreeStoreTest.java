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
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
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
        for (final String login : List.of("mia", "alice", "alice-new", "bob", "carol", "mallory")) {
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

    private OrganizationQuestions read() throws IOException {
        return new GitTreeStore(runner, repo.toString(), ORGANISATION).read(forge);
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
    void aFounderWhoseKeyIsNotTheOneListedIsNoOwner() throws Exception {
        forge.owners.put("mia", true);
        forge.keys.put("mia", List.of(key("alice")));
        write("owners.yml", owners("mia"));
        commit("mia");

        assertThat(read().isOrganizationOwner("mia"))
                .as("owning the organisation with another key proves nothing")
                .isFalse();
        assertThat(refusedPaths(read()))
                .as("a complete list without the key is a plain no, not a question for later")
                .containsExactly("owners.yml");
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

        assertThatThrownBy(
                        () ->
                                new GitTreeStore(runner, shallow.toString(), ORGANISATION)
                                        .read(forge))
                .as("a shallow history proves nothing")
                .isInstanceOf(OrganizationException.class)
                .hasMessageContaining("shallow");
    }

    @Test
    void aRepositoryThatCannotBeFetchedIsAnError() {
        assertThatThrownBy(
                        () ->
                                new GitTreeStore(
                                                runner,
                                                dir.resolve("absent").toString(),
                                                ORGANISATION)
                                        .read(forge))
                .as("an empty answer would read as no owners at all")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aProgramAsTheRepositorysAddressIsNotRun() throws Exception {
        final Path planted = dir.resolve("planted");

        assertThatThrownBy(
                        () ->
                                new GitTreeStore(
                                                runner,
                                                "ext::sh -c touch% " + planted,
                                                ORGANISATION)
                                        .read(forge))
                .isInstanceOf(IOException.class);

        assertThat(planted).as("ext:: stays forbidden").doesNotExist();
    }
}
