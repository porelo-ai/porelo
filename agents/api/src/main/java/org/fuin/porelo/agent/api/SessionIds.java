package org.fuin.porelo.agent.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where this agent names the session it is running - declared by the agent, because the record, the
 * field and the file shape are facts about one agent and nothing outside {@code agents/} may know
 * them.
 *
 * <p><strong>Read from what the host already has.</strong> A run's own machine-readable output for
 * an unattended run; the agent's own session files, inside the container, for an attached one.
 * Nothing new crosses out of the container for it, and the agent is given no way to send it.
 *
 * <p><strong>A session id is an identifier, not a credential.</strong> It names a transcript on
 * this machine and authorizes nothing, so passing it on a command line - as {@code resume_flag}
 * needs - is allowed.
 *
 * @param record The record an unattended run names its session in: fields, each a dotted path of at
 *     most four segments, and the values they must have, or empty when the agent's machine-readable
 *     output does not name it.
 * @param key The field of that record holding the id, a dotted path too, or {@code null} with an
 *     empty {@code record}.
 * @param directory Where an attached agent keeps its session files, absolute or relative to its
 *     home directory, or {@code null} when it keeps none Porelo can read.
 * @param suffix What a session file's name ends in; the rest of the name is the id, and the newest
 *     such file names the session. {@code null} with a {@code null} directory.
 * @param fileRecord The record a session file names its session in, when it differs from a run's;
 *     empty when a file is read with {@code record} and {@code key}.
 * @param fileKey The field of that record holding the id, or {@code null} with an empty {@code
 *     fileRecord}.
 */
public record SessionIds(
        Map<String, String> record,
        @Nullable String key,
        @Nullable String directory,
        @Nullable String suffix,
        Map<String, String> fileRecord,
        @Nullable String fileKey) {

    /**
     * Constructor for an agent whose session files name their session as its runs do, as every
     * agent did before {@code file_record}.
     *
     * @param record The record's fields.
     * @param key The field holding the id.
     * @param directory The session files' directory.
     * @param suffix Their names' ending.
     */
    public SessionIds(
            Map<String, String> record,
            @Nullable String key,
            @Nullable String directory,
            @Nullable String suffix) {
        this(record, key, directory, suffix, Map.of(), null);
    }

    /**
     * Constructor with validation.
     *
     * @param record The record's fields.
     * @param key The field holding the id.
     * @param directory The session files' directory.
     * @param suffix Their names' ending.
     * @param fileRecord The session file's own record's fields.
     * @param fileKey The field of it holding the id.
     */
    public SessionIds {
        record = Map.copyOf(record);
        fileRecord = Map.copyOf(fileRecord);
        if (record.isEmpty() != (key == null || key.isBlank())) {
            throw new AgentException(
                    "A session id in a run's records needs both the record and its key");
        }
        if (fileRecord.isEmpty() != (fileKey == null || fileKey.isBlank())) {
            throw new AgentException(
                    "A session id in a session file's own record needs both the record and its key");
        }
        if (!fileRecord.isEmpty() && directory == null) {
            throw new AgentException(
                    "A session file's own record is declared only with the files' directory");
        }
        for (final String field : record.keySet()) {
            RecordPath.checked(field, "A record's field");
        }
        for (final String field : fileRecord.keySet()) {
            RecordPath.checked(field, "A session file record's field");
        }
        if (key != null && !key.isBlank()) {
            RecordPath.checked(key, "A session id's key");
        }
        if (fileKey != null && !fileKey.isBlank()) {
            RecordPath.checked(fileKey, "A session file's id key");
        }
        if ((directory == null) != (suffix == null)) {
            throw new AgentException(
                    "A session id in session files needs both the directory and the suffix");
        }
        if (record.isEmpty() && directory == null) {
            throw new AgentException(
                    "A session id declaration that names neither a record nor files is"
                            + " written by leaving it out");
        }
        if (directory != null
                && (directory.isBlank()
                        || directory.contains("..")
                        || !directory.matches("[A-Za-z0-9._/-]+"))) {
            // It reaches a shell command in the container, so only plain path characters are safe.
            throw new AgentException(
                    "A session directory is a plain path, not '" + directory + "'");
        }
        if (suffix != null && (suffix.isBlank() || !suffix.matches("[A-Za-z0-9._-]+"))) {
            throw new AgentException(
                    "A session file suffix is a plain file name ending, not '" + suffix + "'");
        }
    }

    /**
     * Picks the session id out of an unattended run's records.
     *
     * @param records The run's machine-readable output, one parsed record per line, in order.
     * @return The id the first matching record names, or {@code null} when none does or none is
     *     declared.
     */
    public @Nullable String of(java.util.List<?> records) {
        return of(records, record, key);
    }

    /**
     * Picks the session id out of a session file's records: by its own record where one is
     * declared, else as a run's.
     *
     * @param records The file's first records, parsed, in order.
     * @return The id the first matching record names, or {@code null} when none does.
     */
    public @Nullable String ofFile(java.util.List<?> records) {
        return fileRecord.isEmpty() ? of(records) : of(records, fileRecord, fileKey);
    }

    private static @Nullable String of(
            java.util.List<?> records, Map<String, String> wanted, @Nullable String field) {
        if (wanted.isEmpty() || field == null) {
            return null;
        }
        for (final Object each : records) {
            if (each instanceof Map<?, ?> fields
                    && RecordPath.matches(fields, wanted)
                    && RecordPath.value(fields, field) instanceof String id
                    && isId(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Whether a text can be a session id: what reaches a command line has to be one plain word.
     *
     * @param id The text.
     * @return true for letters, digits, dots, dashes and underscores, at most 200 of them
     */
    public static boolean isId(String id) {
        return id.length() <= 200 && id.matches("[A-Za-z0-9._-]+");
    }
}
