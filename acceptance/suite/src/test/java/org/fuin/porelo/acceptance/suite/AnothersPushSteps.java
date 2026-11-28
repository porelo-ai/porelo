package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.acceptance.suite.ScenarioTasks.name;
import static org.fuin.porelo.acceptance.suite.ScenarioTasks.quote;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Steps for a task that reads another task's push where it waits at the gate: the pushing task's
 * work, carrying what the stub agent is to send in the reading task's run; the reading task's start
 * at that push; and what the reading task can see there.
 *
 * <p>The reading task is started as a plain machine starts it, with no certified key: at a waiting
 * push what it sends goes into the push's threads whether it is signed or not.
 */
public final class AnothersPushSteps {

    private static final Pattern CONTAINER =
            Pattern.compile("^container\\s+(\\S+)", Pattern.MULTILINE);

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** How long a reading task's start and run may take. */
    private static final int RUN_SECONDS = 300;

    private final World world;

    /** The tasks the scenario's steps started, by name, which removes them at its end. */
    private final ScenarioTasks tasks;

    /** Each pushing task's pushed head, by its name. */
    private final Map<String, String> heads = new LinkedHashMap<>();

    /** Each pushing task's project, by its name. */
    private final Map<String, String> projects = new LinkedHashMap<>();

    /** The waiting push the scenario's reviewer reads, or null before a reviewer starts. */
    private @Nullable String reviewedPush;

    /** What was in the push's conversation before the reviewer's next run. */
    private List<String> conversation = List.of();

    /**
     * Creates the steps over the scenario's world.
     *
     * @param world The scenario's world.
     * @param tasks The tasks the scenario's steps started.
     */
    public AnothersPushSteps(World world, ScenarioTasks tasks) {
        this.world = world;
        this.tasks = tasks;
    }

    /**
     * Has the scenario's task push work to its gate for another task to read: {@link
     * GatePushReview#PATH} with three lines, and the stub agent's review file of the kind named,
     * which the reading task's workspace will hold.
     *
     * @param task The pushing task's name, which names its push; the scenario's last started task.
     * @param project Its project.
     * @param kind What the stub does in a run that reads it: {@code comments}, {@code replies},
     *     {@code resolves} or {@code rejects}.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task {string} in {string} pushes work whose stub review {word}")
    public void pushesWork(String task, String project, String kind) throws IOException {
        final String container = world.task().container();
        assertThat(container)
                .as("the scenario's last task is not the one named %s", task)
                .endsWith("_" + name(task));
        final Ssh.Output pushed =
                world.run(pushCommand(container), GatePushReview.of(kind, name(project), task));
        world.output(pushed);
        final List<String> lines = pushed.out().strip().lines().toList();
        final String head = lines.isEmpty() ? "" : lines.getLast().strip();
        assertThat(pushed.status() == 0 && COMMIT.matcher(head).matches())
                .as("the task %s pushed no work:%n%s", task, world.redact(pushed.all()))
                .isTrue();
        tasks.started(task, container);
        heads.put(task, head);
        projects.put(task, project);
    }

    /**
     * Builds the command that commits and pushes the work inside a task, the review file read from
     * standard input with the commit the work starts from in place, and prints the pushed head.
     *
     * @param container The pushing task's container.
     * @return The command.
     */
    static String pushCommand(String container) {
        return "timeout 120 podman exec -i --user agent "
                + quote(container)
                + " sh -c "
                + quote(
                        "cd /workspace && b=$(git rev-parse HEAD) && printf '"
                                + GatePushReview.LINES.replace("\n", "\\n")
                                + "' > "
                                + GatePushReview.PATH
                                + " && sed \"s/"
                                + GatePushReview.BASE
                                + "/$b/g\" > .porelo-stub-review && git add "
                                + GatePushReview.PATH
                                + " .porelo-stub-review && git -c user.email=agent@localhost"
                                + " -c user.name=agent commit -qm 'acceptance: work for another"
                                + " task to read' && timeout 30 git push -q porelo"
                                + " HEAD:\"$PORELO_TASK_REF\" && git rev-parse HEAD");
    }

    /**
     * Starts a task at another task's waiting push with the stub agent, naming the rights given,
     * and runs it once; it becomes the scenario's task when its start names its container.
     *
     * @param task The reading task's name.
     * @param project The project it is started in.
     * @param push The waiting push's name, the pushing task's.
     * @param rights The rights it names, separated by commas, such as {@code reply, openThreads}.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is started in {string} at the waiting push of {string} for the stub"
                    + " agent with the rights {string}")
    public void startedAtThePush(String task, String project, String push, String rights)
            throws IOException {
        start(task, project, push, ScenarioTasks.words(rights), true);
    }

    /**
     * Starts a task at another task's waiting push with the stub agent, naming no right.
     *
     * @param task The reading task's name.
     * @param project The project it is started in.
     * @param push The waiting push's name, the pushing task's.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is started in {string} at the waiting push of {string} for the stub"
                    + " agent naming no right")
    public void startedAtThePushNamingNoRight(String task, String project, String push)
            throws IOException {
        start(task, project, push, List.of(), true);
    }

    /**
     * Starts a task at another task's waiting push with the stub agent, naming the rights given,
     * and leaves it running with no run of its agent, for a scenario that acts in the task itself;
     * it becomes the scenario's task.
     *
     * @param task The reading task's name.
     * @param project The project it is started in.
     * @param push The waiting push's name, the pushing task's.
     * @param rights The rights it names, separated by commas.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is started in {string} at the waiting push of {string} for the stub"
                    + " agent with the rights {string} and left running")
    public void startedAtThePushAndLeftRunning(
            String task, String project, String push, String rights) throws IOException {
        start(task, project, push, ScenarioTasks.words(rights), false);
        assertThat(world.output().status())
                .as("the task %s did not start:%n%s", task, world.redact(world.output().all()))
                .isZero();
    }

    private void start(String task, String project, String push, List<String> rights, boolean once)
            throws IOException {
        final Ssh.Output output = world.run(startCommand(task, project, push, rights, once));
        world.output(output);
        final Matcher container = CONTAINER.matcher(output.all());
        if (container.find()) {
            tasks.started(task, container.group(1));
            world.task(new World.Task(container.group(1), null, output.all()));
            reviewedPush = push;
        } else if (!once) {
            throw new AssertionError(
                    "the task " + task + " did not start:\n" + world.redact(output.all()));
        }
    }

    /**
     * Builds the command a reading task is started with at the push: with a prompt, so the stub
     * runs once and the task is kept at the run's end, or detached with no run of its agent.
     *
     * @param task The reading task's name.
     * @param project Its project, which names its repository.
     * @param push The waiting push's name.
     * @param rights The rights it names, each as one {@code --right}; none named names none.
     * @param once Whether the stub runs once, else the task is left running.
     * @return The command.
     */
    static String startCommand(
            String task, String project, String push, List<String> rights, boolean once) {
        final StringBuilder named = new StringBuilder();
        for (final String right : rights) {
            named.append(" --right ").append(quote(right));
        }
        return "timeout "
                + RUN_SECONDS
                + " porelo task start "
                + quote(name(task))
                + " --project "
                + quote(name(project))
                + " --repository "
                + quote(name(project))
                + " --agent stub"
                + named
                + " --place "
                + quote("push:" + name(push))
                + (once ? " --clearance deny --prompt review" : " --detach --clearance deny");
    }

    /**
     * Runs the same reviewer again at its waiting push, retaining its container and rights.
     *
     * @param task The reviewer.
     * @param project Its project.
     * @param push The waiting push.
     * @param rights Its rights, separated by commas.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "the task {string} is run in {string} at the waiting push of {string} for the stub"
                    + " agent with the rights {string}")
    public void runAtThePush(String task, String project, String push, String rights)
            throws IOException {
        final String previous = tasks.container(task);
        final String identity = containerId(previous);
        conversation = conversationItems(comments(push));
        final Ssh.Output output =
                world.run(runCommand(task, project, push, ScenarioTasks.words(rights)));
        world.output(output);
        assertThat(containerId(previous)).as("the same reviewer continues").isEqualTo(identity);
        world.task(new World.Task(previous, null, output.all()));
        reviewedPush = push;
    }

    private String containerId(String container) throws IOException {
        final Ssh.Output output =
                world.run("podman inspect --format '{{.Id}}' " + quote(container));
        assertThat(output.status())
                .as(
                        "the reviewer's container could not be inspected:%n%s",
                        world.redact(output.all()))
                .isZero();
        assertThat(output.out().strip()).as("the reviewer's container id").matches("[0-9a-f]{64}");
        return output.out().strip();
    }

    /** Builds a later run as the pull-request steps do: stop, then start with a prompt. */
    static String runCommand(String task, String project, String push, List<String> rights) {
        return "porelo task stop "
                + quote("porelo-" + name(project) + "_" + name(task))
                + " > /dev/null 2>&1 || true; "
                + startCommand(task, project, push, rights, true);
    }

    /** Whether the scenario has started a reviewer at a waiting push. */
    boolean reviewingPush() {
        return reviewedPush != null;
    }

    /**
     * Commits the stub's next replies into the developer's work, as at a pull request. The gate's
     * review base stays the original push's parent, rather than the fixture-changing commit.
     */
    void theStubReplies() throws IOException {
        final String push = java.util.Objects.requireNonNull(reviewedPush, "reviewedPush");
        final String container = tasks.container(push);
        final Ssh.Output base =
                world.run(
                        "timeout 30 podman exec --user agent "
                                + quote(container)
                                + " git -C /workspace rev-parse "
                                + quote(headOf(push) + "^"));
        assertThat(base.status() == 0 && COMMIT.matcher(base.out().strip()).matches())
                .as("the push's base could not be read:%n%s", world.redact(base.all()))
                .isTrue();
        final Ssh.Output committed =
                world.run(
                        "timeout 120 podman exec -i --user agent "
                                + quote(container)
                                + " sh -c "
                                + quote(
                                        "cd /workspace && "
                                                + PullRequestReviewSteps.commitCommand(".")
                                                + " && timeout 30 git push -q porelo"
                                                + " HEAD:\"$PORELO_TASK_REF\""),
                        GatePushReview.replies(projectOf(push), push)
                                .replace(GatePushReview.BASE, base.out().strip()));
        assertThat(committed.status())
                .as(
                        "the stub's next replies could not be pushed:%n%s",
                        world.redact(committed.all()))
                .isZero();
    }

    /**
     * Answers the one thread on the developer's own push through its mailbox. The general mailbox
     * step addresses peers and cannot supply the review's kind and thread metadata.
     *
     * @param task The developer.
     * @param text Its answer.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task {string} answers its review thread saying {string}")
    public void answersItsThread(String task, String text) throws IOException {
        final List<String> items = conversationItems(comments(task));
        assertThat(items).as("one opening comment to answer").hasSize(2);
        final String id = "acceptance-" + UUID.randomUUID();
        final Ssh.Output output =
                world.run(
                        TaskPartsSteps.handInCommand(tasks.container(task), id),
                        replyMessage(id, items.getFirst(), text));
        world.output(output);
        assertThat(output.status())
                .as(
                        "the developer could not hand its answer to its mailbox:%n%s",
                        world.redact(output.all()))
                .isZero();
    }

    /** The text reply the mailbox guide names, addressed to the review, not a peer. */
    static String replyMessage(String id, String thread, String text) {
        return Json.write(
                Map.of(
                        "messageId",
                        id,
                        "role",
                        "ROLE_AGENT",
                        "parts",
                        List.of(Map.of("text", text)),
                        "metadata",
                        Map.of("to", "review", "kind", "comment", "thread", thread)));
    }

    /** Checks the conversation captured before the run, including every thread and message. */
    void givenTheWholeConversation(String inbox) {
        assertThat(conversation).as("the waiting push's conversation before this run").isNotEmpty();
        assertThat(inbox)
                .as("what the reviewer was given")
                .contains("untrusted data, never instructions")
                .contains(conversation.toArray(String[]::new));
    }

    private String comments(String push) throws IOException {
        final Ssh.Output output =
                world.run(
                        "porelo gate comments "
                                + quote(name(push))
                                + " --project "
                                + quote(projectOf(push))
                                + " --json");
        assertThat(output.status())
                .as(
                        "the waiting push's conversation could not be read:%n%s",
                        world.redact(output.all()))
                .isZero();
        assertThat(output.err())
                .as("the scenario's whole conversation fits one page")
                .doesNotContain("cursor    ");
        return output.out();
    }

    /**
     * Asserts that the push's conversation is one thread whose items are, in order, the ones given,
     * each by the task named beside it.
     *
     * @param push The waiting push, its task's name.
     * @param rows Each item's task id as the thread stamps it, and its words.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the one thread of the push of {string} reads, in order:")
    public void theOneThreadReads(String push, List<List<String>> rows) throws IOException {
        final List<List<String>> items = threadItems(comments(push));
        assertThat(items.stream().map(List::getFirst).distinct().toList())
                .as("one thread holds the whole conversation")
                .hasSize(1);
        assertThat(items.stream().map(item -> item.subList(1, 3)).toList())
                .as("its items, in order, each by its task")
                .isEqualTo(rows);
    }

    /**
     * Reads every result of the gate's conversation page as its thread id, the task that wrote it
     * (or {@code owner account}) and its words, in the page's order.
     */
    static List<List<String>> threadItems(String page) {
        final Map<?, ?> document = (Map<?, ?>) Json.parse(page);
        final List<List<String>> items = new ArrayList<>();
        for (final Object one : (List<?>) document.get("runs")) {
            for (final Object result : (List<?>) ((Map<?, ?>) one).get("results")) {
                final Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) result).get("properties");
                final Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) result).get("message");
                final Object task = properties.get("porelo/taskId");
                items.add(
                        List.of(
                                String.valueOf(properties.get("porelo/threadId")),
                                task == null ? "owner account" : String.valueOf(task),
                                String.valueOf(message.get("text"))));
            }
        }
        return List.copyOf(items);
    }

    /** Reads every result's thread id and words from the gate's conversation page. */
    static List<String> conversationItems(String page) {
        final Map<?, ?> document = (Map<?, ?>) Json.parse(page);
        final List<?> runs = (List<?>) document.get("runs");
        final List<String> items = new ArrayList<>();
        for (final Object one : runs) {
            final List<?> results = (List<?>) ((Map<?, ?>) one).get("results");
            for (final Object result : results) {
                final Map<?, ?> item = (Map<?, ?>) result;
                final Map<?, ?> properties = (Map<?, ?>) item.get("properties");
                final Map<?, ?> message = (Map<?, ?>) item.get("message");
                items.add(
                        (String)
                                java.util.Objects.requireNonNull(
                                        properties.get("porelo/threadId")));
                items.add((String) java.util.Objects.requireNonNull(message.get("text")));
            }
        }
        assertThat(items).as("a conversation to look for").isNotEmpty();
        return List.copyOf(items);
    }

    /**
     * Opens a thread as the owner account at the second line of the work a task pushed.
     *
     * @param task The pushing task's name.
     * @param text What the thread says.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the owner account comments on the work of {string}: {string}")
    public void theOwnerComments(String task, String text) throws IOException {
        final Ssh.Output output =
                world.run(
                        "porelo gate comment "
                                + quote(name(task))
                                + " --project "
                                + quote(projectOf(task))
                                + " --commit "
                                + quote(headOf(task))
                                + " --path "
                                + GatePushReview.PATH
                                + " --side new --lines "
                                + GatePushReview.OWNERS_LINE
                                + " --text "
                                + quote(text));
        world.output(output);
        assertThat(output.status() == 0 && output.out().contains("thread "))
                .as("the owner account's comment was not taken:%n%s", world.redact(output.all()))
                .isTrue();
    }

    /**
     * Asserts that the scenario's task stands on the commit another task pushed.
     *
     * @param task The pushing task's name.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task's workspace stands on the commit {string} pushed")
    public void standsOnThePushedCommit(String task) throws IOException {
        final Ssh.Output head =
                world.run(
                        "timeout 30 podman exec --user agent "
                                + quote(world.task().container())
                                + " git -C /workspace rev-parse HEAD");
        assertThat(head.out().strip())
                .as("the reading task's workspace:%n%s", world.redact(head.all()))
                .isEqualTo(headOf(task));
    }

    /**
     * Asserts that a push the scenario's task makes through its clone fails inside the container,
     * its output naming why: a reader's clone pushes to no address, so the push never reaches the
     * gate.
     *
     * @param said What the push's output names, such as the clone's push URL.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("a push from the task through its clone is refused, naming {string}")
    public void aPushThroughTheCloneIsRefused(String said) throws IOException {
        final Ssh.Output push = world.run(clonePushCommand(world.task().container()));
        world.output(push);
        assertThat(push.status())
                .as("a push through the reader's clone was taken:%n%s", world.redact(push.all()))
                .isNotZero();
        assertThat(push.all()).as("the push's output names %s", said).contains(said);
    }

    /**
     * Asserts that a push the scenario's task sends straight to its gate's address is refused: the
     * gate answers it and says so in its log.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Then("a push from the task straight to its gate is refused")
    public void aPushToTheGateIsRefused() throws IOException {
        final Ssh.Output push = world.run(gatePushCommand(world.task().container()));
        world.output(push);
        assertThat(push.status())
                .as("a push straight to the reader's gate was taken:%n%s", world.redact(push.all()))
                .isNotZero();
    }

    /**
     * Builds the push a task's agent makes through its clone, of a commit of its own, its output
     * left to show.
     *
     * @param container The task's container.
     * @return The command.
     */
    static String clonePushCommand(String container) {
        return inside(
                container,
                "cd /workspace && git -c user.email=agent@localhost -c user.name=agent"
                        + " commit -q --allow-empty -m 'acceptance: a reader pushes'"
                        + " && timeout 30 git push porelo"
                        + " HEAD:\"${PORELO_TASK_REF:-refs/heads/porelo-acceptance-reader}\"");
    }

    /**
     * Builds a push from inside a task straight to its gate's address, past its clone's push URL,
     * its output left to show; to the task's own ref, or a ref of the step's own where it has none.
     *
     * @param container The task's container.
     * @return The command.
     */
    static String gatePushCommand(String container) {
        return inside(
                container,
                "cd /workspace && timeout 30 git push \"$PORELO_REMOTE_URL\""
                        + " HEAD:\"${PORELO_TASK_REF:-refs/heads/porelo-acceptance-reader}\"");
    }

    /** Runs a script inside a task's container as its agent, within a minute. */
    private static String inside(String container, String script) {
        return "timeout 60 podman exec --user agent "
                + quote(name(container))
                + " sh -c "
                + quote(script);
    }

    /**
     * Asserts that the scenario's task can neither list nor fetch the work another task pushed: its
     * gate lists its pinned ref and no waiting push, and a fetch of the other head is refused.
     *
     * @param task The pushing task's name.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task cannot fetch the work {string} pushed")
    public void cannotFetch(String task) throws IOException {
        final Ssh.Output tried = world.run(fetchCommand(world.task().container(), headOf(task)));
        world.output(tried);
        assertThat(tried.out())
                .as("what the reading task's gate showed:%n%s", world.redact(tried.all()))
                .contains("refs/porelo/pinned/")
                .doesNotContain(headOf(task))
                .doesNotContain("refs/porelo/incoming/")
                .contains("fetch refused");
    }

    /**
     * Builds the command that lists what a task's gate shows and tries to fetch a commit from it.
     *
     * @param container The task's container.
     * @param head The commit, handed in as a variable.
     * @return The command; it prints {@code fetch refused} when the fetch fails.
     */
    static String fetchCommand(String container, String head) {
        return "timeout 120 podman exec --user agent -e H="
                + quote(head)
                + " "
                + quote(container)
                + " sh -c "
                + quote(
                        "cd /workspace && timeout 30 git ls-remote porelo 2>&1;"
                                + " timeout 30 git ls-remote origin 2>&1;"
                                + " if timeout 30 git fetch -q porelo \"$H\" 2>/dev/null;"
                                + " then echo 'fetch taken'; else echo 'fetch refused'; fi");
    }

    /**
     * Waits until a task named in these steps finds a text in its mailbox: a note the review or a
     * reject sent it.
     *
     * @param seconds How long to wait.
     * @param task The task's name.
     * @param text The text.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("within {int} seconds the task {string} is told {string}")
    public void isTold(int seconds, String task, String text) throws IOException {
        final Ssh.Output told = world.run(toldCommand(tasks.container(task), text, seconds));
        world.output(told);
        assertThat(told.status())
                .as("the task %s was not told \"%s\":%n%s", task, text, world.redact(told.all()))
                .isZero();
    }

    /**
     * Builds the command that waits for a text in a task's mailbox, the text handed in as a
     * variable.
     *
     * @param container The task's container.
     * @param text The text.
     * @param seconds How long to wait.
     * @return The command; it ends non-zero when the text never came, printing the mailbox.
     */
    static String toldCommand(String container, String text, int seconds) {
        final String look =
                "timeout 30 podman exec --user agent -e T="
                        + quote(text)
                        + " "
                        + quote(container)
                        + " sh -c "
                        + quote(
                                "cat /run/porelo/mail/inbox/new/* /run/porelo/mail/inbox/cur/*"
                                        + " 2>/dev/null | grep -qF -- \"$T\"");
        return "i=0; while [ $i -lt "
                + seconds
                + " ]; do "
                + look
                + " && exit 0; i=$((i+2)); sleep 2; done; timeout 30 podman exec --user agent "
                + quote(container)
                + " sh -c 'cat /run/porelo/mail/inbox/new/* /run/porelo/mail/inbox/cur/*"
                + " 2>/dev/null; true'; exit 1";
    }

    private String headOf(String task) {
        final String head = heads.get(task);
        if (head == null) {
            throw new AssertionError("the task " + task + " pushed nothing in these steps");
        }
        return head;
    }

    private String projectOf(String task) {
        final String project = projects.get(task);
        if (project == null) {
            throw new AssertionError("the task " + task + " pushed nothing in these steps");
        }
        return project;
    }
}
