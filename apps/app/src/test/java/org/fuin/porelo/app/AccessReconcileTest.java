package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the access file to the project's trust chain, using real git repositories and signatures.
 */
class AccessReconcileTest {

    @ParameterizedTest
    @ValueSource(strings = {"missing", "background", "unborn", "wrong-head"})
    void a_successful_unchanged_follow_recovers_its_missing_access_clone(
            final String recovery, @TempDir final Path dir) throws Exception {
        // A successful follow must leave the verified access settings available to readers.
        final Fixture fixture = fixture(dir);
        final FollowSignedBy follow = new FollowSignedBy(fixture.context());
        final var first = follow.follow("demo", fixture.project().toString(), null, false, false);
        final Path clone = accessClone(fixture);
        FollowedProjects.forget(clone);
        if (recovery.equals("background")) {
            new Reconcile(fixture.context()).run(projects(fixture.context()).require("demo"));
            assertThat(clone.resolve("access.yml"))
                    .as("background recovery restores the access configuration")
                    .hasContent("{}\n");
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

        final var answer = follow.follow("demo", fixture.project().toString(), null, false, false);

        assertThat(answer.result().accessOutcome())
                .as(answer.result().detail())
                .isEqualTo("UNCHANGED");
        assertThat(clone.resolve("access.yml"))
                .as("recovered access settings become live")
                .hasContent("{}\n");
        assertThat(git(clone, "rev-parse", "HEAD"))
                .as("the recovered access commit is checked out")
                .isEqualTo(first.result().accessCommit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed", "unsigned", "document"})
    void switching_to_verified_checks_the_access_tip_as_a_first_follow(
            final String tipKind, @TempDir final Path dir) throws Exception {
        // Unchecked access settings cannot acquire verified status through an unchanged baseline.
        final Fixture fixture = fixture(dir);
        final FollowSignedBy follow = new FollowSignedBy(fixture.context());
        if (tipKind.equals("unsigned")) {
            Files.writeString(fixture.access().resolve("access.yml"), "# unchecked\n{}\n");
            commit(fixture.access(), null);
        }
        follow.follow("demo", fixture.project().toString(), null, true, false);
        if (tipKind.equals("document")) {
            Files.writeString(fixture.access().resolve("README.md"), "unchecked document\n");
            commit(fixture.access(), null);
        }
        final var answer =
                follow.follow(
                        "demo",
                        fixture.project().toString(),
                        publicKey(fixture.key()),
                        false,
                        false);

        assertThat(answer.result().accessOutcome())
                .as(answer.result().detail())
                .isEqualTo(tipKind.equals("signed") ? "APPLIED" : "NOT_SIGNED");
        assertThat(projects(fixture.context()).require("demo").unverified())
                .as("an unsigned access tip cannot turn the follow into a verified one")
                .isEqualTo(!tipKind.equals("signed"));
    }

    @Test
    void a_forged_access_document_signature_cannot_claim_an_audit_signer(@TempDir final Path dir)
            throws Exception {
        // A document writer must not impersonate an access authority in the audit log.
        final Fixture fixture = fixture(dir);
        reconcile(fixture.context());
        Files.writeString(fixture.access().resolve("notes.md"), "notes\n");
        commit(fixture.access(), fixture.key());
        final String object =
                runner.run(
                                Command.of(
                                        "git",
                                        "-C",
                                        fixture.access().toString(),
                                        "cat-file",
                                        "commit",
                                        "HEAD"))
                        .standardOutput();
        final String forged = object.replace("\n\nconfiguration\n", "\n\nforged notes\n");
        assertThat(forged)
                .as("the payload differs from what the signature covers")
                .isNotEqualTo(object);
        final CommandResult written =
                runner.run(
                        Command.of(
                                        "git",
                                        "-C",
                                        fixture.access().toString(),
                                        "hash-object",
                                        "-t",
                                        "commit",
                                        "-w",
                                        "--stdin")
                                .withInput(forged));
        assertThat(written.successful()).as(written.standardError()).isTrue();
        git(fixture.access(), "update-ref", "refs/heads/main", written.standardOutput().strip());
        assertThat(SignedBy.keyOf(runner, fixture.access(), "HEAD"))
                .as("the forged signature still embeds the trusted public key")
                .isEqualTo(publicKey(fixture.key()));
        assertThat(
                        new ConfigurationGate(
                                        runner,
                                        fixture.context().paths().projects().configurationSigners())
                                .verify(fixture.access(), "HEAD")
                                .mayApply())
                .as("the embedded key does not verify the forged payload")
                .isFalse();

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("APPLIED");
        assertThat(EventLog.of(fixture.context()).entries(null))
                .as("an access document update has its own event")
                .hasSize(3)
                .last()
                .satisfies(
                        event -> {
                            assertThat(event.get("detail"))
                                    .as("the event identifies the applied access commit")
                                    .asString()
                                    .contains(result.accessCommit())
                                    .doesNotContain(
                                            "signed by",
                                            SignedBy.fingerprintOf(publicKey(fixture.key())));
                        });
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
                                                    "1")));

    @Test
    void a_pinned_access_commit_moves_its_clone_and_is_recorded(@TempDir final Path dir)
            throws Exception {
        // Access settings must arrive only after their commit passes the project's pinned key.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result result = reconcile(fixture.context());
        final String commit = git(fixture.access(), "rev-parse", "HEAD");

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("APPLIED");
        assertThat(result.accessCommit())
                .as("the signed access commit is in force")
                .isEqualTo(commit);
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("the access clone moved only after verification")
                .isEqualTo(commit);
        assertThat(Files.readString(accessClone(fixture).resolve("access.yml")))
                .as("the access clone holds the verified settings")
                .isEqualTo("{}\n");
        assertThat(projects(fixture.context()).require("demo").accessCommit())
                .as("the access commit survives the next pass")
                .isEqualTo(commit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void an_unknown_or_unsigned_access_commit_keeps_what_was_verified(
            final boolean unsigned, @TempDir final Path dir) throws Exception {
        // A forge writer without a project's signing key must not replace its access settings.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        final Path stranger = key(dir, "stranger");
        pin(fixture.context(), "another-project", stranger);
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), unsigned ? null : stranger);

        final Reconcile.Result refused = reconcile(fixture.context());

        assertThat(refused.outcome())
                .as("the project itself is unchanged")
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(refused.accessOutcome())
                .as(refused.detail())
                .isEqualTo(unsigned ? "NOT_SIGNED" : "UNKNOWN_KEY");
        assertThat(refused.accessCommit())
                .as("the previous access commit remains in force")
                .isEqualTo(first.accessCommit());
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("a refused access commit never moves the clone")
                .isEqualTo(first.accessCommit());
        assertThat(Files.readString(accessClone(fixture).resolve("access.yml")))
                .as("a key pinned for another project cannot change these settings")
                .isEqualTo("{}\n");
        assertThat(projects(fixture.context()).require("demo").needsAPerson())
                .as("the access refusal needs a person's attention")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void access_checks_the_range_and_allows_unsigned_documents(
            final boolean protectedChange, @TempDir final Path dir) throws Exception {
        // A signed tip cannot hide an unsigned access rule earlier in the range.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        Files.writeString(
                fixture.access().resolve(protectedChange ? "access.yml" : "notes.md"),
                "# unsigned change\n");
        commit(fixture.access(), null);
        final String unsigned = git(fixture.access(), "rev-parse", "HEAD");
        if (protectedChange) {
            Files.writeString(fixture.access().resolve("notes.md"), "signed notes\n");
            commit(fixture.access(), fixture.key());
        }

        final Reconcile.Result preview =
                new FollowSignedBy(fixture.context())
                        .check("demo", fixture.project().toString(), null, false)
                        .result();
        final Reconcile.Result multiple =
                new FollowSignedBy(fixture.context())
                        .check(
                                "demo",
                                fixture.project().toString(),
                                List.of(publicKey(fixture.key())))
                        .result();
        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(multiple.accessOutcome())
                .as("several anchors keep the same access baseline")
                .isEqualTo(result.accessOutcome());
        assertThat(preview.accessOutcome())
                .as("a dry run checks every access commit since the one in force")
                .isEqualTo(result.accessOutcome());
        assertThat(preview.accessCommit())
                .as("the access commit in force is shared with the dry run")
                .isEqualTo(result.accessCommit());
        assertThat(result.accessOutcome()).isEqualTo(protectedChange ? "NOT_SIGNED" : "APPLIED");
        assertThat(result.accessRefused()).isEqualTo(protectedChange ? unsigned : "");
        assertThat(result.accessRefusedFile()).isEqualTo(protectedChange ? "access.yml" : "");
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .isEqualTo(protectedChange ? first.accessCommit() : unsigned);
        assertThat(projects(fixture.context()).require("demo").accessRefused())
                .isEqualTo(result.accessRefused());
        if (protectedChange) {
            git(fixture.access(), "reset", "--hard", first.accessCommit());
            Files.writeString(fixture.access().resolve("access.yml"), "{}\n# repaired access\n");
            commit(fixture.access(), fixture.key());
            final Reconcile.Result repaired = reconcile(fixture.context());
            assertThat(repaired.accessOutcome()).as(repaired.detail()).isEqualTo("APPLIED");
            final FollowedProjects.Followed stored = projects(fixture.context()).require("demo");
            assertThat(stored.accessRefused())
                    .as("a valid access commit clears the refused commit")
                    .isEmpty();
            assertThat(stored.accessRefusedFile())
                    .as("a valid access commit clears the refused path")
                    .isEmpty();
        }
    }

    @Test
    void an_unreadable_anchor_keeps_an_existing_access_refusal(@TempDir final Path dir)
            throws Exception {
        // A failure before retrying access must not erase the commit and path awaiting review.
        final Fixture fixture = fixture(dir);
        reconcile(fixture.context());
        Files.writeString(fixture.access().resolve("access.yml"), "{}\n# unsigned access\n");
        commit(fixture.access(), null);
        final Reconcile.Result refused = reconcile(fixture.context());
        assertThat(refused.accessOutcome()).as(refused.detail()).isEqualTo("NOT_SIGNED");
        final Path signers = fixture.context().paths().projects().configurationSigners();
        final var permissions = Files.getPosixFilePermissions(signers);
        try {
            Files.setPosixFilePermissions(signers, java.util.Set.of());
            final Reconcile.Result result =
                    new FollowSignedBy(fixture.context())
                            .follow("demo", fixture.project().toString(), null, false, false)
                            .result();
            assertThat(result.outcome())
                    .as(result.detail())
                    .isEqualTo(Reconcile.Outcome.UNREADABLE);
            assertThat(result.accessRefused())
                    .as("the access refusal was never retried")
                    .isEqualTo(refused.accessRefused());
            assertThat(result.accessRefusedFile())
                    .as("the refused access path is retained")
                    .isEqualTo("access.yml");
            final FollowedProjects.Followed stored = projects(fixture.context()).require("demo");
            assertThat(stored.accessRefused())
                    .as("the refusal survives storage")
                    .isEqualTo(refused.accessRefused());
            assertThat(stored.accessRefusedFile())
                    .as("the path survives storage")
                    .isEqualTo("access.yml");
        } finally {
            Files.setPosixFilePermissions(signers, permissions);
        }
    }

    @Test
    void a_signed_access_rewrite_keeps_the_commit_in_force(@TempDir final Path dir)
            throws Exception {
        // Replaying an older signed configuration must not restore access that was withdrawn.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        git(fixture.access(), "checkout", "--orphan", "rewritten");
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), fixture.key());

        final Reconcile.Result refused = reconcile(fixture.context());

        assertThat(refused.accessOutcome()).as(refused.detail()).isEqualTo("REWRITTEN");
        assertThat(refused.detail())
                .as("the operator can deliberately accept the new history")
                .contains("--accept-rewrite");
        assertThat(refused.accessCommit())
                .as("the trusted history stays in force")
                .isEqualTo(first.accessCommit());
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("even a signed rewrite cannot move the clone")
                .isEqualTo(first.accessCommit());
    }

    @Test
    void a_key_removed_by_the_project_cannot_sign_the_next_access_commit(@TempDir final Path dir)
            throws Exception {
        // A retired signer must lose access authority in the same pass as its retirement.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        final Path successor = key(dir, "successor");
        project(fixture.project(), fixture.access().toString(), "retired the first key", successor);
        commit(fixture.project(), fixture.key());
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), fixture.key());

        final Reconcile.Result refused = reconcile(fixture.context());

        assertThat(refused.outcome())
                .as("the old key may sign the handover itself")
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(fixture.context().paths().projects().configurationSigners()))
                .as("the old key remains only for history")
                .contains("demo " + publicKey(successor))
                .contains("valid-before=")
                .contains(publicKey(fixture.key()));
        assertThat(refused.accessOutcome()).as(refused.detail()).isEqualTo("UNKNOWN_KEY");
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("the handover's signer cannot authorize access after retiring itself")
                .isEqualTo(first.accessCommit());
    }

    @Test
    void an_active_access_signer_in_the_account_krl_is_refused(@TempDir final Path dir)
            throws Exception {
        // Revoking a key must also close the independent access-repository path.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        Files.writeString(fixture.access().resolve("access.yml"), "# changed access\n{}\n");
        commit(fixture.access(), fixture.key());
        final Path krl = fixture.context().paths().projects().revocations();
        final var made =
                runner.run(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-k",
                                "-f",
                                krl.toString(),
                                fixture.key() + ".pub"));
        assertThat(made.successful()).as(made.standardError()).isTrue();

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(result.accessOutcome()).isEqualTo("UNKNOWN_KEY");
        assertThat(result.accessRefused()).isEqualTo(git(fixture.access(), "rev-parse", "HEAD"));
        assertThat(result.accessRefusedFile()).isEqualTo("access.yml");
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD")).isEqualTo(first.accessCommit());
    }

    @Test
    void the_successor_can_sign_access_in_the_pass_that_hands_over_to_it(@TempDir final Path dir)
            throws Exception {
        // Verifying access against the keys before a handover would reject its authorized
        // successor.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        final Path successor = key(dir, "successor");
        project(fixture.project(), fixture.access().toString(), "handed on", successor);
        commit(fixture.project(), fixture.key());
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), successor);

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("APPLIED");
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("the successor's verified access commit is checked out")
                .isEqualTo(git(fixture.access(), "rev-parse", "HEAD"));
    }

    @Test
    void a_modified_working_tree_is_not_the_access_file_in_force(@TempDir final Path dir)
            throws Exception {
        // A local edit must not acquire the authority of the signed project commit beside it.
        final PoreloContext context = context(dir);
        final Path signer = key(dir, "operator");
        pin(context, "demo", signer);
        final Path repo = repository(dir, "project");
        project(repo, "", "beside project");
        Files.writeString(repo.resolve("access.yml"), "{}\n");
        commit(repo, signer);
        projects(context).follow("demo", repo.toString());
        final Reconcile.Result first = reconcile(context);
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        final Path file = context.paths().projects().followedClone("demo").resolve("access.yml");
        Files.writeString(file, "transports: [this is not an access mapping\n");

        final Reconcile.Result again = reconcile(context);

        assertThat(again.accessOutcome())
                .as("the verified commit is readable despite the local edit")
                .isEqualTo("UNCHANGED");
        assertThat(again.accessCommit())
                .as("the access file belongs to the verified project commit")
                .isEqualTo(first.commit());
        assertThat(Files.readString(file))
                .as("the invalid working copy was present during the check")
                .contains("not an access mapping");
    }

    @Test
    void an_untracked_access_file_cannot_fill_a_missing_signed_file(@TempDir final Path dir)
            throws Exception {
        // A file that no trusted commit contains must never supply settings.
        final PoreloContext context = context(dir);
        final Path signer = key(dir, "operator");
        pin(context, "demo", signer);
        final Path repo = repository(dir, "project");
        project(repo, "", "missing access file");
        commit(repo, signer);
        projects(context).follow("demo", repo.toString());
        final Reconcile.Result first = reconcile(context);
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("UNUSABLE");
        Files.writeString(
                context.paths().projects().followedClone("demo").resolve("access.yml"), "{}\n");

        final Reconcile.Result again = reconcile(context);

        assertThat(again.accessOutcome())
                .as("untracked bytes are not covered by the signature")
                .isEqualTo("UNUSABLE");
        assertThat(again.accessCommit()).as("no signed access file has been applied").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_refused_project_commit_applies_no_access_file(
            final boolean separateRepository, @TempDir final Path dir) throws Exception {
        // Untrusted project bytes may neither select an access repository nor vouch for a local
        // file.
        final Fixture fixture = fixture(dir);
        project(
                fixture.project(),
                separateRepository ? fixture.access().toString() : "",
                "untrusted");
        Files.writeString(fixture.project().resolve("access.yml"), "{}\n");
        commit(fixture.project(), key(dir, "stranger"));

        final Reconcile.Result refused = reconcile(fixture.context());

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.accessCommit())
                .as("the project signature failed before any access was applied")
                .isEmpty();
        assertThat(refused.accessOutcome()).as("an access attempt was never authorized").isEmpty();
        assertThat(accessClone(fixture)).as("an untrusted URL is never fetched").doesNotExist();
        assertThat(fixture.context().paths().projects().followedClone("demo").resolve("access.yml"))
                .as("untrusted project files are not checked out")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_missing_access_file_is_unusable_and_puts_nothing_in_force(
            final boolean separateRepository, @TempDir final Path dir) throws Exception {
        // Successful signature verification cannot stand in for the existence of the settings file.
        final Fixture fixture = fixture(dir);
        if (separateRepository) {
            git(fixture.access(), "rm", "access.yml");
            commit(fixture.access(), fixture.key());
        } else {
            project(fixture.project(), "", "missing beside project");
            commit(fixture.project(), fixture.key());
        }

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.outcome())
                .as("the project is independently usable")
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("UNUSABLE");
        assertThat(result.accessCommit())
                .as("a missing access file puts no commit in force")
                .isEmpty();
        assertThat(result.detail())
                .as("the refusal names what could not be read")
                .contains("access.yml");
        if (separateRepository) {
            assertThat(accessClone(fixture).resolve("access.yml"))
                    .as("the access clone is never reset to an unusable commit")
                    .doesNotExist();
        }
    }

    @Test
    void an_unreachable_access_repository_keeps_its_commit(@TempDir final Path dir)
            throws Exception {
        // A network failure must not discard the access settings that passed verification before
        // it.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        Files.move(fixture.access(), dir.resolve("unavailable"));

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.outcome())
                .as("access unavailability does not change the project's outcome")
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("UNREACHABLE");
        assertThat(result.accessCommit())
                .as("the last verified access commit stays in force")
                .isEqualTo(first.accessCommit());
        assertThat(result.detail())
                .as("the refusal says which access commit is kept")
                .contains("keeping access commit " + first.accessCommit());
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("a failed fetch leaves the checkout alone")
                .isEqualTo(first.accessCommit());
    }

    @Test
    void removing_the_access_source_forgets_the_access_commit(@TempDir final Path dir)
            throws Exception {
        // Settings withdrawn by the signed project must not remain recorded as in force.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        project(fixture.project(), null, "no access source");
        commit(fixture.project(), fixture.key());

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.accessCommit()).as("the project withdrew its access source").isEmpty();
        assertThat(result.accessOutcome())
                .as("there is no access attempt for a project without access")
                .isEmpty();
        assertThat(projects(fixture.context()).require("demo").accessCommit())
                .as("the stored follow forgets the withdrawn access commit")
                .isEmpty();
    }

    @Test
    void moving_the_access_file_between_its_places_is_no_rewrite(@TempDir final Path dir)
            throws Exception {
        // A commit of one repository never descends from one of another; asking so stopped a move.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        Files.writeString(fixture.project().resolve("access.yml"), "{}\n");
        project(fixture.project(), "", "access beside the project");
        commit(fixture.project(), fixture.key());
        final Reconcile.Result beside = reconcile(fixture.context());
        assertThat(beside.accessOutcome()).as(beside.detail()).isEqualTo("APPLIED");
        assertThat(beside.accessCommit())
                .as("the project commit that carries the file beside it")
                .isEqualTo(git(fixture.project(), "rev-parse", "HEAD"));
        project(fixture.project(), fixture.access().toString(), "access in its repository again");
        commit(fixture.project(), fixture.key());

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("APPLIED");
        assertThat(result.accessCommit())
                .as("the access repository's commit, not refused as a rewrite of a project commit")
                .isEqualTo(first.accessCommit());
    }

    @Test
    void a_new_access_repository_that_is_not_verified_puts_nothing_in_force(@TempDir final Path dir)
            throws Exception {
        // The old repository's commit would otherwise be served for a project that names another.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        final Path elsewhere = repository(dir, "elsewhere");
        Files.writeString(elsewhere.resolve("access.yml"), "{}\n");
        commit(elsewhere, key(dir, "stranger"));
        project(fixture.project(), elsewhere.toString(), "access elsewhere");
        commit(fixture.project(), fixture.key());

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("UNKNOWN_KEY");
        assertThat(result.accessCommit())
                .as("nothing of the repository the project no longer names")
                .isEmpty();
    }

    @Test
    void an_unverified_follow_also_takes_access_without_a_signature(@TempDir final Path dir)
            throws Exception {
        // The recorded trust mode must describe both files rather than silently treating them
        // differently.
        final Fixture fixture = fixture(dir);
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), null);
        projects(fixture.context()).follow("demo", fixture.project().toString(), true);

        final Reconcile.Result result = reconcile(fixture.context());

        assertThat(result.accessOutcome()).as(result.detail()).isEqualTo("APPLIED");
        assertThat(git(accessClone(fixture), "rev-parse", "HEAD"))
                .as("the explicit unverified mode includes the access repository")
                .isEqualTo(git(fixture.access(), "rev-parse", "HEAD"));
        assertThat(projects(fixture.context()).require("demo").unverified())
                .as("the follow never claims that a signature was checked")
                .isTrue();
    }

    @Test
    void a_check_keeps_both_clones_records_keys_and_events_untouched(@TempDir final Path dir)
            throws Exception {
        // A preview must not apply access settings or leave a successor trusted on the machine.
        final Fixture fixture = fixture(dir);
        final Path successor = key(dir, "successor");
        project(fixture.project(), fixture.access().toString(), "handed on", successor);
        commit(fixture.project(), fixture.key());
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), successor);
        final String keys =
                Files.readString(fixture.context().paths().projects().configurationSigners());
        final FollowedProjects.Followed before = projects(fixture.context()).require("demo");

        final Reconcile.Result checked = new Reconcile(fixture.context()).check(before);

        assertThat(checked.accessOutcome()).as(checked.detail()).isEqualTo("APPLIED");
        assertThat(fixture.context().paths().projects().followedClone("demo"))
                .as("a check creates no project clone in durable state")
                .doesNotExist();
        assertThat(accessClone(fixture))
                .as("a check creates no access clone in durable state")
                .doesNotExist();
        assertThat(projects(fixture.context()).require("demo"))
                .as("a check records neither commit")
                .isEqualTo(before);
        assertThat(Files.readString(fixture.context().paths().projects().configurationSigners()))
                .as("a checked handover pins no key on the machine")
                .isEqualTo(keys);
        assertThat(fixture.context().paths().eventLog())
                .as("a preview applies nothing to log")
                .doesNotExist();
    }

    @Test
    void access_changes_are_events_even_when_the_project_is_unchanged(@TempDir final Path dir)
            throws Exception {
        // An access-only update must leave an audit event identifying the settings and their
        // signer.
        final Fixture fixture = fixture(dir);
        final Reconcile.Result first = reconcile(fixture.context());
        assertThat(first.accessOutcome()).as(first.detail()).isEqualTo("APPLIED");
        Files.writeString(
                fixture.access().resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n");
        commit(fixture.access(), fixture.key());

        final Reconcile.Result updated = reconcile(fixture.context());
        reconcile(fixture.context());

        assertThat(updated.outcome())
                .as("only the access repository changed")
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(EventLog.of(fixture.context()).entries(null))
                .as(
                        "one project event and two access events, without a duplicate for unchanged"
                                + " access")
                .hasSize(3)
                .last()
                .satisfies(
                        event -> {
                            assertThat(event.get("kind"))
                                    .as("access applies share the project audit kind")
                                    .isEqualTo(EventLog.PROJECT_APPLIED);
                            assertThat(event.get("detail"))
                                    .as("the event names the access source, commit and signer")
                                    .isEqualTo(
                                            "access file in "
                                                    + fixture.access()
                                                    + " commit "
                                                    + updated.accessCommit()
                                                    + ", signed by "
                                                    + SignedBy.fingerprintOf(
                                                            publicKey(fixture.key())));
                        });
    }

    @Test
    void a_follow_logs_the_access_file_beside_the_project(@TempDir final Path dir)
            throws Exception {
        // A terminal follow writes its record before logging; that must not hide the access apply.
        final PoreloContext context = context(dir);
        final Path signer = key(dir, "operator");
        final Path repo = repository(dir, "project");
        project(repo, "", "beside project");
        Files.writeString(repo.resolve("access.yml"), "{}\n");
        commit(repo, signer);

        final FollowSignedBy.Answer followed =
                new FollowSignedBy(context)
                        .follow("demo", repo.toString(), publicKey(signer), false, false);

        assertThat(followed.result().accessOutcome())
                .as(followed.result().detail())
                .isEqualTo("APPLIED");
        assertThat(EventLog.of(context).entries(null))
                .as("both applied files are audited")
                .hasSize(2)
                .last()
                .satisfies(
                        event ->
                                assertThat(event.get("detail"))
                                        .as(
                                                "the event identifies the signed file in the"
                                                        + " project commit")
                                        .isEqualTo(
                                                "access file beside project.yml, commit "
                                                        + followed.result().commit()
                                                        + ", signed by "
                                                        + SignedBy.fingerprintOf(
                                                                publicKey(signer))));
    }

    private record Fixture(PoreloContext context, Path key, Path project, Path access) {}

    private Fixture fixture(final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path signer = key(dir, "operator");
        pin(context, "demo", signer);
        final Path access = repository(dir, "access");
        Files.writeString(access.resolve("access.yml"), "{}\n");
        commit(access, signer);
        final Path project = repository(dir, "project");
        project(project, access.toString(), "initial");
        commit(project, signer);
        projects(context).follow("demo", project.toString());
        return new Fixture(context, signer, project, access);
    }

    private PoreloContext context(final Path dir) {
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
                    return runner.run(command);
                },
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private FollowedProjects projects(final PoreloContext context) {
        return new FollowedProjects(context.paths().projects().followed());
    }

    private Path accessClone(final Fixture fixture) {
        return fixture.context().paths().projects().followedAccessClone("demo");
    }

    private Reconcile.Result reconcile(final PoreloContext context) throws IOException {
        final FollowedProjects.Followed before = projects(context).require("demo");
        final Reconcile.Result result = new Reconcile(context).run(before);
        projects(context).write(Reconcile.after(before, result));
        Reconcile.recordApplied(context, before, result);
        return result;
    }

    private Path repository(final Path dir, final String name) throws IOException {
        final Path repo = Files.createDirectories(dir.resolve(name));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "user.email", "operator@example.org");
        return repo;
    }

    private void project(
            final Path repo,
            final String accessUrl,
            final String description,
            final Path... signers)
            throws IOException {
        final StringBuilder named = new StringBuilder();
        if (signers.length > 0) {
            named.append("  signers:\n");
            for (final Path signer : signers) {
                named.append("    - \"").append(publicKey(signer)).append("\"\n");
            }
        }
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: demo
                  description: %s
                  security_class: guarded
                %simage:
                  base_image: ubuntu:24.04
                %s
                """
                        .formatted(
                                description,
                                named,
                                accessUrl == null
                                        ? ""
                                        : accessUrl.isEmpty()
                                                ? "access: {file: access.yml}"
                                                : "access: {repository: \"" + accessUrl + "\"}"));
    }

    private void commit(final Path repo, final Path signingKey) {
        git(repo, "add", "--all");
        if (signingKey == null) {
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            git(
                    repo,
                    "-c",
                    "gpg.format=ssh",
                    "-c",
                    "user.signingkey=" + signingKey,
                    "commit",
                    "-q",
                    "-S",
                    "-m",
                    "configuration");
        }
    }

    private Path key(final Path dir, final String name) {
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
        assertThat(made.exitCode())
                .as("create a test signing key: " + made.standardError())
                .isZero();
        return key;
    }

    private String publicKey(final Path key) {
        try {
            final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
            return fields[0] + " " + fields[1];
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private void pin(final PoreloContext context, final String project, final Path key)
            throws IOException {
        final Path signers = context.paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        Files.writeString(
                signers,
                project + " " + publicKey(key) + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
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
