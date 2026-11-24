package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fuin.porelo.app.CommentIntake;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Comment, Reply, Comments and ResolveThread over the daemon's socket, against stand-ins. */
class GateCommentMethodsTest {

    private static final Push SHOP =
            new Push("uc", "shop", "shop-2", "a".repeat(40), "b".repeat(40), "c".repeat(64));

    private static final Push OTHER =
            new Push("uc", "shop", "shop-3", "d".repeat(40), null, "e".repeat(64));

    @TempDir Path dir;

    private final Check check = new Check();
    private final Store store = new Store();
    private final Writer writer = new Writer();
    private @Nullable VarlinkServer server;
    private @Nullable VarlinkClient client;

    /** Answers a fixed refusal or none, and records what it was asked. */
    static final class Check implements CommentCheck {
        final List<ReviewItem> asked = new ArrayList<>();
        @Nullable ReviewRefusal answer;

        @Override
        public Optional<ReviewRefusal> check(final Push push, final ReviewItem item) {
            asked.add(item);
            return Optional.ofNullable(answer);
        }
    }

    /** Keeps in memory, with retry ids and thread states, as the store's contract says. */
    static final class Store implements ReviewStore {
        final List<Kept> kept = new ArrayList<>();
        final Map<String, Push> threadPush = new LinkedHashMap<>();
        final Map<String, ThreadState> states = new HashMap<>();
        final Map<String, Kept> byId = new HashMap<>();

        @Override
        public synchronized Kept keep(
                final Push push,
                final ReviewItem item,
                final Origin origin,
                final @Nullable String id)
                throws ReviewRefused {
            final String key = push.name() + "/" + id;
            if (id != null && byId.containsKey(key)) {
                final Kept first = byId.get(key);
                if (first.item().equals(item) && first.origin().equals(origin)) {
                    return first;
                }
                throw new ReviewRefused(new ReviewRefusal(ReviewError.ID_REUSED, null));
            }
            final String thread;
            if (item instanceof ReviewReply reply) {
                if (!push.equals(threadPush.get(reply.thread()))) {
                    throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
                }
                thread = reply.thread();
            } else {
                thread = UUID.randomUUID().toString();
                threadPush.put(thread, push);
                states.put(thread, ThreadState.OPEN);
            }
            final Kept one = new Kept(UUID.randomUUID().toString(), thread, origin, item);
            kept.add(one);
            if (id != null) {
                byId.put(key, one);
            }
            return one;
        }

        @Override
        public synchronized List<ReviewThread> threads(final Push push) {
            final List<ReviewThread> threads = new ArrayList<>();
            threadPush.forEach(
                    (thread, owner) -> {
                        if (owner.equals(push)) {
                            threads.add(thread(thread));
                        }
                    });
            return threads;
        }

        private ReviewThread thread(final String id) {
            return new ReviewThread(
                    id,
                    states.get(id),
                    kept.stream().filter(each -> each.thread().equals(id)).toList());
        }

        @Override
        public synchronized ReviewThread state(
                final Push push, final String thread, final ThreadState state)
                throws ReviewRefused {
            if (!push.equals(threadPush.get(thread))) {
                throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
            }
            states.put(thread, state);
            return thread(thread);
        }
    }

    /** Answers a fixed page or refusal, and records what it was given. */
    static final class Writer implements ReviewWriter {
        @Nullable Push push;
        @Nullable List<ReviewThread> threads;
        @Nullable String cursor;
        Page page = new Page("{\"version\":\"2.1.0\",\"runs\":[]}", null);
        @Nullable ReviewRefusal refusal;

        @Override
        public synchronized Page page(
                final Push push, final List<ReviewThread> threads, final @Nullable String cursor)
                throws ReviewRefused {
            this.push = push;
            this.threads = threads;
            this.cursor = cursor;
            if (refusal != null) {
                throw new ReviewRefused(refusal);
            }
            return page;
        }
    }

    private static CommentIntake.Pushes pushes() {
        return (project, repository, name) -> {
            for (final Push push : List.of(SHOP, OTHER)) {
                if (push.project().equals(project)
                        && push.repository().equals(repository)
                        && push.name().equals(name)) {
                    return push;
                }
            }
            throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
        };
    }

    private VarlinkClient serving(final CommentIntake.Parts parts) throws Exception {
        server = new VarlinkServer(dir.resolve("c.sock"), PoreloDaemon.INTERFACE);
        GateMethods.registerComments(server, parts);
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());
        return client;
    }

    private VarlinkClient serving() throws Exception {
        return serving(new CommentIntake.Parts(pushes(), check, store, writer));
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

    private static Map<String, Object> comment(final Push push, final String... more) {
        final Map<String, Object> call = new LinkedHashMap<>();
        call.put("project", push.project());
        call.put("repository", push.repository());
        call.put("name", push.name());
        call.put("commit", push.head());
        call.put("path", "src/Shop.java");
        call.put("side", "new");
        call.put("startLine", 3);
        call.put("endLine", 5);
        call.put("text", "Keep the order.");
        for (int i = 0; i < more.length; i += 2) {
            call.put(more[i], more[i + 1]);
        }
        return call;
    }

    private static Map<String, Object> named(final Push push, final Object... more) {
        final Map<String, Object> call = new LinkedHashMap<>();
        call.put("project", push.project());
        call.put("repository", push.repository());
        call.put("name", push.name());
        for (int i = 0; i < more.length; i += 2) {
            call.put((String) more[i], more[i + 1]);
        }
        return call;
    }

    private static String method(final String name) {
        return PoreloDaemon.INTERFACE + "." + name;
    }

    private static void refusedAs(
            final org.assertj.core.api.ThrowableAssert.ThrowingCallable call, final String error) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> assertThat(ex.getErrorName()).isEqualTo(method(error)));
    }

    @Test
    void aCommentIsKeptAsTheOwnerAccountsAndReadBackThroughTheWriter() throws Exception {
        // The interface and the plugin read what was written only through Comments.
        final VarlinkClient client = serving();

        final Map<String, Object> answer =
                client.call(method("Comment"), comment(SHOP, "replacement", "orders.sort();"));

        assertThat(store.kept)
                .singleElement()
                .satisfies(
                        kept -> {
                            assertThat(answer)
                                    .containsEntry("guid", kept.guid())
                                    .containsEntry("thread", kept.thread());
                            assertThat(kept.origin()).isEqualTo(Origin.of(new OwnerAccount()));
                            assertThat(kept.item())
                                    .isInstanceOfSatisfying(
                                            ReviewComment.class,
                                            item -> {
                                                assertThat(item.anchor())
                                                        .isEqualTo(
                                                                new LineAnchor(
                                                                        "src/Shop.java",
                                                                        Side.NEW,
                                                                        3,
                                                                        5));
                                                assertThat(item.suggestion()).isNotNull();
                                            });
                        });
        final Map<String, Object> page = client.call(method("Comments"), named(SHOP));
        assertThat(page).containsEntry("sarif", writer.page.sarif()).doesNotContainKey("cursor");
        assertThat(writer.push).isEqualTo(SHOP);
        assertThat(writer.threads).isEqualTo(store.threads(SHOP));
        assertThat(writer.cursor).isNull();
    }

    @Test
    void aReplyJoinsItsThread() throws Exception {
        final VarlinkClient client = serving();
        final String thread = (String) client.call(method("Comment"), comment(SHOP)).get("thread");

        final Map<String, Object> answer =
                client.call(
                        method("Reply"),
                        named(SHOP, "commit", SHOP.head(), "thread", thread, "text", "Done."));

        assertThat(answer).containsEntry("thread", thread);
        assertThat(store.threads(SHOP))
                .singleElement()
                .satisfies(t -> assertThat(t.items()).hasSize(2));
    }

    @Test
    void aRetryWithTheSameIdAnswersTheSameCommentAndAnotherCommentUnderItIsIdReused()
            throws Exception {
        final VarlinkClient client = serving();

        final Map<String, Object> first =
                client.call(method("Comment"), comment(SHOP, "id", "r-1"));
        final Map<String, Object> again =
                client.call(method("Comment"), comment(SHOP, "id", "r-1"));

        assertThat(again).isEqualTo(first);
        assertThat(store.kept).hasSize(1);
        refusedAs(
                () -> client.call(method("Comment"), comment(SHOP, "id", "r-1", "text", "Other.")),
                "IdReused");
    }

    @Test
    void aCommentOnACommitThePushMovedPastIsMovedSinceReviewAndNothingIsAsked() throws Exception {
        final VarlinkClient client = serving();

        assertThatThrownBy(
                        () ->
                                client.call(
                                        method("Comment"), comment(SHOP, "commit", "f".repeat(40))))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).isEqualTo(method("MovedSinceReview"));
                            assertThat(ex.getParameters())
                                    .containsEntry("reviewed", "f".repeat(40))
                                    .containsEntry("now", SHOP.head());
                        });
        assertThat(check.asked).isEmpty();
        assertThat(store.kept).isEmpty();
    }

    @Test
    void aPushThatDoesNotWaitIsNoSuchPushForEveryMethod() throws Exception {
        final VarlinkClient client = serving();
        final Push gone = new Push("uc", "shop", "gone", "a".repeat(40), null, "c".repeat(64));

        refusedAs(() -> client.call(method("Comment"), comment(gone)), "NoSuchPush");
        refusedAs(() -> client.call(method("Comments"), named(gone)), "NoSuchPush");
        refusedAs(
                () -> client.call(method("ResolveThread"), named(gone, "thread", "t")),
                "NoSuchPush");
        assertThat(store.kept).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ReviewError.class)
    void eachRefusalOfTheCheckIsAnsweredByItsContractNameAndKeepsNothing(final ReviewError error)
            throws Exception {
        // A client tells a refusal by its name; one renamed or lost reads as an internal error.
        final VarlinkClient client = serving();
        check.answer = new ReviewRefusal(error, "text: 65536");

        assertThatThrownBy(() -> client.call(method("Comment"), comment(SHOP)))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).isEqualTo(method(error.wireName()));
                            if (error == ReviewError.TEXT_REFUSED) {
                                assertThat(ex.getParameters()).containsEntry("rule", "text: 65536");
                            }
                            if (error == ReviewError.TOO_LARGE) {
                                assertThat(ex.getParameters())
                                        .containsEntry("limit", "text: 65536");
                            }
                        });
        assertThat(store.kept).isEmpty();
    }

    @Test
    void eachShapeTheRecordsRefuseIsAnsweredByItsContractNameBeforeAnyCheck() throws Exception {
        final VarlinkClient client = serving();

        refusedAs(
                () ->
                        client.call(
                                method("Comment"),
                                comment(SHOP, "side", "old", "replacement", "x")),
                "OldSideFix");
        refusedAs(
                () -> client.call(method("Comment"), comment(SHOP, "side", "left")),
                "AnchorOutsideBlob");
        final Map<String, Object> zero = comment(SHOP);
        zero.put("startLine", 0);
        refusedAs(() -> client.call(method("Comment"), zero), "AnchorOutsideBlob");
        final Map<String, Object> backwards = comment(SHOP);
        backwards.put("endLine", 2);
        refusedAs(() -> client.call(method("Comment"), backwards), "AnchorOutsideBlob");
        assertThatThrownBy(() -> client.call(method("Comment"), comment(SHOP, "path", " ")))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).isEqualTo(method("TextRefused"));
                            assertThat(ex.getParameters()).containsEntry("rule", "path: empty");
                        });
        assertThatThrownBy(() -> client.call(method("Comment"), comment(SHOP, "text", "")))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).isEqualTo(method("TextRefused"));
                            assertThat(ex.getParameters()).containsEntry("rule", "empty");
                        });
        refusedAs(
                () ->
                        client.call(
                                method("Reply"),
                                named(SHOP, "commit", SHOP.head(), "thread", " ", "text", "Hi.")),
                "NoSuchThread");
        assertThat(check.asked).isEmpty();
        assertThat(store.kept).isEmpty();
    }

    @Test
    void aReplyInAThreadOfAnotherPushIsNoSuchThread() throws Exception {
        final VarlinkClient client = serving();
        final String foreign =
                (String) client.call(method("Comment"), comment(OTHER)).get("thread");

        refusedAs(
                () ->
                        client.call(
                                method("Reply"),
                                named(
                                        SHOP,
                                        "commit",
                                        SHOP.head(),
                                        "thread",
                                        foreign,
                                        "text",
                                        "Hi.")),
                "NoSuchThread");
        assertThat(store.kept).hasSize(1);
    }

    @Test
    void theCursorGoesToTheWriterAndTheNextComesBackAndARefusedOneIsCursorRefused()
            throws Exception {
        final VarlinkClient client = serving();
        writer.page = new ReviewWriter.Page("{\"runs\":[]}", "next-2");

        assertThat(client.call(method("Comments"), named(SHOP, "cursor", "next-1")))
                .containsEntry("sarif", "{\"runs\":[]}")
                .containsEntry("cursor", "next-2");
        assertThat(writer.cursor).isEqualTo("next-1");

        writer.refusal = new ReviewRefusal(ReviewError.CURSOR_REFUSED, null);
        refusedAs(
                () -> client.call(method("Comments"), named(SHOP, "cursor", "forged")),
                "CursorRefused");
    }

    @Test
    void resolvingChangesOnlyTheThreadsStateAndReopenOpensIt() throws Exception {
        // A resolve that kept or approved anything would turn a click into a decision.
        final VarlinkClient client = serving();
        final String thread = (String) client.call(method("Comment"), comment(SHOP)).get("thread");
        final int kept = store.kept.size();

        assertThat(client.call(method("ResolveThread"), named(SHOP, "thread", thread)))
                .containsEntry("thread", thread)
                .containsEntry("state", "resolved");
        assertThat(store.states).containsEntry(thread, ThreadState.RESOLVED);
        assertThat(
                        client.call(
                                method("ResolveThread"),
                                named(SHOP, "thread", thread, "reopen", true)))
                .containsEntry("state", "open");
        assertThat(store.kept).hasSize(kept);
        assertThat(check.asked).hasSize(1);
    }

    @Test
    void resolvingAThreadOfAnotherPushIsNoSuchThread() throws Exception {
        final VarlinkClient client = serving();
        final String foreign =
                (String) client.call(method("Comment"), comment(OTHER)).get("thread");

        refusedAs(
                () -> client.call(method("ResolveThread"), named(SHOP, "thread", foreign)),
                "NoSuchThread");
        assertThat(store.states).containsEntry(foreign, ThreadState.OPEN);
    }

    @Test
    void untilTheReviewIsWiredEveryCallIsRefusedAsNoSuchPush() throws Exception {
        // Taken and kept nowhere, a comment would read as written and be lost.
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final CommentIntake.Parts unwired = CommentIntake.Parts.unwired(context);
        final VarlinkClient client =
                serving(
                        new CommentIntake.Parts(
                                pushes(), unwired.check(), unwired.store(), unwired.writer()));

        refusedAs(() -> client.call(method("Comment"), comment(SHOP)), "NoSuchPush");
        refusedAs(
                () ->
                        client.call(
                                method("Reply"),
                                named(SHOP, "commit", SHOP.head(), "thread", "t", "text", "Hi.")),
                "NoSuchPush");
        refusedAs(() -> client.call(method("Comments"), named(SHOP)), "NoSuchPush");
        refusedAs(
                () -> client.call(method("ResolveThread"), named(SHOP, "thread", "t")),
                "NoSuchPush");
    }

    @Test
    void aMovedSinceReviewFromTheCheckNamesTheCommitTheCallWasCheckedAgainst() throws Exception {
        // The commit the call matched is the one reviewed; where the push went the check did not
        // say, and a reviewed commit shown as the new head would send the person to review it.
        final VarlinkClient client = serving();
        check.answer = new ReviewRefusal(ReviewError.MOVED_SINCE_REVIEW, null);

        assertThatThrownBy(() -> client.call(method("Comment"), comment(SHOP)))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).isEqualTo(method("MovedSinceReview"));
                            assertThat(ex.getParameters())
                                    .containsEntry("name", SHOP.name())
                                    .containsEntry("reviewed", SHOP.head())
                                    .containsEntry("now", "");
                        });
        assertThat(store.kept).isEmpty();
    }

    @Test
    void anOptionalTextOfAnotherTypeIsInvalidParameterAndNothingIsAskedOrKept() throws Exception {
        // Read as absent, a numeric id would keep a second thread on retry, and a replacement
        // given as an object would drop the fix the caller proposed.
        final VarlinkClient client = serving();
        final Map<String, Object> replacement = comment(SHOP);
        replacement.put("replacement", 5);
        final Map<String, Object> commentId = comment(SHOP);
        commentId.put("id", true);

        invalid(() -> client.call(method("Comment"), replacement), "replacement");
        invalid(() -> client.call(method("Comment"), commentId), "id");
        invalid(
                () ->
                        client.call(
                                method("Reply"),
                                named(
                                        SHOP,
                                        "commit",
                                        SHOP.head(),
                                        "thread",
                                        "t",
                                        "text",
                                        "Hi.",
                                        "id",
                                        Map.of("x", 1))),
                "id");
        invalid(() -> client.call(method("Comments"), named(SHOP, "cursor", 7)), "cursor");
        assertThat(check.asked).isEmpty();
        assertThat(store.kept).isEmpty();
        assertThat(writer.push).isNull();
    }

    @Test
    void anEmptyIdIsNoIdSoEachCallKeepsItsOwnComment() throws Exception {
        // id is ?string: "" names no retry, as an empty optional text does in every method.
        final VarlinkClient client = serving();

        client.call(method("Comment"), comment(SHOP, "id", ""));
        client.call(method("Comment"), comment(SHOP, "id", "", "text", "Other."));

        assertThat(store.kept).hasSize(2);
    }

    private static void invalid(
            final org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            final String parameter) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(ex.getParameters()).containsEntry("parameter", parameter);
                        });
    }
}
