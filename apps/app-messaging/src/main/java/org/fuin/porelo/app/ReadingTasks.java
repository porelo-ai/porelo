package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.fuin.porelo.gate.review.TaskParts;

/**
 * Whether a task reads another task's work, by the parts its start recorded: such a task takes part
 * in no conversation of its project and sends nothing to a transport that leaves the machine, only
 * to its own review.
 *
 * <p>Read from the task's state, then from its durable record while the state holds none. A record
 * that does not read counts as another task's work: such a task sends nothing out. A task with no
 * record at all counts as one where its mailbox carries the mark its start left there, so a reader
 * whose records a removal took first stays one; any other is none Porelo started with parts, and
 * keeps what it has.
 */
public final class ReadingTasks {

    /** The rule a message of such a task to anyone but its review is refused by. */
    public static final String SENDS_TO_REVIEW_ONLY =
            "readsAnothersWork: a task that reads another task's work sends only to its review";

    private ReadingTasks() {}

    /**
     * Whether a task reads another task's work.
     *
     * @param context The machine.
     * @param container The task's container.
     * @return True when its recorded parts read another task's work or do not read.
     */
    public static boolean readsAnothersWork(final PoreloContext context, final String container) {
        try {
            TaskParts parts = RunPlace.partsIn(context.paths().tasks().containerState(container));
            if (parts == null) {
                parts = RunPlace.partsIn(context.paths().tasks().taskRecord(container));
            }
            if (parts != null) {
                return parts.readsAnothersWork();
            }
        } catch (IllegalStateException unreadable) {
            // Fail closed: a task whose record does not read is given nothing that leads out.
            return true;
        }
        // Its records gone and its mailbox left, as a removal leaves it for a while or for good:
        // the mailbox says what they said.
        return Files.exists(
                context.paths().messaging().mailbox(container).resolve(MARK),
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Marks the mailbox of a task that reads another task's work, beside the box its container
     * sees: what is left in it is held so after its records are gone. Nothing for another task.
     *
     * @param context The machine.
     * @param container The task's container, its records written.
     * @throws IOException When the mark cannot be written.
     */
    public static void mark(final PoreloContext context, final String container)
            throws IOException {
        final Path root = context.paths().messaging().mailbox(container);
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                && readsAnothersWork(context, container)) {
            Files.writeString(root.resolve(MARK), "", StandardCharsets.UTF_8);
        }
    }

    /** The mark in a mailbox's own directory, never in the box its container sees. */
    static final String MARK = ".reads-anothers-work";
}
