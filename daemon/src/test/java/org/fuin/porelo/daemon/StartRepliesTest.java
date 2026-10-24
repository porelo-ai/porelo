package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.StartStep;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Guards the progress part of Start's reply without running a machine. */
class StartRepliesTest {
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void aStartPreservesTheExactReplySequenceForOptedInLegacyAndCollectedClients(
            boolean streaming, boolean steps) throws java.io.IOException {
        // An unsolicited step-only reply breaks clients that require a line on each continuation.
        final List<Map<String, Object>> sent = new ArrayList<>();
        final VarlinkServer.Replies replies =
                new VarlinkServer.Replies() {
                    /** {@inheritDoc} */
                    @Override
                    public void more(Map<String, Object> reply) {
                        sent.add(reply);
                    }

                    /** {@inheritDoc} */
                    @Override
                    public void last(Map<String, Object> reply) {
                        sent.add(reply);
                    }

                    /** {@inheritDoc} */
                    @Override
                    public boolean streaming() {
                        return streaming;
                    }

                    /** {@inheritDoc} */
                    @Override
                    public boolean open() {
                        return true;
                    }
                };
        final StartReplies progress =
                steps ? new StartReplies(replies, true) : new StartReplies(replies);
        final StringWriter collected = new StringWriter();
        final PrintWriter lines = new PrintWriter(streaming ? progress.stream() : collected, true);
        final List<StartStep> events =
                List.of(
                        new StartStep(
                                StartStep.Step.SOURCE,
                                StartStep.State.STARTED,
                                "Reading the source",
                                "",
                                ""),
                        new StartStep(
                                StartStep.Step.SOURCE,
                                StartStep.State.DONE,
                                "Reading the source",
                                "",
                                ""),
                        new StartStep(
                                StartStep.Step.IMAGE,
                                StartStep.State.STARTED,
                                "Preparing the image",
                                "",
                                ""),
                        new StartStep(
                                StartStep.Step.IMAGE,
                                StartStep.State.WAITING,
                                "Preparing the image",
                                "podman build",
                                ""));
        lines.println("first line");
        progress.accept(events.get(0));
        lines.println("second line");
        progress.accept(events.get(1));
        progress.accept(events.get(2));
        lines.println("third line");
        progress.accept(events.get(3));
        final Map<String, Object> last = new LinkedHashMap<>();
        last.put("exitCode", 0);
        last.put("output", collected.toString().lines().toList());
        progress.finish(last);
        replies.last(last);
        final List<Map<String, Object>> expected = new ArrayList<>();
        if (streaming) {
            expected.add(Map.of("line", "first line"));
            if (steps) {
                expected.add(Map.of("step", events.get(0).asMap()));
            }
            expected.add(Map.of("line", "second line"));
            if (steps) {
                expected.add(Map.of("step", events.get(1).asMap()));
                expected.add(Map.of("step", events.get(2).asMap()));
            }
            expected.add(Map.of("line", "third line"));
            if (steps) {
                expected.add(Map.of("step", events.get(3).asMap()));
            }
            expected.add(Map.of("exitCode", 0, "output", List.of()));
        } else {
            expected.add(
                    Map.of(
                            "exitCode",
                            0,
                            "output",
                            List.of("first line", "second line", "third line"),
                            "steps",
                            events.stream().map(StartStep::asMap).toList()));
        }
        assertThat(sent)
                .as("reply order and shape must preserve legacy lines and opt-in steps exactly")
                .containsExactlyElementsOf(expected);
    }
}
