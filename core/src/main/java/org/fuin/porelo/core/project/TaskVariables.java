package org.fuin.porelo.core.project;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The environment variables Porelo sets in every task. */
public final class TaskVariables {

    private static final Map<String, String> FIXED =
            Map.of(
                    "PATH", "/home/agent/.local/bin:/usr/local/bin:/usr/bin:/bin",
                    "HOME", "/home/agent",
                    "LANG", "C.UTF-8");

    private TaskVariables() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the variables whose values are the same in every task.
     *
     * @return The immutable names and values.
     */
    public static Map<String, String> fixed() {
        return FIXED;
    }

    /**
     * Says whether Porelo sets a variable, with a fixed value or one chosen per task.
     *
     * @param name The variable's name.
     * @return Whether the name is reserved.
     */
    public static boolean reserved(String name) {
        return name.startsWith("PORELO_") || FIXED.containsKey(name);
    }

    /**
     * Returns a variable's fixed value, when it has one.
     *
     * @param name The variable's name.
     * @return The value, or {@code null} when it has no fixed value.
     */
    public static @Nullable String value(String name) {
        return FIXED.get(name);
    }
}
