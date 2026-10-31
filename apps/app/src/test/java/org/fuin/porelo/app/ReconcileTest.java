package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Reconcile}, against real git and real signatures.
 *
 * <p>The property worth measuring is the order: a machine handed something it cannot verify must
 * keep running what it verified last. A fake runner would let that pass without the working tree
 * ever having been at risk.
 */
class ReconcileTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"commit", "tag", "unsigned"})
    void a_merged_tags_signature_cannot_claim_the_merge_commit(
            final String anchor, @TempDir final Path dir) throws Exception {
        // A tag signer must not impersonate the signer authorizing a protected merge.
        final PoreloContext context = context(dir);
        final Path operator = key(dir, "operator");
        final Path tagger = key(dir, "tagger");
        final Path repo = published(dir, operator, "demo");
        git(repo, "checkout", "-q", "-b", "topic");
        commit(repo, operator, "demo", "topic configuration");
        git(
                repo,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + tagger,
                "tag",
                "-s",
                "-m",
                "signed release",
                "release");
        git(repo, "checkout", "-q", "main");
        Files.writeString(repo.resolve("notes.md"), "main notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
        git(
                repo,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + operator,
                "merge",
                "-q",
                "--no-ff",
                anchor.equals("unsigned") ? "--no-gpg-sign" : "-S",
                "-m",
                "merge signed tag",
                "refs/tags/release");
        final String object =
                runner.run(Command.of("git", "-C", repo.toString(), "cat-file", "commit", "HEAD"))
                        .standardOutput();
        assertThat(object)
                .as("Git embeds the signed annotated tag in the merge header")
                .contains("\nmergetag ", " -----BEGIN SSH SIGNATURE-----");
        final String expected = anchor.equals("unsigned") ? null : typeAndKey(operator);
        assertThat(SignedBy.keyOf(runner, repo, "HEAD"))
                .as("only the merge commit's own signature identifies its signer")
                .isEqualTo(expected);
        if (!anchor.equals("unsigned")) {
            final String fingerprint =
                    SignedBy.fingerprintOf(typeAndKey(anchor.equals("tag") ? tagger : operator));
            if (anchor.equals("tag")) {
                assertThat(
                                catchThrowable(
                                        () ->
                                                new FollowSignedBy(context)
                                                        .follow(
                                                                "demo",
                                                                repo.toString(),
                                                                fingerprint,
                                                                false,
                                                                false)))
                        .as("the tag signer's fingerprint cannot pin the merge")
                        .isInstanceOf(FollowSignedBy.Refused.class);
                assertThat(context.paths().projects().configurationSigners())
                        .as("the tag signer gains no trust")
                        .doesNotExist();
            } else {
                final var answer =
                        new FollowSignedBy(context)
                                .follow("demo", repo.toString(), fingerprint, false, false);
                assertThat(answer.recorded()).as(answer.result().detail()).isTrue();
                assertThat(answer.pinned()).as("the commit's key is pinned").isEqualTo(fingerprint);
                assertThat(answer.result().signer())
                        .as("the commit's signer is reported")
                        .isEqualTo(fingerprint);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"retired", "self-added", "protected", "first"})
    void a_refused_cli_follow_publishes_no_new_record(final String kind, @TempDir final Path dir)
            throws Exception {
        // Refusals must preserve the applied authority and cannot create a partially trusted
        // follow.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        if (!kind.equals("first")) {
            assertThat(
                            cli(
                                            context,
                                            "project",
                                            "follow",
                                            "demo",
                                            repo.toString(),
                                            "--signed-by",
                                            typeAndKey(old))
                                    .code())
                    .as("the initial authority is applied")
                    .isZero();
        }
        if (kind.equals("retired")) {
            commitNaming(repo, old, "handover", old, successor);
            assertThat(cli(context, "project", "follow", "demo", repo.toString()).code())
                    .as("handover applies")
                    .isZero();
            commitNaming(repo, old, "retirement", successor);
            assertThat(cli(context, "project", "follow", "demo", repo.toString()).code())
                    .as("retirement applies")
                    .isZero();
        }
        if (kind.equals("protected")) {
            Files.writeString(repo.resolve("notes.md"), "unsigned notes\n");
            git(repo, "add", "notes.md");
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
            assertThat(cli(context, "project", "follow", "demo", repo.toString()).code())
                    .as("unsigned documents apply")
                    .isZero();
        }
        final String before = cli(context, "project", "following", "--json").out();
        if (kind.equals("retired")) {
            commitNaming(repo, old, "backdated configuration", successor);
            date(repo, "2001-01-01T00:00:00Z");
        } else if (kind.equals("self-added")) {
            final Path stranger = key(dir, "stranger");
            commitNaming(repo, stranger, "self-added key", old, stranger);
        } else {
            commit(repo, null, "demo", "unsigned configuration");
        }
        final String refused = gitOutput(repo, "rev-parse", "HEAD");
        if (kind.equals("protected")) {
            Files.writeString(repo.resolve("notes.md"), "signed notes\n");
            git(repo, "add", "notes.md");
            git(repo, "commit", "-q", "-S", "-m", "signed notes");
        }

        final CliReply refusal = cli(context, "project", "follow", "demo", repo.toString());
        final CliReply after = cli(context, "project", "following", "--json");

        assertThat(refusal.code())
                .as("refusing the new authority is a failed command")
                .isEqualTo(70);
        assertThat(refusal.err())
                .as("the command names the refused commit and protected path")
                .contains(refused, "project.yml", "nothing was recorded");
        if (kind.equals("self-added")) {
            assertThat(refusal.err())
                    .as("the attempted authority change is named")
                    .contains("changes project.signers:");
        }
        assertThat(after.code()).as("listing remains available after a refusal").isZero();
        assertThat(after.out())
                .as("a refusal preserves exactly the previously published listing")
                .isEqualTo(before);
        if (kind.equals("first")) {
            assertThat(after.out())
                    .as("a refused first follow does not name the project")
                    .doesNotContain("demo");
        }
        System.out.println(
                "CLI scenario "
                        + kind
                        + ": exit="
                        + refusal.code()
                        + "\n"
                        + refusal.err()
                        + "following before="
                        + before
                        + "following after="
                        + after.out());
    }

    private record CliReply(int code, String out, String err) {}

    private CliReply cli(final PoreloContext context, final String... args) {
        final var out = new java.io.StringWriter();
        final var err = new java.io.StringWriter();
        final var command = PoreloCli.commandLine(context);
        command.setOut(new java.io.PrintWriter(out));
        command.setErr(new java.io.PrintWriter(err));
        return new CliReply(command.execute(args), out.toString(), err.toString());
    }

    @Test
    void a_shallow_clone_is_refused_before_reading_its_parent(@TempDir final Path dir)
            throws Exception {
        // Shallow metadata must refuse publication even when its hidden parent object is present.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        commit(repo, old, "demo", "signed configuration");
        pin(context, "demo", old);
        final var projects = new FollowedProjects(context.paths().projects().followed());
        projects.follow("demo", repo.toString());
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), clone.toString());
        final String parent = gitOutput(repo, "rev-parse", "HEAD^");
        git(clone, "fetch", "--quiet", repo.toString(), parent);
        assertThat(gitOutput(clone, "rev-parse", "--is-shallow-repository"))
                .as("fetching the parent object did not remove the shallow boundary")
                .isEqualTo("true");
        assertThat(gitOutput(clone, "cat-file", "-t", parent))
                .as("a missing-object refusal cannot mask the shallow guard")
                .isEqualTo("commit");

        final var result = new Reconcile(context).run(projects.require("demo"));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.detail())
                .as("a shallow clone is refused as shallow before walking ancestry")
                .contains("cannot judge in shallow repository");
        final var handed =
                new SignerHandover(runner, context.paths().projects().configurationSigners())
                        .walk(clone, "demo", "", gitOutput(clone, "rev-parse", "HEAD"), null);
        assertThat(handed.refused())
                .as("direct signature checking also refuses shallow history")
                .isNotNull();
        assertThat(handed.refused().detail())
                .as("the handover guard names the shallow clone")
                .contains("cannot judge in shallow repository", clone.toString());
    }

    @Test
    void a_check_judges_the_live_clone_as_the_follow_does(@TempDir final Path dir)
            throws Exception {
        // A dry run fetched into an empty scratch clone and said "ready" where the follow, working
        // on a copy of the live clone, refused it as shallow.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        commit(repo, old, "demo", "signed configuration");
        pin(context, "demo", old);
        final var projects = new FollowedProjects(context.paths().projects().followed());
        projects.follow("demo", repo.toString());
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), clone.toString());

        final var checked = new Reconcile(context).checked(projects.require("demo")).result();
        final var followed = new Reconcile(context).run(projects.require("demo"));

        assertThat(followed.outcome())
                .as(followed.detail())
                .isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(checked.outcome())
                .as("the check says what the follow does: " + checked.detail())
                .isEqualTo(followed.outcome());
        assertThat(gitOutput(clone, "rev-parse", "--is-shallow-repository"))
                .as("the check changed nothing of the live clone")
                .isEqualTo("true");
    }

    @Test
    void an_unverified_follow_refuses_a_shallow_live_clone_too(@TempDir final Path dir)
            throws Exception {
        // With no signature walk behind it, the clone's own guard is the only one left.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        commit(repo, null, "demo", "second configuration");
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), clone.toString());

        final var result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                repo.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.detail()).contains("shallow repository");
    }

    @Test
    void every_dry_run_answers_what_its_follow_does_and_records_nothing(@TempDir final Path dir)
            throws Exception {
        // Through the dry run itself, keyless, by a whole key and by a fingerprint: each prepared a
        // clone of its own, and the fingerprint's left the follow directory behind.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        commit(repo, old, "demo", "signed configuration");
        final var fresh = new FollowSignedBy(context);
        final String fingerprint = SignedBy.fingerprintOf(typeAndKey(old));
        final var byFingerprint =
                fresh.check("demo", repo.toString(), fingerprint, false, false).result();
        assertThat(byFingerprint.outcome())
                .as(byFingerprint.detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(context.paths().projects().followed())
                .as("a fingerprint's dry run records nothing")
                .doesNotExist();

        pin(context, "demo", old);
        final var projects = new FollowedProjects(context.paths().projects().followed());
        projects.follow("demo", repo.toString());
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), clone.toString());
        final var follow = new FollowSignedBy(context);

        final var keyless = follow.check("demo", repo.toString(), null, false, false).result();
        final var whole =
                follow.check("demo", repo.toString(), typeAndKey(old), false, false).result();
        final var followed = follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(followed.outcome()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(keyless.outcome()).as(keyless.detail()).isEqualTo(followed.outcome());
        assertThat(whole.outcome()).as(whole.detail()).isEqualTo(followed.outcome());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"bare", "linked", "dangling", "empty-git", "no-objects"})
    void a_live_clone_that_is_no_work_tree_is_made_again_and_never_fetched_into(
            final String kind, @TempDir final Path dir) throws Exception {
        // A bare clone wedged the follow, an empty .git let git look upwards, and a linked clone
        // was copied as the link, so a dry run fetched into what it pointed at.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        final Path elsewhere = dir.resolve("elsewhere");
        switch (kind) {
            case "bare" -> git(dir, "clone", "-q", "--bare", repo.toString(), clone.toString());
            case "linked" -> {
                git(dir, "clone", "-q", repo.toString(), elsewhere.toString());
                Files.createSymbolicLink(clone, elsewhere);
            }
            case "dangling" -> Files.createSymbolicLink(clone, dir.resolve("missing"));
            case "no-objects" -> {
                Files.createDirectories(clone.resolve(".git"));
                Files.writeString(clone.resolve(".git/HEAD"), "ref: refs/heads/main\n");
            }
            default -> Files.createDirectories(clone.resolve(".git"));
        }
        final var followed =
                following(
                        context,
                        new FollowedProjects.Followed(
                                "demo", repo.toString(), "", "", "", "", "", "", true));

        new Reconcile(context).checked(followed);
        if (kind.equals("linked")) {
            assertThat(elsewhere.resolve(".git/FETCH_HEAD"))
                    .as("the check fetched nothing into what the link points at")
                    .doesNotExist();
        }
        final var result = new Reconcile(context).run(followed);

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.isSymbolicLink(clone)).as("the link itself is replaced").isFalse();
        assertThat(clone.resolve("project.yml")).exists();
        if (kind.equals("linked")) {
            assertThat(elsewhere.resolve(".git/FETCH_HEAD"))
                    .as("nor did the follow fetch into it")
                    .doesNotExist();
        }
    }

    @Test
    void a_follow_at_its_commit_replaces_a_bare_live_clone(@TempDir final Path dir)
            throws Exception {
        // At the recorded commit, a bare clone answered HEAD and was kept, with no project.yml.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final var followed =
                following(
                        context,
                        new FollowedProjects.Followed(
                                "demo", repo.toString(), "", "", "", "", "", "", true));
        final var first = new Reconcile(context).run(followed);
        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        final Path clone = context.paths().projects().followedClone("demo");
        deleteTree(clone);
        git(dir, "clone", "-q", "--bare", repo.toString(), clone.toString());

        // Recorded at the commit the bare clone answers, so only atCommit can tell it apart.
        final var result =
                new Reconcile(context).run(following(context, Reconcile.after(followed, first)));

        assertThat(result.outcome())
                .as(result.detail())
                .isIn(Reconcile.Outcome.APPLIED, Reconcile.Outcome.UNCHANGED);
        assertThat(clone.resolve("project.yml")).as("a work tree again").exists();
    }

    @Test
    void a_shallow_source_is_named_before_the_shallow_clone_it_makes(@TempDir final Path dir)
            throws Exception {
        // Both shallow: the person must fix the source, so the refusal names it, not the copy.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        commit(repo, null, "demo", "second configuration");
        final Path shallow = dir.resolve("shallow");
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), shallow.toString());
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone.getParent());
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), clone.toString());

        final var result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                shallow.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.detail()).contains(shallow.toString(), "shallow repository");
    }

    @Test
    void an_empty_live_clone_directory_is_made_again_and_followed(@TempDir final Path dir)
            throws Exception {
        // An empty directory was copied as a clone, git init skipped and the fetch ran in no
        // repository - or in one above it.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final Path clone = context.paths().projects().followedClone("demo");
        Files.createDirectories(clone);

        final var result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                repo.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(clone.resolve("project.yml")).exists();
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (final Path each : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    private String gitOutput(final Path repo, final String... args) {
        final var all = new java.util.ArrayList<>(java.util.List.of("git", "-C", repo.toString()));
        all.addAll(java.util.List.of(args));
        final var result = runner.run(Command.of(all));
        assertThat(result.successful()).as(result.standardError()).isTrue();
        return result.standardOutput().strip();
    }

    @Test
    void a_shallow_file_source_is_refused_by_its_url(@TempDir final Path dir) throws Exception {
        // A shallow file transport must not hide authority or misidentify the unreadable source.
        final PoreloContext context = context(dir);
        final Path operator = key(dir, "operator");
        final Path repo = published(dir, operator, "demo");
        commit(repo, operator, "demo", "second configuration");
        final Path shallow = dir.resolve("shallow");
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), shallow.toString());
        final String source = shallow.toUri().toString();

        final var answer =
                new FollowSignedBy(context)
                        .follow("demo", source, typeAndKey(operator), false, false);

        assertThat(answer.result().outcome())
                .as(answer.result().detail())
                .isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(answer.result().detail())
                .as("the refusal names the actual source")
                .contains(shallow.toString(), "shallow repository");
        assertThat(context.paths().projects().followed().resolve("demo.json"))
                .as("truncated history establishes no follow")
                .doesNotExist();
    }

    @Test
    void a_localhost_file_url_is_handled_as_a_repository(@TempDir final Path dir) throws Exception {
        // A valid local file authority must not escape as an unclassified failure.
        final PoreloContext context = context(dir);
        final Path operator = key(dir, "operator");
        final Path repo = published(dir, operator, "demo");
        final String source = repo.toUri().toString().replace("file:///", "file://localhost/");
        final var answer =
                new FollowSignedBy(context)
                        .follow("demo", source, typeAndKey(operator), false, false);
        assertThat(answer.result().outcome())
                .as(answer.result().detail())
                .isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(answer.result().detail())
                .as("an unsupported authority names the URL")
                .contains(source);
        assertThat(answer.recorded()).as("an unreadable URL establishes no follow").isFalse();
    }

    @Test
    void a_rewrite_tip_with_a_named_but_missing_parent_is_unreadable(@TempDir final Path dir)
            throws Exception {
        // Missing ancestry must not pass simply because a rewrite uses a separate declaration
        // baseline.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        final String parent =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        commit(repo, old, "demo", "replacement");
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        final Path incomplete = dir.resolve("incomplete");
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), incomplete.toString());
        Files.delete(incomplete.resolve(".git/shallow"));
        pin(context, "demo", old);

        final var handed =
                new SignerHandover(runner, context.paths().projects().configurationSigners())
                        .walk(
                                incomplete,
                                "demo",
                                "",
                                tip,
                                new SignerHandover.Rewrite(repo, parent));

        assertThat(handed.refused())
                .as("the commit object still names a missing parent")
                .isNotNull();
        assertThat(handed.refused().outcome())
                .as(handed.refused().detail())
                .isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
        assertThat(handed.refused().detail())
                .as("the missing parent and its repository are named")
                .contains(parent, incomplete.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"missing", "background", "unborn", "wrong-head"})
    void a_successful_unchanged_follow_recovers_its_missing_clone(
            final String recovery, @TempDir final Path dir) throws Exception {
        // Reporting success must restore the configuration that the machine needs to read.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        final var first = follow.follow("demo", repo.toString(), typeAndKey(old), false, false);
        final Path clone = context.paths().projects().followedClone("demo");
        FollowedProjects.forget(clone);
        if (recovery.equals("background")) {
            final var followed =
                    new FollowedProjects(context.paths().projects().followed()).require("demo");
            new Reconcile(context).run(followed);
            assertThat(clone.resolve("project.yml"))
                    .as("background recovery restores the configuration before reporting success")
                    .hasContent(Files.readString(repo.resolve("project.yml")));
        } else if (recovery.equals("unborn") || recovery.equals("wrong-head")) {
            git(dir, "init", "-q", clone.toString());
            if (recovery.equals("wrong-head")) {
                git(
                        clone,
                        "-c",
                        "user.name=Person",
                        "-c",
                        "user.email=person@example.org",
                        "commit",
                        "-q",
                        "--allow-empty",
                        "--no-gpg-sign",
                        "-m",
                        "unrelated HEAD");
            }
        }

        final var answer = follow.follow("demo", repo.toString(), null, false, false);

        assertThat(answer.result().outcome())
                .as(answer.result().detail())
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(clone.resolve("project.yml"))
                .as("the prepared recovery becomes live")
                .hasContent(Files.readString(repo.resolve("project.yml")));
        assertThat(
                        runner.run(Command.of("git", "-C", clone.toString(), "rev-parse", "HEAD"))
                                .standardOutput()
                                .strip())
                .as("the recovered clone holds the recorded commit")
                .isEqualTo(first.result().commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_shallow_source_cannot_hide_a_retired_parent(
            final boolean fetchFailed, @TempDir final Path dir) throws Exception {
        // A truncated history must not turn a retired signer into a first-follow anchor.
        final PoreloContext base = context(dir);
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            final var result = runner.run(command);
                            return fetchFailed && command.arguments().contains("fetch")
                                    ? new org.fuin.porelo.core.process.CommandResult(
                                            command,
                                            1,
                                            result.standardOutput(),
                                            "fetch failed after receiving objects")
                                    : result;
                        },
                        base.paths(),
                        arguments -> 0);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "retirement", successor);
        commitNaming(repo, old, "retired signature", successor);
        final Path shallow = dir.resolve("shallow");
        git(dir, "clone", "-q", "--depth=1", repo.toUri().toString(), shallow.toString());

        final Reconcile.Result result =
                new FollowSignedBy(context)
                        .follow("demo", shallow.toString(), typeAndKey(old), false, false)
                        .result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.detail())
                .as("the incomplete repository is named")
                .contains(shallow.toString(), "shallow repository");
        assertThat(context.paths().projects().followed().resolve("demo.json"))
                .as("incomplete history establishes no follow")
                .doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "signed, key",
        "unsigned, key",
        "document, key",
        "signed, none"
    })
    void switching_to_verified_checks_the_tip_as_a_first_follow(
            final String tipKind, final String anchor, @TempDir final Path dir) throws Exception {
        // An unverified commit must never become a verified baseline without a signature check.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        if (tipKind.equals("unsigned")) {
            commit(repo, null, "demo", "unchecked configuration");
        }
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), null, true, false);
        if (tipKind.equals("document")) {
            Files.writeString(repo.resolve("README.md"), "unsigned document\n");
            git(repo, "add", "README.md");
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "document");
        }
        final boolean permitted = tipKind.equals("signed") && anchor.equals("key");
        final byte[] before =
                Files.readAllBytes(context.paths().projects().followed().resolve("demo.json"));

        final FollowSignedBy.Answer answer =
                follow.follow(
                        "demo",
                        repo.toString(),
                        anchor.equals("key") ? typeAndKey(old) : null,
                        false,
                        false);

        assertThat(answer.result().outcome())
                .as(answer.result().detail())
                .isEqualTo(
                        permitted
                                ? Reconcile.Outcome.APPLIED
                                : anchor.equals("none")
                                        ? Reconcile.Outcome.NO_ANCHOR
                                        : Reconcile.Outcome.NOT_SIGNED);
        if (permitted) {
            assertThat(answer.result().signer())
                    .as("the unchanged tip was actually checked")
                    .isEqualTo(SignedBy.fingerprintOf(typeAndKey(old)));
        } else {
            assertThat(
                            Files.readAllBytes(
                                    context.paths().projects().followed().resolve("demo.json")))
                    .as("a failed switch preserves the unverified record")
                    .isEqualTo(before);
        }
        assertThat(
                        follow.check(
                                        "demo",
                                        repo.toString(),
                                        anchor.equals("key") ? typeAndKey(old) : null,
                                        false)
                                .result()
                                .outcome())
                .as("preview applies the same first-follow rule")
                .isEqualTo(permitted ? Reconcile.Outcome.UNCHANGED : answer.result().outcome());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"rewrite", "url"})
    void a_refused_follow_without_a_named_key_preserves_every_durable_byte(
            final String refusal, @TempDir final Path dir) throws Exception {
        // A failed request must not change refs, fetched objects, shared pins or applied state.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(old), false, false);
        final Path signers = context.paths().projects().configurationSigners();
        Files.writeString(
                signers,
                "\nother " + typeAndKey(old) + "\n\n",
                java.nio.file.StandardOpenOption.APPEND);
        final Path record = context.paths().projects().followed().resolve("demo.json");
        final Path clone = context.paths().projects().followedClone("demo");
        final byte[] keysBefore = Files.readAllBytes(signers);
        final byte[] recordBefore = Files.readAllBytes(record);
        final byte[] eventsBefore = Files.readAllBytes(context.paths().eventLog());
        final var cloneBefore = bytesIn(clone);
        final String url;
        if (refusal.equals("rewrite")) {
            git(repo, "checkout", "--orphan", "replacement");
            commit(repo, null, "demo", "unsigned replacement");
            git(repo, "branch", "-M", "main");
            url = repo.toString();
            final var answer = follow.follow("demo", url, null, false, true);
            assertThat(answer.result().outcome())
                    .as(answer.result().detail())
                    .isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        } else {
            url = published(Files.createDirectories(dir.resolve("other")), old, "demo").toString();
            assertThat(catchThrowable(() -> follow.follow("demo", url, null, false, true)))
                    .as("a different URL is refused")
                    .isInstanceOf(FollowSignedBy.Refused.class);
        }
        final var softly = new org.assertj.core.api.SoftAssertions();
        softly.assertThat(bytesIn(clone)).as("clone refs and objects").isEqualTo(cloneBefore);
        softly.assertThat(Files.readAllBytes(signers))
                .as("shared signer bytes")
                .isEqualTo(keysBefore);
        softly.assertThat(Files.readAllBytes(record))
                .as("followed record bytes")
                .isEqualTo(recordBefore);
        softly.assertThat(Files.readAllBytes(context.paths().eventLog()))
                .as("event bytes")
                .isEqualTo(eventsBefore);
        softly.assertAll();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void an_unreadable_rewrite_baseline_cannot_be_treated_as_no_declaration(
            final boolean missingObject, @TempDir final Path dir) throws Exception {
        // Losing the old declaration must not authorize a rewrite which removes that declaration.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "declaration", old);
        final FollowSignedBy follow = new FollowSignedBy(context);
        final String baseline =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false)
                        .result()
                        .commit();
        final Path clone = context.paths().projects().followedClone("demo");
        FollowedProjects.forget(clone);
        if (missingObject) {
            git(dir, "init", "-q", clone.toString());
        }
        git(repo, "checkout", "--orphan", "replacement");
        commit(repo, old, "demo", "without declaration");
        git(repo, "branch", "-M", "main");

        final var result =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, true).result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.detail()).as("the lost baseline is named").contains(baseline);
        assertThat(
                        new FollowedProjects(context.paths().projects().followed())
                                .require("demo")
                                .commit())
                .as("the recorded baseline survives")
                .isEqualTo(baseline);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"protected", "document"})
    void an_unmatched_fingerprint_refuses_before_publication(
            final String change, @TempDir final Path dir) throws Exception {
        // A named fingerprint must authorize a checked protected commit before it can be pinned.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path wrong = key(dir, "wrong");
        final Path repo = published(dir, old, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(old), false, false);
        if (change.equals("protected")) {
            commit(repo, old, "demo", "protected update");
        } else {
            Files.writeString(repo.resolve("README.md"), "document\n");
            git(repo, "add", "README.md");
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "document");
        }
        final String fingerprint = SignedBy.fingerprintOf(typeAndKey(wrong));
        final byte[] before =
                Files.readAllBytes(context.paths().projects().followed().resolve("demo.json"));

        final Throwable refused =
                catchThrowable(
                        () -> follow.follow("demo", repo.toString(), fingerprint, false, false));

        assertThat(refused)
                .as("the unmatched request is refused")
                .isInstanceOf(FollowSignedBy.Refused.class)
                .hasMessageContaining(fingerprint);
        assertThat(Files.readAllBytes(context.paths().projects().followed().resolve("demo.json")))
                .as("no unmatched request is published")
                .isEqualTo(before);
    }

    @Test
    void a_fingerprint_can_name_an_earlier_protected_commit_before_a_handover(
            @TempDir final Path dir) throws Exception {
        // A valid range must not be refused just because its last signer differs from its first.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old, successor);
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(old), false, false);
        commitNaming(repo, old, "handover", successor);
        commitNaming(repo, successor, "successor change", successor);
        final String fingerprint = SignedBy.fingerprintOf(typeAndKey(old));

        final var preview = follow.check("demo", repo.toString(), fingerprint, false);
        final var applied = follow.follow("demo", repo.toString(), fingerprint, false, false);

        assertThat(preview.result().outcome())
                .as(preview.result().detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(applied.result().commit())
                .as("preview and follow check the same range")
                .isEqualTo(preview.result().commit());
        assertThat(applied.result().outcome())
                .as(applied.result().detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void a_refused_multi_key_follow_claims_no_pinned_keys(@TempDir final Path dir)
            throws Exception {
        // A refusal must not tell clients that trust was established.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path wrong = key(dir, "wrong");
        final Path repo = published(dir, old, "demo");
        commit(repo, null, "demo", "unsigned");

        final var answer =
                new FollowSignedBy(context)
                        .follow(
                                "demo",
                                repo.toString(),
                                java.util.List.of(typeAndKey(old), typeAndKey(wrong)),
                                false);

        assertThat(answer.result().outcome())
                .as(answer.result().detail())
                .isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(answer.pinned()).as("no single key was pinned").isEmpty();
        assertThat(answer.signers()).as("no collection of keys was pinned").isEmpty();
        final var preview =
                new FollowSignedBy(context)
                        .check(
                                "demo",
                                repo.toString(),
                                java.util.List.of(typeAndKey(old), typeAndKey(wrong)));
        assertThat(preview.signers()).as("a refused preview would pin no keys").isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_first_declaration_retires_its_signer_even_without_a_previous_list(
            final boolean parentless, @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        if (parentless) {
            git(repo, "checkout", "--orphan", "root");
        }
        commitNaming(repo, old, "first declaration", successor);
        if (parentless) {
            git(repo, "branch", "-M", "main");
        }
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        commitNaming(repo, old, "discarded signer", successor);

        final Reconcile.Result refused =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the handover stays applied").isEqualTo(first.commit());
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .as("only the declaration remains active")
                .containsExactly(typeAndKey(successor));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "range, key", "range, file", "rewrite, key", "rewrite, file",
        "first, key", "first, file", "empty, key", "empty, file"
    })
    void a_declared_list_cannot_be_removed(
            final String mode, final String removed, @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path keep = key(dir, "keep");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "declared", old, keep);
        final FollowSignedBy follow = new FollowSignedBy(context);
        String inForce = "";
        if (!mode.equals("first")) {
            inForce =
                    follow.follow("demo", repo.toString(), typeAndKey(old), false, false)
                            .result()
                            .commit();
        }
        if (mode.equals("empty")) {
            Files.writeString(
                    repo.resolve("project.yml"),
                    Files.readString(repo.resolve("project.yml"))
                            .replaceAll("(?m)^  signers:\n(?:    - .*\n)*", "  signers: []\n"));
            git(repo, "add", "project.yml");
            git(repo, "commit", "-q", "-S", "-m", "empty declaration");
            final Reconcile.Result empty =
                    follow.follow("demo", repo.toString(), null, false, false).result();
            assertThat(empty.outcome()).as(empty.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
            inForce = empty.commit();
            // An explicit extra pin cannot authorize removing even an empty declaration.
            pin(context, "demo", old);
        }
        if (mode.equals("rewrite")) {
            git(repo, "checkout", "--orphan", "replacement");
        }
        if (removed.equals("key")) {
            commit(repo, old, "demo", "without signers");
        } else {
            git(repo, "rm", "-f", "project.yml");
            git(repo, "commit", "-q", "-S", "--allow-empty", "-m", "without project file");
        }
        if (mode.equals("rewrite")) {
            git(repo, "branch", "-M", "main");
        }
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();

        final Reconcile.Result refused =
                follow.follow(
                                "demo",
                                repo.toString(),
                                mode.equals("first") ? typeAndKey(old) : null,
                                false,
                                mode.equals("rewrite"))
                        .result();

        assertThat(refused.outcome())
                .as(refused.detail())
                .isNotIn(Reconcile.Outcome.APPLIED, Reconcile.Outcome.UNCHANGED);
        assertThat(refused.commit()).as("the previous commit stays in force").isEqualTo(inForce);
        assertThat(refused.refused()).as("the removal commit is named").isEqualTo(tip);
        assertThat(refused.refusedFile())
                .as("the protected file is named")
                .isEqualTo("project.yml");
        assertThat(refused.detail())
                .as("the removal is explained")
                .contains(tip, "project.yml", "signers", "removed");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_first_follow_refuses_a_tip_signed_by_a_previously_retired_key(
            final boolean merge, @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "handover", successor);
        if (merge) {
            git(repo, "checkout", "-b", "topic");
            Files.writeString(repo.resolve("README.md"), "topic\n");
            git(repo, "add", "README.md");
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "topic");
            git(repo, "checkout", "main");
            git(repo, "merge", "--no-ff", "-S", "topic", "-m", "merge by retired key");
        } else {
            commitNaming(repo, old, "after handover", successor);
        }
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();

        final Reconcile.Result refused =
                new FollowSignedBy(context)
                        .follow("demo", repo.toString(), typeAndKey(old), false, false)
                        .result();

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.refused()).as("the tip is named").isEqualTo(tip);
        assertThat(refused.signer())
                .as("the retired signer is named")
                .isEqualTo(SignedBy.fingerprintOf(typeAndKey(old)));
        assertThat(refused.commit()).as("no tip was applied").isEmpty();
    }

    @Test
    void a_wrong_rewrite_fingerprint_changes_nothing_and_keeps_the_old_baseline(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path wrong = key(dir, "wrong");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old, successor);
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();
        final Path clone = context.paths().projects().followedClone("demo");
        final Path signers = context.paths().projects().configurationSigners();
        final Path record = context.paths().projects().followed().resolve("demo.json");
        final Path events = context.paths().eventLog();
        final java.util.Map<String, String> cloneBefore = bytesIn(clone);
        final byte[] keysBefore = Files.readAllBytes(signers);
        final byte[] recordBefore = Files.readAllBytes(record);
        final byte[] eventsBefore = Files.readAllBytes(events);
        git(repo, "checkout", "--orphan", "replacement");
        commitNaming(repo, old, "replacement", successor);
        git(repo, "branch", "-M", "main");

        final Throwable refused =
                catchThrowable(
                        () ->
                                follow.follow(
                                        "demo",
                                        repo.toString(),
                                        SignedBy.fingerprintOf(typeAndKey(wrong)),
                                        false,
                                        true));

        assertThat(refused)
                .as("a different fingerprint refuses before publication")
                .isInstanceOf(FollowSignedBy.Refused.class);
        org.assertj.core.api.SoftAssertions softly = new org.assertj.core.api.SoftAssertions();
        softly.assertThat(bytesIn(clone))
                .as("every clone file stays byte-for-byte unchanged")
                .isEqualTo(cloneBefore);
        softly.assertThat(Files.readAllBytes(signers))
                .as("the shared keys stay byte-for-byte unchanged")
                .isEqualTo(keysBefore);
        softly.assertThat(Files.readAllBytes(record))
                .as("the followed record stays byte-for-byte unchanged")
                .isEqualTo(recordBefore);
        softly.assertThat(Files.readAllBytes(events))
                .as("the event log stays byte-for-byte unchanged")
                .isEqualTo(eventsBefore);
        softly.assertAll();
        git(repo, "reset", "--hard", first.commit());
        commitNaming(repo, old, "valid descendant of original", old, successor);
        final Reconcile.Result later =
                follow.follow("demo", repo.toString(), null, false, false).result();
        assertThat(later.outcome())
                .as("the original authority and baseline survive: " + later.detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1, successor", "2, removal"})
    void publication_uses_the_same_commit_that_passed_preparation(
            final int fetchNumber, final String replacement, @TempDir final Path dir)
            throws Exception {
        final PoreloContext base = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old, successor);
        new FollowSignedBy(base).follow("demo", repo.toString(), typeAndKey(old), false, false);
        git(repo, "checkout", "--orphan", "replacement");
        commitNaming(repo, old, "checked replacement", successor);
        git(repo, "branch", "-M", "main");
        final String checked =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        final java.util.concurrent.atomic.AtomicInteger fetches =
                new java.util.concurrent.atomic.AtomicInteger();
        final PoreloContext changing =
                new PoreloContext(
                        command -> {
                            final var result = runner.run(command);
                            if (command.arguments().contains("fetch")
                                    && result.successful()
                                    && fetches.incrementAndGet() == fetchNumber) {
                                try {
                                    if (replacement.equals("successor")) {
                                        commitNaming(
                                                repo, successor, "different signer", successor);
                                    } else {
                                        commit(
                                                repo,
                                                old,
                                                "demo",
                                                "declaration removed after check");
                                    }
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            }
                            return result;
                        },
                        base.paths(),
                        arguments -> 0);

        final Reconcile.Result result =
                new FollowSignedBy(changing)
                        .follow(
                                "demo",
                                repo.toString(),
                                SignedBy.fingerprintOf(typeAndKey(old)),
                                false,
                                true)
                        .result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit())
                .as("publication is bound to the checked commit")
                .isEqualTo(checked);
        assertThat(fetches.get()).as("publication never fetches a second version").isEqualTo(1);
    }

    @Test
    void a_refused_repository_change_preserves_the_shared_signers_bytes(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        new FollowSignedBy(context).follow("demo", repo.toString(), typeAndKey(old), false, false);
        final Path other = published(Files.createDirectories(dir.resolve("other")), old, "demo");
        final Path signers = context.paths().projects().configurationSigners();
        Files.writeString(
                signers,
                "other " + typeAndKey(old) + "\n",
                java.nio.file.StandardOpenOption.APPEND);
        final byte[] before = Files.readAllBytes(signers);

        final Throwable refused =
                catchThrowable(
                        () ->
                                new FollowSignedBy(context)
                                        .follow(
                                                "demo",
                                                other.toString(),
                                                SignedBy.fingerprintOf(typeAndKey(old)),
                                                false,
                                                true));

        assertThat(refused)
                .as("changing a followed repository is refused")
                .isInstanceOf(FollowSignedBy.Refused.class);
        assertThat(Files.readAllBytes(signers))
                .as("refusal preserves key ordering and every byte")
                .isEqualTo(before);
    }

    @Test
    void a_first_follow_at_an_unchanged_declaration_trusts_its_other_declared_keys(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "declaration", old, successor);
        commitNaming(repo, old, "unchanged declaration", old, successor);
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        commitNaming(repo, successor, "successor change", old, successor);

        final Reconcile.Result result =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void prepared_publication_preserves_untracked_files_and_reports_replaced_edits(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path repo = published(dir, old, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(old), false, false);
        final Path clone = context.paths().projects().followedClone("demo");
        Files.writeString(clone.resolve("local-notes.txt"), "local notes\n");
        Files.writeString(clone.resolve("project.yml"), "local changes\n");
        commit(repo, old, "demo", "upstream change");

        final Reconcile.Result result =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(clone.resolve("local-notes.txt"))
                .as("untracked files survive a named-key follow")
                .hasContent("local notes\n");
        assertThat(result.detail())
                .as("tracked local changes are reported")
                .contains("local", "project.yml");
    }

    private static java.util.Map<String, String> bytesIn(final Path directory) throws IOException {
        final java.util.Map<String, String> bytes = new java.util.TreeMap<>();
        try (final var paths = Files.walk(directory)) {
            for (final Path file : paths.filter(Files::isRegularFile).toList()) {
                bytes.put(
                        directory.relativize(file).toString(),
                        java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return bytes;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "false, none",
        "true, none",
        "true, key",
        "true, fingerprint",
        "true, several"
    })
    void an_accepted_rewrite_retires_keys_missing_from_the_changed_declaration(
            final boolean outOfBand, final String repin, @TempDir final Path dir) throws Exception {
        // Accepting new history must not let discarded keys authorize later protected changes.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path extra = key(dir, "extra");
        final Path repo = published(dir, old, "demo");
        commitNaming(
                repo, old, "baseline", outOfBand ? new Path[] {old} : new Path[] {old, successor});
        commitNaming(
                repo,
                old,
                "unchanged baseline",
                outOfBand ? new Path[] {old} : new Path[] {old, successor});
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow(
                                "demo",
                                repo.toString(),
                                outOfBand
                                        ? java.util.List.of(typeAndKey(old), typeAndKey(extra))
                                        : java.util.List.of(typeAndKey(old)),
                                false)
                        .result();
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        git(repo, "checkout", "--orphan", "replacement");
        commitNaming(repo, outOfBand ? extra : old, "new declaration", successor);
        git(repo, "branch", "-M", "main");

        final Reconcile.Result rewritten =
                switch (repin) {
                    case "key" ->
                            follow.follow("demo", repo.toString(), typeAndKey(extra), false, true)
                                    .result();
                    case "fingerprint" ->
                            follow.follow(
                                            "demo",
                                            repo.toString(),
                                            SignedBy.fingerprintOf(typeAndKey(extra)),
                                            false,
                                            true)
                                    .result();
                    case "several" ->
                            follow.follow(
                                            "demo",
                                            repo.toString(),
                                            java.util.List.of(
                                                    typeAndKey(extra), typeAndKey(successor)),
                                            true)
                                    .result();
                    default -> follow.follow("demo", repo.toString(), null, false, true).result();
                };

        assertThat(rewritten.outcome()).as(rewritten.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        for (final Path retired :
                outOfBand ? java.util.List.of(extra, old) : java.util.List.of(old)) {
            Files.writeString(repo.resolve("machine-signers"), "# retired key change\n");
            git(repo, "add", "machine-signers");
            git(repo, "config", "user.signingkey", retired.toString());
            git(repo, "commit", "-q", "-S", "-m", "retired key change");
            final Reconcile.Result refused =
                    follow.follow("demo", repo.toString(), null, false, false).result();
            assertThat(refused.outcome())
                    .as("the retired key cannot change protected files: " + refused.detail())
                    .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
            assertThat(refused.commit())
                    .as("the accepted rewrite stays in force")
                    .isEqualTo(rewritten.commit());
            git(repo, "reset", "--hard", rewritten.commit());
        }
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .as("the changed declaration retires both declared and out-of-band keys")
                .containsExactly(typeAndKey(successor));
        commitNaming(repo, successor, "successor change", successor);
        final Reconcile.Result advanced =
                follow.follow("demo", repo.toString(), null, false, false).result();
        assertThat(advanced.outcome())
                .as("the successor can still change configuration: " + advanced.detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void a_first_follow_at_a_handover_retires_the_anchor_that_signed_it(@TempDir final Path dir)
            throws Exception {
        // An anchor authorizes its handover without retaining authority after it leaves the
        // declaration.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old);
        commitNaming(repo, old, "handover", successor);
        final FollowSignedBy follow = new FollowSignedBy(context);

        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();

        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        commitNaming(repo, old, "retired key change", successor);
        final Reconcile.Result refused =
                follow.follow("demo", repo.toString(), null, false, false).result();
        assertThat(refused.outcome())
                .as("the first follow completed the handover: " + refused.detail())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the handover remains applied").isEqualTo(first.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"none", "key", "fingerprint", "several"})
    void a_dry_run_honors_an_accepted_rewrite(final String anchor, @TempDir final Path dir)
            throws Exception {
        // A preview must not refuse a recovery that the same follow would apply.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path extra = key(dir, "extra");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(key), false, false);
        git(repo, "checkout", "--orphan", "replacement");
        commit(repo, key, "demo", "replacement history");
        git(repo, "branch", "-M", "main");
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed before = projects.require("demo");
        final String pins = Files.readString(context.paths().projects().configurationSigners());
        final String events = Files.readString(context.paths().eventLog());
        final java.util.List<String> args =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "project",
                                "follow",
                                "demo",
                                repo.toString(),
                                "--accept-rewrite",
                                "--dry-run"));
        switch (anchor) {
            case "key" -> args.addAll(java.util.List.of("--signed-by", typeAndKey(key)));
            case "fingerprint" ->
                    args.addAll(
                            java.util.List.of(
                                    "--signed-by", SignedBy.fingerprintOf(typeAndKey(key))));
            case "several" ->
                    args.addAll(
                            java.util.List.of(
                                    "--signed-by",
                                    typeAndKey(key),
                                    "--signed-by",
                                    typeAndKey(extra)));
            default -> {}
        }
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        final java.io.StringWriter output = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(output));
        cli.setErr(new java.io.PrintWriter(output));

        final int code = cli.execute(args.toArray(String[]::new));

        assertThat(code).as("rewrite preview: " + output).isZero();
        assertThat(projects.require("demo")).as("a preview records no rewrite").isEqualTo(before);
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .as("a preview pins nothing")
                .isEqualTo(pins);
        assertThat(Files.readString(context.paths().eventLog()))
                .as("a preview applies nothing to log")
                .isEqualTo(events);
        assertThat(
                        new ConfigurationGate(
                                        runner, context.paths().projects().configurationSigners())
                                .verify(context.paths().projects().followedClone("demo"), "HEAD")
                                .commit())
                .as("the durable clone stays at the old commit")
                .isEqualTo(before.commit());
        final Reconcile.Result actual =
                anchor.equals("several")
                        ? follow.follow(
                                        "demo",
                                        repo.toString(),
                                        java.util.List.of(typeAndKey(key), typeAndKey(extra)),
                                        true)
                                .result()
                        : follow.follow("demo", repo.toString(), typeAndKey(key), false, true)
                                .result();
        assertThat(actual.outcome()).as(actual.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void initial_and_rewritten_follows_keep_extra_pins_until_a_declared_handover(
            final boolean rewrite, @TempDir final Path dir) throws Exception {
        // Extra anchors must authorize protected edits until a later declaration retires them.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path extra = key(dir, "extra");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        if (rewrite) {
            commitNaming(repo, key, "existing declaration", key);
            commitNaming(repo, key, "unchanged existing declaration", key);
            follow.follow(
                    "demo",
                    repo.toString(),
                    java.util.List.of(typeAndKey(key), typeAndKey(extra)),
                    false);
            git(repo, "checkout", "--orphan", "replacement");
        }
        commitNaming(repo, key, "declared signers", key);
        if (rewrite) {
            git(repo, "branch", "-M", "main");
        } else {
            commitNaming(repo, key, "unchanged declaration", key);
        }
        final Reconcile.Result first =
                rewrite
                        ? follow.follow("demo", repo.toString(), null, false, true).result()
                        : follow.follow(
                                        "demo",
                                        repo.toString(),
                                        java.util.List.of(typeAndKey(key), typeAndKey(extra)),
                                        rewrite)
                                .result();
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        Files.writeString(repo.resolve("machine-signers"), "# change\n");
        git(repo, "add", "machine-signers");
        git(repo, "config", "user.signingkey", extra.toString());
        git(repo, "commit", "-q", "-S", "-m", "message key change");

        final Reconcile.Result changed =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(changed.outcome())
                .as("an extra pin still authorizes protected changes: " + changed.detail())
                .isEqualTo(Reconcile.Outcome.APPLIED);
        commitNaming(repo, key, "handover", key, extra);
        follow.follow("demo", repo.toString(), null, false, false);
        commitNaming(repo, key, "retire extra", key);
        final Reconcile.Result retired =
                follow.follow("demo", repo.toString(), null, false, false).result();
        assertThat(retired.outcome()).as(retired.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        Files.writeString(repo.resolve("machine-signers"), "# another change\n");
        git(repo, "add", "machine-signers");
        git(repo, "config", "user.signingkey", extra.toString());
        git(repo, "commit", "-q", "-S", "-m", "retired key change");
        final Reconcile.Result refused =
                follow.follow("demo", repo.toString(), null, false, false).result();
        assertThat(refused.outcome())
                .as("a declared retirement still removes authority")
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit())
                .as("the retirement stays in force")
                .isEqualTo(retired.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "@0 +0000",
                "@1 +0000",
                "@253402300800 +0000",
                "2024-05-01T00:00:00Z",
                "2025-06-01T00:00:00Z"
            })
    void an_invalid_retirement_window_cannot_poison_the_accounts_signers(
            final String time, @TempDir final Path dir) throws Exception {
        // One handover must not make the shared trust file unreadable for every project.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path other = key(dir, "other");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        commitNaming(repo, key, "baseline", key, other);
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        final Path signers = context.paths().projects().configurationSigners();
        Files.writeString(
                signers,
                "demo "
                        + (time.startsWith("@") ? "" : "valid-after=\"20250601000000Z\" ")
                        + typeAndKey(key)
                        + "\ndemo "
                        + typeAndKey(other)
                        + "\nother "
                        + typeAndKey(other)
                        + "\n");
        final String before = Files.readString(signers);
        commitNaming(repo, other, "retirement", other);
        date(repo, time);
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome())
                .as(result.detail())
                .isEqualTo(
                        time.equals("@253402300800 +0000")
                                ? Reconcile.Outcome.UNKNOWN_KEY
                                : Reconcile.Outcome.UNREADABLE);
        assertThat(result.commit())
                .as("the unsafe handover is not applied")
                .isEqualTo(first.commit());
        assertThat(result.refused()).as("the unsafe handover is named").isEqualTo(tip);
        if (!time.equals("@253402300800 +0000")) {
            assertThat(result.detail())
                    .as("the retirement constraint is explained")
                    .contains("valid-before");
        }
        assertThat(Files.readString(signers))
                .as("all projects keep their previous trust file")
                .isEqualTo(before);
        assertThat(AllowedSigner.read(runner, signers))
                .as("the trust file still passes OpenSSH validation")
                .hasSize(3);
    }

    @Test
    void a_handover_time_beyond_four_digit_years_is_refused_before_formatting(
            @TempDir final Path dir) throws Exception {
        // An unrepresentable timestamp must never reach the account's shared allowed-signers file.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old, successor);
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();
        commitNaming(repo, old, "handover", successor);
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        final Path signers = context.paths().projects().configurationSigners();
        final String before = Files.readString(signers);
        final CommandRunner tooLate =
                command ->
                        command.arguments().contains("--format=%ct")
                                ? new org.fuin.porelo.core.process.CommandResult(
                                        command, 0, "253402300800\n", "")
                                : runner.run(command);

        final Reconcile.Result result =
                new Reconcile(new PoreloContext(tooLate, context.paths(), args -> 0))
                        .run(
                                new FollowedProjects(context.paths().projects().followed())
                                        .require("demo"));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.refused()).as("the out-of-range handover is named").isEqualTo(tip);
        assertThat(result.detail())
                .as("the handover time is refused before making an invalid OpenSSH date")
                .contains("through 9999-12-31");
        assertThat(result.commit())
                .as("the previous configuration stays applied")
                .isEqualTo(first.commit());
        assertThat(Files.readString(signers))
                .as("every project's trust file stays intact")
                .isEqualTo(before);
    }

    @Test
    void an_invalid_retirement_inside_a_range_names_the_handover_and_applies_nothing(
            @TempDir final Path dir) throws Exception {
        // A later valid commit cannot hide the bad retirement or move the refusal to the range's
        // tip.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path other = key(dir, "other");
        final Path repo = published(dir, old, "demo");
        commitNaming(repo, old, "baseline", old, other);
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(old), false, false).result();
        final Path signers = context.paths().projects().configurationSigners();
        Files.writeString(
                signers,
                "demo valid-after=\"20250601000000Z\" "
                        + typeAndKey(old)
                        + "\ndemo "
                        + typeAndKey(other)
                        + "\n");
        final String before = Files.readString(signers);
        commitNaming(repo, other, "invalid retirement", other);
        date(repo, "2024-05-01T00:00:00Z");
        final String bad =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        Files.writeString(repo.resolve("notes.md"), "a later document\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "-m", "later document");

        final Reconcile.Result result =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.refused())
                .as("the intermediate handover is refused, not the tip")
                .isEqualTo(bad);
        assertThat(result.detail())
                .as("the invalid retirement window is explained")
                .contains("valid-before");
        assertThat(result.commit())
                .as("the whole fetched range is refused")
                .isEqualTo(first.commit());
        assertThat(Files.readString(signers))
                .as("the shared signers file is unchanged")
                .isEqualTo(before);
        assertThat(
                        runner.run(
                                        Command.of(
                                                "git",
                                                "-C",
                                                context.paths()
                                                        .projects()
                                                        .followedClone("demo")
                                                        .toString(),
                                                "rev-parse",
                                                "HEAD"))
                                .standardOutput()
                                .strip())
                .as("no commit from the failed range is checked out")
                .isEqualTo(first.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"19691231235959Z", "19700101000000Z", "+100000101000000Z"})
    void pin_validates_the_staged_signers_before_replacing_the_shared_file(
            final String beforeTime, @TempDir final Path dir) throws Exception {
        // No caller can publish a retirement line that OpenSSH refuses.
        final Path key = key(dir, "operator");
        final Path signers = dir.resolve("project-signers");
        final String before = "demo " + typeAndKey(key) + "\n";
        Files.writeString(signers, before);
        final SignerHandover.Handed handed =
                new SignerHandover.Handed(
                        java.util.List.of(),
                        true,
                        null,
                        null,
                        "",
                        java.util.List.of(new SignerHandover.Retired(typeAndKey(key), beforeTime)));

        final Throwable failure =
                catchThrowable(() -> new SignerHandover(runner, signers).pin("demo", handed));

        assertThat(failure)
                .as("OpenSSH refuses the staged retirement")
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(signers))
                .as("the shared file is never replaced by invalid content")
                .isEqualTo(before);
        try (var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("a refused stage leaves no temporary signers file")
                    .noneMatch(name -> name.startsWith(".project-signers-"));
        }
    }

    @Test
    void a_staged_signers_validation_failure_refuses_the_commit_before_checkout(
            @TempDir final Path dir) throws Exception {
        // Failure to validate the complete staged file must preserve the account's applied state.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path successor = key(dir, "successor");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        commitNaming(repo, key, "baseline", key, successor);
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        final Path signers = context.paths().projects().configurationSigners();
        final String before = Files.readString(signers);
        commitNaming(repo, key, "retirement", successor);
        final String tip =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        final PoreloContext failing =
                new PoreloContext(
                        command -> {
                            final java.util.List<String> args = command.arguments();
                            if (args.contains("match-principals")) {
                                try {
                                    final String staged =
                                            Files.readString(
                                                    Path.of(args.get(args.indexOf("-f") + 1)));
                                    if (staged.contains("valid-before=")
                                            && staged.lines().count() > 1) {
                                        return new org.fuin.porelo.core.process.CommandResult(
                                                command, 1, "", "staged signers validation failed");
                                    }
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            }
                            return runner.run(command);
                        },
                        context.paths(),
                        arguments -> 0);

        final Reconcile.Result result =
                new Reconcile(failing)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.refused()).as("the refused handover is named").isEqualTo(tip);
        assertThat(result.detail())
                .as("the validator failure is explained")
                .contains("staged signers validation failed");
        assertThat(Files.readString(signers))
                .as("the failed stage never replaces the shared file")
                .isEqualTo(before);
        assertThat(
                        runner.run(
                                        Command.of(
                                                "git",
                                                "-C",
                                                context.paths()
                                                        .projects()
                                                        .followedClone("demo")
                                                        .toString(),
                                                "rev-parse",
                                                "HEAD"))
                                .standardOutput()
                                .strip())
                .as("the failed stage never applies its project configuration")
                .isEqualTo(first.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aRefusedFirstFollowLeavesNeitherCloneNorLock(boolean fingerprint, @TempDir Path dir)
            throws Exception {
        // Refusing trust must not leave a clone and lock for later operations to mistake for a
        // follow.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");
        final String other = Files.readString(Path.of(key(dir, "other") + ".pub")).strip();
        final String named = fingerprint ? SignedBy.fingerprintOf(other) : other;

        final Throwable refusal =
                catchThrowable(
                        () ->
                                new FollowSignedBy(context)
                                        .follow("demo", repo.toString(), named, false, false));

        if (fingerprint) {
            assertThat(refusal)
                    .as("the other fingerprint is refused")
                    .isInstanceOf(FollowSignedBy.Refused.class);
        } else {
            assertThat(refusal).as("a whole key returns its refusal as an answer").isNull();
        }
        assertThat(new FollowedProjects(context.paths().projects().followed()).find("demo"))
                .as("a first refusal leaves no follow record")
                .isNull();
        assertThat(context.paths().projects().followedClone("demo"))
                .as("a first refusal leaves no clone")
                .doesNotExist();
        assertThat(context.paths().projects().followedCloneLock("demo"))
                .as("a first refusal leaves no clone lock")
                .doesNotExist();
    }

    @Test
    void aReconcileAfterUnfollowCreatesNeitherCloneNorLock(@TempDir final Path dir)
            throws IOException {
        // A stale watcher record must not resurrect a project the operator removed.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed stale = projects.follow("demo", repo.toString());
        projects.unfollow("demo");

        final Reconcile.Result result = new Reconcile(context).run(stale);

        assertThat(context.paths().projects().followedClone("demo"))
                .as("a stale reconcile does not recreate the clone")
                .doesNotExist();
        assertThat(context.paths().projects().followedCloneLock("demo"))
                .as("a stale reconcile leaves no clone lock")
                .doesNotExist();
        assertThat(result.outcome().name())
                .as("the result says the project is no longer followed")
                .isEqualTo("NOT_FOLLOWED");
    }

    @Test
    void aQueuedReconcileReadsTheFollowRecordAgainUnderTheLock(@TempDir final Path dir)
            throws Exception {
        // A watcher can read its record before unfollowing and acquire the clone lock afterwards.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed stale = projects.follow("demo", repo.toString());
        final Path lock = context.paths().projects().followedCloneLock("demo");
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var pending =
                    FileLocks.holding(
                            lock,
                            () -> {
                                final var started = new CountDownLatch(1);
                                final var queued =
                                        executor.submit(
                                                () -> {
                                                    started.countDown();
                                                    return new Reconcile(context).run(stale);
                                                });
                                try {
                                    assertThat(started.await(5, TimeUnit.SECONDS))
                                            .as("the stale reconcile has started")
                                            .isTrue();
                                    assertThat(
                                                    catchThrowable(
                                                            () ->
                                                                    queued.get(
                                                                            300,
                                                                            TimeUnit.MILLISECONDS)))
                                            .as("reconciliation waits for the clone lock")
                                            .isInstanceOf(TimeoutException.class);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(
                                            "interrupted while queuing reconciliation", ex);
                                }
                                projects.unfollow("demo");
                                return queued;
                            });

            final Reconcile.Result result = pending.get(5, TimeUnit.SECONDS);
            assertThat(context.paths().projects().followedClone("demo"))
                    .as("a queued reconcile does not recreate an unfollowed clone")
                    .doesNotExist();
            assertThat(lock).as("a queued reconcile leaves no clone lock").doesNotExist();
            assertThat(result.outcome().name())
                    .as("the queued reconcile reports that following stopped")
                    .isEqualTo("NOT_FOLLOWED");
        }
    }

    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            java.util.Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1",
                                                    "TZ",
                                                    "UTC")));

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    // Its own, or the real /run/user, which a Porelo task has not.
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void git(final Path where, final String... arguments) {
        final java.util.List<String> all =
                new java.util.ArrayList<>(java.util.List.of("git", "-C", where.toString()));
        all.addAll(java.util.List.of(arguments));
        final var result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
    }

    private Path key(final Path dir, final String name) {
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
                        dir.resolve(name).toString()));
        return dir.resolve(name);
    }

    private void pin(final PoreloContext context, final String principal, final Path key)
            throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        Files.createDirectories(context.paths().projects().configurationSigners().getParent());
        Files.writeString(
                context.paths().projects().configurationSigners(),
                principal + " " + fields[0] + " " + fields[1] + "\n");
    }

    /** A project repository somebody else publishes, with one commit. */
    private Path published(final Path dir, final Path signingKey, final String project)
            throws IOException {
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        commit(repo, signingKey, project);
        return repo;
    }

    private void commit(final Path repo, final Path signingKey, final String project)
            throws IOException {
        commit(repo, signingKey, project, "Followed from a repository");
    }

    /**
     * Commits a project file whose description says which version of it this is.
     *
     * <p>The NAME cannot be used to tell two commits apart any more: a file that calls itself
     * something other than the name it is followed under is refused, because the machine would then
     * hold a project whose containers and mirrors are named after the file and a follow record that
     * resolves to none of them.
     */
    private void commit(
            final Path repo, final Path signingKey, final String project, final String description)
            throws IOException {
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: %s
                  description: %s
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                """
                        .formatted(project, description));
        git(repo, "add", "project.yml");
        if (signingKey == null) {
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            git(repo, "config", "gpg.format", "ssh");
            git(repo, "config", "user.signingkey", signingKey.toString());
            git(repo, "commit", "-q", "-S", "-m", "configuration");
        }
    }

    /** Commits a project file that names whose commits change it, signed by one key. */
    private void commitNaming(
            final Path repo, final Path signingKey, final String description, final Path... signers)
            throws IOException {
        final StringBuilder named = new StringBuilder();
        for (final Path signer : signers) {
            named.append("    - \"")
                    .append(Files.readString(Path.of(signer + ".pub")).strip())
                    .append("\"\n");
        }
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  description: %s
                  security_class: guarded
                  signers:
                %simage:
                  base_image: ubuntu:24.04
                """
                        .formatted(description, named));
        git(repo, "add", "project.yml");
        git(repo, "config", "user.signingkey", signingKey.toString());
        git(repo, "commit", "-q", "-S", "-m", description);
    }

    private void date(final Path repo, final String time) {
        final var dated =
                runner.run(
                        Command.of(
                                        "git",
                                        "-C",
                                        repo.toString(),
                                        "commit",
                                        "-q",
                                        "-S",
                                        "--amend",
                                        "--no-edit",
                                        "--date=" + time)
                                .withEnvironment(java.util.Map.of("GIT_COMMITTER_DATE", time)));
        assertThat(dated.successful()).as(dated.standardError()).isTrue();
    }

    private static String typeAndKey(final Path key) throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }

    @Test
    void retired_keys_still_verify_history_but_do_not_authorize_new_commits(@TempDir final Path dir)
            throws IOException {
        // Retiring a key must preserve historical verification without keeping its authority.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path signers = context.paths().projects().configurationSigners();
        final String other = "other " + typeAndKey(key(dir, "other")) + "\n";
        Files.writeString(signers, Files.readString(signers) + other);
        final Path repo = published(dir, old, "demo");
        date(repo, "2024-01-01T00:00:00Z");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commitNaming(repo, old, "retirement", successor);
        date(repo, "2025-01-01T00:00:00Z");
        final Reconcile.Result retired =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));
        assertThat(retired.outcome()).as(retired.detail()).isEqualTo(Reconcile.Outcome.APPLIED);

        final var history =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                repo.toString(),
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.allowedSignersFile=" + signers,
                                "verify-commit",
                                first.commit(),
                                retired.commit()));

        assertThat(history.successful())
                .as("old history still verifies: " + history.standardError())
                .isTrue();
        assertThat(Files.readString(signers))
                .contains("demo valid-before=")
                .contains(typeAndKey(old))
                .contains(other);
        assertThat(new SignerHandover(runner, signers).pinned("demo"))
                .containsExactly(typeAndKey(successor));
        assertThat(
                        new Reconcile(context)
                                .run(following(context, Reconcile.after(followed(repo), retired)))
                                .outcome())
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
    }

    @Test
    void a_project_with_only_retired_keys_names_the_refused_signer(@TempDir final Path dir)
            throws IOException {
        // An intentionally empty active set is a refusal of this signer, not a missing anchor.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        Files.writeString(
                repo.resolve("project.yml"),
                Files.readString(repo.resolve("project.yml"))
                        .replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  signers: []\n"));
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "-S", "-m", "retire every key");
        final Reconcile.Result retired =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));
        assertThat(retired.outcome()).as(retired.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        Files.writeString(repo.resolve("machine-signers"), "# new message keys\n");
        git(repo, "add", "machine-signers");
        git(repo, "commit", "-q", "-S", "-m", "change by a retired key");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), retired)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.signer())
                .as("a person can identify the refused key")
                .isEqualTo(SignedBy.fingerprintOf(typeAndKey(key)));
        assertThat(result.commit()).as("the retirement stays in force").isEqualTo(retired.commit());
    }

    @Test
    void a_pinned_key_hands_on_to_the_key_its_commit_adds(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);

        commitNaming(repo, old, "handed on", old, successor);
        final Reconcile.Result handed =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(handed.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(handed.detail())
                .contains("pinned " + SignedBy.fingerprintOf(typeAndKey(successor)));
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("demo " + typeAndKey(successor))
                .contains("demo " + typeAndKey(old));

        commitNaming(repo, successor, "the old one retired", successor);
        final Reconcile.Result retired =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), handed)));

        assertThat(retired.outcome())
                .as("signed by the successor alone")
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(retired.detail())
                .contains("unpinned " + SignedBy.fingerprintOf(typeAndKey(old)));
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("valid-before=")
                .contains(typeAndKey(old));

        commitNaming(repo, old, "signed by the key that was retired", successor);
        assertThat(
                        new Reconcile(context)
                                .run(following(context, Reconcile.after(followed(repo), retired)))
                                .outcome())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
    }

    @Test
    void a_backdated_protected_change_by_a_retired_key_is_refused(@TempDir final Path dir)
            throws IOException {
        // A retired key controls its timestamps, so expiry cannot authorize a new change.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commitNaming(repo, old, "retirement", successor);
        date(repo, "2025-01-01T00:00:00Z");
        final Reconcile.Result retired =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));
        commitNaming(repo, old, "backdated configuration", successor);
        date(repo, "2024-01-01T00:00:00Z");
        final String refused =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), retired)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.refused()).isEqualTo(refused);
        assertThat(result.refusedFile()).isEqualTo("project.yml");
        assertThat(result.commit()).isEqualTo(retired.commit());
    }

    @Test
    void a_revoked_active_key_cannot_handover_or_change_configuration(@TempDir final Path dir)
            throws IOException {
        // A current key in the account's KRL must not authorize its own successor in a scratch
        // check.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path successor = key(dir, "successor");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commitNaming(repo, key, "handover by a revoked key", successor);
        final String refused =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        final Path krl = context.paths().projects().revocations();
        final var made =
                runner.run(
                        Command.of("ssh-keygen", "-q", "-k", "-f", krl.toString(), key + ".pub"));
        assertThat(made.successful()).as(made.standardError()).isTrue();

        final Reconcile.Checked check =
                new Reconcile(context).verified(Reconcile.after(followed(repo), first));
        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(check.result().outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.refused()).isEqualTo(refused);
        assertThat(result.signer()).isEqualTo(SignedBy.fingerprintOf(typeAndKey(key)));
        assertThat(result.commit()).isEqualTo(first.commit());
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .containsExactly(typeAndKey(key));
    }

    @Test
    void retirements_are_kept_when_a_fetch_returns_to_the_original_keys(@TempDir final Path dir)
            throws IOException {
        // Comparing only the final active set loses keys retired between two unseen handovers.
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path middle = key(dir, "middle");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        date(repo, "2023-01-01T00:00:00Z");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commitNaming(repo, old, "first handover", middle);
        date(repo, "2024-01-01T00:00:00Z");
        final String intermediate =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        commitNaming(repo, middle, "second handover", old);
        date(repo, "2025-01-01T00:00:00Z");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("valid-before=")
                .contains(typeAndKey(middle))
                .contains(typeAndKey(old));
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .containsExactly(typeAndKey(old));
        assertThat(PinnedSigners.of(context.paths().projects().configurationSigners()))
                .containsEntry("demo", java.util.List.of(SignedBy.fingerprintOf(typeAndKey(old))));
        final Path signers = context.paths().projects().configurationSigners();
        git(
                repo,
                "-c",
                "gpg.format=ssh",
                "-c",
                "gpg.ssh.allowedSignersFile=" + signers,
                "verify-commit",
                first.commit(),
                intermediate,
                result.commit());
        assertThat(Files.readString(signers))
                .as("each handover keeps its own historical entry")
                .contains("valid-before=\"20240101000000Z\" " + typeAndKey(old))
                .contains("valid-before=\"20250101000000Z\" " + typeAndKey(middle));
    }

    @Test
    void a_key_never_vouches_for_the_commit_that_adds_it(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path stranger = key(dir, "stranger");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));

        commitNaming(repo, stranger, "added by itself", old, stranger);
        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.detail()).contains("changes project.signers");
        assertThat(result.commit())
                .as("what runs is still what was verified")
                .isEqualTo(first.commit());
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .doesNotContain(typeAndKey(stranger));
    }

    @Test
    void a_key_added_on_the_way_is_followed_through_to_the_commit_fetched(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));

        // Both arrive in one fetch: the hand-on, then a commit only the successor signed.
        commitNaming(repo, old, "handed on", successor);
        commitNaming(repo, successor, "signed by the successor", successor);
        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("demo " + typeAndKey(successor))
                .contains("valid-before=")
                .contains(typeAndKey(old));
    }

    private FollowedProjects.Followed following(
            final PoreloContext context, final FollowedProjects.Followed record)
            throws IOException {
        new FollowedProjects(context.paths().projects().followed()).write(record);
        return record;
    }

    private FollowedProjects.Followed followed(final Path repo) {
        return new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "");
    }

    @Test
    void a_fingerprint_named_on_a_machine_with_nothing_pinned_pins_the_key_that_signed_it(
            @TempDir final Path dir) throws Exception {
        // Measured in a fresh account: with no key pinned at all the gate verifies nothing and
        // names no signer, so a fingerprint was matched against nothing and the follow stayed
        // NO_ANCHOR.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");
        final String fingerprint =
                SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip());
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        final FollowSignedBy following = new FollowSignedBy(context);

        assertThat(following.check("demo", repo.toString(), fingerprint, false).result().outcome())
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(context.paths().projects().configurationSigners())
                .as("a check pins nothing")
                .doesNotExist();
        assertThat(context.paths().eventLog()).as("and applies nothing to log").doesNotExist();
        final FollowSignedBy.Answer answer =
                following.follow("demo", repo.toString(), fingerprint, false, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(context.paths().eventLog()))
                .as("what this machine's tasks run under from now on, and whose key vouched")
                .contains("\"kind\":\"project-applied\"")
                .contains("\"project\":\"demo\"")
                .contains("commit " + answer.result().commit())
                .contains("signed by " + fingerprint);
        assertThat(answer.pinned()).isEqualTo(fingerprint);
        assertThat(answer.result().signer()).isEqualTo(fingerprint);
    }

    @Test
    void a_fingerprint_that_did_not_sign_it_pins_nothing_and_follows_nothing(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");
        final String other =
                SignedBy.fingerprintOf(
                        Files.readString(Path.of(key(dir, "other") + ".pub")).strip());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new FollowSignedBy(context)
                                        .follow("demo", repo.toString(), other, false, false))
                .isInstanceOf(FollowSignedBy.Refused.class)
                .hasMessageContaining("not by " + other);
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        assertThat(new FollowedProjects(context.paths().projects().followed()).find("demo"))
                .isNull();
        assertThat(context.paths().eventLog())
                .as("a follow taken back for its key is no event")
                .doesNotExist();
    }

    @Test
    void a_follow_refused_for_its_key_leaves_the_previous_event_unchanged(@TempDir final Path dir)
            throws Exception {

        // A fingerprint mismatch must leave the previous application and its event intact.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");
        final String fingerprint =
                SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip());
        final String other =
                SignedBy.fingerprintOf(
                        Files.readString(Path.of(key(dir, "other") + ".pub")).strip());
        new FollowSignedBy(context).follow("demo", repo.toString(), fingerprint, false, false);
        commit(repo, key, "demo", "The second version");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new FollowSignedBy(context)
                                        .follow("demo", repo.toString(), other, false, false))
                .isInstanceOf(FollowSignedBy.Refused.class);

        assertThat(Files.readAllLines(context.paths().eventLog()))
                .as("a refused follow appends no event")
                .hasSize(1)
                .first()
                .asString()
                .contains("\"kind\":\"project-applied\"")
                .contains("signed by " + fingerprint);
    }

    @Test
    void an_unverified_follow_says_so_in_the_event_it_records(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");

        new FollowSignedBy(context).follow("demo", repo.toString(), null, true, false);

        assertThat(Files.readString(context.paths().eventLog()))
                .contains("\"kind\":\"project-applied\"")
                .contains(", unverified");
    }

    @Test
    void a_follow_with_every_key_of_a_person_accepts_a_commit_signed_by_any_of_them(
            @TempDir final Path dir) throws Exception {
        // A person who signs from two computers has two keys at the forge; pinning one refused the
        // other's commit.
        final PoreloContext context = context(dir);
        final Path laptop = key(dir, "laptop");
        final Path desktop = key(dir, "desktop");
        final Path repo = published(dir, laptop, "demo");
        final java.util.List<String> keys =
                java.util.List.of(
                        Files.readString(Path.of(laptop + ".pub")).strip(),
                        Files.readString(Path.of(desktop + ".pub")).strip());
        final java.util.List<String> fingerprints =
                keys.stream().map(SignedBy::fingerprintOf).toList();
        final FollowSignedBy following = new FollowSignedBy(context);

        final FollowSignedBy.Answer would = following.check("demo", repo.toString(), keys);
        assertThat(would.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(would.signers()).as("what it would pin").isEqualTo(fingerprints);
        assertThat(context.paths().projects().configurationSigners())
                .as("a check pins nothing")
                .doesNotExist();

        final FollowSignedBy.Answer answer = following.follow("demo", repo.toString(), keys, false);
        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(answer.signers()).isEqualTo(fingerprints);
        assertThat(PinnedSigners.of(context.paths().projects().configurationSigners()))
                .containsEntry("demo", fingerprints);

        // The other computer's commit is accepted too.
        commit(repo, desktop, "demo", "signed on the other computer");
        final Reconcile.Result next =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects(context.paths().projects().followed())
                                                .require("demo")));
        assertThat(next.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(next.signer()).isEqualTo(fingerprints.get(1));

        // A third key's is not.
        commit(repo, key(dir, "stranger"), "demo", "signed by nobody named");
        assertThat(
                        new Reconcile(context)
                                .run(
                                        following(
                                                context,
                                                new FollowedProjects(
                                                                context.paths()
                                                                        .projects()
                                                                        .followed())
                                                        .require("demo")))
                                .outcome())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
    }

    @Test
    void several_keys_named_for_a_follow_that_did_not_apply_are_not_left_pinned(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = published(dir, key(dir, "stranger"), "demo");
        final java.util.List<String> keys =
                java.util.List.of(
                        Files.readString(Path.of(key(dir, "laptop") + ".pub")).strip(),
                        Files.readString(Path.of(key(dir, "desktop") + ".pub")).strip());

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context).follow("demo", repo.toString(), keys, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(answer.recorded()).isFalse();
        assertThat(context.paths().projects().configurationSigners())
                .as("a person told nothing was recorded is left trusting none of the keys")
                .doesNotExist();
    }

    @Test
    void a_follow_that_did_not_apply_leaves_what_another_project_pinned_meanwhile(
            @TempDir final Path dir) throws Exception {
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        final Path signers = context(dir).paths().projects().configurationSigners();
        // While this follow fetches, the daemon's pass hands the project 'other' on from its old
        // key to its successor, as project.signers said.
        final CommandRunner handingOn =
                command -> {
                    if (command.describe().contains(" fetch ")) {
                        try {
                            final java.util.List<String> lines =
                                    new java.util.ArrayList<>(Files.readAllLines(signers));
                            lines.removeIf(line -> line.startsWith("other "));
                            lines.add("other " + typeAndKey(successor));
                            Files.write(signers, lines);
                        } catch (final IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                    return runner.run(command);
                };
        final PoreloContext context =
                new PoreloContext(handingOn, context(dir).paths(), arguments -> 0);
        pin(context, "other", old);
        final Path repo = published(dir, key(dir, "stranger"), "demo");

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context)
                        .follow(
                                "demo",
                                repo.toString(),
                                Files.readString(Path.of(key(dir, "laptop") + ".pub")).strip(),
                                false,
                                false);

        assertThat(answer.recorded()).isFalse();
        assertThat(Files.readString(signers))
                .as("the other project keeps the key it was handed on to, and not the retired one")
                .contains("other " + typeAndKey(successor))
                .doesNotContain(typeAndKey(old))
                .doesNotContain("demo ");
    }

    @Test
    void a_list_with_one_key_that_is_not_one_pins_none_of_them(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path laptop = key(dir, "laptop");
        final Path repo = published(dir, laptop, "demo");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new FollowSignedBy(context)
                                        .follow(
                                                "demo",
                                                repo.toString(),
                                                java.util.List.of(
                                                        Files.readString(Path.of(laptop + ".pub"))
                                                                .strip(),
                                                        "nonsense"),
                                                false))
                .isInstanceOf(FollowSignedBy.Refused.class)
                .hasMessageContaining("not a public key");
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        assertThat(new FollowedProjects(context.paths().projects().followed()).find("demo"))
                .isNull();
    }

    @Test
    void several_keys_are_refused_beside_one_key_beside_unverified_and_as_fingerprints() {
        final java.util.List<String> two =
                java.util.List.of("ssh-ed25519 AAAA1", "ssh-ed25519 AAAA2");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> FollowSignedBy.refuseConflicting("ssh-ed25519 AAAA0", two, false))
                .isInstanceOf(FollowSignedBy.Refused.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> FollowSignedBy.refuseConflicting(null, two, true))
                .isInstanceOf(FollowSignedBy.Refused.class)
                .hasMessageContaining("opposite");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                FollowSignedBy.refuseConflicting(
                                        null,
                                        java.util.List.of(
                                                "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"),
                                        false))
                .isInstanceOf(FollowSignedBy.Refused.class)
                .hasMessageContaining("fingerprint");
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> FollowSignedBy.refuseConflicting("x", null, true))
                .as("one key alone is the other method's to judge")
                .doesNotThrowAnyException();
    }

    @Test
    void applies_a_commit_the_pinned_key_signed(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        final Reconcile.Result result =
                new Reconcile(context).run(following(context, followed(repo)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit()).hasSize(40);
        assertThat(context.paths().projects().followedClone("demo").resolve("project.yml"))
                .exists();
        // What a person compares with the key they were given, beside it.
        assertThat(result.signer())
                .as("the fingerprint of the key that signed what is in force")
                .isEqualTo(SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip()));
    }

    /** The order this class exists for: what cannot be verified never reaches the working tree. */
    @Test
    void keeps_what_it_verified_when_the_next_commit_is_unsigned(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        final FollowedProjects.Followed after = Reconcile.after(followed(repo), first);

        // The project's repository moves on, unsigned.
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  description: Changed by somebody who did not sign
                  security_class: online
                  upstream: git@example.org:demo.git
                image:
                  base_image: ubuntu:24.04
                """);
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "unsigned change");

        final Reconcile.Result second = new Reconcile(context).run(following(context, after));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(second.commit())
                .as("what runs is still what was verified")
                .isEqualTo(first.commit());
        assertThat(
                        Files.readString(
                                context.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")))
                .as("the working tree never moved")
                .doesNotContain("online");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"porelo.yml", "scripts/root.dockerfile"})
    void a_single_repository_image_requires_a_signature(final String input, @TempDir final Path dir)
            throws IOException {
        // Repository image settings run as root even when project.yml declares no repositories.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        Files.createDirectories(repo.resolve("scripts"));
        Files.writeString(repo.resolve("scripts/root.dockerfile"), "RUN true\n");
        Files.writeString(
                repo.resolve("porelo.yml"),
                "image: {base_image: 'ubuntu:24.04', snippet_file: scripts/root.dockerfile}\n");
        git(repo, "add", "--all");
        git(repo, "commit", "-q", "-S", "-m", "image settings");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        Files.writeString(repo.resolve(input), "# unsigned root instructions\n");
        git(repo, "add", input);
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "image change");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refusedFile()).as("the root input is named").isEqualTo(input);
        assertThat(result.commit()).as("the trusted tree stays").isEqualTo(first.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "empty,false",
        "corrupt,false",
        "empty,true",
        "corrupt,true",
        "denied,false"
    })
    void a_broken_revocation_policy_refuses_even_without_protected_changes(
            final String damage, final boolean unchanged, @TempDir final Path dir)
            throws IOException {
        // Broken policy must not turn revoked credentials into usable ones on a quiet fetch.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        if (!unchanged) {
            Files.writeString(repo.resolve("notes.md"), "notes\n");
            git(repo, "add", "notes.md");
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
        }
        final Path krl = context.paths().projects().revocations();
        Files.writeString(krl, damage.equals("empty") ? "" : "corrupt\n");
        final Path accessibleSigners =
                Files.copy(
                        context.paths().projects().configurationSigners(),
                        dir.resolve("accessible-signers"));
        final Path policyDirectory = krl.getParent();
        final var permissions = Files.getPosixFilePermissions(policyDirectory);
        try {
            if (damage.equals("denied")) {
                Files.setPosixFilePermissions(policyDirectory, java.util.Set.of());
            }
            final Reconcile.Result result =
                    damage.equals("denied")
                            ? new Reconcile(context, accessibleSigners)
                                    .verified(Reconcile.after(followed(repo), first))
                                    .result()
                            : new Reconcile(context)
                                    .run(
                                            following(
                                                    context,
                                                    Reconcile.after(followed(repo), first)));
            assertThat(result.outcome())
                    .as(result.detail())
                    .isEqualTo(Reconcile.Outcome.UNREADABLE);
            assertThat(result.commit())
                    .as("no unchecked tree is applied")
                    .isEqualTo(first.commit());
        } finally {
            Files.setPosixFilePermissions(policyDirectory, permissions);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "malformed,false",
        "denied,false",
        "malformed,true",
        "denied,true",
        "directory,false",
        "invalid_key,false",
        "invalid_options,false",
        "dangling,false"
    })
    void a_broken_signers_file_is_unreadable_for_every_range(
            final String damage, final boolean protectedChange, @TempDir final Path dir)
            throws Exception {
        // Losing a trust anchor must refuse documents too, with an actionable policy failure.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        Files.writeString(
                repo.resolve(protectedChange ? "machine-signers" : "notes.md"), "# change\n");
        git(repo, "add", "--all");
        git(repo, "commit", "-q", "-S", "-m", "change");
        final Path signers = context.paths().projects().configurationSigners();
        final var permissions = Files.getPosixFilePermissions(signers);
        try {
            if (damage.equals("malformed")) {
                Files.writeString(signers, "demo invalid\n");
            } else if (damage.equals("invalid_key")) {
                Files.writeString(signers, "demo ssh-ed25519 invalid\n");
            } else if (damage.equals("invalid_options")) {
                Files.writeString(signers, "demo invalid-option=\"git\" " + typeAndKey(key) + "\n");
            } else if (damage.equals("dangling")) {
                Files.delete(signers);
                Files.createSymbolicLink(signers, Path.of("missing-signers"));
            } else if (damage.equals("directory")) {
                Files.delete(signers);
                Files.createDirectory(signers);
            } else {
                Files.setPosixFilePermissions(signers, java.util.Set.of());
            }
            final Reconcile.Result result =
                    new Reconcile(context)
                            .run(following(context, Reconcile.after(followed(repo), first)));
            assertThat(result.outcome())
                    .as(result.detail())
                    .isEqualTo(Reconcile.Outcome.UNREADABLE);
            assertThat(result.needsAPerson()).as("a person must repair the trust input").isTrue();
            assertThat(Reconcile.after(followed(repo), result).needsAPerson())
                    .as("the stored refusal stays actionable")
                    .isTrue();
            assertThat(result.commit()).as("the trusted tree stays").isEqualTo(first.commit());
            final Reconcile.Result named =
                    new FollowSignedBy(context)
                            .check("demo", repo.toString(), typeAndKey(key), false)
                            .result();
            assertThat(named.outcome())
                    .as("an explicit key cannot hide a broken anchor: " + named.detail())
                    .isEqualTo(Reconcile.Outcome.UNREADABLE);
        } finally {
            if (damage.equals("dangling")) {
                Files.delete(signers);
                Files.writeString(signers, "demo " + typeAndKey(key) + "\n");
            }
            Files.setPosixFilePermissions(signers, permissions);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"valid", "forged", "stranger", "retired", "revoked"})
    void a_document_signature_is_not_reported_without_verification(
            final String signature, @TempDir final Path dir) throws IOException {
        // A document writer can put any public key in gpgsig, but cannot claim its authority.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        Reconcile.Result first = new Reconcile(context).run(following(context, followed(repo)));
        if (signature.equals("retired")) {
            commitNaming(repo, key, "retirement", key(dir, "successor"));
            first =
                    new Reconcile(context)
                            .run(following(context, Reconcile.after(followed(repo), first)));
        }
        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(
                repo,
                "config",
                "user.signingkey",
                signature.equals("stranger") ? key(dir, "stranger").toString() : key.toString());
        git(repo, "commit", "-q", "-S", "-m", "notes");
        if (signature.equals("forged")) {
            final String object =
                    runner.run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            repo.toString(),
                                            "cat-file",
                                            "commit",
                                            "HEAD"))
                            .standardOutput();
            final String forged = object.replace("\n\nnotes\n", "\n\nforged notes\n");
            assertThat(forged)
                    .as("the forged commit differs from the signed payload")
                    .isNotEqualTo(object);
            final var written =
                    runner.run(
                            Command.of(
                                            "git",
                                            "-C",
                                            repo.toString(),
                                            "hash-object",
                                            "-t",
                                            "commit",
                                            "-w",
                                            "--stdin")
                                    .withInput(forged));
            assertThat(written.successful()).as(written.standardError()).isTrue();
            git(repo, "update-ref", "refs/heads/main", written.standardOutput().strip());
        }
        if (signature.equals("revoked")) {
            final var made =
                    runner.run(
                            Command.of(
                                    "ssh-keygen",
                                    "-q",
                                    "-k",
                                    "-f",
                                    context.paths().projects().revocations().toString(),
                                    key + ".pub"));
            assertThat(made.successful()).as(made.standardError()).isTrue();
        }
        final FollowedProjects.Followed before = Reconcile.after(followed(repo), first);

        final Reconcile.Result result = new Reconcile(context).run(following(context, before));
        Reconcile.recordApplied(context, before, result);

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.signer()).as("a skipped signature carries no verified signer").isEmpty();
        final Reconcile.Checked checked = new Reconcile(context).verified(before);
        assertThat(checked.key()).as("the checker reports only verified signers").isNull();
        assertThat(EventLog.of(context).entries(null))
                .as("the audit event makes no unchecked signing claim")
                .last()
                .satisfies(
                        event ->
                                assertThat(String.valueOf(event.get("detail")))
                                        .doesNotContain("signed by"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_dry_run_uses_the_same_range_as_a_follow(
            final boolean protectedChange, @TempDir final Path dir) throws Exception {
        // Checking only the tip either hides unsigned configuration or refuses harmless documents.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(key), false, false).result();
        if (protectedChange) {
            commit(repo, null, "demo", "unsigned configuration");
        }
        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", protectedChange ? "-S" : "--no-gpg-sign", "-m", "notes");
        final FollowedProjects.Followed before =
                new FollowedProjects(context.paths().projects().followed()).require("demo");

        final Reconcile.Result check = follow.check("demo", repo.toString(), null, false).result();
        final Reconcile.Result direct = new Reconcile(context).checked(before).result();
        final Reconcile.Result actual =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(check.outcome()).as("dry run uses every new commit").isEqualTo(actual.outcome());
        assertThat(direct.outcome())
                .as("the daemon's check uses the same range")
                .isEqualTo(actual.outcome());
        assertThat(check.refused())
                .as("the same offending commit is named")
                .isEqualTo(actual.refused());
        assertThat(check.commit())
                .as("the same commit stays in force")
                .isEqualTo(protectedChange ? first.commit() : actual.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_dry_run_with_several_keys_uses_the_followed_range(
            final boolean protectedChange, @TempDir final Path dir) throws Exception {
        // Supplying several anchors must not discard the already verified replay baseline.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path colleague = key(dir, "colleague");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(key), false, false);
        if (protectedChange) {
            commit(repo, null, "demo", "unsigned configuration");
        }
        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", protectedChange ? "-S" : "--no-gpg-sign", "-m", "notes");

        final Reconcile.Result preview =
                follow.check(
                                "demo",
                                repo.toString(),
                                java.util.List.of(typeAndKey(key), typeAndKey(colleague)))
                        .result();
        final Reconcile.Result actual =
                follow.follow("demo", repo.toString(), null, false, false).result();

        assertThat(preview.outcome())
                .as("the list overload checks the same range")
                .isEqualTo(actual.outcome());
        assertThat(preview.commit())
                .as("the same commit stays in force")
                .isEqualTo(actual.commit());
        assertThat(preview.refused())
                .as("the same offending commit is named")
                .isEqualTo(actual.refused());
    }

    @Test
    void a_fingerprint_pins_the_refused_commit_beneath_a_document_tip(@TempDir final Path dir)
            throws Exception {
        // The key a person approves belongs to the refused change, which may precede the tip.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path colleague = key(dir, "colleague");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        follow.follow("demo", repo.toString(), typeAndKey(key), false, false);
        commit(repo, colleague, "demo", "colleague configuration");
        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
        final String fingerprint = SignedBy.fingerprintOf(typeAndKey(colleague));

        final Reconcile.Result check =
                follow.check("demo", repo.toString(), fingerprint, false).result();
        final Reconcile.Result result =
                follow.follow("demo", repo.toString(), fingerprint, false, false).result();

        assertThat(check.outcome()).as(check.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .as("the approved key remains pinned")
                .contains(typeAndKey(colleague));
    }

    @Test
    void a_follow_reports_an_unreadable_anchor_as_a_refusal(@TempDir final Path dir)
            throws Exception {
        // A read failure before reconciliation must still be an actionable trust refusal.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");
        final FollowSignedBy follow = new FollowSignedBy(context);
        final Reconcile.Result first =
                follow.follow("demo", repo.toString(), typeAndKey(key), false, false).result();
        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
        final Path signers = context.paths().projects().configurationSigners();
        final var permissions = Files.getPosixFilePermissions(signers);
        try {
            Files.setPosixFilePermissions(signers, java.util.Set.of());
            final Reconcile.Result result =
                    follow.follow("demo", repo.toString(), null, false, false).result();
            assertThat(result.outcome())
                    .as(result.detail())
                    .isEqualTo(Reconcile.Outcome.UNREADABLE);
            assertThat(result.commit()).as("the trusted commit stays").isEqualTo(first.commit());
        } finally {
            Files.setPosixFilePermissions(signers, permissions);
        }
    }

    @Test
    void an_out_of_band_key_stays_active_until_the_declared_signers_change(@TempDir final Path dir)
            throws IOException {
        // A routine protected edit must not undo an operator's extra pin.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path extra = key(dir, "extra");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        commitNaming(repo, key, "declared signers", key);
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        Files.writeString(
                context.paths().projects().configurationSigners(),
                "demo " + typeAndKey(key) + "\ndemo " + typeAndKey(extra) + "\n");
        Files.writeString(repo.resolve("machine-signers"), "# change\n");
        git(repo, "add", "machine-signers");
        git(repo, "config", "user.signingkey", extra.toString());
        git(repo, "commit", "-q", "-S", "-m", "message key change");

        Files.writeString(repo.resolve("notes.md"), "notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");
        commitNaming(repo, extra, "same declaration", key);

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .as("the declaration did not change")
                .containsExactly(typeAndKey(key), typeAndKey(extra));
        final Path successor = key(dir, "successor");
        commitNaming(repo, key, "changed declaration", key, successor);
        final Reconcile.Result handover =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), result)));
        assertThat(handover.outcome()).as(handover.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        new SignerHandover(
                                        runner, context.paths().projects().configurationSigners())
                                .pinned("demo"))
                .as("the first declaration change retires the extra pin")
                .containsExactly(typeAndKey(key), typeAndKey(successor));
        commitNaming(repo, extra, "retired extra", key, successor);
        final Reconcile.Result refused =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), handover)));
        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit())
                .as("the handover remains applied")
                .isEqualTo(handover.commit());
    }

    @Test
    void retirement_preserves_the_keys_other_options(@TempDir final Path dir) throws IOException {
        // Retirement must not widen the namespaces or historical time window of a key.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path successor = key(dir, "successor");
        pin(context, "demo", key);
        final Path signers = context.paths().projects().configurationSigners();
        Files.writeString(
                signers,
                "demo namespaces=\"git\",valid-after=\"20240601000000Z\" "
                        + typeAndKey(key)
                        + "\n");
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commitNaming(repo, key, "retirement", successor);
        date(repo, "2025-01-01T00:00:00Z");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(signers))
                .as("all previous restrictions survive retirement")
                .contains(
                        "namespaces=\"git\",valid-after=\"20240601000000Z\",valid-before=\"20250101000000Z\"");
    }

    @Test
    void an_unsigned_document_tip_is_applied(@TempDir final Path dir) throws IOException {
        // A document writer cannot change the configuration and needs no configuration key.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        Files.writeString(repo.resolve("notes.md"), "a developer's notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "notes");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.signer()).isEmpty();
        assertThat(context.paths().projects().followedClone("demo").resolve("notes.md")).exists();
    }

    @Test
    void a_signed_document_cannot_cover_an_unsigned_configuration(@TempDir final Path dir)
            throws IOException {
        // A later signature must not authorize an earlier unsigned firewall change.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commit(repo, null, "demo", "unsigned configuration");
        final String refused =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        Files.writeString(repo.resolve("notes.md"), "signed notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "-S", "-m", "notes");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refused()).isEqualTo(refused);
        assertThat(result.detail()).contains("project.yml");
        assertThat(result.commit()).isEqualTo(first.commit());
        git(repo, "reset", "--hard", first.commit());
        commit(repo, key, "demo", "repaired configuration");
        final Reconcile.Result repaired =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), result)));
        assertThat(repaired.outcome()).as(repaired.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(repaired.refused())
                .as("a repaired project forgets the refused commit")
                .isEmpty();
        assertThat(repaired.refusedFile())
                .as("a repaired project forgets the refused path")
                .isEmpty();
        following(context, Reconcile.after(followed(repo), repaired));
        final FollowedProjects.Followed stored =
                new FollowedProjects(context.paths().projects().followed()).require("demo");
        assertThat(stored.refusedFile()).as("the cleared path survives storage").isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "access.yml, change",
        "access.yml, delete",
        "project.yml, delete",
        "project.yml, rename",
        "machine-signers, add",
        "machine-signers, change",
        ".gitattributes, change",
        "snippets/.gitattributes, change",
        "root.dockerfile, change",
        "snippets/repo.dockerfile, change",
        "root.dockerfile, delete",
        "snippets/repo.dockerfile, rename"
    })
    void every_protected_path_is_checked_before_a_signed_document(
            final String path, final String operation, @TempDir final Path dir) throws IOException {
        // An unsigned script, access rule, or message key must not inherit a later signature.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        Files.writeString(
                repo.resolve("project.yml"),
                Files.readString(repo.resolve("project.yml"))
                        + "  snippet_file: root.dockerfile\n"
                        + "access: {file: access.yml}\n"
                        + "repositories:\n"
                        + "  work:\n"
                        + "    upstream: https://example.org/work.git\n"
                        + "    image: {base_image: 'ubuntu:24.04', snippet_file:"
                        + " snippets/repo.dockerfile}\n");
        Files.writeString(repo.resolve("access.yml"), "{}\n");
        Files.writeString(repo.resolve("machine-signers"), "# message keys\n");
        Files.writeString(repo.resolve("root.dockerfile"), "RUN true\n");
        Files.createDirectories(repo.resolve("snippets"));
        Files.writeString(repo.resolve("snippets/repo.dockerfile"), "RUN true\n");
        Files.writeString(repo.resolve(".gitattributes"), "# root attributes\n");
        Files.writeString(repo.resolve("snippets/.gitattributes"), "# snippet attributes\n");
        if (operation.equals("add")) {
            Files.delete(repo.resolve(path));
        }
        git(repo, "add", "--all");
        git(repo, "commit", "-q", "-S", "-m", "protected inputs");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        switch (operation) {
            case "delete" -> Files.delete(repo.resolve(path));
            case "rename" -> Files.move(repo.resolve(path), repo.resolve("renamed.dockerfile"));
            default -> Files.writeString(repo.resolve(path), "# unsigned replacement\n");
        }
        git(repo, "add", "--all");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "unsigned input");
        final String refused =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        Files.writeString(repo.resolve("notes.md"), "signed notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "-S", "-m", "notes");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refused()).isEqualTo(refused);
        assertThat(result.refusedFile()).isEqualTo(path);
        assertThat(result.commit()).isEqualTo(first.commit());
    }

    @Test
    void an_unsigned_attribute_file_cannot_transform_a_protected_input(@TempDir final Path dir)
            throws IOException {
        // Git's ident attribute would replace a signed YAML value while checking out the new tip.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        commit(repo, key, "demo", "$Id$");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        Files.writeString(repo.resolve(".gitattributes"), "project.yml ident\n");
        git(repo, "add", ".gitattributes");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "attributes");
        Files.writeString(repo.resolve("notes.md"), "signed notes\n");
        git(repo, "add", "notes.md");
        git(repo, "commit", "-q", "-S", "-m", "notes");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refusedFile()).isEqualTo(".gitattributes");
        assertThat(result.commit()).isEqualTo(first.commit());
        assertThat(
                        Files.readString(
                                context.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")))
                .contains("description: $Id$");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "machine-signers",
                "snippet_file",
                "project.yml",
                "access.yml",
                ".gitattributes",
                "ancestor",
                "porelo.yml",
                "repository_snippet"
            })
    void a_protected_input_cannot_redirect_to_an_unprotected_file(
            final String input, @TempDir final Path dir) throws IOException {
        // A signed symlink would let later unsigned target edits change what a machine consumes.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        final String original = Files.readString(repo.resolve("project.yml"));
        Files.writeString(repo.resolve("unprotected.txt"), "RUN true\n");
        switch (input) {
            case "snippet_file" -> {
                Files.createSymbolicLink(
                        repo.resolve("snippet.dockerfile"), Path.of("unprotected.txt"));
                Files.writeString(
                        repo.resolve("project.yml"),
                        original + "  snippet_file: snippet.dockerfile\n");
            }
            case "project.yml" -> {
                Files.writeString(repo.resolve("unprotected.txt"), original);
                Files.delete(repo.resolve("project.yml"));
                Files.createSymbolicLink(repo.resolve("project.yml"), Path.of("unprotected.txt"));
            }
            case "access.yml" -> {
                Files.writeString(repo.resolve("unprotected.txt"), "{}\n");
                Files.writeString(
                        repo.resolve("project.yml"), original + "access: {file: access.yml}\n");
                Files.createSymbolicLink(repo.resolve("access.yml"), Path.of("unprotected.txt"));
            }
            case "ancestor" -> {
                Files.createDirectories(repo.resolve("other_dir"));
                Files.writeString(repo.resolve("other_dir/root.dockerfile"), "RUN true\n");
                Files.createSymbolicLink(repo.resolve("snippets"), Path.of("other_dir"));
                Files.writeString(
                        repo.resolve("project.yml"),
                        original + "  snippet_file: snippets/root.dockerfile\n");
            }
            case "repository_snippet" -> {
                Files.writeString(
                        repo.resolve("porelo.yml"), "image: {snippet_file: root.dockerfile}\n");
                Files.createSymbolicLink(
                        repo.resolve("root.dockerfile"), Path.of("unprotected.txt"));
            }
            case "porelo.yml" -> {
                Files.writeString(
                        repo.resolve("unprotected.txt"), "image: {snippet: 'RUN true'}\n");
                Files.createSymbolicLink(repo.resolve(input), Path.of("unprotected.txt"));
            }
            default -> Files.createSymbolicLink(repo.resolve(input), Path.of("unprotected.txt"));
        }
        git(repo, "add", "--all");
        git(repo, "commit", "-q", "-S", "-m", "indirect input");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome())
                .as(result.detail())
                .isIn(Reconcile.Outcome.UNUSABLE, Reconcile.Outcome.UNREADABLE);
        assertThat(result.commit()).isEqualTo(first.commit());
    }

    @Test
    void a_foxtrot_merge_cannot_replace_the_trusted_first_parent(@TempDir final Path dir)
            throws IOException {
        // A second-parent ancestry check alone cannot establish the followed main's change range.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        commit(repo, null, "demo", "unsigned configuration");
        git(repo, "branch", "side");
        commit(repo, key, "demo", "trusted main");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        git(repo, "checkout", "-q", "side");
        git(repo, "merge", "--no-ff", "-s", "ours", "--no-gpg-sign", "-m", "foxtrot", "main");
        git(repo, "branch", "-f", "main", "HEAD");
        git(repo, "checkout", "-q", "main");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.commit())
                .as("a second-parent descendant keeps the trusted tree")
                .isEqualTo(first.commit());
        assertThat(
                        Files.readString(
                                context.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")))
                .as("an unsigned merge cannot restore its first parent's older configuration")
                .contains("description: trusted main")
                .doesNotContain("description: unsigned configuration");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void a_merge_is_compared_with_its_first_parent(final boolean signed, @TempDir final Path dir)
            throws IOException {
        // Signing a side branch must not authorize an unsigned merge's protected tree.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        git(repo, "checkout", "-q", "-b", "side");
        commit(repo, key, "demo", "side configuration");
        git(repo, "checkout", "-q", "main");
        git(repo, "merge", "--no-ff", signed ? "-S" : "--no-gpg-sign", "-m", "merge", "side");
        final String merge =
                runner.run(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome())
                .isEqualTo(signed ? Reconcile.Outcome.APPLIED : Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refused()).isEqualTo(signed ? "" : merge);
        assertThat(result.refusedFile()).isEqualTo(signed ? "" : "project.yml");
        assertThat(result.commit()).isEqualTo(signed ? merge : first.commit());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void an_incomplete_changed_file_listing_cannot_hide_a_protected_change(
            final boolean truncated, @TempDir final Path dir) throws IOException {
        // A successful process may return only a retained tail or a partially drained path list.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commit(repo, null, "demo", "unsigned configuration");
        final String partial =
                truncated ? "[8388608 bytes cut before this]\nnotes.md\0" : "notes.md";
        final PoreloContext incomplete =
                new PoreloContext(
                        command ->
                                command.arguments().contains("diff-tree")
                                        ? new org.fuin.porelo.core.process.CommandResult(
                                                command, 0, partial, "")
                                        : runner.run(command),
                        context.paths(),
                        arguments -> 0);

        final Reconcile.Result result =
                new Reconcile(incomplete)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome())
                .as("incomplete output is not a complete list of changes")
                .isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.commit())
                .as("an unchecked range keeps the trusted commit")
                .isEqualTo(first.commit());
    }

    @Test
    void a_failed_commit_listing_cannot_apply_an_unchecked_range(@TempDir final Path dir)
            throws IOException {
        // A failed git listing is not proof that there were no protected changes.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        commit(repo, null, "demo", "unsigned configuration");
        final PoreloContext failing =
                new PoreloContext(
                        command ->
                                command.arguments().contains("rev-list")
                                        ? new org.fuin.porelo.core.process.CommandResult(
                                                command, 1, "", "listing failed")
                                        : runner.run(command),
                        context.paths(),
                        arguments -> 0);

        final Reconcile.Result result =
                new Reconcile(failing)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREADABLE);
        assertThat(result.commit()).isEqualTo(first.commit());
    }

    @Test
    void a_second_run_with_nothing_new_changes_nothing(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(following(context, followed(repo)));

        final Reconcile.Result second =
                reconcile.run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(second.commit()).isEqualTo(first.commit());
        assertThat(second.signer()).startsWith("SHA256:").isEqualTo(first.signer());
    }

    @Test
    void a_repository_that_cannot_be_reached_leaves_everything_alone(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                dir.resolve("nowhere").toString(),
                                                "",
                                                "",
                                                "",
                                                "")));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
        assertThat(result.detail()).contains("cannot fetch");
    }

    /** A machine with nothing pinned verifies nothing, so it applies nothing. */
    @Test
    void a_machine_with_no_anchor_applies_nothing(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");

        final Reconcile.Result result =
                new Reconcile(context).run(following(context, followed(repo)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NO_ANCHOR);
        assertThat(result.detail()).contains("no key is pinned");
    }

    /**
     * Signed by the right key and still not a project: that is a different problem from an unsigned
     * commit, and only one of the two is about trust.
     */
    @Test
    void a_signed_commit_that_is_not_a_project_is_its_own_outcome(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        Files.writeString(repo.resolve("project.yml"), "project:\n  name: NOT A NAME\n");
        git(repo, "add", "project.yml");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", key.toString());
        git(repo, "commit", "-q", "-S", "-m", "signed nonsense");

        final Reconcile.Result result =
                new Reconcile(context).run(following(context, followed(repo)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNUSABLE);
        assertThat(result.detail()).contains("does not read as a project");
    }

    @Test
    void a_signed_commit_refused_as_unusable_never_reaches_the_working_tree(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        // Signed by the pinned key and calling itself another project: refused, as it must be.
        commit(repo, key, "other", "a file that names another project");

        final Reconcile.Result second =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.UNUSABLE);
        assertThat(second.commit()).isEqualTo(first.commit());
        assertThat(
                        Files.readString(
                                context.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")))
                .as("the file in force is still the one applied, not the one refused")
                .contains("name: demo")
                .doesNotContain("another project");
    }

    /**
     * A signed commit that is not a descendant of the one in force. Somebody with the key may have
     * rebased - and somebody without it may be serving an older signed configuration to put back a
     * rule that was taken away, which needs no key at all. The two are identical from here.
     */
    @Test
    void refuses_a_signed_commit_that_did_not_come_after_the_one_in_force(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(following(context, followed(repo)));
        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);

        // The published repository's history is replaced: a new root commit, properly signed, and
        // with different content - an identical tree and message with no parent would be the same
        // commit, because git names a commit by what is in it.
        git(repo, "checkout", "-q", "--orphan", "rewritten");
        commit(repo, key, "demo", "rewritten");
        git(repo, "branch", "-q", "-M", "rewritten", "main");

        final Reconcile.Result second =
                reconcile.run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.REWRITTEN);
        assertThat(second.commit())
                .as("what runs is still what was verified")
                .isEqualTo(first.commit());
        assertThat(second.detail()).contains("--accept-rewrite");
    }

    @Test
    void accepting_a_rewrite_applies_a_verified_replacement_tip(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(following(context, followed(repo)));
        final FollowedProjects.Followed inForce =
                following(context, Reconcile.after(followed(repo), first));
        git(repo, "checkout", "-q", "--orphan", "rewritten");
        commit(repo, key, "demo", "rewritten");
        git(repo, "branch", "-q", "-M", "rewritten", "main");

        final Reconcile.Result accepted = reconcile.acceptingRewrite(true).run(inForce);

        assertThat(accepted.outcome()).as(accepted.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    // ---------------------------------------------------------------- what a reconciliation may
    // touch, and what it may not. What a reconciliation may release a held message or disturb a
    // task is not reconciliation, it is remote control of somebody's machine by whoever can commit.
    // Asserted rather than assumed - the property holds because Reconcile only ever moves the
    // followed clone, and that is exactly the kind of thing that stays true until somebody adds one
    // line.

    @Test
    void what_a_person_held_survives_a_reconciliation(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        // A held message, the moderation file that holds a peer, and a running task's own state.
        final Mailbox mailbox =
                new Mailbox(context.paths().messaging().mailbox("porelo-demo-shell"));
        mailbox.create();
        final Path held = mailbox.hold().resolve("m1.json");
        Files.writeString(held, "{\"held\":\"by a person\"}");
        final Path mode = mailbox.root().resolve("moderation.json");
        Files.writeString(mode, "{\"reviewer\":\"prompt\"}");
        final Path taskState = context.paths().tasks().containerState("porelo-demo-shell");
        Files.createDirectories(taskState);
        Files.writeString(taskState.resolve("gate.pid"), "4242");

        assertThat(new Reconcile(context).run(following(context, followed(repo))).outcome())
                .isEqualTo(Reconcile.Outcome.APPLIED);

        assertThat(held).exists().content().isEqualTo("{\"held\":\"by a person\"}");
        assertThat(mode).exists().content().isEqualTo("{\"reviewer\":\"prompt\"}");
        assertThat(taskState.resolve("gate.pid")).exists().content().isEqualTo("4242");
    }

    @Test
    void a_local_edit_to_a_reconciled_file_is_replaced_and_reported(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first =
                new Reconcile(context).run(following(context, followed(repo)));
        final Path clone = context.paths().projects().followedClone("demo");

        // Somebody edits the reconciled file on the machine, and drops a file of their own beside
        // it. The repository wins for what it covers - and says so, rather than doing it quietly.
        Files.writeString(clone.resolve("project.yml"), "project:\n  name: edited-by-hand\n");
        Files.writeString(clone.resolve("notes.txt"), "mine");

        commit(repo, key, "demo", "moved on");
        final Reconcile.Result second =
                new Reconcile(context)
                        .run(following(context, Reconcile.after(followed(repo), first)));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(clone.resolve("project.yml")).content().contains("moved on");
        // Reported. Without this the rule is real and invisible, which the rule refused to leave
        // unsaid.
        assertThat(second.detail()).contains("replaced").contains("project.yml");
        // And a file the repository does not cover is left where it is.
        assertThat(clone.resolve("notes.txt")).exists().content().isEqualTo("mine");
        assertThat(second.detail()).doesNotContain("notes.txt");
    }

    @Test
    void two_machines_at_the_same_commit_end_up_with_the_same_project(@TempDir final Path dir)
            throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");

        // Two accounts, two machines as far as this is concerned: separate XDG roots, nothing
        // copied between them, the same repository and the same pinned key.
        final PoreloContext one = context(dir.resolve("machine-one"));
        final PoreloContext two = context(dir.resolve("machine-two"));
        pin(one, "demo", key);
        pin(two, "demo", key);

        final Reconcile.Result first = new Reconcile(one).run(following(one, followed(repo)));
        final Reconcile.Result second = new Reconcile(two).run(following(two, followed(repo)));

        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(first.commit()).isEqualTo(second.commit());
        assertThat(
                        Files.readString(
                                one.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")))
                .isEqualTo(
                        Files.readString(
                                two.paths()
                                        .projects()
                                        .followedClone("demo")
                                        .resolve("project.yml")));
    }

    @Test
    void a_key_this_machine_was_never_given_is_its_own_outcome(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        // Signed, and signed well - by somebody else's key. This is the case that belongs in front
        // of a person: either a key that has moved, or somebody putting a project file past the
        // machine. Reported as one refusal with an unsigned commit, the two were the same event.
        final Path stranger = key(dir, "stranger");
        final Path repo = published(dir, stranger, "demo");

        final Reconcile.Result result =
                new Reconcile(context).run(following(context, followed(repo)));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        // The commit turned away, which used to be discarded - the record kept only what was in
        // force, so nothing could say what had been rejected.
        assertThat(result.refused()).hasSize(40);
        assertThat(result.commit()).isEmpty();
        // And the fingerprint, so a person can compare it with the key they meant to pin.
        assertThat(result.signer()).startsWith("SHA256:");
        assertThat(result.needsAPerson()).isTrue();
    }

    @Test
    void an_unsigned_commit_names_neither_a_key_nor_a_person_to_blame(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result result =
                new Reconcile(context).run(following(context, followed(repo)));

        // Different outcome from the one above, which is the whole point: somebody forgot to sign
        // is not somebody signing with a key they should not have.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refused()).hasSize(40);
        assertThat(result.signer()).isEmpty();
    }

    @Test
    void a_machine_with_no_vault_is_unreachable_rather_than_locked(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        // There is no vault here at all, so nothing is locked away and nothing about one explains
        // the failed fetch. Saying "unlock your vault" would send somebody to a vault they have not
        // made.
        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                dir.resolve("nowhere").toString(),
                                                "",
                                                "",
                                                "",
                                                "")));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
        assertThat(result.detail()).doesNotContain("vault");
    }

    @Test
    void an_unverified_follow_applies_what_nobody_signed(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        // Nothing pinned, and the commit is unsigned - which is NOT_SIGNED for an ordinary follow.
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                repo.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        // Somebody asked for this. The check is skipped; the reporting is not.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit()).hasSize(40);
        assertThat(context.paths().projects().followedClone("demo").resolve("project.yml"))
                .exists();
    }

    @Test
    void an_unverified_follow_still_refuses_a_file_that_is_not_a_project(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        Files.writeString(repo.resolve("project.yml"), "this: is not a project\n");
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "broken");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                repo.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        // Unverified means nobody checked WHO wrote it - not that anything goes. A file that is not
        // a project would fail at the first task start, which is somewhere else and later.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNUSABLE);
    }

    @Test
    void following_unverified_is_per_project(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path signed = published(dir, key, "demo");

        final Reconcile.Result verified =
                new Reconcile(context).run(following(context, followed(signed)));
        // A second repository of its own, because a project file that calls itself something other
        // than the name it is followed under is refused.
        final Path other = dir.resolve("published-two");
        Files.createDirectories(other);
        git(other, "init", "-q", "-b", "main", ".");
        git(other, "config", "user.email", "operator@example.org");
        git(other, "config", "user.name", "Operator");
        commit(other, null, "demo-two");

        final Reconcile.Result without =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo-two",
                                                other.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        // One project without an anchor must not quieten another that has one: they are separate
        // records and separate answers.
        assertThat(verified.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(without.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void a_declared_repository_need_not_exist_for_the_follow_to_apply(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        // A second repository pointing at a forge nobody can reach from here.
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                repositories:
                  backend:
                    upstream: "git@nowhere.invalid:acme/backend.git"
                """);
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "two repositories");

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                repo.toString(),
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                "",
                                                true)));

        // Following reads the project file and checks what THIS machine can answer about it - the
        // egress sets it has, the name it is followed under. It reaches no work repository: a
        // mirror is made when a task is started for one, and a project may name a repository that
        // does not exist yet, which is what a project still being planned looks like.
        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        org.fuin.porelo.core.project.ProjectReader.read(
                                        context.paths()
                                                .projects()
                                                .followedClone("demo")
                                                .resolve("project.yml"))
                                .repositoryNames())
                .containsExactly("demo", "backend");
    }

    @Test
    void an_unverified_follow_is_still_unverified_in_the_record_it_writes(@TempDir final Path dir)
            throws IOException {

        // Where it was lost. Every follow ends by writing 'after(known, result)', and that method
        // rebuilt the record through a constructor that defaults the flag - so the follow wrote
        // "unverified" and the next line wrote it away again. The outcome was right, the clone was
        // right, and the one field that says WHO DECIDES read as though a signature had been
        // checked. Found through the interface, whose dialog shows that field on every project.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final FollowedProjects.Followed taken =
                new FollowedProjects.Followed(
                        "demo", repo.toString(), "", "", "", "", "", "", true);

        final FollowedProjects.Followed written =
                Reconcile.after(taken, new Reconcile(context).run(following(context, taken)));

        assertThat(written.outcome()).isEqualTo("APPLIED");
        assertThat(written.unverified()).isTrue();
    }

    @Test
    void accepting_a_rewrite_forgets_the_commit_and_nothing_else(@TempDir final Path dir)
            throws IOException {

        // The same field, lost the same way in the other two writers: both rebuilt the record by
        // hand to clear the commit. Accepting a rewrite must not quietly promote an unverified
        // follow to one that claims a signature was checked.
        final FollowedProjects projects = new FollowedProjects(dir.resolve("followed"));
        projects.write(
                new FollowedProjects.Followed(
                        "demo",
                        "git@example.com:x/demo.git",
                        "a1b2c3",
                        "2026-09-19T00:00:00Z",
                        "REWRITTEN",
                        "history was rewritten",
                        "d4e5f6",
                        "SHA256:whoever",
                        true));

        projects.write(projects.find("demo").forgettingWhatIsInForce());

        final FollowedProjects.Followed after = projects.find("demo");
        assertThat(after.commit()).isEmpty();
        assertThat(after.unverified()).isTrue();
        assertThat(after.url()).isEqualTo("git@example.com:x/demo.git");
        assertThat(after.refused()).isEqualTo("d4e5f6");
        assertThat(after.signer()).isEqualTo("SHA256:whoever");
    }

    @Test
    void a_check_says_what_following_would_do_and_does_none_of_it(@TempDir final Path dir)
            throws IOException {

        // Asked before anything is written. A follow that cannot apply used to leave a project
        // behind that was followed with nothing in force - one was met, with no verb to take it
        // away.
        final PoreloContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        final Reconcile.Result would = new Reconcile(context).check(followed(repo));

        assertThat(would.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(would.commit()).hasSize(40);
        // And nothing of this account moved: no clone, and nothing recorded.
        assertThat(context.paths().projects().followedClone("demo")).doesNotExist();
    }

    @Test
    void a_check_gives_the_same_refusal_the_follow_would(@TempDir final Path dir)
            throws IOException {

        final PoreloContext context = context(dir);
        // Nothing pinned and nothing signed. What it answers matters less than that it answers the
        // SAME as the follow: a check that disagreed with the thing it stands in for would send
        // somebody to follow a project that then refuses, or stop them following one that would
        // have worked.
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result would = new Reconcile(context).check(followed(repo));

        assertThat(would.needsAPerson()).isTrue();
        // Nothing of this account moved while it was asked.
        assertThat(context.paths().projects().followedClone("demo")).doesNotExist();

        final Reconcile.Result real =
                new Reconcile(context).run(following(context, followed(repo)));
        assertThat(would.outcome()).isEqualTo(real.outcome());
        assertThat(would.detail()).isEqualTo(real.detail());
    }

    @Test
    void a_repository_only_a_key_opens_says_the_key_is_missing_not_the_network(
            @TempDir final Path dir) throws IOException {

        // The refusal the operator was given was "unlock your vault", for a vault that held nothing
        // this path would have used - because the follow never asked the vault for anything. With
        // no key stored, an ssh URL that cannot be fetched is a missing credential, and the answer
        // names the entry and the command that fills it.
        final PoreloContext base = context(dir);
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            // The credential diagnosis needs a failed fetch, without contacting a
                            // real host.
                            if (command.arguments().contains("fetch")) {
                                return new org.fuin.porelo.core.process.CommandResult(
                                        command, 1, "", "repository unavailable");
                            }
                            return runner.run(command);
                        },
                        base.paths(),
                        arguments -> 0);

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                "git@nowhere.invalid:acme/demo.git",
                                                "",
                                                "",
                                                "",
                                                "")));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NO_CREDENTIAL);
        // Named by host, so a company forge and a public one are two credentials rather than one
        // key offered to whatever host a project file happens to name.
        assertThat(result.detail()).contains("porelo vault put git.ssh.nowhere.invalid");
        assertThat(result.needsAPerson()).isTrue();
    }

    @Test
    void a_local_repository_that_cannot_be_fetched_is_not_blamed_on_a_missing_key(
            @TempDir final Path dir) throws IOException {

        // The other half of the same rule. A path is not opened by an ssh key, so sending somebody
        // to store one would waste their time on a URL that is simply wrong.
        final PoreloContext context = context(dir);

        final Reconcile.Result result =
                new Reconcile(context)
                        .run(
                                following(
                                        context,
                                        new FollowedProjects.Followed(
                                                "demo",
                                                dir.resolve("nowhere").toString(),
                                                "",
                                                "",
                                                "",
                                                "")));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
    }

    @Test
    void aWholeKeyThatDidNotSignItIsNotLeftPinnedWhenTheFollowFails(@TempDir final Path dir)
            throws Exception {

        // The key was pinned before anything was checked and stayed when the follow failed -
        // "nothing was recorded", the answer said, while the key stayed trusted on this machine.
        final PoreloContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");
        final String other = Files.readString(Path.of(key(dir, "other") + ".pub")).strip();

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context).follow("demo", repo.toString(), other, false, false);

        assertThat(answer.recorded()).isFalse();
        assertThat(
                        context.paths().projects().configurationSigners().toFile().exists()
                                ? Files.readString(
                                        context.paths().projects().configurationSigners())
                                : "")
                .doesNotContain(other.split("\\s+")[1]);
    }

    /**
     * A project repository whose file says the class, unsigned: followed with --unverified in these
     * tests.
     */
    private Path ofClass(final Path dir, final String securityClass) throws IOException {
        final Path repo = Files.createDirectories(dir.resolve("published-" + securityClass));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        setClass(repo, securityClass);
        return repo;
    }

    private void setClass(final Path repo, final String securityClass) throws IOException {
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  description: %s
                  security_class: %s
                image:
                  base_image: ubuntu:24.04
                """
                        .formatted(securityClass + " " + System.nanoTime(), securityClass));
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", securityClass);
    }

    @Test
    void a_failed_first_follow_preserves_preexisting_access_files(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("demo"));
        Files.writeString(accessClone.resolve("access.yml"), "{}\n");

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context)
                        .follow("demo", dir.resolve("missing.git").toString(), null, true, false);

        assertThat(answer.recorded()).as("the first follow could not apply").isFalse();
        assertThat(accessClone.resolve("access.yml"))
                .as("a refusal cannot remove files that predate the request")
                .hasContent("{}\n");
    }

    @Test
    void an_offline_project_is_followed_from_a_local_directory(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "offline");

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context).follow("demo", repo.toString(), null, true, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void an_offline_project_is_followed_from_a_bundle(@TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "offline");
        final Path bundle = dir.resolve("demo.bundle");
        git(repo, "bundle", "create", "-q", bundle.toString(), "--all");

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context).follow("demo", bundle.toString(), null, true, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void an_offline_project_is_not_followed_from_a_url(@TempDir final Path dir) throws Exception {
        // Following fetches the definition again in the background: an offline project would
        // connect every round.
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "offline");

        final FollowSignedBy.Answer answer =
                new FollowSignedBy(context)
                        .follow("demo", repo.toUri().toString(), null, true, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.OFFLINE_FROM_A_URL);
        assertThat(answer.result().detail()).contains("follow it from a file");
        assertThat(answer.recorded()).isFalse();
    }

    @Test
    void a_project_followed_from_a_url_that_turns_offline_keeps_what_it_had(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "guarded");
        final FollowSignedBy.Answer first =
                new FollowSignedBy(context)
                        .follow("demo", repo.toUri().toString(), null, true, false);
        assertThat(first.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        setClass(repo, "offline");

        final java.util.Map<String, Reconcile.Result> done =
                new ConfigurationWatch(context, java.time.Duration.ZERO).refresh("demo");

        assertThat(done.get("demo").outcome()).isEqualTo(Reconcile.Outcome.OFFLINE_FROM_A_URL);
        assertThat(done.get("demo").commit())
                .as("what was in force stays")
                .isEqualTo(first.result().commit());
    }

    @Test
    void a_commit_the_watch_applies_is_an_event_and_one_it_finds_unchanged_is_not(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "guarded");
        new FollowSignedBy(context).follow("demo", repo.toUri().toString(), null, true, false);
        commit(repo, null, "demo", "The second version");
        final ConfigurationWatch watch = new ConfigurationWatch(context, java.time.Duration.ZERO);

        final Reconcile.Result applied = watch.refresh("demo").get("demo");
        watch.refresh("demo");

        assertThat(applied.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readAllLines(context.paths().eventLog()))
                .hasSize(2)
                .last()
                .asString()
                .contains("commit " + applied.commit());
    }

    @Test
    void a_project_followed_from_a_file_is_never_fetched_and_says_how_it_changes(
            @TempDir final Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path repo = ofClass(dir, "offline");
        final FollowSignedBy.Answer first =
                new FollowSignedBy(context).follow("demo", repo.toString(), null, true, false);
        setClass(repo, "offline");

        final java.util.Map<String, Reconcile.Result> done =
                new ConfigurationWatch(context, java.time.Duration.ZERO).refresh("demo");

        assertThat(done.get("demo").outcome()).isEqualTo(Reconcile.Outcome.FROM_A_FILE);
        assertThat(done.get("demo").commit()).isEqualTo(first.result().commit());
        assertThat(done.get("demo").detail()).contains("follow it again from a newer file");
    }
}
