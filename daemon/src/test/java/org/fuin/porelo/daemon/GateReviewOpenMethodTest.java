package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.ReviewOpen;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewComments;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ReviewOpen over the daemon's socket: the answer by the contract's names, its refusals and its
 * parameters, over a real journal and kept ends in a temporary directory with the gate stood in.
 */
class GateReviewOpenMethodTest {

    private static final String CONTAINER = "c".repeat(64);

    private static final String HEAD = "a".repeat(40);

    private static final Push SHOP = new Push("uc", "shop", "shop-2", HEAD, null, CONTAINER);

    @TempDir Path dir;

    private boolean waits = true;

    private ReviewComments journal;

    private PushEnds ends;

    private @Nullable VarlinkServer server;

    private @Nullable VarlinkClient client;

    @BeforeEach
    void serving() throws Exception {
        journal =
                new ReviewComments(
                        dir.resolve("review"),
                        Clock.fixed(Instant.parse("2026-11-25T09:30:00Z"), ZoneOffset.UTC));
        ends = new PushEnds(dir.resolve("review"));
        ends.keep(
                CONTAINER, PushEnd.waiting("shop-2", HEAD, Instant.parse("2026-11-25T08:00:00Z")));
        server = new VarlinkServer(dir.resolve("o.sock"), PoreloDaemon.INTERFACE);
        GateMethods.registerReviewOpen(
                server,
                new ReviewOpen(
                        new ReviewOpen.Parts(
                                (project, repository, name) -> {
                                    if (name.equals("broken")) {
                                        throw new java.io.UncheckedIOException(
                                                new java.io.IOException(
                                                        "the gate's mirror cannot be read"));
                                    }
                                    if (!name.equals("shop-2")) {
                                        throw ReviewOpen.Refused.placeUnreadable(
                                                "NoSuchPush", "no task is recorded for " + name);
                                    }
                                    return new ReviewOpen.Standing(
                                            "uc",
                                            "shop",
                                            "shop-2",
                                            CONTAINER,
                                            "shop-2",
                                            waits ? SHOP : null);
                                },
                                journal,
                                journal,
                                ends::read)));
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());
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

    private static String method(final String name) {
        return PoreloDaemon.INTERFACE + "." + name;
    }

    private static Map<String, Object> asked(final Object place, final Object... more) {
        final Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("project", "uc");
        call.put("place", place);
        for (int i = 0; i < more.length; i += 2) {
            call.put((String) more[i], more[i + 1]);
        }
        return call;
    }

    private static Map<String, Object> gatePush(final String name) {
        return Map.of("gatePush", Map.of("name", name));
    }

    @Test
    void aWaitingPushIsAnsweredByTheContractsNamesWithItsOpenThread() throws Exception {
        final String thread =
                journal.keep(
                                SHOP,
                                new ReviewComment(
                                        new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                                        new Body("Keep the order."),
                                        null),
                                Origin.of(new OwnerAccount()),
                                null)
                        .thread();

        final Map<String, Object> answer =
                client.call(method("ReviewOpen"), asked(gatePush("shop-2")));

        assertThat(answer)
                .as("the answer")
                .containsEntry("decision", "OPEN")
                .containsEntry("head", HEAD)
                .containsEntry("decisionSince", "2026-11-25T08:00:00Z")
                .containsEntry("complete", true)
                .containsKey("version")
                .doesNotContainKeys("mergedCommit", "reason");
        assertThat(answer.get("points"))
                .as("the owner account's open thread")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "thread",
                                        thread,
                                        "path",
                                        "src/Shop.java",
                                        "line",
                                        3L,
                                        "lastBy",
                                        "owner account",
                                        "lastAt",
                                        "2026-11-25T09:30:00Z")));
        assertThat(client.call(method("ReviewOpen"), asked(gatePush("shop-2"))))
                .as("asked again with nothing changed")
                .isEqualTo(answer);
    }

    @Test
    void anApprovedPushAnswersTheCommitThatWentAndItsBranch() throws Exception {
        waits = false;
        ends.keep(
                CONTAINER,
                PushEnd.approved(
                        "shop-2",
                        HEAD,
                        "f".repeat(40),
                        "main",
                        Instant.parse("2026-11-25T11:00:00Z")));

        final Map<String, Object> answer =
                client.call(
                        method("ReviewOpen"),
                        asked(gatePush("shop-2"), "role", "REVIEWER", "task", "planner-1"));

        assertThat(answer)
                .as("the decision and what went")
                .containsEntry("decision", "APPROVED")
                .containsEntry("mergedCommit", "f".repeat(40))
                .containsEntry("mergedHead", "main")
                .containsEntry("decisionSince", "2026-11-25T11:00:00Z");
    }

    @Test
    void aPlaceThatCannotBeReadIsPlaceUnreadableWithItsReason() {
        assertThatThrownBy(() -> client.call(method("ReviewOpen"), asked(gatePush("cart-1"))))
                .as("no such push")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("the error")
                                    .isEqualTo(method("PlaceUnreadable"));
                            assertThat(ex.getParameters())
                                    .as("its fields")
                                    .containsEntry("reason", "NoSuchPush")
                                    .containsKey("detail");
                        });
        assertThatThrownBy(
                        () ->
                                client.call(
                                        method("ReviewOpen"),
                                        asked(Map.of("pullRequest", "feature"))))
                .as("a pull request is not read yet")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getParameters())
                                        .as("its reason")
                                        .containsEntry("reason", "ForgeAdapterMissing"));
    }

    @Test
    void recordsThatCannotBeReadArePlaceUnreadableAsUnreachableNeverAGenericError()
            throws Exception {
        final java.nio.file.Path end = dir.resolve("review").resolve(CONTAINER).resolve("end");
        java.nio.file.Files.writeString(end, "{\"cut");

        for (final String name : List.of("shop-2", "broken")) {
            assertThatThrownBy(() -> client.call(method("ReviewOpen"), asked(gatePush(name))))
                    .as("%s: the ends or the gate cannot be read", name)
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex -> {
                                assertThat(ex.getErrorName())
                                        .as("the error")
                                        .isEqualTo(method("PlaceUnreadable"));
                                assertThat(ex.getParameters())
                                        .as("its reason and a detail")
                                        .containsEntry("reason", "Unreachable")
                                        .containsKey("detail");
                            });
        }
    }

    @Test
    void theOwnPullRequestWithoutATaskAndAPlaceOfNoneOrTwoFieldsAreInvalidParameters() {
        invalid(asked(Map.of("ownPullRequest", true)), "task");
        invalid(asked(Map.of()), "place");
        invalid(
                asked(Map.of("gatePush", Map.of("name", "shop-2"), "pullRequest", "feature")),
                "place");
        invalid(asked(gatePush("shop-2"), "role", "OWNER"), "role");
    }

    private void invalid(final Map<String, Object> call, final String parameter) {
        assertThatThrownBy(() -> client.call(method("ReviewOpen"), call))
                .as("%s refused", call)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("the error")
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(ex.getParameters())
                                    .as("the parameter")
                                    .containsEntry("parameter", parameter);
                        });
    }
}
