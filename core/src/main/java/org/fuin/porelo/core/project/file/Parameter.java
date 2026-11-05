package org.fuin.porelo.core.project.file;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entry under {@code parameters}: a value a task of the project may be started with.
 *
 * @param description For people ({@code description}).
 * @param values The values allowed ({@code values}); not with a pattern.
 * @param pattern A regular expression the whole value must match ({@code pattern}).
 * @param defaultValue The value when a start gives none ({@code default}).
 * @param required Whether a start without a value is refused ({@code required}).
 * @param env The variable the task sees it in, or {@code none} ({@code env}).
 */
public record Parameter(
        @Nullable String description,
        @Nullable List<String> values,
        @Nullable String pattern,
        @Nullable String defaultValue,
        @Nullable Boolean required,
        @Nullable String env) {

    /**
     * Constructor.
     *
     * @param description For people.
     * @param values The values allowed, or {@code null}.
     * @param pattern The pattern, or {@code null}.
     * @param defaultValue The default, or {@code null}.
     * @param required Whether it is required, or {@code null} when unsaid.
     * @param env The variable, or {@code null}.
     */
    public Parameter {
        values = values == null ? null : List.copyOf(values);
    }
}
