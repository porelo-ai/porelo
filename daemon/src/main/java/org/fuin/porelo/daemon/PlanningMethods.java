package org.fuin.porelo.daemon;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.fuin.porelo.app.PlanningChanges;
import org.fuin.porelo.app.PlanningReadSnapshot;
import org.fuin.porelo.app.PlanningReads;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** The window's board contract; unfinished actions refuse without changing store state or tasks. */
final class PlanningMethods {
    private PlanningMethods() {}

    static PlanningChanges changes(
            PoreloContext context, Function<String, PlanningReadSnapshot> snapshots) {
        return PlanningChanges.installed(
                project ->
                        new PlanningReads(
                                context,
                                project,
                                PoreloDaemon.projectFile(Map.of("project", project), context)),
                snapshots);
    }

    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control,
            Function<String, PlanningReadSnapshot> snapshots,
            PlanningChanges changes) {
        for (String method :
                List.of(
                        "GetPlanningWorkflow",
                        "ListStories",
                        "GetStory",
                        "ListStoryIssues",
                        "GetIssue")) {
            server.method(
                    method,
                    (parameters, replies) -> {
                        final var file = PoreloDaemon.projectFile(parameters, context);
                        final String project = PoreloDaemon.text(parameters, "project");
                        try (var reads =
                                new PlanningReads(
                                        context, project, file, snapshots.apply(project))) {
                            replies.last(reads.read(method, parameters));
                        }
                    });
        }
        server.method(
                "WatchStories",
                (parameters, replies) -> {
                    PoreloDaemon.projectFile(parameters, context);
                    watch(changes, PoreloDaemon.text(parameters, "project"), replies);
                });
        server.method(
                "RefreshStories",
                (parameters, replies) -> {
                    PoreloDaemon.projectFile(parameters, context);
                    refresh(changes, PoreloDaemon.text(parameters, "project"), replies);
                });
        server.method(
                "GetStoryWork",
                (parameters, replies) -> {
                    PoreloDaemon.projectFile(parameters, context);
                    final var work =
                            snapshots
                                    .apply(PoreloDaemon.text(parameters, "project"))
                                    .stories()
                                    .get(PoreloDaemon.text(parameters, "story"));
                    replies.last(work == null ? Map.of() : Map.of("work", work.asMap()));
                });
        for (String method :
                List.of(
                        "ProposeStoryWork",
                        "StartStoryWork",
                        "StopStoryWork",
                        "SaveMoves",
                        "ResolveIssueReading")) {
            server.method(
                    method,
                    (parameters, replies) -> {
                        throw notBuilt(method);
                    });
        }
    }

    static void watch(PlanningChanges changes, String project, VarlinkServer.Replies replies)
            throws java.io.IOException {
        if (!replies.streaming()) {
            replies.last(changes.board(project));
            return;
        }
        try (var watcher = changes.watch(project)) {
            while (replies.open()) {
                final var board = watcher.next(200);
                if (board != null) replies.more(board);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted while watching the board", ex);
        }
    }

    static void refresh(PlanningChanges changes, String project, VarlinkServer.Replies replies)
            throws java.io.IOException {
        replies.last(Map.of("revision", changes.refresh(project)));
    }

    private static VarlinkException notBuilt(String method) {
        return new VarlinkException(
                PoreloDaemon.INTERFACE + ".NotYetBuilt", Map.of("method", method));
    }
}
