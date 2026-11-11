package org.fuin.porelo.app;

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
import java.util.regex.Pattern;
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
 * says which ({@code stream}, {@code duration} or {@code arrival}). A shell call's command line is
 * masked here, before anything holds it, and no other text of the stream is kept. A call that ran
 * under a sub-agent named by an issue's id carries that issue, followed up through nested
 * sub-agents.
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

    /** An issue's id as a project names one, such as an uppercase prefix and a number. */
    private static final Pattern ISSUE = Pattern.compile("[A-Z]{1,4}[0-9]{1,6}");

    private final String task;
    private final CommandMask mask;
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Call> byId = new HashMap<>();
    private final Map<String, Call> byJob = new HashMap<>();

    /** The jobs whose end has been taken, so a repeated end changes nothing. */
    private final java.util.Set<String> endedJobs = new java.util.HashSet<>();

    private final Map<String, String> subAgentNames = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private String asked = DID_NOT_ASK;

    /**
     * Creates the entries of one run.
     *
     * @param task The task's container name, which every entry names.
     * @param mask How a command line is masked for this task.
     */
    public ToolCalls(final String task, final CommandMask mask) {
        this.task = task;
        this.mask = mask;
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
        final String name = event.name();
        if (event.kind() == ToolEvent.Kind.SUB_AGENT && name != null) {
            subAgentNames.put(event.id(), name);
        }
        final String parent = event.parent();
        if (parent != null) {
            parents.put(event.id(), parent);
        }
        final String command = event.command();
        final Call call =
                new Call(
                        event.id(),
                        event.tool(),
                        event.kind().wire(),
                        time(event.at()),
                        arrival,
                        event.kind() == ToolEvent.Kind.SHELL && command != null
                                ? mask.mask(command)
                                : null,
                        event.background());
        calls.add(call);
        byId.put(event.id(), call);
        final String job = event.jobId();
        if (event.background() && job != null) {
            taken(job, call);
        }
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
        final Instant end;
        if (given != null) {
            end = given;
            call.endFrom = STREAM;
        } else if (duration != null && call.streamStart != null) {
            end = call.streamStart.plusMillis(duration);
            call.endFrom = DURATION;
        } else {
            end = arrival;
            call.endFrom = ARRIVAL;
        }
        call.end = end;
        if (duration != null && call.streamStart == null) {
            call.start = end.minusMillis(duration);
            call.startFrom = DURATION;
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

    /**
     * Says whether the agent was asked and answered.
     *
     * @return {@link #ASKED}, {@link #CANNOT_SAY} or {@link #DID_NOT_ASK}.
     */
    public synchronized String asked() {
        return asked;
    }

    /**
     * Returns one entry per tool call, in the order they started.
     *
     * @return Each with {@code task}, {@code tool}, {@code kind}, {@code start} and {@code
     *     startFrom}, {@code end} and {@code endFrom} (null for one the run never ended), {@code
     *     command} (a shell call's, masked; else null), {@code background} and {@code issue} (null
     *     outside a sub-agent named by an issue).
     */
    public synchronized List<Map<String, Object>> entries() {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final Call call : calls) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task", task);
            entry.put("tool", call.tool);
            entry.put("kind", call.kind);
            entry.put("start", call.start.toString());
            entry.put("startFrom", call.startFrom);
            entry.put("end", call.end == null ? null : call.end.toString());
            entry.put("endFrom", call.endFrom);
            entry.put("command", call.command);
            entry.put("background", Boolean.valueOf(call.background));
            entry.put("issue", issue(call.id));
            entries.add(entry);
        }
        return entries;
    }

    private @Nullable String issue(final String id) {
        final Set<String> seen = new HashSet<>();
        String parent = parents.get(id);
        while (parent != null && seen.add(parent)) {
            final String name = subAgentNames.get(parent);
            if (name != null && ISSUE.matcher(name).matches()) {
                return name;
            }
            parent = parents.get(parent);
        }
        return null;
    }

    /** One call while its run is read. */
    private static final class Call {
        private final String id;
        private final String tool;
        private final String kind;
        private final @Nullable Instant streamStart;
        private final @Nullable String command;
        private boolean background;
        private Instant start;
        private String startFrom;
        private @Nullable Instant end;
        private @Nullable String endFrom;

        private Call(
                final String id,
                final String tool,
                final String kind,
                final @Nullable Instant streamStart,
                final Instant arrival,
                final @Nullable String command,
                final boolean background) {
            this.id = id;
            this.tool = tool;
            this.kind = kind;
            this.streamStart = streamStart;
            this.start = streamStart != null ? streamStart : arrival;
            this.startFrom = streamStart != null ? STREAM : ARRIVAL;
            this.command = command;
            this.background = background;
        }
    }
}
