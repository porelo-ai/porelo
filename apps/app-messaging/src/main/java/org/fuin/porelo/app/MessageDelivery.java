package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * Hands a task what arrived for it, and only what can be attributed.
 *
 * <p>A transport delivers into the mailbox's {@code inbound}; this is the step between that and the
 * agent's own inbox. What it decides is <strong>not</strong> what a message contains - the machine
 * that wrote it decided that - but whether anybody can say who wrote it: a task key whose
 * certificate one of the receiving project's certifiers issued, valid when the message arrives
 * here.
 *
 * <p><strong>What cannot be attributed is held, never delivered and never deleted.</strong> An
 * agent acting on a message nobody can attribute is the failure this step exists to prevent, and
 * deleting it instead would hide that somebody tried.
 */
public final class MessageDelivery {

    /**
     * Beside a person's words a transport handed over: who wrote them, by the name they joined
     * with.
     */
    public static final String PERSON_SUFFIX = ".person";

    /**
     * What a transport proved about who owned an arriving message.
     *
     * <p>A function rather than a flag, because whether anything is attested depends on the
     * transport that carried each message, and only the caller knows which that was.
     */
    @FunctionalInterface
    public interface Attested {

        /**
         * Says whether an arriving message may be delivered, as far as ownership goes.
         *
         * @param mailbox The task's mailbox.
         * @param message The message, still in {@code inbound}.
         * @param peer The peer whose key signed it.
         * @return An empty string when it may go on, otherwise why it may not.
         * @throws IOException Reading failed.
         */
        String refuse(Mailbox mailbox, java.nio.file.Path message, String peer) throws IOException;
    }

    /** Says whom an arriving message's key speaks for. */
    @FunctionalInterface
    public interface Signers {

        /**
         * Traces a key.
         *
         * @param key The OpenSSH public key blob the signature carries.
         * @param arrived When the message arrived here, by this machine's clock.
         * @return The peer it speaks for, or why it is not believed.
         */
        MessageSigners.Traced trace(byte[] key, Instant arrived);
    }

    private final Clock clock;

    /** Constructor that takes the time a message arrives from this machine's clock. */
    public MessageDelivery() {
        this(Clock.systemUTC());
    }

    /**
     * Constructor.
     *
     * @param clock What says when a message arrives.
     */
    public MessageDelivery(final Clock clock) {
        this.clock = clock;
    }

    /**
     * One message that was not delivered.
     *
     * @param message File name.
     * @param reason Why, in words an operator can act on.
     * @param id Its id, or empty when it was held before its id could be read.
     * @param mark This machine's own verdict on its sender, {@code unsigned}, {@code unverified} or
     *     "": never what the message says of itself.
     */
    public record Held(String message, String reason, String id, String mark) {

        /**
         * Constructor for a message held with no verdict on its sender.
         *
         * @param message File name.
         * @param reason Why.
         * @param id Its id, or "".
         */
        public Held(final String message, final String reason, final String id) {
            this(message, reason, id, "");
        }

        /**
         * Constructor for a message held before its id could be read.
         *
         * @param message File name.
         * @param reason Why.
         */
        public Held(final String message, final String reason) {
            this(message, reason, "");
        }
    }

    /**
     * One message that arrived again after having been delivered or held.
     *
     * @param message File name it arrived under this time.
     * @param id The id it repeats, which is what names it in the record.
     */
    public record Repeat(String message, String id) {}

    /**
     * One message handed to the agent.
     *
     * @param message File name.
     * @param id Its message id, or "" when it carried none.
     * @param peer The peer whose key signed it.
     * @param mark This machine's own verdict on its sender: {@code unverified} when nothing here
     *     verified the sending project, else "".
     */
    public record Delivered(String message, String id, String peer, String mark) {

        /**
         * Constructor for a message delivered with no verdict on its sender, as a person's is.
         *
         * @param message File name.
         * @param id Its id, or "".
         * @param peer Who it is from.
         */
        public Delivered(final String message, final String id, final String peer) {
            this(message, id, peer, "");
        }
    }

    /**
     * What one pass did.
     *
     * @param delivered Messages handed to the agent.
     * @param held Messages that reached no task.
     * @param duplicates Messages carrying an id this task has already been handed.
     */
    public record Outcome(List<Delivered> delivered, List<Held> held, List<Repeat> duplicates) {}

    /**
     * Delivers what can be attributed and holds what cannot.
     *
     * @param mailbox The task's mailbox.
     * @param signers Whom an arriving message's key speaks for.
     * @return What was delivered and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final Signers signers) throws IOException {
        return deliver(mailbox, signers, Set.of());
    }

    /**
     * Delivers what can be attributed, holds what cannot, and drops what has been delivered before.
     *
     * @param mailbox The task's mailbox.
     * @param signers Whom an arriving message's key speaks for.
     * @param seen Ids this task has already been handed, from its record.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final Signers signers, final Set<String> seen)
            throws IOException {
        return deliver(mailbox, signers, seen, null);
    }

    /**
     * Delivers what can be attributed and, where the peer is external, what the filter also passed.
     *
     * @param mailbox The task's mailbox.
     * @param signers Whom an arriving message's key speaks for.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null} when
     *     nothing does - which is only ever the case where no peer is external.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(
            final Mailbox mailbox,
            final Signers signers,
            final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check)
            throws IOException {
        return deliver(mailbox, signers, seen, check, null);
    }

    /**
     * Delivers what can be attributed, what the filter passed and what is within the day's budget.
     *
     * @param mailbox The task's mailbox.
     * @param signers Whom an arriving message's key speaks for.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null}.
     * @param budget How much this task and a peer may say to each other, or {@code null} for no
     *     limit.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(
            final Mailbox mailbox,
            final Signers signers,
            final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check,
            final @org.jspecify.annotations.Nullable MessageBudget budget)
            throws IOException {
        return deliver(mailbox, signers, seen, check, budget, null);
    }

    /**
     * Delivers what can be attributed, what a transport's attestation agrees with, what the filter
     * passed and what is within the day's budget.
     *
     * @param mailbox The task's mailbox.
     * @param signers Whom an arriving message's key speaks for.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null}.
     * @param budget How much this task and a peer may say to each other, or {@code null}.
     * @param attested What a transport proved about who owned a message, or {@code null} when
     *     nothing that carried into this mailbox attests anything.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(
            final Mailbox mailbox,
            final Signers signers,
            final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check,
            final @org.jspecify.annotations.Nullable MessageBudget budget,
            final @org.jspecify.annotations.Nullable Attested attested)
            throws IOException {
        final List<Delivered> delivered = new ArrayList<>();
        final List<Held> held = new ArrayList<>();
        final List<Repeat> duplicates = new ArrayList<>();
        final Set<String> already = new LinkedHashSet<>(seen);
        final java.util.Map<String, Integer> handedOver = new java.util.HashMap<>();
        for (final Path message : arrived(mailbox.inbound())) {
            final String name = message.getFileName().toString();
            final Path signature = mailbox.inbound().resolve(name + ".sig");
            final Path person = mailbox.inbound().resolve(name + PERSON_SUFFIX);
            if (!Files.isRegularFile(signature) && Files.isRegularFile(person)) {
                // A person's words, which their conversation's transport handed over and the host
                // took from a member of it: no key signs them, and they are not filtered
                // (2026-10-04) - the filter stands between an agent and everybody else, not between
                // a person and the agent.
                final String who = Files.readString(person, StandardCharsets.UTF_8).strip();
                final String id;
                try {
                    id = MessageFile.id(message);
                } catch (final RuntimeException ex) {
                    hold(mailbox, message, null);
                    Files.deleteIfExists(person);
                    held.add(new Held(name, "it is not a message that can be read"));
                    continue;
                }
                if (!id.isBlank() && !already.add(id)) {
                    Files.delete(message);
                    Files.delete(person);
                    duplicates.add(new Repeat(name, id));
                    continue;
                }
                mailbox.refuseLinks();
                final Path staged = mailbox.inboxTmp().resolve(name);
                Files.move(message, staged, StandardCopyOption.REPLACE_EXISTING);
                mailbox.intoInbox(staged);
                Files.move(
                        person,
                        mailbox.record().resolve(name + PERSON_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
                delivered.add(new Delivered(name, id, who));
                continue;
            }
            if (!Files.isRegularFile(signature)) {
                hold(mailbox, message, null);
                held.add(new Held(name, "it arrived without a signature", "", TrustMark.UNSIGNED));
                continue;
            }
            final byte[] bytes = Files.readAllBytes(message);
            final String armored = Files.readString(signature, StandardCharsets.UTF_8);
            final byte[] signer = SshSignature.signerOf(armored);
            if (signer == null) {
                hold(mailbox, message, signature);
                held.add(new Held(name, "its signature cannot be read", "", TrustMark.UNSIGNED));
                continue;
            }
            if (!SshSignature.verify(bytes, armored, signer, MessageIntake.NAMESPACE)) {
                hold(mailbox, message, signature);
                held.add(
                        new Held(
                                name,
                                "its signature does not match the message it arrived with",
                                "",
                                TrustMark.UNSIGNED));
                continue;
            }
            // When this pass takes it, by this machine's clock: never a time the sender wrote.
            final MessageSigners.Traced peer = signers.trace(signer, clock.instant());
            if (peer.waits()) {
                // Left where it arrived, as a task's own message waits for the vault: held, it
                // would never be delivered once the vault opened.
                continue;
            }
            if (!peer.believed()) {
                hold(mailbox, message, signature);
                held.add(new Held(name, peer.refusal(), "", peer.mark()));
                continue;
            }
            // The agent reads who wrote a message from its metadata, which the sender wrote: a
            // name other than the key's would pass one task off as another, or as a person.
            final String from = from(bytes);
            if (from != null && !peer.named(from)) {
                hold(mailbox, message, signature);
                held.add(
                        new Held(
                                name,
                                "it says it is from "
                                        + from.replaceAll("\\p{Cntrl}", "?")
                                        + ", and it is signed by "
                                        + peer.peer(),
                                "",
                                peer.mark()));
                continue;
            }

            final String id;
            try {
                id = MessageFile.id(message);
            } catch (final RuntimeException ex) {
                // Held, not thrown: the pass ended here, the file stayed in inbound, and the task
                // never received anything again.
                hold(mailbox, message, signature);
                held.add(new Held(name, "it is not a message that can be read", "", peer.mark()));
                continue;
            }
            if (!id.isBlank() && !already.add(id)) {
                // A transport that hands the same message over twice - after a retry it could not
                // confirm, say - must not make the agent read it twice. The record keeps the fact
                // that it arrived again; keeping the file as well would only grow a pile that
                // nobody can act on, because the agent already has this exact message.
                Files.delete(message);
                Files.delete(signature);
                duplicates.add(new Repeat(name, id));
                continue;
            }

            // Before anything else about the message: who put it here is a fact that stops being
            // knowable once it has been moved, and it is the one the peer table can disagree with.
            final String owner =
                    attested == null ? "" : attested.refuse(mailbox, message, peer.peer());
            if (!owner.isEmpty()) {
                hold(mailbox, message, signature);
                held.add(new Held(name, owner, id, peer.mark()));
                continue;
            }

            final String full =
                    budget == null
                            ? ""
                            : budget.inbound(peer.peer(), handedOver.getOrDefault(peer.peer(), 0));
            if (!full.isEmpty()) {
                // Held rather than refused back: this machine is the one that is full, and the
                // message itself is nobody's fault.
                hold(mailbox, message, signature);
                held.add(new Held(name, full, id, peer.mark()));
                continue;
            }

            // After attribution and after the repeat check: there is no point reading a message
            // nobody can attribute, and none in reading the same one twice.
            final String refused = check == null ? "" : check.refuse(mailbox, message, peer.peer());
            if (!refused.isEmpty()) {
                hold(mailbox, message, signature);
                held.add(new Held(name, refused, id, peer.mark()));
                continue;
            }

            // Into the agent's half through tmp and a rename, so a reader in the container never
            // sees half a message - the same discipline the agent is asked to use on the way out.
            mailbox.refuseLinks();
            final Path staged = mailbox.inboxTmp().resolve(name);
            Files.move(message, staged, StandardCopyOption.REPLACE_EXISTING);
            mailbox.intoInbox(staged);
            // The signature stays on the host's side: it is evidence about the message, and the
            // container has no key to check it with anyway.
            Files.move(
                    signature,
                    mailbox.record().resolve(name + ".sig"),
                    StandardCopyOption.REPLACE_EXISTING);
            // And so does what a transport attested about it, for the same reason and one more:
            // left in 'inbound' it would pile up unread, and the next pass would find a file
            // belonging to a message that is no longer there.
            final Path ownerFile = mailbox.inbound().resolve(name + OwnerAttestation.SUFFIX);
            if (Files.isRegularFile(ownerFile)) {
                Files.move(
                        ownerFile,
                        mailbox.record().resolve(name + OwnerAttestation.SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            delivered.add(new Delivered(name, id, peer.peer(), peer.mark()));
            handedOver.merge(peer.peer(), 1, Integer::sum);
        }
        return new Outcome(delivered, held, duplicates);
    }

    /**
     * Returns whom a message's {@code metadata.from} names, {@code null} when it names nobody, and
     * "" for one that is no name or a message that cannot be read.
     */
    private static @Nullable String from(final byte[] message) {
        final Object parsed;
        try {
            parsed = org.fuin.porelo.wire.Json.parse(new String(message, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof java.util.Map<?, ?> document)
                || !(document.get("metadata") instanceof java.util.Map<?, ?> metadata)
                || metadata.get("from") == null) {
            // JSON's null names nobody, as a serializer writes a field that was never set.
            return null;
        }
        return metadata.get("from") instanceof String name ? name : "";
    }

    private void hold(final Mailbox mailbox, final Path message, final @Nullable Path signature)
            throws IOException {
        final String name = message.getFileName().toString();
        // What a transport attested travels with the message it is about: an operator looking at a
        // held message needs to see why, and "the owner was somebody else" is only readable if the
        // file saying so is beside it.
        final Path attested = mailbox.inbound().resolve(name + OwnerAttestation.SUFFIX);
        if (Files.isRegularFile(attested)) {
            Files.move(
                    attested,
                    mailbox.hold().resolve(name + OwnerAttestation.SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(message, mailbox.hold().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        if (signature != null) {
            Files.move(
                    signature,
                    mailbox.hold().resolve(signature.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> arrived(final Path inbound) throws IOException {
        if (!Files.isDirectory(inbound)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(inbound)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
