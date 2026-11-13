package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.INTERFACE;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.app.McpDaemonSockets;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskIdentities;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.app.TaskReservations;
import org.fuin.porelo.core.project.McpEndpoint;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The per-task daemon sockets {@code porelod} serves a task's {@code porelo-mcp} on, one per task
 * its project's signed file allows an endpoint.
 *
 * <p><strong>The caller is the socket.</strong> Each socket is bound to one task, and every call on
 * it runs as that task: the methods take the caller from {@link #caller()}, which only this sets,
 * never from a field a call carries. So what {@code porelo-mcp} says, or what the agent behind it
 * asks it to say, names no other task.
 *
 * <p><strong>Only what the file allows.</strong> A socket carries the methods the file's {@code
 * mcp.methods} names and no other, so a method not named answers {@code MethodNotFound}; its
 * interface description is Tasks1 with every other method dropped, every type and error kept. A
 * {@code Start} on it is held to the file's limits and records the caller as the started task's
 * parent; a {@code Stop} on it takes only the caller's own, and any other call naming a task only
 * the caller or its own.
 */
final class McpTaskSockets implements McpDaemonSockets.Opener, AutoCloseable {

    /** The directory under Porelo's runtime directory the sockets lie in. */
    static final String DIRECTORY = "mcp";

    /** What a task's socket is called in it, after the task. */
    static final String SUFFIX = ".daemon.sock";

    /** The task a call came in on, bound for the call's length on a task's socket alone. */
    private static final ScopedValue<String> CALLER = ScopedValue.newInstance();

    /** What a task is called: a container name, which becomes a file name here. */
    private static final java.util.regex.Pattern TASK =
            java.util.regex.Pattern.compile("porelo-[a-z0-9][a-z0-9_-]{0,200}");

    private final PoreloContext context;
    private final TaskInventory inventory;
    private final TaskControl control;
    private final Map<String, Served> open = new ConcurrentHashMap<>();

    /**
     * The starts running now over the tasks' sockets, one entry per call under a token of its own,
     * so two starts at once both count and one ending frees only its own place. Guarded by itself.
     */
    private final Map<Object, Starting> starting = new java.util.HashMap<>();

    /** One start running now: who asked for it, and the container it is making or bringing back. */
    private record Starting(String caller, String container) {}

    /**
     * The parameters a start over a task's socket may carry; any other is refused by name. What
     * widens what the started task may reach, hold or spend is the file's or the person's: its
     * clearance, gate, credentials, upstream, token, provider and subagents, and so are a removal,
     * a certificate and a start that returns before its run.
     */
    static final Set<String> START_PARAMETERS =
            Set.of(
                    "task",
                    "project",
                    "repository",
                    "branch",
                    "from",
                    "prompt",
                    "agent",
                    "model",
                    "minutes",
                    "mode",
                    "maxTurns",
                    "parameters",
                    "dryRun",
                    "steps",
                    "work");

    /** The methods a task's endpoint may carry beside the reading ones. */
    private static final Set<String> WRITING = Set.of("Start", "Stop", "Done", "ProposeIdea");

    /** One socket, and the thread serving it. */
    private record Served(VarlinkServer server, Thread thread) {}

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    McpTaskSockets(final PoreloContext context) {
        this(context, org.fuin.porelo.app.AgentSignInRuns.of(context));
    }

    private final org.fuin.porelo.app.AgentSignInRuns signIns;

    McpTaskSockets(PoreloContext context, org.fuin.porelo.app.AgentSignInRuns signIns) {
        this.signIns = signIns;
        this.context = context;
        this.inventory = TaskInventory.deriving(context);
        this.control = new TaskControl(context);
    }

    /**
     * Returns the machine the sockets serve.
     *
     * @return It.
     */
    PoreloContext context() {
        return context;
    }

    /**
     * Returns the task whose socket the running call came in on.
     *
     * @return Its container name, or {@code null} on the person's own socket or outside a call.
     */
    static @Nullable String caller() {
        return CALLER.isBound() ? CALLER.get() : null;
    }

    /**
     * Runs something as a task's socket runs a call.
     *
     * @param task The caller.
     * @param body What to run.
     */
    static void as(final String task, final Runnable body) {
        ScopedValue.where(CALLER, task).run(body);
    }

    /**
     * Says what is wrong with the methods a file's {@code mcp} section names, which only the daemon
     * can say: whether each is one it never offers to MCP, or one it does not have.
     *
     * @param methods The names, without the interface.
     * @return One refusal per name, in order; empty when every one may be carried.
     */
    static List<String> refusals(final List<String> methods) {
        final Set<String> described = McpMethods.described();
        final List<String> refused = new ArrayList<>();
        for (final String method : new LinkedHashSet<>(methods)) {
            if (McpMethods.NEVER.contains(method)) {
                refused.add(
                        "mcp.methods: '"
                                + method
                                + "' is never offered to MCP: it stops every task, removes what"
                                + " cannot be had back, or hands out a secret");
            } else if (!described.contains(method)) {
                refused.add(
                        "mcp.methods: '"
                                + method
                                + "' is no method of "
                                + INTERFACE
                                + " this daemon has");
            } else if (!McpMethods.READING.contains(method) && !WRITING.contains(method)) {
                refused.add(
                        "mcp.methods: '"
                                + method
                                + "' is not offered to a task: its endpoint carries only the"
                                + " reading methods McpMethods names, Start, Stop, Done and ProposeIdea");
            }
        }
        return List.copyOf(refused);
    }

    /**
     * Returns where a task's socket lies.
     *
     * @param task The task.
     * @return {@code $XDG_RUNTIME_DIR/porelo/mcp/<task>.daemon.sock}.
     */
    Path socketOf(final String task) {
        return context.paths().daemonSocket().resolveSibling(DIRECTORY).resolve(task + SUFFIX);
    }

    @Override
    public synchronized Optional<Path> open(final String task, final String project)
            throws IOException {
        if (!TASK.matcher(task).matches()) {
            throw new IOException("'" + task + "' is no task's name");
        }
        final var identity = new TaskIdentities(context).lookup(task, project);
        final var reserved =
                identity instanceof TaskIdentities.Found
                        ? null
                        : new TaskReservations(context).unmade(task);
        if (!(identity instanceof TaskIdentities.Found)
                && !(reserved != null && project.equals(reserved.project()))) {
            throw new IOException(
                    "The identity of " + task + " does not belong to project " + project);
        }
        if (open.containsKey(task)) {
            return Optional.of(socketOf(task));
        }
        final McpEndpoint endpoint;
        try {
            final Optional<McpEndpoint> allowed =
                    ProjectReader.endpoint(
                            org.fuin.porelo.app.ProjectSource.require(context, project));
            if (allowed.isEmpty()) {
                close(task);
                return Optional.empty();
            }
            endpoint = allowed.get();
        } catch (ProjectException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        final List<String> refused = refusals(endpoint.methods());
        if (!refused.isEmpty()) {
            throw new IOException(
                    "project '" + project + "' cannot give a task an endpoint: " + refused);
        }
        final Path socket = socketOf(task);
        final TaskSocket server;
        try {
            server = new TaskSocket(socket, task, project, endpoint, takingATask());
        } catch (VarlinkException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        final Thread thread;
        try {
            server.describedBy(cut(PoreloDaemon.description(), new HashSet<>(endpoint.methods())))
                    .reporting(org.fuin.porelo.app.PoreloVersion.version());
            PoreloDaemon.register(server, context, inventory, control, signIns);
            thread = Thread.ofPlatform().daemon().name("porelod-mcp-" + task).start(server);
        } catch (RuntimeException ex) {
            // Bound and not served: the socket goes, as a stale one would.
            server.close();
            throw new IOException("the socket of " + task + " cannot be served", ex);
        }
        open.put(task, new Served(server, thread));
        return Optional.of(socket);
    }

    @Override
    public synchronized void close(final String task) {
        final Served served = open.remove(task);
        if (served == null) {
            return;
        }
        served.server().close();
        try {
            served.thread().join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        for (final String task : List.copyOf(open.keySet())) {
            close(task);
        }
    }

    /**
     * Returns an interface description with only some of its methods: each other method is dropped
     * with the comment above it, and every type and error is kept, so the rest parses as a whole
     * interface and an error a kept method raises is declared.
     *
     * @param idl The interface description.
     * @param keep The methods to keep, by name without the interface.
     * @return The description cut down.
     */
    static String cut(final String idl, final Set<String> keep) {
        final List<String> lines = idl.lines().toList();
        final StringBuilder kept = new StringBuilder();
        final List<String> comment = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            final String line = lines.get(i);
            if (line.startsWith("#")) {
                comment.add(line);
                i++;
                continue;
            }
            final java.util.regex.Matcher method =
                    java.util.regex.Pattern.compile("^method\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(")
                            .matcher(line);
            if (method.find()) {
                final int end = endOfMethod(lines, i);
                if (keep.contains(method.group(1))) {
                    comment.forEach(each -> kept.append(each).append('\n'));
                    for (int j = i; j <= end; j++) {
                        kept.append(lines.get(j)).append('\n');
                    }
                }
                comment.clear();
                i = end + 1;
                continue;
            }
            comment.forEach(each -> kept.append(each).append('\n'));
            comment.clear();
            kept.append(line).append('\n');
            i++;
        }
        comment.forEach(each -> kept.append(each).append('\n'));
        // With the comment above each: a task's start refuses both, so a task is not told of them.
        return kept.toString()
                .replaceAll(
                        "(?m)(?:^  #.*\\R)*^  (?:waitForSignIn|allRepositories): \\?bool,\\R", "");
    }

    /**
     * Returns the last line of the method that starts at a line: where its parameters and then its
     * reply have closed, skipping comments, which may hold a bracket of their own.
     */
    private static int endOfMethod(final List<String> lines, final int start) {
        int depth = 0;
        int closed = 0;
        for (int i = start; i < lines.size(); i++) {
            final String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                final char ch = line.charAt(c);
                if (ch == '#') {
                    break;
                }
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        closed++;
                    }
                }
            }
            if (closed >= 2 && depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException(
                "The interface description ends inside a method: " + lines.get(start));
    }

    /**
     * Refuses a start the file's limits do not allow, and otherwise runs it while counting it as
     * one of the caller's: a prompt run unattended, in the caller's own project, with only the
     * parameters a task may set, and a task brought back only when it is the caller's and with the
     * agent and model it was made with.
     */
    void start(
            final String caller,
            final String project,
            final McpEndpoint endpoint,
            final Map<String, Object> parameters,
            final VarlinkServer.Replies replies,
            final VarlinkServer.Method start)
            throws IOException {
        if (!(new TaskIdentities(context).lookup(caller, project)
                instanceof TaskIdentities.Found)) {
            throw notItsOwn(caller, null);
        }
        for (final String parameter : new java.util.TreeSet<>(parameters.keySet())) {
            if (!START_PARAMETERS.contains(parameter)) {
                throw new VarlinkException(
                        INTERFACE + ".ParameterNotAllowed", Map.of("parameter", parameter));
            }
        }
        final String asked = PoreloDaemon.text(parameters, "project");
        if (!project.equals(asked)) {
            throw new VarlinkException(
                    INTERFACE + ".ProjectNotItsOwn", Map.of("project", asked, "own", project));
        }
        final Object mode = parameters.get("mode");
        if (mode != null
                && !(mode instanceof String named
                        && TaskMode.parse(named, TaskMode.SHELL) == TaskMode.UNATTENDED)) {
            throw new VarlinkException(
                    INTERFACE + ".UnattendedOnly", Map.of("mode", String.valueOf(mode)));
        }
        if (PoreloDaemon.absent(parameters, "prompt") == null) {
            // Without one the container stays up with no run to end it.
            throw new VarlinkException(INTERFACE + ".PromptRequired", Map.of());
        }
        final Object given = parameters.get("minutes");
        final int minutes;
        if (given == null) {
            minutes = TaskLaunch.DEFAULT_MINUTES;
        } else if (given instanceof Number number) {
            minutes = org.fuin.porelo.wire.varlink.VarlinkNumbers.intOf(number);
        } else {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", "minutes"));
        }
        if (minutes < 1) {
            // Over before it began, the run would leave its container up.
            throw new VarlinkException(INTERFACE + ".RunTooShort", Map.of("minutes", minutes));
        }
        if (minutes > endpoint.minutes()) {
            throw new VarlinkException(
                    INTERFACE + ".RunTooLong",
                    Map.of("minutes", minutes, "limit", endpoint.minutes()));
        }
        final TaskLaunch.Request request = PoreloDaemon.request(parameters, context);
        final boolean unnamed = PoreloDaemon.text(parameters, "task").isEmpty();
        final TaskIdentities identities = new TaskIdentities(context);
        Map<String, Object> held = new LinkedHashMap<>(parameters);
        final Object token = new Object();
        synchronized (starting) {
            // As the launch names it: a task given by its container name is the same task.
            final var selected = identities.lookup(request.task(), project);
            if (selected instanceof TaskIdentities.Refused refused
                    && refused.reason() != TaskIdentities.Reason.MISSING) {
                throw notItsOwn(request.task(), null);
            }
            if (selected instanceof TaskIdentities.Refused
                    && request.task().startsWith(ContainerName.PREFIX)) {
                throw notItsOwn(request.task(), null);
            }
            final String picked =
                    selected instanceof TaskIdentities.Found found
                            ? found.identity().task()
                            : request.task();
            String name = picked;
            // Unnamed, past the name another start picked and has not made yet.
            for (int at = 2;
                    unnamed
                            && (beingStarted(newContainer(project, name, request.shortName()))
                                    || !(identities.lookup(name, project)
                                                    instanceof TaskIdentities.Refused refusal
                                            && refusal.reason() == TaskIdentities.Reason.MISSING)
                                    || new TaskReservations(context).tasks(project).contains(name));
                    at++) {
                name = picked + "-" + at;
            }
            final String container =
                    !unnamed && selected instanceof TaskIdentities.Found found
                            ? found.identity().container()
                            : newContainer(project, name, request.shortName());
            if (beingStarted(container)) {
                throw new VarlinkException(
                        INTERFACE + ".AlreadyStarting", Map.of("task", container));
            }
            // The name settled once: the start would otherwise pick an unnamed task's again, and
            // another than the one counted here once a task of that name exists.
            held =
                    settled(
                            held,
                            !unnamed && selected instanceof TaskIdentities.Found ? container : name,
                            unnamed);
            final String agent = PoreloDaemon.text(parameters, "agent");
            final String model = PoreloDaemon.text(parameters, "model");
            String runs = agent;
            @Nullable String asks = model.isEmpty() ? null : model;
            final TaskProfile existing = recorded(container);
            if (existing != null) {
                if (!(selected instanceof TaskIdentities.Found)) {
                    throw notItsOwn(container, existing.parent());
                }
                if (!caller.equals(existing.parent())) {
                    // Bringing back a task it did not start would run its prompt in another's work.
                    throw notItsOwn(container, existing.parent());
                }
                final String made = existing.agent() == null ? "" : existing.agent();
                if (!agent.isEmpty() && !agent.equals(made)
                        || !model.isEmpty() && !model.equals(existing.model())) {
                    throw new VarlinkException(
                            INTERFACE + ".MadeWithAnother",
                            Map.of(
                                    "task",
                                    container,
                                    "agent",
                                    made,
                                    "model",
                                    existing.model() == null ? "" : existing.model()));
                }
                runs = made;
                asks = existing.model();
            }
            if (runs.isEmpty() || !endpoint.allows(runs, asks)) {
                throw new VarlinkException(
                        INTERFACE + ".AgentNotAllowed",
                        Map.of(
                                "agent",
                                runs,
                                "model",
                                asks == null ? "" : asks,
                                "allowed",
                                endpoint.allowed()));
            }
            held.put("agent", runs);
            if (asks != null) {
                held.put("model", asks);
            }
            // Each start running counts once, and a running child once unless a start holds it.
            int running = 0;
            final Set<String> counted = new HashSet<>();
            for (final Starting other : starting.values()) {
                if (other.caller().equals(caller)) {
                    running++;
                    counted.add(other.container());
                }
            }
            for (final String child : children(caller)) {
                if (!counted.contains(child) && !child.equals(container)) {
                    running++;
                }
            }
            if (running + 1 > endpoint.tasks()) {
                throw new VarlinkException(
                        INTERFACE + ".ChildLimitReached",
                        Map.of("parent", caller, "running", running, "limit", endpoint.tasks()));
            }
            starting.put(token, new Starting(caller, container));
        }
        try {
            // Credential preflight and its wait belong inside the reservation, before launch
            // effects.
            // The run's lines and answer are what Logs and Tail read: a section that allows neither
            // gets neither through Start.
            start.call(
                    held,
                    released(token, readsTheRun(endpoint) ? replies : withoutTheRun(replies)));
        } finally {
            synchronized (starting) {
                starting.remove(token);
            }
        }
    }

    /**
     * Returns replies whose last one first ends the start's reservation: a caller told the start
     * ended may start the task again at once, before the start's thread has returned.
     *
     * @param token The start's place among those starting.
     * @param replies Where the replies go.
     * @return The replies to answer through.
     */
    private VarlinkServer.Replies released(
            final Object token, final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                replies.more(parameters);
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                synchronized (starting) {
                    starting.remove(token);
                }
                replies.last(parameters);
            }

            @Override
            public boolean streaming() {
                return replies.streaming();
            }

            @Override
            public boolean open() {
                return replies.open();
            }
        };
    }

    /**
     * Returns a start's parameters with the name the endpoint settled for it.
     *
     * @param parameters What the start carries.
     * @param name The task's name, as given or as the endpoint picked it.
     * @param unnamed Whether the start carried no name, so the endpoint picked it.
     * @return A copy, marked as named by nobody when the endpoint picked the name.
     */
    static Map<String, Object> settled(
            final Map<String, Object> parameters, final String name, final boolean unnamed) {
        final Map<String, Object> held = new LinkedHashMap<>(parameters);
        held.put("task", name);
        if (unnamed) {
            // Picked here, not given: the started task shows its short name.
            held.put(PoreloDaemon.UNNAMED, true);
        }
        return held;
    }

    /** Tells whether a start running now makes or brings back a container; under its lock. */
    private boolean beingStarted(final String container) {
        for (final Starting other : starting.values()) {
            if (other.container().equals(container)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a task's record: where the running task keeps it, or else where it is kept across a
     * reboot, before a resume puts it back.
     */
    private @Nullable TaskProfile recorded(final String container) {
        final TaskProfile profile =
                TaskProfile.readFrom(context.paths().tasks().containerState(container));
        return profile != null
                ? profile
                : TaskProfile.readFrom(context.paths().tasks().taskRecord(container));
    }

    /**
     * The methods answering with every task of the person, which a task sees cut to its project.
     */
    static final Set<String> TASK_LISTS = Set.of("List", "Watch");

    /**
     * The methods taking a project that the task socket does not hold to the caller's own: only
     * {@code Start}, which holds its project itself. Every read reaches only the caller's project.
     */
    static final Set<String> ANY_PROJECT = Set.of("Start");

    /**
     * What a listed task carries of what its run said, which Logs and Tail read: the file it handed
     * back among it.
     */
    static final Set<String> WHAT_IT_SAID = Set.of("lastMessage", "agentEnded", "ended", "result");

    /**
     * What a listed task carries that its agent wrote and every row has, sent empty instead: the
     * ideas it proposed, which would otherwise reach another agent's prompt.
     */
    static final Set<String> WHAT_IT_WROTE = Set.of("ideas");

    private static Object withoutWhatItSaid(final Object task) {
        final Map<Object, Object> kept = new LinkedHashMap<>((Map<?, ?>) task);
        kept.keySet().removeAll(WHAT_IT_SAID);
        for (final String field : WHAT_IT_WROTE) {
            kept.replace(field, List.of());
        }
        // Not even how many: the count says the task wrote something.
        kept.replace("ideasHeldBack", 0L);
        return kept;
    }

    /** What a start's replies carry of its run, which Logs and Tail read. */
    static final Set<String> THE_RUN = Set.of("runLine", "result", "resultKind", "resultCut");

    /**
     * Returns replies without what a start says of its run.
     *
     * @param replies Where the replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies withoutTheRun(final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                if (parameters.containsKey("runLine")) {
                    return;
                }
                replies.more(cut(parameters));
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                replies.last(cut(parameters));
            }

            @Override
            public boolean streaming() {
                return replies.streaming();
            }

            @Override
            public boolean open() {
                return replies.open();
            }

            private Map<String, Object> cut(final Map<String, Object> parameters) {
                final Map<String, Object> answer = new LinkedHashMap<>(parameters);
                answer.keySet().removeAll(THE_RUN);
                return answer;
            }
        };
    }

    /**
     * Returns replies that keep, of a list of tasks, those of one project alone.
     *
     * @param project The caller's project.
     * @param replies Where the cut replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies ownProject(
            final PoreloContext context,
            final String project,
            final VarlinkServer.Replies replies) {
        return ownProject(context, project, true, replies);
    }

    /**
     * Returns replies that pass, of the clearance questions and answers of every task, only those
     * of one project's tasks: a task's question names where it tried to go, its project and its
     * name, and none of it is another project's to read.
     *
     * @param context Where the tasks' records are.
     * @param project The caller's project.
     * @param replies Where the kept events go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies ownPrompts(
            final PoreloContext context,
            final String project,
            final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                if (ours(parameters)) {
                    replies.more(parameters);
                }
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                replies.last(ours(parameters) ? parameters : Map.of());
            }

            @Override
            public boolean streaming() {
                return replies.streaming();
            }

            @Override
            public boolean open() {
                return replies.open();
            }

            private boolean ours(final Map<String, Object> event) {
                // By the task's own record, not the project the event says: the watcher writes
                // that from what its start was told.
                return event.get("task") instanceof String task
                        && new TaskIdentities(context).lookup(task, project)
                                instanceof TaskIdentities.Found;
            }
        };
    }

    /**
     * Whether a section allows what Logs and Tail read: a run's lines and what it answered.
     *
     * @param endpoint The section.
     * @return Whether it names either.
     */
    static boolean readsTheRun(final McpEndpoint endpoint) {
        return endpoint.methods().contains("Logs") || endpoint.methods().contains("Tail");
    }

    /**
     * Returns replies that keep, of a list of tasks, those of one project alone, and without what a
     * run said where the section allows no reading of it. A Watch reply the cut leaves as the last
     * one sent is not sent again: a change the caller may not see, another project's or what
     * another task wrote, would otherwise reach it as a notification, which counts them.
     *
     * @param project The caller's project.
     * @param readsTheRun Whether the section allows Logs or Tail.
     * @param replies Where the cut replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies ownProject(
            final PoreloContext context,
            final String project,
            final boolean readsTheRun,
            final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            private @Nullable String sent;

            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                final Map<String, Object> answer = cut(parameters);
                // Compared as the daemon's Watch compares, without what changes on its own.
                final String settled = settled(answer);
                if (settled.equals(sent)) {
                    return;
                }
                sent = settled;
                replies.more(answer);
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                replies.last(cut(parameters));
            }

            @Override
            public boolean streaming() {
                return replies.streaming();
            }

            @Override
            public boolean open() {
                return replies.open();
            }

            private Map<String, Object> cut(final Map<String, Object> parameters) {
                final Map<String, Object> answer = new LinkedHashMap<>(parameters);
                if (parameters.get("tasks") instanceof List<?> tasks) {
                    answer.put(
                            "tasks",
                            tasks.stream()
                                    .filter(
                                            task ->
                                                    task instanceof Map<?, ?> each
                                                            && project.equals(each.get("project"))
                                                            && each.get("name")
                                                                    instanceof String name
                                                            && new TaskIdentities(context)
                                                                            .lookup(name, project)
                                                                    instanceof TaskIdentities.Found)
                                    .map(task -> readsTheRun ? task : withoutWhatItSaid(task))
                                    .toList());
                }
                if (parameters.get("unrecognized") instanceof List<?> names) {
                    // Unrecognized ownership cannot authorize visibility on a task socket.
                    answer.put("unrecognized", List.of());
                }
                return answer;
            }
        };
    }

    /** Returns a reply of tasks as Watch compares it, without what changes on its own. */
    private static String settled(final Map<String, Object> answer) {
        final Map<String, Object> settled = new LinkedHashMap<>(answer);
        if (answer.get("tasks") instanceof List<?> tasks) {
            settled.put(
                    "tasks",
                    tasks.stream()
                            .map(
                                    task -> {
                                        final Map<String, Object> row = new LinkedHashMap<>();
                                        if (task instanceof Map<?, ?> fields) {
                                            fields.forEach(
                                                    (key, value) ->
                                                            row.put(String.valueOf(key), value));
                                        }
                                        return PoreloDaemon.withoutAge(row);
                                    })
                            .toList());
        }
        return org.fuin.porelo.wire.Json.write(settled);
    }

    /**
     * Returns the methods that take a task, as the interface description declares their parameters:
     * over a task's socket each acts on the caller or its own child alone.
     *
     * @return Their names, without the interface.
     */
    static Set<String> takingATask() {
        return declaring("task");
    }

    /**
     * Returns the methods that take a project, but those in {@link #ANY_PROJECT}: over a task's
     * socket each reads the caller's own project alone, so one added later is held too.
     *
     * @return Their names, without the interface.
     */
    static Set<String> takingAProject() {
        final Set<String> taking = new java.util.TreeSet<>(declaring("project"));
        taking.removeAll(ANY_PROJECT);
        return taking;
    }

    /** The methods whose own parameters, as the interface description declares them, name one. */
    private static Set<String> declaring(final String parameter) {
        final List<String> lines = PoreloDaemon.description().lines().toList();
        final java.util.regex.Pattern method =
                java.util.regex.Pattern.compile("^method\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");
        final java.util.regex.Pattern task =
                java.util.regex.Pattern.compile("(^|[,\\s])" + parameter + "\\s*:");
        final Set<String> taking = new java.util.TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            final java.util.regex.Matcher named = method.matcher(lines.get(i));
            if (!named.find()) {
                continue;
            }
            // Its parameters alone, at their own depth: a field of its reply or of a type is none.
            final StringBuilder parameters = new StringBuilder();
            int depth = 0;
            boolean closed = false;
            for (int j = i; j < lines.size() && !closed; j++) {
                final String line = lines.get(j);
                final int hash = line.indexOf('#');
                final String code = hash < 0 ? line : line.substring(0, hash);
                for (int c = 0; c < code.length() && !closed; c++) {
                    final char ch = code.charAt(c);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        closed = depth == 0;
                    } else if (depth == 1) {
                        parameters.append(ch);
                    }
                }
                parameters.append('\n');
            }
            if (task.matcher(parameters).find()) {
                taking.add(named.group(1));
            }
        }
        return taking;
    }

    /** The caller's tasks that are running, by container name. */
    private Set<String> children(final String caller) {
        final Set<String> children = new HashSet<>();
        for (final TaskInventory.Task task : inventory.tasks()) {
            if (!task.running()) {
                continue;
            }
            final TaskProfile profile =
                    TaskProfile.readFrom(context.paths().tasks().containerState(task.name()));
            if (profile != null
                    && caller.equals(profile.parent())
                    && new TaskIdentities(context).lookup(task.name(), null)
                            instanceof TaskIdentities.Found) {
                children.add(task.name());
            }
        }
        return children;
    }

    /**
     * Refuses a call naming a task that is not the caller's own: one whose recorded parent is
     * another task or none, or, where the caller may name itself, not the caller either.
     *
     * @param caller The caller.
     * @param parameters The call's parameters; a {@code task} left out names none.
     * @param itself Whether the caller may name itself.
     */
    void own(final String caller, final Map<String, Object> parameters, final boolean itself) {
        final String task = PoreloDaemon.text(parameters, "task");
        final TaskIdentities identities = new TaskIdentities(context);
        final var source = identities.lookup(caller, null);
        if (!(source instanceof TaskIdentities.Found own)) {
            throw notItsOwn(task, null);
        }
        final var target = identities.lookup(task, own.identity().project());
        if (!(target instanceof TaskIdentities.Found found)
                || !task.equals(found.identity().container())) {
            throw notItsOwn(task, null);
        }
        if (itself && task.equals(caller)) {
            return;
        }
        final TaskProfile profile = recorded(task);
        final String parent = profile == null ? null : profile.parent();
        if (!caller.equals(parent)) {
            throw notItsOwn(task, parent);
        }
    }

    private static VarlinkException notItsOwn(final String task, final @Nullable String parent) {
        return new VarlinkException(
                INTERFACE + ".NotItsOwn",
                Map.of("task", task, "parent", parent == null ? "" : parent));
    }

    /**
     * One task's socket: a server that keeps only the methods its file allows, each run as the
     * task, with a start held to the file, and a stop or any other call naming a task held to the
     * task's own.
     */
    private final class TaskSocket extends VarlinkServer {

        private final String task;
        private final String project;
        private final McpEndpoint endpoint;
        private final Set<String> takingATask;
        private final Set<String> takingAProject = takingAProject();

        TaskSocket(
                final Path socket,
                final String task,
                final String project,
                final McpEndpoint endpoint,
                final Set<String> takingATask) {
            super(socket, INTERFACE, org.fuin.porelo.core.process.SocketPeer::read);
            this.task = task;
            this.project = project;
            this.endpoint = endpoint;
            this.takingATask = Set.copyOf(takingATask);
        }

        /** Refuses a call that names a project other than the caller's own, or none. */
        private void refuseAnotherProject(final Map<String, Object> parameters) {
            final String asked = PoreloDaemon.text(parameters, "project");
            if (!project.equals(asked)) {
                throw new VarlinkException(
                        INTERFACE + ".ProjectNotItsOwn", Map.of("project", asked, "own", project));
            }
        }

        @Override
        public VarlinkServer method(final String name, final VarlinkServer.Method method) {
            if (!endpoint.methods().contains(name)) {
                return this;
            }
            final VarlinkServer.Method held;
            if ("Start".equals(name)) {
                held =
                        (parameters, replies) ->
                                start(task, project, endpoint, parameters, replies, method);
            } else if ("Stop".equals(name) || takingATask.contains(name)) {
                // A stop takes its children alone; any other call naming a task, the caller too.
                final boolean itself = !"Stop".equals(name);
                // A method naming a task and a project, as Peers does, is held by both.
                final boolean alsoProject = takingAProject.contains(name);
                held =
                        (parameters, replies) -> {
                            own(task, parameters, itself);
                            if (alsoProject) {
                                refuseAnotherProject(parameters);
                            }
                            method.call(parameters, replies);
                        };
            } else if (takingAProject.contains(name)) {
                held =
                        (parameters, replies) -> {
                            refuseAnotherProject(parameters);
                            method.call(parameters, replies);
                        };
            } else if ("Prompts".equals(name)) {
                // Every task's questions come through the daemon's stream; a task's agent reads
                // its own project's, as it lists only them.
                held =
                        (parameters, replies) ->
                                method.call(parameters, ownPrompts(context, project, replies));
            } else if (TASK_LISTS.contains(name)) {
                // The person's every task is theirs to read; a task's agent reads its own
                // project's.
                held =
                        (parameters, replies) ->
                                method.call(
                                        parameters,
                                        ownProject(
                                                context, project, readsTheRun(endpoint), replies));
            } else {
                held = method;
            }
            return super.method(
                    name,
                    (parameters, replies) ->
                            ScopedValue.where(CALLER, task)
                                    .call(
                                            () -> {
                                                held.call(parameters, replies);
                                                return null;
                                            }));
        }
    }

    /**
     * The name a new task of the project is made under, as the launch makes it. A project that
     * cannot be read, or a name too long for one, gets the old spelling here: the start refuses it,
     * and until then it only keys the starts in flight.
     */
    private String newContainer(String project, String task, @Nullable String taskShort) {
        try {
            return context.tasks()
                    .containerName(
                            ProjectReader.read(
                                    org.fuin.porelo.app.ProjectSource.require(context, project)),
                            task,
                            taskShort);
        } catch (ProjectException | IllegalArgumentException unreadable) {
            return ContainerName.PREFIX + project + "-" + task;
        }
    }

    /**
     * Checks a draft as {@code CheckProjectFile} answers it, adding what only the daemon can say
     * about its {@code mcp} section to what Porelo refuses anywhere.
     *
     * @param checked What the machine's check answered.
     * @param text The draft.
     * @return The answer, with any refused method.
     */
    static Map<String, Object> checked(final Map<String, Object> checked, final String text) {
        final List<String> refused = new ArrayList<>();
        if (checked.get("refused") instanceof List<?> already) {
            already.forEach(each -> refused.add(String.valueOf(each)));
        }
        try {
            ProjectReader.endpoint(new java.io.StringReader(text), "project.yml")
                    .ifPresent(endpoint -> refused.addAll(refusals(endpoint.methods())));
        } catch (ProjectException ex) {
            // Refused already, as the machine's check reads the same file.
        }
        final Map<String, Object> answer = new LinkedHashMap<>(checked);
        answer.put("refused", List.copyOf(refused));
        return answer;
    }
}
