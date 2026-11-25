package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.fuin.porelo.machines.Ssh;
import org.junit.jupiter.api.Test;

/**
 * The glue of the scenario on GitHub, without GitHub: its steps are all defined, the test identity
 * is required, the token stays out of every argument and text, and a thread is read back in order.
 */
class GitHubReviewTest {

    private static final String TOKEN = "github-test-value-never-shown";

    private static final String ORGANIZATION = "example-org";

    @Test
    void everyStepOfTheGitHubFeatureHasItsGlue() {
        final byte exit =
                io.cucumber.core.cli.Main.run(
                        new String[] {
                            "--dry-run",
                            "--glue",
                            "org.fuin.porelo.acceptance",
                            "--plugin",
                            "summary",
                            "classpath:org/fuin/porelo/acceptance/gate/pr-review-github.feature"
                        },
                        Thread.currentThread().getContextClassLoader());

        assertThat(exit)
                .as("a dry run of pr-review-github.feature finds an undefined or ambiguous step")
                .isZero();
    }

    @Test
    void aMissingTokenFailsTheScenarioNamingItsVariable() {
        assertThatThrownBy(
                        () ->
                                GitHubScratch.Identity.of(
                                        Map.of("PORELO_GITHUB_TEST_ORG", ORGANIZATION)::get,
                                        "PORELO_GITHUB_TEST_TOKEN",
                                        "PORELO_GITHUB_TEST_ORG"))
                .as("the identity without a token")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PORELO_GITHUB_TEST_TOKEN");
    }

    @Test
    void aBlankOrganizationFailsTheScenarioNamingItsVariable() {
        assertThatThrownBy(
                        () ->
                                GitHubScratch.Identity.of(
                                        Map.of(
                                                        "PORELO_GITHUB_TEST_TOKEN",
                                                        TOKEN,
                                                        "PORELO_GITHUB_TEST_ORG",
                                                        " ")
                                                ::get,
                                        "PORELO_GITHUB_TEST_TOKEN",
                                        "PORELO_GITHUB_TEST_ORG"))
                .as("the identity with a blank organization")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PORELO_GITHUB_TEST_ORG")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void theIdentityAndTheHelperNeverShowTheToken() {
        final GitHubScratch.Identity identity = new GitHubScratch.Identity(TOKEN, ORGANIZATION);

        assertThat(identity.toString()).as("the identity's text").doesNotContain(TOKEN);
        assertThat(new GitHubScratch(identity, Path.of("target", "unused")).toString())
                .as("the helper's text")
                .doesNotContain(TOKEN);
    }

    @Test
    void gitIsGivenTheTokenInItsEnvironmentAndNeverInAnArgument() {
        final Map<String, String> environment = GitHubScratch.gitEnvironment(TOKEN);
        final List<String> command =
                GitHubScratch.gitCommand(
                        Path.of("target", "checkout"),
                        List.of(
                                "push",
                                "-q",
                                "https://github.com/example-org/scratch.git",
                                "main"));

        assertThat(command).as("git's arguments").noneMatch(one -> one.contains(TOKEN));
        assertThat(command).as("git's arguments").noneMatch(one -> one.contains(basic()));
        assertThat(environment)
                .as("git's environment")
                .containsEntry("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader")
                .containsEntry("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic())
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .containsEntry("GIT_CONFIG_KEY_1", "credential.helper")
                .containsEntry("GIT_CONFIG_VALUE_1", "");
    }

    @Test
    void aFailureShowsNeitherTheTokenNorItsHeader() {
        final String shown =
                GitHubScratch.hide(
                        "fatal: header Authorization: Basic " + basic() + " and " + TOKEN, TOKEN);

        assertThat(shown).as("the failure shown").doesNotContain(TOKEN).doesNotContain(basic());
    }

    @Test
    void theThreadIsItsRootAndEveryAnswerToItInTheOrderWritten() {
        final String json =
                """
                [
                  {"id": 30, "in_reply_to_id": 10, "user": {"login": "tester"}, "body": "second answer"},
                  {"id": 10, "user": {"login": "tester"}, "body": "the task's comment"},
                  {"id": 20, "in_reply_to_id": 10, "user": {"login": "tester"}, "body": "first answer"},
                  {"id": 40, "in_reply_to_id": null, "user": {"login": "tester"}, "body": "elsewhere"}
                ]
                """;

        final List<GitHubScratch.Comment> comments = GitHubScratch.comments(json);
        final List<GitHubScratch.Comment> thread = GitHubScratch.thread(comments, 10);

        assertThat(thread.stream().map(GitHubScratch.Comment::body))
                .as("the thread read back")
                .containsExactly("the task's comment", "first answer", "second answer");
        assertThat(thread.stream().map(GitHubScratch.Comment::login))
                .as("the accounts of the thread")
                .containsOnly("tester");
    }

    @Test
    void moreThanOneThreadHasNoRootToAnswer() {
        final List<GitHubScratch.Comment> comments =
                GitHubScratch.comments(
                        """
                        [
                          {"id": 10, "user": {"login": "tester"}, "body": "one"},
                          {"id": 11, "user": {"login": "tester"}, "body": "two"}
                        ]
                        """);

        assertThatThrownBy(() -> GitHubScratch.root(comments))
                .as("the root of two threads")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("2 threads");
    }

    @Test
    void thePullRequestsHeadChangesTheLinesTheFixturesAnchorIn() {
        final String[] base = GitHubScratch.lines(false).split("\n");
        final String[] head = GitHubScratch.lines(true).split("\n");

        // The comment anchors at lines 27 and 28, the reply at 41 to 43: GitHub takes a comment
        // only on a line inside the pull request's diff.
        for (final int line : new int[] {27, 28, 41, 42, 43}) {
            assertThat(head[line - 1]).as("line %d of the head", line).isNotEqualTo(base[line - 1]);
        }
        assertThat(head).as("the head's lines").hasSize(base.length);
    }

    @Test
    void aMissingAdapterStopsTheScenarioWithPorelosAnswer() {
        final Ssh.Output refused =
                new Ssh.Output(
                        "",
                        "porelo: no forge adapter 'github' is installed; installed: stub-forge\n",
                        2);

        assertThatThrownBy(
                        () ->
                                GitHubReviewSteps.requireAdapter(
                                        refused, "github", "github.com", UnaryOperator.identity()))
                .as("the step when the adapter is not installed")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the forge adapter github cannot read github.com")
                .hasMessageContaining("no forge adapter 'github' is installed");
    }

    @Test
    void anInstalledAdapterLetsTheScenarioGoOn() {
        final Ssh.Output added = new Ssh.Output("added     github.com read by github\n", "", 0);

        GitHubReviewSteps.requireAdapter(added, "github", "github.com", UnaryOperator.identity());

        assertThat(added.status()).as("porelo forge add's exit").isZero();
    }

    @Test
    void thePullRequestsNumberIsReadFromGitHubsAnswer() {
        assertThat(GitHubScratch.pullRequestNumber("{\"number\": 7, \"title\": \"x\"}"))
                .as("the pull request's number")
                .isEqualTo(7L);
    }

    private static String basic() {
        return Base64.getEncoder()
                .encodeToString(("x-access-token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    }
}
