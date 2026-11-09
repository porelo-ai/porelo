package org.fuin.porelo.daemon;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control) {
        register(server, context, inventory, control, project -> PlanningReadSnapshot.empty());
    }

    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control,
            Function<String, PlanningReadSnapshot> snapshots) {
        for (String method :
                List.of(
                        "GetPlanningWorkflow",
                        "ListStories",
                        "WatchStories",
                        "GetStory",
                        "ListStoryIssues",
                        "GetIssue")) {
            server.method(
                    method,
                    (parameters, replies) -> {
                        if (method.equals("WatchStories") && replies.streaming()) {
                            throw notBuilt(method);
                        }
                        final var file = PoreloDaemon.projectFile(parameters, context);
                        final String project = PoreloDaemon.text(parameters, "project");
                        try (var reads =
                                new PlanningReads(
                                        context, project, file, snapshots.apply(project))) {
                            replies.last(
                                    reads.read(
                                            method.equals("WatchStories") ? "ListStories" : method,
                                            parameters));
                        }
                    });
        }
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
                        "RefreshStories",
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

    private static VarlinkException notBuilt(String method) {
        return new VarlinkException(
                PoreloDaemon.INTERFACE + ".NotYetBuilt", Map.of("method", method));
    }
}
