package org.fuin.porelo.gate.review;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A thread at a place marked resolved by a task: it names the thread and carries no text, anchor or
 * producer. At a gate push the thread is one of Porelo's, at a pull request one of the forge's,
 * named as the conversation the task read gave it. Only a task's submission carries one; a forge's
 * page never does, and the store never keeps one as an item of its thread.
 *
 * @param thread The thread's id at its place: 1 to 64 ASCII letters, digits or hyphens.
 */
public record ThreadResolve(String thread) implements ReviewItem {

    /** What a thread id a resolve names may be: a UUID of Porelo's or a forge's decimal id. */
    public static final Pattern THREAD =
            Pattern.compile("[A-Za-z0-9-]{1," + ForgeReply.THREAD_LIMIT + "}");

    /** Refuses a thread id of any other shape. */
    public ThreadResolve {
        Objects.requireNonNull(thread, "thread");
        if (!THREAD.matcher(thread).matches()) {
            throw new IllegalArgumentException(
                    "a resolve names a thread id of 1 to 64 letters, digits or hyphens");
        }
    }
}
