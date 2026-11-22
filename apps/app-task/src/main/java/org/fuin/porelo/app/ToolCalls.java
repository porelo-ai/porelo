package org.fuin.porelo.app;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.agent.api.ToolEvent;
import org.jspecify.annotations.Nullable;

/**
 * Turns the tool events an agent reads out of one run's output into one entry per tool call, in
 * Porelo's own format, the shape of no provider.
 *
 * <p>A start is joined to its end by the call's id; a call in the background ends when its job
 * does, joined by the job's id, not when the call returned. An end is the stream's own time, else
 * the start's stream time plus the duration the end gives, else its line's arrival; a start is the
 * stream's own time, else the end's time less that duration, else its line's arrival. Each entry
 * says which ({@code stream}, {@code duration} or {@code arrival}), and no end is before its start.
 * A shell call's command line is masked here, before anything holds it, and no other text of the
 * stream is kept. Each entry names the sub-agent call it ran under, when that call was started
 * before it and is still open. A sub-agent named by an issue the task was started for carries that
 * issue, and so does every call under it, followed up through nested sub-agents; a sub-agent's own
 * tokens are those its first end gives.
 */
public final class ToolCalls {

    /** The agent answered which tool calls its output holds. */
    public static final String ASKED = "ASKED";

    /** The agent has no way to say: never read as no tool having run. */
    public static final String CANNOT_SAY = "CANNOT_SAY";

    /** Nothing was asked: no unattended run was read. */
    public static final String DID_NOT_ASK = "DID_NOT_ASK";

    /** A time the stream gave. */
    public static final String STREAM = "stream";

    /** A time taken from the other end of the call and its duration. */
    public static final String DURATION = "duration";

    /** The time the line was read. */
    public static final String ARRIVAL = "arrival";

    /** The most calls one run keeps, and one record over all its runs. */
    public static final int MOST = 10_000;

    /** The most characters of a call's id, its parent's and its tool's name. */
    public static final int MOST_ID = 128;

    private final String task;
    private final CommandMask mask;
    private final Set<String> work;
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Call> byId = new HashMap<>();
    private final Map<String, Call> byJob = new HashMap<>();

    /** The jobs whose end has been taken, so a repeated end changes nothing. */
    private final Set<String> endedJobs = new HashSet<>();

    private String asked = DID_NOT_ASK;
    private long dropped;

    /**
     * Creates the entries of one run.
     *
     * @param task The task's container name, which every entry names.
     * @param mask How a command line is masked for this task.
     * @param work The issues the task was started for: a sub-agent names no other.
     */
    public ToolCalls(final String task, final CommandMask mask, final Set<String> work) {
        this.task = task;
        this.mask = mask;
        this.work = Set.copyOf(work);
    }

    /**
     * Takes the agent's answer for a batch of lines.
     *
     * @param answer The events, or empty when the agent cannot say.
     * @param arrival When the lines were read, for a moment without the stream's own time.
     */
    public synchronized void read(final Optional<List<ToolEvent>> answer, final Instant arrival) {
        if (answer.isEmpty()) {
            asked = CANNOT_SAY;
            return;
        }
        if (!CANNOT_SAY.equals(asked)) {
            asked = ASKED;
        }
        for (final ToolEvent event : answer.get()) {
            // Ids reach the record and join its calls: one that is no short printable text is none.
            if (!printable(event.id(), false)) {
                continue;
            }
            if (event.phase() == ToolEvent.Phase.START) {
                started(event, arrival);
            } else {
                ended(event, arrival);
            }
        }
    }

    private void started(final ToolEvent event, final Instant arrival) {
        final Call known = byId.get(event.id());
        if (known != null) {
            // An agent may name a call's job only on a later line: the job then ends the call, and
            // an earlier return, before or after this line, does not.
            final String job = event.jobId();
            if (job != null && byJob.get(job) != known) {
                taken(job, known);
                known.background = true;
                known.end = null;
                known.endFrom = null;
            }
            return;
        }
        if (calls.size() >= MOST) {
            dropped++;
            return;
        }
        final Call parent = parent(event);
        final String command = event.command();
        final Call call =
                new Call(
                        event.id(),
                        printable(event.tool(), true) ? event.tool() : "",
                        event.kind().wire(),
                        time(event.at()),
                        arrival,
                        event.kind() == ToolEvent.Kind.SHELL && command != null
                                ? mask.mask(command)
                                : null,
                        event.background(),
                        parent,
                        event.kind() == ToolEvent.Kind.SUB_AGENT
                                ? issue(event.name(), parent)
                                : parent == null ? null : parent.issue);
        calls.add(call);
        byId.put(event.id(), call);
        final String job = event.jobId();
        if (event.background() && job != null) {
            taken(job, call);
        }
    }

    /** The open sub-agent call started before this one that the event names, or none. */
    private @Nullable Call parent(final ToolEvent event) {
        final String id = event.parent();
        if (id == null || id.equals(event.id()) || !printable(id, false)) {
            return null;
        }
        final Call parent = byId.get(id);
        return parent != null && parent.subAgent && parent.end == null ? parent : null;
    }

    /**
     * A sub-agent's issue: its own name where that is an issue of the task's work, as the work
     * names it, else the issue of the sub-agent it ran under.
     */
    private @Nullable String issue(final @Nullable String name, final @Nullable Call parent) {
        if (name != null && work.contains(name)) {
            return name;
        }
        return parent == null ? null : parent.issue;
    }

    /** A job's id an agent uses again is the later call's: its next end is that call's. */
    private void taken(final String job, final Call call) {
        byJob.put(job, call);
        endedJobs.remove(job);
    }

    private void ended(final ToolEvent event, final Instant arrival) {
        final String job = event.jobId();
        final boolean byItsJob = event.background() && job != null && byJob.containsKey(job);
        final Call call = byItsJob ? byJob.get(job) : byId.get(event.id());
        // A call in the background ends with its job, never when it returned.
        if (call == null || call.background && !(event.background() && job != null)) {
            return;
        }
        // A first end is its call's: an agent that repeats it would move it later.
        if (byItsJob ? !endedJobs.add(job) : call.end != null) {
            return;
        }
        final Long duration = event.durationMs();
        final Instant given = time(event.at());
        final Instant shifted =
                duration != null && call.streamStart != null
                        ? shifted(call.streamStart, duration)
                        : null;
        // The line that gave the end was read at its arrival: a duration past it is no length.
        final Instant fromDuration = shifted != null && !shifted.isAfter(arrival) ? shifted : null;
        final Instant end;
        if (given != null) {
            end = given;
            call.endFrom = STREAM;
        } else if (fromDuration != null) {
            end = fromDuration;
            call.endFrom = DURATION;
        } else {
            end = arrival;
            call.endFrom = ARRIVAL;
        }
        if (duration != null && call.streamStart == null) {
            final Instant start = shifted(end, -duration);
            if (start != null) {
                call.start = start;
                call.startFrom = DURATION;
            }
        }
        // A stream that ends a call before it started says nothing of its length.
        call.end = end.isBefore(call.start) ? call.start : end;
        if (call.subAgent && event.usage() != null) {
            call.tokens = event.usage();
        }
    }

    /** A time moved by a duration, or {@code null} where no time can hold the result. */
    private static @Nullable Instant shifted(final Instant from, final long millis) {
        try {
            return from.plusMillis(millis);
        } catch (DateTimeException | ArithmeticException ex) {
            return null;
        }
    }

    private static @Nullable Instant time(final @Nullable String given) {
        if (given != null) {
            try {
                return OffsetDateTime.parse(given).toInstant();
            } catch (DateTimeParseException ex) {
                // Not a time: another source is.
            }
        }
        return null;
    }

    /** Whether text is 1 to {@value #MOST_ID} characters of printable ASCII, a space only if so. */
    private static boolean printable(final String text, final boolean space) {
        final int lowest = space ? 0x20 : 0x21;
        return !text.isEmpty()
                && text.length() <= MOST_ID
                && text.chars().allMatch(c -> c >= lowest && c < 0x7f);
    }

    /**
     * Says whether the agent was asked and answered.
     *
     * @return {@link #ASKED}, {@link #CANNOT_SAY} or {@link #DID_NOT_ASK}.
     */
    public synchronized String asked() {
        return asked;
    }

    /**
     * Says how many calls the run started past {@value #MOST}, which no entry holds.
     *
     * @return The number.
     */
    public synchronized long dropped() {
        return dropped;
    }

    /**
     * Returns one entry per tool call, in the order they started.
     *
     * @return Each with {@code task}, {@code id} (the agent's own), {@code parent} (the id of the
     *     open sub-agent call it ran under, else null), {@code tool}, {@code kind}, {@code start}
     *     and {@code startFrom}, {@code end} and {@code endFrom} (null for one the run never
     *     ended), {@code command} (a shell call's, masked; else null), {@code background}, {@code
     *     issue} (null outside a sub-agent named by an issue of the task's work) and {@code tokens}
     *     (a sub-agent's, each kind null where its agent gave none; null for any other call and
     *     where its agent gave none at all).
     */
    public synchronized List<Map<String, Object>> entries() {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final Call call : calls) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task", task);
            entry.put("id", call.id);
            entry.put("parent", call.parent == null ? null : call.parent.id);
            entry.put("tool", call.tool);
            entry.put("kind", call.kind);
            entry.put("start", call.start.toString());
            entry.put("startFrom", call.startFrom);
            entry.put("end", call.end == null ? null : call.end.toString());
            entry.put("endFrom", call.endFrom);
            entry.put("command", call.command);
            entry.put("background", Boolean.valueOf(call.background));
            entry.put("issue", call.issue);
            entry.put("tokens", call.tokens == null ? null : tokens(call.tokens));
            entries.add(entry);
        }
        return entries;
    }

    private static Map<String, Object> tokens(final ToolEvent.Usage usage) {
        final Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", usage.input());
        tokens.put("cacheRead", usage.cacheRead());
        tokens.put("cacheWrite", usage.cacheWrite());
        tokens.put("output", usage.output());
        tokens.put("reasoning", usage.reasoning());
        return tokens;
    }

    /** One call while its run is read. */
    private static final class Call {
        private final String id;
        private final String tool;
        private final String kind;
        private final boolean subAgent;
        private final @Nullable Instant streamStart;
        private final @Nullable String command;
        private final @Nullable Call parent;
        private final @Nullable String issue;
        private boolean background;
        private Instant start;
        private String startFrom;
        private @Nullable Instant end;
        private @Nullable String endFrom;
        private ToolEvent.@Nullable Usage tokens;

        private Call(
                final String id,
                final String tool,
                final String kind,
                final @Nullable Instant streamStart,
                final Instant arrival,
                final @Nullable String command,
                final boolean background,
                final @Nullable Call parent,
                final @Nullable String issue) {
            this.id = id;
            this.tool = tool;
            this.kind = kind;
            this.subAgent = ToolEvent.Kind.SUB_AGENT.wire().equals(kind);
            this.streamStart = streamStart;
            this.start = streamStart != null ? streamStart : arrival;
            this.startFrom = streamStart != null ? STREAM : ARRIVAL;
            this.command = command;
            this.background = background;
            this.parent = parent;
            this.issue = issue;
        }
    }
}
