package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Task;

/**
 * Takes a task's reply in a review thread through the review's intake, bound to a push the task's
 * own container pushed.
 *
 * <p>The push is found from the gate's records alone: of the pushes waiting in the task's project,
 * only one whose recorded container id is the task's own is asked, and only one that holds the
 * thread takes the reply. A task of the same name in another project, or one started later under
 * the same name, has another recorded container, so it reaches no thread of this one.
 */
public final class TaskReviewReplies implements ReviewReplies {

    private final OwnPushes own;

    private final CommentIntake.Parts parts;

    /**
     * Finds the pushes waiting at the gate that a task's own container pushed.
     *
     * <p>A stand-in answers a fixed list.
     */
    @FunctionalInterface
    public interface OwnPushes {

        /**
         * Returns the pushes waiting in the task's project whose records name its container.
         *
         * @param task The task, as this machine recorded it.
         * @return Its waiting pushes, none when nothing of it waits.
         */
        List<Push> of(TaskIdentity task);
    }

    /**
     * Constructor.
     *
     * @param own Finds the task's own waiting pushes.
     * @param parts The review's parts: its check and store.
     */
    public TaskReviewReplies(final OwnPushes own, final CommentIntake.Parts parts) {
        this.own = Objects.requireNonNull(own, "own");
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    /**
     * Returns the reply path of a machine, over its own review parts.
     *
     * @param context The machine.
     * @return The reply path.
     */
    public static TaskReviewReplies of(final PoreloContext context) {
        final CommentIntake.Parts parts = CommentIntake.Parts.of(context);
        return new TaskReviewReplies(waitingAt(context, parts.pushes()), parts);
    }

    @Override
    public Optional<String> take(final TaskIdentity task, final String thread, final String text) {
        final Task producer;
        final ReviewReply reply;
        try {
            producer = new Task(task.task());
            reply = new ReviewReply(thread, new Body(text));
        } catch (IllegalArgumentException ex) {
            // A thread id or text that is no reply at all; the message's words are not quoted.
            return Optional.of(ReviewError.TEXT_REFUSED.wireName());
        }
        try {
            parts.intake().take(producer, pushOf(task, thread), reply, null);
            return Optional.empty();
        } catch (ReviewRefused ex) {
            return Optional.of(words(ex.refusal()));
        }
    }

    /** The push of the task's own container that holds the thread. */
    private Push pushOf(final TaskIdentity task, final String thread) throws ReviewRefused {
        for (final Push push : own.of(task)) {
            // Bound to the container its records name: a push of another container is never
            // answered in, whoever listed it.
            if (push.task().equals(task.recordedId())
                    && parts.store().threads(push).stream()
                            .anyMatch(each -> each.id().equals(thread))) {
                return push;
            }
        }
        // Whether the thread exists elsewhere is not said: that would tell a task about others.
        throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
    }

    /**
     * Returns a machine's own waiting pushes of a task: in every repository of its project, each
     * push whose recorded container id is the task's, as the review finds it. What does not wait is
     * passed over, so the reply is refused; a gate or record that cannot be read now throws, so the
     * reply waits for the next pass rather than being refused for good.
     *
     * @param context The machine.
     * @param pushes How a waiting push is found by its name.
     * @return The lookup.
     */
    static OwnPushes waitingAt(final PoreloContext context, final CommentIntake.Pushes pushes) {
        return task -> {
            final Project project;
            try {
                project = GateSupport.byName(context, task.project());
            } catch (RuntimeException ex) {
                return List.of();
            }
            final List<Push> found = new ArrayList<>();
            for (final Repository repository : project.allRepositories()) {
                final GitGate gate = GateSupport.gate(context, project, repository, null, null);
                if (!GateSupport.holds(gate)) {
                    // No mirror here: nothing of this repository waits.
                    continue;
                }
                for (final String name : gate.pending()) {
                    final TaskIdentity pushed = TaskHistory.forPush(context, project.name(), name);
                    if (pushed == null || !pushed.recordedId().equals(task.recordedId())) {
                        continue;
                    }
                    try {
                        found.add(pushes.waiting(project.name(), repository.name(), name));
                    } catch (ReviewRefused ex) {
                        // It stopped waiting meanwhile.
                    }
                }
            }
            return found;
        };
    }

    /** A refusal in words a task may read: its contract name and rule, never what was given. */
    static String words(final ReviewRefusal refusal) {
        final String detail = refusal.detail();
        return detail == null || detail.isEmpty()
                ? refusal.error().wireName()
                : refusal.error().wireName() + " (" + detail + ")";
    }
}
