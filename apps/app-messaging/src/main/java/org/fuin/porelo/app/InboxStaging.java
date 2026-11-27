package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stages a file from the host's own half of a mailbox in the agent's inbox, written anew there.
 *
 * <p><strong>Never renamed across.</strong> On an SELinux host the box is mounted with the task's
 * own private label, and a file made in it takes that label; a file renamed in from the host's half
 * keeps the host's, and the agent cannot even read that it is there. A copy is made in the box, so
 * it is the box's file, and only the rename into {@code new} that follows stays inside the box.
 */
final class InboxStaging {

    private InboxStaging() {}

    /**
     * Writes a file into the inbox's {@code tmp} under its own name, and removes it from where it
     * was.
     *
     * @param mailbox The task's mailbox.
     * @param from The file, in the host's half; a link is refused.
     * @return The staged file, for {@link Mailbox#intoInbox(Path)}.
     * @throws IOException If it cannot be written or removed.
     */
    static Path stage(final Mailbox mailbox, final Path from) throws IOException {
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file, so not handed to the agent: " + from);
        }
        final Path staged = mailbox.inboxTmp().resolve(from.getFileName().toString());
        Files.copy(from, staged, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        Files.delete(from);
        return staged;
    }
}
