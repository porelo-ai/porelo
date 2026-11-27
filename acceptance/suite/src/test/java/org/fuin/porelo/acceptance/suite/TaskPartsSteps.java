package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.acceptance.suite.ScenarioTasks.name;
import static org.fuin.porelo.acceptance.suite.ScenarioTasks.quote;
import static org.fuin.porelo.acceptance.suite.ScenarioTasks.words;

import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Steps for the parts a task is started with and what its own endpoint carries: starts through the
 * daemon and on the command line naming parts, a task's calls of its own MCP endpoint from inside
 * its container, the parts the daemon's {@code List} shows, and a message a task hands its mailbox.
 */
public final class TaskPartsSteps {

    private static final Pattern CONTAINER =
            Pattern.compile("^container\\s+(\\S+)", Pattern.MULTILINE);

    private static final Pattern SIDECAR = Pattern.compile("^sidecar\\s+(\\S+)", Pattern.MULTILINE);

    /** How long a start may take: an image may be built first. */
    private static final int START_SECONDS = 900;

    /** How long one call of a task's endpoint may take: a child's start waits for its run. */
    static final int CALL_SECONDS = 300;

    /** The members of a task's parts as the daemon's row writes them. */
    static final Set<String> PARTS =
            Set.of("workspace", "readLanded", "projectServices", "writeAs", "rights");

    /** Where a task's agent writes a message before it moves it into the outbox. */
    private static final String OUTBOX = "/run/porelo/mail/outbox";

    private final World world;

    /** Every task the steps started or saw started through an endpoint, by name, to remove. */
    private final ScenarioTasks tasks;

    /** The transport the scenario installed, or {@code null}. */
    private @Nullable String transport;

    /**
     * Creates the steps over the scenario's world.
     *
     * @param world The scenario's world.
     * @param tasks The tasks the scenario's steps started.
     */
    public TaskPartsSteps(World world, ScenarioTasks tasks) {
        this.world = world;
        this.tasks = tasks;
    }

    /**
     * Starts a task through the daemon's {@code Start}, naming no part, and leaves it running: a
     * project whose file has an {@code mcp} section gives its tasks an endpoint, which only the
     * daemon opens, so the command line's start is refused there.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @param agent The agent the step names.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "a task called {string} is started in {string} through the daemon for the {word} agent"
                    + " and left running")
    public void aDaemonStart(String task, String project, String agent) throws IOException {
        daemonStart(task, project, agent, null);
    }

    /**
     * Starts a task through the daemon's {@code Start} with the rights named, and leaves it
     * running.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @param agent The agent the step names.
     * @param rights The rights, separated by commas.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "a task called {string} is started in {string} through the daemon for the {word} agent"
                    + " with the rights {string} and left running")
    public void aDaemonStartWithRights(String task, String project, String agent, String rights)
            throws IOException {
        daemonStart(task, project, agent, words(rights));
    }

    private void daemonStart(
            String task, String project, String agent, @Nullable List<String> rights)
            throws IOException {
        final Ssh.Output output =
                world.run(
                        "printf '%s\\0' "
                                + quote(startRequest(name(task), name(project), agent, rights))
                                + " | timeout "
                                + START_SECONDS
                                + " porelo daemon connect | tr '\\0' '\\n'");
        world.output(output);
        final Map<?, ?> reply = lastReply(output.out());
        final Object parameters = reply.get("parameters");
        assertThat(reply.get("error") == null && parameters instanceof Map<?, ?>)
                .as("the daemon's Start refused %s:%n%s", task, world.redact(output.all()))
                .isTrue();
        final Map<?, ?> answered = (Map<?, ?>) parameters;
        final Object container = answered.get("container");
        assertThat(container instanceof String && Integer.valueOf(0).equals(exitCode(answered)))
                .as("the daemon did not start %s:%n%s", task, world.redact(output.all()))
                .isTrue();
        started(task, (String) container, output.all());
    }

    /**
     * Builds the daemon's {@code Start} of a task left running at its own work: its agent's own
     * session, no clearance asked.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @param agent The task's agent.
     * @param rights The rights it names, or {@code null} for none named.
     * @return The request, one line.
     */
    static String startRequest(
            String task, String project, String agent, @Nullable List<String> rights) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", task);
        parameters.put("project", project);
        parameters.put("repository", project);
        parameters.put("agent", agent);
        parameters.put("clearance", "deny");
        parameters.put("mode", "AGENT");
        if (rights != null) {
            parameters.put("rights", rights);
        }
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "org.fuin.porelo.Tasks1.Start");
        request.put("parameters", parameters);
        return Json.write(request);
    }

    /**
     * Starts a task on the command line naming no part, and leaves it running.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "a task called {string} is started in {string} for the stub agent naming no part and"
                    + " left running")
    public void aStartNamingNoPart(String task, String project) throws IOException {
        aStartWithOptions(task, project, "");
    }

    /**
     * Starts a task on the command line with options of the scenario's, and leaves it running.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @param options The options beside project, repository, agent and clearance, separated by
     *     spaces.
     * @throws IOException If the machine cannot be reached.
     */
    @When(
            "a task called {string} is started in {string} for the stub agent with the options"
                    + " {string} and left running")
    public void aStartWithOptions(String task, String project, String options) throws IOException {
        final Ssh.Output output = world.run(startCommand(task, project, options));
        world.output(output);
        final Matcher container = CONTAINER.matcher(output.all());
        assertThat(output.status() == 0 && container.find())
                .as("the task %s did not start:%n%s", task, world.redact(output.all()))
                .isTrue();
        started(task, container.group(1), output.all());
    }

    /**
     * Builds the command line's start of a task left running, with the scenario's options.
     *
     * @param task The task's name.
     * @param project The project, which is also its repository.
     * @param options The further options, separated by spaces.
     * @return The command.
     */
    static String startCommand(String task, String project, String options) {
        final StringBuilder command =
                new StringBuilder("timeout ")
                        .append(START_SECONDS)
                        .append(" porelo task start ")
                        .append(quote(name(task)))
                        .append(" --project ")
                        .append(quote(name(project)))
                        .append(" --repository ")
                        .append(quote(name(project)))
                        .append(" --agent stub --detach --clearance deny");
        for (final String option : options.strip().split("\\s+")) {
            if (!option.isEmpty()) {
                command.append(' ').append(quote(option));
            }
        }
        return command.toString();
    }

    private void started(String task, String container, String said) {
        tasks.started(task, container);
        final Matcher sidecar = SIDECAR.matcher(said);
        final java.nio.file.Path parent =
                sidecar.find() ? java.nio.file.Path.of(sidecar.group(1)).getParent() : null;
        world.task(new World.Task(container, parent == null ? null : parent.toString(), said));
    }

    /**
     * Has a task's agent commit a change and push it to its gate, where it waits.
     *
     * @param task The task's name.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task {string} pushes a change to its gate")
    public void pushes(String task) throws IOException {
        final Ssh.Output output = world.run(pushCommand(container(task), task));
        world.output(output);
        assertThat(output.status())
                .as("the task %s could not push:%n%s", task, world.redact(output.all()))
                .isZero();
    }

    /**
     * Builds the push of one change from inside a task, as its agent makes it.
     *
     * @param container The task's container.
     * @param task The task's name, which the change and its subject carry.
     * @return The command.
     */
    static String pushCommand(String container, String task) {
        return "timeout 90 podman exec --user agent "
                + quote(container)
                + " sh -c "
                + quote(
                        "cd /workspace && echo "
                                + quote(name(task))
                                + " >> README.md && git add README.md && git -c"
                                + " user.email=agent@localhost -c user.name=agent commit -qm "
                                + quote("acceptance: the change of " + name(task))
                                + " && timeout 30 git push -q porelo HEAD:\"$PORELO_TASK_REF\"");
    }

    /**
     * Asserts which tools a task's own endpoint offers it, as its {@code tools/list} answers.
     *
     * @param task The task's name.
     * @param offered The tools it offers, separated by commas.
     * @param lacking The tools it does not offer, separated by commas.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the own endpoint of the task {string} offers {string} and not {string}")
    public void offers(String task, String offered, String lacking) throws IOException {
        final Ssh.Output output = world.run(endpointCommand(container(task)), listRequest());
        world.output(output);
        final List<String> tools = tools(answer(output.out()));
        assertThat(tools)
                .as("the tools of the endpoint of %s:%n%s", task, world.redact(output.all()))
                .containsAll(words(offered))
                .doesNotContainAnyElementsOf(words(lacking));
    }

    /**
     * Has a task call one tool of its own endpoint from inside its container, with the
     * Authorization its guide names; what came back is the step's output, the HTTP status last.
     *
     * @param task The task's name.
     * @param method The tool, a daemon method's name.
     * @param arguments The arguments, one JSON object; {@code {container}} stands for the calling
     *     task's container, the name its socket's own task is known by.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task {string} calls {string} on its own endpoint with:")
    public void calls(String task, String method, String arguments) throws IOException {
        final String container = container(task);
        final Ssh.Output output =
                world.run(
                        endpointCommand(container),
                        callRequest(method, withContainer(arguments, container)));
        world.output(output);
        final @Nullable String child = startedChild(method, output.out());
        if (child != null) {
            tasks.started(child, child);
        }
    }

    /**
     * Puts the calling task's container where a call's arguments say {@code {container}}.
     *
     * @param arguments The arguments as the scenario wrote them.
     * @param container The calling task's container.
     * @return The arguments.
     * @throws IllegalArgumentException When the container is not a name.
     */
    static String withContainer(String arguments, String container) {
        return arguments.replace("{container}", name(container));
    }

    /**
     * Builds the command a task's agent reaches its endpoint with: the request on standard input,
     * the answer's body and then its HTTP status on a line of its own.
     *
     * @param container The task's container.
     * @return The command.
     */
    static String endpointCommand(String container) {
        return "timeout "
                + (CALL_SECONDS + 30)
                + " podman exec -i --user agent "
                + quote(container)
                + " sh -c "
                + quote(
                        "curl -sS --max-time "
                                + CALL_SECONDS
                                + " -w '\\n%{http_code}'"
                                + " -H \"Authorization: Bearer $PORELO_TOKEN_PORELO_MCP\""
                                + " -H 'Content-Type: application/json'"
                                + " -H 'Accept: application/json'"
                                + " --data-binary @- \"$PORELO_URL_PORELO_MCP\"");
    }

    /**
     * Builds the request that lists an endpoint's tools.
     *
     * @return One JSON-RPC message.
     */
    static String listRequest() {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/list");
        return Json.write(request);
    }

    /**
     * Builds the request that calls one tool.
     *
     * @param method The tool.
     * @param arguments Its arguments, one JSON object.
     * @return One JSON-RPC message.
     */
    static String callRequest(String method, String arguments) {
        final Object parsed = Json.parse(arguments.isBlank() ? "{}" : arguments);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("The arguments are no JSON object: " + arguments);
        }
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", method);
        params.put("arguments", parsed);
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "tools/call");
        request.put("params", params);
        return Json.write(request);
    }

    /**
     * Reads an endpoint's answer: the body before the last line, which is the HTTP status.
     *
     * @param said What the call printed.
     * @return The answer.
     * @throws AssertionError When the status is not 200 or the body is no JSON object.
     */
    static Map<?, ?> answer(String said) {
        final String trimmed = said.stripTrailing();
        final int last = trimmed.lastIndexOf('\n');
        final String status = trimmed.substring(last + 1).strip();
        final String body = last < 0 ? "" : trimmed.substring(0, last);
        if (!"200".equals(status)) {
            throw new AssertionError("the endpoint answered HTTP " + status + ": " + body);
        }
        if (!(Json.parse(body) instanceof Map<?, ?> answer)) {
            throw new AssertionError("the endpoint's answer is no JSON object: " + body);
        }
        return answer;
    }

    /**
     * Returns the names of the tools a {@code tools/list} answer lists.
     *
     * @param answer The answer.
     * @return The names, in its order.
     */
    static List<String> tools(Map<?, ?> answer) {
        final List<String> names = new ArrayList<>();
        if (answer.get("result") instanceof Map<?, ?> result
                && result.get("tools") instanceof List<?> listed) {
            for (final Object tool : listed) {
                if (tool instanceof Map<?, ?> each && each.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Returns the container a {@code Start} through the endpoint made, so the scenario removes it.
     *
     * @param method The tool called.
     * @param said What the call printed.
     * @return The container, or {@code null} when it was no start or made none.
     */
    static @Nullable String startedChild(String method, String said) {
        if (!"Start".equals(method)) {
            return null;
        }
        final Map<?, ?> answer;
        try {
            answer = answer(said);
        } catch (AssertionError | RuntimeException notAnAnswer) {
            return null;
        }
        if (answer.get("result") instanceof Map<?, ?> result
                && result.get("structuredContent") instanceof Map<?, ?> content
                && content.get("container") instanceof String container
                && ScenarioTasks.isName(container)) {
            return container;
        }
        return null;
    }

    /**
     * Asserts the parts the daemon's {@code List} shows for a task, each member written out.
     *
     * @param task The task's name within its project.
     * @param project The project.
     * @param parts The members expected, by name: {@code rights} separated by commas, the others as
     *     they are written.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the daemon lists the task {string} of {string} with these parts:")
    public void listedWith(String task, String project, Map<String, @Nullable String> parts)
            throws IOException {
        final Ssh.Output output =
                world.run(
                        "printf '%s\\0' '{\"method\":\"org.fuin.porelo.Tasks1.List\","
                                + "\"parameters\":{}}' | timeout 60 porelo daemon connect"
                                + " | tr '\\0' '\\n' | head -1");
        world.output(output);
        final Map<?, ?> row = row(output.out(), name(project), name(task));
        assertThat(differences(row, parts))
                .as("the parts of %s in %s: %s", task, project, row)
                .isEmpty();
    }

    /**
     * Returns the row of one task in the daemon's {@code List} reply.
     *
     * @param said The reply, one JSON message on its first line.
     * @param project The task's project.
     * @param task The task's name within it.
     * @return The row.
     * @throws AssertionError When the reply lists no such task.
     */
    static Map<?, ?> row(String said, String project, String task) {
        final Map<?, ?> reply = lastReply(said);
        if (reply.get("parameters") instanceof Map<?, ?> parameters
                && parameters.get("tasks") instanceof List<?> rows) {
            for (final Object each : rows) {
                if (each instanceof Map<?, ?> row
                        && project.equals(row.get("project"))
                        && task.equals(row.get("task"))) {
                    return row;
                }
            }
        }
        throw new AssertionError(
                "the daemon lists no task " + task + " of " + project + ": " + said);
    }

    /**
     * Returns where a row's parts differ from what a scenario expects: {@code rights} as a set,
     * every other member by its written value.
     *
     * @param row The task's row.
     * @param expected The members expected.
     * @return One line per member that differs, empty when none does.
     */
    static List<String> differences(Map<?, ?> row, Map<String, @Nullable String> expected) {
        final List<String> differ = new ArrayList<>();
        expected.forEach(
                (member, cell) -> {
                    // An empty cell of a table comes as null: no right, no landed work.
                    final String value = cell == null ? "" : cell;
                    if (!PARTS.contains(member)) {
                        differ.add(member + ": no member of a task's parts");
                        return;
                    }
                    final Object listed = row.get(member);
                    final boolean same =
                            "rights".equals(member)
                                    ? listed instanceof List<?> held
                                            && new TreeSet<>(
                                                            held.stream()
                                                                    .map(String::valueOf)
                                                                    .toList())
                                                    .equals(new TreeSet<>(words(value)))
                                    : listed != null
                                            && value.strip().equals(String.valueOf(listed));
                    if (!same) {
                        differ.add(member + ": expected " + value + ", listed " + listed);
                    }
                });
        return differ;
    }

    /**
     * Installs a message transport into the account that takes every message and never delivers
     * one: it answers each send as a temporary failure, so what reached its queue stays there to be
     * seen. Removed after the scenario.
     *
     * @param scheme The transport's scheme, the part of a peer's address before the colon.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a message transport {string} that keeps every message waiting is installed")
    public void aWaitingTransport(String scheme) throws IOException {
        final Ssh.Output output =
                world.run(transportCommand(name(scheme)), transportScript(name(scheme)));
        world.output(output);
        assertThat(output.status())
                .as("the transport %s could not be installed:%n%s", scheme, output.all())
                .isZero();
        transport = scheme;
    }

    /**
     * Builds the command that installs a transport from its script on standard input, refusing one
     * already there, which is not the scenario's.
     *
     * @param scheme The transport's scheme.
     * @return The command.
     */
    static String transportCommand(String scheme) {
        return "d=\"${XDG_DATA_HOME:-$HOME/.local/share}/porelo/transports\";"
                + " f=\"$d/porelo-message-transport-"
                + scheme
                + "\"; if [ -e \"$f\" ]; then echo \"refused: $f is not this scenario's\";"
                + " exit 1; fi; mkdir -p \"$d\" && cat > \"$f\" && chmod 700 \"$f\""
                + " && \"$f\" describe";
    }

    /**
     * Returns the transport's script: it describes itself, and answers every send as a failure to
     * try again later.
     *
     * @param scheme The transport's scheme.
     * @return The script.
     */
    static String transportScript(String scheme) {
        return "#!/usr/bin/python3\n"
                + "import json, sys\n"
                + "if sys.argv[1] == \"describe\":\n"
                + "    print(json.dumps({\"scheme\": \""
                + scheme
                + "\", \"poll\": False, \"confirms\": \"handover\", \"max_bytes\": 1048576,"
                + " \"credentials\": [], \"hosts\": [], \"attests\": [], \"secrets\": \"input\"}))\n"
                + "elif sys.argv[1] == \"send\":\n"
                + "    sys.stdin.read()\n"
                + "    sys.exit(75)\n"
                + "else:\n"
                + "    sys.exit(64)\n";
    }

    /**
     * Has the scenario's task's agent hand its mailbox one message, as its guide says: written to
     * the outbox's {@code tmp} and moved into {@code new}.
     *
     * @param to The one name it addresses.
     * @param text What it says.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task's agent hands its mailbox a message to {string} saying {string}")
    public void handsAMessage(String to, String text) throws IOException {
        final String id = "acceptance-" + UUID.randomUUID();
        final Ssh.Output output =
                world.run(handInCommand(world.task().container(), id), message(id, to, text));
        world.output(output);
        assertThat(output.status())
                .as("the agent could not write its message:%n%s", world.redact(output.all()))
                .isZero();
    }

    /**
     * Builds the command that writes a message from standard input into a task's outbox.
     *
     * @param container The task's container.
     * @param id The message's id, which names its file.
     * @return The command.
     */
    static String handInCommand(String container, String id) {
        final String file = id + ".json";
        return "timeout 60 podman exec -i --user agent "
                + quote(container)
                + " sh -c "
                + quote(
                        "cat > " + OUTBOX + "/tmp/" + file + " && mv " + OUTBOX + "/tmp/" + file
                                + " " + OUTBOX + "/new/");
    }

    /**
     * Builds a message as a task's guide shows it.
     *
     * @param id Its id.
     * @param to The one name it addresses.
     * @param text What it says.
     * @return The message, one JSON object.
     */
    static String message(String id, String to, String text) {
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("to", to);
        metadata.put("via", "room");
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", id);
        message.put("role", "ROLE_AGENT");
        message.put("parts", List.of(Map.of("text", text)));
        message.put("metadata", metadata);
        return Json.write(message);
    }

    /**
     * Removes the transport the steps installed, whether the scenario passed or not; the tasks they
     * started go with {@link ScenarioTasks}. Best effort, logged.
     *
     * @param scenario The scenario that ended.
     */
    @After
    public void removeTheTransport(Scenario scenario) {
        final @Nullable String installed = transport;
        if (installed != null) {
            try {
                world.run(
                        "rm -f -- \"${XDG_DATA_HOME:-$HOME/.local/share}/porelo/transports/"
                                + "porelo-message-transport-"
                                + installed
                                + "\"");
            } catch (final IOException | RuntimeException ex) {
                scenario.log(
                        "could not remove the transport " + installed + ": " + ex.getMessage());
            }
            transport = null;
        }
    }

    private String container(String task) {
        return tasks.container(task);
    }

    /**
     * Returns the last reply a {@code porelo daemon connect} printed, one JSON message a line.
     *
     * @param said What it printed.
     * @return The reply.
     * @throws AssertionError When no line is a JSON object.
     */
    static Map<?, ?> lastReply(String said) {
        final List<String> lines = said.lines().filter(line -> !line.isBlank()).toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                if (Json.parse(lines.get(i)) instanceof Map<?, ?> reply) {
                    return reply;
                }
            } catch (RuntimeException notJson) {
                // A line of another kind; the reply is a line of its own.
            }
        }
        throw new AssertionError("the daemon gave no reply: " + said);
    }

    private static @Nullable Object exitCode(Map<?, ?> answered) {
        return answered.get("exitCode") instanceof Number number
                ? Integer.valueOf(number.intValue())
                : null;
    }
}
