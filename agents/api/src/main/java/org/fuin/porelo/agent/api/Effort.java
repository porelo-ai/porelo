package org.fuin.porelo.agent.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How an agent takes a reasoning effort, and which values: its definition's {@code effort} section.
 *
 * <p>Declared because the spelling is the agent's own - a flag for some, a config line for others -
 * and the values are in its own words ({@code off} or {@code none}, {@code max}, {@code ultra}).
 * Porelo maps nothing: a person picks one of the agent's values, and no value passes nothing.
 *
 * @param arguments What is added to the agent's command, with {@value #VALUE} where the value goes.
 *     Empty for an agent that takes none.
 * @param values The values it takes, in its own order. Empty for an agent that takes none.
 */
public record Effort(List<String> arguments, List<String> values) {

    /** Where the value goes in {@link #arguments()}. */
    public static final String VALUE = "{effort}";

    /** An agent that takes no effort. */
    public static final Effort NONE = new Effort(List.of(), List.of());

    private static final String WORD = "[a-z0-9][a-z0-9_-]{0,31}";

    /**
     * Constructor with checks.
     *
     * @param arguments What is added, with {@value #VALUE} in it.
     * @param values The values it takes.
     * @throws AgentException If one is declared without the other, without the value's place, or
     *     with a value that is not one short lowercase word, or twice.
     */
    public Effort {
        arguments = List.copyOf(arguments);
        values = List.copyOf(values);
        if (arguments.isEmpty() != values.isEmpty()) {
            throw new AgentException(
                    "effort declares "
                            + (values.isEmpty() ? "no values" : "no arguments")
                            + "; it takes both or neither");
        }
        if (!arguments.isEmpty() && arguments.stream().noneMatch(a -> a.contains(VALUE))) {
            throw new AgentException(
                    "effort's arguments name no place for the value: " + VALUE + " is missing");
        }
        final Set<String> seen = new HashSet<>();
        for (final String value : values) {
            if (!value.matches(WORD) || !seen.add(value)) {
                throw new AgentException(
                        "effort's value '"
                                + value
                                + "' is not one lowercase word of at most 32 characters, or is"
                                + " named twice");
            }
        }
    }

    /**
     * Returns the arguments that hand the agent this value.
     *
     * @param agent The agent's name, for the refusal.
     * @param value One of {@link #values()}.
     * @return The arguments, the value in place.
     * @throws AgentException If the agent takes no effort, or not this one.
     */
    public List<String> argumentsFor(String agent, String value) {
        if (values.isEmpty()) {
            throw new AgentException("Agent '" + agent + "' takes no effort");
        }
        if (!values.contains(value)) {
            throw new AgentException(
                    "Agent '"
                            + agent
                            + "' takes no effort '"
                            + value
                            + "'; it takes "
                            + String.join(", ", values));
        }
        return arguments.stream().map(argument -> argument.replace(VALUE, value)).toList();
    }
}
