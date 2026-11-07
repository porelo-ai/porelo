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
 * A repository's file signed from the checkout a person keeps. Harm guarded: the checkout is the
 * person's own work, so nothing in it may change but its main by the one signed commit, and the
 * signature may carry nothing the person did not read or the forge does not hold.
 */
class ProjectFileCheckoutTest {

    private static final String TEXT = "egress:\n  domains:\n    - repo.example.org\n";

    private static final String PROJECT =
            """
            project:
              name: "demo"
              security_class: "guarded"

            image:
              from: "ubuntu:24.04"
            """;

    @TempDir private Path dir;

    private Path forge;

    private Path checkout;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, org.fuin.porelo.vault.SigningKey> keys = new HashMap<>();

    @BeforeEach
    void aForgeAndTheCheckoutAPersonKeepsOfIt() throws Exception {
        for (final String login : List.of("alice", "carol", "mallory")) {
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
        forge = dir.resolve("forge.git");
        git(dir, "init", "-q", "--bare", "-b", "main", forge.toString());
        checkout = dir.resolve("checkout");
        git(dir, "clone", "-q", forge.toString(), checkout.toString());
        git(checkout, "checkout", "-q", "-b", "main");
        Files.writeString(checkout.resolve("porelo.yml"), TEXT);
        Files.writeString(checkout.resolve("README.md"), "work\n");
        git(checkout, "add", "-A");
        git(checkout, "commit", "-q", "-m", "Unsigned settings");
        git(checkout, "push", "-q", "origin", "main");
    }

    private String git(final Path in, final String... args) {
        return git(in, Map.of(), args);
    }

    private String git(final Path in, final Map<String, String> more, final String... args) {
        final Map<String, String> environment =
                new HashMap<>(
                        Map.of(
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_AUTHOR_NAME",
                                "Person",
                                "GIT_AUTHOR_EMAIL",
                                "person@example.org",
                                "GIT_COMMITTER_NAME",
                                "Person",
                                "GIT_COMMITTER_EMAIL",
                                "person@example.org"));
        environment.putAll(more);
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all).in(in).withEnvironment(environment))
                .trimmedOutput();
    }

    /** Commits what is staged, signed by alice and dated, since a newer file is judged by date. */
    private void signedOn(final Path in, final String date, final String message) {
        git(
                in,
                Map.of("GIT_COMMITTER_DATE", date, "GIT_AUTHOR_DATE", date),
                "-c",
                "user.signingkey=" + keyFiles.get("alice"),
                "commit",
                "-q",
                "-S",
                "-m",
                message);
    }

    private String key(final String holder) {
        return PersonCard.keyLine(keys.get(holder));
    }

    private List<SigningKey> rows() {
        return List.of(
                new SigningKey(
                        "alice",
                        "admin",
                        key("alice"),
                        LocalDate.of(2000, 1, 1),
                        LocalDate.of(2999, 1, 1),
                        null),
                new SigningKey(
                        "carol",
                        "developer",
                        key("carol"),
                        LocalDate.of(2000, 1, 1),
                        LocalDate.of(2999, 1, 1),
                        null));
    }

    private ProjectFile file() {
        final Map<String, Path> files = new HashMap<>();
        keys.keySet().forEach(each -> files.put(key(each), keyFiles.get(each)));
        return new ProjectFile(
                runner,
                FetchAccess.NONE,
                new CommitSigning(Map.of(), files),
                dir.resolve("memory"));
    }

    private Written sign(final String holder, final String id) throws IOException {
        return file().signCheckout(
                        new Change(new Acting(holder, key(holder)), null, id),
                        checkout,
                        forge.toString(),
                        rows());
    }

    private String forgeMain() {
        return git(forge, "rev-parse", "refs/heads/main");
    }

    private String checkoutMain() {
        return git(checkout, "rev-parse", "refs/heads/main");
    }

    /** What a person sees of their checkout besides its main: its refs, index and work. */
    private List<String> allButMain() throws IOException {
        return List.of(
                git(checkout, "for-each-ref", "--format=%(refname) %(objectname)")
                        .lines()
                        .filter(line -> !line.startsWith("refs/heads/main "))
                        .toList()
                        .toString(),
                git(checkout, "status", "--porcelain=v1", "--untracked-files=all"),
                git(checkout, "symbolic-ref", "HEAD"),
                Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(checkout.resolve(".git/index"))),
                Files.readString(checkout.resolve("porelo.yml")),
                Files.readString(checkout.resolve(".git/config")));
    }

    @Test
    void aDeveloperSignsTheCheckoutsMainAndOnlyThatMainMovesByTheOneCommit() throws Exception {
        final String read = checkoutMain();
        final List<String> before = allButMain();

        final Written written = sign("carol", "sign-web");

        assertThat(written.again()).as("a first signing is made, not found").isFalse();
        assertThat(forgeMain())
                .as("the forge holds the signed commit")
                .isEqualTo(written.revision());
        assertThat(git(forge, "rev-parse", written.revision() + "^"))
                .as("on top of the main the person read")
                .isEqualTo(read);
        assertThat(git(forge, "show", "--no-patch", "--format=%B", written.revision()))
                .as("signing the repository file anew")
                .contains("Porelo-Signs-Anew: porelo.yml");
        assertThat(git(forge, "diff", read, written.revision())).as("no byte changed").isEmpty();
        assertThat(checkoutMain())
                .as("the checkout's main holds the signature too")
                .isEqualTo(written.revision());
        assertThat(allButMain())
                .as("its other refs, its index, its work and its config stay as they were")
                .isEqualTo(before);
    }

    @Test
    void aForgeThatMovedOnRefusesAndTheCheckoutStaysAsItWas() throws Exception {
        final Path other = dir.resolve("other");
        git(dir, "clone", "-q", forge.toString(), other.toString());
        git(other, "commit", "-q", "--allow-empty", "-m", "Someone else's");
        git(other, "push", "-q", "origin", "main");
        final String moved = forgeMain();
        final String read = checkoutMain();

        assertThatThrownBy(() -> sign("carol", "behind"))
                .as("what the person read is no longer the forge's main")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.MOVED_ON));
        assertThat(forgeMain()).as("nothing is pushed").isEqualTo(moved);
        assertThat(checkoutMain()).as("the checkout stays as it was").isEqualTo(read);
    }

    @Test
    void aCommitOnlyTheCheckoutHoldsIsRefusedAndNamed() throws Exception {
        final String pushed = forgeMain();
        git(checkout, "commit", "-q", "--allow-empty", "-m", "Not pushed yet");
        final String local = checkoutMain();

        assertThatThrownBy(() -> sign("carol", "ahead"))
                .as("a signature would carry a commit the person did not push")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo(Refused.REFUSED_STATE);
                            assertThat(refused.getMessage()).contains(local);
                        });
        assertThat(forgeMain()).as("nothing is pushed").isEqualTo(pushed);
        assertThat(checkoutMain()).as("the checkout stays as it was").isEqualTo(local);
    }

    @Test
    void aCheckoutWhoseOriginIsAnotherAddressIsRefusedNamingBoth() throws Exception {
        final Path elsewhere = dir.resolve("elsewhere.git");
        git(dir, "clone", "-q", "--bare", forge.toString(), elsewhere.toString());
        git(checkout, "remote", "set-url", "origin", elsewhere.toString());
        final String pushed = forgeMain();

        assertThatThrownBy(() -> sign("carol", "elsewhere"))
                .as("the checkout is not of the repository the project names")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .contains(elsewhere.toString())
                                        .contains(forge.toString()));
        assertThat(forgeMain()).as("nothing is pushed").isEqualTo(pushed);
    }

    @Test
    void theCheckoutsHooksAndAddressRewritesHaveNoSay() throws Exception {
        final Path ran = dir.resolve("hook-ran");
        for (final String hook : List.of("reference-transaction", "pre-push", "post-commit")) {
            final Path script = checkout.resolve(".git/hooks/" + hook);
            Files.writeString(script, "#!/bin/sh\ntouch '" + ran + "'\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
        final Path decoy = dir.resolve("decoy.git");
        git(dir, "init", "-q", "--bare", decoy.toString());
        git(checkout, "config", "url." + decoy + ".insteadOf", forge.toString());
        git(checkout, "config", "url." + decoy + ".pushInsteadOf", forge.toString());

        final Written written = sign("carol", "hooks");

        assertThat(forgeMain())
                .as("the push reaches the project's upstream, not the checkout's rewrite")
                .isEqualTo(written.revision());
        assertThat(Files.exists(ran)).as("no hook of the checkout ran").isFalse();
        assertThat(checkoutMain()).isEqualTo(written.revision());
    }

    @Test
    void aWorktreeOfTheCheckoutSignsLikeTheCheckoutItself() throws Exception {
        final Path worktree = dir.resolve("worktree");
        git(checkout, "worktree", "add", "-q", "-b", "side", worktree.toString());
        checkout = worktree;

        final Written written = sign("carol", "worktree");

        assertThat(forgeMain()).isEqualTo(written.revision());
        assertThat(git(dir.resolve("checkout"), "rev-parse", "refs/heads/main"))
                .as("main, kept in the common git directory, moved")
                .isEqualTo(written.revision());
        assertThat(git(worktree, "symbolic-ref", "HEAD"))
                .as("the worktree's own branch is left alone")
                .isEqualTo("refs/heads/side");
    }

    @Test
    void aKeyOfNoAdminOrDeveloperSignsNothing() throws Exception {
        final String pushed = forgeMain();

        assertThatThrownBy(() -> sign("mallory", "outsider"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThat(forgeMain()).isEqualTo(pushed);
        assertThat(checkoutMain()).isEqualTo(pushed);
    }

    @Test
    void aSigningAskedAgainUnderItsIdAnswersWhatItMade() throws Exception {
        final Written first = sign("carol", "again");

        final Written second = sign("carol", "again");

        assertThat(second.again()).as("found, not made twice").isTrue();
        assertThat(second.revision()).isEqualTo(first.revision());
        assertThat(forgeMain()).isEqualTo(first.revision());
    }

    @Test
    void aCheckoutWithoutTheFileIsRefused() throws Exception {
        git(checkout, "rm", "-q", "porelo.yml");
        git(checkout, "commit", "-q", "-m", "No settings");
        git(checkout, "push", "-q", "origin", "main");

        assertThatThrownBy(() -> sign("carol", "absent"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.REFUSED_STATE));
    }

    @Test
    void aLookShowsTheFileAtTheCheckoutsMainAndItsNewestChange() throws Exception {
        final String read = checkoutMain();

        final ProjectFile.RepositoryLook look = file().lookCheckout(checkout, rows());

        assertThat(look.look().revision()).as("the main it would sign").isEqualTo(read);
        assertThat(look.look().text()).isEqualTo(TEXT);
        assertThat(look.change()).as("the newest change of the file").isEqualTo(read);
        assertThat(look.look().read().state())
                .as("an unsigned change is no signer's")
                .isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aProjectFileIsJudgedInTheProjectsCheckoutAndAnOlderOneIsRefused() throws Exception {
        final Path project = dir.resolve("project");
        git(dir, "init", "-q", "-b", "main", project.toString());
        git(project, "config", "gpg.format", "ssh");
        Files.writeString(project.resolve("project.yml"), PROJECT);
        git(project, "add", "-A");
        signedOn(project, "2026-01-02T00:00:00Z", "Newer");
        final String address = "git@github.com:acme/demo.git";

        final ProjectFile.Read newer = file().readCheckout(project, address, rows(), "demo");
        assertThat(newer.state()).as("an admin's file reads: %s", newer.why()).isEqualTo("read");
        assertThat(newer.project().name()).isEqualTo("demo");

        git(project, "checkout", "-q", "--orphan", "older");
        signedOn(project, "2025-01-02T00:00:00Z", "Older");
        git(project, "branch", "-f", "main", "older");
        final ProjectFile.Read older = file().readCheckout(project, address, rows(), "demo");
        assertThat(older.state())
                .as("this machine accepted a newer file from that project: %s", older.why())
                .isEqualTo(ProjectFile.REFUSED);
    }

    @Test
    void aProjectFileNamingAnotherProjectIsRefusedForTheNameItWasKeptUnder() throws Exception {
        final Path project = dir.resolve("project");
        git(dir, "init", "-q", "-b", "main", project.toString());
        git(project, "config", "gpg.format", "ssh");
        Files.writeString(project.resolve("project.yml"), PROJECT);
        git(project, "add", "-A");
        signedOn(project, "2026-01-02T00:00:00Z", "Demo's");

        final ProjectFile.Read read =
                file().readCheckout(project, "git@github.com:acme/alpha.git", rows(), "alpha");

        assertThat(read.state())
                .as("alpha's keys judge only alpha's file")
                .isEqualTo(ProjectFile.REFUSED);
        assertThat(read.why()).contains("demo").contains("alpha");
    }

    @Test
    void aSigningTheForgeNoLongerHoldsIsNotAnsweredAsMade() throws Exception {
        final String read = forgeMain();
        final Written first = sign("carol", "dropped");
        git(forge, "update-ref", "refs/heads/main", read);

        assertThatThrownBy(() -> sign("carol", "dropped"))
                .as("only what the forge holds was made; the checkout's copy is a local commit")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo(Refused.REFUSED_STATE);
                            assertThat(refused.getMessage()).contains(first.revision());
                        });
        assertThat(forgeMain()).as("nothing is pushed").isEqualTo(read);
    }

    @Test
    void aForgeWithoutMainIsRefusedAsItStands() throws Exception {
        final Path empty = dir.resolve("empty.git");
        git(dir, "init", "-q", "--bare", "-b", "main", empty.toString());
        git(checkout, "remote", "set-url", "origin", empty.toString());

        assertThatThrownBy(
                        () ->
                                file().signCheckout(
                                                new Change(
                                                        new Acting("carol", key("carol")),
                                                        null,
                                                        "empty"),
                                                checkout,
                                                empty.toString(),
                                                rows()))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo(Refused.REFUSED_STATE);
                            assertThat(refused.getMessage()).contains("no main");
                        });
    }

    @Test
    void aShallowCheckoutIsRefused() throws Exception {
        final Path shallow = dir.resolve("shallow");
        git(dir, "clone", "-q", "--depth", "1", "file://" + forge, shallow.toString());

        assertThatThrownBy(() -> ProjectFile.checkoutMain(runner, shallow))
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.getMessage()).contains("shallow"));
    }
}
