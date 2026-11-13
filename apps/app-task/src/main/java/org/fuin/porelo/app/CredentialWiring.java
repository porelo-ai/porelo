package org.fuin.porelo.app;

import java.io.PrintWriter;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * The host-side processes that stand between a task and a real credential.
 *
 * <p>Split out of {@code TaskRunCommand} after {@link CredentialChoice}, which decides *which*
 * credential a task uses; this starts what serves it. The broker, the relay and the signing agent
 * are three processes with one property in common: the container talks to them, and none of them
 * ever hands over what it holds.
 *
 * <p>Each one it starts is recorded, because a task that is stopped and resumed has to bring the
 * same processes back in the same order: the broker before the container, and the relay, which
 * binds inside it, once it runs.
 */
final class CredentialWiring {

    /** Records a helper so a resumed task can start it again. */
    @FunctionalInterface
    interface Recorder {

        /**
         * Records one helper.
         *
         * @param name Helper name, which is also its pid and log file name.
         * @param command What to run.
         * @param environment What it needs in its environment.
         * @param phase Whether it must be up before the container or needs the running one.
         */
        void record(
                String name,
                java.util.List<String> command,
                java.util.Map<String, String> environment,
                String phase);
    }

    private final PoreloContext context;

    private final CredentialChoice choice;

    private final Recorder recorder;

    private final String task;

    private final int tokenHours;

    private final @Nullable String upstream;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where the paths and the vault come from.
     * @param choice Which credential this task uses.
     * @param recorder Where each started helper is recorded.
     * @param task Task name, which the token is minted for.
     * @param tokenHours How long that token lasts.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    CredentialWiring(
            PoreloContext context,
            CredentialChoice choice,
            Recorder recorder,
            String task,
            int tokenHours,
            @Nullable String upstream) {
        this.context = context;
        this.choice = choice;
        this.recorder = recorder;
        this.task = task;
        this.tokenHours = tokenHours;
        this.upstream = upstream;
    }

    /**
     * Told each helper this wiring started, by its own handle: a start that fails before it holds a
     * container of its own ends them by these, never by the pid files every start of the name
     * writes in one directory.
     */
    java.util.function.Consumer<ProcessHandle> owned = handle -> {};

    /** A broker that was started and did not come up: the start fails with 70, never goes on. */
    static final class BrokerDown extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor with what was said.
         *
         * @param message What did not come up.
         */
        BrokerDown(final String message) {
            super(message);
        }
    }

    /** Wraps a command so it runs in the task's network namespace; a test's runs where it is. */
    java.util.function.BiFunction<Long, java.util.List<String>, java.util.List<String>> namespace =
            org.fuin.porelo.shield.EgressPolicy::inNamespace;

    private void record(
            String name,
            java.util.List<String> command,
            java.util.Map<String, String> environment,
            String phase) {
        recorder.record(name, command, environment, phase);
    }

    /**
     * Starts the relay that gives the agent its way to the broker inside its own task: a port in
     * its network namespace for a URL, and a socket file in its own filesystem for a socket agent.
     *
     * @param runner Runs containers.
     * @param container Container name.
     * @param socket Broker socket to forward to.
     * @param port Whether it listens on {@link TaskWiring#VAULT_PORT}.
     * @param inTask Whether it binds {@link TaskWiring#VAULT_SOCKET} inside the task.
     * @param out Where progress is reported.
     * @param err Where failures are reported.
     * @return Whether the relay came up and recorded its pid; one that did not is stopped again.
     */
    boolean startRelay(
            TaskRunner runner,
            String container,
            java.nio.file.Path socket,
            boolean port,
            boolean inTask,
            PrintWriter out,
            PrintWriter err) {
        return relay(
                runner,
                container,
                socket,
                port ? TaskWiring.VAULT_PORT : 0,
                inTask ? TaskWiring.VAULT_SOCKET : null,
                "relay",
                out,
                err);
    }

    /** The helper that serves the task's package repositories, as it is recorded. */
    static final String REPOSITORY_BROKER = "repository";

    /** The relay that gives the repository broker its port in the task's namespace. */
    static final String REPOSITORY_RELAY = "repository-relay";

    /**
     * Starts the task's repository broker for the repositories its project declares with a
     * credential: GET and HEAD under each one's prefix, the vault's credential added on the host.
     * Nothing credential-shaped enters the task, not even a token; the task is known by the socket.
     *
     * <p>A repository whose credential the vault cannot give is said and left out, as another
     * credential of the task is: its build then meets the repository's own refusal.
     *
     * @param declared The project's tool repositories.
     * @param container The container.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return The broker's socket and each repository's address for the environment, or {@code
     *     null} when the project declares none with a credential, or none could be served.
     * @throws BrokerDown When the broker was started and did not come up within the helpers'
     *     patience; it is ended by its own handle, never left running.
     */
    @Nullable RepositoryPlumbing startRepositories(
            java.util.List<org.fuin.porelo.core.project.ToolRepository> declared,
            String container,
            PrintWriter out,
            PrintWriter err) {
        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        final java.nio.file.Path socket = state.resolve("repository.sock");
        final java.util.List<String> command =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                PoreloBinary.path(),
                                "vault",
                                "repositories",
                                "--socket",
                                socket.toString(),
                                "--pid-file",
                                state.resolve(REPOSITORY_BROKER + ".pid").toString()));
        final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        for (final org.fuin.porelo.core.project.ToolRepository each : declared) {
            final String credential = each.credential();
            if (credential == null) {
                continue;
            }
            final String missing =
                    reason(
                            credentialUnavailable(context.readableCredentials(), credential),
                            credential);
            if (missing != null) {
                out.println("repository " + each.name() + " none - " + missing);
                continue;
            }
            command.add("--repository");
            command.add(each.name() + "=" + credential + "|" + each.url());
            environment.put(
                    "PORELO_REPOSITORY_" + variableSuffix(each.name()) + "_URL",
                    TaskWiring.repositoryUrl(each.name()));
        }
        if (environment.isEmpty()) {
            out.flush();
            return null;
        }
        final Process broker;
        try {
            broker =
                    new ProcessBuilder(
                                    org.fuin.porelo.core.process.Scope.around(
                                            "porelo " + container + " " + REPOSITORY_BROKER,
                                            command))
                            .redirectErrorStream(true)
                            .redirectOutput(state.resolve(REPOSITORY_BROKER + ".log").toFile())
                            .start();
            owned.accept(broker.toHandle());
            record(REPOSITORY_BROKER, command, java.util.Map.of(), TaskHelpers.BEFORE);
        } catch (java.io.IOException ex) {
            // Asked for and not there: the start went on, and the task's builds met the
            // repository's own refusal.
            err.println("porelo: could not start the repository broker: " + ex.getMessage());
            err.flush();
            throw new BrokerDown("could not start the repository broker");
        }
        // From its start, as the credential broker's patience runs from its own.
        final long deadline = org.fuin.porelo.wire.HelperPid.deadline(GateWiring.HELPER_PATIENCE);
        // Its pid as well as its socket: one that never records it is a broker nothing that cleans
        // up by the pid files finds, and the start that went on left it running.
        if (!awaitSocket(socket, deadline)
                || org.fuin.porelo.wire.HelperPid.await(
                                state.resolve(REPOSITORY_BROKER + ".pid"), deadline)
                        .isEmpty()) {
            // Ended here, by its own handle: it may never have recorded its pid, and the pid files
            // of the name are every start's.
            org.fuin.porelo.wire.HelperPid.end(broker.toHandle(), GateWiring.HELPER_GRACE);
            err.println(
                    "porelo: the repository broker did not come up, see "
                            + state.resolve(REPOSITORY_BROKER + ".log"));
            err.flush();
            throw new BrokerDown("the repository broker did not come up");
        }
        environment.forEach(
                (variable, url) -> out.println("repository " + url + " as " + variable));
        out.flush();
        return new RepositoryPlumbing(socket, java.util.Map.copyOf(environment));
    }

    /**
     * What the repository broker gives a task.
     *
     * @param socket The broker's socket on the host, which its relay dials.
     * @param environment Each repository's address, as {@code PORELO_REPOSITORY_<NAME>_URL}.
     */
    record RepositoryPlumbing(
            java.nio.file.Path socket, java.util.Map<String, String> environment) {}

    /**
     * Starts the relay that gives the repository broker its port in the task's namespace, as {@link
     * #startRelay} gives the credential broker its own.
     *
     * @param runner Runs containers.
     * @param container Container name.
     * @param socket The repository broker's socket.
     * @param out Where progress is reported.
     * @param err Where failures are reported.
     * @return Whether the relay came up and recorded its pid; one that did not is stopped again.
     */
    boolean startRepositoryRelay(
            TaskRunner runner,
            String container,
            java.nio.file.Path socket,
            PrintWriter out,
            PrintWriter err) {
        return relay(
                runner,
                container,
                socket,
                TaskWiring.REPOSITORY_PORT,
                null,
                REPOSITORY_RELAY,
                out,
                err);
    }

    /**
     * Starts a relay that gives a broker something to dial inside the task's own namespace.
     *
     * <p>Only the listening end moves: the broker keeps the host's resolver, the host's egress and
     * the credential. Binding the broker itself in the namespace was tried and fails - it reads the
     * host's {@code /etc/resolv.conf} and then cannot resolve anything at all.
     *
     * @param port The port it listens on in the task's namespace, or zero for none.
     * @param inTask The socket it binds inside the task, or {@code null} for none.
     * @return Whether the relay came up and recorded its pid; one that did not is stopped again.
     */
    private boolean relay(
            TaskRunner runner,
            String container,
            java.nio.file.Path socket,
            int port,
            @Nullable String inTask,
            String helper,
            PrintWriter out,
            PrintWriter err) {

        final java.util.Optional<Long> pid = runner.containerPid(container);
        if (pid.isEmpty()) {
            err.println(
                    "porelo: the container reports no process, so nothing can listen in it and"
                            + " the task has no way to its broker");
            err.flush();
            return false;
        }
        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        final java.util.List<String> relay =
                new java.util.ArrayList<>(java.util.List.of(PoreloBinary.path(), "vault", "relay"));
        if (port > 0) {
            relay.addAll(java.util.List.of("--listen", String.valueOf(port)));
        }
        if (inTask != null) {
            // Through the container's root, so the socket lies in the task's own filesystem and
            // the relay still reaches the broker's socket on the host.
            relay.addAll(java.util.List.of("--bind-in", String.valueOf(pid.get()), "--at", inTask));
        }
        relay.addAll(
                java.util.List.of(
                        "--socket",
                        socket.toString(),
                        "--pid-file",
                        state.resolve(helper + ".pid").toString()));
        final java.util.List<String> command = namespace.apply(pid.get(), relay);
        try {
            final Process started =
                    new ProcessBuilder(
                                    org.fuin.porelo.core.process.Scope.around(
                                            "porelo " + container + " " + helper, command))
                            .redirectErrorStream(true)
                            .redirectOutput(state.resolve(helper + ".log").toFile())
                            .start();
            owned.accept(started.toHandle());
            record(helper, command, java.util.Map.of(), TaskHelpers.AFTER);
            // Without its pid nothing that cleans up finds it, and the task has no endpoint.
            if (org.fuin.porelo.wire.HelperPid.await(
                            state.resolve(helper + ".pid"),
                            org.fuin.porelo.wire.HelperPid.deadline(GateWiring.HELPER_PATIENCE))
                    .isEmpty()) {
                // Signalled is not yet gone: the start answers once it no longer runs, killed
                // when it did not go when asked.
                org.fuin.porelo.wire.HelperPid.end(started.toHandle(), GateWiring.HELPER_GRACE);
                err.println(
                        "porelo: the endpoint relay did not come up; see "
                                + state.resolve(helper + ".log"));
                err.flush();
                return false;
            }
            if (port > 0) {
                out.println("endpoint  http://127.0.0.1:" + port + " in the task's namespace");
            }
            if (inTask != null) {
                out.println("socket    " + inTask + " in the task");
            }
            out.flush();
            return true;
        } catch (java.io.IOException ex) {
            err.println("porelo: could not start the endpoint relay: " + ex.getMessage());
            err.flush();
            return false;
        }
    }

    private static boolean awaitSocket(java.nio.file.Path socket, long deadline) {
        while (System.nanoTime() - deadline < 0) {
            if (java.nio.file.Files.exists(socket)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Returns where the agent was told to send its requests.
     *
     * <p>A socket agent already has the path in a variable, and repeating it here costs nothing. An
     * agent that can only address a URL has no variable to read it from, which is the whole reason
     * the endpoint is passed to the agent rather than left implicit.
     *
     * @param agent The agent.
     * @param environment What the container was given.
     * @return Endpoint, or empty when nothing was brokered.
     */
    String endpointFor(
            org.fuin.porelo.agent.api.InstalledAgent agent,
            java.util.Map<String, String> environment) {
        final SelectedProvider selection = choice.provider(agent);
        if (selection == null) {
            return "";
        }
        final org.fuin.porelo.agent.api.ProviderRoute route = selection.route();
        final org.fuin.porelo.agent.api.AgentProvider declared = agent.definition().provider();
        final boolean addsPath =
                route.baseUrlEnvironment() != null
                        ? route.addsPath()
                        : declared != null && declared.addsPath();
        return endpointFor(route, addsPath, environment);
    }

    /**
     * Returns the endpoint an agent is told about: the same address its base URL variable holds.
     *
     * @param route The route to the provider.
     * @param addsPath Whether the agent adds the dialect's path itself.
     * @param environment What the container was given.
     * @return Endpoint, or empty when nothing was brokered.
     */
    static String endpointFor(
            final org.fuin.porelo.agent.api.ProviderRoute route,
            final boolean addsPath,
            final java.util.Map<String, String> environment) {
        if (route.endpoint() == org.fuin.porelo.agent.api.ProviderRoute.Endpoint.URL) {
            // The dialect's path belongs on the endpoint rather than in the agent: the same
            // provider serves different wire formats under different paths, and only the provider
            // knows which - unless the agent adds it itself.
            return addsPath ? TaskWiring.VAULT_URL : route.endpointFor(TaskWiring.VAULT_URL);
        }
        final String variable = route.socketEnvironment();
        return variable == null ? "" : environment.getOrDefault(variable, "");
    }

    /**
     * What the credential proxy set up for this task.
     *
     * @param socket Host path of the broker's socket, which the task's relay forwards to.
     * @param upstreamHost Provider host to withhold from the firewall.
     * @param environment Variables the container needs to use the proxy.
     * @param servesOthers Whether it serves credentials beyond the agent's, which are reached over
     *     its URL in the task's namespace and so need the relay whatever the agent's own endpoint
     *     is.
     * @param socketInTask Whether the agent was given {@link TaskWiring#VAULT_SOCKET}, which the
     *     relay binds inside the task.
     */
    record CredentialPlumbing(
            java.nio.file.Path socket,
            String upstreamHost,
            java.util.Map<String, String> environment,
            boolean servesOthers,
            boolean socketInTask) {
        /** Without what it holds, as a line must never carry that. */
        @Override
        public String toString() {
            return "CredentialPlumbing[" + socket + ", " + upstreamHost + "]";
        }
    }

    /** Returns whether the vault holds the credential, without conflating locked and missing. */
    static StartCredentials.State credentialUnavailable(
            java.util.Optional<java.util.Map<String, org.fuin.porelo.vault.VaultEntry>> stored,
            String name) {
        if (stored.isEmpty()) {
            return StartCredentials.State.VAULT_LOCKED;
        }
        return stored.get().containsKey(name)
                ? StartCredentials.State.PRESENT
                : StartCredentials.State.MISSING;
    }

    /** Renders a credential state for a person at a terminal. */
    static @Nullable String reason(StartCredentials.State state, String name) {
        return switch (state) {
            case PRESENT -> null;
            case MISSING -> "the vault holds no credential for '" + name + "'";
            case VAULT_LOCKED ->
                    "the vault is locked, so this task cannot authenticate;"
                            + " 'porelo vault unlock' and start it again";
        };
    }

    /**
     * Says why a run that asked for the agent could not authenticate, or {@code null} when it
     * could.
     *
     * <p>For the two modes that asked for the agent itself, and this is what lets the launcher
     * refuse before anything is created. An <em>unattended</em> task that cannot authenticate is
     * certain to be wasted, and the person who finds the wreckage is not the one who started it. An
     * <em>agent</em> task is the same waste with somebody watching it happen: they asked for the
     * agent's session and would get an agent that fails on its first request, after an image and a
     * container were built for it.
     *
     * <p><strong>A shell task keeps the warning</strong>, because working inside the container by
     * hand is exactly what it is for and the agent's credential may not matter to it at all. That
     * reasoning once covered both interactive modes and was wrong for one of them: it argues about
     * somebody opening a terminal, and says nothing about somebody who asked for the agent.
     *
     * <p>An agent that takes no brokered credential answers {@code PRESENT}: there is nothing that
     * could be missing, and refusing it would stop a task that was never going to authenticate
     * anyway.
     *
     * @param agent The agent, or {@code null} when none is installed.
     * @return The credential's availability, with locked distinct from missing.
     */
    StartCredentials.State unavailableFor(
            org.fuin.porelo.agent.api.@Nullable InstalledAgent agent) {
        if (agent == null) {
            return StartCredentials.State.PRESENT;
        }
        final SelectedProvider selection = choice.provider(agent);
        final org.fuin.porelo.agent.api.ProviderRoute route =
                selection == null ? null : selection.route();
        if (route == null || choice.tokenVariable(agent) == null) {
            return StartCredentials.State.PRESENT;
        }
        return credentialUnavailable(context.readableCredentials(), choice.credentialName(agent));
    }

    @Nullable CredentialPlumbing startVault(
            org.fuin.porelo.agent.api.@Nullable InstalledAgent agent,
            String container,
            PrintWriter out,
            PrintWriter err) {
        return startVault(agent, container, java.util.Map.of(), null, out, err);
    }

    /**
     * Starts the task's credential broker: for the agent's own credential, and for every other
     * credential the project and the run name, one route each, picked by the token.
     *
     * <p><strong>Each credential reaches the container as a token worthless anywhere else</strong>:
     * the agent's in the variable its definition names, as before, and every other one as {@code
     * PORELO_TOKEN_<NAME>} beside {@code PORELO_URL_<NAME>}, the broker's address in the task's own
     * namespace. No stored value enters the container.
     *
     * @param agent The agent, or {@code null} when the task has none.
     * @param container The container.
     * @param extras Other credentials, by vault entry name, each with the destination it is for.
     * @param mcp The route to the task's porelo-mcp, or {@code null} for a task without one.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return What the container is given, or {@code null} when nothing is brokered.
     * @throws BrokerDown When a broker was asked for and did not come up; it is not left running.
     */
    @Nullable CredentialPlumbing startVault(
            org.fuin.porelo.agent.api.@Nullable InstalledAgent agent,
            String container,
            java.util.Map<String, Destination> extras,
            @Nullable McpRoute mcp,
            PrintWriter out,
            PrintWriter err) {

        final AgentRoute own = agent == null ? null : agentRoute(agent, out, err);
        final java.util.Map<String, Destination> served = new java.util.LinkedHashMap<>();
        extras.forEach(
                (name, destination) -> {
                    final String missing =
                            reason(
                                    credentialUnavailable(context.readableCredentials(), name),
                                    name);
                    if (missing != null) {
                        out.println("credential " + name + " none - " + missing);
                    } else {
                        served.put(name, destination);
                    }
                });
        if (mcp != null) {
            // Its key is the task's own, not a stored credential, so the vault is not asked here;
            // the broker reads it from the entry it is told.
            served.put(mcp.destination().name(), mcp.destination());
        }
        if (own == null && served.isEmpty()) {
            return null;
        }

        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        final java.nio.file.Path socket = state.resolve("vault.sock");
        final java.nio.file.Path tokenFile = state.resolve("vault.token");
        final java.nio.file.Path routeTokens = state.resolve("routes");

        // The agent's own credential is the broker's first route; without one, the first other
        // credential is.
        final String primaryName =
                own != null ? own.credential() : served.keySet().iterator().next();
        final Destination primary =
                own != null
                        ? own.destination()
                        : java.util.Objects.requireNonNull(served.get(primaryName), primaryName);
        final java.util.Map<String, Destination> routes = new java.util.LinkedHashMap<>(served);
        routes.remove(primaryName);

        // A URL endpoint is bound inside the task's own network namespace: a host-side listener is
        // either unreachable from a rootless container or bound to every interface, and neither is
        // acceptable for something that answers with a credential. Entering the namespace is how
        // the ruleset and the resolver already get there.
        final java.util.List<String> command =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                PoreloBinary.path(),
                                "vault",
                                "serve",
                                "--socket",
                                socket.toString()));
        command.addAll(
                java.util.List.of(
                        "--credential", primaryName,
                        "--task", task,
                        "--upstream", primary.upstream(),
                        "--auth-header", primary.authHeader(),
                        "--auth-prefix", primary.authPrefix(),
                        "--token-file", tokenFile.toString(),
                        "--pid-file", state.resolve("vault.pid").toString(),
                        "--hours", String.valueOf(tokenHours)));
        command.addAll(
                java.util.List.of(
                        "--usage-file", context.paths().tasks().usageRecord(container).toString()));
        if (agent != null) command.addAll(java.util.List.of("--agent", agent.name()));
        final String primaryProvider =
                own != null
                        ? own.provider()
                        : context.providers().containsKey(primary.name()) ? primary.name() : null;
        if (primaryProvider != null)
            command.addAll(java.util.List.of("--provider-name", primaryProvider));
        routes.forEach(
                (name, destination) -> {
                    if (context.providers().containsKey(destination.name())) {
                        command.addAll(
                                java.util.List.of("--provider", name + "=" + destination.name()));
                    }
                });
        // A service that takes its key in the URL: the broker puts it there, and in no header.
        if (primary.authQuery() != null) {
            command.add("--auth-query");
            command.add(primary.authQuery());
        }
        // Named here and filled by the broker from the stored credential, so no value is on a
        // command line.
        if (own != null) {
            own.credentialHeaders()
                    .forEach(
                            (header, setting) -> {
                                command.add("--credential-header");
                                command.add(header + "=" + setting);
                            });
        }
        if (mcp != null) {
            command.addAll(mcp.arguments());
        }
        if (!routes.isEmpty()) {
            command.add("--route-tokens");
            command.add(routeTokens.toString());
            routes.forEach(
                    (name, destination) -> {
                        command.add("--route");
                        command.add(
                                name
                                        + "="
                                        + destination.upstream()
                                        + "|"
                                        + destination.authHeader()
                                        + "|"
                                        + destination.authPrefix()
                                        + "|"
                                        + (destination.authQuery() == null
                                                ? ""
                                                : destination.authQuery()));
                    });
        }

        final Process broker;
        try {
            java.nio.file.Files.deleteIfExists(tokenFile);
            if (!routes.isEmpty()) {
                Directories.create(routeTokens);
                java.nio.file.Files.setPosixFilePermissions(
                        routeTokens,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
            broker =
                    new ProcessBuilder(
                                    org.fuin.porelo.core.process.Scope.around(
                                            "porelo " + container + " vault", command))
                            .redirectErrorStream(true)
                            .redirectOutput(state.resolve("vault.log").toFile())
                            .start();
            owned.accept(broker.toHandle());
            record("vault", command, java.util.Map.of(), TaskHelpers.BEFORE);
        } catch (java.io.IOException ex) {
            // Asked for and not there: taken for "nothing brokered", the start went on with an
            // agent that cannot authenticate and answered 0.
            err.println("porelo: could not start the credential proxy: " + ex.getMessage());
            err.flush();
            throw new BrokerDown("could not start the credential proxy");
        }

        // From its start, as the gate's and the relay's patience runs from theirs.
        final long recorded = org.fuin.porelo.wire.HelperPid.deadline(GateWiring.HELPER_PATIENCE);
        final String token = awaitToken(socket, tokenFile);
        // Recorded before its token is published; a broker whose pid is not there is one nothing
        // that cleans up by the pid files finds, and the start that went on left it running.
        if (token == null
                || org.fuin.porelo.wire.HelperPid.await(state.resolve("vault.pid"), recorded)
                        .isEmpty()) {
            // Ended here, by its own handle: it may never have recorded its pid, and then nothing
            // that cleans up by the pid files finds it.
            org.fuin.porelo.wire.HelperPid.end(broker.toHandle(), GateWiring.HELPER_GRACE);
            err.println(
                    "porelo: the credential proxy did not come up, see "
                            + state.resolve("vault.log"));
            err.flush();
            throw new BrokerDown("the credential proxy did not come up");
        }

        final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        if (own != null) {
            environment.putAll(
                    agentEnvironment(
                            agent == null ? null : agent.definition(),
                            own.variable(),
                            token,
                            own.route()));
            out.println("vault     " + socket + " -> " + own.route().upstream());
            // The variable's name alone: a prefix is part of a secret.
            out.println("token     " + own.variable());
        }
        for (final java.util.Map.Entry<String, Destination> credential : served.entrySet()) {
            final String name = credential.getKey();
            final String minted =
                    name.equals(primaryName) && own == null
                            ? token
                            : awaitToken(null, routeTokens.resolve(name + ".token"));
            if (minted == null) {
                err.println(
                        "porelo: the credential proxy wrote no token for "
                                + name
                                + ", see "
                                + state.resolve("vault.log"));
                err.flush();
                continue;
            }
            final String suffix = variableSuffix(name);
            environment.put("PORELO_TOKEN_" + suffix, minted);
            environment.put("PORELO_URL_" + suffix, TaskWiring.VAULT_URL);
            out.println(
                    "credential "
                            + name
                            + " -> "
                            + where(credential.getValue())
                            + " as PORELO_TOKEN_"
                            + suffix
                            + ", PORELO_URL_"
                            + suffix);
        }
        out.flush();
        return new CredentialPlumbing(
                socket,
                own != null ? own.route().upstreamHost() : where(primary),
                environment,
                !served.isEmpty(),
                own != null && own.route().socketEnvironment() != null);
    }

    /**
     * Returns the variables a task's container is given for its agent's brokered credential.
     *
     * @param agent The agent's definition, or {@code null}.
     * @param variable Variable the phantom token goes in.
     * @param token The phantom token.
     * @param route The resolved route to the provider.
     * @return Variables for the container.
     */
    static java.util.Map<String, String> agentEnvironment(
            final org.fuin.porelo.agent.api.@Nullable AgentDefinition agent,
            final String variable,
            final String token,
            final org.fuin.porelo.agent.api.ProviderRoute route) {
        final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(variable, token);
        if (route.socketEnvironment() != null) {
            environment.put(route.socketEnvironment(), TaskWiring.VAULT_SOCKET);
        }
        final org.fuin.porelo.agent.api.AgentProvider provider =
                agent == null ? null : agent.provider();
        final String baseUrlEnvironment =
                route.baseUrlEnvironment() != null
                        ? route.baseUrlEnvironment()
                        : provider == null ? null : provider.baseUrlEnvironment();
        final boolean addsPath =
                route.baseUrlEnvironment() != null
                        ? route.addsPath()
                        : provider != null && provider.addsPath();
        if (baseUrlEnvironment != null) {
            // Both, always. The socket variable only picks the transport; without a base URL the
            // agent uses its own compiled-in endpoint and never touches the socket at all.
            environment.put(
                    baseUrlEnvironment,
                    addsPath ? TaskWiring.VAULT_URL : route.endpointFor(TaskWiring.VAULT_URL));
        }
        return java.util.Map.copyOf(environment);
    }

    /**
     * Returns the part of a variable's name a credential gives: upper case, {@code -} as {@code _}.
     *
     * @param name The credential's name.
     * @return The suffix.
     */
    /**
     * The route to a task's porelo-mcp: a destination whose key is no credential a person stored,
     * but the task's own vault entry, which the broker reads itself.
     *
     * @param destination Where the gateway sends the route's requests, {@code unix:<socket>}.
     * @param keyEntry The task's own vault entry holding the key.
     */
    record McpRoute(Destination destination, String keyEntry) {

        /**
         * Returns what the broker is told beside the route: which entry holds its key, never the
         * key.
         *
         * @return The arguments.
         */
        java.util.List<String> arguments() {
            return java.util.List.of("--task-key", destination.name() + "=" + keyEntry);
        }
    }

    /**
     * Returns where a destination is, for a line: its host, or the socket of a {@code unix:} one,
     * which has no host.
     *
     * @param destination The destination.
     * @return The host or the socket.
     */
    static String where(Destination destination) {
        return destination.upstream().startsWith("unix:")
                ? destination.upstream()
                : destination.host();
    }

    static String variableSuffix(String name) {
        return org.fuin.porelo.core.project.CredentialName.variable(name);
    }

    /** The agent's own credential, where it goes and which variable carries its token. */
    private record AgentRoute(
            String provider,
            String credential,
            String variable,
            org.fuin.porelo.agent.api.ProviderRoute route,
            Destination destination,
            java.util.Map<String, String> credentialHeaders) {}

    private @Nullable AgentRoute agentRoute(
            org.fuin.porelo.agent.api.InstalledAgent agent, PrintWriter out, PrintWriter err) {
        final SelectedProvider selection = choice.provider(agent);
        final org.fuin.porelo.agent.api.ProviderRoute route =
                selection == null ? null : selection.route();
        final String type = choice.credentialType(choice.credentialName(agent));
        final String variable = choice.tokenVariable(agent);
        if (route == null || variable == null) {
            // Nothing to proxy through. Not an error - an agent may take no credential at all - but
            // if it takes one and cannot be redirected, say so rather than issue a token that
            // cannot work.
            if (variable != null) {
                err.println(
                        "porelo: '"
                                + agent.name()
                                + "' declares no proxy route, so its"
                                + " credential cannot be brokered; it will not authenticate");
                err.flush();
            } else if (route != null && selection != null) {
                // Said, not skipped: a provider chosen and nothing brokered for it started a task
                // that failed on its first request with "no API key" (measured with Copilot,
                // 2026-10-01).
                err.println(
                        "porelo: neither '"
                                + agent.name()
                                + "' nor the provider '"
                                + selection.name()
                                + "' names"
                                + " the variable a task's token goes in, so nothing is brokered for it; it will not"
                                + " authenticate. Add 'token_env' to the provider's definition.");
                err.flush();
            }
            return null;
        }
        final String unavailable =
                reason(
                        credentialUnavailable(
                                context.readableCredentials(), choice.credentialName(agent)),
                        choice.credentialName(agent));
        if (unavailable != null) {
            out.println("token     none - " + unavailable);
            return null;
        }
        final String query = route.authQueryFor(type);
        return new AgentRoute(
                java.util.Objects.requireNonNull(selection, "a route has a provider").name(),
                choice.credentialName(agent),
                variable,
                route,
                new Destination(
                        "agent-provider",
                        "the agent's provider",
                        route.upstream(),
                        route.authHeaderFor(type),
                        route.authPrefixFor(type),
                        query),
                java.util.Objects.requireNonNull(selection, "a route has a provider")
                        .definition()
                        .credentialHeaders());
    }

    /**
     * Waits for the proxy to be listening and to have written its token.
     *
     * <p>Both, not either: the socket exists a moment before the token file does, and starting the
     * container with an empty token produces an authentication failure that looks like a bad
     * credential.
     *
     * @param socket Socket the proxy binds.
     * @param tokenFile File the proxy writes its token to.
     * @return The token, or {@code null} if it did not appear in time.
     */
    private static @Nullable String awaitToken(
            java.nio.file.@org.jspecify.annotations.Nullable Path socket,
            java.nio.file.Path tokenFile) {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                // A proxy that bound a port leaves no socket file to wait for; the token file is
                // written after it is listening either way, so that is the signal that works for
                // both.
                if ((socket == null || java.nio.file.Files.exists(socket))
                        && java.nio.file.Files.exists(tokenFile)) {
                    final String token = java.nio.file.Files.readString(tokenFile).strip();
                    if (!token.isEmpty()) {
                        return token;
                    }
                }
                Thread.sleep(100);
            } catch (java.io.IOException ex) {
                return null;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
