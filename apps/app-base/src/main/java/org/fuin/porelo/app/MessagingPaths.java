package org.fuin.porelo.app;

import java.nio.file.Path;

/**
 * Where Porelo keeps the files of messages: the mailboxes, the keys, the moderation and the
 * transports.
 *
 * <p>One class per area, beside {@link PoreloPaths}, which keeps only the roots and what belongs to
 * the machine as a whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record MessagingPaths(PoreloPaths paths) {

    /**
     * Returns the root of one task's mailbox.
     *
     * <p>Under the state directory rather than the runtime one, because a conversation has to
     * survive {@code stop}, {@code start} and a machine restart, and the kernel clears the runtime
     * directory at boot. It is the task's: made when the task is made, deleted when the task is
     * removed, and untouched in between.
     *
     * @param container Container name.
     * @return Mailbox root, which is the host's half - only what is under it is mounted.
     */
    public Path mailbox(String container) {
        return mailboxes().resolve(container);
    }

    /**
     * Returns the directory every task's mailbox is in.
     *
     * @return The directory.
     */
    public Path mailboxes() {
        return paths.xdg().state().resolve("mail");
    }

    /**
     * Returns the file holding this machine's own message key, which signs no message any more: a
     * task signs with its own key. Kept while an interface still asks for the key and clearing
     * still names its line, so a project that lists it can drop it.
     *
     * <p>Under the state directory, mode {@code 0600}, and never mounted anywhere.
     *
     * @return The key file, which may not exist yet.
     */
    public Path messageKey() {
        return paths.xdg().state().resolve("message-key");
    }

    /**
     * Returns the file holding what a person decided about one project's peers: which are held, and
     * how much is asked before a message reaches each.
     *
     * <p>Under the state directory, on the host: per project rather than per task, so a task
     * started later finds what was decided, and never where a task can reach it - a task must not
     * be able to change how closely it is watched.
     *
     * @param project The project's name.
     * @return The file.
     */
    public Path moderation(String project) {
        return paths.xdg().state().resolve("moderation").resolve(project + ".json");
    }

    /**
     * Returns the transports installed for this user, their own before the packaged ones.
     *
     * @return The directory.
     */
    public TransportDirectory transportDirectory() {
        return new TransportDirectory(
                java.util.List.of(
                        paths.xdg().data().resolve("transports"),
                        PoreloPaths.packaged(TransportDirectory.PACKAGED)));
    }

    /**
     * Returns the filter that decides what a message may contain, or {@code null} when this machine
     * has none.
     *
     * <p>A machine without it sends nothing: the filter is what stands between an agent's outbox
     * and every transport, so its absence is a closed door rather than an open one.
     *
     * @return The executable, or {@code null}.
     */
    public @org.jspecify.annotations.Nullable Path messageFilter() {
        for (final Path candidate :
                java.util.List.of(
                        paths.xdg().data().resolve("filter").resolve(PoreloPaths.MESSAGE_FILTER),
                        PoreloPaths.packaged(Path.of("/usr/libexec/porelo"))
                                .resolve(PoreloPaths.MESSAGE_FILTER))) {
            if (java.nio.file.Files.isRegularFile(candidate)
                    && java.nio.file.Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
