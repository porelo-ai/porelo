package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;
import org.jspecify.annotations.Nullable;

/**
 * Steps for a task's runs at a real pull request on GitHub, read and written by the installed
 * {@code github} forge adapter: the test's own calls to GitHub go from the host the suite runs on,
 * and the runs, holds and releases are those of the stub forge's scenario.
 */
public final class GitHubReviewSteps {

    private final World world;

    private final PullRequestReviewSteps review;

    private @Nullable GitHubScratch github;

    /**
     * Creates the steps over the scenario's world and the pull request steps they run with.
     *
     * @param world The scenario's world.
     * @param review The steps that run, hold and release at a pull request.
     */
    public GitHubReviewSteps(World world, PullRequestReviewSteps review) {
        this.world = world;
        this.review = review;
    }

    /**
     * Reads the test identity from the suite's environment, failing the scenario loud, never
     * skipping it, when a variable is missing.
     *
     * @param token The variable holding the token.
     * @param organization The variable holding the test organization.
     * @throws IOException If no work directory can be made.
     */
    @Given("the GitHub test identity is given by {string} and {string}")
    public void theTestIdentity(String token, String organization) throws IOException {
        final GitHubScratch.Identity identity =
                GitHubScratch.Identity.of(System::getenv, token, organization);
        // Remembered as a secret, so every failure the world shows has it replaced.
        world.secret(token);
        final Path target = Path.of("target");
        Files.createDirectories(target);
        github = new GitHubScratch(identity, Files.createTempDirectory(target, "github-scratch-"));
    }

    /**
     * Sets the forge file to read a host with an installed adapter; a missing adapter stops the
     * scenario with Porelo's own answer.
     *
     * @param adapter The adapter.
     * @param host The forge's host.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the installed forge adapter {string} reads {string}")
    public void theAdapterReads(String adapter, String host) throws IOException {
        final Ssh.Output output =
                world.run("porelo forge add " + quote(host) + " --adapter " + quote(adapter));
        world.output(output);
        requireAdapter(output, adapter, host, world::redact);
    }

    /**
     * Fails when Porelo did not set the forge file, naming the adapter and showing what Porelo
     * answered.
     *
     * @param output What {@code porelo forge add} printed.
     * @param adapter The adapter.
     * @param host The forge's host.
     * @param redact What removes secrets from a text shown.
     */
    static void requireAdapter(
            Ssh.Output output, String adapter, String host, UnaryOperator<String> redact) {
        if (output.status() != 0) {
            throw new AssertionError(
                    "the forge adapter "
                            + adapter
                            + " cannot read "
                            + host
                            + " in the suite's account; install it there first. porelo forge add"
                            + " exited "
                            + output.status()
                            + ":\n"
                            + redact.apply(output.all()));
        }
    }

    /**
     * Makes a scratch repository in the test organization and opens a pull request in it.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    @Given("a scratch repository in the test organization has a pull request on GitHub")
    public void aPullRequestOnGitHub() throws IOException {
        github().open();
    }

    /**
     * Has the stub agent submit a fixture's submission in its next run, committed to the pull
     * request's head on GitHub.
     *
     * @param fixture The submission fixture's file name.
     * @throws IOException If GitHub cannot be reached.
     */
    @Given("in its next run the stub agent submits {string} at the pull request on GitHub")
    public void theStubSubmits(String fixture) throws IOException {
        final GitHubScratch scratch = github();
        scratch.commitToHead(
                StubReview.FILE, StubReview.submit(fixture, scratch.url(), scratch.base(), null));
    }

    /**
     * Has the stub agent answer every open thread it is given in its next run, committed to the
     * pull request's head on GitHub.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    @Given(
            "in its next run the stub agent replies to every open thread it is given at the pull"
                    + " request on GitHub")
    public void theStubReplies() throws IOException {
        final GitHubScratch scratch = github();
        scratch.commitToHead(StubReview.FILE, StubReview.replies(scratch.url(), scratch.base()));
    }

    /**
     * Runs a task at the pull request on GitHub with the stub agent.
     *
     * @param task The task's name.
     * @param role {@code reviewer} or {@code author}.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task {string} is run by the stub agent as {word} at the pull request on GitHub")
    public void aRun(String task, String role) throws IOException {
        review.aRun(task, role, github().url());
    }

    /**
     * Asserts that no review comment reached the pull request.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    @Then("the pull request on GitHub has no review comment")
    public void noReviewComment() throws IOException {
        assertThat(github().comments()).as("the review comments on GitHub").isEmpty();
    }

    /**
     * Posts the test identity's answer in the task's thread, as a forge account's would be.
     *
     * @param text What it says.
     * @throws IOException If GitHub cannot be reached.
     */
    @When("the test identity answers the task's thread on GitHub: {string}")
    public void theTestIdentityAnswers(String text) throws IOException {
        github().answer(text);
    }

    /**
     * Asserts the task's thread on GitHub, read back: each comment, in order, holds its text.
     *
     * @param texts The texts, the root's first.
     * @throws IOException If GitHub cannot be reached.
     */
    @Then("the task's thread on GitHub reads, in order:")
    public void theThreadReads(List<String> texts) throws IOException {
        final List<GitHubScratch.Comment> thread = github().thread(texts.size());
        assertThat(thread).as("the comments of the task's thread on GitHub").hasSize(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            assertThat(thread.get(i).body())
                    .as("comment %d of the task's thread on GitHub", i + 1)
                    .contains(texts.get(i));
        }
    }

    /**
     * Asserts that every comment of the task's thread went out under the test identity's account.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    @Then("every comment of the task's thread on GitHub is the test identity's")
    public void everyCommentIsTheIdentitys() throws IOException {
        final GitHubScratch scratch = github();
        assertThat(scratch.thread(1).stream().map(GitHubScratch.Comment::login))
                .as("the accounts the task's thread on GitHub was written under")
                .isNotEmpty()
                .containsOnly(scratch.login());
    }

    /**
     * Removes the scratch repository, even when an earlier step failed.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    @After("@github")
    public void removeTheRepository() throws IOException {
        final GitHubScratch scratch = github;
        if (scratch != null) {
            github = null;
            scratch.delete();
        }
    }

    private GitHubScratch github() {
        final GitHubScratch scratch = github;
        if (scratch == null) {
            throw new AssertionError(
                    "No test identity yet: start with 'Given the GitHub test identity ...'");
        }
        return scratch;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
