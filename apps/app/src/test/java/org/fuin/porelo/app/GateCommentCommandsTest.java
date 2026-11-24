package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Suggestion;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.ThreadState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code gate comment}, {@code gate comments} and {@code gate resolve}, over stand-ins. */
class GateCommentCommandsTest {

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store store = new ReviewStandIns.Store();
    private final ReviewStandIns.Writer writer = new ReviewStandIns.Writer();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(final Object command, final String... arguments) {
        ((PoreloFactory.ContextAware) command).setContext(NoAccount.context());
        final CommentIntake.Parts parts = ReviewStandIns.parts(check, store, writer);
        switch (command) {
            case GateCommentCommand comment -> comment.use(parts);
            case GateCommentsCommand comments -> comments.use(parts);
            case GateResolveCommand resolve -> resolve.use(parts);
            default -> throw new IllegalArgumentException(command.toString());
        }
        return new CommandLine((Callable<?>) command)
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(arguments);
    }

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("Keep the order."),
                    null);

    @Test
    void aCommentIsTakenThroughTheIntakeAsTheOwnerAccountsWithItsReplacement(@TempDir Path dir)
            throws Exception {
        final Path replacement = Files.writeString(dir.resolve("fix.txt"), "orders.sort();\n");

        final int code =
                run(
                        new GateCommentCommand(),
                        "-p",
                        "uc",
                        "-r",
                        "shop",
                        "--commit",
                        SHOP.head(),
                        "--path",
                        "src/Shop.java",
                        "--side",
                        "new",
                        "--lines",
                        "3-5",
                        "--text",
                        "Keep the order.",
                        "--replacement-file",
                        replacement.toString(),
                        "shop-2");

        assertThat(code).as(err.toString()).isZero();
        final ReviewComment expected =
                new ReviewComment(
                        COMMENT.anchor(), COMMENT.body(), new Suggestion("orders.sort();\n"));
        assertThat(check.asked).containsExactly(expected);
        assertThat(store.kept)
                .singleElement()
                .satisfies(
                        kept -> {
                            assertThat(kept.origin()).isEqualTo(Origin.of(new OwnerAccount()));
                            assertThat(out.toString()).contains(kept.thread(), kept.guid());
                        });
    }

    @Test
    void aReplyGoesIntoItsThreadAndACommentOnAMovedPushIsRefusedKeepingNothing() throws Exception {
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);

        assertThat(
                        run(
                                new GateCommentCommand(),
                                "-p",
                                "uc",
                                "--commit",
                                SHOP.head(),
                                "--reply-to",
                                thread,
                                "--text",
                                "Done.",
                                "shop-2"))
                .as(err.toString())
                .isZero();
        assertThat(store.kept.getLast().item())
                .isEqualTo(new ReviewReply(thread, new Body("Done.")));

        final int before = store.kept.size();
        assertThat(
                        run(
                                new GateCommentCommand(),
                                "-p",
                                "uc",
                                "--commit",
                                "f".repeat(40),
                                "--reply-to",
                                thread,
                                "--text",
                                "Late.",
                                "shop-2"))
                .isEqualTo(70);
        // The push and the head it waits at now, so the person reviews that commit again.
        assertThat(err.toString())
                .contains("MovedSinceReview", "shop-2", SHOP.head(), "f".repeat(40));
        assertThat(store.kept).hasSize(before);
    }

    @Test
    void theThreadsAreShownWithTheirProducersAndEveryLineEscapedBelowItsHeader() throws Exception {
        // Printed raw, an erase sequence or a line forged as a header would show a thread that was
        // never written, or hide the one that was.
        final ReviewComment hostile =
                new ReviewComment(
                        new LineAnchor("src/Shop\u001b[2K.java", Side.OLD, 7, 7),
                        new Body("first\n  replies\n\u001b[1Aerased"),
                        null);
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), hostile);
        store.keep(
                SHOP,
                new ReviewReply(thread, new Body("Fixed.")),
                Origin.of(new Task("shop-2")),
                null);

        assertThat(run(new GateCommentsCommand(), "-p", "uc", "shop-2"))
                .as(err.toString())
                .isZero();

        assertThat(out.toString())
                .isEqualTo(
                        "thread "
                                + thread
                                + "  open\n"
                                + "  owner account  src/Shop\\x1b[2K.java  old 7-7\n"
                                + "    first\n"
                                + "      replies\n"
                                + "    \\x1b[1Aerased\n"
                                + "  task shop-2  replies\n"
                                + "    Fixed.\n");
    }

    @Test
    void jsonPrintsTheWritersPageForTheCursorAndResolveChangesOnlyTheThreadsState()
            throws Exception {
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
        writer.page = new ReviewWriter.Page("{\"runs\":[]}", "next-1");

        assertThat(
                        run(
                                new GateCommentsCommand(),
                                "-p",
                                "uc",
                                "--json",
                                "--cursor",
                                "c-1",
                                "shop-2"))
                .as(err.toString())
                .isZero();
        assertThat(out.toString()).isEqualTo("{\"runs\":[]}\n");
        assertThat(err.toString()).contains("next-1");
        assertThat(writer.push).isEqualTo(SHOP);
        assertThat(writer.cursor).isEqualTo("c-1");
        assertThat(writer.threads).isEqualTo(store.threads(SHOP));

        final int kept = store.kept.size();
        assertThat(run(new GateResolveCommand(), "-p", "uc", "shop-2", thread)).isZero();
        assertThat(store.states).containsEntry(thread, ThreadState.RESOLVED);
        assertThat(run(new GateResolveCommand(), "-p", "uc", "--reopen", "shop-2", thread))
                .isZero();
        assertThat(store.states).containsEntry(thread, ThreadState.OPEN);
        assertThat(store.kept).hasSize(kept);
        assertThat(check.asked).as("resolving takes no item").isEmpty();
    }

    private int comment(final Path replacement) {
        return run(
                new GateCommentCommand(),
                "-p",
                "uc",
                "--commit",
                SHOP.head(),
                "--path",
                "src/Shop.java",
                "--side",
                "new",
                "--lines",
                "3",
                "--text",
                "Shorter.",
                "--replacement-file",
                replacement.toString(),
                "shop-2");
    }

    @Test
    void aReplacementFileUpToTheLimitIsReadAndOneByteMoreIsRefusedBeforeAnythingIsAsked(
            @TempDir Path dir) throws Exception {
        final byte[] most = new byte[GateCommentCommand.REPLACEMENT_LIMIT];
        java.util.Arrays.fill(most, (byte) 'x');
        final Path fits = Files.write(dir.resolve("fits.txt"), most);
        final byte[] more = java.util.Arrays.copyOf(most, most.length + 1);
        more[most.length] = 'x';
        final Path over = Files.write(dir.resolve("over.txt"), more);

        assertThat(comment(fits)).as(err.toString()).isZero();
        assertThat(check.asked).hasSize(1);

        assertThat(comment(over)).isEqualTo(64);
        assertThat(err.toString()).contains("larger than " + GateCommentCommand.REPLACEMENT_LIMIT);
        assertThat(check.asked).hasSize(1);
    }

    @Test
    void aReplacementFileThatIsALinkOrNoRegularFileIsRefusedBeforeAnythingIsAsked(@TempDir Path dir)
            throws Exception {
        // Followed, a link would put a file the person never named into the review.
        final Path target = Files.writeString(dir.resolve("target.txt"), "orders.sort();\n");
        final Path link = Files.createSymbolicLink(dir.resolve("link.txt"), target);

        assertThat(comment(link)).isEqualTo(64);
        assertThat(comment(dir)).isEqualTo(64);
        assertThat(check.asked).isEmpty();
        assertThat(store.kept).isEmpty();
    }
}
