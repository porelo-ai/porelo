package org.fuin.porelo.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.fuin.porelo.agent.api.AgentEnd;
import org.fuin.porelo.agent.api.Waiting;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.runtime.Podman;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Says how a task's agent ended its run, when it has.
 *
 * <p>A provider answered 402 and the agent ended; the task said "idle", then "working", and nothing
 * pointed the person at what had happened. The agent reads its own end (each ends its output its
 * own way); this asks it about the last lines of the task's log, and adds what the workspace holds
 * that never reached the gate - an agent can finish its report and end before it pushes.
 *
 * <p><strong>Asked only of a quiet log, and once per size.</strong> A log still growing is a run
 * still going, and asking on every pass would start every agent twice a second for every running
 * task.
 *
 * <p><strong>What it answered, from the same lines.</strong> The record the agent's definition
 * declares as its last message is taken from the lines read for its end, so a person or a client
 * reads the answer without a second read of the log.
 */
public final class AgentEnding {

    /** How long a log must have been still before its end is asked for. */
    static final Duration QUIET = Duration.ofSeconds(10);

    /**
     * How much of a log's end is read for its last lines. An agent's end line can carry every
     * message of its run, tool output included, so 64 KiB cut it and the end was never read; each
     * line goes to the agent in one varlink message of at most 4 MiB, which leaves room for its
     * escaping.
     */
    static final int TAIL = 2 * 1024 * 1024;

    /** How many lines from the end are offered to the agent. */
    static final int LINES = 40;

    /**
     * The most of an answer that is kept, in UTF-8 bytes: its end, where a work order asks for the
     * last line.
     */
    static final int ANSWER = 16 * 1024;

    /** Asks an agent which of its last lines is the end of its run. */
    @FunctionalInterface
    public interface Reader {

        /**
         * Reads lines for an end.
         *
         * @param agent The agent's name.
         * @param fromEnd Lines of its output, the last first.
         * @return How its run ended, or {@code null} when none of them is its end.
         */
        @Nullable AgentEnd ended(String agent, List<String> fromEnd);
    }

    /**
     * How a task's agent ended.
     *
     * @param at When its log was last written, ISO 8601.
     * @param finished Whether it answered its prompt to the end.
     * @param error Empty when finished; else the text as the agent or its provider gave it.
     * @param source {@code provider} or {@code agent}, or {@code porelo} when porelo ended it.
     * @param status The provider's HTTP status, when the provider refused.
     * @param unpushed Changed files and commits in the workspace that never reached the gate.
     * @param answer The agent's last message, as the record its definition declares gives it; ""
     *     when it declares none or none was written. At most its last {@link #ANSWER} bytes.
     * @param answerCut Whether the answer was longer and only its end is kept.
     * @param limit Whether porelo ended it because its time limit passed.
     */
    public record Ended(
            String at,
            boolean finished,
            String error,
            String source,
            @Nullable Integer status,
            int unpushed,
            String answer,
            boolean answerCut,
            boolean limit) {

        /** An end its agent said, not one porelo made at a time limit. */
        public Ended(
                String at,
                boolean finished,
                String error,
                String source,
                @Nullable Integer status,
                int unpushed,
                String answer,
                boolean answerCut) {
            this(at, finished, error, source, status, unpushed, answer, answerCut, false);
        }

        /**
         * Returns it as an interface reads it.
         *
         * @return The fields; the status only when there is one.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", at);
            map.put("finished", finished);
            map.put("error", error);
            map.put("source", source);
            if (status != null) {
                map.put("status", status);
            }
            map.put("unpushed", unpushed);
            map.put("answer", answer);
            map.put("answerCut", answerCut);
            map.put("limit", limit);
            return map;
        }
    }

    private record Seen(long size, @Nullable Ended ended) {}

    private final Podman podman;

    private final Clock clock;

    private final Reader reader;

    private final Function<String, Waiting.@Nullable LastMessage> declared;

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /**
     * Creates it.
     *
     * @param runner Runs podman, for the workspace's census.
     * @param clock The time.
     * @param reader Asks an agent about its lines.
     */
    public AgentEnding(CommandRunner runner, Clock clock, Reader reader) {
        this(runner, clock, reader, agent -> null);
    }

    /**
     * Creates it with where each agent declares its last message.
     *
     * @param runner Runs podman, for the workspace's census.
     * @param clock The time.
     * @param reader Asks an agent about its lines.
     * @param declared The record an agent declares as its last message, by its name, or {@code
     *     null} when it declares none.
     */
    public AgentEnding(
            CommandRunner runner,
            Clock clock,
            Reader reader,
            Function<String, Waiting.@Nullable LastMessage> declared) {
        this.podman = new Podman(runner);
        this.clock = clock;
        this.reader = reader;
        this.declared = declared;
    }

    /**
     * Creates it for a machine, asking its installed agents.
     *
     * @param context The machine.
     * @return The reading.
     */
    public static AgentEnding of(PoreloContext context) {
        return new AgentEnding(
                context.runner(),
                Clock.systemUTC(),
                (agent, fromEnd) -> {
                    try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
                        final org.fuin.porelo.agent.api.InstalledAgent installed =
                                agents.find(agent).orElse(null);
                        if (installed == null) {
                            return null;
                        }
                        return lastEnd(installed::ended, fromEnd);
                    } catch (RuntimeException ex) {
                        // An agent that cannot be asked says nothing about its end, as before.
                        return null;
                    }
                },
                agent -> {
                    try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
                        final org.fuin.porelo.agent.api.InstalledAgent installed =
                                agents.find(agent).orElse(null);
                        final Waiting waiting =
                                installed == null ? null : installed.definition().waiting();
                        return waiting == null ? null : waiting.lastMessage();
                    } catch (RuntimeException ex) {
                        // An agent that will not describe itself declares no last message.
                        return null;
                    }
                });
    }

    /**
     * Finds the end of a run among a log's last lines.
     *
     * @param asking Asks the agent about one line.
     * @param fromEnd The lines, the last one first.
     * @return How it ended, or {@code null} when no line says.
     */
    static @Nullable AgentEnd lastEnd(
            java.util.function.Function<String, @Nullable AgentEnd> asking, List<String> fromEnd) {
        for (final String line : fromEnd) {
            try {
                final AgentEnd ended = asking.apply(line);
                if (ended != null) {
                    return ended;
                }
            } catch (VarlinkException ex) {
                // The agent answered that it failed on this line, so it can still read the others.
                // No error name means no answer: the connection is gone, and so is every other try.
                if (ex.getErrorName() == null) {
                    throw ex;
                }
            }
        }
        return null;
    }

    /**
     * Says how a task's agent ended, if it has.
     *
     * @param container The task.
     * @param running Whether its container is up: the workspace is asked only then.
     * @param agent Its agent's name, or {@code null} when it has none.
     * @param state Its state directory, holding {@code task.log}.
     * @return How it ended, or {@code null} while it runs, or when its agent does not say.
     */
    public @Nullable Ended about(
            String container, boolean running, @Nullable String agent, Path state) {
        // Porelo's own end comes first: the agent was stopped mid-run, so its log says nothing.
        final RunLimit.Ended byLimit = RunLimit.endedBy(state);
        if (byLimit != null) {
            return new Ended(
                    byLimit.at(),
                    false,
                    "its time limit of " + byLimit.minutes() + " minutes passed",
                    "porelo",
                    null,
                    unpushed(container, running, state),
                    "",
                    false,
                    true);
        }
        final Path log = state.resolve("task.log");
        if (agent == null || !Files.isRegularFile(log)) {
            return null;
        }
        final long size;
        final java.time.Instant written;
        try {
            size = Files.size(log);
            written = Files.getLastModifiedTime(log).toInstant();
        } catch (IOException ex) {
            return null;
        }
        if (written.isAfter(clock.instant().minus(QUIET))) {
            return null;
        }
        final Seen known = seen.get(container);
        if (known != null && known.size() == size) {
            return known.ended();
        }
        final List<String> lines = wholeLines(log, size);
        final AgentEnd end = reader.ended(agent, fromEnd(lines));
        final Answer answer = end == null ? Answer.NONE : answer(agent, lines);
        final Ended ended =
                end == null
                        ? null
                        : new Ended(
                                written.toString(),
                                end.finished(),
                                end.error(),
                                end.source(),
                                end.status(),
                                unpushed(container, running, state),
                                answer.text(),
                                answer.cut());
        seen.put(container, new Seen(size, ended));
        return ended;
    }

    private int unpushed(String container, boolean running, Path state) {
        // A stopped task is not asked: what it held was written down when it stopped.
        final UnhandedWork.Held held =
                running ? UnhandedWork.ask(podman, container) : UnhandedWork.read(state);
        return held.readable() ? held.changedFiles() + held.unpushedCommits() : 0;
    }

    private record Answer(String text, boolean cut) {

        static final Answer NONE = new Answer("", false);
    }

    private Answer answer(String agent, List<String> lines) {
        final Waiting.LastMessage where = declared.apply(agent);
        if (where == null) {
            return Answer.NONE;
        }
        final List<Object> records = new ArrayList<>();
        for (final String line : lines) {
            try {
                final Object record = Json.parse(line);
                if (record != null) {
                    records.add(record);
                }
            } catch (RuntimeException notARecord) {
                // A line that is not a record - an error printed around the stream - says nothing
                // here.
            }
        }
        final String said = where.of(records);
        return said == null ? Answer.NONE : kept(said);
    }

    /**
     * Keeps the end of an answer, at most {@link #ANSWER} bytes of its UTF-8, starting at a whole
     * character.
     */
    private static Answer kept(String said) {
        final byte[] bytes = said.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= ANSWER) {
            return new Answer(said, false);
        }
        int from = bytes.length - ANSWER;
        // A continuation byte is the middle of a character: start at the next whole one.
        while (from < bytes.length && (bytes[from] & 0xC0) == 0x80) {
            from++;
        }
        return new Answer(
                new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8), true);
    }

    private static List<String> fromEnd(List<String> lines) {
        final List<String> fromEnd = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && fromEnd.size() < LINES; i--) {
            if (!lines.get(i).isBlank()) {
                fromEnd.add(lines.get(i));
            }
        }
        return fromEnd;
    }

    private static List<String> wholeLines(Path log, long size) {
        final int length = (int) Math.min(size, TAIL);
        final byte[] tail = new byte[length];
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            file.seek(size - length);
            file.readFully(tail);
        } catch (IOException ex) {
            return List.of();
        }
        final List<String> lines = List.of(new String(tail, StandardCharsets.UTF_8).split("\n"));
        // The first line of a cut read may be the end of a longer one: only whole lines are
        // offered.
        return size > length && !lines.isEmpty() ? lines.subList(1, lines.size()) : lines;
    }
}
