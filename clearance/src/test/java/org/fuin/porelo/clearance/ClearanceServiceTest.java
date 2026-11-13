package org.fuin.porelo.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link ClearanceService}: what a report carries to every subscriber. */
class ClearanceServiceTest {

    @Test
    void aReportReachesSubscribersAsWhatWasBlockedAndNothingItsSenderAdded(@TempDir Path dir)
            throws Exception {

        // Forwarded as it came, a report carrying 'verdict' and 'source' read to an interface like
        // an answer.
        final ClearanceHub hub =
                new ClearanceHub("uc", "shell", request -> Verdict.DENY, (address, name) -> {});
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (ClearanceService service = new ClearanceService(dir.resolve("c.sock"), hub)) {
            service.start();
            final Thread listener =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (VarlinkClient client =
                                                new VarlinkClient(dir.resolve("c.sock"))) {
                                            client.callMore(
                                                    ClearanceService.INTERFACE + ".Subscribe",
                                                    Map.of(),
                                                    event ->
                                                            events.add(event) && events.size() < 2);
                                        } catch (Exception ex) {
                                            // The test reads what arrived.
                                        }
                                    });
            for (int i = 0; i < 50 && service.subscriberCount() == 0; i++) {
                Thread.sleep(20);
            }
            try (VarlinkClient reporter = new VarlinkClient(dir.resolve("c.sock"))) {
                reporter.call(
                        ClearanceService.INTERFACE + ".Report",
                        Map.of(
                                "destination",
                                "1.1.1.1",
                                "port",
                                443,
                                "protocol",
                                "tcp",
                                "verdict",
                                "allow",
                                "source",
                                "client"));
            }
            listener.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst())
                .containsEntry("destination", "1.1.1.1")
                .doesNotContainKeys("verdict", "source");
    }

    @Test
    void aSubscriberThatComesLaterIsStillToldTheQuestionsStillOpen(@TempDir Path dir)
            throws Exception {

        // The daemon subscribes when a list asks it to: a question put before then reached
        // nobody, and its retries are not put again, so the list never showed it.
        final ClearanceHub hub =
                new ClearanceHub("uc", "shell", request -> Verdict.DENY, (address, name) -> {});
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (ClearanceService service = new ClearanceService(dir.resolve("c.sock"), hub)) {
            service.start();
            service.publish(question("tcp/1.1.1.1/443", "1.1.1.1"));
            service.publish(question("tcp/9.9.9.9/443", "9.9.9.9"));
            hub.decide("tcp/9.9.9.9/443", "9.9.9.9", Verdict.DENY);

            subscribe(dir, events, 1);
        }

        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .containsEntry("key", "tcp/1.1.1.1/443")
                .doesNotContainKey("verdict");
    }

    private static Map<String, Object> question(String key, String address) {
        final Map<String, Object> question = new java.util.LinkedHashMap<>();
        question.put("key", key);
        question.put("destination", address);
        question.put("address", address);
        question.put("protocol", "tcp");
        question.put("port", 443);
        return question;
    }

    /** Takes what arrives within a second, at most this many events. */
    private static void subscribe(Path dir, List<Map<String, Object>> events, int most)
            throws InterruptedException {
        final Thread listener =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (VarlinkClient client =
                                            new VarlinkClient(dir.resolve("c.sock"))) {
                                        client.callMore(
                                                ClearanceService.INTERFACE + ".Subscribe",
                                                Map.of(),
                                                event -> events.add(event) && events.size() < most);
                                    } catch (Exception ex) {
                                        // The test reads what arrived.
                                    }
                                });
        listener.join(TimeUnit.SECONDS.toMillis(1));
        Thread.sleep(300);
    }
}
