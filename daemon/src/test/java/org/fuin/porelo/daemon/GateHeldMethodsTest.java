package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.fuin.porelo.app.HeldStandIns.PLACE;
import static org.fuin.porelo.app.HeldStandIns.TASK;
import static org.fuin.porelo.app.HeldStandIns.incoming;
import static org.fuin.porelo.app.HeldStandIns.outgoing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.HeldStandIns;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HeldItems and DecideItem over the daemon's socket, against a stand-in store. */
class GateHeldMethodsTest {

    @TempDir Path dir;

    private final HeldStandIns.Holds holds = new HeldStandIns.Holds();
    private @Nullable VarlinkServer server;
    private @Nullable VarlinkClient client;

    private VarlinkClient serving() throws Exception {
        return serving(holds);
    }

    private VarlinkClient serving(final ReviewHolds store) throws Exception {
        server = new VarlinkServer(dir.resolve("h.sock"), PoreloDaemon.INTERFACE);
        GateMethods.registerHolds(server, store);
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());
        return client;
    }

    @AfterEach
    void close() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static Map<String, Object> call(final String... pairs) {
        final Map<String, Object> call = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            call.put(pairs[i], pairs[i + 1]);
        }
        return call;
    }

    private Map<String, Object> decide(final Held held, final String decision) {
        return call(
                "task",
                held.task(),
                "id",
                held.id(),
                "digest",
                held.digest(),
                "decision",
                decision);
    }

    @Test
    void heldItemsListsEachItemWithItsDestinationOperationCommitAndExactText() throws Exception {
        // The owner account decides on exactly what it was shown, control characters included.
        final Held out = outgoing("h1", "Keep \u001b[2J the order.", 10);
        final Held in = incoming("h2", 20);
        holds.with(out, in);

        final Map<String, Object> answer =
                serving().call(PoreloDaemon.INTERFACE + ".HeldItems", Map.of());

        assertThat(answer).as("the last page").doesNotContainKey("cursor");
        final List<?> items = (List<?>) answer.get("items");
        assertThat(items).as("both items, oldest first").hasSize(2);
        final Map<String, Object> first = fields(items.get(0));
        assertThat(first)
                .as("the outgoing comment")
                .containsEntry("id", "h1")
                .containsEntry("task", TASK)
                .containsEntry("place", PLACE)
                .containsEntry("direction", "outgoing")
                .containsEntry("destination", PLACE)
                .containsEntry("operation", "comment")
                .containsEntry("path", "src/Shop.java")
                .containsEntry("side", "new")
                .containsEntry("suggestion", "orders.sort();\n")
                .containsEntry("commit", "a".repeat(40))
                .containsEntry("text", "Keep \u001b[2J the order.")
                .containsEntry("digest", out.digest())
                .containsEntry("at", "1970-01-01T00:00:10Z")
                .doesNotContainKeys("thread", "from");
        assertThat(((Number) first.get("startLine")).intValue()).as("first line").isEqualTo(3);
        assertThat(((Number) first.get("endLine")).intValue()).as("last line").isEqualTo(5);
        assertThat(((Number) first.get("run")).intValue()).as("run").isEqualTo(1);
        final Map<String, Object> second = fields(items.get(1));
        assertThat(second)
                .as("the incoming reply")
                .containsEntry("direction", "incoming")
                .containsEntry("destination", "task " + TASK)
                .containsEntry("operation", "reply")
                .containsEntry("thread", "1834527690")
                .containsEntry("from", "octocat")
                .doesNotContainKeys("path", "side", "startLine", "endLine", "suggestion");
    }

    @Test
    void heldItemsMarksAnItemNoCertifiedKeySignedAndLeavesTheMarkOutOfEveryOther()
            throws Exception {
        holds.with(
                org.fuin.porelo.app.HeldStandIns.unsigned(outgoing("h1", "Keep the order.", 10)),
                outgoing("h2", "ok", 20),
                incoming("h3", 30));

        final Map<String, Object> answer =
                serving().call(PoreloDaemon.INTERFACE + ".HeldItems", Map.of());

        final List<?> items = (List<?>) answer.get("items");
        assertThat(fields(items.get(0))).as("the unsigned item").containsEntry("mark", "unsigned");
        assertThat(fields(items.get(1))).as("the signed item").doesNotContainKey("mark");
        assertThat(fields(items.get(2))).as("the incoming item").doesNotContainKey("mark");
    }

    @Test
    void heldItemsPagesOldestFirstAndRefusesACursorNoPageGave() throws Exception {
        for (int i = 0; i < 30; i++) {
            holds.with(outgoing("h" + i, "Item " + i, i));
        }
        final VarlinkClient client = serving();
        final String method = PoreloDaemon.INTERFACE + ".HeldItems";

        final Map<String, Object> first = client.call(method, call("task", TASK));
        final List<Object> ids = new ArrayList<>();
        ((List<?>) first.get("items")).forEach(item -> ids.add(((Map<?, ?>) item).get("id")));
        final Map<String, Object> second =
                client.call(method, call("task", TASK, "cursor", (String) first.get("cursor")));
        ((List<?>) second.get("items")).forEach(item -> ids.add(((Map<?, ?>) item).get("id")));

        assertThat(ids)
                .as("every item once, oldest first")
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 30).mapToObj(i -> "h" + i).toList());
        assertThat(second).as("the last page").doesNotContainKey("cursor");
        assertThat(holds.listed).as("the task asked for").containsOnly(TASK);
        assertThatThrownBy(() -> client.call(method, call("cursor", "7.0123456789abcdef")))
                .as("a cursor no page gave")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("the error")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".CursorRefused"));
    }

    @Test
    void aCursorIsRefusedOnceAnEarlierItemWasDecided() throws Exception {
        for (int i = 0; i < 30; i++) {
            holds.with(outgoing("h" + i, "Item " + i, i));
        }
        final VarlinkClient client = serving();
        final String method = PoreloDaemon.INTERFACE + ".HeldItems";
        final String cursor = (String) client.call(method, call("task", TASK)).get("cursor");
        final Held third = holds.items.get("h3");

        client.call(PoreloDaemon.INTERFACE + ".DecideItem", decide(third, "release"));

        assertThatThrownBy(() -> client.call(method, call("task", TASK, "cursor", cursor)))
                .as("the cursor after a decision among the items before it")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("the error")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".CursorRefused"));
    }

    @Test
    void aCursorOfOneTasksListingIsRefusedForAnotherTaskAndForEveryTask() throws Exception {
        final String other = "porelo-uc_shop-3";
        for (int i = 0; i < 30; i++) {
            holds.with(outgoing("a" + i, "Item " + i, i));
        }
        for (int i = 0; i < 30; i++) {
            holds.with(outgoing(other, "b" + i, "Item " + i, 100 + i));
        }
        final VarlinkClient client = serving();
        final String method = PoreloDaemon.INTERFACE + ".HeldItems";
        final String cursor = (String) client.call(method, call("task", TASK)).get("cursor");

        for (final Map<String, Object> asked :
                List.of(call("task", other, "cursor", cursor), call("cursor", cursor))) {
            assertThatThrownBy(() -> client.call(method, asked))
                    .as("task %s's cursor in %s", TASK, asked)
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(ex.getErrorName())
                                            .as("the error")
                                            .isEqualTo(PoreloDaemon.INTERFACE + ".CursorRefused"));
        }
    }

    @Test
    void everyTasksHeldItemsAreListedAndATaskWhoseJournalCannotBeReadIsNamed() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal journal = new HoldJournal(root);
        final String broken = "porelo-uc_shop-3";
        final Held healthy = HeldStandIns.heldIn(journal, TASK);
        HeldStandIns.heldIn(journal, broken);
        java.nio.file.Files.writeString(root.resolve(broken).resolve("holds"), "not json\n");

        final Map<String, Object> answer =
                serving(journal).call(PoreloDaemon.INTERFACE + ".HeldItems", Map.of());

        final List<?> items = (List<?>) answer.get("items");
        assertThat(items).as("the readable task's item").hasSize(1);
        assertThat(fields(items.getFirst())).as("that item").containsEntry("id", healthy.id());
        assertThat(answer.get("unreadable"))
                .as("the task whose journal cannot be read")
                .isEqualTo(List.of(broken));
    }

    @Test
    void eachRefusedDecisionIsAnsweredByItsOwnNameWithTheItemsId() throws Exception {
        final Held fresh = outgoing("h1", "Keep the order.", 1);
        final Held decided = outgoing("h2", "Keep it.", 2);
        final Held lapsed = outgoing("h3", "Late.", 3).in(HeldState.LAPSED, null, null);
        holds.with(fresh, decided, lapsed);
        final VarlinkClient client = serving();
        final String method = PoreloDaemon.INTERFACE + ".DecideItem";
        client.call(method, decide(decided, "release"));

        final Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("NoSuchItem", decide(outgoing("h9", "Gone.", 9), "release"));
        final Map<String, Object> changed = decide(fresh, "release");
        changed.put("digest", "0".repeat(64));
        calls.put("DigestChanged", changed);
        calls.put("AlreadyDecided", decide(decided, "refuse"));
        calls.put("Lapsed", decide(lapsed, "release"));
        for (final Map.Entry<String, Map<String, Object>> each : calls.entrySet()) {
            assertThatThrownBy(() -> client.call(method, each.getValue()))
                    .as(each.getKey())
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex -> {
                                assertThat(ex.getErrorName())
                                        .as("the error")
                                        .isEqualTo(PoreloDaemon.INTERFACE + "." + each.getKey());
                                assertThat(ex.getParameters())
                                        .as("names the item")
                                        .containsEntry("id", each.getValue().get("id"));
                            });
        }
        assertThat(holds.decided)
                .as("only the one decision that fit")
                .containsExactly(TASK + " h2 RELEASE");
    }

    @Test
    void aDecisionThatDoesNotFitTheItemOrIsNoneIsAnInvalidParameter() throws Exception {
        final Held in = incoming("h1", 1);
        holds.with(in);
        final VarlinkClient client = serving();
        final String method = PoreloDaemon.INTERFACE + ".DecideItem";

        for (final String decision : List.of("release", "approve")) {
            assertThatThrownBy(() -> client.call(method, decide(in, decision)))
                    .as(decision + " on an incoming item")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex -> {
                                assertThat(ex.getErrorName())
                                        .as("the error")
                                        .isEqualTo("org.varlink.service.InvalidParameter");
                                assertThat(ex.getParameters())
                                        .as("names the decision")
                                        .containsEntry("parameter", "decision");
                            });
        }
        client.call(method, decide(in, "pass"));
        assertThat(holds.decided).as("pass fits").containsExactly(TASK + " h1 PASS");
    }

    @Test
    void aReleaseThroughTheCommandLineAndOneThroughTheSocketReachTheSameStore() throws Exception {
        final Held one = outgoing("h1", "Keep the order.", 1);
        final Held two = outgoing("h2", "Keep it.", 2);
        holds.with(one, two);
        serving().call(PoreloDaemon.INTERFACE + ".DecideItem", decide(one, "release"));

        final HeldStandIns.Ran ran =
                HeldStandIns.decide(holds, TASK, "h2", "release", "--digest", two.digest());

        assertThat(ran.code()).as(ran.err()).isZero();
        assertThat(ran.out()).as("nothing said on success").isEmpty();
        assertThat(holds.decided)
                .as("both decisions in the one store")
                .containsExactly(TASK + " h1 RELEASE", TASK + " h2 RELEASE");
    }

    @Test
    void aReleaseIsHandedOnToBeSentAndARefusalIsNot() throws Exception {
        final Held one = outgoing("h1", "Keep the order.", 1);
        final Held two = outgoing("h2", "Keep it.", 2);
        holds.with(one, two);
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch passed =
                new java.util.concurrent.CountDownLatch(1);
        server = new VarlinkServer(dir.resolve("h.sock"), PoreloDaemon.INTERFACE);
        GateMethods.registerHolds(
                server,
                holds,
                () -> {
                    sent.add("pass");
                    passed.countDown();
                });
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());

        client.call(PoreloDaemon.INTERFACE + ".DecideItem", decide(one, "refuse"));
        assertThat(sent).as("nothing sent after a refusal").isEmpty();
        client.call(PoreloDaemon.INTERFACE + ".DecideItem", decide(two, "release"));

        assertThat(passed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("a pass ran after the answer")
                .isTrue();
        assertThat(sent).as("one sending pass after the release").containsExactly("pass");
    }

    /** An answer's object, its keys as text, so it can be asserted by name. */
    private static Map<String, Object> fields(final Object value) {
        final Map<String, Object> fields = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, each) -> fields.put(String.valueOf(key), each));
        return fields;
    }
}
