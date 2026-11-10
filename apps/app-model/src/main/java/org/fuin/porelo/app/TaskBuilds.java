package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.BuildFields;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What the builds of a task's pushes did, as Porelo told the task: the record that outlives the
 * task, and the view a person sees while it runs.
 *
 * <p>The record holds what was delivered - commit, verdict, job, the log's name, size and hash -
 * and never a line of a log: the log is kept only in the task it was handed to, and goes with it.
 */
public final class TaskBuilds {

    private final TaskPaths paths;

    /**
     * A failing job's log as it was handed into the task.
     *
     * @param name Its name in {@code /porelo/files}.
     * @param bytes Its size.
     * @param sha256 Its content's hash, hex.
     */
    public record Log(String name, long bytes, String sha256) {}

    /**
     * One job of a build, and its log as it was handed into the task.
     *
     * @param name As the forge spells it, workflow and job.
     * @param result What became of it, as the forge says it.
     * @param log Its log, or {@code null} when none was delivered.
     * @param run Its run's id, or null when none of the build's runs is named.
     * @param failedTests The bounded failed-test names, or null when none are named.
     * @param url The bounded HTTPS page, or null when none is given.
     */
    public record Job(
            String name,
            String result,
            @Nullable Log log,
            @Nullable String run,
            @Nullable List<String> failedTests,
            @Nullable String url) {

        /** Keeps precisely the build API's bounds, including when reading a stored answer. */
        public Job {
            name = BuildFields.line(name);
            result = BuildFields.line(result);
            run = BuildFields.cut(run, BuildFields.MOST_ID);
            failedTests = BuildFields.failedTests(failedTests);
            url = BuildFields.page(url);
        }

        /** Creates a job from a reader that names no run, failed tests or page. */
        public Job(final String name, final String result, final @Nullable Log log) {
            this(name, result, log, null, null, null);
        }

        /**
         * Returns it as the daemon sends it: the log as its name.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("result", result);
            map.put("log", log == null ? "" : log.name());
            if (run != null) {
                map.put("run", run);
            }
            if (failedTests != null) {
                map.put("failedTests", failedTests);
            }
            if (url != null) {
                map.put("url", url);
            }
            return map;
        }
    }

    /**
     * One run of a workflow for the commit, as the reader named it.
     *
     * @param name As the forge spells it.
     * @param result What became of it, or its state while it is not finished.
     * @param event The trigger, or null when the reader names none.
     * @param id The run's id, or null when the reader names none.
     */
    public record Run(String name, String result, @Nullable String event, @Nullable String id) {

        /** Keeps precisely the build API's one-line run fields. */
        public Run {
            name = BuildFields.line(name);
            result = BuildFields.line(result).toLowerCase(java.util.Locale.ROOT);
            final String trigger = BuildFields.cut(event, BuildFields.MOST_ID);
            event = trigger == null ? null : trigger.toLowerCase(java.util.Locale.ROOT);
            id = BuildFields.cut(id, BuildFields.MOST_ID);
        }

        /** Creates a run from a reader that names neither trigger nor id. */
        public Run(final String name, final String result) {
            this(name, result, null, null);
        }

        /**
         * Returns it as the daemon sends it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("result", result);
            if (event != null) {
                map.put("event", event);
            }
            if (id != null) {
                map.put("id", id);
            }
            return map;
        }
    }

    /**
     * One verdict for one commit, as it was handed into the task.
     *
     * @param commit The full sha the task pushed.
     * @param verdict queued, running, success, failure, cancelled or unknown.
     * @param jobs The jobs whose log reached the task, in the forge's order.
     * @param since UTC, when the verdict last changed.
     * @param detail Why it is unknown, or what more the forge said; "" otherwise.
     * @param run The container id of the task it went to.
     * @param runs Each run of the commit's workflows the reader named, in its order.
     */
    public record Seen(
            String commit,
            String verdict,
            List<Job> jobs,
            String since,
            String detail,
            String run,
            List<Run> runs) {

        /** Kept a copy. */
        public Seen {
            runs =
                    List.copyOf(
                            runs.size() > BuildFields.MOST_RUNS
                                    ? runs.subList(0, BuildFields.MOST_RUNS)
                                    : runs);
            final java.util.Set<String> ids = new java.util.HashSet<>();
            for (final Run each : runs) {
                if (each.id() != null) {
                    ids.add(each.id());
                }
            }
            jobs =
                    jobs.stream()
                            .limit(BuildFields.MOST_JOBS)
                            .map(
                                    job ->
                                            BuildFields.knownRun(job.run(), ids) != null
                                                            || job.run() == null
                                                    ? job
                                                    : new Job(
                                                            job.name(),
                                                            job.result(),
                                                            job.log(),
                                                            null,
                                                            job.failedTests(),
                                                            job.url()))
                            .toList();
        }

        /**
         * Constructor for a verdict that names no run, as a record line from before reads.
         *
         * @param commit The full sha the task pushed.
         * @param verdict The verdict.
         * @param jobs The jobs.
         * @param since When the verdict last changed.
         * @param detail More.
         * @param run The container id of the task it went to.
         */
        public Seen(
                final String commit,
                final String verdict,
                final List<Job> jobs,
                final String since,
                final String detail,
                final String run) {
            this(commit, verdict, jobs, since, detail, run, List.of());
        }

        /**
         * Returns it as the daemon sends it, the agreed contract.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("commit", commit);
            map.put("verdict", verdict);
            map.put("jobs", jobs.stream().map(Job::asMap).toList());
            map.put("since", since);
            map.put("detail", detail);
            // Always: Tasks1's Build.runs is not optional.
            map.put("runs", runs.stream().map(Run::asMap).toList());
            return map;
        }

        /**
         * Returns whether the verdict will not change any more.
         *
         * @return {@code true} for success, failure and cancelled.
         */
        public boolean finished() {
            return List.of("success", "failure", "cancelled").contains(verdict);
        }
    }

    /**
     * What a person sees of a task's builds.
     *
     * @param reader The forge whose builds of its pushes are followed; "" when none is named.
     * @param problem Why the named reader does not follow them; "" while it does.
     * @param builds Each push's newest verdict, the newest push first.
     * @param pullRequest Where the pull request from the task's branch is; "" while none is known.
     * @param pullRequestProblem Why none is open; "" while one is, or before the first push.
     * @param credential The missing entry that prevented following builds, or {@code null}.
     */
    public record View(
            String reader,
            String problem,
            List<Seen> builds,
            String pullRequest,
            String pullRequestProblem,
            @Nullable MissingCredential credential) {

        /** Creates a view recorded without a typed missing credential. */
        public View(
                final String reader,
                final String problem,
                final List<Seen> builds,
                final String pullRequest,
                final String pullRequestProblem) {
            this(reader, problem, builds, pullRequest, pullRequestProblem, null);
        }

        /**
         * Constructor for a view without a pull request.
         *
         * @param reader The forge whose builds of its pushes are followed.
         * @param problem Why the named reader does not follow them.
         * @param builds Each push's newest verdict, the newest push first.
         */
        public View(final String reader, final String problem, final List<Seen> builds) {
            this(reader, problem, builds, "", "");
        }

        /**
         * Returns the view of a task whose builds nobody follows.
         *
         * @return It.
         */
        public static View none() {
            return new View("", "", List.of());
        }
    }

    /**
     * The helper that follows a task's builds: its pid file, its log and its entry in the resume
     * record.
     */
    public static final String HELPER = "builds";

    /**
     * The file in a task's state that says which reader follows its builds, and what keeps it from
     * doing so.
     */
    static final String READER_FILE = "builds.json";

    /**
     * Constructor.
     *
     * @param paths Where tasks keep their state.
     */
    public TaskBuilds(final TaskPaths paths) {
        this.paths = paths;
    }

    /**
     * Writes a verdict down.
     *
     * @param container The task.
     * @param seen What was handed in.
     * @throws IOException When it cannot be written.
     */
    public void write(final String container, final Seen seen) throws IOException {
        final Path record = recordOf(container);
        Directories.create(
                record.getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.exists(record)) {
            Files.createFile(
                    record,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }
        final Map<String, Object> line = new LinkedHashMap<>(seen.asMap());
        // The record keeps each log's size and hash beside its name: what was delivered, never a
        // line of it.
        line.put(
                "jobs",
                seen.jobs().stream()
                        .map(
                                job -> {
                                    final Map<String, Object> each =
                                            new LinkedHashMap<>(job.asMap());
                                    if (job.log() != null) {
                                        each.put(
                                                "log",
                                                Map.of(
                                                        "name",
                                                        job.log().name(),
                                                        "bytes",
                                                        job.log().bytes(),
                                                        "sha256",
                                                        job.log().sha256()));
                                    }
                                    return each;
                                })
                        .toList());
        line.put("run", seen.run());
        Files.writeString(
                record, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * Returns every verdict written for a task name, oldest first: of every run that had it.
     *
     * @param container The task's name.
     * @return The record, empty when nothing was written.
     * @throws IOException When it cannot be read.
     */
    public List<Seen> record(final String container) throws IOException {
        final Path file = recordOf(container);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Seen> seen = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && Json.parse(line) instanceof Map<?, ?> map) {
                final List<Job> jobs = new ArrayList<>();
                if (map.get("jobs") instanceof List<?> listed) {
                    for (final Object entry : listed) {
                        if (entry instanceof Map<?, ?> job) {
                            final Log log =
                                    job.get("log") instanceof Map<?, ?> each
                                            ? new Log(
                                                    text(each.get("name")),
                                                    number(each.get("bytes")),
                                                    text(each.get("sha256")))
                                            : null;
                            jobs.add(
                                    new Job(
                                            text(job.get("name")),
                                            text(job.get("result")),
                                            log,
                                            optionalText(job.get("run")),
                                            job.get("failedTests") instanceof List<?> tests
                                                    ? tests.stream()
                                                            .filter(String.class::isInstance)
                                                            .map(String.class::cast)
                                                            .toList()
                                                    : null,
                                            optionalText(job.get("url"))));
                        }
                    }
                }
                final List<Run> runs = new ArrayList<>();
                if (map.get("runs") instanceof List<?> named) {
                    for (final Object entry : named) {
                        if (entry instanceof Map<?, ?> each) {
                            runs.add(
                                    new Run(
                                            text(each.get("name")),
                                            text(each.get("result")),
                                            optionalText(each.get("event")),
                                            optionalText(each.get("id"))));
                        }
                    }
                }
                seen.add(
                        new Seen(
                                text(map.get("commit")),
                                text(map.get("verdict")),
                                jobs,
                                text(map.get("since")),
                                text(map.get("detail")),
                                text(map.get("run")),
                                runs));
            }
        }
        return seen;
    }

    /**
     * Returns the builds of a task's current run, each commit's newest verdict, the newest commit
     * first.
     *
     * @param container The task.
     * @return The builds; empty before the first push and where nothing can be read.
     */
    public List<Seen> current(final String container) {
        final String run = HandIns.runOf(paths, container);
        final Map<String, Seen> newest = new LinkedHashMap<>();
        try {
            for (final Seen seen : record(container)) {
                if (seen.run().equals(run)) {
                    // A commit keeps the place it was first seen at, so the order is the order of
                    // the pushes.
                    newest.put(seen.commit(), seen);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // An unreadable record says no build is known rather than failing the listing it is a
            // part of.
            return List.of();
        }
        final List<Seen> builds = new ArrayList<>(newest.values());
        java.util.Collections.reverse(builds);
        return List.copyOf(builds);
    }

    /**
     * Writes down which reader follows a task's builds, and why it does not when it does not.
     *
     * @param container The task.
     * @param reader The forge, as the project names it.
     * @param problem Why it does not follow them; "" when it does.
     * @throws IOException When it cannot be written.
     */
    public void reader(final String container, final String reader, final String problem)
            throws IOException {
        reader(container, reader, problem, null);
    }

    /** Records why a start follows no builds, keeping its typed remedy beside the sentence. */
    public void reader(
            final String container,
            final String reader,
            final String problem,
            final @Nullable MissingCredential credential)
            throws IOException {
        reader(container, reader, problem, credential, List.of());
    }

    /** Records a deferred reader command without starting it while its credential is missing. */
    public void reader(
            final String container,
            final String reader,
            final String problem,
            final @Nullable MissingCredential credential,
            final List<String> deferred)
            throws IOException {
        final Map<String, Object> said = said(container);
        if (deferred.isEmpty()) {
            said.remove("deferred");
        } else {
            said.put("deferred", List.copyOf(deferred));
        }
        said.put("reader", reader);
        said.put("problem", problem);
        if (credential == null) {
            said.remove("credential");
        } else {
            said.put("credential", credential.asMap());
        }
        write(container, said);
    }

    /** Returns the reader command a start deferred, empty for an older or unreadable record. */
    public List<String> deferred(final String container) {
        final Object value = said(container).get("deferred");
        if (value instanceof List<?> command
                && command.stream().allMatch(String.class::isInstance)) {
            return command.stream().map(String.class::cast).toList();
        }
        return List.of();
    }

    /**
     * Writes down where the pull request from a task's branch is, or why none is open.
     *
     * @param container The task.
     * @param url Its address; "" when none is open.
     * @param problem Why none is open; "" when one is.
     * @throws IOException When it cannot be written.
     */
    public void pullRequest(final String container, final String url, final String problem)
            throws IOException {
        final Map<String, Object> said = said(container);
        said.putIfAbsent("reader", "");
        said.putIfAbsent("problem", "");
        said.put("pull_request", url);
        said.put("pull_request_problem", problem);
        write(container, said);
    }

    /** What the task's reader file says now, so one writer keeps what the other wrote. */
    private Map<String, Object> said(final String container) {
        final Map<String, Object> said = new LinkedHashMap<>();
        final Path file = paths.containerState(container).resolve(READER_FILE);
        try {
            if (Files.isRegularFile(file)
                    && Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> read) {
                read.forEach((key, value) -> said.put(String.valueOf(key), value));
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable, it is written anew.
        }
        return said;
    }

    private void write(final String container, final Map<String, Object> said) throws IOException {
        final Path state = paths.containerState(container);
        Directories.create(state);
        Files.writeString(
                state.resolve(READER_FILE), Json.write(said) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Returns what a person sees of a task's builds.
     *
     * @param container The task.
     * @return The view; {@link View#none()} when nothing follows them.
     */
    public View view(final String container) {
        final Path file = paths.containerState(container).resolve(READER_FILE);
        try {
            if (Files.isRegularFile(file)
                    && Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> said) {
                return new View(
                        text(said.get("reader")),
                        text(said.get("problem")),
                        current(container),
                        text(said.get("pull_request")),
                        text(said.get("pull_request_problem")),
                        MissingCredential.read(said.get("credential")));
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable, it says nothing follows them rather than failing the listing it is a part
            // of.
        }
        return View.none();
    }

    /** The record, in the state directory like the hand-ins', so it outlives the task. */
    private Path recordOf(final String container) {
        return paths.paths().xdg().state().resolve("builds").resolve(container + ".jsonl");
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static @Nullable String optionalText(final @Nullable Object value) {
        return value instanceof String text ? text : null;
    }

    private static long number(final @Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
