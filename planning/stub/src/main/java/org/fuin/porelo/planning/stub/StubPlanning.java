package org.fuin.porelo.planning.stub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planning.api.Change;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanPhase;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningMain;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.SubAgent;
import org.fuin.porelo.planning.api.Workflow;
import org.jspecify.annotations.Nullable;

/** Thread-safe in-memory adapter. One monitor protects revisions, snapshots and whole batches. */
public class StubPlanning implements PlanningAdapter {
    private final @Nullable Workflow workflow;
    private final List<String> capabilities;
    private final Map<String, Story> stories = new LinkedHashMap<>();
    private final Map<String, Issue> issues = new LinkedHashMap<>();
    private final Map<String, List<String>> storyIssues = new LinkedHashMap<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private long counter;

    /** Makes a board with two issues under story and an issue-less empty story. */
    public StubPlanning() {
        this(defaultWorkflow(), List.of("write", "watch", "issues"));
    }

    /** Makes the same board with a chosen workflow and capabilities; null means WorkflowMissing. */
    public StubPlanning(@Nullable Workflow workflow, List<String> capabilities) {
        this.workflow = workflow;
        this.capabilities = List.copyOf(capabilities);
        stories.put(
                "story",
                new Story(
                        "story",
                        "A story",
                        "Backlog",
                        "https://example.org/story",
                        "Story text",
                        "Highest"));
        stories.put(
                "empty",
                new Story(
                        "empty",
                        "An issue-less story",
                        "Backlog",
                        "https://example.org/empty",
                        "Empty story text",
                        "Low"));
        issues.put(
                "one",
                new Issue(
                        "one",
                        "service",
                        "First issue",
                        "Backlog",
                        "S",
                        List.of(),
                        "https://example.org/one",
                        "The first condition holds.",
                        List.of(
                                new SubAgent(
                                        "builder",
                                        SubAgent.Kind.BUILDER,
                                        "example-agent",
                                        "build-model",
                                        "# Build\n\nImplement the first condition.\nOutcome: done\n"),
                                new SubAgent(
                                        "tester",
                                        SubAgent.Kind.TESTER,
                                        "example-agent",
                                        "test-model",
                                        "# Test\n\nCheck the first condition.\nOutcome: done\n"),
                                new SubAgent(
                                        "reader",
                                        SubAgent.Kind.READER,
                                        "example-agent",
                                        "read-model",
                                        "# Read\n\nRead against the acceptance.\nOutcome: done\n"))));
        issues.put(
                "two",
                new Issue(
                        "two",
                        "client",
                        "Second issue",
                        "Backlog",
                        "M",
                        List.of("one"),
                        "https://example.org/two",
                        "The second condition holds.",
                        List.of()));
        storyIssues.put("story", List.of("one", "two"));
        storyIssues.put("empty", List.of());
    }

    /** Returns the fixture's ordered workflow. */
    public static Workflow defaultWorkflow() {
        return new Workflow(
                List.of(
                        new PlanState("Backlog", PlanState.Kind.WAITING),
                        new PlanState("Working", PlanState.Kind.WORKING),
                        new PlanState("Done", PlanState.Kind.CLOSED)),
                List.of(new PlanPhase("Delivery", List.of("Backlog", "Working", "Done"))));
    }

    /** Starts the stub using the same main an external adapter uses. */
    public static void main(String[] arguments) {
        PlanningMain.run(new StubPlanning(), arguments);
    }

    @Override
    public String name() {
        return "stub-planning";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<String> capabilities() {
        return capabilities;
    }

    @Override
    public List<String> credentials() {
        return List.of("github-app", "token");
    }

    @Override
    public synchronized Result<Workflow> getWorkflow(Map<String, Object> settings, String token) {
        return result(requireWorkflow());
    }

    @Override
    public synchronized Result<List<StorySummary>> listStories(
            Map<String, Object> settings, String token, @Nullable List<String> states) {
        requireWorkflow();
        return result(
                stories.values().stream()
                        .filter(story -> states == null || states.contains(story.state()))
                        .map(
                                story ->
                                        new StorySummary(
                                                story.id(),
                                                story.title(),
                                                story.state(),
                                                story.link(),
                                                ids(story.id()).stream()
                                                        .map(this::issue)
                                                        .map(Issue::summary)
                                                        .toList(),
                                                story.priority()))
                        .toList());
    }

    @Override
    public synchronized Result<Story> getStory(
            Map<String, Object> settings, String token, String story) {
        requireWorkflow();
        return result(story(story));
    }

    @Override
    public synchronized Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story) {
        requireWorkflow();
        require("issues");
        story(story);
        return result(ids(story).stream().map(this::issue).map(Issue::summary).toList());
    }

    @Override
    public synchronized Result<Issue> getIssue(
            Map<String, Object> settings, String token, String issue) {
        requireWorkflow();
        require("issues");
        return result(issue(issue));
    }

    @Override
    public synchronized String setStates(
            Map<String, Object> settings,
            String token,
            List<StateChange> changes,
            String revision) {
        requireWorkflow();
        require("write");
        if (changes.stream().anyMatch(change -> change.issue() != null)) require("issues");
        current(revision);
        final var seenStories = new java.util.HashSet<String>();
        final var seenIssues = new java.util.HashSet<String>();
        for (StateChange change : changes) {
            final String storyId = change.story();
            if (storyId != null) {
                final Story found = story(storyId);
                if (!ids(storyId).isEmpty()) {
                    throw new PlanningRefused(
                            PlanningRefused.Reason.STORY_HAS_ISSUES, Map.of("story", storyId));
                }
                transition(storyId, found.state(), change.to());
                if (!seenStories.add(storyId)) {
                    throw disallowed(storyId, found.state(), change.to(), "Duplicate story");
                }
            } else {
                final String issueId =
                        java.util.Objects.requireNonNull(
                                change.issue(), "StateChange requires exactly one story or issue");
                final Issue found = issue(issueId);
                transition(issueId, found.state(), change.to());
                if (!seenIssues.add(issueId)) {
                    throw disallowed(issueId, found.state(), change.to(), "Duplicate issue");
                }
            }
        }
        if (changes.isEmpty()) return revision();
        final var movedStories = new java.util.LinkedHashSet<String>();
        final var movedIssues = new ArrayList<String>();
        for (StateChange change : changes) {
            final String storyId = change.story();
            if (storyId != null) {
                final Story found = story(storyId);
                stories.put(
                        storyId,
                        new Story(
                                found.id(),
                                found.title(),
                                change.to(),
                                found.link(),
                                found.description(),
                                found.priority()));
                movedStories.add(storyId);
            } else {
                final String issueId =
                        java.util.Objects.requireNonNull(
                                change.issue(), "StateChange requires exactly one story or issue");
                moveIssue(issueId, change.to());
                movedIssues.add(issueId);
            }
        }
        movedStories.addAll(owners(movedIssues));
        changed(List.copyOf(movedStories), movedIssues);
        return revision();
    }

    @Override
    public synchronized String refresh(Map<String, Object> settings, String token) {
        requireWorkflow();
        require("watch");
        applyPending();
        return revision();
    }

    @Override
    public synchronized Watch watchChanges(Map<String, Object> settings, String token) {
        requireWorkflow();
        require("watch");
        final var subscription = new Subscription();
        subscriptions.add(subscription);
        return subscription;
    }

    /** Simulates a remote change not yet fetched. Refresh applies and publishes it. */
    public synchronized void stageIssueState(String id, String to) {
        transition(id, issue(id).state(), to);
        pending.put(id, to);
    }

    /** Simulates an already observed change from outside Porelo and publishes it. */
    public synchronized void externalIssueState(String id, String to) {
        transition(id, issue(id).state(), to);
        moveIssue(id, to);
        changed(owners(List.of(id)), List.of(id));
    }

    /** Returns the number of active subscribers, to verify disconnect cleanup. */
    public synchronized int subscribers() {
        return subscriptions.size();
    }

    private Workflow requireWorkflow() {
        if (workflow == null) {
            throw new PlanningRefused(
                    PlanningRefused.Reason.WORKFLOW_MISSING,
                    Map.of("detail", "The project has no workflow"));
        }
        return workflow;
    }

    private void require(String capability) {
        if (!capabilities.contains(capability)) {
            throw PlanningRefused.notSupported(capability);
        }
    }

    private Story story(String id) {
        final Story found = stories.get(id);
        if (found == null) {
            throw new PlanningRefused(PlanningRefused.Reason.NO_SUCH_STORY, Map.of("story", id));
        }
        return found;
    }

    private Issue issue(String id) {
        final Issue found = issues.get(id);
        if (found == null) {
            throw new PlanningRefused(PlanningRefused.Reason.NO_SUCH_ISSUE, Map.of("issue", id));
        }
        return found;
    }

    private List<String> ids(String story) {
        return java.util.Objects.requireNonNull(
                storyIssues.get(story), "every known story has an issue list");
    }

    private void current(String revision) {
        // A write observes remote divergence before committing, even without an explicit refresh.
        applyPending();
        if (!revision().equals(revision)) {
            throw new PlanningRefused(
                    PlanningRefused.Reason.STALE_REVISION, Map.of("revision", revision()));
        }
    }

    private String revision() {
        return Long.toString(counter);
    }

    private <T> Result<T> result(T value) {
        return new Result<>(value, revision());
    }

    private void transition(String item, String from, String to) {
        if (requireWorkflow().states().stream().noneMatch(state -> state.name().equals(to))) {
            throw disallowed(item, from, to, "Unknown state");
        }
    }

    private static PlanningRefused disallowed(String item, String from, String to, String reason) {
        return new PlanningRefused(
                PlanningRefused.Reason.STATE_NOT_ALLOWED,
                Map.of("item", item, "from", from, "to", to, "reason", reason));
    }

    private void moveIssue(String id, String to) {
        final Issue found = issue(id);
        issues.put(
                id,
                new Issue(
                        found.id(),
                        found.repository(),
                        found.title(),
                        to,
                        found.size(),
                        found.blockedBy(),
                        found.link(),
                        found.whatMustBeTrue(),
                        found.subAgents()));
    }

    private List<String> owners(List<String> changedIssues) {
        return storyIssues.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(changedIssues::contains))
                .map(Map.Entry::getKey)
                .toList();
    }

    private void applyPending() {
        if (!pending.isEmpty()) {
            final var moved = List.copyOf(pending.keySet());
            pending.forEach(this::moveIssue);
            pending.clear();
            changed(owners(moved), moved);
        }
    }

    private void changed(List<String> stories, List<String> issues) {
        counter++;
        final var change = new Change(revision(), stories, issues);
        for (Subscription subscription : subscriptions) {
            subscription.offer(change);
        }
    }

    private final class Subscription implements Watch {
        private final ArrayBlockingQueue<Change> queue = new ArrayBlockingQueue<>(1);
        private volatile boolean closed;
        private @Nullable Change initial = new Change(revision(), List.of(), List.of());

        private void offer(Change change) {
            if (!queue.offer(change)) {
                queue.clear();
                // A slow subscriber rereads the whole board rather than losing resource ids.
                queue.offer(new Change(change.revision(), List.of(), List.of()));
            }
        }

        @Override
        public @Nullable Change next(long timeoutMillis) throws InterruptedException {
            synchronized (StubPlanning.this) {
                if (closed) return null;
                if (initial != null) {
                    Change first = initial;
                    initial = null;
                    return first;
                }
            }
            Change change = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return closed ? null : change;
        }

        @Override
        public void close() {
            synchronized (StubPlanning.this) {
                closed = true;
                initial = null;
                subscriptions.remove(this);
                queue.clear();
                queue.offer(new Change(revision(), List.of(), List.of()));
            }
        }
    }
}
