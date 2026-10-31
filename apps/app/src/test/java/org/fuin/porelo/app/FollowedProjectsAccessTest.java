package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps the access trust state durable without changing what old follow records mean. */
class FollowedProjectsAccessTest {

    @Test
    void the_access_commit_and_outcome_survive_writing_and_following_again(@TempDir final Path dir)
            throws IOException {
        // Losing the access commit would let a replay escape the descent check on the next pass.
        final FollowedProjects.Followed before =
                new FollowedProjects.Followed(
                        "demo",
                        "https://example.org/project.git",
                        "project-commit",
                        "",
                        "UNCHANGED",
                        "",
                        "",
                        "",
                        false,
                        "access-commit",
                        "UNKNOWN_KEY",
                        "project.yml",
                        "access-refused",
                        "access.yml");
        new FollowedProjects(dir).write(before);

        final FollowedProjects reread = new FollowedProjects(dir);

        assertThat(reread.require("demo"))
                .as("both access fields round-trip through JSON")
                .isEqualTo(before);
        reread.follow("demo", before.url(), true);
        assertThat(new FollowedProjects(dir).require("demo").accessCommit())
                .as("following again does not forget the replay anchor")
                .isEqualTo("access-commit");
        assertThat(new FollowedProjects(dir).require("demo").accessOutcome())
                .as("following again retains the last access refusal")
                .isEqualTo("UNKNOWN_KEY");
        assertThat(reread.require("demo").refusedFile()).isEqualTo("project.yml");
        assertThat(reread.require("demo").accessRefused()).isEqualTo("access-refused");
        assertThat(reread.require("demo").accessRefusedFile()).isEqualTo("access.yml");
    }

    @Test
    void an_old_record_has_no_access_commit_or_outcome(@TempDir final Path dir) throws IOException {
        // Existing installations must not invent access authority when their records are upgraded.
        Files.writeString(
                dir.resolve("demo.json"),
                """
                {"name":"demo","url":"https://example.org/project.git","commit":"project-commit",
                 "at":"","outcome":"APPLIED","detail":"","refused":"","signer":"","unverified":false}
                """);

        final FollowedProjects.Followed old = new FollowedProjects(dir).require("demo");

        assertThat(old.refusedFile()).isEmpty();
        assertThat(old.accessRefused()).isEmpty();
        assertThat(old.accessRefusedFile()).isEmpty();
        assertThat(old.accessCommit()).as("an old record has applied no access commit").isEmpty();
        assertThat(old.accessOutcome())
                .as("an old record has attempted no access reconcile")
                .isEmpty();
        assertThat(old.commit())
                .as("the project commit remains in force")
                .isEqualTo("project-commit");
    }

    @Test
    void accepting_a_rewrite_forgets_both_commits_and_keeps_the_trust_mode() {
        // Keeping the access replay anchor would make an explicitly accepted rewrite unusable.
        final FollowedProjects.Followed before =
                new FollowedProjects.Followed(
                        "demo",
                        "https://example.org/project.git",
                        "project-commit",
                        "",
                        "UNCHANGED",
                        "",
                        "",
                        "",
                        true,
                        "access-commit",
                        "REWRITTEN");

        final FollowedProjects.Followed after = before.forgettingWhatIsInForce();

        assertThat(after.commit()).as("the project rewrite was accepted").isEmpty();
        assertThat(after.accessCommit()).as("the access rewrite was accepted too").isEmpty();
        assertThat(after.unverified())
                .as("accepting a rewrite must not claim a signature was checked")
                .isTrue();
        assertThat(after.accessOutcome())
                .as("the last attempt remains visible until the next pass")
                .isEqualTo("REWRITTEN");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "NOT_SIGNED",
                "UNKNOWN_KEY",
                "NO_ANCHOR",
                "UNREADABLE",
                "REWRITTEN",
                "UNUSABLE",
                "VAULT_LOCKED"
            })
    void an_access_refusal_needs_a_person_even_when_the_project_applied(final String outcome) {
        // A successful project update must not hide an access failure that cannot heal itself.
        final FollowedProjects.Followed followed =
                new FollowedProjects.Followed(
                        "demo",
                        "https://example.org/project.git",
                        "project-commit",
                        "",
                        "APPLIED",
                        "",
                        "",
                        "",
                        false,
                        "access-commit",
                        outcome);

        assertThat(followed.needsAPerson())
                .as("the access outcome " + outcome + " needs action")
                .isTrue();
        assertThat(
                        new Reconcile.Result(
                                        Reconcile.Outcome.APPLIED,
                                        "project-commit",
                                        "",
                                        "",
                                        "",
                                        "access-commit",
                                        outcome)
                                .needsAPerson())
                .as("the immediate result exposes the same access refusal")
                .isTrue();
    }
}
