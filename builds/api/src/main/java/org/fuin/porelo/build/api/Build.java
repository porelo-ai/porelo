package org.fuin.porelo.build.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the forge said about the build of one commit.
 *
 * @param verdict One of {@link #VERDICTS}.
 * @param jobs Every job in the forge's order once the verdict is failure or final. Empty while the
 *     build is queued or running. At most {@link #MOST_JOBS}, their logs together at most {@link
 *     #MOST_LOG_BYTES}; {@link Logs} decides which carry a log.
 * @param detail Why the verdict is {@link #UNKNOWN}, or what more the forge said; "" otherwise.
 */
public record Build(String verdict, List<Job> jobs, String detail, List<Run> runs) {

    /** The most runs one build names, so a forge cannot flood a task or a reply. */
    public static final int MOST_RUNS = 50;

    /** The most code points of a job's or a run's name or result. */
    public static final int MOST_TEXT = 200;

    /**
     * One run of a workflow or pipeline for the commit, and what became of it or its state while it
     * is not finished.
     *
     * @param name As the forge spells it, one line of printable text.
     * @param result Lower case: the conclusion once finished, else "queued", "in_progress", ...
     */
    public record Run(String name, String result) {

        /**
         * Constructor keeping each text one line of printable text, the result in lower case.
         *
         * @param name Its name.
         * @param result What became of it.
         */
        public Run {
            name = line(name);
            result = line(result).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Returns a reader's text as one line Porelo may write: control and format characters, line
     * breaks among them, become a space; stripped and cut at {@link #MOST_TEXT} code points.
     *
     * <p>The name is the repository's to write: a workflow named {@code x\nverdict: success} wrote
     * a forged line into the file a task reads.
     *
     * @param text What the reader said.
     * @return It as one line.
     */
    static String line(final @Nullable String text) {
        final String stripped = flat(text);
        return stripped.codePointCount(0, stripped.length()) <= MOST_TEXT
                ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, MOST_TEXT));
    }

    /**
     * Returns a text as one line, uncut: control and format characters, line breaks among them,
     * become a space.
     *
     * @param text What the reader said.
     * @return It as one line, stripped.
     */
    static String flat(final @Nullable String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder kept = new StringBuilder(Math.min(text.length(), MOST_TEXT * 2));
        text.codePoints()
                .map(
                        point ->
                                Character.isISOControl(point)
                                                || Character.getType(point) == Character.FORMAT
                                                || Character.getType(point)
                                                        == Character.LINE_SEPARATOR
                                                || Character.getType(point)
                                                        == Character.PARAGRAPH_SEPARATOR
                                        ? ' '
                                        : point)
                .forEach(kept::appendCodePoint);
        return kept.toString().strip();
    }

    /** Not started yet. */
    public static final String QUEUED = "queued";

    /** Started, not finished. */
    public static final String RUNNING = "running";

    /** Finished, and every run of the commit succeeded. */
    public static final String SUCCESS = "success";

    /** Finished, and a run failed. */
    public static final String FAILURE = "failure";

    /** Finished because somebody cancelled it. */
    public static final String CANCELLED = "cancelled";

    /**
     * The forge holds no build of the commit, or none Porelo can read a verdict from; 'detail' says
     * which.
     */
    public static final String UNKNOWN = "unknown";

    /** Every verdict, in the order a build passes through them. */
    public static final List<String> VERDICTS =
            List.of(QUEUED, RUNNING, SUCCESS, FAILURE, CANCELLED, UNKNOWN);

    /**
     * The most of a log's end a reader hands over, per job: enough for a stack trace and the step
     * that failed.
     */
    public static final int TAIL = 64 * 1024;

    /**
     * The most of all of a build's logs together; past {@value #MOST_LOG_BYTES} / {@link #TAIL}
     * logs, each keeps an equal share of its end.
     *
     * <p>A build travels as one varlink message of at most 4 MiB, and a log goes in base64, a third
     * larger: fifty full logs did not fit, and the answer was refused in every round for as long as
     * the commit was watched.
     */
    public static final int MOST_LOG_BYTES = 2 * 1024 * 1024;

    /** The most jobs one build hands logs over for, so a matrix of hundreds cannot flood a task. */
    public static final int MOST_JOBS = 50;

    /** Which logs a caller wants downloaded and handed over. */
    public enum Logs {

        /** No log is downloaded; jobs are listed without logs. */
        NONE,

        /** The log of each failed job. */
        FAILURE,

        /** The log of every job, once the verdict will not change any more. */
        ALL;

        /**
         * Returns the policy the protocol names.
         *
         * @param name "none", "failure" or "all".
         * @return It.
         * @throws IllegalArgumentException For anything else.
         */
        public static Logs of(final String name) {
            return switch (name) {
                case "none" -> NONE;
                case "failure" -> FAILURE;
                case "all" -> ALL;
                default ->
                        throw new IllegalArgumentException(
                                "'" + name + "' is not 'none', 'failure' or 'all'");
            };
        }

        /**
         * Returns its name as the protocol writes it.
         *
         * @return "none", "failure" or "all".
         */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One job of the build and the end of its log.
     *
     * @param name As the forge spells it, workflow and job: {@code Build / unit tests}.
     * @param result What became of it, as the forge says it, lower case: success, failure,
     *     cancelled, skipped, ...
     * @param log Its log's last {@link #TAIL} bytes; {@code null} when the forge gave none.
     */
    public record Job(String name, String result, byte @Nullable [] log) {

        /**
         * Constructor keeping only a log's end.
         *
         * @param name Its name.
         * @param result What became of it.
         * @param log Its log, or {@code null}.
         */
        public Job {
            name = line(name);
            result = line(result);
            if (log != null) {
                log =
                        log.length > TAIL
                                ? java.util.Arrays.copyOfRange(log, log.length - TAIL, log.length)
                                : log.clone();
            }
        }

        /**
         * Constructor for a job without a log.
         *
         * @param name Its name.
         * @param result What became of it.
         */
        public Job(final String name, final String result) {
            this(name, result, null);
        }

        @Override
        public byte @Nullable [] log() {
            return log == null ? null : log.clone();
        }

        /**
         * Returns the log's end as text, for a person.
         *
         * @return It, invalid UTF-8 replaced; "" when there is none.
         */
        public String logText() {
            return log == null ? "" : new String(log, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            return other instanceof Job that
                    && name.equals(that.name)
                    && result.equals(that.result)
                    && java.util.Arrays.equals(log, that.log);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, result, java.util.Arrays.hashCode(log));
        }

        @Override
        public String toString() {
            return "Job[name="
                    + name
                    + ", result="
                    + result
                    + ", log="
                    + (log == null ? "none" : log.length + " bytes")
                    + "]";
        }
    }

    /**
     * Constructor with checks.
     *
     * @param verdict The verdict.
     * @param jobs The jobs with their logs.
     * @param detail More.
     * @param runs Each run of the commit, newest first as the forges list them; at most {@link
     *     #MOST_RUNS} are kept.
     */
    public Build {
        if (!VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException(
                    "Not a verdict: '" + verdict + "', expected one of " + VERDICTS);
        }
        // A reader's detail is one line of the task's file: a line break in it wrote a line of its
        // own, such as a verdict.
        detail = flat(detail);
        jobs = sharingTheLogs(jobs.size() > MOST_JOBS ? jobs.subList(0, MOST_JOBS) : jobs);
        // Every run as the reader gives it: two workflows may share a name, and the forges list a
        // rerun once, at its latest attempt.
        runs = List.copyOf(runs.size() > MOST_RUNS ? runs.subList(0, MOST_RUNS) : runs);
    }

    /**
     * Constructor for a build that names no run, as a reader from before answers.
     *
     * @param verdict The verdict.
     * @param jobs The jobs with their logs.
     * @param detail More.
     */
    public Build(final String verdict, final List<Job> jobs, final String detail) {
        this(verdict, jobs, detail, List.of());
    }

    /**
     * Cuts the logs to an equal share each of {@link #MOST_LOG_BYTES} when they do not fit it
     * whole, keeping ends.
     */
    private static List<Job> sharingTheLogs(final List<Job> jobs) {
        long logs = 0;
        long bytes = 0;
        for (final Job job : jobs) {
            final byte[] log = job.log;
            if (log != null) {
                logs++;
                bytes += log.length;
            }
        }
        if (bytes <= MOST_LOG_BYTES) {
            return List.copyOf(jobs);
        }
        final int share = (int) (MOST_LOG_BYTES / logs);
        final List<Job> shared = new ArrayList<>(jobs.size());
        for (final Job job : jobs) {
            final byte[] log = job.log;
            shared.add(
                    log == null || log.length <= share
                            ? job
                            : new Job(
                                    job.name(),
                                    job.result(),
                                    Arrays.copyOfRange(log, log.length - share, log.length)));
        }
        return List.copyOf(shared);
    }

    /**
     * Constructor for a build that hands no log over.
     *
     * @param verdict The verdict.
     * @param detail More, or "".
     */
    public Build(final String verdict, final String detail) {
        this(verdict, List.of(), detail);
    }

    /**
     * Returns a build without a verdict.
     *
     * @param detail Why.
     * @return It.
     */
    public static Build unknown(final String detail) {
        return new Build(UNKNOWN, detail);
    }

    /**
     * Returns whether the build will not change any more.
     *
     * @return {@code true} for success, failure and cancelled.
     */
    public boolean finished() {
        return SUCCESS.equals(verdict) || FAILURE.equals(verdict) || CANCELLED.equals(verdict);
    }

    /**
     * Returns the build as the protocol carries it, each log in base64 since it need not be text.
     *
     * @return The fields.
     */
    Map<String, Object> asMap() {
        final List<Map<String, Object>> carried = new ArrayList<>();
        for (final Job job : jobs) {
            final Map<String, Object> each = new LinkedHashMap<>();
            each.put("name", job.name());
            each.put("result", job.result());
            final byte[] log = job.log();
            if (log != null) {
                each.put("log", Base64.getEncoder().encodeToString(log));
            }
            carried.add(each);
        }
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("verdict", verdict);
        map.put("jobs", carried);
        map.put("detail", detail);
        if (!runs.isEmpty()) {
            map.put(
                    "runs",
                    runs.stream()
                            .map(
                                    run ->
                                            Map.<String, Object>of(
                                                    "name", run.name(), "result", run.result()))
                            .toList());
        }
        return map;
    }

    /**
     * Reads a build as the protocol carries it.
     *
     * @param map The fields.
     * @return The build.
     * @throws IllegalArgumentException When a field is not what the protocol says.
     */
    static Build of(final Map<String, Object> map) {
        final List<Job> jobs = new ArrayList<>();
        if (map.get("jobs") instanceof List<?> carried) {
            for (final Object each : carried) {
                if (each instanceof Map<?, ?> job) {
                    jobs.add(
                            job.get("log") instanceof String log
                                    ? new Job(
                                            text(job.get("name")),
                                            text(job.get("result")),
                                            Base64.getDecoder().decode(log))
                                    : new Job(text(job.get("name")), text(job.get("result"))));
                }
            }
        }
        final List<Run> runs = new ArrayList<>();
        if (map.get("runs") instanceof List<?> named) {
            for (final Object each : named) {
                if (each instanceof Map<?, ?> run
                        && run.get("name") instanceof String name
                        && run.get("result") instanceof String result) {
                    runs.add(new Run(name, result));
                }
            }
        }
        return new Build(text(map.get("verdict")), jobs, text(map.get("detail")), runs);
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
