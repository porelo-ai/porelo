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

    private static final Set<String> ANSWER_FIELDS =
            Set.of("project", "read_at", "repositories", "state", "why", "host_repositories");

    private static final Set<String> PROJECT_STATES =
            Set.of("pending", "carried", "waiting-for-token", "held", "refused");

    private static final Set<String> REPOSITORY_STATES =
            Set.of("taken", "waiting", "not-carried", "held", "no-central");

    private static final Set<String> HOST_ENTRY_FIELDS = Set.of("repository", "state", "why");

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

    /** The follow's progress, independently of its last distance from central. */
    record Progress(String state, @Nullable String why) {}

    /** A valid legacy file permits placement's answer; a refused file never does. */
    record Status(
            @Nullable String state,
            @Nullable String why,
            Map<String, Progress> repositories,
            boolean repositoriesKnown) {
        boolean permitsCarrying() {
            return state == null || state.equals("carried");
        }

        Progress repository(String name) {
            if (!permitsCarrying()) {
                return new Progress(
                        java.util.Objects.requireNonNull(
                                state, "A withheld project has a host state"),
                        why);
            }
            return repositories.getOrDefault(
                    name,
                    new Progress(
                            repositoriesKnown ? "not-carried" : state == null ? "" : state, why));
        }

        boolean permitsRepository(String name) {
            final Progress progress = repositories.get(name);
            return permitsCarrying()
                    && (progress == null ? !repositoriesKnown : progress.state().equals("taken"));
        }
    }

    private record Publication(Map<String, Object> values, Status status, List<Measurement> rows) {}

    private record Measurement(String name, Answer answer) {}

    private static final class RefusedAnswer extends IllegalArgumentException {
        RefusedAnswer(String field) {
            super(REFUSED + ": " + safe(field, LONGEST_WHY - REFUSED.length() - 2));
        }

        @Override
        public String getMessage() {
            return java.util.Objects.requireNonNull(
                    super.getMessage(), "Every refused answer names its field");
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
        try {
            return answers(parse(output, project), carried);
        } catch (RefusedAnswer ex) {
            return all(carried, Answer.notTaken(ex.getMessage()));
        }
    }

    /** Read the same checked publication for the inventory, without reaching a forge. */
    Status status(final String project) {
        final String output = readFile(project);
        if (output == null) {
            return new Status("cannot-say", FAILED, Map.of(), false);
        }
        try {
            return parse(output, project).status();
        } catch (RefusedAnswer ex) {
            return new Status("cannot-say", ex.getMessage(), Map.of(), false);
        }
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
        try {
            return parse(output, project).values();
        } catch (RefusedAnswer ex) {
            return cannotSay(project, ex.getMessage());
        }
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
        try {
            return parse(output, project).values();
        } catch (RefusedAnswer ex) {
            return null;
        }
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
        try {
            return answers(parse(output, project), carried);
        } catch (RefusedAnswer ex) {
            return null;
        }
    }

    private static Map<String, Answer> answers(
            Publication publication, Map<String, @Nullable String> carried) {
        final Instant readAt = Instant.parse((String) publication.values().get("read_at"));
        final Map<String, Answer> found = new LinkedHashMap<>();
        for (final Measurement each : publication.rows()) {
            final List<String> matching = matching(carried, each.name());
            if (matching.isEmpty() && "cannot-say".equals(each.answer().state())) {
                // A repository the host's forge keeps and no one carries here, such as the
                // project's own, listed as one it has no read key for: it says nothing of the
                // carried ones. A state measured for it would be one no host may give.
                continue;
            }
            // Two rows for one entry, or two entries for one row: no repository can be named.
            if (matching.size() != 1 || found.containsKey(matching.getFirst())) {
                throw new RefusedAnswer(
                        "repositories.repository: unmatched or ambiguous " + each.name());
            }
            found.put(matching.getFirst(), each.answer());
        }
        final Map<String, Answer> all = new LinkedHashMap<>();
        for (final Map.Entry<String, @Nullable String> row : carried.entrySet()) {
            final Answer said = found.get(row.getKey());
            if (row.getValue() == null) {
                // The host lists no copy it does not keep; the project file says why.
                all.put(row.getKey(), new Answer("no-central", null, readAt, null, false));
            } else if (said != null) {
                all.put(row.getKey(), said);
            } else if (publication.rows().isEmpty()) {
                // The host could not say of any of them.
                all.put(row.getKey(), new Answer("cannot-say", null, readAt, null, false));
            } else {
                all.put(row.getKey(), new Answer("cannot-say", null, readAt, "unreachable", false));
            }
        }
        return all;
    }

    private static Publication parse(String output, String project) {
        if (output.length() > MAXIMUM_BYTES) {
            throw new RefusedAnswer("answer: exceeds size bound");
        }
        final Object parsed;
        try {
            parsed = Json.parseExact(output.strip());
        } catch (RuntimeException ex) {
            throw new RefusedAnswer("answer: invalid JSON or UTF-8");
        }
        final Map<?, ?> answer = object(parsed, "answer");
        if (!project.equals(text(answer.get("project"), "project", 40))) {
            throw new RefusedAnswer("project: does not match requested project");
        }
        final String at = text(answer.get("read_at"), "read_at", 64);
        final Instant readAt = utc(at);
        if (readAt == null) {
            throw new RefusedAnswer("read_at: expected a UTC instant");
        }
        fields(answer, ANSWER_FIELDS, "answer");
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("project", project);
        values.put("read_at", at);
        final List<Measurement> measurements = new ArrayList<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final List<?> entries = list(answer.get("repositories"), "repositories");
        for (int i = 0; i < entries.size(); i++) {
            final String field = "repositories[" + i + "]";
            final Map<?, ?> entry = object(entries.get(i), field);
            final String named = text(entry.get("repository"), field + ".repository", LONGEST_WHY);
            if (!seen.add(named.toLowerCase(Locale.ROOT))) {
                throw new RefusedAnswer(field + ".repository: duplicate " + named);
            }
            final Answer judged = judged(entry, readAt, field);
            measurements.add(new Measurement(named, judged));
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("repository", named);
            row.put("state", judged.state());
            if ("ahead".equals(judged.state()) && judged.ahead() != null) {
                row.put("commits", judged.ahead());
            }
            if (judged.why() != null) {
                row.put("why", judged.why());
            }
            rows.add(Map.copyOf(row));
        }
        values.put("repositories", List.copyOf(rows));
        final String state =
                answer.containsKey("state")
                        ? state(answer.get("state"), PROJECT_STATES, "state")
                        : null;
        final String why = reason(answer, "why");
        if (state == null
                && (answer.containsKey("why") || answer.containsKey("host_repositories"))) {
            throw new RefusedAnswer("state: required with host progress");
        }
        final Map<String, Progress> progress = new LinkedHashMap<>();
        if (answer.containsKey("host_repositories")) {
            final List<?> statuses = list(answer.get("host_repositories"), "host_repositories");
            final List<Map<String, Object>> hostRows = new ArrayList<>();
            for (int i = 0; i < statuses.size(); i++) {
                final String field = "host_repositories[" + i + "]";
                final Map<?, ?> row = object(statuses.get(i), field);
                fields(row, HOST_ENTRY_FIELDS, field);
                final String name = text(row.get("repository"), field + ".repository", 96);
                final String status = state(row.get("state"), REPOSITORY_STATES, field + ".state");
                final String detail = reason(row, field + ".why");
                if (progress.putIfAbsent(name, new Progress(status, detail)) != null) {
                    throw new RefusedAnswer(field + ".repository: duplicate " + name);
                }
                final Map<String, Object> hostRow = new LinkedHashMap<>();
                hostRow.put("repository", name);
                hostRow.put("state", status);
                if (detail != null) hostRow.put("why", detail);
                hostRows.add(Map.copyOf(hostRow));
            }
            values.put("host_repositories", List.copyOf(hostRows));
        }
        if (state != null) values.put("state", state);
        if (why != null) values.put("why", why);
        return new Publication(
                Map.copyOf(values),
                new Status(
                        state, why, Map.copyOf(progress), answer.containsKey("host_repositories")),
                List.copyOf(measurements));
    }

    private static Map<?, ?> object(@Nullable Object value, String field) {
        if (value instanceof Map<?, ?> map) return map;
        throw new RefusedAnswer(field + ": expected an object");
    }

    private static List<?> list(@Nullable Object value, String field) {
        if (value instanceof List<?> list) return list;
        throw new RefusedAnswer(field + ": expected an array");
    }

    private static String text(@Nullable Object value, String field, int maximum) {
        if (value instanceof String text
                && text.codePointCount(0, text.length()) <= maximum
                && printable(text)) return text;
        throw new RefusedAnswer(field + ": expected bounded printable text");
    }

    private static String state(@Nullable Object value, Set<String> states, String field) {
        final String text = text(value, field, 32);
        if (!states.contains(text)) throw new RefusedAnswer(field + ": unknown value " + text);
        return text;
    }

    private static @Nullable String reason(Map<?, ?> row, String field) {
        return row.get("why") == null ? null : text(row.get("why"), field, LONGEST_WHY);
    }

    private static void fields(Map<?, ?> row, Set<String> allowed, String field) {
        for (Object key : row.keySet()) {
            if (!allowed.contains(key))
                throw new RefusedAnswer(
                        field + "." + safe(String.valueOf(key), 80) + ": unknown field");
        }
    }

    private static String safe(String value, int maximum) {
        final StringBuilder shown = new StringBuilder();
        value.codePoints()
                .limit(maximum)
                .forEach(point -> shown.appendCodePoint(unreadable(point) ? '?' : point));
        return shown.toString();
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

    /** A central measurement, refused whole when any field is invalid. */
    private static Answer judged(final Map<?, ?> entry, final Instant readAt, final String field) {
        fields(entry, ENTRY_FIELDS, field);
        final String state = state(entry.get("state"), STATES, field + ".state");
        final Integer ahead;
        if ("ahead".equals(state)) {
            if (!(entry.get("commits") instanceof Long number
                    && number >= 0
                    && number < MOST_COMMITS)) {
                throw new RefusedAnswer(field + ".commits: expected a count from 0 through 999999");
            }
            ahead = Math.toIntExact(number);
        } else if (entry.containsKey("commits")) {
            throw new RefusedAnswer(field + ".commits: unavailable for " + state);
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
            throw new RefusedAnswer(field + ".why: expected bounded printable text for cannot-say");
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
