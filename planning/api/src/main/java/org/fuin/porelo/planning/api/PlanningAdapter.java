package org.fuin.porelo.planning.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The contract an adapter implements. Calls may run concurrently, including watches and writes.
 * Each operation receives settings unchanged and a token for that call; neither may be logged.
 * Every data operation refuses a missing workflow with WorkflowMissing, including optional
 * operations.
 */
public interface PlanningAdapter {
    /** Returns the installed executable's plain name. */
    String name();

    /** Returns the adapter's own version. */
    String version();

    /** Returns supported capabilities: write, watch and issues. */
    List<String> capabilities();

    /** Returns accepted credential kinds: github-app and/or token. */
    List<String> credentials();

    /**
     * Receives the adapter's project directory, already made by its caller; keeps no token there.
     */
    default void initialize(Path directory) {}

    /**
     * A read and its opaque revision, captured together; revisions cover the entire board and are
     * never reused.
     */
    record Result<T>(T value, String revision) {}

    /** Returns the one workflow in board order. */
    Result<Workflow> getWorkflow(Map<String, Object> settings, String token);

    /**
     * Lists stories filtered by stored state; null means all, an empty list means none. Adapters
     * with issues supply each summary's complete issue snapshot at this revision. For a computed
     * board, omit the filter and compare GetWorkflow's revision, retrying on a mismatch.
     */
    Result<List<StorySummary>> listStories(
            Map<String, Object> settings, String token, @Nullable List<String> states);

    /** Returns a story or NoSuchStory. */
    Result<Story> getStory(Map<String, Object> settings, String token, String story);

    /** Lists a story's issues or answers NoSuchStory / NotSupported(issues). */
    Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story);

    /**
     * Returns an issue with acceptance text and sub-agents, or NoSuchIssue / NotSupported(issues).
     */
    Result<Issue> getIssue(Map<String, Object> settings, String token, String issue);

    /**
     * Sets story and issue states together against one revision, requiring write and, when an issue
     * is present, issues. Validates the entire batch before writing; a refusal changes nothing.
     * Stories must have no issues or answer StoryHasIssues. Success returns one new revision and
     * publishes one change. An empty batch checks revision without advancing it. Repeated ids
     * within a resource kind answer StateNotAllowed with reason "Duplicate story" or "Duplicate
     * issue". StaleRevision carries the current store revision. Replaces setStoryState and
     * setIssueStates before Planning1's first release; no aliases.
     */
    String setStates(
            Map<String, Object> settings, String token, List<StateChange> changes, String revision);

    /**
     * Refreshes immediately and publishes any change to existing watches; requires watch. Returns
     * the revision after refreshing, unchanged when no change was found.
     */
    String refresh(Map<String, Object> settings, String token);

    /**
     * Opens an independent watch, atomically with capturing its first revision. The caller MUST
     * compare that first reply with its read revision and reread the board if different. Later
     * writes remain pending even before the first next. Closing releases resources and wakes next
     * with null. A missing workflow is refused before returning a watch.
     */
    Watch watchChanges(Map<String, Object> settings, String token);

    /** A per-subscriber change source; no callbacks run under an adapter's state lock. */
    interface Watch extends AutoCloseable {
        /** Returns the next invalidation, or null when none arrives within the bound. */
        @Nullable Change next(long timeoutMillis) throws InterruptedException;

        /** Releases this subscription and unblocks a waiting reader. */
        @Override
        void close();
    }
}
