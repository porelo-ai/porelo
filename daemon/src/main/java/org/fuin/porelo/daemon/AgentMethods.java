package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentModels;
import org.fuin.porelo.app.ModelLists;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The daemon's methods for agents and providers.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 *
 * <p>Read-only answers about the machine, so an interface never shells out to the CLI to find out
 * what is installed or what is waiting.
 */
final class AgentMethods {

    private AgentMethods() {}

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     * @param signIns The daemon's one sign-in registry.
     */
    @SuppressWarnings("unused")
    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control,
            org.fuin.porelo.app.AgentSignInRuns signIns) {
        // The same rows 'porelo providers' prints, from the same class.
        server.method(
                "Providers",
                (parameters, replies) ->
                        replies.last(org.fuin.porelo.app.ProviderInventory.list(context).asMap()));

        // One per daemon, so a list is kept across calls for its hour.
        final org.fuin.porelo.app.ModelLists models =
                new org.fuin.porelo.app.ModelLists(context.limits());
        server.method(
                "Models",
                (parameters, replies) -> replies.last(models(context, models, parameters)));

        server.method(
                "Agents",
                (parameters, replies) -> {
                    // Each agent is a process this starts and handshakes with, so it is closed
                    // again: leaving them running would leak one per call.
                    try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
                        final List<Map<String, Object>> found =
                                agents.all().stream()
                                        .map(
                                                agent -> {
                                                    final Map<String, Object> entry =
                                                            entry(
                                                                    agent.name(),
                                                                    agent.definition(),
                                                                    agent.executable());
                                                    found(
                                                            entry,
                                                            org.fuin.porelo.app.AgentChoice.found(
                                                                    context, agent));
                                                    return entry;
                                                })
                                        .toList();
                        // Which copy of a shadowed name runs is a rule, and it lives in
                        // AgentDirectory with the scan that implements it. Reported separately
                        // because a shadowed binary is never started and so has no agent entry to
                        // carry a flag. Two ways a binary ends up installed and never run, and one
                        // list: an earlier location holds the same FILE NAME, or another binary
                        // claimed the same DECLARED NAME first. Different causes, identical
                        // consequence, and an interface has one thing to say about both - so they
                        // are not split into two fields nobody asked for.
                        final Map<Path, Path> never =
                                new LinkedHashMap<>(
                                        context.paths().agents().agentDirectory().shadowedBy());
                        never.putAll(agents.ignored());
                        final List<Map<String, Object>> hidden =
                                never.entrySet().stream()
                                        .map(
                                                pair -> {
                                                    final Map<String, Object> row =
                                                            new LinkedHashMap<>();
                                                    row.put("path", pair.getKey().toString());
                                                    row.put(
                                                            "usedInstead",
                                                            pair.getValue().toString());
                                                    return row;
                                                })
                                        .toList();
                        // What could not be asked matters as much as what could: an agent that
                        // fails to describe itself is installed and unusable, and silence would
                        // read as absent.
                        replies.last(
                                Map.of(
                                        "agents",
                                        found,
                                        "failures",
                                        agents.failures(),
                                        "shadowed",
                                        hidden));
                    }
                });

        // One per agent on the machine, kept for the daemon's life so a wait after a sign-in
        // ended hears how it ended.
        server.method(
                "AgentSignIns",
                (parameters, replies) -> {
                    try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
                        // Read once: each read of a passphrase vault is a key derivation.
                        final var stored = context.readableCredentials();
                        final var providers = context.providers();
                        final List<Map<String, Object>> rows =
                                agents.all().stream()
                                        .map(
                                                agent -> {
                                                    final Map<String, Object> row =
                                                            new LinkedHashMap<>();
                                                    row.put("agent", agent.name());
                                                    row.put(
                                                            "state",
                                                            org.fuin.porelo.app.AgentSignInRuns
                                                                    .state(
                                                                            providers,
                                                                            agent.definition(),
                                                                            stored)
                                                                    .name());
                                                    signIns.running(agent.name())
                                                            .ifPresent(
                                                                    container ->
                                                                            row.put(
                                                                                    "running",
                                                                                    container));
                                                    return row;
                                                })
                                        .toList();
                        replies.last(Map.of("signIns", rows));
                    }
                });
        server.method(
                "AgentSignInStart",
                (parameters, replies) -> {
                    final String agent = text(parameters, "agent");
                    try {
                        final org.fuin.porelo.app.AgentSignInRuns.Started started =
                                signIns.start(
                                        agent,
                                        flag(parameters, "force"),
                                        flag(parameters, "remote"));
                        replies.last(
                                Map.of(
                                        "container",
                                        started.container(),
                                        "started",
                                        started.started(),
                                        "attach",
                                        started.container().isEmpty()
                                                ? List.of()
                                                : context.podman()
                                                        .arguments(
                                                                // A closed terminal ends the
                                                                // watching, not the sign-in.
                                                                List.of(
                                                                        "attach",
                                                                        "--sig-proxy=false",
                                                                        started.container()))));
                    } catch (org.fuin.porelo.app.AgentSignInRuns.Refused ex) {
                        throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                INTERFACE + ".SignInRefused",
                                Map.of(
                                        "agent",
                                        agent,
                                        "reason",
                                        ex.outcome().name(),
                                        "detail",
                                        String.valueOf(ex.getMessage())));
                    }
                });
        server.method(
                "AgentSignInWait",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.AgentSignInRuns.Ended ended =
                            signIns.await(text(parameters, "agent"));
                    replies.last(Map.of("stored", ended.stored(), "reason", ended.reason()));
                });

        // The first start's own question, so a window sees what a start would make of a checkout.
        final org.fuin.porelo.app.FirstStart firstStart = TaskMethods.firstStart(context);
        server.method(
                "Ecosystems",
                (parameters, replies) -> {
                    try {
                        final Path checkout =
                                parameters.get("checkout") == null
                                        ? null
                                        : TaskMethods.checkout(parameters);
                        replies.last(firstStart.ecosystems(checkout).asMap());
                    } catch (org.fuin.porelo.app.FirstStart.Refused ex) {
                        throw TaskMethods.firstStartRefused(ex);
                    } catch (org.fuin.porelo.ecosystem.api.EcosystemException ex) {
                        throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                INTERFACE + ".EcosystemsNotListed",
                                Map.of(
                                        "detail",
                                        org.fuin.porelo.vault.RegistryText.printable(
                                                String.valueOf(ex.getMessage()))));
                    }
                });

        server.method(
                "Installable",
                (parameters, replies) -> {
                    // Asked of this machine's own package source, never of a list kept here: every
                    // published agent package provides 'porelo-agent' (the test stub does not) and
                    // every transport 'porelo-transport', so a package published this morning is
                    // offered this morning.
                    replies.last(
                            Map.of(
                                    "packages",
                                    new org.fuin.porelo.app.InstallablePackages(context.runner())
                                            .list().stream()
                                                    .map(
                                                            one ->
                                                                    Map.of(
                                                                            "name",
                                                                            one.name(),
                                                                            "kind",
                                                                            one.kind(),
                                                                            "description",
                                                                            one.description(),
                                                                            "installed",
                                                                            one.installed(),
                                                                            "version",
                                                                            one.version()))
                                                    .toList()));
                });
    }

    /**
     * Answers {@code Models}: the provider's list, or the selected agent's matching model menu. A
     * fresh ask from a task's socket is answered as one that is not fresh.
     *
     * @param context The machine.
     * @param models The daemon's one keeper of lists.
     * @param parameters The call's.
     * @return The reply.
     */
    static Map<String, Object> models(
            final PoreloContext context,
            final org.fuin.porelo.app.ModelLists models,
            final Map<String, Object> parameters) {
        return models(
                context.providers(),
                context::readableCredentials,
                models,
                parameters,
                name -> {
                    try (var installed = context.agents()) {
                        return installed.find(name).map(agent -> agent.definition()).orElse(null);
                    }
                });
    }

    /**
     * Answers {@code Models} from the providers and the vault given.
     *
     * @param providers The providers this machine declares, by name.
     * @param credentials What the vault holds, read only when a provider is asked.
     * @param models The daemon's one keeper of lists.
     * @param parameters The call's.
     * @return The reply.
     */
    static Map<String, Object> models(
            final Map<String, org.fuin.porelo.agent.api.ProviderDefinition> providers,
            final java.util.function.Supplier<
                            java.util.Optional<Map<String, org.fuin.porelo.vault.VaultEntry>>>
                    credentials,
            final org.fuin.porelo.app.ModelLists models,
            final Map<String, Object> parameters) {
        return models(providers, credentials, models, parameters, name -> null);
    }

    /** Answers with the installed agent definitions supplied by the caller. */
    static Map<String, Object> models(
            final Map<String, org.fuin.porelo.agent.api.ProviderDefinition> providers,
            final java.util.function.Supplier<
                            java.util.Optional<Map<String, org.fuin.porelo.vault.VaultEntry>>>
                    credentials,
            final ModelLists models,
            final Map<String, Object> parameters,
            final java.util.function.Function<String, @Nullable AgentDefinition> agents) {
        final Object requested = parameters.get("agent");
        final AgentModels declared;
        if (requested == null) {
            declared = AgentModels.NONE;
        } else {
            final AgentDefinition agent =
                    requested instanceof String name ? agents.apply(name) : null;
            if (agent == null) {
                throw new org.fuin.porelo.wire.varlink.VarlinkException(
                        "org.varlink.service.InvalidParameter", Map.of("parameter", "agent"));
            }
            declared = agent.models();
        }
        final String name = text(parameters, "provider");
        final org.fuin.porelo.agent.api.ProviderDefinition provider = providers.get(name);
        // An agent's menu cannot make an undeclared provider usable.
        if (provider == null) {
            throw modelsFailed(
                    name,
                    "this machine declares no provider '"
                            + org.fuin.porelo.vault.RegistryText.printable(name)
                            + "'; 'porelo providers' lists those it does");
        }
        final List<AgentModels.Model> fallback = declared.providers().getOrDefault(name, List.of());
        try {
            // A task's agent takes what was kept: its fresh asks would spend the person's key.
            final boolean fresh = flag(parameters, "fresh") && McpTaskSockets.caller() == null;
            final ModelLists.Listing listing = models.list(provider, credentials, fresh);
            return "none".equals(listing.from()) && !fallback.isEmpty()
                    ? agentModels(fallback)
                    : listing.asMap();
        } catch (ModelLists.Failed ex) {
            if (!fallback.isEmpty()) {
                return agentModels(fallback);
            }
            throw modelsFailed(name, String.valueOf(ex.getMessage()));
        }
    }

    private static Map<String, Object> agentModels(List<AgentModels.Model> models) {
        // Static menus belong to an agent, never the provider's cache or minute limit.
        return new ModelLists.Listing(
                        models.stream()
                                .map(model -> new ModelLists.Model(model.id(), model.name(), null))
                                .toList(),
                        "agent",
                        Instant.now())
                .asMap();
    }

    private static org.fuin.porelo.wire.varlink.VarlinkException modelsFailed(
            String provider, String message) {
        return new org.fuin.porelo.wire.varlink.VarlinkException(
                INTERFACE + ".ModelsFailed",
                Map.of(
                        "provider",
                        org.fuin.porelo.vault.RegistryText.printable(provider),
                        "message",
                        message));
    }

    /**
     * Adds what an agent found of itself to its entry, as the {@code Agents} method answers it.
     *
     * @param entry The agent's entry.
     * @param finds What it found.
     */
    static void found(
            final Map<String, Object> entry, final org.fuin.porelo.app.AgentChoice.Found finds) {
        entry.put("found", finds.state().name());
        entry.put("foundProvider", finds.provider() == null ? "" : finds.provider());
        entry.put("foundPaths", finds.paths());
        if (finds.throughVault()) {
            entry.put("foundVia", "vault");
        }
    }

    /**
     * One agent as the {@code Agents} method answers it.
     *
     * @param name The name the binary calls itself by.
     * @param definition What it declares.
     * @param executable Where it was found.
     * @return The entry, in the interface's field order.
     */
    static Map<String, Object> entry(
            String name, org.fuin.porelo.agent.api.AgentDefinition definition, Path executable) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("label", definition.label());
        entry.put("binary", definition.binary());
        entry.put("version", definition.version() == null ? "" : definition.version());
        entry.put("from", executable.toString());
        // How this agent logs itself in, and where that
        // is written down. Declared in the manifest all
        // along and never answered here, so a client
        // that wanted to offer "log in with the agent"
        // had no way to know the verb - and Porelo must
        // not guess one, because hardcoding one agent's
        // would be wrong for the rest.
        entry.put(
                "loginArguments",
                definition.loginArguments() == null
                        ? java.util.List.<String>of()
                        : definition.loginArguments());
        // Empty when the agent declares no login at
        // all, which is most of them: an agent whose
        // credential is an API key has nothing to log
        // in to.
        entry.put("canLogIn", definition.loginArguments() != null);
        // The whole command, not the verb for a client
        // to spell out. A client that composes 'porelo
        // vault login <agent>' is right until the day
        // the verb moves, and then it is wrong
        // everywhere at once - the same reason
        // storeCommand is answered rather than joined
        // together from a provider list.
        entry.put(
                "loginCommand",
                definition.loginArguments() == null
                        ? java.util.List.<String>of()
                        : java.util.List.of("porelo", "vault", "login", name));
        // How its sign-in works, as 'porelo agents
        // --json' says it: absent when the definition
        // names none, so a client never reads a guess.
        if (definition.loginFlow() != null) {
            entry.put("loginFlow", definition.loginFlow().value());
        }
        entry.put(
                "loginDocumentation",
                definition.loginDocumentation() == null ? "" : definition.loginDocumentation());
        entry.put("allowedDomains", definition.allowedDomains());
        // Asked for and deliberately not given. Absent
        // from the allowed list means nobody mentioned
        // it; this means somebody decided, and the two
        // read the same way to anybody who is only
        // shown the first.
        entry.put("refusedDomains", definition.refusedDomains());
        entry.put(
                "artifacts", definition.artifacts().stream().map(PoreloDaemon::artifact).toList());
        // What an agent's commits are signed with.
        // Nothing surfaced this until a pre-push guard
        // started deciding on it, and "did a person or
        // an agent write this commit?" had no answer
        // anybody could look up.
        final Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", definition.gitIdentity().name());
        identity.put("email", definition.gitIdentity().email());
        entry.put("commitsAs", identity);
        // What Start's effort takes for it, in the definition's order; empty: it takes none.
        entry.put("efforts", definition.effort().values());
        return entry;
    }
}
