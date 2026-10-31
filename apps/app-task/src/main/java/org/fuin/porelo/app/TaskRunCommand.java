package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ContainerSummary;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Starts one agent task against a project, and hands the terminal to a shell inside it.
 *
 * <p><strong>One verb for two cases.</strong> A task that does not exist is created; one that
 * exists and is stopped is brought back with the workspace, the branch and the uncommitted changes
 * it has. The caller cannot know which case they are in - that is what {@code startAction} on the
 * listing is for - and pressing should not be how they find out. A task that is already running is
 * refused rather than started twice.
 *
 * <p><strong>The container is kept unless {@code --rm} says otherwise.</strong> This used to be the
 * other way round: the command a person reaches for first removed what it had just made, which is
 * how an operator lost a task by typing the obvious thing.
 */
@Command(
        name = "start",
        mixinStandardHelpOptions = true,
        description =
                "Starts a task: creates it, brings back the one that is stopped, or at a terminal goes into"
                        + " the one that is running.")
public class TaskRunCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /**
     * Exit code recorded for a run that was signalled rather than ended.
     *
     * <p>128 plus SIGINT, the shell's own convention. The value matters less than that it is not
     * zero: {@code cleanUp} stops and keeps a task that ended badly, and an interrupted run is one
     * - the workspace may hold commits that never reached the gate.
     */
    static final int INTERRUPTED = 130;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<task>",
            description =
                    "Name of the task. Without one, in a checkout a task was started from, that task;"
                            + " otherwise the repository's name - or the next free one, such as my-first-2 - and the start"
                            + " says which.")
    private String task = "shell";

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            description =
                    "Project name, as 'porelo project list' prints it. Default: the one that names the"
                            + " checkout you stand in, else 'default'.")
    private @Nullable String projectName;

    @Option(
            names = "--agent",
            paramLabel = "<name>",
            description = "Agent to install in the image. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(
            names = "--provider",
            paramLabel = "<name>",
            description = "Provider to serve the models. Default: the agent's own.")
    private @Nullable String providerName;

    @Option(
            names = "--set",
            paramLabel = "<name>[=<value>]",
            description =
                    "A value the project declares under 'parameters': NAME=VALUE, or NAME alone for the"
                            + " value of its variable in this shell. Repeatable; wins over --set-file and"
                            + " --set-env.")
    private java.util.List<String> sets = new java.util.ArrayList<>();

    @Option(
            names = "--set-file",
            paramLabel = "<file>",
            description =
                    "Values from a YAML file of NAME: VALUE. Repeatable, the last file winning; wins over"
                            + " --set-env.")
    private java.util.List<Path> setFiles = new java.util.ArrayList<>();

    @Option(
            names = "--set-env",
            description =
                    "Every declared value whose variable is set in this shell. Nothing is taken from the"
                            + " shell without this or --set NAME.")
    private boolean setEnv;

    @Option(
            names = "--credential",
            paramLabel = "<entry>=<destination>",
            description =
                    "Another credential this task holds, beyond the agent's own and the project's: a vault"
                            + " entry and the destination or provider it is for. Repeatable. It reaches the task as"
                            + " PORELO_TOKEN_<ENTRY> and PORELO_URL_<ENTRY>, a token worthless anywhere else.")
    private java.util.Map<String, String> credentials = new java.util.LinkedHashMap<>();

    @Option(
            names = "--credential-type",
            paramLabel = "<type>",
            description = "Overrides the kind recorded with the credential when it was stored.")
    private @Nullable String credentialType;

    @Option(
            names = "--token-hours",
            paramLabel = "<n>",
            description = "How long the phantom token is accepted. Default: ${DEFAULT-VALUE}")
    private int tokenHours = 8;

    @Option(
            names = "--shell",
            paramLabel = "<path>",
            description = "Shell to attach. Default: ${DEFAULT-VALUE}")
    private String shell = "/bin/bash";

    @Option(
            names = "--attach",
            paramLabel = "<what>",
            description = "What to start on attach: agent or shell. Default: ${DEFAULT-VALUE}")
    private String attach = "agent";

    @Option(
            names = "--rm",
            description = "Removes the container when the session ends. Default: it is kept.")
    private boolean rm;

    @Option(
            names = "--dry-run",
            description = "Reports what would be done without starting anything.")
    private boolean dryRun;

    @Option(
            names = {"-P", "--prompt"},
            paramLabel = "<text>",
            description = "Runs the agent with this prompt instead of attaching a shell.")
    private @Nullable String prompt;

    @Option(
            names = "--model",
            paramLabel = "<name>",
            description = "Model to ask the agent for. Only if the agent declares a model flag.")
    private @Nullable String model;

    @Option(
            names = "--max-turns",
            paramLabel = "<n>",
            description = "Turn limit. Only if the agent declares one.")
    private @Nullable Integer maxTurns;

    @Option(
            names = "--minutes",
            paramLabel = "<n>",
            description = "How long the agent may run. Default: ${DEFAULT-VALUE}")
    private int minutes = 30;

    @Option(
            names = "--upstream",
            paramLabel = "<url>",
            description = "Upstream the gate forwards approved pushes to.")
    private @Nullable String upstream;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<name>",
            description =
                    "Which of the project's repositories the task works on. Required:"
                            + " there is no default, and the project's own repository is one of the"
                            + " choices.")
    private @Nullable String repository;

    @Option(
            names = "--branch",
            paramLabel = "<name>",
            description =
                    "The task's branch at the forge, named outright instead of by the project's"
                            + " branch.template; it must match branch.pattern too.")
    private @Nullable String branch;

    @Option(
            names = "--no-gate",
            description =
                    "Runs without a workspace or a git gate. The agent gets an empty"
                            + " directory and cannot commit anywhere.")
    private boolean noGate;

    @Option(
            names = "--clearance",
            paramLabel = "<mode>",
            description =
                    "What to do with a blocked connection: prompt, allow, deny, or off."
                            + " Default: ${DEFAULT-VALUE}")
    private String clearance = "prompt";

    @Option(
            names = "--raw",
            description = "Shows the agent's output as it came, without its own formatter.")
    private boolean raw;

    @Option(
            names = "--detach",
            description = "Starts the task and returns, instead of handing over a shell.")
    private boolean detach;

    @Option(
            names = "--no-input",
            description =
                    "Asks nothing: what is missing is refused and named, as without a terminal.")
    private boolean noInput;

    @Option(
            names = "--yes",
            description =
                    "Takes a harmless remedy for what is missing without asking; never trusts or discards.")
    private boolean yes;

    @Option(
            names = "--now",
            description =
                    "Returns as soon as the task exists, without waiting for the image"
                            + " build. Needs --detach: there is nothing to attach to yet.")
    private boolean now;

    @Option(
            names = "--host",
            paramLabel = "<login>@<host>",
            description =
                    "Starts the task on that host over SSH, its key certified here by your own agent,"
                            + " which is never forwarded. Starts it detached; paths and files are the host's.")
    private @Nullable String host;

    @Option(
            names = "--certify-stdin",
            hidden = true,
            description =
                    "Asks for the task's certificate on standard output and reads it from standard input.")
    private boolean certifyStdin;

    @Option(
            names = "--restarted",
            description =
                    "Brings back every task the machine took down when it restarted, each whole or not at"
                            + " all, without naming them; a task started with a prompt stays stopped, its session kept.")
    private boolean restarted;

    @Spec private CommandSpec spec;

    /** Whether a person is at a terminal; a test says so, since it has none. */
    java.util.function.BooleanSupplier terminal = Offer::atTerminal;

    private PoreloContext context = PoreloContext.real();

    /** The phantom-token environment, kept so the agent run gets the same one the container has. */
    private java.util.Map<String, String> environmentCache = java.util.Map.of();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * Returns how somebody is meant to be involved in this task.
     *
     * <p>Derived here, once, from what was asked for: a prompt means nobody is expected to be
     * watching, and otherwise it is whichever session was asked to be attached. Written down at
     * start rather than re-derived later, because a task that has finished no longer has flags to
     * derive it from.
     *
     * @return The mode.
     */
    private org.fuin.porelo.wire.TaskMode mode() {
        if (prompt != null) {
            return org.fuin.porelo.wire.TaskMode.UNATTENDED;
        }
        return "shell".equals(attach)
                ? org.fuin.porelo.wire.TaskMode.SHELL
                : org.fuin.porelo.wire.TaskMode.AGENT;
    }

    /**
     * Returns the one existing task the given name means, when the directory is in no checkout;
     * else {@code null}. In a checkout the checkout says the project, and a name there may be a new
     * task's.
     */
    private @Nullable ContainerSummary existingOutsideACheckout() {
        if (TaskTarget.fromCheckout(context, Path.of(".")) != null) {
            return null;
        }
        final String container = TaskTarget.spelled(context, task);
        return context.podman().poreloTasks().stream()
                .filter(summary -> summary.name().equals(container))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the host's arguments for a start: the same ones less {@code --host}, certified and
     * detached there.
     *
     * @param spec The parsed start.
     * @return The host's Porelo arguments.
     */
    static java.util.List<String> hostArguments(final CommandSpec spec) {
        final java.util.List<String> host =
                java.util.List.of("--certify-stdin", "--detach", "--no-input");
        final java.util.List<String> arguments =
                new java.util.ArrayList<>(java.util.List.of("task", "start"));
        // From what was parsed, not from the words given: each value goes with its own option, as
        // "--name=value", so no word can pair with another option there or slip behind a "--".
        final picocli.CommandLine.ParseResult parsed = spec.commandLine().getParseResult();
        // Each option once, however often it was given: its values hold every occurrence.
        for (final picocli.CommandLine.Model.OptionSpec option :
                new java.util.LinkedHashSet<>(parsed.matchedOptions())) {
            final String name = option.longestName();
            if (name.equals("--host") || host.contains(name)) {
                continue;
            }
            if (option.arity().max() == 0) {
                // Its value, not its name alone: "--x=false" and a negated "--no-x" stay false.
                arguments.add(name + "=" + option.getValue());
                continue;
            }
            for (final String value : option.originalStringValues()) {
                arguments.add(name + "=" + value);
            }
        }
        arguments.addAll(host);
        final java.util.List<String> positionals = new java.util.ArrayList<>();
        for (final picocli.CommandLine.Model.PositionalParamSpec positional :
                parsed.matchedPositionals()) {
            positionals.addAll(positional.originalStringValues());
        }
        if (!positionals.isEmpty()) {
            arguments.add("--");
            arguments.addAll(positionals);
        }
        return arguments;
    }

    /**
     * Starts the task on a host, by the same arguments less {@code --host}, certified here.
     *
     * @param out Where the host's output goes.
     * @param err Where its errors go.
     * @return The host's exit code.
     */
    private int onHost(final PrintWriter out, final PrintWriter err) {
        final String refused = hostRefusal();
        if (refused != null) {
            err.println("porelo: " + refused);
            err.flush();
            return 64;
        }
        final java.nio.file.Path agent = RemoteStart.agent(context);
        if (agent == null) {
            err.println(
                    "porelo: --host certifies the task's key with your agent, and none is running");
            err.flush();
            return 64;
        }
        final java.util.List<String> arguments = hostArguments(spec);
        final java.util.List<String> connection = new java.util.ArrayList<>(RemoteStart.SSH);
        connection.add(host);
        try {
            return new RemoteStart(connection, agent)
                    .run(arguments, java.util.Objects.requireNonNull(projectName), task, out, err);
        } catch (java.io.IOException ex) {
            err.println("porelo: could not reach " + host + ": " + ex.getMessage());
            err.flush();
            return 69;
        }
    }

    /**
     * Says why a start cannot be run on a host as given, or {@code null}. The host runs the same
     * start in its own account, with no checkout and its own environment, so whatever this machine
     * would take from either has to be given.
     */
    private @Nullable String hostRefusal() {
        if (!java.util.Objects.requireNonNull(host).matches(RemoteStart.HOST)) {
            return "--host takes <login>@<host>";
        }
        if (restarted) {
            return "--host does not take --restarted";
        }
        // Named, not defaulted: the host would pick its own name, and the certificate this one.
        if (!spec.commandLine().getParseResult().hasMatchedPositional(0)
                || task.isBlank()
                || task.startsWith("@")
                || task.startsWith("-")) {
            return "--host needs the task's name, which its certificate names";
        }
        if (projectName == null || projectName.isBlank()) {
            return "--host needs --project: the host has no checkout to take it from";
        }
        if (!projectName.matches(RemoteStart.PROJECT)) {
            return "--host needs a project's name, " + RemoteStart.PROJECT;
        }
        // The host would take a container's spelling for the task it names, and certify that.
        final java.util.Optional<String> misnamed =
                org.fuin.porelo.runtime.ContainerName.refusal(projectName, task);
        if (misnamed.isPresent()) {
            return misnamed.get();
        }
        if (!org.fuin.porelo.runtime.ContainerName.taskFrom(projectName, task).equals(task)) {
            return "--host needs the task's own name, not its container's";
        }
        if (!setFiles.isEmpty()) {
            return "--host does not take --set-file: the host would read it from its own files";
        }
        if (setEnv || sets.stream().anyMatch(each -> !each.contains("="))) {
            return "--host takes --set NAME=VALUE only: the host's environment is not this shell's";
        }
        return null;
    }

    @Override
    public Integer call() {
        // A refusal the person answered with another way to start - another name, or a shell -
        // starts again with it, at most a few times, so answers that each lead to another refusal
        // end rather than loop.
        int code = startOnce();
        for (int tries = 0; code == TaskLaunch.RETRY && tries < 3; tries++) {
            code = startOnce();
        }
        return code == TaskLaunch.RETRY ? 69 : code;
    }

    /**
     * Takes the way the person chose instead of a refusal, for the next start.
     *
     * @param launch The start.
     * @param code What it returned.
     * @return The code, {@link TaskLaunch#RETRY} when the next start is to go another way.
     */
    private int retried(final TaskLaunch launch, final int code) {
        final String chosen = launch.retry();
        if (code != TaskLaunch.RETRY || chosen == null) {
            return code == TaskLaunch.RETRY ? 69 : code;
        }
        if (chosen.equals("shell")) {
            attach = "shell";
        } else if (chosen.startsWith("name:")) {
            task = chosen.substring("name:".length());
        }
        return code;
    }

    private Integer startOnce() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Before --restarted: a start meant for a host never restarts this machine's tasks.
        if (host != null) {
            return onHost(out, err);
        }
        if (restarted) {
            return restarted(out, err);
        }

        if (now && !detach) {
            // Refused rather than guessed at. Returning before the build finishes and then
            // attaching would mean attaching to an image that is not there yet.
            err.println(
                    "porelo: --now needs --detach: there is nothing to attach to until the"
                            + " image is built.");
            err.flush();
            return 64;
        }

        final Path projectFile;
        try {
            if (projectName == null) {
                // No project named: the checkout stood in says which. A followed project naming its
                // origin comes first; otherwise it is 'default', which every machine has. A named
                // project never depends on the directory.
                final ContainerSummary existing =
                        spec.commandLine().getParseResult().hasMatchedPositional(0)
                                ? existingOutsideACheckout()
                                : null;
                if (existing != null && existing.project() != null) {
                    // Outside a checkout, a name an existing task has is that task, by either of
                    // its names, as for every other task command: a stopped one is brought back
                    // without its project named.
                    projectName = existing.project();
                    task = existing.name().substring(("porelo-" + projectName + "-").length());
                    spec.commandLine()
                            .getOut()
                            .println(
                                    "project        "
                                            + projectName
                                            + " - that of the task "
                                            + existing.name());
                } else {
                    final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, Path.of("."));
                    projectName = choice.project();
                    if (repository == null) {
                        repository = choice.repository();
                    }
                    spec.commandLine().getOut().println("project        " + choice.said());
                }
            }
            // A name, not a path: which project a task belongs to must not depend on which
            // directory somebody was standing in. Where the file comes from is ProjectSource's
            // answer, and it prefers the one this machine verified.
            projectFile = ProjectSource.require(context, projectName);
            if ((repository == null || repository.isBlank())
                    && !noGate
                    && !dryRun
                    && !noInput
                    && Offer.atTerminal()) {
                // Asked here, before the task is named, so an unnamed task is named after the
                // repository chosen.
                final org.fuin.porelo.core.project.Project chosenFrom =
                        ProjectReader.read(projectFile);
                if (!chosenFrom.workRepositoryNames().isEmpty()) {
                    repository =
                            repositoryChosen(
                                    chosenFrom,
                                    new Offer(Offer.Asker.terminal(), noInput, yes, err));
                    if (repository == null) {
                        return 2;
                    }
                }
            }
            if (!spec.commandLine().getParseResult().hasMatchedPositional(0)
                    && repository != null
                    && !repository.isBlank()) {
                // The task started from this checkout, when there is one, as every other task
                // command takes it; a new one only when there is none.
                final java.util.List<String> started =
                        TaskTarget.fromCheckout(context, Path.of("."));
                final String prefix = "porelo-" + projectName + "-";
                if (started != null && started.size() > 1) {
                    final TaskTarget.Found found =
                            TaskTarget.resolve(
                                    context,
                                    null,
                                    Path.of("."),
                                    TaskTarget.console(spec.commandLine().getOut()));
                    if (found.container() == null) {
                        err.println(
                                "porelo: "
                                        + found.refusal()
                                        + ", or give a new name to start another");
                        err.flush();
                        return found.code();
                    }
                    task = found.container().substring(prefix.length());
                    spec.commandLine().getOut().println("task           " + task);
                } else if (started != null
                        && started.size() == 1
                        && started.getFirst().startsWith(prefix)) {
                    task = started.getFirst().substring(prefix.length());
                    spec.commandLine()
                            .getOut()
                            .println(
                                    "task           "
                                            + task
                                            + " - the one started from this checkout; give a name to start another");
                } else {
                    task = TaskNames.fromRepository(context, projectName, repository);
                    spec.commandLine()
                            .getOut()
                            .println(
                                    "task           "
                                            + task
                                            + " - named after its repository; give a name to choose another");
                }
            }
        } catch (final ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }

        // Read here, where the caller's shell and files are; checked against the declarations by
        // the
        // start itself, as a daemon's start is.
        final java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> given;
        try {
            given = given(ProjectReader.read(projectFile).parameters());
        } catch (final ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }

        // Starting the task is the domain's job; what this class adds is the terminal. The daemon
        // builds the same request and gets the same behavior without running a CLI.
        final TaskLaunch launch =
                new TaskLaunch(
                                context,
                                new TaskLaunch.Request(
                                        task,
                                        projectFile,
                                        agentName,
                                        providerName,
                                        credentialType,
                                        tokenHours,
                                        upstream,
                                        noGate,
                                        dryRun,
                                        clearance,
                                        !rm,
                                        mode(),
                                        prompt,
                                        model,
                                        maxTurns,
                                        minutes,
                                        repository,
                                        credentials,
                                        given,
                                        branch),
                                new StartSteps(step -> StartSteps.render(out, step), err))
                        // What a refusal would name is offered at a terminal instead; a script or a
                        // pipe is refused as before.
                        .offering(offer(noInput, yes, Offer.atTerminal(), err));
        if (certifyStdin) {
            launch.certifyingOver(
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    System.in, java.nio.charset.StandardCharsets.UTF_8)),
                    out);
        }

        // A person at a terminal who typed 'start' wants to work in the task, running or not; a
        // script is answered with RUNNING as before, so it never hangs in a session.
        final boolean goingIn = terminal.getAsBoolean() && !noInput;
        final TaskLaunch.Existing existing = launch.startExisting(out, err, goingIn);
        if (existing != null) {
            if ("RUNNING".equals(existing.action()) && existing.code() == 0) {
                return intoRunning(existing.container(), out, err);
            }
            if (existing.code() != 0) {
                return existing.code();
            }
            if (prompt != null) {
                // Brought back with a prompt: run it, continuing the conversation where the agent
                // can.
                final int code =
                        launch.runAgentInExisting(
                                existing.container(),
                                (agent, container) -> render(agent, container, out, err),
                                out,
                                err);
                return code == 0 || code == TaskLaunch.NO_AGENT ? code : 70;
            }
            if (detach) {
                return existing.code();
            }
            // An agent task whose session is gone starts its agent again, continuing where it can.
            return context.exec()
                    .applyAsInt(
                            context.tasks()
                                    .attachCommand(
                                            existing.container(),
                                            shell,
                                            new TaskSession(context)
                                                    .attachedAgent(existing.container(), out),
                                            existing.project()
                                                    + "/"
                                                    + org.fuin.porelo.runtime.ContainerName.taskIn(
                                                            existing.project(),
                                                            existing.container())));
        }

        return retried(
                launch,
                launch.launch(
                        out,
                        err,
                        running -> {
                            environmentCache = running.environment();

                            if (prompt != null) {
                                // The run itself belongs to the launch, which the daemon calls too;
                                // what is left here is showing it to a person.
                                final int code =
                                        launch.runAgent(
                                                running.runner(),
                                                running.selected(),
                                                running.container(),
                                                running.environment(),
                                                out,
                                                err);
                                if (code == TaskLaunch.NO_AGENT) {
                                    // A refusal, not a failed run: there is no log to render and
                                    // nothing to say beyond what was already said.
                                    return code;
                                }
                                render(running.selected(), running.container(), out, err);
                                return code == 0 ? 0 : 70;
                            }

                            if (detach) {
                                // Everything below replaces this process, which makes the normal
                                // path impossible to drive from a script or a test. This is the
                                // seam for both.
                                out.println("attached  no");
                                out.flush();
                                return 0;
                            }

                            // The same arguments the unattended path passes. An attached run is
                            // still a run inside the box, so an agent that would stop to ask
                            // permission is turned off here too - reported from the test machine,
                            // where an attached claude asked to approve a command the container had
                            // already made safe.
                            final String startWith =
                                    "agent".equals(attach) && running.selected() != null
                                            ? org.fuin.porelo.runtime.ShellWords.quote(
                                                    new TaskGuide(
                                                                    context.paths(),
                                                                    running.container())
                                                            .instructing(
                                                                    running.selected().definition(),
                                                                    running.selected()
                                                                            .definition()
                                                                            .attendedCommand(
                                                                                    model)))
                                            : null;
                            out.println(
                                    rm
                                            ? "Attaching. Leaving the shell removes the container; Ctrl-C stops it and"
                                                    + " keeps it, because an interrupted run may hold work."
                                            : "Attaching. The container is kept when you leave; remove it with"
                                                    + " 'porelo task remove "
                                                    + running.container()
                                                    + "'.");
                            if (startWith != null) {
                                out.println(
                                        "Starting "
                                                + startWith
                                                + " first; you get a shell when it exits.");
                            }
                            out.flush();

                            // Waits rather than replacing this process, so there is still something
                            // here to remove the container when the shell ends.
                            //
                            // Through a Teardown rather than a plain finally, because the way this
                            // ends most often is not a return: Ctrl-C and a closed terminal signal
                            // the whole process group, which kills the gate, the broker and the
                            // watcher and leaves the container running without them. The status
                            // starts at "interrupted" so that a signal takes the keep-and-stop
                            // branch - work that never reached the gate is still in there.
                            final java.util.concurrent.atomic.AtomicInteger outcome =
                                    new java.util.concurrent.atomic.AtomicInteger(INTERRUPTED);
                            int left;
                            // Leaving the window is not finishing the work, and both end the
                            // command this terminal is attached to. The session's own shell is what
                            // tells them apart: it writes a marker when it returns, and the absence
                            // of one keeps the task.
                            //
                            // Asking tmux whether the session survived was tried first. It does NOT
                            // race - that was assumed and then measured on 2026-09-12, five probes,
                            // "gone" every time - but it fails in the wrong direction: any
                            // container that cannot be asked answers "no session", which reads as
                            // "finished", which removes a task.
                            //
                            // Absent a marker the task is KEPT. Tearing down under a running agent
                            // would stop the gate, the broker and the clearance watcher, which is
                            // precisely the state the contract warns about: a container that is up
                            // with no helpers.
                            try (Teardown teardown =
                                    Teardown.arm(
                                            () -> {
                                                if (!running.runner()
                                                        .sessionEnded(running.container())) {
                                                    out.println();
                                                    out.println(
                                                            "detached  "
                                                                    + running.container()
                                                                    + " is still running");
                                                    out.println(
                                                            "          come back with 'porelo task attach "
                                                                    + running.container()
                                                                    + "'");
                                                    out.println(
                                                            "          stop it with 'porelo task stop "
                                                                    + running.container()
                                                                    + "'");
                                                    out.flush();
                                                    return;
                                                }
                                                outcome.set(
                                                        running.cleanUp()
                                                                .applyAsInt(outcome.get()));
                                            })) {
                                left =
                                        context.exec()
                                                .applyAsInt(
                                                        running.runner()
                                                                .attachCommand(
                                                                        running.container(),
                                                                        shell,
                                                                        startWith,
                                                                        running.project().name()
                                                                                + "/"
                                                                                + task));
                                // A shell exits with its last command's status, which is not a
                                // verdict on the task. Reported from a machine where a typo at the
                                // prompt - 'bash: /exit: No such file or directory' - made leaving
                                // the session print "it failed, so nothing was removed" and keep
                                // the container. Somebody who walks out of a shell has ended the
                                // task normally, whatever they last typed; only a signal means they
                                // did not get to finish, and that is what the starting value is
                                // for.
                                outcome.set(0);
                            }
                            return left == 0 ? outcome.get() : left;
                        }));
    }

    /**
     * Starts the git gate as its own process.
     *
     * <p>Detached, like the clearance watcher and for the same reason: this process either returns
     * or replaces itself with a shell, and the gate has to be there for as long as the container
     * is. The first version of this held the server in {@code task run} and it died the moment the
     * command finished - the container could then commit and never push.
     *
     * <p>The token goes through the environment, not the command line: a command line is visible in
     * the host's process list, and this token authenticates pushes.
     */
    /**
     * Starts the credential proxy for this task, detached, and returns how to reach it.
     *
     * <p>Detached for the same reason as the gate and the clearance watcher: this process either
     * replaces itself with a shell or returns when the agent finishes, and the proxy has to outlive
     * both. It writes a pid file that the poststop hook reaps.
     *
     * <p><strong>The proxy mints the token, not this process.</strong> The real credential then
     * only ever exists in the proxy, which is the property that makes the phantom token worth
     * having. This process waits for the token file to appear and injects the value into the
     * container.
     *
     * @param agent The selected agent, or {@code null}.
     * @param container Container name.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return The plumbing, or {@code null} when this task brokers no credential.
     */
    static @Nullable String upstreamHost(@Nullable String upstream) {
        return TaskRunner.upstreamHost(upstream);
    }

    /**
     * Runs the agent, then renders its output with the agent's own formatter.
     *
     * <p>Both halves come from the agent: Porelo knows neither how to invoke it nor how to read
     * what it produced. That is the whole point of the boundary - a second agent with a different
     * command line and a different output format needs no change here.
     */
    /**
     * Shows what the agent produced, which is the half of an unattended run that needs a person.
     *
     * <p>The agent renders its own log. A second agent with a different format needs no change
     * here, which is the property the reference implementation lost by branching on the agent's
     * name in its log viewer.
     *
     * @param agent The agent that ran, or {@code null} when none did.
     * @param container Container name.
     * @param out Where the output goes.
     * @param err Where a rendering failure is reported.
     */
    private void render(
            org.fuin.porelo.agent.api.@org.jspecify.annotations.Nullable InstalledAgent agent,
            String container,
            PrintWriter out,
            PrintWriter err) {
        render(
                agent,
                context.paths().tasks().containerState(container).resolve("task.log"),
                raw,
                out,
                err);
    }

    /**
     * Shows a task's log as its agent renders it, or as it came.
     *
     * @param agent The agent that ran, or {@code null} when none did.
     * @param log The task's log.
     * @param raw Whether to leave the agent's formatter out.
     * @param out Where the output goes.
     * @param err Where a rendering failure is reported.
     */
    static void render(
            org.fuin.porelo.agent.api.@org.jspecify.annotations.Nullable InstalledAgent agent,
            java.nio.file.Path log,
            boolean raw,
            PrintWriter out,
            PrintWriter err) {
        out.println();
        // The agent's text with its terminal controls written out: anything it read can steer it,
        // and an escape sequence printed raw rewrites what the person reads, or their window's
        // title, or their clipboard.
        final java.util.function.Consumer<String> show =
                line -> out.println(TalkReadCommand.shown(line));
        try {
            if (raw || agent == null) {
                java.nio.file.Files.readAllLines(log).forEach(show);
            } else {
                final int[] shown = {0};
                agent.formatLog(
                        log,
                        line -> {
                            shown[0]++;
                            show.accept(line);
                        });
                if (shown[0] == 0 && java.nio.file.Files.size(log) > 0) {
                    // The formatter suppressed everything - which is right for a run that only
                    // produced setup chatter, and unhelpful when that is all there was. Silence is
                    // the one thing that tells a reader nothing, so fall back to the raw text.
                    out.println("(nothing to render; showing the raw output)");
                    out.println();
                    java.nio.file.Files.readAllLines(log).forEach(show);
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            err.println(
                    "porelo: cannot render the agent output at " + log + ": " + ex.getMessage());
            err.flush();
        }
        out.println();
        out.println("output kept at " + log);
        out.flush();
    }

    /**
     * Brings back every task a restart took down, through the same start a person would type for
     * each.
     *
     * <p>One command naming none of them, because after a restart nobody should have to find the
     * names. Each comes back whole - container and helpers - or is said not to have; there is no
     * third state presented as running. A task that needs the vault is not started into a prompt
     * per task: the vault is unlocked once, first.
     *
     * @param out Where each task's outcome is said.
     * @param err Where a refusal is said.
     * @return 0 when every one came back, 1 when some did not, 69 when the vault has to be unlocked
     *     first.
     */
    private int restarted(final PrintWriter out, final PrintWriter err) {
        if (spec.commandLine().getParseResult().hasMatchedPositional(0)) {
            err.println(
                    "porelo: --restarted brings back every task a restart took down, so it takes no task name");
            err.flush();
            return 64;
        }
        final java.util.List<TaskInventory.Task> tasks = new TaskInventory(context).tasks();
        final java.util.List<TaskInventory.Task> down =
                tasks.stream()
                        .filter(each -> TaskInventory.RESTARTED.equals(each.startDetail()))
                        .toList();
        // A run nobody watches is not started on its own: it stays stopped, and its next start
        // continues it.
        final java.util.List<TaskInventory.Task> interrupted =
                tasks.stream()
                        .filter(each -> TaskInventory.INTERRUPTED.equals(each.startDetail()))
                        .toList();
        for (final TaskInventory.Task each : interrupted) {
            out.println(
                    "stays     "
                            + each.name()
                            + ": its run was interrupted by the restart; its session is kept,"
                            + " and 'porelo task start "
                            + (each.project() == null
                                    ? each.name()
                                    : org.fuin.porelo.runtime.ContainerName.taskIn(
                                                    each.project(), each.name())
                                            + " --project "
                                            + each.project())
                            + " --prompt ...' continues it");
        }
        if (down.isEmpty()) {
            if (interrupted.isEmpty()) {
                out.println("no task is down because the machine restarted");
            }
            out.flush();
            return 0;
        }
        if (down.stream().anyMatch(each -> "NEEDS_VAULT".equals(each.startAction()))) {
            // At a terminal it is opened here, as 'porelo vault unlock' would, and the tasks come
            // back with it.
            context.openIfShut(true, true, err);
        }
        if (down.stream().anyMatch(each -> "NEEDS_VAULT".equals(each.startAction()))
                && context.opener().isEmpty()) {
            err.println(
                    "porelo: the tasks need their tokens from the vault, which is shut; 'porelo vault unlock' once,"
                            + " then this again");
            err.flush();
            return 69;
        }
        int failed = 0;
        for (final TaskInventory.Task each : down) {
            final String project = each.project();
            final String repository = each.repository();
            final String prefix = "porelo-" + project + "-";
            if (project == null || repository == null || !each.name().startsWith(prefix)) {
                out.println(
                        "not back  "
                                + each.name()
                                + ": nothing recorded which project or repository it was");
                failed++;
                continue;
            }
            final java.io.StringWriter said = new java.io.StringWriter();
            // The whole command tree, as the person's own 'task start' would run - built from the
            // root this command was reached through, since the command line itself is in the area
            // above this one.
            final picocli.CommandLine start =
                    new picocli.CommandLine(
                                    spec.root().userObject().getClass(), new PoreloFactory(context))
                            .setParameterExceptionHandler(CliErrors.handler())
                            .setExecutionExceptionHandler(CliErrors.failures(context.paths()));
            start.setOut(new PrintWriter(said));
            start.setErr(new PrintWriter(said));
            final int code =
                    start.execute(
                            "task",
                            "start",
                            each.name().substring(prefix.length()),
                            "-p",
                            project,
                            "-r",
                            repository,
                            "--detach");
            if (code == 0) {
                out.println("back      " + each.name());
            } else {
                failed++;
                out.println(
                        "not back  "
                                + each.name()
                                + ": "
                                + said.toString()
                                        .lines()
                                        .filter(line -> line.startsWith("porelo: "))
                                        .reduce((first, last) -> last)
                                        .map(line -> line.substring("porelo: ".length()))
                                        .orElse("start exited with " + code));
            }
            out.flush();
        }
        return failed == 0 ? 0 : 1;
    }

    /**
     * Asks which of a project's repositories a task without one is for, where a start would refuse
     * and name the option.
     *
     * @param project The project.
     * @param offer Who is asked.
     * @return The one chosen, or {@code null} when nothing was, said with the command.
     */
    static @Nullable String repositoryChosen(
            final org.fuin.porelo.core.project.Project project, final Offer offer) {
        return offer.choose(
                "say which repository this task is for",
                project.workRepositoryNames(),
                "porelo task start --repository <name>");
    }

    /**
     * Returns who is offered a remedy at this start: the terminal's person, or nobody - a script, a
     * pipe or {@code --no-input} - so that the refusal is word for word today's.
     *
     * @param noInput Whether {@code --no-input} was given.
     * @param yes Whether {@code --yes} was given.
     * @param atTerminal Whether a person is at a terminal.
     * @param err Where an offer is said.
     * @return The offer.
     */
    static Offer offer(
            final boolean noInput,
            final boolean yes,
            final boolean atTerminal,
            final PrintWriter err) {
        return noInput || !atTerminal
                ? Offer.NOBODY
                : new Offer(Offer.Asker.terminal(), false, yes, err);
    }

    /**
     * Reads the values handed in, the environment's first so a file and then the command line win
     * over it.
     *
     * @param declared The project's declarations, which name each value's variable.
     * @return Each value with where it came from.
     * @throws ProjectException When a file cannot be read or a value names no variable that is set.
     */
    java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> given(
            final org.fuin.porelo.core.project.Parameters declared) {
        final java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> given =
                new java.util.LinkedHashMap<>();
        if (setEnv) {
            for (final var parameter : declared.declared().values()) {
                final String value =
                        parameter.env() == null
                                ? null
                                : context.environment().apply(parameter.env());
                if (value != null) {
                    given.put(
                            parameter.name(),
                            new org.fuin.porelo.core.project.Parameters.Given(
                                    value, "environment"));
                }
            }
        }
        for (final Path file : setFiles) {
            final Object loaded;
            try {
                loaded =
                        new org.yaml.snakeyaml.Yaml(
                                        new org.yaml.snakeyaml.constructor.SafeConstructor(
                                                new org.yaml.snakeyaml.LoaderOptions()))
                                .load(java.nio.file.Files.readString(file));
            } catch (final java.io.IOException | RuntimeException ex) {
                throw new ProjectException("Cannot read " + file + ": " + ex.getMessage(), ex);
            }
            if (!(loaded instanceof java.util.Map<?, ?> values)) {
                throw new ProjectException(file + " is not a YAML mapping of NAME: VALUE");
            }
            values.forEach(
                    (name, value) ->
                            given.put(
                                    String.valueOf(name),
                                    new org.fuin.porelo.core.project.Parameters.Given(
                                            String.valueOf(value), "file " + file)));
        }
        for (final String set : sets) {
            final int equals = set.indexOf('=');
            if (equals >= 0) {
                given.put(
                        set.substring(0, equals),
                        new org.fuin.porelo.core.project.Parameters.Given(
                                set.substring(equals + 1), "command line"));
                continue;
            }
            final var parameter = declared.declared().get(set);
            if (parameter == null || parameter.env() == null) {
                throw new ProjectException(
                        "--set "
                                + set
                                + " reads a variable from this shell, and the project declares no"
                                + " parameter '"
                                + set
                                + "' with one; say --set "
                                + set
                                + "=VALUE");
            }
            final String value = context.environment().apply(parameter.env());
            if (value == null) {
                throw new ProjectException(
                        "--set "
                                + set
                                + " reads "
                                + parameter.env()
                                + ", which this shell does not set");
            }
            given.put(set, new org.fuin.porelo.core.project.Parameters.Given(value, "environment"));
        }
        return given;
    }

    /**
     * The options that take effect only when a task is created or brought up, so a start on one
     * that is running would drop them without a word. {@code -r} is not among them: a different
     * repository is refused before, and the task's own changes nothing.
     */
    private static final java.util.List<String> AT_START_ONLY =
            java.util.List.of(
                    "--agent",
                    "--provider",
                    "--credential",
                    "--set",
                    "--set-file",
                    "--set-env",
                    "--branch",
                    "--credential-type",
                    "--token-hours",
                    "--shell",
                    "--attach",
                    "--rm",
                    "--prompt",
                    "--model",
                    "--max-turns",
                    "--minutes",
                    "--upstream",
                    "--no-gate",
                    "--clearance",
                    "--raw",
                    "--detach",
                    "--restarted");

    /**
     * Goes into a running task as {@code attach} does, unless an option was given that it would
     * drop; then names each of them and goes in nowhere.
     */
    private int intoRunning(final String container, final PrintWriter out, final PrintWriter err) {
        final picocli.CommandLine.ParseResult parsed = spec.commandLine().getParseResult();
        final java.util.List<String> given =
                AT_START_ONLY.stream().filter(parsed::hasMatchedOption).toList();
        if (!given.isEmpty()) {
            err.println(
                    "porelo: "
                            + container
                            + " is already running; "
                            + String.join(", ", given)
                            + (given.size() == 1 ? " takes" : " take")
                            + " effect only after 'porelo task stop "
                            + container
                            + "'");
            err.flush();
            return 64;
        }
        return TaskAttachCommand.goInto(context, container, out);
    }
}
