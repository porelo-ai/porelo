package org.fuin.porelo.core.project;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * A value a task of a project may be started with, as the project declares it.
 *
 * @param name The name a start hands it in by, lower-case letters, digits and hyphens.
 * @param description For people.
 * @param values The values allowed, or {@code null} when a pattern or anything goes.
 * @param pattern The regular expression the whole value must match, or {@code null}.
 * @param defaultValue The value when none is handed in, or {@code null}.
 * @param required Whether a start without it is refused.
 * @param env The variable the task sees it in, and the caller's shell is read from; {@code null}
 *     keeps it out of the task.
 */
public record Parameter(
        String name,
        String description,
        @Nullable List<String> values,
        @Nullable String pattern,
        @Nullable String defaultValue,
        boolean required,
        @Nullable String env) {

    /** What a declaration's {@code env} says to keep a value out of the task. */
    public static final String NO_ENV = "none";

    /**
     * Constructor with checks.
     *
     * @param name The name.
     * @param description For people.
     * @param values The values allowed, or {@code null}.
     * @param pattern The pattern, or {@code null}.
     * @param defaultValue The default, or {@code null}.
     * @param required Whether it is required.
     * @param env The variable, or {@code null} for none.
     */
    public Parameter {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new ProjectException(
                    "'parameters."
                            + name
                            + "' is no parameter name: lower-case letters, digits and hyphens");
        }
        if (values != null && pattern != null) {
            throw new ProjectException(
                    "'parameters." + name + "' names both 'values' and 'pattern'; say one");
        }
        if (values != null) {
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new ProjectException("'parameters." + name + ".values' names no value");
            }
        }
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                throw new ProjectException(
                        "'parameters." + name + ".pattern' is no regular expression: " + pattern,
                        ex);
            }
        }
        if (env != null
                && (!env.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                        || TaskVariables.reserved(env) && !env.startsWith("PORELO_PARAM_"))) {
            // Porelo sets these in every task; a value under one of them would decide where the
            // task's tools and its own token are found.
            throw new ProjectException(
                    "'parameters."
                            + name
                            + ".env' cannot be '"
                            + env
                            + "': a variable name Porelo does not set itself, or 'none'");
        }
        if (defaultValue != null && refusal(name, values, pattern, defaultValue) != null) {
            throw new ProjectException(
                    "'parameters." + name + ".default' is not allowed by its own declaration");
        }
    }

    /**
     * Returns the variable a value is set in by default, when its declaration names none.
     *
     * @param name The parameter's name.
     * @return {@code PORELO_PARAM_<NAME>}.
     */
    public static String defaultEnv(String name) {
        return "PORELO_PARAM_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Says why a value is not allowed, or nothing when it is.
     *
     * @param value The value.
     * @return The reason, or {@code null}.
     */
    public @Nullable String refusal(String value) {
        return refusal(name, values, pattern, value);
    }

    private static @Nullable String refusal(
            String name, @Nullable List<String> values, @Nullable String pattern, String value) {
        if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return "'" + name + "' holds a control character";
        }
        if (values != null && !values.contains(value)) {
            return "'" + name + "' is '" + value + "', and its declaration allows " + values;
        }
        if (pattern != null && !Pattern.matches(pattern, value)) {
            return "'" + name + "' is '" + value + "', and its declaration allows only " + pattern;
        }
        return null;
    }
}
