package org.fuin.porelo.app;

/** The logical owner and the actual container Porelo recorded for one task. */
public record TaskIdentity(
        Format format, String project, String task, String container, String recordedId) {
    /** The encoding known from the task's records, never guessed from its name. */
    public enum Format {
        LEGACY,
        ENCODED
    }
}
