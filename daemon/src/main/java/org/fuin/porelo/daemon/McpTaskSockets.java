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
import org.fuin.porelo.app.EndpointRights;
import org.fuin.porelo.app.McpDaemonSockets;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.app.StartParts;
import org.fuin.porelo.app.StartRules;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskIdentities;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.app.TaskReservations;
import org.fuin.porelo.core.project.McpEndpoint;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
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
 * mcp.methods} names at the task's last start or resume and no other, so a method not named answers
 * {@code MethodNotFound}; each open reads the file again and the served socket takes it; its
 * interface description is Tasks1 with every other method dropped, every type and error kept. The
 * section is the project's bound: within it, {@code Start}, {@code Stop}, {@code Done} and {@code
 * Report} are carried only where the task's recorded rights hold {@code start}, {@code stop},
 * {@code done} and {@code report}, read at each call; a task whose record does not read holds none.
 * A {@code Start} on it is held to the file's limits, gives the started task at most the caller's
 * own workspace, equipment and rights, and records the caller as its parent; a {@code Stop} on it
 * takes only the caller's own, and any other call naming a task only the caller or its own. {@code
 * Pending}, {@code Review} and {@code ReviewPage} answer only for the gate push that is the
 * caller's recorded place.
 */
final class McpTaskSockets implements McpDaemonSockets.Opener, AutoCloseable {

    /** The directory under Porelo's runtime directory the sockets lie in. */
    static final String DIRECTORY = "mcp";

    /** What a task's socket is called in it, after the task. */
    static final String SUFFIX = ".daemon.sock";

    /** The task a call came in on, bound for the call's length on a task's socket alone. */
    private static final ScopedValue<String> CALLER = ScopedValue.newInstance();

    /** What the file allowed when a call came in, read once and kept for the call's length. */
    private static final ScopedValue<McpEndpoint> ALLOWED = ScopedValue.newInstance();

    /** What a task is called: a container name, which becomes a file name here. */
    private static final java.util.regex.Pattern TASK =
            java.util.regex.Pattern.compile(ContainerName.NAME_PATTERN);

    /** The longest name taken, so its socket's file name fits. */
    private static final int TASK_LENGTH = 208;

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
     * a certificate and a start that returns before its run. The started task's parts may be named,
     * each held to what the calling task holds.
     */
    static final Set<String> START_PARAMETERS =
            Set.of(
                    "rights",
                    "readLanded",
                    "writeAs",
                    "projectServices",
                    "task",
                    "project",
                    "repository",
                    "branch",
                    "from",
                    "prompt",
                    "agent",
                    "model",
                    "effort",
                    "minutes",
                    "mode",
                    "maxTurns",
                    "parameters",
                    "dryRun",
                    "steps",
                    "work");

    /**
     * The methods a task's endpoint may carry beside the reading ones, each only where the task's
     * start recorded its right.
     */
    private static final Set<String> WRITING = EndpointRights.writing();

    /**
     * The reading methods that answer a task only for the gate push that is its place: its own, or
     * the one it reads.
     */
    static final Set<String> AT_ITS_PLACE = Set.of("Pending", "Review", "ReviewPage");

    /** One socket, the thread serving it, and what releases its kept readings. */
    private record Served(TaskSocket server, Thread thread, ReadingSweep sweep) {}

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
        this(context, signIns, ReviewParts.of(context, new org.fuin.porelo.app.HeldRuns()));
    }

    private final ReviewParts reviews;

    /**
     * Constructor over the daemon's one set of the review's parts.
     *
     * @param context The machine.
     * @param signIns The daemon's one sign-in registry.
     * @param reviews The daemon's one set of the review's parts.
     */
    McpTaskSockets(
            PoreloContext context,
            org.fuin.porelo.app.AgentSignInRuns signIns,
            ReviewParts reviews) {
        this.reviews = reviews;
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
                                + " reading methods McpMethods names, Start, Stop, Done and Report");
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
        if (task.length() > TASK_LENGTH || !TASK.matcher(task).matches()) {
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
        // Read on every open, the first and each repeated one: a resumed task takes the methods
        // its file allows now. The served socket stays, so a running task's mount keeps its inode.
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
            // Refused before anything changes: a served socket keeps what it allowed.
            throw new IOException(
                    "project '" + project + "' cannot give a task an endpoint: " + refused);
        }
        final Served served = open.get(task);
        if (served != null) {
            served.server().allow(endpoint);
            return Optional.of(socketOf(task));
        }
        final Path socket = socketOf(task);
        final TaskSocket server;
        try {
            server = new TaskSocket(socket, task, project, endpoint, takingATask());
        } catch (VarlinkException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        final Thread thread;
        final ReadingSweep sweep = new ReadingSweep(context.limits().polling(ReadingSweep.EVERY));
        // Whatever ends this early, an Error too, leaves no sweep on the shared timer.
        boolean opened = false;
        try {
            try {
                server.allow(endpoint);
                server.reporting(org.fuin.porelo.app.PoreloVersion.version());
                PoreloDaemon.register(server, context, inventory, control, signIns, sweep, reviews);
                thread = Thread.ofPlatform().daemon().name("porelod-mcp-" + task).start(server);
            } catch (RuntimeException ex) {
                // Bound and not served: the socket goes, as a stale one would.
                server.close();
                throw new IOException("the socket of " + task + " cannot be served", ex);
            }
            open.put(task, new Served(server, thread, sweep));
            opened = true;
        } finally {
            if (!opened) {
                sweep.close();
            }
        }
        return Optional.of(socket);
    }

    /** How long closing a task's socket waits for its thread to end. */
    static final java.time.Duration THREAD_END = java.time.Duration.ofSeconds(5);

    /**
     * Returns how long closing a task's socket waits for its thread to end.
     *
     * @param limits The factors.
     * @return {@link #THREAD_END} under {@code machines}.
     */
    static java.time.Duration threadEnd(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.machines(THREAD_END);
    }

    @Override
    public synchronized void close(final String task) {
        final Served served = open.remove(task);
        if (served == null) {
            return;
        }
        try {
            served.server().close();
        } finally {
            served.sweep().close();
        }
        try {
            served.thread().join(threadEnd(context.limits()));
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
        // With the comment above each: none is offered through a task's start, so a task is not
        // told of them.
        return kept.toString()
                .replaceAll(
                        "(?m)(?:^  #.*\\R)*^  (?:waitForSignIn|allRepositories|readOnly): \\?bool,\\R",
                        "")
                .replaceAll("(?m)(?:^  #.*\\R)*^  given: \\?\\[string\\]string,\\R", "");
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
        // Read at this call, never kept: a record that does not read holds no right.
        final TaskParts parent = partsOf(caller);
        if (parent == null || !parent.holds(Right.START)) {
            throw notCarried("Start");
        }
        // Before any other task's name is looked up: a start asking more is refused alike whether
        // or not a task of that name exists.
        final StartParts taken = childParts(project, parameters);
        refuseBeyond(taken.parts(), parent);
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
        // Read as the daemon's own Start reads it, so both refuse the same values; a whole number
        // below one is this endpoint's own, shorter refusal.
        final java.util.OptionalInt whole =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.wholeInt(parameters.get("minutes"));
        final int minutes =
                whole.isPresent() && whole.getAsInt() < 1
                        ? whole.getAsInt()
                        : PoreloDaemon.minutes(parameters);
        if (minutes < 1) {
            // Over before it began, the run would leave its container up.
            throw new VarlinkException(INTERFACE + ".RunTooShort", Map.of("minutes", minutes));
        }
        if (minutes > endpoint.minutes()) {
            throw new VarlinkException(
                    INTERFACE + ".RunTooLong",
                    Map.of("minutes", minutes, "limit", endpoint.minutes()));
        }
        final boolean unnamed = PoreloDaemon.text(parameters, "task").isEmpty();
        // Chosen once, here: the name the request carries, the one moved on below and the note a
        // task is told are one value.
        final org.fuin.porelo.app.TaskNames.Chosen first =
                unnamed ? PoreloDaemon.unnamed(parameters, context) : null;
        final TaskLaunch.Request request = PoreloDaemon.request(parameters, context, first);
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
                    && ContainerName.isPorelo(request.task())) {
                throw notItsOwn(request.task(), null);
            }
            final String picked =
                    selected instanceof TaskIdentities.Found found
                            ? found.identity().task()
                            : request.task();
            String name = picked;
            int at = 1;
            org.fuin.porelo.app.TaskNames.Chosen chosen =
                    first != null ? first : org.fuin.porelo.app.TaskNames.Chosen.free(picked);
            // Unnamed, past the name another start picked and has not made yet.
            while (unnamed) {
                final String holder = heldBy(identities, project, name, request.shortName());
                if (holder == null) {
                    break;
                }
                name = picked + "-" + (++at);
                chosen = chosen.movedOn(name, holder);
            }
            // Said to the caller as to a person: a task starting another one is as misled by a
            // name it did not get.
            final String said = unnamed ? chosen.note() : null;
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
            if (said != null) {
                held.put(PoreloDaemon.NAME_NOTE, new PoreloDaemon.Own(said));
            }
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
                // Brought back with the parts it recorded, which stay within its parent's.
                final TaskParts kept = partsOf(container);
                if (kept == null) {
                    throw new VarlinkException(
                            INTERFACE + ".StartRuleBroken",
                            Map.of(
                                    "rule",
                                    TaskParts.EXCEEDS_PARENT,
                                    "detail",
                                    "no recorded parts that read"));
                }
                // A member the start names must be the recorded one; one it leaves out keeps it.
                if (taken.asked().changes(kept)) {
                    throw new VarlinkException(INTERFACE + ".RightsChanged", Map.of());
                }
                refuseBeyond(kept, parent);
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
     * Returns the parts a task's start recorded, read now: from its state, else from its durable
     * record.
     *
     * @param task The task's container.
     * @return Them, or {@code null} when it recorded none or they do not read: no right at all.
     */
    private @Nullable TaskParts partsOf(final String task) {
        return EndpointRights.recorded(context, task);
    }

    /**
     * Tells whether a task's endpoint carries a method as far as its recorded rights go: a writing
     * method only where its start recorded the right, every other one always.
     *
     * @param task The task's container.
     * @param method The method, without the interface.
     * @return Whether its rights allow it.
     */
    boolean carries(final String task, final String method) {
        return !WRITING.contains(method) || EndpointRights.carries(partsOf(task), method);
    }

    /** A method the endpoint does not carry, answered as one it does not have. */
    private static VarlinkException notCarried(final String method) {
        return new VarlinkException(
                "org.varlink.service.MethodNotFound", Map.of("method", INTERFACE + "." + method));
    }

    /**
     * Returns the parts a start over a task's socket gives the started task, every member it leaves
     * out resolved to its default, as the daemon's own Start takes them: what it names, checked
     * against Porelo's own start rules. It names no place, so an account to write as is refused,
     * which is taken at a pull request alone. Nothing of another task is looked up for it.
     *
     * @param project The caller's project, whose file the start is read against.
     * @param parameters What the start carries.
     * @return What it names and the parts that follow.
     * @throws VarlinkException {@code InvalidParameter} for a member of the wrong shape or {@code
     *     writeAs}, {@code StartRuleBroken} for parts that break one of Porelo's own rules.
     */
    private StartParts childParts(final String project, final Map<String, Object> parameters) {
        final String repository = PoreloDaemon.text(parameters, "repository");
        return RunPlaces.taken(
                context,
                parameters,
                org.fuin.porelo.app.ProjectSource.require(context, project),
                PoreloDaemon.text(parameters, "task"),
                repository.isEmpty() ? null : repository);
    }

    /**
     * Refuses a child's parts that exceed its parent's, or that break one of Porelo's own start
     * rules.
     *
     * @param child The parts the child gets, every default resolved.
     * @param parent The parts of the task that starts it.
     * @throws VarlinkException {@code StartRuleBroken}, naming the rule and the part.
     */
    static void refuseBeyond(final TaskParts child, final TaskParts parent) {
        // Credentials and clearance are refused by name before: a task's start names neither.
        final Optional<TaskParts.Broken> broken =
                child.exceeds(parent).or(() -> StartRules.broken(child, false, null));
        if (broken.isPresent()) {
            throw new VarlinkException(
                    INTERFACE + ".StartRuleBroken",
                    Map.of("rule", broken.get().rule(), "detail", broken.get().detail()));
        }
    }

    /**
     * Holds {@code Pending}, {@code Review} and {@code ReviewPage} to the gate push that is the
     * caller's recorded place: {@code Pending} shows that push's row alone, the other two refuse
     * any other name as {@code NoSuchPush} before the gate is asked. A caller at a pull request, or
     * without a place, sees no waiting push; one pinned to a head sees that commit and no newer
     * one; a page names the commit checked or is {@code NoSuchPush}. A base to compare with is the
     * place's own history alone: nothing under {@code refs/porelo/}, and only an ancestor of the
     * commit read.
     *
     * @param caller The task.
     * @param project Its project.
     * @param method The method called.
     * @param parameters What the call carries.
     * @param replies Where its answer goes.
     * @param call The method itself.
     * @throws IOException When the answer cannot be sent.
     */
    void atItsPlace(
            final String caller,
            final String project,
            final String method,
            final Map<String, Object> parameters,
            final VarlinkServer.Replies replies,
            final VarlinkServer.Method call)
            throws IOException {
        final RunPlace recorded = RunPlace.recordedPlace(context, caller);
        final RunPlace place =
                recorded != null
                                && recorded.pullRequest() == null
                                && java.util.Objects.equals(
                                        RunPlace.otherThanOwn(project, recorded.repository()),
                                        RunPlace.otherThanOwn(
                                                project,
                                                PoreloDaemon.text(parameters, "repository")))
                        ? recorded
                        : null;
        if ("Pending".equals(method)) {
            call.call(parameters, onlyAt(place, replies));
            return;
        }
        if (place == null || !place.name().equals(PoreloDaemon.text(parameters, "name"))) {
            throw noSuchPush();
        }
        final String against = PoreloDaemon.text(parameters, "against");
        if (namesPoreloRef(against)) {
            throw againstRefused();
        }
        final GitGate gate = PoreloDaemon.gate(parameters, context);
        final GitGate.Waiting waiting = gate.waiting(place.name());
        final RunPlace.Pin pin = place.pinned();
        if (waiting == null || (pin != null && !pin.head().equals(waiting.commit()))) {
            // Never the gate's own answer, which names the pushes that do wait.
            throw noSuchPush();
        }
        final String head = waiting.commit();
        final Map<String, Object> held = new LinkedHashMap<>(parameters);
        if ("ReviewPage".equals(method)) {
            // The commit checked, never one the push moved to before the gate reads it: the gate
            // pages whatever head it then finds, so long as the call names that one.
            if (!head.equals(PoreloDaemon.text(parameters, "commit").strip())) {
                throw noSuchPush();
            }
            if (!against.isEmpty() && !gate.contains(head, against)) {
                throw againstRefused();
            }
        } else if (!against.isBlank()) {
            final String base;
            try {
                base = gate.namedBase(against);
            } catch (GateException ex) {
                throw againstRefused();
            }
            if (!gate.contains(head, base)) {
                throw againstRefused();
            }
            // The commit checked, not the name again: a ref moved since would read past it.
            held.put("against", base);
        }
        try {
            call.call(held, ofCommit(head, replies));
        } catch (GateException ex) {
            if (gate.waiting(place.name()) == null) {
                throw noSuchPush();
            }
            throw ex;
        }
    }

    /** Whether a base names one of Porelo's own refs, in full or in git's short spelling. */
    private static boolean namesPoreloRef(final String against) {
        // git reads porelo/... as refs/porelo/...; any other path through such a directory is
        // refused too, never told apart.
        final String ref = against.strip();
        return ref.startsWith("refs/porelo")
                || ref.startsWith("porelo/")
                || ref.contains("/porelo/");
    }

    private static VarlinkException noSuchPush() {
        return new VarlinkException(INTERFACE + ".NoSuchPush", Map.of());
    }

    private static VarlinkException againstRefused() {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", "against"));
    }

    /**
     * Returns replies that keep, of the pushes {@code Pending} lists, only the one at a place, and
     * of a pinned place only the commit it is pinned to.
     *
     * @param place The caller's place at this gate, or {@code null} for none: no row is kept.
     * @param replies Where the cut replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies onlyAt(
            final @Nullable RunPlace place, final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
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
                if (parameters.get("pending") instanceof List<?> rows) {
                    answer.put("pending", rows.stream().filter(this::at).toList());
                }
                return answer;
            }

            private boolean at(final Object row) {
                if (place == null || !(row instanceof Map<?, ?> push)) {
                    return false;
                }
                final RunPlace.Pin pin = place.pinned();
                return place.name().equals(push.get("name"))
                        && (pin == null || pin.head().equals(push.get("commit")));
            }
        };
    }

    /**
     * Returns replies that send a review only of the commit checked: one the push moved to since is
     * refused as {@code NoSuchPush} rather than shown.
     */
    private static VarlinkServer.Replies ofCommit(
            final String head, final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                replies.more(checked(parameters));
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                replies.last(checked(parameters));
            }

            @Override
            public boolean streaming() {
                return replies.streaming();
            }

            @Override
            public boolean open() {
                return replies.open();
            }

            private Map<String, Object> checked(final Map<String, Object> parameters) {
                if (parameters.get("commit") instanceof String commit && !head.equals(commit)) {
                    throw noSuchPush();
                }
                return parameters;
            }
        };
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
            held.put(PoreloDaemon.UNNAMED, new PoreloDaemon.Own(true));
        }
        return held;
    }

    /**
     * Says what holds an unnamed start's name, under the starts' lock.
     *
     * @param identities The machine's tasks.
     * @param project The project.
     * @param name The name asked.
     * @param shortName The start's short spelling.
     * @return {@code the task <name>}, {@code a start reserving it}, or {@code null} when the name
     *     is free.
     * @throws IOException When the reservations cannot be read.
     */
    private @Nullable String heldBy(
            final TaskIdentities identities,
            final String project,
            final String name,
            final @Nullable String shortName)
            throws IOException {
        if (beingStarted(newContainer(project, name, shortName))
                || new TaskReservations(context).tasks(project).contains(name)) {
            return "a start reserving it";
        }
        return identities.lookup(name, project) instanceof TaskIdentities.Refused refusal
                        && refusal.reason() == TaskIdentities.Reason.MISSING
                ? null
                : "the task " + name;
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
     * The methods that list the person's projects, cut to the caller's own as a list of tasks is.
     */
    static final Set<String> PROJECT_LISTS = Set.of("Projects", "WatchProjects");

    /** Methods whose every row names its project: a task reads its own project's rows alone. */
    static final Set<String> PROJECT_ROWS = Set.of("Authorizations", "Events");

    /** Methods whose every row names its task: a task reads its own project's tasks' rows alone. */
    static final Set<String> TASK_ROWS = Set.of("Prompts", "Talk");

    /**
     * Reading methods held from a task: what each answers names other projects, and it has no row
     * to cut by. {@code Doctor}'s findings name projects in their text; {@code DefaultRepositories}
     * names the project that claims each repository. {@code Ecosystems} is held only when it is
     * given a checkout, which it would look into wherever it lies.
     */
    static final Set<String> HELD = Set.of("Doctor", "DefaultRepositories");

    /**
     * Reading methods taking no project or task that are the account's or the machine's by nature,
     * and so are answered whole: the one vault's names and devices, never a value ({@code
     * Credentials}, {@code Keyslots}, {@code SshKeys}, {@code CredentialCheck}, {@code
     * HostVaultShare}); the machine's destinations, egress sets, host keys, agent catalogue and the
     * models a provider offers it; a check of the caller's own text; the event log's integrity,
     * counts alone; the daemon's process and the methods it grants; the account's own time factors
     * and its settings of how long something holds.
     */
    static final Set<String> ACCOUNT_WIDE =
            Set.of(
                    "AccountSettings",
                    "Agents",
                    "AgentSignIns",
                    "CheckProjectFile",
                    "CredentialCheck",
                    "Credentials",
                    "Destination",
                    "Destinations",
                    "Ecosystems",
                    "HostKeys",
                    "HostVaultShare",
                    "Installable",
                    "Keyslots",
                    "Limits",
                    "McpMethods",
                    "Models",
                    "Process",
                    "ProjectFileSchema",
                    "Providers",
                    "Sets",
                    "SshKeys",
                    "VerifyEvents");

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
     * What a listed task carries of what its agent wrote and every row has, sent as 0 instead: the
     * number of its last report, which says the task wrote something.
     */
    static final Set<String> WHAT_IT_WROTE = Set.of("reports");

    private static Object withoutWhatItSaid(final Object task) {
        final Map<Object, Object> kept = new LinkedHashMap<>((Map<?, ?>) task);
        kept.keySet().removeAll(WHAT_IT_SAID);
        for (final String field : WHAT_IT_WROTE) {
            kept.replace(field, 0L);
        }
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
     * Returns replies that keep, of a list of projects, the caller's own alone.
     *
     * @param project The caller's project.
     * @param replies Where the cut replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies ownProjectRows(
            final String project, final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            private @Nullable Map<String, Object> sent;

            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                // A change of another project leaves the caller's row as it was: sent again, it
                // would tell the task that something changed where it may not look.
                final Map<String, Object> answer = cut(parameters);
                if (!answer.equals(sent)) {
                    sent = answer;
                    replies.more(answer);
                }
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
                if (parameters.get("projects") instanceof List<?> projects) {
                    answer.put(
                            "projects",
                            projects.stream()
                                    .filter(
                                            row ->
                                                    row instanceof Map<?, ?> each
                                                            && project.equals(each.get("name")))
                                    .toList());
                }
                return answer;
            }
        };
    }

    /**
     * Returns replies that keep only the rows one test allows: each streamed row, and each row of
     * the list a last answer carries under {@code events}.
     *
     * @param kept Whether a row stays.
     * @param replies Where the cut replies go.
     * @return The replies to answer through.
     */
    static VarlinkServer.Replies ownRows(
            final java.util.function.Predicate<Map<?, ?>> kept,
            final VarlinkServer.Replies replies) {
        return new VarlinkServer.Replies() {
            @Override
            public void more(final Map<String, Object> parameters) throws IOException {
                if (kept.test(parameters)) {
                    replies.more(parameters);
                }
            }

            @Override
            public void last(final Map<String, Object> parameters) throws IOException {
                if (parameters.get("events") instanceof List<?> rows) {
                    final Map<String, Object> answer = new LinkedHashMap<>(parameters);
                    answer.put(
                            "events",
                            rows.stream()
                                    .filter(row -> row instanceof Map<?, ?> each && kept.test(each))
                                    .toList());
                    replies.last(answer);
                } else {
                    replies.last(kept.test(parameters) ? parameters : Map.of());
                }
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
     * Returns whether a row names a task of one project, by the task's identity, not its name. A
     * task found to be the project's stays so, a container's project never changing, so it is not
     * looked up again for each row of a stream; one not found is looked up again, as it may be a
     * task just made.
     *
     * @param context The machine.
     * @param project The caller's project.
     * @return The test.
     */
    static java.util.function.Predicate<Map<?, ?>> ofItsTasks(
            final PoreloContext context, final String project) {
        final TaskIdentities identities = new TaskIdentities(context);
        final Set<String> its = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return row -> {
            if (!(row.get("task") instanceof String task)) {
                return false;
            }
            if (its.contains(task)) {
                return true;
            }
            if (identities.lookup(task, project) instanceof TaskIdentities.Found) {
                its.add(task);
                return true;
            }
            return false;
        };
    }

    private static VarlinkException heldFromATask(final String project) {
        return new VarlinkException(
                INTERFACE + ".ProjectNotItsOwn", Map.of("project", "", "own", project));
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

        /** What the file allowed at the socket's last open; each call reads it once, as ALLOWED. */
        private volatile McpEndpoint endpoint;

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

        /**
         * Takes what the file allows now: the methods answered, the description, and what a start
         * is held to. A call already running keeps the endpoint it read.
         *
         * @param now The file's section as it reads now.
         */
        void allow(final McpEndpoint now) {
            endpoint = now;
            // Cut by the task's recorded rights as each call is held to them: a start records them
            // before its socket is opened, and they stay for the task's life.
            final Set<String> kept = new HashSet<>();
            for (final String method : now.methods()) {
                if (carries(task, method)) {
                    kept.add(method);
                }
            }
            describedBy(cut(PoreloDaemon.description(), kept));
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
            return super.method(name, guarded(name, method));
        }

        @Override
        public VarlinkServer methodEndingWithItsCaller(
                final String name, final VarlinkServer.Method method) {
            // Review and ReviewPage come this way: unguarded, they answered past the file and
            // read another project's gate.
            return super.methodEndingWithItsCaller(name, guarded(name, method));
        }

        /** Wraps a method in the file's check first, then in what holds it to the caller. */
        private VarlinkServer.Method guarded(final String name, final VarlinkServer.Method method) {
            // Every method is registered and the file is asked at the call, so a later open can
            // allow a method or take it away; one not allowed answers as an unregistered one does.
            final VarlinkServer.Method held;
            if ("Start".equals(name)) {
                held =
                        (parameters, replies) ->
                                start(task, project, ALLOWED.get(), parameters, replies, method);
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
            } else if (AT_ITS_PLACE.contains(name)) {
                held =
                        (parameters, replies) -> {
                            refuseAnotherProject(parameters);
                            atItsPlace(task, project, name, parameters, replies, method);
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
            } else if (HELD.contains(name)) {
                held =
                        (parameters, replies) -> {
                            throw heldFromATask(project);
                        };
            } else if ("Ecosystems".equals(name)) {
                held =
                        (parameters, replies) -> {
                            if (!PoreloDaemon.text(parameters, "checkout").isEmpty()) {
                                throw heldFromATask(project);
                            }
                            method.call(parameters, replies);
                        };
            } else if (PROJECT_ROWS.contains(name)) {
                held =
                        (parameters, replies) ->
                                method.call(
                                        parameters,
                                        ownRows(
                                                row -> project.equals(row.get("project")),
                                                replies));
            } else if (TASK_ROWS.contains(name)) {
                held =
                        (parameters, replies) ->
                                method.call(
                                        parameters, ownRows(ofItsTasks(context, project), replies));
            } else if (PROJECT_LISTS.contains(name)) {
                // A task's agent reads its own project's row, not the person's every project.
                held =
                        (parameters, replies) ->
                                method.call(parameters, ownProjectRows(project, replies));
            } else if (TASK_LISTS.contains(name)) {
                // The person's every task is theirs to read; a task's agent reads its own
                // project's.
                held =
                        (parameters, replies) ->
                                method.call(
                                        parameters,
                                        ownProject(
                                                context,
                                                project,
                                                readsTheRun(ALLOWED.get()),
                                                replies));
            } else {
                held = method;
            }
            return (parameters, replies) -> {
                final McpEndpoint allowed = endpoint;
                // The file's section is the project's bound; the task's recorded rights, read at
                // each call, decide within it.
                if (!allowed.methods().contains(name) || !carries(task, name)) {
                    throw notCarried(name);
                }
                ScopedValue.where(CALLER, task)
                        .where(ALLOWED, allowed)
                        .call(
                                () -> {
                                    held.call(parameters, replies);
                                    return null;
                                });
            };
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
            return ContainerName.contract(project, task);
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
