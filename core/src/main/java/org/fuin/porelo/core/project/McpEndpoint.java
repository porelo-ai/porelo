package org.fuin.porelo.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a task of a project may do through its MCP endpoint, as the project's signed file allows it
 * in its {@code mcp} section: which Tasks1 methods the endpoint carries, and the limits on a task
 * started through it.
 *
 * @param methods The methods the endpoint carries, by name without the interface, in the order
 *     written.
 * @param agents Each agent a task started through it may use, to the models it may be asked for; an
 *     empty list allows any model.
 * @param tasks The most tasks one task may have running through it at once.
 * @param minutes The longest run a task started through it may be given, in minutes.
 */
public record McpEndpoint(
        List<String> methods, Map<String, List<String>> agents, int tasks, int minutes) {

    /** The most tasks one task may have running at once when the file does not say. */
    public static final int DEFAULT_TASKS = 6;

    /** The longest run when the file does not say, in minutes. */
    public static final int DEFAULT_MINUTES = 240;

    /**
     * The agents and models allowed when the file does not say, as the operator decided on
     * 2026-11-08: the first with its two larger models, the others with any. Data the file
     * replaces, never a branch: Porelo itself treats every agent alike.
     */
    public static final Map<String, List<String>> DEFAULT_AGENTS = defaultAgents();

    /**
     * Constructor.
     *
     * @param methods The methods.
     * @param agents The agents to their models.
     * @param tasks The most tasks at once.
     * @param minutes The longest run.
     */
    public McpEndpoint {
        methods = List.copyOf(methods);
        final Map<String, List<String>> copied = new LinkedHashMap<>();
        agents.forEach((agent, models) -> copied.put(agent, List.copyOf(models)));
        agents = Collections.unmodifiableMap(copied);
    }

    private static Map<String, List<String>> defaultAgents() {
        final Map<String, List<String>> agents = new LinkedHashMap<>();
        agents.put("claude", List.of("opus", "sonnet"));
        agents.put("codex", List.of());
        agents.put("agy", List.of());
        return Collections.unmodifiableMap(agents);
    }

    /**
     * Tells whether a task started through the endpoint may use an agent and a model.
     *
     * @param agent The agent, by the name {@code porelo agents} lists.
     * @param model The model asked for, or {@code null} for the agent's own default.
     * @return Whether the agent is allowed, and the model with it: any model when its list is
     *     empty, otherwise one named on it. An agent whose models are listed is not started on its
     *     own default, which the file cannot see.
     */
    public boolean allows(final String agent, final @Nullable String model) {
        final List<String> models = agents.get(agent);
        if (models == null) {
            return false;
        }
        return models.isEmpty() || model != null && models.contains(model);
    }

    /**
     * Returns the allowed agents and models as a refusal names them.
     *
     * @return One entry per agent, {@code agent} alone for any model, otherwise {@code agent:model}
     *     per model.
     */
    public List<String> allowed() {
        final List<String> allowed = new java.util.ArrayList<>();
        agents.forEach(
                (agent, models) -> {
                    if (models.isEmpty()) {
                        allowed.add(agent);
                    } else {
                        models.forEach(model -> allowed.add(agent + ":" + model));
                    }
                });
        return List.copyOf(allowed);
    }
}
