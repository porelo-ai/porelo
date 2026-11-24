package org.fuin.porelo.gate.review;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps comments from a process of its own, so a test sees two processes write one journal at once.
 * Arguments: the store's root, the container id, how many comments, a label for their text, and a
 * file both writers wait for before they start.
 */
public final class ReviewCommentsWriter {

    private ReviewCommentsWriter() {}

    /**
     * Keeps the comments.
     *
     * @param args Root, container id, count, label, barrier file.
     * @throws ReviewRefused The store refused one.
     * @throws InterruptedException Interrupted while waiting for the barrier.
     */
    public static void main(final String[] args) throws ReviewRefused, InterruptedException {
        final Path barrier = Path.of(args[4]);
        while (!Files.exists(barrier)) {
            Thread.sleep(5);
        }
        final ReviewComments store = new ReviewComments(Path.of(args[0]));
        final Push push = new Push("shop", "app", "main", "1".repeat(40), "2".repeat(40), args[1]);
        final int count = Integer.parseInt(args[2]);
        for (int i = 0; i < count; i++) {
            store.keep(
                    push,
                    new ReviewComment(
                            new LineAnchor("a.txt", Side.NEW, 1, 1),
                            new Body(args[3] + " " + i),
                            null),
                    Origin.of(new OwnerAccount()),
                    null);
        }
    }
}
