package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ProjectAccessCommand}, against real git, a real ssh-agent and a bare access
 * repository reached by {@code file://}. Harm guarded: an access change that the follow would
 * refuse, or that lands on top of somebody else's, or twice, changes who may work on a project
 * without anybody having asked for that change.
 */
class ProjectAccessCommandTest {

    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

    @TempDir Path dir;

    private StringWriter out = new StringWriter();

    private StringWriter err = new StringWriter();

    private @Nullable Process agent;

    private Path socket;

    private Path signer;

    private Path project;

    private Path remote;

    /** Runs before a command's push, to let somebody else be faster. */
    private Runnable beforePush = () -> {};

    /** The environment the command takes its caller to have, as a shell would hand it over. */
    private Map<String, String> caller = System.getenv();

    /** Whether the command under test is running, rather than the fixture around it. */
    private boolean running;

    private final SigningKey alice = SigningKey.generate("alice");

    private final SigningKey bob = SigningKey.generate("bob");

    @BeforeEach
    void follow() throws Exception {
        socket = dir.resolve("agent.sock");
        agent = new ProcessBuilder("ssh-agent", "-D", "-a", socket.toString()).start();
        for (int tries = 0; tries < 100 && !Files.exists(socket); tries++) {
            Thread.sleep(50);
        }
        assertThat(socket).as("the ssh-agent is listening").exists();
        signer = key("operator");
        agentAdds(signer);
        final Path signers = context().paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        Files.writeString(signers, "demo " + publicKey(signer) + "\n");
        remote = dir.resolve("access.git");
        git(dir, "init", "-q", "--bare", "-b", "main", remote.toString());
        project = Files.createDirectories(dir.resolve("project"));
        git(project, "init", "-q", "-b", "main", ".");
        Files.writeString(project.resolve("project.yml"), projectFile(url()));
        git(project, "add", "--all");
        git(
                project,
                "-c",
                "user.name=Operator",
                "-c",
                "user.email=operator@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signer,
                "commit",
                "-q",
                "-S",
                "-m",
                "configuration");
        final FollowedProjects projects =
                new FollowedProjects(context().paths().projects().followed());
        projects.follow("demo", project.toString());
        final FollowedProjects.Followed before = projects.require("demo");
        projects.write(Reconcile.after(before, new Reconcile(context()).run(before)));
        assertThat(projects.require("demo").commit()).as("the project is in force").isNotEmpty();
    }

    @AfterEach
    void stopAgent() throws InterruptedException {
        if (agent != null) {
            agent.destroy();
            agent.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void initMakesTheFirstAccessFileAndASecondInitIsRefused() throws Exception {
        // A second init would replace every person with one, by somebody who read nothing.
        final String revision = init();

        assertThat(revision).as("the commit made is the remote's head").isEqualTo(head());
        assertThat(verified().get("persons"))
                .as("the admin is the first person, on the host named, with their card")
                .isEqualTo(List.of(person("alice", alice, "admin", List.of("h1"), false)));

        assertThat(
                        access(
                                "init",
                                "--person",
                                "bob",
                                "--host",
                                "h1",
                                "--card",
                                card("bob", bob),
                                "--id",
                                "second"))
                .as("an access file is there")
                .isEqualTo(65);
        assertThat(err.toString()).contains("already holds access.yml");
        assertThat(head()).as("nothing was pushed").isEqualTo(revision);
    }

    @Test
    void addMakesASignedCommitThatVerifyAnswersWithThePersonAndTheirCard() throws Exception {
        // An access commit not signed by a project signer is refused by every follow.
        final String first = init();

        final String added =
                change(
                        "add",
                        "--person",
                        "bob",
                        "--host",
                        "h1",
                        "--card",
                        card("bob", bob),
                        "--base",
                        first,
                        "--id",
                        "add-bob");
        final String again =
                change(
                        "add",
                        "--person",
                        "alice",
                        "--host",
                        "h2",
                        "--base",
                        added,
                        "--id",
                        "alice-h2");

        final Path allowed = dir.resolve("allowed");
        Files.writeString(allowed, "demo " + publicKey(signer) + "\n");
        final CommandResult checked =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                remote.toString(),
                                "-c",
                                "gpg.ssh.allowedSignersFile=" + allowed,
                                "verify-commit",
                                added));
        assertThat(checked.exitCode())
                .as("the commit verifies against the project's signer: " + checked.standardError())
                .isZero();
        assertThat(git(remote, "log", "-1", "--format=%B", added))
                .as("the change is named by its id and digest")
                .contains("Porelo-Change-Id: add-bob", "Porelo-Change-Digest: sha256:");
        assertThat(again).isEqualTo(head());
        assertThat(verified().get("persons"))
                .as("bob is a developer with his card; alice is on a second host")
                .isEqualTo(
                        List.of(
                                person("alice", alice, "admin", List.of("h1", "h2"), false),
                                person("bob", bob, "developer", List.of("h1"), false)));
    }

    @Test
    void removeOfTheLastHostOrWithoutAHostTakesThePersonOff() throws Exception {
        // A person left on a host after their removal still reaches it.
        String at = init();
        at =
                change(
                        "add",
                        "--person",
                        "bob",
                        "--host",
                        "h1",
                        "--card",
                        card("bob", bob),
                        "--base",
                        at,
                        "--id",
                        "b1");
        at = change("add", "--person", "bob", "--host", "h2", "--base", at, "--id", "b2");
        at = change("add", "--person", "alice", "--host", "h2", "--base", at, "--id", "a2");

        at = change("remove", "--person", "bob", "--host", "h1", "--base", at, "--id", "r1");
        assertThat(verified().get("persons"))
                .as("bob is off h1 only")
                .isEqualTo(
                        List.of(
                                person("alice", alice, "admin", List.of("h1", "h2"), false),
                                person("bob", bob, "developer", List.of("h2"), false)));
        at = change("remove", "--person", "bob", "--host", "h2", "--base", at, "--id", "r2");
        assertThat(verified().get("persons"))
                .as("bob's last host gone, bob is gone")
                .isEqualTo(List.of(person("alice", alice, "admin", List.of("h1", "h2"), false)));
        assertThat(git(remote, "ls-tree", "--name-only", at, "cards/"))
                .as("his card stays for a later add")
                .contains("cards/bob.card");

        change("remove", "--person", "alice", "--base", at, "--id", "r3");
        assertThat(verified().get("persons"))
                .as("alice is off every host at once")
                .isEqualTo(List.of());
    }

    @Test
    void lockAnswersLockedAndUnlockAnswersNot() throws Exception {
        // A lock nobody can see is no lock.
        String at = init();

        at = change("lock", "--person", "alice", "--base", at, "--id", "lock");
        assertThat(verified().get("persons"))
                .isEqualTo(List.of(person("alice", alice, "admin", List.of("h1"), true)));
        change("unlock", "--person", "alice", "--base", at, "--id", "unlock");
        assertThat(verified().get("persons"))
                .isEqualTo(List.of(person("alice", alice, "admin", List.of("h1"), false)));
    }

    @Test
    void aStaleBaseIsRefusedNamingBothRevisions() throws Exception {
        // A change made against an older file would undo whatever came after it.
        final String first = init();
        final String second =
                change("add", "--person", "alice", "--host", "h2", "--base", first, "--id", "a2");

        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "late"))
                .isEqualTo(65);
        assertThat(err.toString()).as("both revisions are named").contains(first, second);
        assertThat(head()).as("nothing was pushed").isEqualTo(second);
    }

    @Test
    void aRepeatedIdAnswersTheSameRevisionAndAnotherChangeUnderItIsRefused() throws Exception {
        // A caller that lost the answer and asks again must not make the change twice.
        final String first = init();
        final String locked = change("lock", "--person", "alice", "--base", first, "--id", "same");

        assertThat(change("lock", "--person", "alice", "--base", first, "--id", "same"))
                .as("the same change answers the same commit, stale base or not")
                .isEqualTo(locked);
        assertThat(head()).as("no second commit").isEqualTo(locked);
        assertThat(access("unlock", "--person", "alice", "--base", first, "--id", "same"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("--id same names another change: " + locked);
        assertThat(head()).isEqualTo(locked);
    }

    @Test
    void anIdUsedAgainOnAnotherBaseMakesTheChangeAgain() throws Exception {
        // Answered with the old commit, a lock asked after an unlock would report done and leave
        // the person unlocked.
        String at = init();
        final String locked = change("lock", "--person", "alice", "--base", at, "--id", "lock");
        at = change("unlock", "--person", "alice", "--base", locked, "--id", "unlock");

        final String again = change("lock", "--person", "alice", "--base", at, "--id", "lock");

        assertThat(again).as("a new commit").isNotEqualTo(locked).isEqualTo(head());
        assertThat(verified().get("persons"))
                .isEqualTo(List.of(person("alice", alice, "admin", List.of("h1"), true)));
    }

    @Test
    void anIdOnACommitNoSignerMadeOrCarriedTwiceIsNoAnswer() throws Exception {
        // A trailer anybody with push access writes would answer "done" for a lock never made.
        final String first = init();
        refresh();
        final String digest =
                "sha256:"
                        + java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        "action=lock\nperson=alice\nhost=\nrole=\ncard=\n"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)));
        final String unsigned =
                pushed(
                        false,
                        "notes\n\nPorelo-Change-Id: lock-alice\nPorelo-Change-Digest: "
                                + digest
                                + "\n",
                        tree -> Files.writeString(tree.resolve("notes.md"), "a\n"));

        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "lock-alice"))
                .as("an unsigned commit answers nothing")
                .isEqualTo(65);
        assertThat(err.toString()).contains("the access repository is at " + unsigned);

        git(remote, "update-ref", "refs/heads/main", first);
        final String twice =
                pushed(
                        true,
                        "notes\n\nPorelo-Change-Id: other\nPorelo-Change-Id: lock-alice\n"
                                + "Porelo-Change-Digest: "
                                + digest
                                + "\nPorelo-Change-Digest: sha256:00\n",
                        tree -> Files.writeString(tree.resolve("notes.md"), "b\n"));
        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "lock-alice"))
                .as("ids and digests carried twice are not paired up")
                .isEqualTo(65);
        assertThat(err.toString()).contains("the access repository is at " + twice);
    }

    @Test
    void aRemoteHeadTheFollowWouldRefuseIsNotWrittenOnto() throws Exception {
        // Signed on top by the admin, an unsigned change to access.yml would pass every follow.
        final String first = init();
        final String inForce = refresh();
        final String unsigned =
                pushed(
                        false,
                        "more",
                        tree ->
                                Files.writeString(
                                        tree.resolve("access.yml"),
                                        Files.readString(tree.resolve("access.yml"))
                                                .replace("h1", "h9")));

        assertThat(access("lock", "--person", "alice", "--base", unsigned, "--id", "x"))
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the commit refused is named")
                .contains("the follow refuses", unsigned);
        assertThat(head()).as("nothing was pushed").isEqualTo(unsigned);

        git(remote, "update-ref", "refs/heads/main", first);
        final String other =
                pushed(true, "another root", tree -> git(tree, "checkout", "-q", "--orphan", "x"));
        git(remote, "update-ref", "refs/heads/main", other);
        assertThat(access("lock", "--person", "alice", "--base", other, "--id", "y")).isEqualTo(65);
        assertThat(err.toString()).contains("does not descend from access commit " + inForce);
    }

    @Test
    void initOnARepositoryNothingHereVouchesForIsRefused() throws Exception {
        // What is there already would be signed over by the admin, read by nobody.
        final String planted =
                pushed(true, "planted", tree -> Files.writeString(tree.resolve("x.md"), "x\n"));

        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                card("alice", alice),
                                "--id",
                                "first"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("holds " + planted, "only into an empty");
        assertThat(head()).isEqualTo(planted);
    }

    @Test
    void addWithoutACardTakesOnlyTheCardOfAPersonAlreadyIn() throws Exception {
        // A card anybody pushed beside the access file would be pinned under the admin's
        // signature.
        String at = init();
        at =
                change(
                        "add",
                        "--person",
                        "bob",
                        "--host",
                        "h1",
                        "--card",
                        card("bob", bob),
                        "--base",
                        at,
                        "--id",
                        "b1");
        at = change("remove", "--person", "bob", "--base", at, "--id", "r1");
        refresh();
        final SigningKey mallory = SigningKey.generate("bob");
        final String forged = card("bob", mallory);
        final String planted =
                pushed(
                        false,
                        "card",
                        tree ->
                                Files.copy(
                                        Path.of(forged),
                                        tree.resolve("cards/bob.card"),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING));

        assertThat(
                        access(
                                "add",
                                "--person",
                                "bob",
                                "--host",
                                "h1",
                                "--base",
                                planted,
                                "--id",
                                "b2"))
                .isEqualTo(64);
        assertThat(err.toString()).contains("bob is not a person of demo; give their --card");
        assertThat(head()).isEqualTo(planted);
    }

    @Test
    void aCardTheAccessFileRevokesIsRefused() throws Exception {
        // Written, the person would be answered revoked by every follow at once.
        init();
        final String revoked =
                pushed(
                        true,
                        "revoke",
                        tree ->
                                Files.writeString(
                                        tree.resolve("access.yml"),
                                        "revoked:\n- "
                                                + PersonCard.fingerprint(PersonCard.keyLine(bob))
                                                + "\n",
                                        java.nio.file.StandardOpenOption.APPEND));

        assertThat(
                        access(
                                "add",
                                "--person",
                                "bob",
                                "--host",
                                "h1",
                                "--card",
                                card("bob", bob),
                                "--base",
                                revoked,
                                "--id",
                                "b"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("lists under revoked");
        assertThat(head()).isEqualTo(revoked);
    }

    @Test
    void aLinkWhereTheCardsDirectoryBelongsIsRefused() throws Exception {
        // git would write the card through it, or the follow would not read the card at all.
        init();
        final String linked =
                pushed(
                        true,
                        "link",
                        tree -> {
                            git(tree, "rm", "-q", "-r", "cards");
                            Files.createSymbolicLink(tree.resolve("cards"), Path.of(".."));
                        });

        assertThat(access("lock", "--person", "alice", "--base", linked, "--id", "l"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("cards at " + linked + " is not a directory");
    }

    @Test
    void theSameCardGivenAgainIsNothingToChange() throws Exception {
        // Read with its last newline cut, the card looked new and a commit with no change was
        // attempted.
        final Path given = Path.of(card("alice", alice));
        final String first =
                change(
                        "init",
                        "--person",
                        "alice",
                        "--host",
                        "h1",
                        "--card",
                        given.toString(),
                        "--id",
                        "first");

        assertThat(
                        access(
                                "add",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                given.toString(),
                                "--base",
                                first,
                                "--id",
                                "again"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("nothing to change");
    }

    @Test
    void aCardThatIsALinkOrTooLargeIsRefused() throws Exception {
        // A link in a shared directory points the card read at whatever somebody chose.
        final Path link = dir.resolve("link.card");
        Files.createSymbolicLink(link, Path.of(card("alice", alice)));
        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                link.toString(),
                                "--id",
                                "i"))
                .isEqualTo(66);
        final Path large = dir.resolve("large.card");
        Files.writeString(large, "x".repeat(64 * 1024 + 1));
        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                large.toString(),
                                "--id",
                                "i"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("is larger than a card");
    }

    @Test
    void anUnverifiedFollowIsRefused() throws Exception {
        // Whoever can push to an unverified project would choose where admins' signed commits go.
        final FollowedProjects projects =
                new FollowedProjects(context().paths().projects().followed());
        final FollowedProjects.Followed was = projects.require("demo");
        projects.write(
                new FollowedProjects.Followed(
                        was.name(),
                        was.url(),
                        was.commit(),
                        was.at(),
                        was.outcome(),
                        was.detail(),
                        was.refused(),
                        was.signer(),
                        true,
                        was.accessCommit(),
                        was.accessOutcome(),
                        was.refusedFile(),
                        was.accessRefused(),
                        was.accessRefusedFile()));

        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                card("alice", alice),
                                "--id",
                                "i"))
                .isEqualTo(64);
        assertThat(err.toString()).contains("followed unverified");
        assertThat(git(remote, "rev-parse", "--all")).isEmpty();
    }

    @Test
    void theAccessRepositoryIsTheOneTheCommitInForceNames() throws Exception {
        // The clone's working tree is a file anybody on this account may have changed.
        final Path decoy = dir.resolve("decoy.git");
        git(dir, "init", "-q", "--bare", "-b", "main", decoy.toString());
        Files.writeString(
                context().paths().projects().followedClone("demo").resolve("project.yml"),
                projectFile(decoy.toUri().toString()));

        final String revision = init();

        assertThat(revision).isEqualTo(head());
        assertThat(git(decoy, "rev-parse", "--all")).isEmpty();
    }

    @Test
    void theOnlySignerTheRevocationListLeavesSigns() throws Exception {
        // A revoked key's commit is refused by every follow, after the push.
        final Path second = key("second");
        agentAdds(second);
        Files.writeString(
                context().paths().projects().configurationSigners(),
                "demo " + publicKey(signer) + "\ndemo " + publicKey(second) + "\n");
        final CommandResult revoked =
                runner.run(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-k",
                                "-f",
                                context().paths().projects().revocations().toString(),
                                signer + ".pub"));
        assertThat(revoked.exitCode()).as(revoked.standardError()).isZero();

        final String first = init();

        final Path allowed = dir.resolve("allowed");
        Files.writeString(allowed, "demo " + publicKey(second) + "\n");
        assertThat(
                        runner.run(
                                        Command.of(
                                                "git",
                                                "-C",
                                                remote.toString(),
                                                "-c",
                                                "gpg.ssh.allowedSignersFile=" + allowed,
                                                "verify-commit",
                                                first))
                                .exitCode())
                .as("signed by the key not revoked")
                .isZero();
    }

    @Test
    void aBranchRewoundSinceTheReadIsNotWrittenOnto() throws Exception {
        // Taken as a fast-forward, the push would restore a commit somebody took back.
        final String first = init();
        final String second =
                change("add", "--person", "alice", "--host", "h2", "--base", first, "--id", "a2");
        beforePush = () -> git(remote, "update-ref", "refs/heads/main", first);

        assertThat(access("lock", "--person", "alice", "--base", second, "--id", "l"))
                .isEqualTo(65);
        assertThat(head()).as("the rewind stands").isEqualTo(first);
    }

    @Test
    void anEmptyRepositoryIsWrittenOnTheBranchItsHeadNames() throws Exception {
        // A main beside an unborn master is a branch nobody fetches: the access file is lost.
        deleteTree(remote);
        git(dir, "init", "-q", "--bare", "-b", "master", remote.toString());

        final String revision = init();

        assertThat(git(remote, "rev-parse", "refs/heads/master")).isEqualTo(revision);
        assertThat(git(remote, "for-each-ref", "refs/heads/main")).isEmpty();
    }

    @Test
    void aDetachedHeadIsRefused() throws Exception {
        // The head read would not be the branch written.
        final String first = init();
        git(remote, "update-ref", "--no-deref", "HEAD", first);

        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "l")).isEqualTo(65);
        assertThat(err.toString()).contains("is detached");
    }

    @Test
    void theCallersGitEnvironmentReachesNoGitTheCommandRuns() throws Exception {
        // Inherited, GIT_CONFIG_* or the caller's global configuration rewrites the access
        // repository's URL, and the signed commit goes to whoever the rewrite names.
        final Path decoy = dir.resolve("decoy.git");
        git(dir, "init", "-q", "--bare", "-b", "main", decoy.toString());
        final String rewrite = "[url \"" + decoy.toUri() + "\"]\n\tinsteadOf = " + url() + "\n";
        final Path global = dir.resolve("global.gitconfig");
        Files.writeString(global, rewrite);
        final Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".gitconfig"), rewrite);
        final Map<String, String> environment = new java.util.HashMap<>(System.getenv());
        environment.putAll(
                Map.of(
                        "GIT_CONFIG_COUNT",
                        "1",
                        "GIT_CONFIG_KEY_0",
                        "url." + decoy.toUri() + ".insteadOf",
                        "GIT_CONFIG_VALUE_0",
                        url(),
                        "GIT_CONFIG_GLOBAL",
                        global.toString(),
                        "HOME",
                        home.toString()));
        caller = environment;

        final String revision = init();

        assertThat(revision).as("the commit went to the access repository").isEqualTo(head());
        assertThat(git(decoy, "rev-parse", "--all")).as("and nowhere else").isEmpty();
    }

    @Test
    void aSignerLineTheFollowWouldNotVerifyACommitBySignsNothingPushed() throws Exception {
        // Judged by a reading of its own, a line the follow takes differently signed a commit
        // that every first follow refuses, while the walk from the commit in force skipped it.
        final String first = init();
        refresh();
        final Path signers = context().paths().projects().configurationSigners();
        final List<String> lines =
                List.of(
                        "demo namespaces=\"GIT\" " + publicKey(signer) + "\n",
                        "demo cert-authority "
                                + publicKey(signer)
                                + "\ndemo "
                                + publicKey(signer)
                                + "\n");
        int day = 1;
        for (final String line : lines) {
            Files.writeString(signers, line);

            assertThat(
                            access(
                                    "add",
                                    "--person",
                                    "alice",
                                    "--host",
                                    "h1",
                                    "--card",
                                    card("alice", alice, "2026-11-0" + day++),
                                    "--base",
                                    first,
                                    "--id",
                                    "renew-" + day))
                    .as("refused under " + line.strip() + ": " + err)
                    .isEqualTo(65);
            assertThat(err.toString()).contains("the follow would refuse the access commit made");
            assertThat(head()).as("nothing was pushed").isEqualTo(first);
        }
    }

    @Test
    void aRedirectFromTheAccessRepositoryIsNotFollowed() throws Exception {
        // Followed, the redirect sends the signed commit, and what a follow lends to reach the
        // repository, to an address the project never named.
        final List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        final com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress(
                                java.net.InetAddress.getLoopbackAddress(), 0),
                        0);
        server.createContext(
                "/",
                exchange -> {
                    final String path = exchange.getRequestURI().getPath();
                    asked.add(path);
                    if (path.startsWith("/a/")) {
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort()
                                                + "/b/"
                                                + path.substring("/a/".length())
                                                + "?"
                                                + exchange.getRequestURI().getRawQuery());
                        exchange.sendResponseHeaders(302, -1);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                    exchange.close();
                });
        server.start();
        try {
            inForceNaming("http://127.0.0.1:" + server.getAddress().getPort() + "/a/access.git");

            assertThat(
                            access(
                                    "init",
                                    "--person",
                                    "alice",
                                    "--host",
                                    "h1",
                                    "--card",
                                    card("alice", alice),
                                    "--id",
                                    "first"))
                    .isEqualTo(66);
        } finally {
            server.stop(0);
        }
        assertThat(asked).as("the repository named was asked").isNotEmpty();
        assertThat(asked)
                .as("the address it redirected to was not")
                .noneMatch(path -> path.startsWith("/b/"));
    }

    @Test
    void anUnbornHeadBesideOtherRefsIsRefused() throws Exception {
        // Probed with a clone, a tag beside the unborn HEAD was fetched whole, whatever its size,
        // and the access file begun on a branch beside commits no follow reads.
        final Path seed = Files.createDirectories(dir.resolve("seed"));
        git(seed, "init", "-q", "-b", "main", ".");
        git(
                seed,
                "-c",
                "user.name=O",
                "-c",
                "user.email=o@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "--no-gpg-sign",
                "-m",
                "tagged");
        git(seed, "push", "-q", url(), "HEAD:refs/tags/t");

        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                card("alice", alice),
                                "--id",
                                "first"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("names no commit, and the repository holds other refs");
        assertThat(git(remote, "for-each-ref", "refs/heads")).as("nothing was pushed").isEmpty();
    }

    @Test
    void aSnippetFileIsReadFromTheCommitInForce() throws Exception {
        // Resolved from the directory the command runs in, it read whatever lay there - or
        // waited on a pipe - instead of the commit's own file.
        Files.writeString(project.resolve("img.dockerfile"), "RUN true\n");
        Files.writeString(
                project.resolve("project.yml"),
                projectFile(url())
                        .replace(
                                "base_image: ubuntu:24.04",
                                "base_image: ubuntu:24.04\n  snippet_file: img.dockerfile"));
        git(project, "add", "--all");
        git(
                project,
                "-c",
                "user.name=Operator",
                "-c",
                "user.email=operator@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signer,
                "commit",
                "-q",
                "-S",
                "-m",
                "snippet");
        final FollowedProjects projects =
                new FollowedProjects(context().paths().projects().followed());
        final FollowedProjects.Followed before = projects.require("demo");
        projects.write(Reconcile.after(before, new Reconcile(context()).run(before)));
        assertThat(projects.require("demo").commit())
                .as("the snippet commit is in force")
                .isEqualTo(git(project, "rev-parse", "HEAD"));

        assertThat(init()).isEqualTo(head());
    }

    @Test
    void anIdCarriedOnceWithTwoDigestsIsNoAnswer() throws Exception {
        // Paired with the first digest, a commit carrying a second answered "done" for a change
        // it may never have made.
        final String first = init();
        final String digest =
                "sha256:"
                        + java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        "action=lock\nperson=alice\nhost=\nrole=\ncard=\n"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)));
        final String twice =
                pushed(
                        true,
                        "notes\n\nPorelo-Change-Id: lock-alice\nPorelo-Change-Digest: "
                                + digest
                                + "\nPorelo-Change-Digest: sha256:00\n",
                        tree -> Files.writeString(tree.resolve("notes.md"), "b\n"));

        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "lock-alice"))
                .as("two digests under one id are no answer")
                .isEqualTo(65);
        assertThat(err.toString()).contains("the access repository is at " + twice);
    }

    @Test
    void withoutJsonTheRevisionIsOneLine() throws Exception {
        // A program reading the plain answer takes the commit from this line.
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final int code =
                cmd.execute(
                        "project",
                        "access",
                        "init",
                        "--person",
                        "alice",
                        "--host",
                        "h1",
                        "--card",
                        card("alice", alice),
                        "--id",
                        "first",
                        "--project",
                        "demo");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString()).isEqualTo("revision " + head() + System.lineSeparator());
    }

    @Test
    void aPushRaceIsRefusedNamingTheRemoteHeadAndTheBase() throws Exception {
        // A forced or merged push would drop the other change, which nobody reviewed here.
        final String first = init();
        final AtomicBoolean raced = new AtomicBoolean();
        beforePush =
                () -> {
                    if (raced.compareAndSet(false, true)) {
                        final Path other = dir.resolve("other");
                        git(dir, "clone", "-q", url(), other.toString());
                        try {
                            Files.writeString(other.resolve("notes.md"), "faster\n");
                        } catch (final IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                        git(other, "add", "--all");
                        git(
                                other,
                                "-c",
                                "user.name=O",
                                "-c",
                                "user.email=o@example.org",
                                "commit",
                                "-q",
                                "--no-gpg-sign",
                                "-m",
                                "faster");
                        git(other, "push", "-q", "origin", "HEAD:main");
                    }
                };

        assertThat(access("lock", "--person", "alice", "--base", first, "--id", "slow"))
                .as("the push is rejected without force")
                .isEqualTo(65);
        final String faster = head();
        assertThat(faster).isNotEqualTo(first);
        assertThat(err.toString())
                .as("the remote head and the base are named")
                .contains(faster, first);
        assertThat(git(remote, "log", "--format=%s", "main"))
                .as("the faster change is kept")
                .doesNotContain("Access: lock");
    }

    @Test
    void anAgentHoldingNoSignerKeyIsRefusedNamingTheSigners() throws Exception {
        // A commit signed by any other key is one the follow refuses, after the push.
        runner.run(
                Command.of("ssh-add", "-D")
                        .withEnvironment(Map.of("SSH_AUTH_SOCK", socket.toString())));
        agentAdds(key("stranger"));

        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                card("alice", alice),
                                "--id",
                                "first"))
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the signers' fingerprints are named")
                .contains(SignedBy.fingerprintOf(publicKey(signer)));
        assertThat(git(remote, "rev-parse", "--all")).as("nothing was pushed").isEmpty();
    }

    @Test
    void aCardOfAnotherLoginIsRefused() throws Exception {
        // A person pinned to someone else's card would sign in as them.
        assertThat(
                        access(
                                "init",
                                "--person",
                                "alice",
                                "--host",
                                "h1",
                                "--card",
                                card("bob", bob),
                                "--id",
                                "first"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("is the card of bob, not of alice");
        assertThat(git(remote, "rev-parse", "--all")).as("nothing was pushed").isEmpty();
    }

    @Test
    void theScratchDirectoryIsGoneAfterSuccessAndAfterRefusal() throws Exception {
        // A clone left behind in the temp directory keeps who works where readable by anyone.
        final List<Path> before = scratches();
        final String first = init();
        assertThat(scratches()).as("after success").isEqualTo(before);

        assertThat(access("lock", "--person", "alice", "--base", "0".repeat(40), "--id", "x"))
                .as("refused after the clone")
                .isEqualTo(65);
        assertThat(scratches()).as("after refusal").isEqualTo(before);
        assertThat(head()).isEqualTo(first);
    }

    private static List<Path> scratches() throws IOException {
        try (var entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.filter(
                            path -> path.getFileName().toString().startsWith("porelo-access-"))
                    .sorted()
                    .toList();
        }
    }

    private String init() throws IOException {
        return change(
                "init",
                "--person",
                "alice",
                "--host",
                "h1",
                "--card",
                card("alice", alice),
                "--id",
                "first");
    }

    private String change(final String... args) {
        final int code = access(args);
        assertThat(code).as("the change is made: " + err).isZero();
        final Map<?, ?> answer =
                (Map<?, ?>) org.fuin.porelo.wire.Json.parse(out.toString().strip());
        return (String) answer.get("revision");
    }

    private int access(final String... args) {
        return access(true, args);
    }

    private int access(final boolean json, final String... args) {
        out = new StringWriter();
        err = new StringWriter();
        final ProjectAccessCommand command = new ProjectAccessCommand();
        command.setContext(context());
        final Map<String, String> environment = caller;
        command.setCallerEnvironment(() -> environment);
        final CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--project", "demo"));
        if (json) {
            all.add("--json");
        }
        running = true;
        try {
            return cmd.execute(all.toArray(String[]::new));
        } finally {
            running = false;
        }
    }

    /** Puts the access repository's head in force here, as a refresh does, and answers it. */
    private String refresh() throws IOException {
        final FollowedProjects projects =
                new FollowedProjects(context().paths().projects().followed());
        final FollowedProjects.Followed before = projects.require("demo");
        projects.write(Reconcile.after(before, new Reconcile(context()).run(before)));
        final String inForce = projects.require("demo").accessCommit();
        assertThat(inForce).as("the access commit is in force").isEqualTo(head());
        return inForce;
    }

    /** A change to a tree, as somebody else makes it. */
    private interface Edit {
        void apply(Path tree) throws IOException;
    }

    /**
     * Somebody with push access writes a commit onto the access repository's main, signed by the
     * project's signer or not signed at all, and the new head is answered.
     */
    private String pushed(final boolean signed, final String message, final Edit edit)
            throws IOException {
        final Path other = dir.resolve("other-" + System.nanoTime());
        git(dir, "clone", "-q", url(), other.toString());
        edit.apply(other);
        git(other, "add", "--all");
        git(
                other,
                "-c",
                "user.name=O",
                "-c",
                "user.email=o@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signer,
                "commit",
                "-q",
                "--allow-empty",
                signed ? "-S" : "--no-gpg-sign",
                "-m",
                message);
        git(other, "push", "-q", "-f", "origin", "HEAD:main");
        return head();
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            for (final Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String projectFile(final String url) {
        return """
                project:
                  name: demo
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                access: {repository: "%s"}
                """
                .formatted(url);
    }

    private Map<?, ?> verified() throws IOException {
        out = new StringWriter();
        err = new StringWriter();
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        assertThat(
                        cmd.execute(
                                "project",
                                "verify",
                                "--repository",
                                project.toString(),
                                "--signed-by",
                                publicKey(signer),
                                "--access-repository",
                                remote.toString(),
                                "--json"))
                .as("the written access file passes the follow's check: " + err)
                .isZero();
        return (Map<?, ?>) org.fuin.porelo.wire.Json.parse(out.toString().strip());
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                command -> {
                    if (command.arguments().getFirst().equals("logger")) {
                        return new CommandResult(command, 0, "", "");
                    }
                    if (command.arguments().contains("push")) {
                        beforePush.run();
                    }
                    // The command's own processes run in the environment it chose, as given: kept
                    // from the caller's git there only by the command. One of them inheriting
                    // instead gets the caller's git, as it would outside a test. The fixture's
                    // follow, inheriting this one, is kept from it here.
                    if (!command.inheritsEnvironment()) {
                        return new ProcessCommandRunner().run(command);
                    }
                    if (!running) {
                        return runner.run(command);
                    }
                    final Map<String, String> inherited = new java.util.HashMap<>();
                    caller.forEach(
                            (name, value) -> {
                                if (name.startsWith("GIT_") || name.equals("HOME")) {
                                    inherited.put(name, value);
                                }
                            });
                    return new ProcessCommandRunner().run(command.withEnvironment(inherited));
                },
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0,
                name -> name.equals("SSH_AUTH_SOCK") ? socket.toString() : null);
    }

    private String url() {
        return remote.toUri().toString();
    }

    private String head() {
        return git(remote, "rev-parse", "refs/heads/main");
    }

    private String card(final String login, final SigningKey key) throws IOException {
        return card(login, key, "2026-10-30");
    }

    /** A card of the key, issued on the day given and valid for a year. */
    private String card(final String login, final SigningKey key, final String issued)
            throws IOException {
        final Path file = dir.resolve(login + "-" + System.nanoTime() + ".card");
        Files.writeString(
                file,
                PersonCard.write(
                        login,
                        login,
                        "",
                        key,
                        LocalDate.parse(issued),
                        LocalDate.parse(issued).plusYears(1),
                        null));
        return file.toString();
    }

    /**
     * Puts a project commit naming the access repository at the URL in force here, as a follow that
     * took it would, without fetching that repository.
     */
    private void inForceNaming(final String url) throws IOException {
        Files.writeString(project.resolve("project.yml"), projectFile(url));
        git(project, "add", "--all");
        git(
                project,
                "-c",
                "user.name=Operator",
                "-c",
                "user.email=operator@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signer,
                "commit",
                "-q",
                "-S",
                "-m",
                "elsewhere");
        final String commit = git(project, "rev-parse", "HEAD");
        git(
                context().paths().projects().followedClone("demo"),
                "fetch",
                "-q",
                project.toString(),
                "main");
        final FollowedProjects projects =
                new FollowedProjects(context().paths().projects().followed());
        final FollowedProjects.Followed was = projects.require("demo");
        projects.write(
                new FollowedProjects.Followed(
                        was.name(),
                        was.url(),
                        commit,
                        was.at(),
                        was.outcome(),
                        was.detail(),
                        was.refused(),
                        was.signer(),
                        was.unverified(),
                        was.accessCommit(),
                        was.accessOutcome(),
                        was.refusedFile(),
                        was.accessRefused(),
                        was.accessRefusedFile()));
    }

    private static Map<String, Object> person(
            final String login,
            final SigningKey key,
            final String role,
            final List<String> hosts,
            final boolean locked) {
        return Map.of(
                "login", login,
                "ssh", PersonCard.keyLine(key),
                "role", role,
                "hosts", hosts,
                "expires", "2027-10-30",
                "revoked", false,
                "locked", locked);
    }

    private void agentAdds(final Path key) {
        final CommandResult added =
                runner.run(
                        Command.of("ssh-add", "-q", key.toString())
                                .withEnvironment(Map.of("SSH_AUTH_SOCK", socket.toString())));
        assertThat(added.exitCode()).as("ssh-add: " + added.standardError()).isZero();
    }

    private Path key(final String name) {
        final Path key = dir.resolve(name);
        final CommandResult made =
                runner.run(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                "",
                                "-C",
                                name,
                                "-f",
                                key.toString()));
        assertThat(made.exitCode()).as("create a test key: " + made.standardError()).isZero();
        return key;
    }

    private static String publicKey(final Path key) throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }

    private String git(final Path repo, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repo.toString()));
        all.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
        return result.standardOutput().strip();
    }
}
