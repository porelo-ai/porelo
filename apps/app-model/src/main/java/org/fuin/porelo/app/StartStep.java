package org.fuin.porelo.app;

import java.util.Map;

/**
 * One transition of a task's start.
 *
 * @param step The stable stage.
 * @param state What it is doing.
 * @param text The words every surface shows.
 * @param waitingOn The outside dependency, or empty.
 * @param cause The failure's last diagnostic, or empty.
 */
public record StartStep(Step step, State state, String text, String waitingOn, String cause) {
    /** The stable stages of starting a task. */
    public enum Step {
        SOURCE,
        CHECKS,
        CREDENTIALS,
        WORKSPACE,
        CONVERSATION,
        IMAGE,
        CONTAINER,
        HELPERS,
        AGENT
    }

    /** What a start is doing in a stage. */
    public enum State {
        STARTED,
        WAITING,
        DONE,
        FAILED
    }

    /**
     * Returns the contract's five fields.
     *
     * @return The varlink shape.
     */
    public Map<String, Object> asMap() {
        return Map.of(
                "step",
                step.name(),
                "state",
                state.name(),
                "text",
                text,
                "waitingOn",
                waitingOn,
                "cause",
                cause);
    }

    /**
     * Cleans a dependency's diagnostic for a terminal.
     *
     * @param value The diagnostic.
     * @return Its safe last line.
     */
    public static String cleanCause(String value) {
        final String[] lines = value.split("\\R", -1);
        for (int index = lines.length - 1; index >= 0; index--) {
            final String line =
                    lines[index]
                            .replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "")
                            .replaceAll(
                                    "[\\p{Cc}\\p{Cf}\\x{034f}\\x{115f}-\\x{1160}\\x{17b4}-\\x{17b5}"
                                            + "\\x{180b}-\\x{180f}\\x{2065}\\x{3164}\\x{fe00}-\\x{fe0f}"
                                            + "\\x{ffa0}\\x{fff0}-\\x{fff8}\\x{e0000}-\\x{e0fff}]",
                                    "")
                            .strip();
            if (!line.isEmpty()) {
                return line.substring(0, Math.min(300, line.length()));
            }
        }
        return "";
    }

    /**
     * Cleans an outside dependency's name.
     *
     * @param value Its diagnostic name.
     * @return At most 80 safe characters.
     */
    public static String cleanWaitingOn(String value) {
        final String cleaned = cleanCause(value);
        return cleaned.substring(0, Math.min(80, cleaned.length()));
    }
}
