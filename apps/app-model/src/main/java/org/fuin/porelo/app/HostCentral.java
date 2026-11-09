package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.RootOwnedFile;
import org.jspecify.annotations.Nullable;

/**
 * Reads what a host's follow last found of how its forge's copies of a project stand against the
 * central forge.
 *
 * <p>The central forge's credential is root's on a host, so the person's account cannot measure
 * this itself. The host's follow publishes {@code
 * /etc/porelo-host/organization/central/<project>.json} for the project's unlocked members to read
 * in their own account, asking central nothing. The file is trusted only when it and every parent
 * directory are root's alone with no symlink, and the whole answer is judged before anything of it
 * is recorded or printed; an answer that is missing, refused or unparsable says {@code cannot-say}
 * rather than {@code up-to-date}.
 */
final class HostCentral {

    /** Where a host's follow publishes each project's central state, root's alone. */
    static final Path DIRECTORY = Path.of("/etc/porelo-host/organization/central");

    /** Reads Unix ownership and mode without following symlinks. */
    static final RootOwnedFile.Attributes ATTRIBUTES =
            path ->
                    java.nio.file.Files.readAttributes(
                            path, "unix:uid,mode", java.nio.file.LinkOption.NOFOLLOW_LINKS);

    /** A project name as the host's central-state files name it. */
    static final Pattern PROJECT_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");

    /** Largest central-state file read for one project. */
    static final int MAXIMUM_BYTES = 1024 * 1024;

    /** The states a host may answer. */
    static final Set<String> STATES = Set.of("up-to-date", "ahead", "moved-on", "cannot-say");

    /** A {@code commits} at or above this is no answer a forge's copy can give. */
    static final long MOST_COMMITS = 1_000_000;

    /** Longest {@code why} taken, in code points. */
    static final int LONGEST_WHY = 200;

    /** Why recorded where the host's answer was refused. */
    static final String REFUSED = "host-answer-refused";

    /** Why recorded where the host's file could not be read or trusted. */
    static final String FAILED = "host-command-failed";

    private static final Set<String> ANSWER_FIELDS = Set.of("project", "read_at", "repositories");

    private static final Set<String> ENTRY_FIELDS = Set.of("repository", "state", "commits", "why");

    /**
     * What the host said of one repository.
     *
     * @param state {@code up-to-date}, {@code ahead}, {@code moved-on}, {@code cannot-say} or
     *     {@code no-central}.
     * @param ahead The commits for {@code ahead}, 0 for {@code up-to-date}, else {@code null}.
     * @param readAt When the host's follow read it, or {@code null} where nothing was read.
     * @param why Why it cannot say, or {@code null}.
     * @param keepsEarlier Whether the answer was refused or could not be had, so what was recorded
     *     before of how far ahead stays.
     */
    record Answer(
            String state,
            @Nullable Integer ahead,
            @Nullable Instant readAt,
            @Nullable String why,
            boolean keepsEarlier) {

        /** An answer that could not be taken, for the reason given. */
        static Answer notTaken(final String why) {
            return new Answer("cannot-say", null, null, why, true);
        }
    }

    private final Path directory;

    private final RootOwnedFile.Attributes attributes;

    /**
     * Constructor.
     *
     * @param directory Where the host publishes {@code <project>.json}.
     * @param attributes Checks the file and every parent before it is read: root's, in directories
     *     only root may write, with no symlink.
     */
    HostCentral(final Path directory, final RootOwnedFile.Attributes attributes) {
        this.directory = directory;
        this.attributes = attributes;
    }

    /**
     * Tells whether a project name may name a central-state file.
     *
     * @param project The project name.
     * @return {@code true} when it is a single valid project name.
     */
    static boolean validProject(final String project) {
        return PROJECT_PATTERN.matcher(project).matches();
    }

    /**
     * Reads the host's published central state once for a project.
     *
     * @param project The project.
     * @param carried The repositories the host's file carries, each with the name of its copy on
     *     the host's forge, or {@code null} for one with no central address.
     * @return An answer for each of them.
     */
    Map<String, Answer> ask(final String project, final Map<String, @Nullable String> carried) {
        final String output = readFile(project);
        if (output == null) {
            return all(carried, Answer.notTaken(FAILED));
        }
        final Map<String, Answer> read = read(output, project, carried);
        return read == null ? all(carried, Answer.notTaken(REFUSED)) : read;
    }

    /**
     * Reads and judges the host's published central state for {@code porelo host central-state}.
     *
     * @param project The project name.
     * @return The judged answer map, or a {@code cannot-say} map naming {@link #FAILED} or {@link
     *     #REFUSED}.
     */
    Map<String, Object> centralState(final String project) {
        final String output = readFile(project);
        if (output == null) {
            return cannotSay(project, FAILED);
        }
        final Map<String, Object> judged = readStandalone(output, project);
        return judged == null ? cannotSay(project, REFUSED) : judged;
    }

    private @Nullable String readFile(final String project) {
        if (!validProject(project)) {
            return null;
        }
        final Path file = directory.resolve(project + ".json");
        final byte[] bytes;
        try {
            bytes = RootOwnedFile.read(file, MAXIMUM_BYTES, attributes, 0);
        } catch (final IOException | IllegalArgumentException ex) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException ex) {
            return "";
        }
    }

    private static Map<String, Object> cannotSay(final String project, final String why) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("project", project);
        map.put("state", "cannot-say");
        map.put("why", why);
        map.put("repositories", List.of());
        return Map.copyOf(map);
    }

    /**
     * Judges a host's answer when no project file is open alongside it.
     *
     * @param output What the file held.
     * @param project The project asked for.
     * @return The judged answer map, or {@code null} where the answer is refused.
     */
    static @Nullable Map<String, Object> readStandalone(final String output, final String project) {
        final Object parsed;
        try {
            parsed = Json.parseExact(output.strip());
        } catch (final RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> answer)
                || !ANSWER_FIELDS.containsAll(answer.keySet())
                || !project.equals(answer.get("project"))
                || !(answer.get("read_at") instanceof String text)
                || !(answer.get("repositories") instanceof List<?> entries)) {
            return null;
        }
        final Instant readAt = utc(text);
        if (readAt == null) {
            return null;
        }
        final Set<String> seen = new HashSet<>();
        final List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (final Object each : entries) {
            if (!(each instanceof Map<?, ?> entry)
                    || !(entry.get("repository") instanceof String named)
                    || !printable(named)
                    || !seen.add(named.toLowerCase(Locale.ROOT))) {
                return null;
            }
            final Answer judged = judged(entry, readAt);
            if (judged == null) {
                return null;
            }
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("repository", named);
            row.put("state", judged.state());
            if ("ahead".equals(judged.state()) && judged.ahead() != null) {
                row.put("commits", judged.ahead());
            } else if ("cannot-say".equals(judged.state()) && judged.why() != null) {
                row.put("why", judged.why());
            }
            rows.add(Map.copyOf(row));
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project);
        result.put("read_at", text);
        result.put("repositories", List.copyOf(rows));
        return Map.copyOf(result);
    }

    /**
     * Judges a host's answer as a whole.
     *
     * @param output What the published file held.
     * @param project The project asked for.
     * @param carried The repositories carried, each with its copy's name or {@code null}.
     * @return An answer for each carried repository, or {@code null} where the answer is refused.
     */
    static @Nullable Map<String, Answer> read(
            final String output,
            final String project,
            final Map<String, @Nullable String> carried) {
        final Object parsed;
        try {
            parsed = Json.parseExact(output.strip());
        } catch (final RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> answer)
                || !ANSWER_FIELDS.containsAll(answer.keySet())
                || !project.equals(answer.get("project"))
                || !(answer.get("read_at") instanceof String text)
                || !(answer.get("repositories") instanceof List<?> entries)) {
            return null;
        }
        final Instant readAt = utc(text);
        if (readAt == null) {
            return null;
        }
        final Map<String, Answer> found = new LinkedHashMap<>();
        for (final Object each : entries) {
            if (!(each instanceof Map<?, ?> entry)
                    || !(entry.get("repository") instanceof String named)) {
                return null;
            }
            final List<String> matching = matching(carried, named);
            // None carried, or two rows for one entry, or two entries for one row: no repository
            // can be named for it.
            if (matching.size() != 1 || found.containsKey(matching.getFirst())) {
                return null;
            }
            final Answer judged = judged(entry, readAt);
            if (judged == null) {
                return null;
            }
            found.put(matching.getFirst(), judged);
        }
        final Map<String, Answer> all = new LinkedHashMap<>();
        for (final Map.Entry<String, @Nullable String> row : carried.entrySet()) {
            final Answer said = found.get(row.getKey());
            if (row.getValue() == null) {
                // The host lists no copy it does not keep; the project file says why.
                all.put(row.getKey(), new Answer("no-central", null, readAt, null, false));
            } else if (said != null) {
                all.put(row.getKey(), said);
            } else if (entries.isEmpty()) {
                // The host could not say of any of them.
                all.put(row.getKey(), new Answer("cannot-say", null, readAt, null, false));
            } else {
                all.put(row.getKey(), new Answer("cannot-say", null, readAt, "unreachable", false));
            }
        }
        return all;
    }

    /** The carried repositories whose copy has this name on the forge, ignoring case. */
    private static List<String> matching(
            final Map<String, @Nullable String> carried, final String named) {
        final List<String> matching = new java.util.ArrayList<>();
        for (final Map.Entry<String, @Nullable String> row : carried.entrySet()) {
            final String copy = row.getValue();
            if (copy != null && copy.equalsIgnoreCase(named)) {
                matching.add(row.getKey());
            }
        }
        return matching;
    }

    /** One entry, judged; {@code null} where it says what no host may. */
    private static @Nullable Answer judged(final Map<?, ?> entry, final Instant readAt) {
        if (!ENTRY_FIELDS.containsAll(entry.keySet())
                || !(entry.get("state") instanceof String state)
                || !STATES.contains(state)) {
            return null;
        }
        final Integer ahead;
        if ("ahead".equals(state)) {
            if (!(entry.get("commits") instanceof Long number
                    && number >= 0
                    && number < MOST_COMMITS)) {
                return null;
            }
            ahead = Math.toIntExact(number);
        } else if (entry.containsKey("commits")) {
            return null;
        } else {
            ahead = "up-to-date".equals(state) ? 0 : null;
        }
        final String why;
        if (!entry.containsKey("why")) {
            why = null;
        } else if ("cannot-say".equals(state)
                && entry.get("why") instanceof String text
                && printable(text)) {
            why = text;
        } else {
            return null;
        }
        return new Answer(state, ahead, readAt, why, false);
    }

    /** A UTC instant as the host writes it, or {@code null}. */
    private static @Nullable Instant utc(final String text) {
        if (!text.endsWith("Z")) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (final DateTimeParseException ex) {
            return null;
        }
    }

    /** One short line a person can read: no control, separator or format character. */
    private static boolean printable(final String text) {
        return !text.isEmpty()
                && text.codePointCount(0, text.length()) <= LONGEST_WHY
                && text.codePoints().noneMatch(HostCentral::unreadable);
    }

    private static boolean unreadable(final int point) {
        final int type = Character.getType(point);
        return Character.isISOControl(point)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT
                || type == Character.UNASSIGNED
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE;
    }

    private static Map<String, Answer> all(
            final Map<String, @Nullable String> carried, final Answer answer) {
        final Map<String, Answer> all = new LinkedHashMap<>();
        // A row with no central has no copy, so no host's failure makes it one that cannot say.
        carried.forEach(
                (repository, copy) ->
                        all.put(
                                repository,
                                copy == null
                                        ? new Answer("no-central", null, null, null, false)
                                        : answer));
        return all;
    }

    /**
     * Returns the name a host's forge gives a repository's copy, its central's last part.
     *
     * @param project The project.
     * @param central The repository's central address, or {@code null}.
     * @return The name, or {@code null} for no copy.
     */
    static @Nullable String copyName(final String project, final @Nullable String central) {
        final String copy = HostForgeAddress.copyOf(project, central);
        return copy == null ? null : copy.substring(copy.indexOf('/') + 1);
    }
}
