package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * Takes what an agent has finished writing out of its outbox, and signs it.
 *
 * <p>The agent writes into {@code outbox/tmp} and renames into {@code outbox/new}, so a message is
 * complete exactly when it appears here and nothing has to guess whether a file is still being
 * written.
 *
 * <p><strong>Two directories, and the role each may carry is fixed here.</strong> What comes out of
 * the container's outbox may only be {@code ROLE_AGENT}; what a person wrote, which arrives in a
 * directory the container cannot see, may only be {@code ROLE_USER}. The filter accepts both roles
 * on this host's word that they mean what they say, so this is where that word is kept - a task
 * that wrote {@code ROLE_USER} into its outbox would otherwise be forging a message from its own
 * operator.
 *
 * <p><strong>The signature is made over the bytes as they are, by the task's own key</strong>, and
 * nothing downstream may rewrite them - not the filter, not a transport. The key is held on the
 * host, never in the container; the far end traces it to its certificate by its fingerprint. A task
 * without a key of its own sends nothing: its messages are held and it is told why. While only the
 * vault is locked they wait in the outbox.
 */
public final class MessageIntake {

    /** What a signature over a message is for, so one cannot be replayed as another. */
    public static final String NAMESPACE = "porelo-message";

    /** The only role a message out of a task's own outbox may carry. */
    public static final String AGENT = "ROLE_AGENT";

    /** The only role a message a person wrote may carry. */
    public static final String PERSON = "ROLE_USER";

    private final MessageSigners.Own key;

    private final List<MessageDelivery.Held> unsigned = new ArrayList<>();

    private final Holding holding;

    /**
     * Told of each message the intake holds with a verdict, the moment it lies in {@code hold/}.
     *
     * <p>Not after the whole intake returns: a later message that makes the intake fail would
     * otherwise leave one already held with no verdict on record, which a task could do on purpose.
     */
    @FunctionalInterface
    public interface Holding {

        /**
         * Takes note of one held message.
         *
         * @param held The message, why and its mark.
         * @throws IOException Recording it failed; the intake stops.
         */
        void held(MessageDelivery.Held held) throws IOException;
    }

    /**
     * Constructor with the task's own key.
     *
     * @param key Its key, held on the host and never inside a container; read only when there is
     *     something to sign.
     */
    public MessageIntake(final MessageSigners.Own key) {
        this(key, held -> {});
    }

    /**
     * Constructor with the task's own key and what is told of each hold.
     *
     * @param key Its key, held on the host and never inside a container.
     * @param holding Told of each message held with a verdict, as it is held.
     */
    public MessageIntake(final MessageSigners.Own key, final Holding holding) {
        this.key = key;
        this.holding = holding;
    }

    /**
     * Takes every finished message out of the outbox and out of the person's directory.
     *
     * @param mailbox The task's mailbox.
     * @return What was taken, in the order it was taken.
     * @throws IOException Reading or moving failed.
     */
    public List<String> take(final Mailbox mailbox) throws IOException {
        unsigned.clear();
        mailbox.refuseLinks();
        final List<String> taken = new ArrayList<>(take(mailbox, mailbox.outboxNew(), false));
        taken.addAll(take(mailbox, mailbox.person(), true));
        return taken;
    }

    /**
     * Takes from one directory.
     *
     * @param mailbox The task's mailbox.
     * @param from Where to take from.
     * @param person Whether this is the directory only a person writes into.
     * @return What was taken.
     * @throws IOException Reading or moving failed.
     */
    private List<String> take(final Mailbox mailbox, final Path from, final boolean person)
            throws IOException {
        final List<String> taken = new ArrayList<>();
        final List<Path> finished = finished(from);
        if (finished.isEmpty()) {
            return taken;
        }
        @Nullable SigningKey signing = null;
        String unsigned = "";
        try {
            signing = key.key();
        } catch (final MessageSigners.Unsigned ex) {
            if (ex.waits()) {
                return taken;
            }
            unsigned = String.valueOf(ex.getMessage());
        }
        for (final Path message : finished) {
            final String name = message.getFileName().toString();
            // The bytes, once, and from then on the host's own copy: renamed onward, the file
            // stayed the agent's inode, and through a hard link it kept it could change a message
            // after it was signed, filtered or read.
            final byte[] bytes = bounded(message);
            Files.delete(message);
            if (bytes == null) {
                // Beyond the limit, and not read: a sparse file of gigabytes, read whole, ended
                // messaging on the machine. No transport carries one anyway.
                continue;
            }
            // Only the false claim is stopped here, not every wrong role: a message with no role or
            // a nonsense one is the filter's to refuse, and it answers the sender properly. What
            // the filter cannot judge is whether a task is entitled to say a person wrote this, and
            // that is exactly what this decides. Read from the bytes signed, not the file again.
            final String role = MessageFile.role(bytes);
            if (person ? !PERSON.equals(role) : PERSON.equals(role)) {
                // Held, not corrected: a host that rewrote the role would be signing its own
                // sentence about who wrote this, and the record would stop being evidence.
                place(bytes, mailbox.hold().resolve(name));
                if (signing == null) {
                    // Still unsigned: the role it claims takes nothing away from that verdict.
                    held(
                            new MessageDelivery.Held(
                                    name, "it was not sent: " + unsigned, "", TrustMark.UNSIGNED));
                }
                continue;
            }

            if (signing == null) {
                // Held, with the sender told: unsigned, no receiver could believe it. Recorded
                // before the sender is told, so nothing after the hold can lose the verdict.
                final Path held = mailbox.hold().resolve(name);
                place(bytes, held);
                held(
                        new MessageDelivery.Held(
                                name, "it was not sent: " + unsigned, "", TrustMark.UNSIGNED));
                new HostBounce().write(mailbox, held, "it was not sent: " + unsigned, true);
                continue;
            }

            if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
                // Held, with the sender told: the signature beside it, and the names it is given on
                // its way out, would not fit a file name, and failing on it stopped the intake of
                // every message after it.
                final Path held = mailbox.hold().resolve(name);
                place(bytes, held);
                final String why =
                        "it was not sent: its file name is longer than "
                                + MAX_NAME_BYTES
                                + " bytes";
                held(new MessageDelivery.Held(name, why, "", ""));
                new HostBounce().write(mailbox, held, why, true);
                continue;
            }

            // The signature is written before the message. A crash between the two leaves a
            // signature with no message, which the next run overwrites; the other order would leave
            // a message with no signature, and nothing downstream could tell whether it had been
            // signed or tampered with.
            final Path signature = mailbox.incoming().resolve(name + ".sig");
            final Path partial = staging(mailbox.incoming().resolve(name + ".sig"));
            Files.writeString(
                    partial, SshSignature.sign(signing, bytes, NAMESPACE), StandardCharsets.UTF_8);
            Files.move(
                    partial,
                    signature,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            place(bytes, mailbox.incoming().resolve(name));
            taken.add(name);
        }
        return taken;
    }

    private void held(final MessageDelivery.Held held) throws IOException {
        unsigned.add(held);
        holding.held(held);
    }

    /**
     * Returns what the last {@link #take} held with a verdict: because the task has no key to sign
     * it with, each marked {@code unsigned} - this machine's own verdict, so a person sees it held
     * as such - or because its name is too long to sign beside it.
     *
     * @return The held messages, in the order they were held.
     */
    public List<MessageDelivery.Held> heldUnsigned() {
        return List.copyOf(unsigned);
    }

    /**
     * The most a message may hold: transports carry kilobytes, and a file read whole is held in
     * memory.
     */
    static final int MAX_BYTES = 1024 * 1024;

    /**
     * The longest message file name taken, in bytes: a file name holds 255, and a message is given
     * names beside it on its way out ({@code .sig}, {@code .to}, {@code .released}).
     */
    static final int MAX_NAME_BYTES = 200;

    /**
     * Reads a message without following a link, or returns {@code null} when it is beyond the
     * limit.
     */
    private static byte @org.jspecify.annotations.Nullable [] bounded(final Path message)
            throws IOException {
        try (java.io.InputStream in =
                Files.newInputStream(message, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            final byte[] read = in.readNBytes(MAX_BYTES + 1);
            return read.length > MAX_BYTES ? null : read;
        }
    }

    /**
     * Reads a file in the part of the box the task writes - only a regular file, never through a
     * link, and only up to {@link #MAX_BYTES} - or returns {@code null}: a pipe placed there held
     * the host's read, and with it every mailbox's pass, for as long as the task wanted.
     *
     * @param file The file.
     * @return Its bytes, or {@code null} when it is not a regular file or is beyond the limit.
     * @throws IOException Reading failed, or the file went away.
     */
    static byte @org.jspecify.annotations.Nullable [] taskWritten(final Path file)
            throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return bounded(file);
    }

    /** Writes the host's own copy, complete or not at all. */
    private static void place(final byte[] bytes, final Path target) throws IOException {
        final Path partial = staging(target);
        Files.write(partial, bytes);
        Files.move(
                partial,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Returns where a file is written before it is renamed into place: a dot file beside it, named
     * by a digest of its name. Built from the name itself, a task's longest name made one longer
     * than a file name may be, and the intake of every message after it failed. The same name gives
     * the same staging file, so what a crash left is overwritten by the next run.
     */
    private static Path staging(final Path target) {
        try {
            final byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(
                                    target.getFileName()
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8));
            return target.resolveSibling(
                    ".take-" + java.util.HexFormat.of().formatHex(digest) + ".tmp");
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing", ex);
        }
    }

    private List<Path> finished(final Path outbox) throws IOException {
        if (!Files.isDirectory(outbox)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(outbox)) {
            // Not through a link the agent placed: followed, the host read whatever file its
            // account can and signed it.
            return entries.filter(
                            path ->
                                    Files.isRegularFile(
                                            path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    // A dot file is the agent's business, and anything without the suffix is not a
                    // message: neither is taken, and neither is reported as refused.
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
