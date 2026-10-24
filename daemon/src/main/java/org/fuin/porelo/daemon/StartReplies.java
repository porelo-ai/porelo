package org.fuin.porelo.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.app.StartStep;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** Keeps the streamed and collected forms of Start's progress consistent. */
final class StartReplies implements Consumer<StartStep> {
    private final VarlinkServer.Replies replies;
    private final boolean steps;
    private final PoreloDaemon.StreamingWriter stream;
    private final List<Map<String, Object>> collected = new ArrayList<>();

    StartReplies(VarlinkServer.Replies replies) {
        this(replies, false);
    }

    StartReplies(VarlinkServer.Replies replies, boolean steps) {
        this.replies = replies;
        this.steps = steps;
        this.stream = new PoreloDaemon.StreamingWriter(replies);
    }

    PoreloDaemon.StreamingWriter stream() {
        return stream;
    }

    /** {@inheritDoc} */
    @Override
    public void accept(StartStep step) {
        if (replies.streaming()) {
            if (steps) {
                stream.send(Map.of("step", step.asMap()));
            }
        } else {
            collected.add(step.asMap());
        }
    }

    void finish(Map<String, Object> reply) {
        if (!replies.streaming()) {
            reply.put("steps", List.copyOf(collected));
        }
    }
}
