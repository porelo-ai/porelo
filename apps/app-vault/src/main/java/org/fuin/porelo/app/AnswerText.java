package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/** An answer as it may be printed: nothing in it moves a terminal's cursor or hides text. */
public final class AnswerText {

    private AnswerText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Replaces what could move a terminal's cursor or hide text in every string of an answer.
     *
     * @param value Text, or lists and maps of it; {@code null} stays {@code null}.
     * @return The same shape, every string printable.
     */
    public static @Nullable Object printable(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case String text -> RegistryText.printable(text);
            case List<?> values -> values.stream().map(AnswerText::printable).toList();
            case Map<?, ?> values -> {
                final Map<String, @Nullable Object> safe = new LinkedHashMap<>();
                for (var entry : values.entrySet()) {
                    safe.put(
                            String.valueOf(
                                    printable(
                                            Objects.requireNonNull(
                                                    entry.getKey(), "an answer's keys are text"))),
                            printable(entry.getValue()));
                }
                yield safe;
            }
            default -> value;
        };
    }
}
