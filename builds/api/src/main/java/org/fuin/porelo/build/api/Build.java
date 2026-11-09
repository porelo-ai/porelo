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

    /** The most code points of a run's trigger or id. */
    public static final int MOST_ID = 64;

    /** The most failed tests a job names. */
    public static final int MOST_FAILED_TESTS = 50;

    /** The most characters of a job's page address. */
    public static final int MOST_URL = 2048;

    /**
     * One run of a workflow or pipeline for the commit, and what became of it or its state while it
     * is not finished.
     *
     * @param name As the forge spells it, one line of printable text.
     * @param result Lower case: the conclusion once finished, else "queued", "in_progress", ...
     * @param event What started it, lower case as the forge spells it ({@code push}, {@code
     *     pull_request}, ...), or {@code null} when the reader does not say.
     * @param id The forge's id of the run, or {@code null} when the reader does not say.
     */
    public record Run(String name, String result, @Nullable String event, @Nullable String id) {

        /**
         * Constructor keeping each text one line of printable text, the result and the event in
         * lower case, the event and the id at most {@link #MOST_ID} code points.
         *
         * @param name Its name.
         * @param result What became of it.
         * @param event What started it, or {@code null}.
         * @param id Its id, or {@code null}.
         */
        public Run {
            name = line(name);
            result = line(result).toLowerCase(Locale.ROOT);
            final String trigger = cut(event, MOST_ID);
            event = trigger == null ? null : trigger.toLowerCase(Locale.ROOT);
            id = cut(id, MOST_ID);
        }

        /**
         * Constructor for a run whose reader names neither its trigger nor its id.
         *
         * @param name Its name.
         * @param result What became of it.
         */
        public Run(final String name, final String result) {
            this(name, result, null, null);
        }
    }

    /**
     * Returns a reader's optional text as one line of at most so many code points, or {@code null}
     * when it is absent or blank.
     */
    static @Nullable String cut(final @Nullable String text, final int most) {
        final String stripped = flat(text);
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.codePointCount(0, stripped.length()) <= most
                ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, most));
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
     * @param run The id of the run it belongs to, one of the build's {@link Run#id()}; {@code null}
     *     when the reader does not say, or names no run of the build.
     * @param failedTests For a failed job, the tests that failed as the runner names them, at most
     *     {@link #MOST_FAILED_TESTS}; {@code null} when the reader names none.
     * @param url The forge's page of the job, an {@code https} address; {@code null} when the
     *     reader gives none or none of that form.
     */
    public record Job(
            String name,
            String result,
            byte @Nullable [] log,
            @Nullable String run,
            @Nullable List<String> failedTests,
            @Nullable String url) {

        /**
         * Constructor keeping only a log's end, and each text one line of what may be shown.
         *
         * @param name Its name.
         * @param result What became of it.
         * @param log Its log, or {@code null}.
         * @param run Its run's id, or {@code null}.
         * @param failedTests The tests that failed, or {@code null}.
         * @param url Its page, or {@code null}.
         */
        // Written out, not compact: javac leaves the array's @Nullable off a compact constructor's
        // parameter, and a caller checked by NullAway could then pass no log only by a suppression.
        public Job(
                final String name,
                final String result,
                final byte @Nullable [] log,
                final @Nullable String run,
                final @Nullable List<String> failedTests,
                final @Nullable String url) {
            this.name = line(name);
            this.result = line(result);
            this.log =
                    log == null
                            ? null
                            : log.length > TAIL
                                    ? java.util.Arrays.copyOfRange(
                                            log, log.length - TAIL, log.length)
                                    : log.clone();
            this.run = cut(run, MOST_ID);
            List<String> kept = null;
            if (failedTests != null) {
                // A test's name is the repository's to write: a name with a line break wrote a line
                // of its own into the status.
                final List<String> named = new ArrayList<>();
                for (final String test : failedTests) {
                    final String each = cut(test, MOST_TEXT);
                    if (each != null && named.size() < MOST_FAILED_TESTS) {
                        named.add(each);
                    }
                }
                kept = named.isEmpty() ? null : List.copyOf(named);
            }
            this.failedTests = kept;
            this.url = page(url);
        }

        /**
         * Constructor for a job whose reader names no run, failed tests or page.
         *
         * @param name Its name.
         * @param result What became of it.
         * @param log Its log, or {@code null}.
         */
        public Job(final String name, final String result, final byte @Nullable [] log) {
            this(name, result, log, null, null, null);
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

        /**
         * Returns the same job belonging to no run, for a run id the build does not name.
         *
         * @return It.
         */
        Job ofNoRun() {
            return new Job(name, result, log, null, failedTests, url);
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            return other instanceof Job that
                    && name.equals(that.name)
                    && result.equals(that.result)
                    && java.util.Arrays.equals(log, that.log)
                    && java.util.Objects.equals(run, that.run)
                    && java.util.Objects.equals(failedTests, that.failedTests)
                    && java.util.Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    name, result, java.util.Arrays.hashCode(log), run, failedTests, url);
        }

        @Override
        public String toString() {
            return "Job[name="
                    + name
                    + ", result="
                    + result
                    + ", log="
                    + (log == null ? "none" : log.length + " bytes")
                    + ", run="
                    + run
                    + ", failedTests="
                    + failedTests
                    + ", url="
                    + url
                    + "]";
        }
    }

    /**
     * Returns a job's page address when it is one Porelo may show: {@code https}, with a host, no
     * user part, no space or control character, at most {@link #MOST_URL} characters.
     */
    static @Nullable String page(final @Nullable String url) {
        if (url == null || url.isEmpty() || url.length() > MOST_URL) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                return null;
            }
        }
        try {
            final java.net.URI address = new java.net.URI(url);
            return "https".equals(address.getScheme())
                            && address.getHost() != null
                            && address.getRawUserInfo() == null
                    ? url
                    : null;
        } catch (final java.net.URISyntaxException ex) {
            return null;
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
        // Every run as the reader gives it: two workflows may share a name, and the forges list a
        // rerun once, at its latest attempt.
        runs = List.copyOf(runs.size() > MOST_RUNS ? runs.subList(0, MOST_RUNS) : runs);
        jobs =
                ofItsRuns(
                        sharingTheLogs(jobs.size() > MOST_JOBS ? jobs.subList(0, MOST_JOBS) : jobs),
                        runs);
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

    /** Drops a job's run where the build names no run of that id: it would list under nothing. */
    private static List<Job> ofItsRuns(final List<Job> jobs, final List<Run> runs) {
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final Run run : runs) {
            if (run.id() != null) {
                ids.add(run.id());
            }
        }
        return jobs.stream()
                .map(job -> job.run() == null || ids.contains(job.run()) ? job : job.ofNoRun())
                .toList();
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
                                    Arrays.copyOfRange(log, log.length - share, log.length),
                                    job.run(),
                                    job.failedTests(),
                                    job.url()));
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
            if (job.run() != null) {
                each.put("run", job.run());
            }
            if (job.failedTests() != null) {
                each.put("failedTests", job.failedTests());
            }
            if (job.url() != null) {
                each.put("url", job.url());
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
                                    run -> {
                                        final Map<String, Object> each = new LinkedHashMap<>();
                                        each.put("name", run.name());
                                        each.put("result", run.result());
                                        if (run.event() != null) {
                                            each.put("event", run.event());
                                        }
                                        if (run.id() != null) {
                                            each.put("id", run.id());
                                        }
                                        return each;
                                    })
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
                            new Job(
                                    text(job.get("name")),
                                    text(job.get("result")),
                                    job.get("log") instanceof String log
                                            ? Base64.getDecoder().decode(log)
                                            : null,
                                    job.get("run") instanceof String run ? run : null,
                                    job.get("failedTests") instanceof List<?> tests
                                            ? tests.stream()
                                                    .filter(String.class::isInstance)
                                                    .map(String.class::cast)
                                                    .toList()
                                            : null,
                                    job.get("url") instanceof String url ? url : null));
                }
            }
        }
        final List<Run> runs = new ArrayList<>();
        if (map.get("runs") instanceof List<?> named) {
            for (final Object each : named) {
                if (each instanceof Map<?, ?> run
                        && run.get("name") instanceof String name
                        && run.get("result") instanceof String result) {
                    runs.add(
                            new Run(
                                    name,
                                    result,
                                    run.get("event") instanceof String event ? event : null,
                                    run.get("id") instanceof String id ? id : null));
                }
            }
        }
        return new Build(text(map.get("verdict")), jobs, text(map.get("detail")), runs);
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
