package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A local forge and signed git objects exercise the boundary between a proposal and main. */
class CardProposalsTest {
    @TempDir Path dir;
    final ProcessCommandRunner processes = new ProcessCommandRunner();
    final Map<String, Path> files = new HashMap<>();
    final Map<String, SigningKey> keys = new HashMap<>();
    final Map<String, List<String>> listed = new HashMap<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC);
    Path repo;
    boolean mainAllowed;
    boolean branchAllowed = true;
    final List<String> pushes = new ArrayList<>();
    final FakeForge forge = new FakeForge();
    final IdentitySource identities =
            new IdentitySource() {
                public Optional<Keys> keys(String login) {
                    return Optional.of(new Keys(listed.getOrDefault(login, List.of()), true));
                }

                public Optional<Boolean> owns(String organization, String login) {
                    return Optional.of(login.equals("owner"));
                }
            };
    final CommandRunner runner =
            command -> {
                List<String> args = command.arguments();
                if (args.contains("push")) {
                    String ref = args.getLast();
                    pushes.add(ref);
                    if ((!mainAllowed && ref.endsWith(":refs/heads/main"))
                            || (!branchAllowed
                                    && args.contains(repo.toString())
                                    && ref.contains("porelo-card-"))) {
                        return new org.fuin.porelo.core.process.CommandResult(
                                command, 1, "", "refused");
                    }
                }
                return processes.run(command);
            };

    @BeforeEach
    void found() throws Exception {
        repo = dir.resolve("organization.git");
        processes.runOrFail(
                Command.of("git", "init", "-q", "--bare", "-b", "main", repo.toString()));
        for (String login : List.of("owner", "alice", "bob", "developer", "other")) {
            Path file = dir.resolve(login);
            processes.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            SigningKey key =
                    new SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test");
            keys.put(login, key);
            files.put(PersonCard.keyLine(key), file);
            listed.put(login, List.of(PersonCard.keyLine(key)));
        }
        writes().createOrganization(new Change(acting("owner"), null, "found"), card("owner"));
    }

    GitTreeWrites writes() {
        return new GitTreeWrites(
                processes, repo.toString(), "acme", FetchAccess.NONE, identities, signing());
    }

    CommitSigning signing() {
        return new CommitSigning(Map.of(), files);
    }

    Acting acting(String login) {
        return new Acting(login, PersonCard.keyLine(keys.get(login)));
    }

    String card(String login) {
        return PersonCard.write(
                login,
                login,
                "",
                keys.get(login),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1),
                null);
    }

    CardProposals service() {
        return service(runner);
    }

    CardProposals service(CommandRunner commands) {
        return new CardProposals(
                commands,
                repo.toString(),
                "acme",
                FetchAccess.NONE,
                identities,
                signing(),
                forge,
                clock);
    }

    String git(String... args) {
        List<String> all = new ArrayList<>(List.of("git", "--git-dir=" + repo));
        all.addAll(List.of(args));
        return processes
                .runOrFail(
                        Command.of(all)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    String tip() {
        return git("rev-parse", "refs/heads/main");
    }

    TreeRecord read() throws Exception {
        try (TreeRepository clone =
                TreeRepository.fetch(repo.toString(), processes, FetchAccess.NONE)) {
            TreeWalk walk = new TreeWalk(clone, identities, "acme");
            walk.readChains();
            return walk.read(null, null);
        }
    }

    class FakeForge implements CardProposals.Forge {
        final List<CardProposals.Pull> pulls = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        int forks;
        int opens;
        String who = "alice";

        public String login() {
            return who;
        }

        public List<CardProposals.Pull> list() {
            return List.copyOf(pulls);
        }

        public CardProposals.Pull read(long number) {
            CardProposals.Pull pull = pulls.get((int) number - 1);
            if (pull.sourceOwner().equals("alice")) {
                git(
                        "fetch",
                        dir.resolve("fork.git").toString(),
                        "+refs/heads/" + pull.branch() + ":refs/pull/" + number + "/head");
            }
            String commit = git("rev-parse", "refs/pull/" + number + "/head");
            return new CardProposals.Pull(
                    number,
                    pull.url(),
                    pull.author(),
                    pull.branch(),
                    "main",
                    commit,
                    pull.opened(),
                    true,
                    false,
                    pull.sourceOwner());
        }

        public String fork(String login) {
            forks++;
            Path fork = dir.resolve("fork.git");
            if (!Files.exists(fork))
                processes.runOrFail(
                        Command.of(
                                "git", "clone", "-q", "--bare", repo.toString(), fork.toString()));
            return fork.toString();
        }

        public CardProposals.Pull open(String login, boolean fork) {
            opens++;
            long number = pulls.size() + 1;
            String branch = "porelo-card-" + login;
            String source = fork ? dir.resolve("fork.git").toString() : repo.toString();
            git("fetch", source, "+refs/heads/" + branch + ":refs/pull/" + number + "/head");
            CardProposals.Pull pull =
                    new CardProposals.Pull(
                            number,
                            "https://forge.example/acme/org/pulls/" + number,
                            who,
                            branch,
                            "main",
                            git("rev-parse", "refs/pull/" + number + "/head"),
                            "2026-11-12T12:00:00Z",
                            true,
                            false,
                            fork ? login : "acme");
            pulls.add(pull);
            return pull;
        }

        public void close(long number, String comment) {
            closed.add(number + ":" + comment);
        }
    }

    void propose() throws Exception {
        service().join(acting("alice"), card("alice"), false);
    }

    void alter(Map<String, String> paths, String signer) throws Exception {
        try (TreeRepository clone =
                TreeRepository.fetch(repo.toString(), processes, FetchAccess.NONE)) {
            String commit =
                    clone.commit(
                            tip(),
                            paths,
                            "proposal",
                            "alter",
                            signing(),
                            acting(signer).key(),
                            "alice");
            clone.push(repo.toString(), FetchAccess.NONE);
            git("update-ref", "refs/pull/1/head", commit);
            git("update-ref", "refs/heads/main", git("rev-parse", commit + "^"));
        }
    }

    @Test
    void joinLeavesMainAndEveryGrantUnchanged() throws Exception {
        String before = tip();
        CardProposals.Result result = service().join(acting("alice"), card("alice"), false);
        assertThat(result.state()).as("a reader proposes").isEqualTo("proposed");
        assertThat(tip()).as("an open proposal never reaches main").isEqualTo(before);
        TreeModel model = read().model();
        assertThat(model.people()).as("no person is granted").doesNotContainKey("alice");
        assertThat(model.organizationAdmins())
                .as("no organization role is granted")
                .doesNotContain("alice");
        assertThat(model.projects()).as("no project or project role is granted").isEmpty();
        assertThat(model.hosts()).as("no host is granted").isEmpty();
        assertThat(forge.forks).as("a branch is tried first").isZero();
        assertThat(forge.opens).as("one proposal").isEqualTo(1);
    }

    @Test
    void mainWriterFilesWithoutAProposal() throws Exception {
        mainAllowed = true;
        assertThat(service().join(acting("alice"), card("alice"), false).state())
                .as("main writer files directly")
                .isEqualTo("filed");
        assertThat(forge.opens).as("no pull request").isZero();
        assertThat(read().model().people().get("alice").state())
                .as("reader accepts the own key")
                .isEqualTo(Organization.TRUSTED);
    }

    @Test
    void refusedOrganizationBranchFallsBackToAFork() throws Exception {
        branchAllowed = false;
        propose();
        assertThat(forge.forks).as("only a refused branch needs a fork").isEqualTo(1);
        assertThat(forge.opens).as("one open after fork push").isEqualTo(1);
    }

    @Test
    void acceptedCommitIsUnchangedAndReadAsThePersonsOwn() throws Exception {
        propose();
        String commit = forge.read(1).commit();
        mainAllowed = true;
        assertThat(service().accept(acting("owner"), "alice").state())
                .as("owner accepts")
                .isEqualTo("accepted");
        assertThat(tip()).as("no merge, squash or new signature").isEqualTo(commit);
        assertThat(read().model().people().get("alice").state())
                .as("TreeWalk trusts the person's own signed card")
                .isEqualTo(Organization.TRUSTED);
        assertThat(pushes.getLast())
                .as("only the judged commit reaches main")
                .isEqualTo(commit + ":refs/heads/main");
    }

    @Test
    void staleAcceptanceLeavesMainUnchangedAndJoinSignsAgain() throws Exception {
        propose();
        writes().addCard(new Change(acting("bob"), tip(), "bob"), card("bob"));
        String before = tip();
        assertThat(service().proposals().getFirst().state())
                .as("moved parent is stale")
                .isEqualTo("stale");
        assertThatThrownBy(() -> service().accept(acting("owner"), "alice"))
                .as("stale cannot be accepted")
                .isInstanceOf(Refused.class)
                .hasMessageContaining("stale");
        assertThat(tip()).as("stale acceptance changes nothing").isEqualTo(before);
        propose();
        assertThat(forge.opens).as("existing proposal is updated").isEqualTo(1);
        String head = git("rev-parse", "refs/heads/porelo-card-alice");
        assertThat(git("rev-parse", head + "^"))
                .as("new signature covers the new tip")
                .isEqualTo(before);
    }

    @Test
    void developerCannotAcceptOrDeclineBeforeAnyMutation() throws Exception {
        writes().addCard(new Change(acting("developer"), tip(), "developer"), card("developer"));
        propose();
        pushes.clear();
        assertThatThrownBy(() -> service().accept(acting("developer"), "alice"))
                .as("a developer cannot accept")
                .isInstanceOf(Refused.class);
        assertThatThrownBy(() -> service().decline(acting("developer"), "alice"))
                .as("a developer cannot decline")
                .isInstanceOf(Refused.class);
        assertThat(pushes).as("no push before role check").isEmpty();
        assertThat(forge.closed).as("no close before role check").isEmpty();
    }

    @Test
    void wrongActingKeyCannotAcceptOrDecline() throws Exception {
        propose();
        pushes.clear();
        Acting forged = new Acting("owner", acting("other").key());
        assertThatThrownBy(() -> service().accept(forged, "alice"))
                .as("role alone grants no acceptance")
                .isInstanceOf(Refused.class);
        assertThatThrownBy(() -> service().decline(forged, "alice"))
                .as("role alone grants no closing")
                .isInstanceOf(Refused.class);
        assertThat(pushes).as("no push with an untrusted key").isEmpty();
        assertThat(forge.closed).as("no close with an untrusted key").isEmpty();
    }

    @Test
    void anotherCommitSignerIsRefusedWithItsReason() throws Exception {
        propose();
        alter(Map.of("people/alice.card", card("alice")), "other");
        CardProposals.Proposal judged = service().proposals().getFirst();
        assertThat(judged.state()).as("another key cannot sign the proposal").isEqualTo("refused");
        assertThat(judged.reason()).as("named reason").contains("card's own key");
    }

    @Test
    void secondPathIsRefusedWithItsReason() throws Exception {
        propose();
        alter(Map.of("people/alice.card", card("alice"), "unread", "extra"), "alice");
        CardProposals.Proposal judged = service().proposals().getFirst();
        assertThat(judged.state())
                .as("every path counts, including paths readers ignore")
                .isEqualTo("refused");
        assertThat(judged.reason()).as("named reason").contains("only people/alice.card");
    }

    @Test
    void anotherForgeAuthorIsNoProposal() throws Exception {
        forge.who = "other";
        try {
            propose();
        } catch (Refused expected) {
            /* The forge login can refuse at join. */
        }
        if (forge.pulls.isEmpty()) {
            forge.who = "alice";
            propose();
        }
        CardProposals.Pull p = forge.pulls.getFirst();
        forge.pulls.set(
                0,
                new CardProposals.Pull(
                        p.number(),
                        p.url(),
                        "other",
                        p.branch(),
                        p.base(),
                        p.commit(),
                        p.opened(),
                        true,
                        false,
                        p.sourceOwner()));
        assertThat(service().proposals())
                .as("branch and author must name the same person")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mallory", ""})
    void aProposalFromAThirdAccountsForkOrAForkGoneIsNoProposal(String source) throws Exception {
        propose();
        CardProposals.Pull p = forge.pulls.getFirst();
        forge.pulls.set(
                0,
                new CardProposals.Pull(
                        p.number(),
                        p.url(),
                        p.author(),
                        p.branch(),
                        p.base(),
                        p.commit(),
                        p.opened(),
                        true,
                        false,
                        source));
        mainAllowed = true;
        final String before = tip();
        assertThat(service().proposals())
                .as("only the person's own fork or the organization's repository proposes")
                .isEmpty();
        assertThatThrownBy(() -> service().accept(acting("owner"), "alice"))
                .as("nothing to accept")
                .isInstanceOf(Refused.class);
        assertThat(tip()).as("main unchanged").isEqualTo(before);
    }

    @Test
    void declineAndWithdrawCloseWithComments() throws Exception {
        propose();
        assertThat(service().decline(acting("owner"), "alice").state())
                .as("admin declines")
                .isEqualTo("declined");
        assertThat(service().join(acting("alice"), card("alice"), true).state())
                .as("own withdrawal")
                .isEqualTo("withdrawn");
        assertThat(forge.closed)
                .as("both actions give a reason")
                .hasSize(2)
                .allMatch(comment -> comment.length() > 10);
    }

    @Test
    void aLeaseRefusesMainMovingAfterTheJudgment() throws Exception {
        propose();
        mainAllowed = true;
        final String[] moved = {""};
        CommandRunner racing =
                command -> {
                    if (command.arguments().contains("push")
                            && command.arguments().getLast().endsWith(":refs/heads/main")) {
                        assertThat(command.arguments())
                                .as("accept leases precisely the tip judged")
                                .contains("--force-with-lease=refs/heads/main:" + tip());
                        try {
                            writes().addCard(new Change(acting("bob"), tip(), "race"), card("bob"));
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                        moved[0] = tip();
                    }
                    return processes.run(command);
                };
        assertThatThrownBy(() -> service(racing).accept(acting("owner"), "alice"))
                .as("movement between judgment and push refuses")
                .isInstanceOf(Refused.class);
        assertThat(tip()).as("the newer main survives").isEqualTo(moved[0]);
        assertThat(read().model().people())
                .as("the proposed person is still absent")
                .doesNotContainKey("alice");
    }

    @Test
    void twoCommitsAreRefused() throws Exception {
        propose();
        try (TreeRepository clone =
                TreeRepository.fetch(repo.toString(), processes, FetchAccess.NONE)) {
            String first =
                    clone.commit(
                            tip(),
                            Map.of("unread", "first"),
                            "first",
                            "first",
                            signing(),
                            acting("alice").key(),
                            "alice");
            String head =
                    clone.commit(
                            first,
                            Map.of("people/alice.card", card("alice")),
                            "second",
                            "second",
                            signing(),
                            acting("alice").key(),
                            "alice");
            clone.push(repo.toString(), FetchAccess.NONE);
            git("update-ref", "refs/pull/1/head", head);
            git("update-ref", "refs/heads/main", git("rev-parse", first + "^"));
        }
        assertThat(service().proposals().getFirst().reason())
                .as("one commit means the whole proposal")
                .contains("exactly one commit");
    }

    @Test
    void aPredecessorIsRefused() throws Exception {
        propose();
        String card =
                PersonCard.write(
                        "alice",
                        "Alice",
                        "",
                        keys.get("alice"),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 12, 1),
                        keys.get("other"));
        alter(Map.of("people/alice.card", card), "alice");
        assertThat(service().proposals().getFirst().reason())
                .as("only a first card is proposed")
                .contains("predecessor");
    }

    @Test
    void aKeyAlreadyHeldByAnotherLoginIsRefused() throws Exception {
        propose();
        String card =
                PersonCard.write(
                        "alice",
                        "Alice",
                        "",
                        keys.get("owner"),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 12, 1),
                        null);
        alter(Map.of("people/alice.card", card), "owner");
        assertThat(service().proposals().getFirst().reason())
                .as("keyHolder binds a key to its existing login")
                .contains("held by another login: owner");
    }

    @Test
    void futureAndExpiredCardsAreRefused() throws Exception {
        propose();
        for (LocalDate day : List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1))) {
            String card =
                    PersonCard.write(
                            "alice", "Alice", "", keys.get("alice"), day, day.plusDays(5), null);
            alter(Map.of("people/alice.card", card), "alice");
            assertThat(service().proposals().getFirst().reason())
                    .as("today lies inside the card's term")
                    .contains("created and expires");
        }
    }

    @Test
    void aForgeKeyNoLongerListedIsRefusedByTreeWalk() throws Exception {
        propose();
        listed.put("alice", List.of(acting("other").key()));
        CardProposals.Proposal proposal = service().proposals().getFirst();
        assertThat(proposal.state())
                .as("forge key binding is judged by the reader")
                .isEqualTo("refused");
        assertThat(proposal.reason())
                .as("reader's reason is retained")
                .contains("not the one listed");
        mainAllowed = true;
        String before = tip();
        assertThatThrownBy(() -> service().accept(acting("owner"), "alice"))
                .as("fresh acceptance judges the changed key binding again")
                .isInstanceOf(Refused.class);
        assertThat(tip()).as("refused key never reaches main").isEqualTo(before);
    }

    @Test
    void organizationAdminListRemovesTheOwnersFallback() throws Exception {
        writes().addCard(new Change(acting("developer"), tip(), "dev-card"), card("developer"));
        writes().addOrganizationAdmin(new Change(acting("owner"), tip(), "dev-admin"), "developer");
        writes().removeOrganizationAdmin(new Change(acting("owner"), tip(), "owner-out"), "owner");
        propose();
        pushes.clear();
        assertThatThrownBy(() -> service().accept(acting("owner"), "alice"))
                .as("an owner outside the explicit list cannot accept")
                .isInstanceOf(Refused.class);
        assertThat(pushes).as("role refusal precedes push").isEmpty();
        mainAllowed = true;
        assertThat(service().accept(acting("developer"), "alice").state())
                .as("a listed trusted admin can accept")
                .isEqualTo("accepted");
    }

    @Test
    void revokedActingAdminKeyCannotClose() throws Exception {
        writes().addCard(new Change(acting("developer"), tip(), "dev-card"), card("developer"));
        writes().addOrganizationAdmin(new Change(acting("owner"), tip(), "dev-admin"), "developer");
        writes().revokeCard(new Change(acting("owner"), tip(), "revoke-dev"), "developer");
        propose();
        pushes.clear();
        assertThatThrownBy(() -> service().decline(acting("developer"), "alice"))
                .as("a revoked key does not retain its role")
                .isInstanceOf(Refused.class);
        assertThat(forge.closed).as("no close for a revoked key").isEmpty();
        assertThat(pushes).as("no push for a revoked key").isEmpty();
    }

    @Test
    void anOpenForkProposalKeepsItsBranchInThatFork() throws Exception {
        branchAllowed = false;
        propose();
        writes().addCard(new Change(acting("bob"), tip(), "bob"), card("bob"));
        branchAllowed = true;
        propose();
        String head = forge.read(1).commit();
        assertThat(git("rev-parse", head + "^"))
                .as("refresh updates the fork the open proposal actually names")
                .isEqualTo(tip());
        assertThat(forge.opens).as("one open proposal survives the refresh").isEqualTo(1);
    }

    @Test
    void ownersAcceptBeforeTheOrganizationAdminListExists() throws Exception {
        repo = dir.resolve("legacy.git");
        processes.runOrFail(
                Command.of("git", "init", "-q", "--bare", "-b", "main", repo.toString()));
        try (TreeRepository clone =
                TreeRepository.founding(repo.toString(), processes, FetchAccess.NONE)
                        .orElseThrow()) {
            clone.commit(
                    "",
                    Map.of(
                            "owners.yml",
                            "owners:\n  - login: owner\n    key: \""
                                    + acting("owner").key()
                                    + "\"\n",
                            "people/owner.card",
                            card("owner")),
                    "found",
                    "legacy",
                    signing(),
                    acting("owner").key(),
                    "owner");
            clone.push(repo.toString(), FetchAccess.NONE);
        }
        propose();
        mainAllowed = true;
        assertThat(service().accept(acting("owner"), "alice").state())
                .as("owners do the admin's work only before the list exists")
                .isEqualTo("accepted");
    }

    @Test
    void aRevokedProposalKeyIsRefusedByTreeWalk() throws Exception {
        propose();
        writes().addCard(new Change(acting("alice"), tip(), "file-alice"), card("alice"));
        writes().revokeCard(new Change(acting("owner"), tip(), "revoke-alice"), "alice");
        alter(Map.of("people/alice.card", card("alice")), "alice");
        assertThat(service().proposals().getFirst().reason())
                .as("the ordinary reader enforces revocation")
                .contains("revoked");
    }

    @Test
    void aDamagedCardSignatureIsRefused() throws Exception {
        propose();
        alter(
                Map.of(
                        "people/alice.card",
                        card("alice").replace("name: \"alice\"", "name: \"Changed\"")),
                "alice");
        assertThat(service().proposals().getFirst().reason())
                .as("a signed commit cannot repair the card's own damaged signature")
                .contains("signature");
    }

    @Test
    void aRepositoryServedBehindTheTrustedRevisionCannotAcceptOrDecline() throws Exception {
        propose();
        String older = tip();
        writes().addCard(new Change(acting("bob"), older, "bob"), card("bob"));
        String trusted = tip();
        git("update-ref", "refs/heads/main", older, trusted);
        pushes.clear();
        mainAllowed = true;
        CardProposals anchored = service().standingAt(trusted);
        assertThatThrownBy(() -> anchored.accept(acting("owner"), "alice"))
                .as("a reverted forge must not restore old rights")
                .isInstanceOf(Refused.class)
                .hasMessageContaining("went back");
        assertThatThrownBy(() -> anchored.decline(acting("owner"), "alice"))
                .as("closing a proposal also requires the trusted history")
                .isInstanceOf(Refused.class);
        assertThat(pushes)
                .as("nothing is pushed on a history served behind the trust record")
                .isEmpty();
        assertThat(forge.closed)
                .as("nothing is closed on a history served behind the trust record")
                .isEmpty();
    }
}
