package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.*;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.app.TaskPanic;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The daemon's methods for starting, watching, stopping and removing tasks.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 *
 * <p>The one call that runs the CLI rather than calling into it, and the reason is written down
 * rather than glossed: 'task run' is seven hundred lines that build an image, mint a token, install
 * hooks, start four helpers in a fixed order and can hand over a terminal. Extracting that the way
 * stop and resume were extracted is the right end state; doing it hastily to a command that is the
 * whole product is not. Spawning it is behavior parity by construction - it *is* the same code - at
 * the cost of parsing one line of its output for the container name.
 *
 * <p>Started detached and never waited on as a child: a task must outlive whoever asked for it,
 * which is the property that lets this daemon be restarted while tasks run.
 */
final class TaskMethods {

    private TaskMethods() {}

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control) {
        // The same question 'porelo task list' asks, answered by the same code. A second
        // implementation is how the two come to disagree about what is running.
        server.method(
                "List",
                (parameters, replies) -> {
                    final List<TaskInventory.Task> found = inventory.tasks();
                    replies.last(
                            Map.of(
                                    "tasks",
                                    found.stream().map(TaskInventory.Task::asMap).toList(),
                                    "unrecognized",
                                    inventory.unrecognized(found)));
                });

        server.method(
                "RefreshTask",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.TaskRefresh.Result result =
                            new org.fuin.porelo.app.TaskRefresh(context)
                                    .refresh(text(parameters, "task"));
                    if (result.outcome() == org.fuin.porelo.app.TaskRefresh.Outcome.NO_SUCH_TASK) {
                        throw new VarlinkException(
                                INTERFACE + ".NoSuchTask",
                                Map.of("task", text(parameters, "task")));
                    }
                    replies.last(result.asMap());
                });

        server.method(
                "Stop",
                (parameters, replies) -> {
                    // Stops and keeps. What used to be Stop(purge:) is Remove, because one verb
                    // that destroys depending on a flag is one people press without reading.
                    final TaskControl.Stopped result = control.stop(text(parameters, "task"));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("outcome", result.outcome().name());
                    answer.put("helpers", result.helpers());
                    answer.put("surviving", result.surviving());
                    answer.put("detail", result.detail() == null ? "" : result.detail());
                    replies.last(answer);
                });

        server.method(
                "Remove",
                (parameters, replies) -> {
                    final TaskControl.Stopped result =
                            control.remove(
                                    text(parameters, "task"),
                                    flag(parameters, "rescue"),
                                    flag(parameters, "force"));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("outcome", result.outcome().name());
                    answer.put("work", result.work() == null ? "" : result.work());
                    answer.put(
                            "rescuedRef", result.rescuedRef() == null ? "" : result.rescuedRef());
                    answer.put("removed", result.removed());
                    answer.put("detail", result.detail() == null ? "" : result.detail());
                    answer.put("discarded", result.discarded());
                    replies.last(answer);
                });

        server.method(
                "Label",
                (parameters, replies) -> {
                    // Through TaskControl, which is what the CLI drives too. A caption written by
                    // one surface and invisible to the other would be worse than none.
                    final String caption = text(parameters, "label");
                    final TaskControl.Labelled outcome =
                            control.label(text(parameters, "task"), caption);
                    replies.last(
                            Map.of(
                                    "outcome",
                                    outcome.name(),
                                    "label",
                                    outcome == TaskControl.Labelled.LABELLED
                                            ? caption.strip()
                                            : ""));
                });

        server.method(
                "Panic",
                (parameters, replies) -> {
                    // The same operation the CLI runs, not a faster one. Somebody reaches for this
                    // when they do not know what is wrong, which is the worst moment for two
                    // implementations of "stop everything" to differ - and it is why shelling out
                    // to 'porelo panic' was never an acceptable answer for an interface.
                    //
                    // It stops and never removes: every workspace, log and unpushed commit
                    // survives, and Start brings a task back with the work it had.
                    final TaskPanic.Result result =
                            new TaskPanic(context).panic(flag(parameters, "dryRun"), "daemon");
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put(
                            "tasks",
                            result.tasks().stream().map(TaskPanic.Stopped::asMap).toList());
                    answer.put("surviving", result.surviving());
                    answer.put("previewed", result.previewed());
                    answer.put("journalled", result.journalled());
                    replies.last(answer);
                });

        server.method(
                "Lock",
                (parameters, replies) -> {
                    // The one thing that mutates the credential store over the socket. Everything
                    // else about the vault is read-only here on purpose: a daemon has no terminal,
                    // so it can shut the vault but can never open it.
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    final boolean keyring = org.fuin.porelo.vault.KernelKeyring.available();
                    answer.put("keyring", keyring);
                    final org.fuin.porelo.vault.KernelKeyring.Forgotten forgotten =
                            keyring
                                    ? new org.fuin.porelo.vault.KernelKeyring(
                                                    context.paths().vault().vaultKeyringKey())
                                            .forget()
                                    : org.fuin.porelo.vault.KernelKeyring.Forgotten.NOTHING_CACHED;
                    // Only a clearing counts as one. A keyring that could not answer must not read
                    // as "there was nothing", which is what a boolean made of it.
                    answer.put(
                            "wasCached",
                            forgotten == org.fuin.porelo.vault.KernelKeyring.Forgotten.CLEARED);
                    // Locking does not reach a running task: its proxy read the credential when it
                    // started and holds it in its own memory. Stopping the task is what ends that,
                    // and an interface that said "locked" without saying this would be claiming
                    // more than happened.
                    answer.put(
                            "holding",
                            context.podman().poreloTasks().stream()
                                    .filter(org.fuin.porelo.runtime.ContainerSummary::running)
                                    .count());
                    replies.last(answer);
                });

        server.method(
                "Watch",
                (parameters, replies) -> {
                    if (!replies.streaming()) {
                        // Asked without 'more', which is a client wanting the state once. Answering
                        // with the list rather than an error means one method serves both, and a
                        // client that cannot stream is not locked out of the data.
                        final List<TaskInventory.Task> found = inventory.tasks();
                        replies.last(
                                Map.of(
                                        "tasks",
                                        found.stream().map(TaskInventory.Task::asMap).toList(),
                                        "unrecognized",
                                        inventory.unrecognized(found)));
                        return;
                    }
                    List<String> previous = null;
                    while (replies.open()) {
                        final List<TaskInventory.Task> tasks = inventory.tasks();
                        // Only on change - and "change" cannot include the age. The runtime's state
                        // is a phrase carrying one: 'Up 3 seconds' becomes 'Up 4 seconds' a second
                        // later, so comparing the whole answer made this fire every second and told
                        // a fleet view to redraw because a clock had moved. Measured against a real
                        // task, which is the only way this shows up at all.
                        final List<String> unrecognized = inventory.unrecognized(tasks);
                        final List<String> settled =
                                new java.util.ArrayList<>(
                                        tasks.stream().map(PoreloDaemon::withoutAge).toList());
                        settled.addAll(unrecognized);
                        if (!settled.equals(previous)) {
                            replies.more(
                                    Map.of(
                                            "tasks",
                                            tasks.stream().map(TaskInventory.Task::asMap).toList(),
                                            "unrecognized",
                                            unrecognized));
                            previous = settled;
                        }
                        sleep(WATCH_INTERVAL);
                    }
                });

        server.method(
                "Prepare",
                (parameters, replies) -> {
                    final java.io.StringWriter collected = new java.io.StringWriter();
                    // Streamed as it happens, for the same reason Start is: a build takes minutes.
                    final PrintWriter sink =
                            replies.streaming()
                                    ? new PrintWriter(new StreamingWriter(replies), true)
                                    : new PrintWriter(collected, true);

                    final String asked = text(parameters, "rebuild");
                    final org.fuin.porelo.runtime.Podman.Rebuild rebuild;
                    try {
                        rebuild =
                                asked.isEmpty()
                                        ? org.fuin.porelo.runtime.Podman.Rebuild.CACHED
                                        : org.fuin.porelo.runtime.Podman.Rebuild.valueOf(asked);
                    } catch (IllegalArgumentException ex) {
                        // Refused rather than defaulted: silently doing the cheapest thing when
                        // somebody asked for the most expensive is the failure this whole feature
                        // exists to avoid.
                        throw new VarlinkException(
                                INTERFACE + ".UnknownRebuild", Map.of("rebuild", asked));
                    }

                    final org.fuin.porelo.app.Preparation.Result result =
                            org.fuin.porelo.app.Preparation.prepare(
                                    context,
                                    java.nio.file.Path.of(text(parameters, "project")),
                                    absent(parameters, "agent"),
                                    rebuild,
                                    flag(parameters, "dryRun"),
                                    sink);
                    sink.flush();
                    replies.last(
                            Map.of(
                                    "outcome",
                                    result.outcome().name(),
                                    "image",
                                    result.image(),
                                    "rebuild",
                                    result.rebuild().name(),
                                    "output",
                                    replies.streaming()
                                            ? List.of()
                                            : collected.toString().lines().toList(),
                                    "detail",
                                    result.detail()));
                });

        server.method(
                "Logs",
                (parameters, replies) -> {
                    // Listed, not guessed: which files a task has depends on what it started, and a
                    // client that knew the names would open an empty viewer for one that never
                    // existed.
                    replies.last(
                            Map.of(
                                    "logs",
                                    new TaskInventory(context)
                                            .logs(text(parameters, "task")).stream()
                                                    .map(TaskInventory.Log::asMap)
                                                    .toList()));
                });

        server.method(
                "LogArchive",
                (parameters, replies) -> {
                    final String task = text(parameters, "task");
                    if (!ContainerName.isTask(task)) {
                        throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
                    }
                    // A daemon has no current directory: written where an interface can offer to
                    // save it from.
                    final java.nio.file.Path archives =
                            context.paths().xdg().state().resolve("archives");
                    final java.nio.file.Path target =
                            archives.resolve(
                                    org.fuin.porelo.app.TaskLogArchive.defaultName(
                                            task.substring("porelo-".length()),
                                            java.time.Instant.now()));
                    final List<String> files;
                    try {
                        java.nio.file.Files.createDirectories(
                                archives,
                                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rwx------")));
                        files =
                                org.fuin.porelo.app.TaskLogsCommand.archive(
                                        context, task, target, flag(parameters, "withAgentOutput"));
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                    replies.last(Map.of("path", target.toString(), "files", files));
                });

        server.method(
                "WorkHeld",
                (parameters, replies) -> {
                    final String task = text(parameters, "task");
                    if (!ContainerName.isTask(task)) {
                        // The same refusal as everywhere else that takes a container name: a name
                        // Porelo did not create belongs to somebody else, and this one runs git
                        // inside it.
                        throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
                    }
                    final boolean up =
                            context.podman().poreloTasks().stream()
                                    .anyMatch(each -> each.name().equals(task) && each.running());
                    final org.fuin.porelo.app.UnhandedWork.Held held =
                            up
                                    ? new org.fuin.porelo.app.TaskControl(context).heldBy(task)
                                    : org.fuin.porelo.app.UnhandedWork.read(
                                            context.paths().tasks().containerState(task));
                    final Map<String, Object> answer = new java.util.LinkedHashMap<>();
                    answer.put("readable", Boolean.valueOf(held.readable()));
                    answer.put("changedFiles", Integer.valueOf(held.changedFiles()));
                    answer.put("unpushedCommits", Integer.valueOf(held.unpushedCommits()));
                    if (held.asOf() != null) {
                        answer.put("asOf", held.asOf());
                    }
                    replies.last(answer);
                });

        server.method(
                "Tail",
                (parameters, replies) -> {
                    final Path log =
                            logOf(context, text(parameters, "task"), text(parameters, "log"));
                    if (log == null) {
                        throw new VarlinkException(
                                INTERFACE + ".NoSuchLog",
                                Map.of(
                                        "task",
                                        text(parameters, "task"),
                                        "log",
                                        text(parameters, "log")));
                    }
                    // From its last lines when a client asks: from the first line, a log of 53 MB
                    // crossed the socket before an interface could show any of it. Every reply says
                    // where its first line starts, for reading back.
                    long position =
                            parameters.get("last") instanceof Number last
                                    ? startOfLast(log, last.intValue())
                                    : 0;
                    // Through the task's own agent when a client asks: its formatter reads one line
                    // at a time, so a console showing a log's end gets that end formatted, never
                    // the whole file. One agent for the whole stream.
                    final String agent =
                            Boolean.TRUE.equals(parameters.get("formatted"))
                                            && log.getParent() != null
                                    ? agentOf(log.getParent())
                                    : null;
                    try (org.fuin.porelo.agent.api.InstalledAgents agents =
                            agent == null ? null : context.agents()) {
                        final org.fuin.porelo.agent.api.InstalledAgent formatter =
                                agents == null || agent == null
                                        ? null
                                        : agents.find(agent).orElse(null);
                        if (!replies.streaming()) {
                            final Chunk chunk = read(log, position);
                            replies.last(
                                    Map.of(
                                            "lines",
                                            shown(formatter, chunk.lines()),
                                            "offset",
                                            position));
                            return;
                        }
                        boolean first = true;
                        while (replies.open()) {
                            final Chunk chunk = read(log, position);
                            if (!chunk.lines().isEmpty() || first) {
                                replies.more(
                                        Map.of(
                                                "lines",
                                                shown(formatter, chunk.lines()),
                                                "offset",
                                                position));
                                first = false;
                            }
                            position = chunk.position();
                            sleep(TAIL_INTERVAL);
                        }
                    }
                });

        server.method(
                "HandIn",
                (parameters, replies) -> {
                    // A file for a running task, in parts: a reply is one message, so a large file
                    // is several calls, and a client on another machine carries bytes, never a
                    // path. It reaches the task only whole.
                    final String task = runningTask(context, text(parameters, "task"));
                    final byte[] part;
                    try {
                        part = java.util.Base64.getDecoder().decode(text(parameters, "part"));
                    } catch (IllegalArgumentException ex) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "part"));
                    }
                    try {
                        final org.fuin.porelo.app.HandIns.Progress progress =
                                org.fuin.porelo.app.HandIns.of(context)
                                        .part(
                                                task,
                                                text(parameters, "name"),
                                                number(parameters, "bytes"),
                                                text(parameters, "sha256"),
                                                number(parameters, "offset"),
                                                part,
                                                account());
                        final Map<String, Object> reply = new java.util.LinkedHashMap<>();
                        reply.put("received", progress.received());
                        if (progress.file() != null) {
                            reply.put("file", progress.file().asMap());
                        }
                        replies.last(reply);
                        if (progress.file() != null) {
                            // Its agent, when it waits at its prompt; otherwise the next message
                            // pass tells it.
                            new org.fuin.porelo.app.AgentWake(context).announceFiles(task);
                        }
                    } catch (org.fuin.porelo.app.HandIns.Refused refused) {
                        throw new VarlinkException(
                                INTERFACE + "." + refused.error(), refused.parameters());
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                });

        server.method(
                "TakeBack",
                (parameters, replies) -> {
                    final String task = runningTask(context, text(parameters, "task"));
                    try {
                        replies.last(
                                Map.of(
                                        "file",
                                        org.fuin.porelo.app.HandIns.of(context)
                                                .takeBack(task, text(parameters, "name"), account())
                                                .asMap()));
                    } catch (org.fuin.porelo.app.HandIns.Refused refused) {
                        throw new VarlinkException(
                                INTERFACE + "." + refused.error(), refused.parameters());
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                });

        server.method(
                "HandIns",
                (parameters, replies) -> {
                    // Answered for a task that is gone too: the record outlives it, which is what
                    // it is for.
                    final String task = text(parameters, "task");
                    if (!ContainerName.isTask(task)) {
                        throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
                    }
                    try {
                        replies.last(
                                Map.of(
                                        "record",
                                        org.fuin.porelo.app.HandIns.of(context)
                                                .record(task)
                                                .stream()
                                                .map(org.fuin.porelo.app.HandIns.Entry::asMap)
                                                .toList()));
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                });

        server.method(
                "Screen",
                (parameters, replies) -> {
                    // A tile's console for work in a terminal, which has no log to follow: what its
                    // session shows, read by tmux in the task without attaching. A snapshot; a
                    // client asks again.
                    final String task = text(parameters, "task");
                    if (!ContainerName.isTask(task)
                            || context.podman().poreloTasks().stream()
                                    .noneMatch(each -> each.name().equals(task))) {
                        throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
                    }
                    final org.fuin.porelo.runtime.Podman.Screen screen =
                            context.podman()
                                    .screen(
                                            task,
                                            parameters.get("last") instanceof Number last
                                                    ? last.intValue()
                                                    : 20,
                                            Boolean.TRUE.equals(parameters.get("escapes")));
                    replies.last(Map.of("lines", screen.lines(), "live", screen.live()));
                });

        server.method(
                "CanStart",
                (parameters, replies) -> {
                    // The rule lives in one place and this is not it: StartCheck delegates every
                    // decision to the same objects a launch uses, so this and an actual Start
                    // cannot come to different conclusions. That was the whole argument for a
                    // method over a reply field - the rule needs four inputs and a client has one
                    // of them.
                    final String project = text(parameters, "project");
                    // A name this machine does not have is an ANSWER here, not an error: asking
                    // whether work can start is a question a client is entitled to ask about a
                    // project that turns out not to exist, and making it throw would hand it an
                    // exception for asking.
                    final java.nio.file.Path named =
                            project.isEmpty()
                                    ? null
                                    : org.fuin.porelo.app.ProjectSource.resolve(context, project)
                                            .file();
                    if (!project.isEmpty() && named == null) {
                        replies.last(
                                Map.of(
                                        "ready",
                                        false,
                                        "outcome",
                                        "NO_PROJECT_FILE",
                                        "agent",
                                        "",
                                        "provider",
                                        "",
                                        "credential",
                                        "",
                                        "host",
                                        "",
                                        "hostKeys",
                                        java.util.List.of(),
                                        "detail",
                                        "no project '"
                                                + project
                                                + "' here. This machine has: "
                                                + String.join(
                                                        ", ",
                                                        org.fuin.porelo.app.ProjectSource.names(
                                                                context))));
                        return;
                    }
                    final Map<String, Object> answer =
                            new LinkedHashMap<>(
                                    org.fuin.porelo.app.StartCheck.check(
                                                    context,
                                                    // A name, as everything else takes now. Left
                                                    // out, no project is checked - which is how an
                                                    // interface asks about the machine before one
                                                    // is chosen.
                                                    named,
                                                    empty(parameters, "task"),
                                                    empty(parameters, "agent"),
                                                    empty(parameters, "provider"),
                                                    empty(parameters, "credentialType"),
                                                    empty(parameters, "repository"),
                                                    // A run with no gate works on no repository, so
                                                    // there is nothing for it to name - and asking
                                                    // for one would be asking about something that
                                                    // does not exist for that run.
                                                    !flag(parameters, "noGate"),
                                                    credentials(parameters))
                                            .asMap());
                    // What the host offers, asked only when it is the question: it reaches the
                    // network.
                    answer.put(
                            "hostKeys",
                            "UNKNOWN_HOST_KEY".equals(answer.get("outcome"))
                                    ? org.fuin.porelo.app.HostKeys.offeredBy(
                                                    context, String.valueOf(answer.get("host")))
                                            .stream()
                                            .map(org.fuin.porelo.app.HostKeys.Offered::asMap)
                                            .toList()
                                    : java.util.List.of());
                    replies.last(answer);
                });

        server.method(
                "Start",
                (parameters, replies) -> {
                    // Refused as itself before anything is launched: a destination nobody declared
                    // is a typed answer an interface can show, not a launch line among others. A
                    // host never met is a typed refusal, before anything is made: it went ahead
                    // without a gate before. The host the start will fetch from: the upstream it
                    // was given, as the launch asks it.
                    final String unmet =
                            org.fuin.porelo.app.StartCheck.unknownHost(
                                    context,
                                    projectFile(parameters, context),
                                    empty(parameters, "repository"),
                                    address(parameters, "upstream"));
                    if (unmet != null) {
                        throw new VarlinkException(
                                INTERFACE + ".UnknownHostKey",
                                Map.of(
                                        "host",
                                        unmet,
                                        "hostKeys",
                                        org.fuin.porelo.app.HostKeys.offeredBy(context, unmet)
                                                .stream()
                                                .map(org.fuin.porelo.app.HostKeys.Offered::asMap)
                                                .toList(),
                                        "detail",
                                        "this machine has never met "
                                                + unmet
                                                + "; confirm its key with TrustHostKey"
                                                + " against what its owner publishes, and start again"));
                    }
                    final String undeclared =
                            org.fuin.porelo.app.StartCheck.undeclared(
                                    context,
                                    projectFile(parameters, context),
                                    credentials(parameters));
                    if (undeclared != null) {
                        throw new VarlinkException(
                                INTERFACE + ".NoSuchDestination",
                                Map.of("name", undeclared.substring(0, undeclared.indexOf('\n'))));
                    }
                    // Into the domain, not out to a subprocess. Until TaskLaunch existed this
                    // spawned 'porelo task run' and read one line of its output for the container
                    // name, because running the command was the only way to start a task. Now the
                    // CLI and this call the same object, so neither can grow a behavior the other
                    // lacks - and there is because the name comes back as a value.
                    final java.io.StringWriter collected = new java.io.StringWriter();
                    final PrintWriter sink =
                            replies.streaming()
                                    // Streamed as it happens: building an image takes minutes, and
                                    // an interface showing nothing for that long is
                                    // indistinguishable from one that hung.
                                    ? new PrintWriter(new StreamingWriter(replies), true)
                                    : new PrintWriter(collected, true);

                    final java.util.concurrent.atomic.AtomicReference<String> started =
                            new java.util.concurrent.atomic.AtomicReference<>("");
                    final TaskLaunch launch = new TaskLaunch(context, request(parameters, context));
                    // A task that already exists is brought back, not created again - the same call
                    // the CLI makes first, so a stopped task answers the same over the socket as at
                    // a terminal.
                    final TaskLaunch.Existing existing = launch.startExisting(sink, sink);
                    final int code;
                    // What Start did, or the refusal it answered with - on the final reply, as the
                    // contract says.
                    final String action = existing == null ? "CREATE" : existing.action();
                    if (existing != null) {
                        started.set(existing.container());
                        // Brought back with a prompt, it runs - continuing its session where the
                        // agent can - as a new task with a prompt does. A start that ignored it
                        // would report a run nobody performed.
                        code =
                                existing.code() != 0 || empty(parameters, "prompt") == null
                                        ? existing.code()
                                        : launch.runAgentInExisting(
                                                existing.container(), null, sink, sink);
                    } else {
                        code =
                                launch.launch(
                                        sink,
                                        sink,
                                        running -> {
                                            started.set(running.container());
                                            // With a prompt this is an unattended run and has to
                                            // actually run: a task started over the socket is as
                                            // unattended as one started at a terminal, and a client
                                            // that got "started" for a run nobody performed would
                                            // wait for output that was never going to come. Without
                                            // one, there is no terminal on the far end of a socket,
                                            // so the task is up and that is all.
                                            if (empty(parameters, "prompt") == null) {
                                                return 0;
                                            }
                                            return launch.runAgent(
                                                    running.runner(),
                                                    running.selected(),
                                                    running.container(),
                                                    running.environment(),
                                                    sink,
                                                    sink);
                                        });
                    }
                    sink.flush();
                    final org.fuin.porelo.gate.GitGate.Waiting earlier = launch.earlierWork();
                    if (existing == null && code != 0 && earlier != null) {
                        // Typed, so an interface offers the waiting work or another name in the
                        // same dialog; nothing was made.
                        System.out.println(startLine(text(parameters, "task"), "", code));
                        throw new VarlinkException(
                                INTERFACE + ".EarlierWorkWaits",
                                Map.of(
                                        "task",
                                        text(parameters, "task"),
                                        "commit",
                                        earlier.commit(),
                                        "subject",
                                        earlier.subject()));
                    }
                    final org.fuin.porelo.app.UpstreamProbe.Answer unreadable =
                            launch.unreadableUpstream();
                    if (existing == null && code != 0 && unreadable != null) {
                        // Typed, as CanStart answers it, so an interface shows the cause and its
                        // fix; nothing was made.
                        System.out.println(startLine(text(parameters, "task"), "", code));
                        throw new VarlinkException(
                                INTERFACE + ".UpstreamUnreadable",
                                Map.of(
                                        "cause",
                                        unreadable.cause().name(),
                                        "detail",
                                        unreadable.detail(),
                                        "fix",
                                        unreadable.fix()));
                    }
                    // The one trace a start leaves on the machine: what was output went to the
                    // caller only.
                    final String container =
                            java.util.Objects.requireNonNullElse(started.get(), "");
                    System.out.println(startLine(text(parameters, "task"), container, code));
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("container", container);
                    reply.put("exitCode", code);
                    reply.put(
                            "output",
                            replies.streaming()
                                    ? List.of()
                                    : List.of(collected.toString().split("\n", -1)));
                    if (action != null) {
                        reply.put("action", action);
                    }
                    replies.last(reply);
                });
    }

    /**
     * The agent a task runs, from its profile, or {@code null} when it has none or nothing says.
     */
    private static @Nullable String agentOf(Path state) {
        try {
            final org.fuin.porelo.wire.TaskProfile profile =
                    org.fuin.porelo.wire.TaskProfile.readFrom(state);
            return profile == null ? null : profile.agent();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Lines as the agent shows them, those it hides left out; as they are without an agent to ask.
     */
    private static List<String> shown(
            org.fuin.porelo.agent.api.@Nullable InstalledAgent formatter, List<String> lines) {
        if (formatter == null || lines.isEmpty()) {
            return lines;
        }
        final List<String> shown = new ArrayList<>();
        for (final String line : formatter.format(lines)) {
            if (line != null) {
                shown.add(line);
            }
        }
        return shown;
    }

    /**
     * Returns a task's name when it is one of Porelo's and running.
     *
     * @param context The machine.
     * @param task The name a caller gave.
     * @return The same name.
     * @throws VarlinkException NoSuchTask, or NotRunning.
     */
    private static String runningTask(final PoreloContext context, final String task) {
        final java.util.Optional<org.fuin.porelo.runtime.ContainerSummary> found =
                ContainerName.isTask(task)
                        ? context.podman().poreloTasks().stream()
                                .filter(each -> each.name().equals(task))
                                .findFirst()
                        : java.util.Optional.empty();
        if (found.isEmpty()) {
            throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
        }
        if (!found.get().running()) {
            throw new VarlinkException(INTERFACE + ".NotRunning", Map.of("task", task));
        }
        return task;
    }

    private static long number(final Map<String, Object> parameters, final String name) {
        if (parameters.get(name) instanceof Number number) {
            return number.longValue();
        }
        throw new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", name));
    }

    /** Who calls: the account the daemon runs as, which is the only one its socket lets in. */
    private static String account() {
        return System.getProperty("user.name", "");
    }
}
