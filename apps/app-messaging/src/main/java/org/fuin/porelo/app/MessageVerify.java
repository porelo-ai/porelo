package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.vault.SshSignature;

/**
 * Walks a task's record and says what no longer checks out.
 *
 * <p>Two different questions, answered in one pass because an operator asking either is really
 * asking "can I still believe this mailbox":
 *
 * <ul>
 *   <li><strong>The chain</strong> - was a line edited or removed after it was written.
 *   <li><strong>The signatures</strong> - does each kept signature still match the message it was
 *       made over.
 * </ul>
 *
 * <p><strong>It names the first thing that fails and keeps going.</strong> Stopping at the first
 * would hide how far the damage reaches, and a list with nothing first in it makes a person read
 * all of it to find out whether anything matters.
 */
public final class MessageVerify {

    /**
     * One thing that does not check out.
     *
     * @param what The line number, or the file name.
     * @param detail Why, in words an operator can act on.
     */
    public record Fault(String what, String detail) {}

    /**
     * What the walk found.
     *
     * @param lines How many record lines were read.
     * @param signatures How many kept signatures were checked.
     * @param faults What does not check out, in the order found. Empty means it all holds.
     */
    public record Report(int lines, int signatures, List<Fault> faults) {}

    /**
     * Checks one mailbox.
     *
     * @param mailbox The task's mailbox.
     * @param certified Whether a key is a task key whose certificate is kept: whether it was
     *     believed is what its arrival decided, against the time it arrived.
     * @return What was found.
     * @throws IOException Reading failed.
     */
    public Report check(final Mailbox mailbox, final java.util.function.Predicate<byte[]> certified)
            throws IOException {
        final List<Fault> faults = new ArrayList<>();
        final MessageRecord record = new MessageRecord(mailbox);
        final int broken = record.firstBrokenLine();
        if (broken > 0) {
            faults.add(
                    new Fault(
                            "line " + broken,
                            "it does not follow the line before it, so the record was changed after it was"
                                    + " written"));
        }
        int checked = 0;
        for (final Path signature : kept(mailbox.record())) {
            final String name = signature.getFileName().toString();
            final String messageName = name.substring(0, name.length() - ".sig".length());
            Found found = found(mailbox, messageName);
            if (found != null && found.retry()) {
                // Once more: the agent moves what it read from new to cur while this looks.
                found = found(mailbox, messageName);
                if (found != null && found.retry() && found.fault() == null) {
                    // Seen and gone twice: moved on, as a read message may be.
                    found = null;
                }
            }
            if (found == null) {
                // Not a fault: an agent is free to delete what it has read, and a signature for a
                // message that is gone is evidence about something nobody can check any more.
                continue;
            }
            checked++;
            if (found.fault() != null) {
                faults.add(new Fault(messageName, found.fault()));
                continue;
            }
            final byte[] bytes = found.bytes();
            final String armored = Files.readString(signature, StandardCharsets.UTF_8);
            final byte[] signer = SshSignature.signerOf(armored);
            if (signer == null || !certified.test(signer)) {
                faults.add(
                        new Fault(
                                messageName,
                                "its signature is by no task key whose certificate is kept"));
            } else if (!matches(bytes, armored, signer)) {
                faults.add(
                        new Fault(
                                messageName, "it no longer matches the signature it arrived with"));
            }
        }
        return new Report(record.size(), checked, List.copyOf(faults));
    }

    /**
     * Says whether a message still is what its signature was made over: read as the task left it in
     * its inbox, so a link or a file beyond a message's size is no longer that message.
     */
    private static boolean matches(
            final byte @org.jspecify.annotations.Nullable [] bytes,
            final String armored,
            final byte[] signer) {
        return bytes != null
                && SshSignature.verify(bytes, armored, signer, MessageIntake.NAMESPACE);
    }

    /**
     * What was found under a recorded name: the bytes to check, or why there are none, or that it
     * should be looked for once more.
     */
    private record Found(
            byte @org.jspecify.annotations.Nullable [] bytes,
            @org.jspecify.annotations.Nullable String fault,
            boolean retry) {}

    /**
     * Looks in every place a recorded message can be and settles on the one Porelo delivered, so a
     * file planted under the same name in {@code inbox/new} does not hide the real one in {@code
     * inbox/cur}.
     */
    private static @org.jspecify.annotations.Nullable Found found(
            final Mailbox mailbox, final String name) {
        boolean any = false;
        boolean foreign = false;
        boolean changed = false;
        for (final Path where : List.of(mailbox.inboxNew(), mailbox.inboxCur())) {
            final Path candidate = where.resolve(name);
            if (!Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            any = true;
            final byte[] bytes;
            try {
                bytes = mailbox.readDelivered(candidate, MessageIntake.MAX_BYTES);
            } catch (final IOException ex) {
                return new Found(
                        null, "it could not be read to check it: " + ex.getMessage(), false);
            }
            if (bytes != null) {
                return new Found(bytes, null, false);
            }
            if (mailbox.delivered(candidate)) {
                changed = true;
            } else if (Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                foreign = true;
            }
        }
        final Path sent = mailbox.sent().resolve(name);
        if (Files.exists(sent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                return new Found(MessageIntake.taskWritten(sent), null, false);
            } catch (final IOException ex) {
                return new Found(
                        null, "it could not be read to check it: " + ex.getMessage(), false);
            }
        }
        if (changed) {
            // Porelo's own, beyond a message's size: no longer what was signed.
            return new Found(null, null, false);
        }
        if (foreign) {
            // Anything in the container can write into the inbox, under a recorded name too.
            return new Found(null, "it was not delivered by Porelo", true);
        }
        return any ? new Found(null, null, true) : null;
    }

    private List<Path> kept(final Path record) throws IOException {
        if (!Files.isDirectory(record)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(record)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sig"))
                    .sorted()
                    .toList();
        }
    }
}
