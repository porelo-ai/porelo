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
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.core.project.McpEndpoint;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ContainerName;
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
 * parent; a {@code Stop} on it takes only the caller's own.
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
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,200}");

    private final PoreloContext context;
    private final TaskInventory inventory;
    private final TaskControl control;
    private final Map<String, Served> open = new ConcurrentHashMap<>();

    /** Containers each caller is starting now, so two starts at once both count. */
    private final Map<String, Set<String>> starting = new ConcurrentHashMap<>();

    /** One socket, and the thread serving it. */
    private record Served(VarlinkServer server, Thread thread) {}

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    McpTaskSockets(final PoreloContext context) {
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
        // Again on a resume: the file may allow other methods now, and the socket is the task's.
        close(task);
        final Path socket = socketOf(task);
        final TaskSocket server;
        try {
            server = new TaskSocket(socket, task, endpoint);
        } catch (VarlinkException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        final Thread thread;
        try {
            server.describedBy(cut(PoreloDaemon.description(), new HashSet<>(endpoint.methods())))
                    .reporting(org.fuin.porelo.app.PoreloVersion.version());
            PoreloDaemon.register(server, context, inventory, control);
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
        return kept.toString();
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
     * one of the caller's.
     */
    private void start(
            final String caller,
            final McpEndpoint endpoint,
            final Map<String, Object> parameters,
            final VarlinkServer.Replies replies,
            final VarlinkServer.Method start)
            throws IOException {
        final String agent = PoreloDaemon.text(parameters, "agent");
        final String model = PoreloDaemon.text(parameters, "model");
        if (agent.isEmpty() || !endpoint.allows(agent, model.isEmpty() ? null : model)) {
            throw new VarlinkException(
                    INTERFACE + ".AgentNotAllowed",
                    Map.of("agent", agent, "model", model, "allowed", endpoint.allowed()));
        }
        final int minutes =
                parameters.get("minutes") instanceof Number given
                        ? given.intValue()
                        : TaskLaunch.DEFAULT_MINUTES;
        if (minutes > endpoint.minutes()) {
            throw new VarlinkException(
                    INTERFACE + ".RunTooLong",
                    Map.of("minutes", minutes, "limit", endpoint.minutes()));
        }
        final TaskLaunch.Request request = PoreloDaemon.request(parameters, context);
        final String project = PoreloDaemon.text(parameters, "project");
        // As the launch names it: a task given by its container name is the same task.
        final String container =
                ContainerName.PREFIX
                        + project
                        + "-"
                        + ContainerName.taskFrom(project, request.task());
        final TaskProfile existing =
                TaskProfile.readFrom(context.paths().tasks().containerState(container));
        if (existing != null && !caller.equals(existing.parent())) {
            // Bringing back a task it did not start would run its own prompt in another's work.
            throw notItsOwn(container, existing.parent());
        }
        final Set<String> mine =
                starting.computeIfAbsent(caller, key -> ConcurrentHashMap.newKeySet());
        synchronized (mine) {
            final Set<String> counted = new HashSet<>(children(caller));
            counted.addAll(mine);
            counted.add(container);
            if (counted.size() > endpoint.tasks()) {
                counted.remove(container);
                throw new VarlinkException(
                        INTERFACE + ".ChildLimitReached",
                        Map.of(
                                "parent",
                                caller,
                                "running",
                                counted.size(),
                                "limit",
                                endpoint.tasks()));
            }
            mine.add(container);
        }
        try {
            start.call(parameters, replies);
        } finally {
            synchronized (mine) {
                mine.remove(container);
            }
        }
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
            if (profile != null && caller.equals(profile.parent())) {
                children.add(task.name());
            }
        }
        return children;
    }

    /** Refuses a stop of a task the caller did not start, and otherwise runs it. */
    private void stop(
            final String caller,
            final Map<String, Object> parameters,
            final VarlinkServer.Replies replies,
            final VarlinkServer.Method stop)
            throws IOException {
        final String task = PoreloDaemon.text(parameters, "task");
        final TaskProfile profile =
                task.isEmpty() || !TASK.matcher(task).matches()
                        ? null
                        : TaskProfile.readFrom(context.paths().tasks().containerState(task));
        final String parent = profile == null ? null : profile.parent();
        if (!caller.equals(parent)) {
            throw notItsOwn(task, parent);
        }
        stop.call(parameters, replies);
    }

    private static VarlinkException notItsOwn(final String task, final @Nullable String parent) {
        return new VarlinkException(
                INTERFACE + ".NotItsOwn",
                Map.of("task", task, "parent", parent == null ? "" : parent));
    }

    /**
     * One task's socket: a server that keeps only the methods its file allows, each run as the
     * task, with a start and a stop held to the file.
     */
    private final class TaskSocket extends VarlinkServer {

        private final String task;
        private final McpEndpoint endpoint;

        TaskSocket(final Path socket, final String task, final McpEndpoint endpoint) {
            super(socket, INTERFACE, org.fuin.porelo.core.process.SocketPeer::read);
            this.task = task;
            this.endpoint = endpoint;
        }

        @Override
        public VarlinkServer method(final String name, final VarlinkServer.Method method) {
            if (!endpoint.methods().contains(name)) {
                return this;
            }
            final VarlinkServer.Method held =
                    switch (name) {
                        case "Start" ->
                                (parameters, replies) ->
                                        start(task, endpoint, parameters, replies, method);
                        case "Stop" ->
                                (parameters, replies) -> stop(task, parameters, replies, method);
                        default -> method;
                    };
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
