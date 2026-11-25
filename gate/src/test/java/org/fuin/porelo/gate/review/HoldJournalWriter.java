package org.fuin.porelo.gate.review;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Holds items from a process of its own, releasing every other one, so a test sees two processes
 * write one hold journal at once. Arguments: the store's root, the task, the place of its first
 * run, how many items, a label for their text, and a file both writers wait for before they start.
 */
public final class HoldJournalWriter {

    private HoldJournalWriter() {}

    /**
     * Holds the items.
     *
     * @param args Root, task, place, count, label, barrier file.
     * @throws HoldRefused The journal refused a release.
     * @throws InterruptedException Interrupted while waiting for the barrier.
     */
    public static void main(final String[] args) throws HoldRefused, InterruptedException {
        final Path barrier = Path.of(args[5]);
        while (!Files.exists(barrier)) {
            Thread.sleep(5);
        }
        final HoldJournal holds = new HoldJournal(Path.of(args[0]));
        final Push push =
                new Push("shop", "app", "review-1", "1".repeat(40), null, "0123456789abcdef0123");
        final Run run = new Run(args[2], Role.REVIEWER, new Pinned(push, null), 1);
        final int count = Integer.parseInt(args[3]);
        for (int i = 0; i < count; i++) {
            final Held held =
                    holds.hold(
                            run,
                            args[1],
                            Direction.OUTGOING,
                            new ReviewComment(
                                    new LineAnchor("a.txt", Side.NEW, 1, 1),
                                    new Body(args[4] + " " + i),
                                    null),
                            null,
                            null);
            if (i % 2 == 0) {
                holds.decide(args[1], held.id(), held.digest(), Decision.RELEASE);
            }
        }
    }
}
