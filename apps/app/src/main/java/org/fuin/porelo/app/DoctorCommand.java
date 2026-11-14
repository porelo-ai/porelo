package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.hardening.ProcessHardening;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.shield.DnsmasqProbe;
import org.fuin.porelo.wire.SocketContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Reports what the binary can see of its own environment.
 *
 * <p>Of hardening it says what the running daemon has, since that process holds the vault's
 * secrets; the downcalls themselves are reached by the broker and the ssh agent, which call them
 * before reading a secret, and checked by the build's FFM check.
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Reports paths, the daemon's hardening and whether a task can run here.")
public class DoctorCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    private static final String PRIVATE_FILES = "private files";

    /** Where the running processes are. */
    private static final java.nio.file.Path PROC = java.nio.file.Path.of("/proc");

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @picocli.CommandLine.Option(
            names = "--json",
            description =
                    "Every finding as one JSON object, from the same checks as the text, for a program to read.")
    private boolean json;

    @picocli.CommandLine.Option(
            names = "--color",
            paramLabel = "<when>",
            description =
                    "auto, always or never. Default: ${DEFAULT-VALUE} - colour on a terminal unless NO_COLOR is"
                            + " set.")
    private String color = "auto";

    /** Whether this report is coloured, decided once per run. */
    private boolean colour;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * Returns whether the report is coloured: as the command says, else on a terminal unless {@code
     * NO_COLOR} is set.
     *
     * @param mode {@code auto}, {@code always} or {@code never}.
     * @param terminal Whether standard output is a terminal.
     * @param noColor {@code NO_COLOR}, or {@code null} when it is not set; set at all, even empty,
     *     it means none.
     * @return Whether to colour.
     */
    static boolean colours(
            final String mode,
            final boolean terminal,
            final @org.jspecify.annotations.Nullable String noColor) {
        return switch (mode) {
            case "always" -> true;
            case "never" -> false;
            default -> terminal && noColor == null;
        };
    }

    /**
     * Returns a line's start in its state's colour: a failure red, a warning yellow, what is fine
     * green. Only the colour is added; the words are what a reader without colour reads.
     *
     * @param text What to colour.
     * @param state What the probe found.
     * @param colour Whether this report is coloured.
     * @return The text, coloured or as it was.
     */
    static String painted(final String text, final Probe.State state, final boolean colour) {
        if (!colour) {
            return text;
        }
        final String code =
                switch (state) {
                    case MISSING -> "31";
                    case DEGRADED, UNKNOWN -> "33";
                    case OK -> "32";
                };
        return "\u001b[" + code + "m" + text + "\u001b[0m";
    }

    /**
     * Reports whether the installed dnsmasq can populate the firewall's allow set.
     *
     * <p>Without {@code --nftset} a declared domain resolves and is then dropped: names work,
     * nothing connects, and the cause is invisible. A version number does not answer this - the
     * option is a build-time choice - so the binary is asked what it was compiled with.
     *
     * @return The probe.
     */
    private Probe nftSetSupport() {
        return nftSetSupport(DnsmasqProbe::versionCommand);
    }

    /**
     * Reports whether the dnsmasq the hooks run can populate the firewall's allow set.
     *
     * @param versionCommand The command that asks it, by its fixed path.
     * @return The probe.
     */
    Probe nftSetSupport(final java.util.function.Supplier<java.util.List<String>> versionCommand) {
        final String name = "dnsmasq nftset";
        final String install =
                "install a dnsmasq built with nftset support"
                        + " (Fedora and Debian both ship one)";
        final java.util.List<String> version;
        try {
            version = versionCommand.get();
        } catch (java.io.UncheckedIOException ex) {
            // The hooks run only a root-owned dnsmasq in the fixed directories; judging one from
            // PATH said yes about a program no task would use.
            if (ex.getCause() instanceof org.fuin.porelo.wire.SystemProgram.Missing) {
                return Probe.missing(
                        name,
                        "no dnsmasq in /usr/sbin, /usr/bin, /sbin or /bin, the only places the"
                                + " hooks take it from, so no declared domain resolves",
                        install);
            }
            return Probe.missing(
                    name,
                    "the hooks refuse this dnsmasq, so no declared domain resolves: "
                            + ex.getMessage(),
                    "make dnsmasq root's own and writable by root alone, as its package installs it");
        }
        try {
            final CommandResult result =
                    context.runner().run(org.fuin.porelo.core.process.Command.of(version));
            if (!result.successful()) {
                return Probe.unknown(
                        name,
                        version.getFirst()
                                + " did not run, so declared domains may not be reachable",
                        install);
            }
            return DnsmasqProbe.supportsNftSet(result.standardOutput())
                    ? Probe.ok(name, "yes")
                    : Probe.missing(
                            name,
                            "this dnsmasq cannot open the firewall for declared domains",
                            install);
        } catch (RuntimeException ex) {
            // Found and checked, so installed: it could not be started.
            return Probe.unknown(
                    name,
                    version.getFirst()
                            + " could not be started, so declared domains may not be reachable: "
                            + ex.getMessage(),
                    install);
        }
    }

    /**
     * Reports the transports this machine can carry a message with, and whether each one answers.
     *
     * <p>Installed rather than built in, so the honest answer on a machine with none is that
     * messages go nowhere - not an error, and not silence either. An adapter that is there and does
     * not answer {@code describe} is worse than one that is absent, because a peer configured
     * against it looks configured.
     *
     * @return The probe.
     */
    private Probe transports() {
        final String name = "transports";
        final java.util.Map<String, java.nio.file.Path> found =
                context.paths().messaging().transportDirectory().byName();
        if (found.isEmpty()) {
            return Probe.degraded(
                    name,
                    "none installed, so a message reaches no peer",
                    "install a transport package");
        }
        final java.util.List<String> answers = new java.util.ArrayList<>();
        final java.util.List<String> silent = new java.util.ArrayList<>();
        final java.util.List<String> exposing = new java.util.ArrayList<>();
        found.forEach(
                (transport, executable) -> {
                    try {
                        final CommandResult result =
                                context.runner()
                                        .run(
                                                // As the message area asks it: nothing of the
                                                // caller's that steers a helper.
                                                HelperEnvironment.chosen(
                                                        org.fuin.porelo.core.process.Command.of(
                                                                executable.toString(),
                                                                "describe")));
                        if (result.successful()) {
                            answers.add(transport);
                            if (exposesSecrets(result.standardOutput())) {
                                exposing.add(transport);
                            }
                        } else {
                            silent.add(transport);
                        }
                    } catch (final RuntimeException ex) {
                        silent.add(transport);
                    }
                });
        // An accepted risk until every published transport takes them on its input, so said, not
        // degraded.
        final String exposed =
                exposing.stream()
                        .map(
                                transport ->
                                        "; "
                                                + transport
                                                + " takes its secrets in its environment, where"
                                                + " another process of the account can read them:"
                                                + " update it")
                        .collect(java.util.stream.Collectors.joining());
        if (silent.isEmpty()) {
            return Probe.ok(name, String.join(", ", answers) + exposed);
        }
        return Probe.degraded(
                name,
                String.join(", ", silent)
                        + " did not answer 'describe'"
                        + (answers.isEmpty() ? "" : "; " + String.join(", ", answers) + " did")
                        + exposed,
                "run the adapter by hand to see why");
    }

    /**
     * Reports whether this machine can carry its projects' messages: the transport each project's
     * conversation names is installed and answers {@code describe}, and a server it runs on this
     * machine, where its setup said the conversation reaches loopback alone, answers there.
     *
     * <p>Beside {@code transports}, which asks every installed adapter: this asks what the projects
     * need. A machine whose projects name no transport needs none, which is fine. A server
     * elsewhere is not asked, so this line never reaches beyond the machine.
     *
     * @param context The machine.
     * @param answers Whether something listens at a {@code host:port} a conversation reaches.
     * @return The probe.
     */
    static Probe messages(
            final PoreloContext context, final java.util.function.Predicate<String> answers) {
        final String name = "messages";
        final java.util.List<ProjectInventory.Summary> projects;
        try {
            projects = new ProjectInventory(context).projects();
        } catch (final RuntimeException ex) {
            return Probe.unknown(
                    name, "the projects cannot be listed: " + ex.getMessage(), "porelo projects");
        }
        final TransportDirectory directory = context.paths().messaging().transportDirectory();
        final java.util.Map<String, Boolean> describes = new java.util.HashMap<>();
        final java.util.List<String> carried = new java.util.ArrayList<>();
        final java.util.List<String> problems = new java.util.ArrayList<>();
        final java.util.List<String> unread = new java.util.ArrayList<>();
        String action = null;
        java.util.Set<String> running;
        try {
            running = runningProjects(context);
        } catch (final RuntimeException ex) {
            // Unsure what runs, nothing is taken for orphaned: each project is still asked about.
            running = null;
        }
        for (final ProjectInventory.Summary summary : projects) {
            // What nothing owns any more has its own line under projects.
            if (running != null && Prune.orphaned(summary, running)) {
                continue;
            }
            final String file = summary.file();
            // A task of 'default' talks to nobody, whatever its file says.
            if (file == null || DefaultProject.is(summary.name())) {
                continue;
            }
            final org.fuin.porelo.core.project.Project project;
            try {
                project =
                        org.fuin.porelo.core.project.ProjectReader.read(
                                java.nio.file.Path.of(file));
            } catch (final RuntimeException ex) {
                unread.add(summary.name());
                continue;
            }
            final java.util.Map<String, Object> row = Conversations.row(context, project);
            if (row == null) {
                continue;
            }
            final String scheme = String.valueOf(row.get("transport"));
            final java.nio.file.Path adapter = directory.find(scheme);
            if (adapter == null) {
                problems.add(project.name() + " names " + scheme + ", which is not installed here");
                action = action != null ? action : "install the transport " + scheme;
                continue;
            }
            if (!describes.computeIfAbsent(
                    scheme,
                    any ->
                            TransportDescription.of(context.runner(), adapter)
                                    != TransportDescription.UNKNOWN)) {
                problems.add(project.name() + "'s " + scheme + " did not answer 'describe'");
                action =
                        action != null ? action : "run " + adapter + " describe by hand to see why";
                continue;
            }
            final java.util.List<String> reaches =
                    row.get("reaches") instanceof java.util.List<?> listed
                            ? listed.stream().map(String::valueOf).toList()
                            : java.util.List.of();
            // Loopback alone is a server of this account's own; one elsewhere is not asked.
            final java.util.List<String> silent =
                    !reaches.isEmpty() && reaches.stream().allMatch(TaskConversations::loopback)
                            ? reaches.stream().filter(reach -> !answers.test(reach)).toList()
                            : java.util.List.of();
            if (!silent.isEmpty()) {
                problems.add(
                        project.name()
                                + "'s "
                                + scheme
                                + " server on this machine does not answer at "
                                + String.join(", ", silent));
                action =
                        action != null
                                ? action
                                : "start a task of "
                                        + project.name()
                                        + " ('porelo task start --project "
                                        + project.name()
                                        + "'): its start sets the conversation up, which starts"
                                        + " the server";
                continue;
            }
            carried.add(project.name() + " by " + scheme);
        }
        final String cannotTell =
                "cannot tell for " + String.join(", ", unread) + ": its file does not read";
        if (action != null) {
            if (!unread.isEmpty()) {
                problems.add(cannotTell);
            }
            return Probe.degraded(name, String.join("; ", problems), action);
        }
        if (!unread.isEmpty()) {
            return Probe.unknown(name, cannotTell, "porelo projects");
        }
        if (carried.isEmpty()) {
            return Probe.ok(name, "no project names a transport, so this machine needs none");
        }
        return Probe.ok(name, String.join(", ", carried));
    }

    /**
     * Says whether something accepts a connection at a {@code host:port} on this machine, within a
     * second.
     *
     * @param reach Where, as a transport's {@code reaches} names it.
     * @return Whether it answered.
     */
    static boolean answersAt(final String reach) {
        final int colon = reach.lastIndexOf(':');
        final String host = TaskConversations.loopbackHost(reach);
        if (colon < 0 || host == null) {
            return false;
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                    new java.net.InetSocketAddress(
                            "localhost".equals(host) ? "127.0.0.1" : host,
                            Integer.parseInt(reach.substring(colon + 1))),
                    1000);
            return true;
        } catch (final java.io.IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Whether a transport's {@code describe} says it keeps a conversation - only such a transport
     * is handed secrets - and does not say it takes them on its input.
     */
    private static boolean exposesSecrets(final String described) {
        try {
            return org.fuin.porelo.wire.Json.parse(described) instanceof java.util.Map<?, ?> said
                    && said.get("lifecycle") instanceof java.util.List<?> verbs
                    && !verbs.isEmpty()
                    && !TransportInput.ON_INPUT.equals(said.get("secrets"));
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /**
     * Reports whether a message filter is installed.
     *
     * <p>Its own line rather than a footnote to {@code transports}, because the two failures are
     * not the same: with no transport a message reaches no peer, and with no filter <strong>nothing
     * is sent at all</strong> - that is what fail closed means here. A machine with every transport
     * in the world and no filter is silent, and an operator reading a list of green lines should
     * not have to work that out.
     *
     * @return The probe.
     */
    private Probe messageFilter() {
        final String name = "message filter";
        final java.nio.file.Path filter = context.paths().messaging().messageFilter();
        if (filter == null) {
            return Probe.degraded(
                    name,
                    "none installed, so nothing a task writes ever leaves this machine",
                    "install " + PoreloPaths.MESSAGE_FILTER);
        }
        return Probe.ok(name, filter.toString());
    }

    /**
     * Reports the projects on this machine and how many repositories each has.
     *
     * <p><strong>The count is the point.</strong> A project is a unit of work over one or more
     * repositories, and every report that named a project without saying how many it has would read
     * as though a project were one - which is exactly the assumption this machine no longer makes.
     * A project whose file cannot be read says so rather than being counted as one.
     *
     * @return The probe.
     */
    private Probe projects() {
        final String name = "projects";
        final java.util.List<ProjectInventory.Summary> projects;
        try {
            projects = new ProjectInventory(context).projects();
        } catch (final RuntimeException ex) {
            return Probe.degraded(name, "cannot be listed: " + ex.getMessage(), "porelo projects");
        }
        // What nothing owns any more is not listed here as a project with an unreadable file: it
        // has its own line.
        final java.util.Set<String> running = runningProjects(context);
        final java.util.List<ProjectInventory.Summary> owned =
                projects.stream().filter(project -> !Prune.orphaned(project, running)).toList();
        if (owned.isEmpty()) {
            return Probe.ok(name, "none yet - one appears the first time you follow one");
        }
        final java.util.List<String> said = new java.util.ArrayList<>();
        for (final ProjectInventory.Summary project : owned) {
            if (DefaultProject.is(project.name()) && project.repositories().isEmpty()) {
                said.add(project.name() + " (no repository yet)");
                continue;
            }
            said.add(project.name() + " " + repositories(project));
        }
        return Probe.ok(name, String.join(", ", said));
    }

    /** The projects a task runs for: one of them is not orphaned, whatever its file says. */
    private static java.util.Set<String> runningProjects(final PoreloContext context) {
        final java.util.Set<String> running = new java.util.HashSet<>();
        new TaskInventory(context)
                .tasks().stream()
                        .filter(TaskInventory.Task::running)
                        .map(TaskInventory.Task::project)
                        .filter(java.util.Objects::nonNull)
                        .forEach(running::add);
        return running;
    }

    /** What a project holds, as this probe says it. */
    static String repositories(final ProjectInventory.Summary project) {
        if (project.carried()) {
            return "(on this host, not started here yet)";
        }
        // Not "1". An unreadable file is not a project with one repository, and saying so would
        // be the very thing this line exists to stop.
        return project.repositories().isEmpty()
                ? "(file unreadable)"
                : project.repositories().size()
                        + " repositor"
                        + (project.repositories().size() == 1 ? "y" : "ies");
    }

    /**
     * Reports what anyone but this account can read of what Porelo left behind.
     *
     * <p>Asked of this command alone, as a walk of every mirror is no question for a daemon or a
     * setup to ask each time. It degrades and does not make the machine not ready: one that ran
     * under an older Porelo can still run tasks. The command's exit says it all the same.
     *
     * @param looseFiles Where to look.
     * @return The probe.
     */
    static Probe privateFiles(final LooseFiles looseFiles) {
        final String name = PRIVATE_FILES;
        final java.util.List<java.nio.file.Path> loose;
        try {
            loose = looseFiles.found();
        } catch (final java.io.IOException ex) {
            return Probe.unknown(
                    name, "cannot be looked through: " + ex.getMessage(), "porelo doctor again");
        }
        if (loose.isEmpty()) {
            return Probe.ok(name, "this account's alone");
        }
        return Probe.degraded(
                name,
                loose.size()
                        + " readable by others, at or below: "
                        + String.join(
                                ", ",
                                LooseFiles.topmost(loose).stream()
                                        .map(java.nio.file.Path::toString)
                                        .toList()),
                "porelo tighten");
    }

    private Probe leftovers() {
        final String name = "leftovers";
        final Prune.Result found;
        try {
            found = new Prune(context).run(false, false);
        } catch (final RuntimeException ex) {
            return Probe.unknown(name, "cannot be looked for: " + ex.getMessage(), "porelo prune");
        }
        if (!found.found()) {
            return Probe.ok(name, "none: everything here belongs to a project or a task");
        }
        final java.util.List<String> projects =
                java.util.stream.Stream.concat(found.removes().stream(), found.keeps().stream())
                        .filter(item -> "PROJECT".equals(item.kind()))
                        .map(Prune.Item::name)
                        .toList();
        return Probe.degraded(
                name,
                (found.removes().size() + found.keeps().size())
                        + " left over from earlier"
                        + (projects.isEmpty()
                                ? ""
                                : ", among them the projects " + String.join(", ", projects))
                        + (found.keeps().isEmpty()
                                ? ""
                                : "; " + found.keeps().size() + " of them hold work"),
                "porelo prune shows them; --yes removes them");
    }

    /**
     * Reports a binary a task needs and the version it answers with.
     *
     * <p>By name, because each of these fails somewhere else entirely: without {@code nft} a
     * container comes up with no ruleset, and without {@code git} the gate has no mirror to serve.
     *
     * @param name What to report it as.
     * @param program The binary.
     * @param consequence What breaks without it, for the line that says so.
     * @return The probe.
     */
    private Probe binary(String name, String program, String consequence) {
        try {
            final CommandResult result =
                    context.runner()
                            .run(org.fuin.porelo.core.process.Command.of(program, "--version"));
            if (!result.successful()) {
                return Probe.missing(
                        name,
                        program
                                + " is installed but did not run: "
                                + result.standardError().strip(),
                        "check the installation of " + program);
            }
            return Probe.ok(
                    name,
                    result.trimmedOutput()
                            .lines()
                            .findFirst()
                            .orElse("unknown")
                            .replace(program + " ", "")
                            .replace("version ", ""));
        } catch (RuntimeException ex) {
            return Probe.missing(name, "not installed, so " + consequence, "install " + program);
        }
    }

    /**
     * Reports how podman connects a rootless container, which decides where the git gate can bind.
     *
     * <p>Only pasta can map the host's loopback into the container. Under anything else the gate
     * binds every interface and sits on the operator's network with nothing but its per-task token
     * in front of it - which works, and is worth knowing.
     *
     * @return The probe.
     */
    private Probe rootlessNetwork() {
        final String name = "rootless network";
        final java.util.Optional<String> backend = context.podman().rootlessNetworkCmd();
        if (backend.isEmpty()) {
            return Probe.unknown(
                    name,
                    "podman did not say which backend it uses",
                    "run 'podman info --format {{.Host.RootlessNetworkCmd}}' and check podman is"
                            + " working");
        }
        if (org.fuin.porelo.runtime.LoopbackMapping.PASTA.equals(backend.get())) {
            return Probe.ok(name, backend.get());
        }
        return Probe.degraded(
                name,
                backend.get()
                        + " cannot map the host's loopback, so the git"
                        + " gate binds every interface and is reachable from this machine's network",
                "install passt and let podman use pasta, or accept that the per-task token is what"
                        + " keeps the gate shut");
    }

    /**
     * Reports whether a passphrase can be cached for the session.
     *
     * @return The probe.
     */
    private static Probe keyring() {
        final String name = "keyring";
        if (org.fuin.porelo.vault.KernelKeyring.available()) {
            return Probe.ok(name, "available");
        }
        // The library missing has a remedy here; a kernel that refuses it, as a container's seccomp
        // profile does, has none on this side, and naming a package to install would mislead.
        final String why = org.fuin.porelo.vault.KernelKeyring.unavailable();
        return Probe.degraded(
                name,
                why + ", so no passphrase can be cached and every vault command asks again",
                why.startsWith("libkeyutils")
                        ? "install keyutils"
                        : "nothing to install: type the passphrase when asked, or run where the kernel"
                                + " offers the keyring");
    }

    /**
     * Reports whether this account has a vault, as a state of its own.
     *
     * <p>A machine just prepared has none, and an interface asked to infer that from an empty
     * credential list cannot tell it from a vault that is shut or holds nothing yet. Degraded
     * rather than missing: a task that needs no credential runs without one.
     *
     * @return The probe.
     */
    private Probe vault() {
        final String name = "vault";
        return context.vault().exists()
                ? Probe.ok(name, "there")
                : Probe.degraded(
                        name,
                        "there is no vault here yet, so no credential can be stored and no device"
                                + " enrolled",
                        "run 'porelo setup' at a terminal (over ssh: 'ssh -t'), which creates it");
    }

    /**
     * Reports whether podman will actually run Porelo's hooks.
     *
     * <p>The hooks are what load the firewall. Without them a container starts with no egress
     * policy at all and looks entirely normal, so this is the one line here that decides the exit
     * code.
     *
     * @return A line describing the state.
     */
    private Probe hookRegistration() {
        final String name = "hooks registered";
        return switch (context.hooks().registration()) {
            case ACTIVE -> Probe.ok(name, "registered");
            case MISSING ->
                    Probe.missing(
                            name, "a task would run with no firewall at all", "run 'porelo setup'");
            case DANGLING ->
                    Probe.missing(
                            name,
                            "the descriptors name hook binaries that are not installed",
                            "run 'porelo setup' again");
            case SHADOWED ->
                    Probe.missing(
                            name,
                            "another containers.conf.d drop-in sorts after Porelo's and points hooks_dir at "
                                    + context.hooks().effectiveHooksDirectories(),
                            "remove that drop-in or make it sort before Porelo's");
            // What a package upgrade leaves behind: the binaries were replaced, these files were
            // not, and nothing runs 'porelo setup' for the operator.
            case STALE ->
                    Probe.missing(
                            name,
                            "the installed hook files are from a different version of Porelo: "
                                    + context.hooks().outdated(),
                            "run 'porelo setup' again");
        };
    }

    /**
     * Reports whether a task container will be able to reach Porelo's sockets.
     *
     * <p>Without the policy the container's connection is refused and the agent reports an
     * authentication failure, so the cause is asked about here rather than left to be guessed.
     *
     * @return A line describing the state.
     */
    private Probe socketPolicy() {
        final String name = "selinux policy";
        if (!SocketContext.selinuxPresent()) {
            return Probe.ok(name, "not needed - this machine does not run SELinux");
        }
        if (SocketContext.available()) {
            // Loaded is not enough: a module from before an upgrade stays loaded, and the one
            // before 1.1 let no sshd domain connect, so every socket forward was reset while this
            // line said "installed" (2026-10-18). What the loaded one grants is asked instead.
            final java.util.List<String> refused;
            try {
                refused = SocketContext.refused();
            } catch (java.io.IOException ex) {
                return Probe.ok(
                        name, "installed; what it grants could not be asked: " + ex.getMessage());
            }
            if (refused.isEmpty()) {
                return Probe.ok(name, "installed");
            }
            return Probe.missing(
                    name,
                    "the loaded module is older than this package's: " + String.join("; ", refused),
                    context.paths().selinuxInstaller());
        }
        return Probe.missing(
                name, "a task cannot reach the vault proxy", context.paths().selinuxInstaller());
    }

    /**
     * Reports whether a task is held to Porelo's own sockets on a machine that runs AppArmor.
     *
     * @return A line describing the state.
     */
    private Probe appArmorProfile() {
        final org.fuin.porelo.wire.AppArmorProfile profile =
                org.fuin.porelo.wire.AppArmorProfile.real();
        return appArmor(profile.state(), profile.label());
    }

    /**
     * Says what an AppArmor state means for a task.
     *
     * <p>Only {@code installed} puts a task into its profile. Everywhere else a task runs as it did
     * before, so no answer here makes the machine unready: the line says where the lock that keeps
     * a stand-in from posing as the broker does not hold, and what would make it hold.
     *
     * @param state What {@code AppArmorProfile} found.
     * @param label The process's complete label, or empty where it could not be read.
     * @return The probe.
     */
    static Probe appArmor(
            final org.fuin.porelo.wire.AppArmorProfile.State state, final String label) {
        final String name = "apparmor profile";
        return switch (state) {
            case NOT_NEEDED -> Probe.ok(name, "not needed - this machine does not run AppArmor");
            case MISSING ->
                    Probe.degraded(
                            name,
                            org.fuin.porelo.wire.AppArmorProfile.TASK
                                    + " is not loaded: a task connects to a socket any process of"
                                    + " this account bound",
                            org.fuin.porelo.wire.AppArmorProfile.INSTALL);
            case ABSENT_ON_KERNEL ->
                    Probe.degraded(
                            name,
                            "absent on this kernel - it does not mediate unix sockets, so no"
                                    + " profile holds a task to Porelo's sockets",
                            "nothing to load: a kernel that mediates unix sockets holds it, as"
                                    + " Ubuntu 26.04's does");
            case OUTSIDE ->
                    Probe.degraded(
                            name,
                            "loaded, but this binary is not in plain porelo ("
                                    + (label.isEmpty()
                                            ? "process label could not be read"
                                            : "unconfined".equals(label)
                                                    ? "no profile: unconfined"
                                                    : "process label: " + label)
                                    + "); the lock is not held here; an account install or"
                                    + " developer's build cannot acquire plain porelo by name"
                                    + " under enforced stacking; tasks run without porelo-task",
                            "use the machine-wide package whose binaries wear plain porelo by"
                                    + " path attachment to hold the lock");
            case INSTALLED ->
                    Probe.ok(
                            name,
                            "installed - this process wears plain porelo; the lock holds here"
                                    + "; machine-wide installs attach this label by path");
        };
    }

    /**
     * Returns the directories agents are scanned in, in order.
     *
     * @return Locations.
     */
    private java.util.List<java.nio.file.Path> agentLocations() {
        return context.paths().agents().agentDirectory().locations();
    }

    /**
     * Reports binaries that are installed and never run, naming what hides each one.
     *
     * <p>Both halves are the finding. "This copy is unused" sends an operator looking for the one
     * that is used; found on a real machine as yesterday's build in the data directory, silently
     * winning over the packaged one. Nothing is printed when nothing is shadowed - a line that is
     * always there is a line nobody reads.
     *
     * @param out Where to write.
     */
    private void printShadowed(PrintWriter out) {
        final java.util.List<String[]> notes = shadowed();
        if (notes.isEmpty()) {
            return;
        }
        out.println();
        for (final String[] note : notes) {
            out.println(painted(note[0], Probe.State.DEGRADED, colour) + " " + note[1]);
            out.println("         " + note[2]);
        }
    }

    /**
     * Returns what is installed and not used: each as its kind ({@code not used}, {@code not
     * taken}), the file, and why. The text and the JSON say the same list.
     *
     * @return The notes, possibly none.
     */
    private java.util.List<String[]> shadowed() {

        final java.util.List<java.nio.file.Path[]> hidden = new java.util.ArrayList<>();
        final java.nio.file.Path hooks = context.paths().shadowedHookBinaries();
        if (hooks != null) {
            hidden.add(new java.nio.file.Path[] {hooks, context.paths().binaryDirectory()});
        }
        final org.fuin.porelo.agent.api.AgentDirectory agents =
                context.paths().agents().agentDirectory();
        final java.util.List<java.nio.file.Path> used = agents.executables();
        for (final java.nio.file.Path shadowed : agents.shadowed()) {
            used.stream()
                    .filter(winner -> winner.getFileName().equals(shadowed.getFileName()))
                    .findFirst()
                    .ifPresent(winner -> hidden.add(new java.nio.file.Path[] {shadowed, winner}));
        }
        agents.hiddenDescriptions()
                .forEach((loser, winner) -> hidden.add(new java.nio.file.Path[] {loser, winner}));
        final java.util.List<org.fuin.porelo.agent.api.AgentDirectory.Description> refused =
                agents.descriptions().stream()
                        .filter(description -> description.refusal() != null)
                        .toList();
        final java.util.List<String[]> notes = new java.util.ArrayList<>();
        for (final java.nio.file.Path[] pair : hidden) {
            notes.add(new String[] {"not used", pair[0].toString(), "hidden by " + pair[1]});
        }
        for (final org.fuin.porelo.agent.api.AgentDirectory.Description description : refused) {
            notes.add(
                    new String[] {
                        "not taken",
                        description.file().toString(),
                        String.valueOf(description.refusal())
                    });
        }
        return notes;
    }

    /**
     * Reports the container runtime's version, and whether Porelo will run on it.
     *
     * <p>podman 4 is refused: it has no pasta, so the host's loopback cannot be mapped into a
     * container and the git gate would bind every interface. Reported here as well as at task
     * start, because this is the command an operator runs to find out what their machine can do.
     *
     * @return The version, with what is wrong with it when something is.
     */
    private Probe podmanVersion() {
        final String name = "podman";
        final java.util.Optional<String> tooOld = context.podman().unsupportedVersion();
        return tooOld.isPresent()
                ? Probe.missing(
                        name,
                        tooOld.get(),
                        "run 'podman version' as this user and fix what it reports; Porelo needs podman 5"
                                + " or newer")
                : Probe.ok(name, context.podman().version());
    }

    /**
     * Reports the OpenSSH version, and whether Porelo will run on it.
     *
     * <p>Below the floor a task start refuses, so this is missing; one that cannot be read refuses
     * nothing, so this is unknown and says so.
     *
     * @return The version, with what is wrong with it when something is.
     */
    private Probe openSsh() {
        final String name = OpenSshFloor.NAME;
        return switch (OpenSshFloor.ask(context.runner())) {
            case OpenSshFloor.Enough enough -> Probe.ok(name, enough.version());
            case OpenSshFloor.TooOld old ->
                    Probe.missing(
                            name,
                            old.refusal(),
                            "install OpenSSH " + OpenSshFloor.MINIMUM + " or newer");
            case OpenSshFloor.Unread unread ->
                    Probe.unknown(name, unread.sentence(), "run 'ssh -V' to see what it says here");
        };
    }

    /**
     * Returns what this machine has of everything a task depends on.
     *
     * <p>Exists so the daemon can answer the same question without building a command-line object
     * to ask it. Both go through this, so a machine cannot be reported ready by one and unready by
     * the other - which is the drift that makes a diagnostic worth less than no diagnostic.
     *
     * @param context The machine to probe.
     * @return The probes, in the order they are reported.
     */
    public static java.util.List<Probe> probesFor(PoreloContext context) {
        final DoctorCommand command = new DoctorCommand();
        command.setContext(context);
        return command.probes();
    }

    /**
     * Says whether this machine can run a task.
     *
     * <p>Anything missing means a task will fail, or run without something it needs and say nothing
     * - hooks that never load a firewall being the worst of them. Degraded and unknown leave a
     * machine ready: it does run tasks, and the report says how well.
     *
     * <p>One rule, used by the exit code and by the daemon's answer. Two summaries of one machine
     * that can disagree are worse than one, because whichever a person saw last is the one they act
     * on.
     *
     * @param probes What was found.
     * @return Whether work can start here.
     */
    public static boolean ready(java.util.List<Probe> probes) {
        return probes.stream().noneMatch(probe -> probe.state() == Probe.State.MISSING);
    }

    /**
     * Reports whether the daemon runs, and whether it is this command's version.
     *
     * <p>An update replaces the binaries and starts nothing, so a daemon that missed its own
     * restart went on as the old version with nothing saying so. Not running is not a failure:
     * nothing on the command line needs it.
     *
     * @param socket Where the daemon listens.
     * @param own This command's version.
     * @param versionOf Asks the daemon at a socket for its version; throws when it cannot.
     * @return The probe.
     */
    static Probe daemon(
            java.nio.file.Path socket,
            String own,
            java.util.function.Function<java.nio.file.Path, String> versionOf) {
        final String name = "daemon";
        if (!java.nio.file.Files.exists(socket)) {
            return Probe.degraded(
                    name,
                    "not running - nothing on the command line needs it; an interface does",
                    "systemctl --user start porelod");
        }
        final String running;
        try {
            running = versionOf.apply(socket);
        } catch (RuntimeException ex) {
            return Probe.unknown(
                    name,
                    "does not answer at " + socket + ": " + ex.getMessage(),
                    "systemctl --user restart porelod");
        }
        if (!running.equals(own)) {
            return Probe.degraded(
                    name,
                    "runs "
                            + running
                            + ", this command is "
                            + own
                            + " - an update was installed and the daemon was not restarted on it",
                    "systemctl --user daemon-reload && systemctl --user restart porelod");
        }
        return Probe.ok(name, "running, " + running);
    }

    /**
     * Asks the daemon at a socket for its version, within a few seconds.
     *
     * @param socket Where it listens.
     * @return Its version.
     */
    static String versionAt(java.nio.file.Path socket) {
        // The connect bounded too: a daemon whose backlog is full holds it until somebody accepts.
        try (org.fuin.porelo.wire.varlink.VarlinkClient client =
                new org.fuin.porelo.wire.varlink.VarlinkClient(
                        socket, java.time.Duration.ofSeconds(5))) {
            return String.valueOf(
                    client.call(
                                    "org.varlink.service.GetInfo",
                                    java.util.Map.of(),
                                    java.time.Duration.ofSeconds(5))
                            .get("version"));
        } catch (org.fuin.porelo.wire.varlink.VarlinkTimeoutException ex) {
            throw new IllegalStateException("no answer within 5 seconds", ex);
        } catch (org.fuin.porelo.wire.varlink.VarlinkException | java.io.IOException ex) {
            throw new IllegalStateException(String.valueOf(ex.getMessage()), ex);
        }
    }

    /**
     * Every external thing a task depends on, in the order an operator would work through them.
     *
     * <p>Package-private so the list itself can be checked - that each failure carries an action,
     * and that nothing was dropped - without reading the printed page.
     *
     * @return The probes.
     */
    java.util.List<Probe> probes() {
        return machine();
    }

    /**
     * What this command reports: the machine, and what {@code --keepassxc} needs. Not asked of a
     * daemon or a setup, which it says nothing to.
     *
     * @return The probes.
     */
    java.util.List<Probe> reported() {
        final java.util.List<Probe> all = new java.util.ArrayList<>(machine());
        all.add(privateFiles(new LooseFiles(context.paths().xdg())));
        all.addAll(keepassxc());
        return java.util.List.copyOf(all);
    }

    private java.util.List<Probe> keepassxc() {
        return within(
                () ->
                        KeePassXcNeeds.read(context, "keepassxc-cli", KeePassXcNeeds::runs).stream()
                                .map(KeePassXcNeeds.Need::probe)
                                .toList(),
                java.time.Duration.ofSeconds(10));
    }

    /**
     * Asks on a thread of its own, for a while only: an agent can hold a question for ever.
     *
     * @param question What to ask.
     * @param limit How long it may take.
     * @return Its answer, or a line that says none came.
     */
    static java.util.List<Probe> within(
            final java.util.concurrent.Callable<java.util.List<Probe>> question,
            final java.time.Duration limit) {
        final java.util.concurrent.ExecutorService asking =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        task -> {
                            final Thread thread = new Thread(task, "porelo-doctor-keepassxc");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            return asking.submit(question)
                    .get(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException
                | java.util.concurrent.ExecutionException ex) {
            return java.util.List.of(
                    Probe.unknown(
                            "keepassxc",
                            "KeePassXC or the agent did not answer within 10 seconds",
                            "see that your ssh-agent is not waiting for you"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return java.util.List.of();
        } finally {
            asking.shutdownNow();
        }
    }

    private java.util.List<Probe> machine() {
        return java.util.List.of(
                podmanVersion(),
                hookRegistration(),
                rootlessNetwork(),
                nftSetSupport(),
                binary("nft", "nft", "a container comes up with no firewall ruleset"),
                binary("git", "git", "the gate has no mirror to serve and no push can be reviewed"),
                openSsh(),
                transports(),
                messages(context, DoctorCommand::answersAt),
                messageFilter(),
                projects(),
                leftovers(),
                binary(
                        "nsenter",
                        "nsenter",
                        "nothing can enter a container's network namespace,"
                                + " so a clearance decision cannot be applied to a running task"),
                keyring(),
                vault(),
                socketPolicy(),
                appArmorProfile(),
                daemon(
                        context.paths().daemonSocket(),
                        PoreloVersion.version(),
                        DoctorCommand::versionAt));
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        if (json) {
            return json(out);
        }
        // A terminal only: piped or written to a file, the report carries no escape codes.
        final java.io.Console console = System.console();
        colour = colours(color, console != null && console.isTerminal(), System.getenv("NO_COLOR"));
        final XdgPaths paths = context.paths().xdg();
        out.println("config   " + paths.config());
        out.println("data     " + paths.data());
        out.println("state    " + paths.state());
        // Only when there is something in it. A line naming an empty log on every healthy machine
        // teaches people to skip the line, and then it says nothing on the day it matters.
        if (java.nio.file.Files.isRegularFile(context.paths().failureLog())) {
            out.println(
                    painted("failures", Probe.State.DEGRADED, colour)
                            + " "
                            + context.paths().failureLog()
                            + " - a command failed unexpectedly");
        }
        out.println("runtime  " + paths.runtime());

        out.println();
        out.println("hooks    " + context.paths().binaryDirectory());
        for (final java.nio.file.Path location : agentLocations()) {
            out.println("agents   " + location);
        }
        printShadowed(out);

        out.println();
        final java.util.List<Probe> probes = reported();
        for (final Probe probe : probes) {
            out.printf(
                    "%s %s%s%n",
                    painted(String.format("%-19s", probe.name()), probe.state(), colour),
                    probe.healthy()
                            ? ""
                            : painted(probe.state().name(), probe.state(), colour) + " - ",
                    probe.detail());
            if (!probe.healthy()) {
                // Indented under the line it belongs to: an operator reading this is looking for
                // the one thing to do, and a list of findings without them is a list of worries.
                out.println("                    -> " + probe.action());
            }
        }

        out.println();
        // The daemon holds the vault's secrets; this process holds none, and nothing hardens it.
        out.println(
                "daemon dumpable     "
                        + DaemonHardening.read(context.runner(), PROC, SetupCommand.DAEMON).said());
        out.println(
                "hardening covers    "
                        + (ProcessHardening.appliesToWholeProcess()
                                ? "the whole process"
                                : "this thread only"));

        out.flush();

        return exit(probes);
    }

    /**
     * Returns the exit code for a report, the same for the text and the JSON.
     *
     * @param probes The findings.
     * @return 69 when anything is missing, 1 when hardening covers one thread only or a file is
     *     readable by others, 0 otherwise.
     */
    private static int exit(final java.util.List<Probe> probes) {
        return exit(probes, ProcessHardening.appliesToWholeProcess());
    }

    /**
     * Says the exit code.
     *
     * @param probes The findings.
     * @param wholeProcess Whether hardening covers the whole process.
     * @return The code.
     */
    static int exit(final java.util.List<Probe> probes, final boolean wholeProcess) {
        // Anything missing means a task will fail, or run without something it needs and say
        // nothing - hooks that never load a firewall being the worst of them. Degraded and unknown
        // do not fail the command: the machine works, and the report says how well.
        if (!ready(probes)) {
            return 69;
        }

        // On a JVM this is a warning, in the shipped binary it must never appear.
        // A machine that can run tasks, and whose files others can read: not "not ready", which the
        // daemon says too, and not fine.
        final boolean loose =
                probes.stream()
                        .anyMatch(
                                probe ->
                                        PRIVATE_FILES.equals(probe.name())
                                                && probe.state() == Probe.State.DEGRADED);
        return wholeProcess && !loose ? 0 : 1;
    }

    /**
     * Writes the report as one JSON object: the same probes in the same order as the text, the
     * notes beneath them, the paths and the process's hardening. No colour, whatever {@code
     * --color} says: a program reads it.
     *
     * @param out Where to write.
     * @return The exit code, the text's.
     */
    private int json(final PrintWriter out) {
        final XdgPaths paths = context.paths().xdg();
        final java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("version", PoreloVersion.version());
        final java.util.List<Probe> probes = reported();
        report.put(
                "findings",
                probes.stream()
                        .map(
                                probe -> {
                                    final java.util.Map<String, Object> finding =
                                            new java.util.LinkedHashMap<>();
                                    finding.put("check", probe.name());
                                    finding.put("state", probe.state().name());
                                    finding.put("detail", probe.detail());
                                    finding.put("remedy", probe.healthy() ? null : probe.action());
                                    return (Object) finding;
                                })
                        .toList());
        final java.util.List<Object> notes = new java.util.ArrayList<>();
        if (java.nio.file.Files.isRegularFile(context.paths().failureLog())) {
            notes.add(
                    java.util.Map.of(
                            "kind",
                            "failures",
                            "file",
                            context.paths().failureLog().toString(),
                            "detail",
                            "a command failed unexpectedly"));
        }
        for (final String[] note : shadowed()) {
            notes.add(java.util.Map.of("kind", note[0], "file", note[1], "detail", note[2]));
        }
        report.put("notes", notes);
        final java.util.Map<String, Object> where = new java.util.LinkedHashMap<>();
        where.put("config", paths.config().toString());
        where.put("data", paths.data().toString());
        where.put("state", paths.state().toString());
        where.put("runtime", paths.runtime().toString());
        where.put("hooks", context.paths().binaryDirectory().toString());
        where.put("agents", agentLocations().stream().map(java.nio.file.Path::toString).toList());
        report.put("paths", where);
        final java.util.Map<String, Object> process = new java.util.LinkedHashMap<>();
        final DaemonHardening daemon =
                DaemonHardening.read(context.runner(), PROC, SetupCommand.DAEMON);
        final java.util.Map<String, Object> daemonState = new java.util.LinkedHashMap<>();
        daemonState.put("pid", daemon.pid());
        daemonState.put("dumpable", daemon.state().name().toLowerCase(java.util.Locale.ROOT));
        process.put("daemon", daemonState);
        process.put("wholeProcess", ProcessHardening.appliesToWholeProcess());
        report.put("process", process);
        out.println(org.fuin.porelo.wire.Json.write(report));
        out.flush();
        return exit(probes);
    }
}
