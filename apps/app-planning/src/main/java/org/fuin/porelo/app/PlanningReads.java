package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.InstalledPlanning;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningException;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.Workflow;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Read-only bridge; no store state or work is changed by opening an adapter for a request. */
public final class PlanningReads implements AutoCloseable {
    private final Source adapter;
    private final String project;
    private final String adapterName;
    private final boolean workConfigured;
    private final int readingRounds;
    private final PlanningReadSnapshot local;

    /** Opens only the named project's installed adapter, with no credential integration yet. */
    public PlanningReads(PoreloContext context, String project, Path file) {
        this(context, project, file, PlanningReadSnapshot.empty());
    }

    /** Opens a request against the supplied local snapshot; reads do not mutate that snapshot. */
    public PlanningReads(
            PoreloContext context, String project, Path file, PlanningReadSnapshot local) {
        this.project = project;
        this.local = local;
        final Map<?, ?> root;
        try (var reader = Files.newBufferedReader(file)) {
            final LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            final Object read = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(read instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Project file must be a mapping");
            }
            root = map;
        } catch (IOException | RuntimeException ex) {
            throw error(
                    "PlanningAdapterRefused",
                    Map.of(
                            "adapter",
                            "",
                            "detail",
                            "Cannot read planning configuration; correct the project planning entry"));
        }
        if (!root.containsKey("planning")) {
            throw error("PlanningNotSet", Map.of("project", project));
        }
        if (!(root.get("planning") instanceof Map<?, ?> planning)
                || !(planning.get("adapter") instanceof String name)) {
            throw error(
                    "PlanningAdapterRefused",
                    Map.of("adapter", "", "detail", "Planning must name an installed adapter"));
        }
        adapterName = name;
        workConfigured = planning.containsKey("work");
        readingRounds = readingRounds(planning);
        // A credential is never silently discarded, nor read before its integration exists.
        if (planning.containsKey("credential")) {
            throw error("NotYetBuilt", Map.of("method", "PlanningCredentials"));
        }
        final Map<String, Object> settings = new LinkedHashMap<>();
        if (planning.containsKey("settings")) {
            if (!(planning.get("settings") instanceof Map<?, ?> supplied)) {
                throw error(
                        "PlanningAdapterRefused",
                        Map.of("adapter", name, "detail", "Planning settings must be an object"));
            }
            supplied.forEach(
                    (key, value) -> {
                        if (!(key instanceof String text) || value == null) {
                            throw error(
                                    "PlanningAdapterRefused",
                                    Map.of(
                                            "adapter",
                                            name,
                                            "detail",
                                            "Planning settings must have string keys and non-null values"));
                        }
                        settings.put(text, value);
                    });
        }
        try {
            final Path executable =
                    InstalledPlanning.find(name, context.paths().planningDirectories());
            final Path runtime =
                    Files.createDirectories(context.paths().xdg().runtime().resolve("planning"));
            final Path directory =
                    Files.createDirectories(
                            context.paths().xdg().state().resolve("planning").resolve(project));
            adapter =
                    new InstalledSource(
                            new InstalledPlanning(executable, runtime, directory), settings);
        } catch (IOException | PlanningException ex) {
            throw error(
                    "PlanningAdapterRefused",
                    Map.of(
                            "adapter",
                            name,
                            "detail",
                            "Cannot open installed planning adapter; install or repair its package"));
        }
    }

    /** A read source bound to one project's settings and credentials. */
    interface Source extends AutoCloseable {
        PlanningAdapter.Result<Workflow> workflow();

        PlanningAdapter.Result<List<StorySummary>> stories();

        PlanningAdapter.Result<Story> story(String id);

        PlanningAdapter.Result<List<IssueSummary>> issues(String story);

        PlanningAdapter.Result<Issue> issue(String id);

        boolean hasIssues();

        @Override
        default void close() {}
    }

    private record InstalledSource(InstalledPlanning installed, Map<String, Object> settings)
            implements Source {
        public PlanningAdapter.Result<Workflow> workflow() {
            return installed.getWorkflow(settings, "");
        }

        public PlanningAdapter.Result<List<StorySummary>> stories() {
            // The adapter filters stored states; only porelod may filter computed states.
            return installed.listStories(settings, "", null);
        }

        public PlanningAdapter.Result<Story> story(String id) {
            return installed.getStory(settings, "", id);
        }

        public PlanningAdapter.Result<List<IssueSummary>> issues(String story) {
            return installed.listStoryIssues(settings, "", story);
        }

        public PlanningAdapter.Result<Issue> issue(String id) {
            return installed.getIssue(settings, "", id);
        }

        public boolean hasIssues() {
            return installed.describe().capabilities().contains("issues");
        }

        public void close() {
            installed.close();
        }
    }

    /** Uses the same read path with an in-memory source, without a socket or account access. */
    PlanningReads(Source adapter, String project, PlanningReadSnapshot local, int readingRounds) {
        this.adapter = adapter;
        this.project = project;
        this.local = local;
        this.readingRounds = readingRounds;
        adapterName = "in-memory";
        workConfigured = false;
    }

    /** Answers a read from one revision, retrying a concurrently changed snapshot at most twice. */
    public Map<String, Object> read(String method, Map<String, Object> parameters) {
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return readOnce(method, parameters);
                } catch (SnapshotChanged ex) {
                    if (attempt == 2) {
                        throw error("StaleRevision", Map.of("revision", ex.revision));
                    }
                }
            }
        } catch (PlanningRefused ex) {
            final var fields = new LinkedHashMap<String, Object>(ex.parameters());
            if (ex.reason() == PlanningRefused.Reason.WORKFLOW_MISSING) {
                fields.put("project", project);
            }
            final String name =
                    ex.reason() == PlanningRefused.Reason.CREDENTIAL_REFUSED
                            ? "PlanningCredentialRefused"
                            : ex.reason().wire();
            throw error(name, fields);
        } catch (PlanningException ex) {
            final String name = ex.errorName();
            if (name != null) {
                throw error(
                        "PlanningAdapterError",
                        Map.of(
                                "adapter",
                                adapterName,
                                "error",
                                name,
                                "parameters",
                                ex.parameters()));
            }
            throw unreadable();
        } catch (IllegalArgumentException ex) {
            throw unreadable();
        }
    }

    private VarlinkException unreadable() {
        return error(
                "PlanningAdapterRefused",
                Map.of(
                        "adapter",
                        adapterName,
                        "detail",
                        "Cannot read a consistent planning snapshot; check the adapter and retry the board read"));
    }

    private Map<String, Object> readOnce(String method, Map<String, Object> parameters) {
        final var workflow = adapter.workflow();
        if (workflow.value().states().isEmpty()) {
            throw error(
                    "WorkflowMissing",
                    Map.of(
                            "project",
                            project,
                            "detail",
                            "The adapter supplied no workflow states"));
        }
        return switch (method) {
            case "GetPlanningWorkflow" -> {
                final var answer = new LinkedHashMap<String, Object>(workflow.value().asMap());
                answer.put("work", List.of());
                answer.put(
                        "faults",
                        workConfigured
                                ? List.of("Work settings and validation are not implemented")
                                : List.of());
                yield answer;
            }
            case "ListStories" -> {
                final var found = adapter.stories();
                same(workflow, found);
                final List<?> states =
                        parameters.get("states") == null ? null : strings(parameters.get("states"));
                final var stories = new java.util.ArrayList<Map<String, Object>>();
                for (var summary : found.value()) {
                    final List<IssueSummary> issues = summary.issues();
                    if (issues == null) {
                        throw PlanningRefused.notSupported("board-snapshot");
                    }
                    final Map<String, Object> full =
                            storyFields(
                                    summary.id(),
                                    summary.title(),
                                    summary.state(),
                                    summary.link(),
                                    "",
                                    summary.priority(),
                                    issues,
                                    workflow.value());
                    if (states == null || states.contains(full.get("state"))) {
                        stories.add(full);
                    }
                }
                yield Map.of("stories", stories, "revision", found.revision());
            }
            case "GetStory" -> {
                final var found = adapter.story(text(parameters, "story"));
                same(workflow, found);
                yield Map.of("story", story(found, workflow), "revision", found.revision());
            }
            case "ListStoryIssues" -> {
                final var found = adapter.issues(text(parameters, "story"));
                same(workflow, found);
                yield Map.of(
                        "issues",
                        found.value().stream().map(issue -> enriched(issue.asMap())).toList(),
                        "revision",
                        found.revision());
            }
            case "GetIssue" -> {
                final var found = adapter.issue(text(parameters, "issue"));
                same(workflow, found);
                final var issue = new LinkedHashMap<String, Object>(found.value().asMap());
                issue.put(
                        "subAgents",
                        found.value().subAgents().stream()
                                .map(
                                        proposal -> {
                                            final var fields =
                                                    new LinkedHashMap<String, Object>(
                                                            proposal.asMap());
                                            fields.put("role", proposal.kind().name());
                                            fields.remove("kind");
                                            return fields;
                                        })
                                .toList());
                yield Map.of("issue", enriched(issue), "revision", found.revision());
            }
            default -> throw new IllegalArgumentException("Unknown planning read");
        };
    }

    private Map<String, Object> story(
            PlanningAdapter.Result<Story> found,
            PlanningAdapter.Result<org.fuin.porelo.planning.api.Workflow> workflow) {
        final var story = found.value();
        final List<IssueSummary> issues;
        if (adapter.hasIssues()) {
            final var result = adapter.issues(story.id());
            same(found, result);
            issues = result.value();
        } else {
            issues = List.of();
        }
        return storyFields(
                story.id(),
                story.title(),
                story.state(),
                story.link(),
                story.description(),
                story.priority(),
                issues,
                workflow.value());
    }

    private Map<String, Object> storyFields(
            String id,
            String title,
            String state,
            String link,
            String description,
            @org.jspecify.annotations.Nullable String priority,
            List<IssueSummary> issues,
            Workflow workflow) {
        final var fields = new LinkedHashMap<String, Object>();
        fields.put("id", id);
        fields.put("title", title);
        fields.put("state", StoryStates.of(workflow, state, issues));
        fields.put("link", link);
        fields.put("description", description);
        if (priority != null) {
            fields.put("priority", priority);
        }
        fields.put("issues", issues.stream().map(issue -> enriched(issue.asMap())).toList());
        final var work = local.stories().get(id);
        if (work != null) {
            fields.put("work", work.asMap());
        }
        return fields;
    }

    private static final class SnapshotChanged extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String revision;

        SnapshotChanged(String revision) {
            this.revision = revision;
        }
    }

    private static void same(PlanningAdapter.Result<?> first, PlanningAdapter.Result<?> next) {
        if (!first.revision().equals(next.revision())) {
            throw new SnapshotChanged(next.revision());
        }
    }

    private Map<String, Object> enriched(Map<String, Object> summary) {
        final var fields = new LinkedHashMap<String, Object>(summary);
        final var saved = local.issue(text(summary, "id"));
        fields.put("rounds", saved == null ? 0 : saved.rounds());
        fields.put("roundsMost", saved == null ? readingRounds : saved.roundsMost());
        fields.put("atWork", saved != null && saved.atWork());
        fields.put("needsYou", saved != null && saved.needsYou());
        fields.put("readingStopped", saved != null && saved.readingStopped());
        fields.put(
                "findings",
                saved == null
                        ? List.of()
                        : saved.findings().stream()
                                .map(org.fuin.porelo.wire.planning.PlanFinding::asMap)
                                .toList());
        return org.fuin.porelo.wire.planning.PlanIssue.of(fields).asMap();
    }

    private static int readingRounds(Map<?, ?> planning) {
        if (!planning.containsKey("reading")) {
            return 2;
        }
        if (planning.get("reading") instanceof Map<?, ?> reading) {
            if (!reading.containsKey("rounds")) {
                return 2;
            }
            if (reading.get("rounds") instanceof Number number
                    && number.intValue() > 0
                    && number.doubleValue() == number.intValue()) {
                return number.intValue();
            }
        }
        throw error(
                "PlanningAdapterRefused",
                Map.of(
                        "adapter",
                        "",
                        "detail",
                        "Planning reading rounds must be a positive whole number"));
    }

    private static String text(Map<String, Object> fields, String key) {
        if (fields.get(key) instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Missing string " + key);
    }

    private static List<?> strings(@org.jspecify.annotations.Nullable Object value) {
        if (value instanceof List<?> values && values.stream().allMatch(String.class::isInstance)) {
            return values;
        }
        throw new IllegalArgumentException("states must be a list of strings");
    }

    private static VarlinkException error(String name, Map<String, Object> fields) {
        return new VarlinkException(TasksContract.INTERFACE + "." + name, fields);
    }

    /** Reaps the adapter and removes this request's private socket directory. */
    @Override
    public void close() {
        adapter.close();
    }
}
