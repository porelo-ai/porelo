package org.fuin.porelo.core.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values a project's tasks may be started with, and how a start's values are settled against
 * them.
 *
 * @param declared The declarations, by name, in the order the project file names them.
 */
public record Parameters(Map<String, Parameter> declared) {

    /** Where a value came from, as a start says it. */
    public static final String DEFAULT = "default";

    /**
     * A value as a start hands it in, with where it came from.
     *
     * @param value The value.
     * @param origin {@code command line}, {@code file <path>}, {@code environment} or {@code
     *     default}.
     */
    public record Given(String value, String origin) {}

    /**
     * Constructor.
     *
     * @param declared The declarations, in order.
     */
    public Parameters {
        declared = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(declared));
        final Map<String, String> envs = new LinkedHashMap<>();
        for (final Parameter parameter : declared.values()) {
            if (parameter.env() == null) {
                continue;
            }
            final String other = envs.putIfAbsent(parameter.env(), parameter.name());
            if (other != null) {
                throw new ProjectException(
                        "'parameters."
                                + other
                                + "' and 'parameters."
                                + parameter.name()
                                + "' both name the variable "
                                + parameter.env());
            }
        }
    }

    /**
     * Returns a project's parameters when it declares none.
     *
     * @return No declarations.
     */
    public static Parameters none() {
        return new Parameters(Map.of());
    }

    /**
     * Fills a setting's {@code {name}} places from a task's values and its fixed names.
     *
     * <p>Only names it knows: {@code {task}}, {@code {project}}, {@code {repository}} and the
     * project's parameters. Any other is refused rather than left as written, since a branch named
     * {@code feature/{tpye}} at the forge is found only by the person who reads it.
     *
     * @param template The setting, such as {@code {type}/{task}}.
     * @param values The task's values, as {@link #resolve} settled them.
     * @param task The task's name.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The setting filled in.
     * @throws ProjectException Naming a place no value fills.
     */
    public String fill(
            String template,
            Map<String, Given> values,
            String task,
            String project,
            String repository) {
        final java.util.regex.Matcher place =
                java.util.regex.Pattern.compile("\\{([a-z0-9][a-z0-9-]*)}").matcher(template);
        final StringBuilder filled = new StringBuilder();
        while (place.find()) {
            final String name = place.group(1);
            final String value =
                    switch (name) {
                        case "task" -> task;
                        case "project" -> project;
                        case "repository" -> repository;
                        default -> {
                            if (!declared.containsKey(name)) {
                                throw new ProjectException(
                                        "'{"
                                                + name
                                                + "}' in '"
                                                + template
                                                + "' names no parameter; the project declares "
                                                + new ArrayList<>(declared.keySet()));
                            }
                            final Given given = values.get(name);
                            if (given == null) {
                                throw new ProjectException(
                                        "'{"
                                                + name
                                                + "}' in '"
                                                + template
                                                + "' has no value: the start gave none and it has"
                                                + " no default");
                            }
                            yield given.value();
                        }
                    };
            place.appendReplacement(filled, java.util.regex.Matcher.quoteReplacement(value));
        }
        place.appendTail(filled);
        return filled.toString();
    }

    /**
     * Settles a start's values: each one checked against its declaration, a default where none was
     * handed in, and a required one missing refused.
     *
     * @param given The values handed in, by name, each already the winner of the ways it was given.
     * @return The values the task gets, in the order of the declarations.
     * @throws ProjectException Naming the first value refused and what its declaration allows.
     */
    public Map<String, Given> resolve(Map<String, Given> given) {
        for (final String name : given.keySet()) {
            if (!declared.containsKey(name)) {
                throw new ProjectException(
                        "no parameter '"
                                + name
                                + "' is declared; the project declares "
                                + (declared.isEmpty()
                                        ? "none"
                                        : new ArrayList<>(declared.keySet())));
            }
        }
        final Map<String, Given> resolved = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        for (final Parameter parameter : declared.values()) {
            final Given value = given.get(parameter.name());
            if (value != null) {
                final String refusal = parameter.refusal(value.value());
                if (refusal != null) {
                    throw new ProjectException(refusal);
                }
                resolved.put(parameter.name(), value);
            } else if (parameter.defaultValue() != null) {
                resolved.put(parameter.name(), new Given(parameter.defaultValue(), DEFAULT));
            } else if (parameter.required()) {
                missing.add(parameter.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new ProjectException(
                    "the project requires "
                            + missing
                            + " and the start gives "
                            + (missing.size() == 1 ? "no value for it" : "none of them"));
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }
}
