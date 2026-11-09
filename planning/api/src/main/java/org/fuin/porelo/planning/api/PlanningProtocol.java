package org.fuin.porelo.planning.api;

/** Names and compatible versions of the planning adapter contract. */
public final class PlanningProtocol {
    /** The shared varlink interface. */
    public static final String INTERFACE = "org.fuin.porelo.Planning1";

    /** The version this API serves. */
    public static final int VERSION = 1;

    /** The oldest compatible adapter. Newer versions within Planning1 remain compatible. */
    public static final int LOWEST = 1;

    /** The Describe operation. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** The GetWorkflow operation. */
    public static final String GET_WORKFLOW = INTERFACE + ".GetWorkflow";

    /** The ListStories operation. */
    public static final String LIST_STORIES = INTERFACE + ".ListStories";

    /** The GetStory operation. */
    public static final String GET_STORY = INTERFACE + ".GetStory";

    /** The ListStoryIssues operation. */
    public static final String LIST_STORY_ISSUES = INTERFACE + ".ListStoryIssues";

    /** The GetIssue operation. */
    public static final String GET_ISSUE = INTERFACE + ".GetIssue";

    /** The SetStates operation, atomic across story and issue moves. */
    public static final String SET_STATES = INTERFACE + ".SetStates";

    /** The WatchChanges operation. */
    public static final String WATCH_CHANGES = INTERFACE + ".WatchChanges";

    /** The Refresh operation. */
    public static final String REFRESH = INTERFACE + ".Refresh";

    private PlanningProtocol() {
        throw new UnsupportedOperationException("Constants");
    }

    static String error(PlanningRefused.Reason reason) {
        return INTERFACE + "." + reason.wire();
    }
}
