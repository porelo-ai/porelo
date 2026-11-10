package org.fuin.porelo.app;

/** The logical owner and the actual container Porelo recorded for one task. */
public record TaskIdentity(
        Format format,
        String project,
        String task,
        String container,
        String recordedId,
        @org.jspecify.annotations.Nullable String projectShort,
        @org.jspecify.annotations.Nullable String taskShort) {
    /** Constructor for an identity without recorded short spellings. */
    public TaskIdentity(
            Format format, String project, String task, String container, String recordedId) {
        this(format, project, task, container, recordedId, null, null);
    }

    /** The encoding known from the task's records, never guessed from its name. */
    public enum Format {
        LEGACY,
        ENCODED
    }
}
