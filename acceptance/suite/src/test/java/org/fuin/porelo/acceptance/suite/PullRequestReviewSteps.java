package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.acceptance.TerminalSteps;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Steps for a task's runs at a pull request on the stub forge adapter: what the forge's side says,
 * what the stub agent is to do in its run, what the owner account decides, and what reached the
 * forge.
 */
public final class PullRequestReviewSteps {

    private static final Pattern CONTAINER =
            Pattern.compile("^container\\s+(\\S+)", Pattern.MULTILINE);

    private static final Pattern SIDECAR = Pattern.compile("^sidecar\\s+(\\S+)", Pattern.MULTILINE);

    /** How long a run may take: its project's image may be built first. */
    private static final int RUN_SECONDS = 900;

    private final World world;

    private final TerminalSteps projects;

    private final ForgeStub forge;

    private final StubGitForge gitForge;

    /** The project the scenario's reviewers start in, or {@code null} for the default one. */
    private @Nullable String project;

    /** The forge account the scenario's pull request was opened by. */
    private @Nullable String openedBy;

    /** The branch a pull request's repository on the machine is opened from. */
    private static final String HEAD_BRANCH = "review-head";

    /** The branch a pull request's repository on the machine is opened against. */
    private static final String BASE_BRANCH = "main";

    /**
     * Reads, inside a task, where its branch left the branch it started from: the first of the
     * gate's branches the workspace has fetched that is not its head, else its first commit, since
     * a workspace may hold neither {@code porelo/HEAD} nor {@code porelo/main}.
     */
    static final String TASK_BASE =
            "cd /workspace && h=$(git rev-parse HEAD) && for r in"
                    + " \"$(git symbolic-ref -q --short refs/remotes/porelo/HEAD || true)\""
                    + " porelo/main porelo/master origin/main origin/master; do"
                    + " if [ -n \"$r\" ] && b=$(git merge-base HEAD \"$r\" 2>/dev/null)"
                    + " && [ \"$b\" != \"$h\" ]; then echo \"$b\"; exit 0; fi; done;"
                    + " git rev-list --max-parents=0 HEAD | tail -n 1";

    private @Nullable String base;

    /** Where those repositories lie in the account's home. */
    private static final String REPOSITORIES = "pr-review-forge";

    private @Nullable String pullRequest;

    private @Nullable String headRepository;

    /** A task's own pull request's upstream and branch, which its pushes reach. */
    private @Nullable String ownUpstream;

    private @Nullable String ownBranch;

    /**
     * Creates the steps over the scenario's world.
     *
     * @param world The scenario's world.
     * @param projects The kit's steps that make a scenario's projects.
     */
    public PullRequestReviewSteps(World world, TerminalSteps projects) {
        this.world = world;
        this.projects = projects;
        final ForgeStub.Script script =
                new ForgeStub.Script() {
                    @Override
                    public Ssh.Output run(String command) throws IOException {
                        return world.run(command);
                    }

                    @Override
                    public Ssh.Output run(String command, String stdin) throws IOException {
                        return world.run(command, stdin);
                    }
                };
        this.gitForge = new StubGitForge(script);
        this.forge = new ForgeStub(script, gitForge);
    }

    /**
     * Starts the forge's git side for this scenario, serving its pull requests' repositories to the
     * token a variable holds, as the vault lends it for the forge's host.
     *
     * @param variable The variable the token is read from.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the stub git forge serves pull requests to the value of {string}")
    public void theGitForgeServes(String variable) throws IOException {
        gitForge.start(world.secret(variable));
    }

    /**
     * Says which forge account a token stands for at the stub forge, so what is written with it
     * goes out under that account.
     *
     * @param variable The variable the token is read from.
     * @param account The forge account.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("at the stub forge the value of {string} is the token of {string}")
    public void theTokenIsAnAccounts(String variable, String account) throws IOException {
        forge.account(world.secret(variable), account);
    }

    /**
     * Returns a pull request's address as a step names it, {@code {forge}} standing for the stub
     * git forge's address.
     *
     * @param url The address as written.
     * @return The address.
     */
    private String url(String url) {
        return url.contains("{forge}") ? url.replace("{forge}", gitForge.origin()) : url;
    }

    /**
     * Makes a guarded project whose own repository's upstream is the repository the scenario's pull
     * requests are at, and has the scenario's reviewers start in it: a task is started only at a
     * pull request of a repository its project names. The account's own default project is left as
     * it is.
     *
     * @param name The project's name and directory.
     * @param written The upstream's address, {@code {forge}} standing for the stub git forge's.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a guarded project called {string} whose upstream is {string}")
    public void aReviewersProject(String name, String written) throws IOException {
        projects.aProjectNamingTheUpstream(name, "guarded", url(written), "");
        project = name;
    }

    /**
     * Makes an online project whose upstream is a bare repository of its own, as the kit's online
     * project has, named by its address on the stub git forge and served there: the task's own pull
     * request is found by the branch its pushes reach at that upstream, and a pull request is its
     * own only at the repository its project pushes to.
     *
     * @param name The project's name and directory.
     * @param written The upstream's address, {@code {forge}} standing for the stub git forge's.
     * @param more YAML appended to the project file, as for the kit's project steps.
     * @throws IOException If the machine cannot be reached.
     */
    @Given(
            "an online project called {string} pushing to {string} on the stub git forge, whose project file also says:")
    public void anOnlineProjectOnTheForge(String name, String written, String more)
            throws IOException {
        final String address = url(written);
        projects.anOnlineProjectPushingTo(name, address, more);
        gitForge.serveUpstream(address, upstreamOf(name));
    }

    /**
     * Gives the stub forge a pull request of its own, with no conversation yet, whose head is a
     * repository on the machine holding the files the review fixtures are anchored in.
     *
     * @param url The pull request.
     * @param account The forge account that opened it.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the stub forge has the pull request {string}, opened by {string}")
    public void aPullRequest(String written, String account) throws IOException {
        final String url = url(written);
        final String repository = repositoryOf(url);
        final Ssh.Output made = world.run(repositoryCommand(repository));
        assertThat(made.status())
                .as("the pull request's repository could not be made:%n%s", made.all())
                .isZero();
        forge.pullRequest(url, account, repository, HEAD_BRANCH);
        pullRequest = url;
        openedBy = account;
        headRepository = repository;
        base = commit(world.run("git -C " + repository + " rev-parse " + BASE_BRANCH), "base");
    }

    /**
     * Gives the stub forge a pull request from the branch the task's pushes reach at the forge,
     * served from the project's own upstream repository: the branch once the task's push reached
     * it, against {@code main}, which is made at the task's base when the upstream has none.
     *
     * @param written The pull request.
     * @param project The task's online project, whose upstream the kit made.
     * @param account The forge account that opened it.
     * @throws IOException If the machine cannot be reached.
     */
    @Given(
            "the stub forge has the pull request {string} from the task's branch in {string}, opened by {string}")
    public void aPullRequestFromTheTasksBranch(String written, String project, String account)
            throws IOException {
        final String url = url(written);
        final Ssh.Output status =
                world.run(
                        "porelo task status "
                                + quote(world.task().container())
                                + " | awk '$1 == \"forge\" { print $2 }'");
        final String branch = status.out().strip();
        assertThat(branch).as("the task's status names no branch at the forge").isNotEmpty();
        base =
                commit(
                        world.run(
                                "timeout 30 podman exec --user agent "
                                        + quote(world.task().container())
                                        + " sh -c "
                                        + quote(TASK_BASE)),
                        "task's base");
        final String upstream = upstreamOf(project);
        final Ssh.Output ready = world.run(upstreamCommand(upstream, branch, base));
        assertThat(ready.status())
                .as("the project's upstream holds no pull request's branch:%n%s", ready.all())
                .isZero();
        forge.pullRequest(url, account, upstream, branch);
        pullRequest = url;
        openedBy = account;
        headRepository = null;
        ownUpstream = upstream;
        ownBranch = branch;
    }

    /**
     * Names the upstream repository the kit makes for an online project of its own.
     *
     * @param project The project.
     * @return Its path, for the shell.
     */
    static String upstreamOf(String project) {
        return "\"$HOME/" + project + "/.git/upstream.git\"";
    }

    /**
     * Builds the command that waits for the task's branch to reach the upstream and gives the
     * upstream a {@code main} at the task's base when it has none, so the pull request has a base
     * and a merge base there.
     *
     * @param upstream The upstream, for the shell.
     * @param branch The task's branch at the forge.
     * @param base The task's base commit.
     * @return The command.
     */
    static String upstreamCommand(String upstream, String branch, String base) {
        return "set -e; u="
                + upstream
                + "; for i in $(seq 1 60); do git -C \"$u\" rev-parse -q --verify "
                + quote("refs/heads/" + branch)
                + " > /dev/null && break; sleep 1; done;"
                + " git -C \"$u\" rev-parse -q --verify "
                + quote("refs/heads/" + branch)
                + " > /dev/null || { echo 'the branch never reached the upstream' >&2; exit 1; };"
                + " git -C \"$u\" rev-parse -q --verify refs/heads/"
                + BASE_BRANCH
                + " > /dev/null || git -C \"$u\" update-ref refs/heads/"
                + BASE_BRANCH
                + " "
                + base;
    }

    /**
     * Reads one commit id from what a command printed.
     *
     * @param output What it printed.
     * @param what Which commit, for the failure.
     * @return The commit.
     */
    static String commit(Ssh.Output output, String what) {
        final String commit = output.out().strip();
        if (output.status() != 0 || !commit.matches("[0-9a-f]{40,64}")) {
            throw new AssertionError(
                    "the pull request's " + what + " commit could not be read:\n" + output.all());
        }
        return commit;
    }

    /**
     * Names the repository on the machine a pull request's head is read from.
     *
     * @param url The pull request.
     * @return Its path, for the shell.
     */
    static String repositoryOf(String url) {
        return "\"$HOME/" + REPOSITORIES + "/" + url.replaceAll("[^A-Za-z0-9]+", "-") + "\"";
    }

    /**
     * Builds the command that makes a pull request's repository anew: a base commit holding the
     * files the review fixtures name, long enough for their lines, and the head branch from it.
     *
     * @param repository Its path, for the shell.
     * @return The command.
     */
    static String repositoryCommand(String repository) {
        return "set -e; r="
                + repository
                + "; rm -rf \"$r\"; mkdir -p \"$r/src/main/java/org/example/shop\";"
                + " git init -q -b "
                + BASE_BRANCH
                + " \"$r\";"
                + " seq 1 120 | sed 's/^/line /' > \"$r/src/main/java/org/example/shop/Login.java\";"
                + " seq 1 120 | sed 's/^/line /' > \"$r/src/main/java/org/example/shop/Session.java\";"
                + " echo 'the project' > \"$r/README.md\";"
                + " git -C \"$r\" add -A;"
                + " git -C \"$r\" -c user.email=forge@localhost -c user.name=forge commit -qm"
                + " 'acceptance: the base';"
                + " git -C \"$r\" checkout -q -b "
                + HEAD_BRANCH;
    }

    /**
     * Appends a forge account's new thread to the pull request's conversation.
     *
     * @param account The forge account.
     * @param url The pull request.
     * @param text What it says.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the forge account {string} comments on {string}: {string}")
    public void aForgeAccountComments(String account, String url, String text) throws IOException {
        forge.comment(url(url), account, text);
    }

    /**
     * Appends a forge account's answer to the thread the task's last released item opened or
     * answered.
     *
     * @param account The forge account.
     * @param url The pull request.
     * @param text What it says.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the forge account {string} answers the task's thread on {string}: {string}")
    public void aForgeAccountAnswers(String account, String written, String text)
            throws IOException {
        final String url = url(written);
        final List<ForgeStub.Written> writes = writtenOnceMoved(url, count -> count > 0);
        assertThat(writes)
                .as("nothing reached %s, so there is no thread to answer", url)
                .isNotEmpty();
        forge.answer(url, account, writes.getLast().thread(), text);
    }

    /**
     * Sets the pull request's state as the forge answers it.
     *
     * @param url The pull request.
     * @param state {@code open}, {@code merged} or {@code closed}.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the stub forge answers {string} as {word}")
    public void theForgeAnswersAState(String url, String state) throws IOException {
        forge.state(url(url), state);
    }

    /**
     * Runs a task at a pull request with the stub agent, as {@code porelo task run} does, and makes
     * it the scenario's task when the run names its container.
     *
     * @param task The task's name.
     * @param account The account its items are written as, {@code author} or {@code review}.
     * @param rights The rights it names, separated by commas, such as {@code reply, openThreads}.
     * @param url The pull request.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is run by the stub agent as {word} with the rights {string} at {string}")
    public void aRun(String task, String account, String rights, String url) throws IOException {
        run(task, account, ScenarioTasks.words(rights), url);
    }

    /**
     * Runs a task at a pull request with the stub agent, naming the rights given.
     *
     * @param task The task's name.
     * @param account The account its items are written as, or {@code null} for the default.
     * @param rights The rights it names; none named takes the default of its workspace.
     * @param url The pull request.
     * @throws IOException If the machine cannot be reached.
     */
    void run(String task, @Nullable String account, List<String> rights, String url)
            throws IOException {
        final Ssh.Output output = world.run(runCommand(task, project, account, rights, url(url)));
        world.output(output);
        final Matcher container = CONTAINER.matcher(output.all());
        if (container.find()) {
            world.task(new World.Task(container.group(1), null, output.all()));
        }
    }

    /**
     * Runs the scenario's task again, at a pull request, with the stub agent, naming no right: at
     * its own pull request it takes the default rights of its own work.
     *
     * @param url The pull request.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task is run by the stub agent at {string}")
    public void theTaskRuns(String url) throws IOException {
        run(world.task().container(), null, List.of(), url);
    }

    /**
     * Builds the command a run is started with: {@code porelo task start} with a prompt, which runs
     * the agent once and stops and keeps the task at the run's end, so the next start brings it
     * back at its place. A task left running is stopped first, since a start refuses a running one;
     * stopping one that is not running answers nothing the start would not say itself. A task named
     * by its container carries its project; one named alone starts in the project the scenario gave
     * its reviewers, whose own repository names the pull request's, or else in the default project.
     *
     * @param task The task's name.
     * @param project The project the scenario gave its reviewers, or {@code null} for none.
     * @param account The account its items are written as, {@code author} or {@code review}, or
     *     {@code null} to name none, which a start takes as {@code author}.
     * @param rights The rights it names, each as one {@code --right}; none named takes the default.
     * @param url The place.
     * @return The command.
     */
    static String runCommand(
            String task,
            @Nullable String project,
            @Nullable String account,
            List<String> rights,
            String url) {
        final String in = ScenarioTasks.name(project == null ? "default" : project);
        final StringBuilder named = new StringBuilder();
        for (final String right : rights) {
            named.append(" --right ").append(quote(right));
        }
        return certified(
                "t="
                        + quote(task)
                        + "; porelo task stop \"$t\" > /dev/null 2>&1 || true;"
                        + " timeout "
                        + RUN_SECONDS
                        + " porelo task start \"$t\""
                        + (task.startsWith("porelo-")
                                ? ""
                                : " --project " + quote(in) + " --repository " + quote(in))
                        + " --agent stub"
                        + named
                        + (account == null
                                ? ""
                                : " --write-as " + quote(ScenarioTasks.name(account)))
                        + " --place "
                        + quote(url)
                        + " --prompt review");
    }

    /**
     * Runs a start with the person's agent holding a key of the scenario's own, so the task is
     * certified: a project of this machine alone takes the first Ed25519 key the starter's agent
     * holds, and only a certified task's key signs what it sends to a pull request. The agent is
     * this command's alone and ends with it; the key stays beside the stub forge for the next run.
     *
     * @param command The start.
     * @return The command, run under the agent.
     */
    static String certified(String command) {
        return "k=\"$HOME/porelo-acceptance-certifier/key\"; mkdir -p -m 700 \"${k%/*}\";"
                + " [ -f \"$k\" ] || ssh-keygen -q -t ed25519 -N '' -C porelo-acceptance-certifier"
                + " -f \"$k\" || exit 1;"
                + " eval \"$(ssh-agent -s)\" > /dev/null || exit 1;"
                + " trap 'ssh-agent -k > /dev/null 2>&1' EXIT;"
                + " ssh-add -q \"$k\" || exit 1; "
                + command;
    }

    /**
     * Places a project as its organization does, naming the scenario's own key as the one that may
     * certify its tasks: a project of a name of its own certifies by the keys its organization
     * names, and by none else. Run inside {@link #certified}, which makes the key first.
     *
     * @param project The project, whose repository is {@code ~/<project>}.
     * @return The commands, ending in {@code ;}.
     */
    static String placed(String project) {
        return "p="
                + quote(project)
                + "; s=\"${XDG_STATE_HOME:-$HOME/.local/state}/porelo/organization-projects\";"
                + " mkdir -p \"$s\" && ln -sfnT \"$HOME/$p\" \"$s/$p.git\" || exit 1;"
                + " key=$(cut -d' ' -f1-2 \"$k.pub\") || exit 1;"
                + " printf '{\"project\":\"%s\",\"revision\":\"c0\",\"address\":"
                + "\"https://forge.example/%s.git\",\"fetch_from\":null,\"homeserver\":null,"
                + "\"room\":null,\"signing_keys\":[{\"login\":\"person\",\"role\":\"member\","
                + "\"key\":\"%s\",\"valid_from\":\"2000-01-01\",\"valid_until\":\"2999-01-01\","
                + "\"revoked_since\":null}]}' \"$p\" \"$p\" \"$key\" > \"$s/$p.json\" || exit 1; ";
    }

    /**
     * Starts a task in a project and leaves it running, certified by the scenario's own key as
     * {@link #certified} gives it, and makes it the scenario's task.
     *
     * @param project The project, which names its repository and the task.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a certified task is started in {string} for the stub agent and left running")
    public void aCertifiedTask(String project) throws IOException {
        final Ssh.Output output =
                world.run(
                        certified(
                                placed(project)
                                        + "timeout 900 porelo task start --project "
                                        + quote(project)
                                        + " --repository "
                                        + quote(project)
                                        + " --agent stub --detach --clearance deny"));
        world.output(output);
        final Matcher container = CONTAINER.matcher(output.all());
        assertThat(output.status() == 0 && container.find())
                .as("the certified task did not start:%n%s", world.redact(output.all()))
                .isTrue();
        final Matcher sidecar = SIDECAR.matcher(output.all());
        final java.nio.file.Path state =
                sidecar.find() ? java.nio.file.Path.of(sidecar.group(1)).getParent() : null;
        world.task(
                new World.Task(
                        container.group(1), state == null ? null : state.toString(), output.all()));
    }

    /**
     * Opens the scenario's vault to its own daemon, as {@code porelo vault unlock} does: the daemon
     * signs a task's messages with the task's key, which the vault holds, and waits while it is
     * shut.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the scenario's vault is open to its daemon")
    public void theVaultIsOpenToTheDaemon() throws IOException {
        final Ssh.Output output =
                world.run("porelo vault unlock --passphrase-command 'printf acceptance'");
        assertThat(output.status())
                .as("the vault could not be opened:%n%s", world.redact(output.all()))
                .isZero();
    }

    /**
     * Has the stub agent submit a fixture's submission in its next run: one {@code comment} message
     * to {@code review} whose data part is the submission.
     *
     * @param fixture The submission fixture's file name.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("in its next run the stub agent submits {string}")
    public void theStubSubmits(String fixture) throws IOException {
        commitReviewFile(StubReview.submit(fixture, pullRequest(), base(), null));
    }

    /**
     * Has the stub agent answer, in its next run, every open thread of the conversation it is
     * delivered, by the thread's native id as the delivery names it.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Given("in its next run the stub agent replies to every open thread it is given")
    public void theStubReplies() throws IOException {
        commitReviewFile(StubReview.replies(pullRequest(), base()));
    }

    /**
     * Has the stub agent submit, in its next run, a comment with a field the profile does not have.
     *
     * @param field The field's name.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("in its next run the stub agent submits a comment with the field {string}")
    public void theStubSubmitsAField(String field) throws IOException {
        commitReviewFile(
                StubReview.submit("submitted-comment.sarif.json", pullRequest(), base(), field));
    }

    private String base() {
        if (base == null) {
            throw new AssertionError("No pull request's base yet: the pull request step reads it");
        }
        return base;
    }

    private String pullRequest() {
        if (pullRequest == null) {
            throw new AssertionError(
                    "No pull request yet: start with 'Given the stub forge has the pull request ...'");
        }
        return pullRequest;
    }

    /**
     * Commits the stub's review file into the pull request's head: in the forge's repository, or,
     * for a task's own pull request, on the task's branch, pushed through its gate.
     */
    private void commitReviewFile(String text) throws IOException {
        pullRequest();
        final Ssh.Output committed =
                world.run(
                        headRepository == null
                                ? "timeout 60 podman exec -i --user agent "
                                        + quote(world.task().container())
                                        + " sh -c "
                                        + quote(
                                                "cd /workspace && "
                                                        + commitCommand(".")
                                                        + " && timeout 30 git push -q porelo"
                                                        + " HEAD:\"$PORELO_TASK_REF\"")
                                : "set -e; r=" + headRepository + "; " + commitCommand("\"$r\""),
                        text);
        assertThat(committed.status())
                .as(
                        "the stub's review file could not be committed:%n%s",
                        world.redact(committed.all()))
                .isZero();
        final String repository = headRepository;
        if (repository != null) {
            // The head moved: the facts name it, so the next run's pin fetches the file.
            forge.pullRequest(
                    pullRequest(),
                    java.util.Objects.requireNonNull(openedBy, "openedBy"),
                    repository,
                    HEAD_BRANCH);
        } else if (ownUpstream != null && ownBranch != null) {
            // The task's push goes on to its upstream branch; once there, the facts name it.
            final Ssh.Output arrived =
                    world.run(arrivedCommand(world.task().container(), ownUpstream, ownBranch));
            assertThat(arrived.status())
                    .as("the task's push never reached its upstream:%n%s", arrived.all())
                    .isZero();
            forge.pullRequest(
                    pullRequest(),
                    java.util.Objects.requireNonNull(openedBy, "openedBy"),
                    ownUpstream,
                    ownBranch);
        }
    }

    /**
     * Waits until a task's upstream branch holds the task's own head.
     *
     * @param container The task's container.
     * @param upstream The upstream repository, for the shell.
     * @param branch The branch its pushes reach.
     * @return The command.
     */
    static String arrivedCommand(String container, String upstream, String branch) {
        return "h=$(timeout 30 podman exec --user agent "
                + quote(container)
                + " git -C /workspace rev-parse HEAD) || exit 1; u="
                + upstream
                + "; for i in $(seq 1 60); do [ \"$(git -C \"$u\" rev-parse -q --verify "
                + quote("refs/heads/" + branch)
                + ")\" = \"$h\" ] && exit 0; sleep 1; done;"
                + " echo \"the upstream's branch never reached $h\" >&2; exit 1";
    }

    /**
     * Builds the command that writes standard input as the review file and commits it.
     *
     * @param directory The checkout, for the shell.
     * @return The command.
     */
    static String commitCommand(String directory) {
        return "cat > "
                + directory
                + "/"
                + StubReview.FILE
                + " && git -C "
                + directory
                + " add "
                + StubReview.FILE
                + " && git -C "
                + directory
                // Allowed empty: a second round of the same replies writes the same file, and the
                // head still moves, as the next run's pin expects.
                + " -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m"
                + " 'acceptance: what the stub does in its next run'";
    }

    /**
     * Releases every outgoing item a task holds, each by the digest {@code porelo gate held} shows.
     *
     * @param task The task.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the owner account releases what the task {string} holds")
    public void theOwnerReleases(String task) throws IOException {
        decideEvery(task, "outgoing", "release");
    }

    /**
     * Passes every incoming item a task holds.
     *
     * @param task The task.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the owner account passes what the task {string} holds")
    public void theOwnerPasses(String task) throws IOException {
        decideEvery(task, "incoming", "pass");
    }

    /**
     * Releases every outgoing item the scenario's task holds.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @When("the owner account releases what the task holds")
    public void theOwnerReleasesTheTasks() throws IOException {
        decideEvery(world.task().container(), "outgoing", "release");
    }

    /**
     * Names a task's container, which the gate's commands take: a bare name is a task of the
     * project the scenario gave its reviewers, or else of the default project, as {@link
     * #runCommand} starts it.
     *
     * @param task A task's name, or its container's.
     * @param project The project the scenario gave its reviewers, or {@code null} for none.
     * @return The container's name.
     */
    static String containerOf(String task, @Nullable String project) {
        return task.startsWith("porelo-")
                ? task
                : "porelo-" + (project == null ? "default" : project) + "_" + task;
    }

    private void decideEvery(String task, String direction, String decision) throws IOException {
        final List<Map<String, Object>> decided =
                heldOnceMoved(task, direction, count -> count > 0);
        assertThat(decided)
                .as("the task %s holds nothing %s to decide", task, direction)
                .isNotEmpty();
        for (final Map<String, Object> item : decided) {
            final Ssh.Output output =
                    world.run(
                            "porelo gate decide "
                                    + quote(containerOf(task, project))
                                    + " "
                                    + quote(String.valueOf(item.get("id")))
                                    + " "
                                    + decision
                                    + " --digest "
                                    + quote(String.valueOf(item.get("digest"))));
            assertThat(output.status())
                    .as("the %s of %s was refused:%n%s", decision, item.get("id"), output.all())
                    .isZero();
        }
    }

    /**
     * Asserts that a task holds nothing.
     *
     * @param task The task.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task {string} holds nothing")
    public void holdsNothing(String task) throws IOException {
        assertThat(held(task)).as("what the task %s holds", task).isEmpty();
    }

    /**
     * Asserts how many items of a direction a task holds.
     *
     * @param task The task.
     * @param count How many.
     * @param direction {@code outgoing} or {@code incoming}.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task {string} holds {int} {word} item(s)")
    public void holds(String task, int count, String direction) throws IOException {
        assertThat(heldOnceMoved(task, direction, found -> found == count))
                .as("the %s items the task %s holds", direction, task)
                .hasSize(count);
    }

    /**
     * Reads a task's held items of a direction until their count is as expected or the daemon's
     * timer has surely moved its mailbox: a run's last message can land after the watch's notice,
     * and then only the timer, once a minute, takes it to the gate.
     */
    private List<Map<String, Object>> heldOnceMoved(
            String task, String direction, java.util.function.IntPredicate expected)
            throws IOException {
        final long until = System.nanoTime() + MAILBOX_MOVED.toNanos();
        while (true) {
            final List<Map<String, Object>> found =
                    held(task).stream()
                            .filter(item -> direction.equals(item.get("direction")))
                            .toList();
            if (expected.test(found.size()) || System.nanoTime() - until > 0) {
                return found;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
    }

    /** The daemon's timer moves every mailbox once a minute; this leaves it a margin. */
    static final java.time.Duration MAILBOX_MOVED = java.time.Duration.ofSeconds(90);

    /**
     * Asserts how many items of a direction the scenario's task holds.
     *
     * @param count How many.
     * @param direction {@code outgoing} or {@code incoming}.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task holds {int} {word} item(s)")
    public void theTaskHolds(int count, String direction) throws IOException {
        holds(world.task().container(), count, direction);
    }

    private List<Map<String, Object>> held(String task) throws IOException {
        final Ssh.Output output =
                world.run("porelo gate held " + quote(containerOf(task, project)) + " --json");
        assertThat(output.status())
                .as("porelo gate held failed:%n%s", world.redact(output.all()))
                .isZero();
        return items(output.out());
    }

    /**
     * Reads what {@code porelo gate held --json} prints: a JSON array of {@code HeldItem}.
     *
     * @param json The answer.
     * @return Its items.
     */
    static List<Map<String, Object>> items(String json) {
        if (!(Json.parse(json) instanceof List<?> items)) {
            throw new AssertionError("porelo gate held --json printed no JSON array: " + json);
        }
        final List<Map<String, Object>> read = new ArrayList<>();
        for (final Object one : items) {
            if (!(one instanceof Map<?, ?> item)) {
                throw new AssertionError("a held item is not a JSON object: " + json);
            }
            final Map<String, Object> copy = new java.util.LinkedHashMap<>();
            item.forEach((key, value) -> copy.put(String.valueOf(key), value));
            read.add(copy);
        }
        return read;
    }

    /**
     * Asserts the operations that reached the forge at a pull request, in order.
     *
     * @param url The pull request.
     * @param operations The operations, separated by commas, such as {@code comment, reply}.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the stub forge's write log for {string} shows {string}")
    public void theWriteLogShows(String url, String operations) throws IOException {
        final List<String> expected =
                Arrays.stream(operations.split(",")).map(String::strip).toList();
        assertThat(
                        writtenOnceMoved(url(url), count -> count >= expected.size()).stream()
                                .map(ForgeStub.Written::operation)
                                .toList())
                .as("what reached %s", url)
                .containsExactlyElementsOf(expected);
    }

    /**
     * Reads what reached the forge at a pull request until its count is as expected or the daemon's
     * timer has surely moved the mailbox: a released answer goes out on the mailbox's next pass,
     * not with the decision.
     */
    private List<ForgeStub.Written> writtenOnceMoved(
            String url, java.util.function.IntPredicate expected) throws IOException {
        final long until = System.nanoTime() + MAILBOX_MOVED.toNanos();
        while (true) {
            final List<ForgeStub.Written> found = forge.written(url);
            if (expected.test(found.size()) || System.nanoTime() - until > 0) {
                return found;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
    }

    /**
     * Asserts that nothing reached the forge at a pull request.
     *
     * @param url The pull request.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the stub forge's write log for {string} is empty")
    public void theWriteLogIsEmpty(String url) throws IOException {
        assertThat(forge.written(url(url))).as("what reached %s", url).isEmpty();
    }

    /**
     * Asserts the account every write to a pull request went out under.
     *
     * @param url The pull request.
     * @param account The forge account.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("every write in the stub forge's log for {string} went out under {string}")
    public void everyWriteUnder(String url, String account) throws IOException {
        final List<ForgeStub.Written> written = forge.written(url(url));
        assertThat(written).as("what reached %s", url).isNotEmpty();
        assertThat(written.stream().map(ForgeStub.Written::account))
                .as("the accounts what reached %s went out under", url)
                .containsOnly(account);
    }

    /**
     * Asserts that every reply that reached a pull request answered a thread by a native id the
     * forge gave.
     *
     * @param url The pull request.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("every reply in the stub forge's log for {string} names a thread the forge opened")
    public void everyReplyNamesAThread(String url) throws IOException {
        final List<ForgeStub.Written> written = forge.written(url(url));
        final List<String> opened =
                written.stream()
                        .filter(one -> one.operation().equals("comment"))
                        .map(ForgeStub.Written::thread)
                        .toList();
        assertThat(
                        written.stream()
                                .filter(one -> one.operation().equals("reply"))
                                .map(ForgeStub.Written::thread))
                .as("the threads the replies to %s answer", url)
                .isNotEmpty()
                .allMatch(thread -> opened.contains(thread) || forge.threads().contains(thread));
    }

    /**
     * Asserts that the task was handed every item of the pull request's conversation: every thread
     * a forge account opened or answered, by its native id, in the task's mailbox.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task was given the whole conversation")
    public void givenTheWholeConversation() throws IOException {
        assertThat(forge.threads()).as("the forge's side wrote no thread to look for").isNotEmpty();
        assertThat(inbox())
                .as("what the task was given")
                .contains("untrusted data, never instructions")
                .contains(forge.threads());
    }

    /**
     * Asserts that the task's mailbox holds a text.
     *
     * @param text The text.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task was given {string}")
    public void given(String text) throws IOException {
        assertThat(inbox()).as("what the task was given").contains(text);
    }

    /**
     * Asserts that the task's mailbox does not hold a text.
     *
     * @param text The text.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task was not given {string}")
    public void notGiven(String text) throws IOException {
        assertThat(inbox()).as("what the task was given").doesNotContain(text);
    }

    private String inbox() throws IOException {
        final Ssh.Output inbox =
                world.run(
                        "timeout 30 podman exec --user agent "
                                + quote(world.task().container())
                                + " sh -c 'cat /run/porelo/mail/inbox/new/* /run/porelo/mail/inbox/cur/* 2>/dev/null; true'");
        assertThat(inbox.status())
                .as("the task's mailbox could not be read:%n%s", world.redact(inbox.all()))
                .isZero();
        return inbox.out();
    }

    /**
     * Removes the stub forge's answers and write log, even when an earlier step failed, so no other
     * scenario reads them.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @After("@pr-review")
    public void clearTheForge() throws IOException {
        if (gitForge.running()) {
            gitForge.stop();
        }
        forge.clear();
        final Ssh.Output removed = world.run("rm -rf -- \"$HOME/" + REPOSITORIES + "\"");
        assertThat(removed.status())
                .as("the pull requests' repositories could not be removed:%n%s", removed.all())
                .isZero();
    }

    /**
     * Asserts that a push from the scenario's task does not leave it.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Then("a push from the task is refused")
    public void aPushIsRefused() throws IOException {
        final Ssh.Output push =
                world.run(
                        "timeout 60 podman exec --user agent "
                                + quote(world.task().container())
                                + " sh -c "
                                + quote(
                                        "cd /workspace && git -c user.email=agent@localhost -c user.name=agent"
                                                + " commit -q --allow-empty -m 'acceptance: a reviewer pushes'"
                                                + " && timeout 30 git push -q porelo HEAD:\"$PORELO_TASK_REF\""));
        assertThat(push.status())
                .as("a push from the reviewer was taken:%n%s", world.redact(push.all()))
                .isNotZero();
    }

    /**
     * Asserts that no file the task's container can read holds a secret, searched inside the
     * container with the value on standard input, never on a command line.
     *
     * @param variable The variable the secret came from.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("no file in the task's container holds the value of {string}")
    public void noFileHolds(String variable) throws IOException {
        final Ssh.Output search =
                world.run(
                        "timeout 120 podman exec -i --user agent "
                                + quote(world.task().container())
                                + " sh -c "
                                + quote(
                                        "IFS= read -r v; n=$(find / -xdev -type f -readable 2>/dev/null | wc -l);"
                                                + " echo \"searched $n\";"
                                                + " grep -rlsF -e \"$v\" / --exclude-dir=proc --exclude-dir=sys"
                                                + " | sed 's/^/holds: /'; true"),
                        world.secret(variable) + "\n");
        assertThat(search.out())
                .as("the container's files could not be searched:%n%s", world.redact(search.all()))
                .containsPattern("searched [1-9]");
        assertThat(search.out())
                .as("these files hold the value of %s", variable)
                .doesNotContain("holds: ");
    }

    private static String quote(@Nullable String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }
}
