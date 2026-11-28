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
import java.util.UUID;
import org.fuin.porelo.acceptance.PoreloSteps;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;
import org.jspecify.annotations.Nullable;

/**
 * Steps for a task's runs at a real pull request on Forgejo, read and written by the installed
 * {@code forgejo} forge adapter: the test's own calls to Forgejo go from the host the suite runs
 * on, and the runs, holds and releases are those of the stub forge's scenario.
 */
public final class ForgejoReviewSteps {

    private final World world;

    private final PullRequestReviewSteps review;

    private final PoreloSteps credentials;

    private @Nullable ForgejoScratch forgejo;

    private @Nullable String certificateReceipt;

    /**
     * Creates the steps over the scenario's world and the pull request steps they run with.
     *
     * @param world The scenario's world.
     * @param review The steps that run, hold and release at a pull request.
     * @param credentials The shared steps that put credentials in the scenario's vault.
     */
    public ForgejoReviewSteps(World world, PullRequestReviewSteps review, PoreloSteps credentials) {
        this.world = world;
        this.review = review;
        this.credentials = credentials;
    }

    /**
     * Reads the required environment before any network call, remembering the token for redaction.
     */
    @Given("the Forgejo test identity is given by its environment")
    public void theTestIdentity() throws IOException {
        final ForgejoScratch.Identity identity = ForgejoScratch.Identity.of(System::getenv);
        world.secret("PORELO_FORGEJO_TEST_TOKEN");
        final Path target = Path.of("target");
        Files.createDirectories(target);
        forgejo =
                new ForgejoScratch(identity, Files.createTempDirectory(target, "forgejo-scratch-"));
    }

    /** Stores the token under the same host name the pull request reference uses. */
    @Given("the Forgejo test token is the credential of the test instance")
    public void theTokenIsTheInstancesCredential() throws IOException {
        final ForgejoScratch.Identity identity = ForgejoScratch.Identity.of(System::getenv);
        credentials.theVaultHolds(
                "PORELO_FORGEJO_TEST_TOKEN", "git.token." + identity.origin().getHost(), "api-key");
    }

    /** Registers the installed adapter for the URL's host, which also names its credential. */
    @Given("the installed forge adapter {string} reads the Forgejo test instance")
    public void theAdapterReads(String adapter) throws IOException {
        final ForgejoScratch.Identity identity = ForgejoScratch.Identity.of(System::getenv);
        review.theAdapterReads(adapter, identity.origin().getHost());
    }

    /** Appends the public CA through SSH and records cleanup before the remote mutation. */
    @Given("the forge adapter {string} trusts the test instance's certificate")
    public void theAdapterTrustsTheCertificate(String adapter) throws IOException {
        assertThat(adapter).as("the adapter of this fixture").isEqualTo("forgejo");
        final ForgejoScratch.Identity identity = ForgejoScratch.Identity.of(System::getenv);
        final String receipt = "target/forgejo-ca-" + UUID.randomUUID() + ".json";
        certificateReceipt = receipt;
        final Ssh.Output output =
                world.run(certificateCommand("append", receipt), Files.readString(identity.ca()));
        assertThat(output.status())
                .as("adding the test CA failed:%n%s", world.redact(output.all()))
                .isZero();
    }

    /** Runs after repository deletion, including when an earlier step or cleanup failed. */
    @After(value = "@forgejo", order = 90)
    public void removeTheTestCertificate() throws IOException {
        final String receipt = certificateReceipt;
        if (receipt != null) {
            final Ssh.Output output = world.run(certificateCommand("remove", receipt));
            assertThat(output.status())
                    .as("removing the test CA failed:%n%s", world.redact(output.all()))
                    .isZero();
            certificateReceipt = null;
        }
    }

    /** The same remote operation used by the step and by tests in a temporary account directory. */
    static String certificateCommand(String operation, String receipt) {
        return "python3 -c " + quote(CERTIFICATE) + " " + quote(operation) + " " + quote(receipt);
    }

    // A receipt is saved before append, so a lost SSH answer still leaves an exact cleanup.
    // The offset and bytes guard against removing another writer's certificate or later additions.
    private static final String CERTIFICATE =
            """
            import fcntl
            import json
            import os
            from pathlib import Path
            import re
            import sys

            os.umask(0o077)
            action, receipt = sys.argv[1], Path(sys.argv[2])
            if action == 'append':
                pem = sys.stdin.buffer.read().replace(b'\\r\\n', b'\\n').strip() + b'\\n'
                pattern = rb'-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----'
                certificates = re.findall(pattern, pem)
                if len(certificates) != 1 or certificates[0].strip() != pem.strip():
                    raise SystemExit('the test CA must be one PEM certificate')
                config = Path(os.environ.get('XDG_CONFIG_HOME', ''))
                if not config.is_absolute():
                    config = Path(os.environ['HOME']) / '.config'
                path = config / 'porelo' / 'ca-certificates.pem'
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                receipt.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                try:
                    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
                    created = True
                except FileExistsError:
                    descriptor = os.open(path, os.O_RDWR)
                    created = False
                with os.fdopen(descriptor, 'r+b') as store:
                    fcntl.flock(store, fcntl.LOCK_EX)
                    original = store.read()
                    normalized = re.sub(rb'\\s+', b'', pem)
                    present = any(re.sub(rb'\\s+', b'', cert) == normalized
                                  for cert in re.findall(pattern, original))
                    addition = b'' if present else ((b'\\n' if original and not original.endswith(b'\\n') else b'') + pem)
                    change = dict(path=str(path), created=created, offset=len(original),
                                  addition=addition.decode('ascii'))
                    with receipt.open('x', encoding='utf-8') as saved:
                        json.dump(change, saved)
                    store.write(addition)
                    store.flush()
                    os.fsync(store.fileno())
            elif action == 'remove':
                if not receipt.exists():
                    sys.exit(0)
                change = json.loads(receipt.read_text(encoding='utf-8'))
                path, addition = Path(change['path']), change['addition'].encode('ascii')
                if addition:
                    with path.open('r+b') as store:
                        fcntl.flock(store, fcntl.LOCK_EX)
                        current, offset = store.read(), change['offset']
                        if current[offset:offset + len(addition)] != addition:
                            raise SystemExit('the appended test CA changed; refusing to remove other lines')
                        remaining = current[:offset] + current[offset + len(addition):]
                        store.seek(0)
                        store.write(remaining)
                        store.truncate()
                        store.flush()
                        os.fsync(store.fileno())
                        if change['created'] and not remaining:
                            path.unlink()
                receipt.unlink()
            else:
                raise SystemExit('unknown certificate operation')
            """;

    /**
     * Makes a scratch repository under the test owner and opens a pull request in it.
     *
     * @throws IOException If Forgejo cannot be reached.
     */
    @Given("a scratch repository under the test owner has a pull request on Forgejo")
    public void aPullRequestOnForgejo() throws IOException {
        forgejo().open();
    }

    /**
     * Has the stub agent comment on one line in its next run, committed to the pull request's head
     * on Forgejo: Forgejo has no reply in a thread, no comment on several lines and no suggestion,
     * so the reviewer's every word there, its answers too, is a comment on the same line.
     *
     * @param text What the comment says.
     * @throws IOException If Forgejo cannot be reached.
     */
    @Given(
            "in its next run the stub agent comments on its line at the pull request on Forgejo: {string}")
    public void theStubComments(String text) throws IOException {
        final ForgejoScratch scratch = forgejo();
        scratch.commitToHead(
                StubReview.FILE, StubReview.singleLine(scratch.url(), scratch.base(), text));
    }

    /**
     * Makes a guarded project whose upstream is the scratch repository on Forgejo, and has the
     * scenario's reviewers start in it: a task is started only at a pull request of a repository
     * its project names.
     *
     * @param name The project's name and directory.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a guarded project called {string} whose upstream is the scratch repository on Forgejo")
    public void aProjectAtTheScratch(String name) throws IOException {
        review.aReviewersProject(name, StubReview.repositoryOf(forgejo().url()) + ".git");
    }

    /**
     * Runs a task at the pull request on Forgejo with the stub agent.
     *
     * @param task The task's name.
     * @param account The account its items are written as, {@code author} or {@code review}.
     * @param rights The rights it names, separated by commas.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is run by the stub agent as {word} with the rights {string} at the"
                    + " pull request on Forgejo")
    public void aRun(String task, String account, String rights) throws IOException {
        review.aRun(task, account, rights, forgejo().url());
    }

    /**
     * Asserts that no review comment reached the pull request.
     *
     * @throws IOException If Forgejo cannot be reached.
     */
    @Then("the pull request on Forgejo has no review comment")
    public void noReviewComment() throws IOException {
        assertThat(forgejo().comments()).as("the review comments on Forgejo").isEmpty();
    }

    /**
     * Posts the test identity's answer in the task's thread, as a forge account's would be.
     *
     * @param text What it says.
     * @throws IOException If Forgejo cannot be reached.
     */
    @When("the test identity answers the task's thread on Forgejo: {string}")
    public void theTestIdentityAnswers(String text) throws IOException {
        forgejo().answer(text);
    }

    /**
     * Asserts the task's thread on Forgejo, read back: each comment, in order, holds its text.
     *
     * @param texts The texts, the root's first.
     * @throws IOException If Forgejo cannot be reached.
     */
    @Then("the task's thread on Forgejo reads, in order:")
    public void theThreadReads(List<String> texts) throws IOException {
        final List<ForgejoScratch.Comment> thread = forgejo().thread(texts.size());
        if (thread.size() != texts.size()) {
            throw new AssertionError(
                    "the task's thread on Forgejo has "
                            + thread.size()
                            + " comments, not "
                            + texts.size()
                            + ": "
                            + thread
                            + "; the pull request's reviews:"
                            + forgejo().reviews()
                            + "\nwhat the task reviewer holds now: "
                            + review.heldNow("reviewer"));
        }
        for (int i = 0; i < texts.size(); i++) {
            assertThat(thread.get(i).body())
                    .as("comment %d of the task's thread on Forgejo", i + 1)
                    .contains(texts.get(i));
        }
    }

    /**
     * Asserts that every comment of the task's thread went out under the test identity's account.
     *
     * @throws IOException If Forgejo cannot be reached.
     */
    @Then("every comment of the task's thread on Forgejo is the test identity's")
    public void everyCommentIsTheIdentitys() throws IOException {
        final ForgejoScratch scratch = forgejo();
        assertThat(scratch.thread(1).stream().map(ForgejoScratch.Comment::login))
                .as("the accounts the task's thread on Forgejo was written under")
                .isNotEmpty()
                .containsOnly(scratch.login());
    }

    /**
     * Removes the scratch repository, even when an earlier step failed.
     *
     * @throws IOException If Forgejo cannot be reached.
     */
    @After(value = "@forgejo", order = 100)
    public void removeTheRepository() throws IOException {
        final ForgejoScratch scratch = forgejo;
        if (scratch != null) {
            forgejo = null;
            scratch.delete();
        }
    }

    private ForgejoScratch forgejo() {
        final ForgejoScratch scratch = forgejo;
        if (scratch == null) {
            throw new AssertionError(
                    "No test identity yet: start with 'Given the Forgejo test identity ...'");
        }
        return scratch;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
