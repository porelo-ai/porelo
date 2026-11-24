package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.wire.Json;

/**
 * Decides which transport carries an accepted message, and queues it for that one.
 *
 * <p><strong>The filter never learns this.</strong> Resolving a peer name to a transport is policy,
 * and policy stays where the peer table is - which is the same reason a container never sees an
 * address. The filter writes what it accepted into one directory; this reads the peer out of the
 * message and moves it into that transport's queue.
 */
public final class MessageDispatch {

    /** Written beside a queued message: where its transport has to deliver it. */
    public static final String DESTINATION_SUFFIX = ".to";

    /**
     * What one pass did.
     *
     * @param queued Message file name to the transport it was queued for.
     * @param held Messages that reached no queue, with why.
     * @param peers Message file name to the peer it was queued for.
     * @param refused Replies to the review it refused for good, with why; their sender was answered
     *     already.
     */
    public record Outcome(
            Map<String, String> queued,
            List<MessageDelivery.Held> held,
            Map<String, String> peers,
            List<MessageDelivery.Held> refused) {}

    /**
     * Beside a message in {@code accepted/}: a person released it, so no mode or hold keeps it
     * back.
     */
    static final String RELEASED_SUFFIX = ".released";

    /** The host's record of which task a mailbox belongs to. */
    @FunctionalInterface
    public interface Tasks {

        /**
         * Returns the task a mailbox belongs to.
         *
         * @param container The mailbox's own name, the task's container.
         * @return The task as this machine recorded it, or {@code null} when no task of this
         *     machine owns it.
         */
        @org.jspecify.annotations.Nullable
        TaskIdentity of(String container);
    }

    private final ReviewReplies replies;

    private final Tasks tasks;

    /**
     * Constructor.
     *
     * @param replies Takes a task's reply to a review thread.
     * @param tasks Which task each mailbox belongs to.
     */
    public MessageDispatch(final ReviewReplies replies, final Tasks tasks) {
        this.replies = replies;
        this.tasks = tasks;
    }

    /**
     * Queues every accepted message whose peer a person has not held.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers.
     * @param moderation What a person decided about each of them.
     * @return What was queued and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome dispatch(final Mailbox mailbox, final Mail mail, final Moderation moderation)
            throws IOException {
        return dispatch(mailbox, mail, moderation, null);
    }

    /**
     * Queues every accepted message whose peer a person has not held and whose budget holds.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers.
     * @param moderation What a person decided about each of them.
     * @param budget How much this task and a peer may say to each other, or {@code null} for no
     *     limit.
     * @return What was queued and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome dispatch(
            final Mailbox mailbox,
            final Mail mail,
            final Moderation moderation,
            final @org.jspecify.annotations.Nullable MessageBudget budget)
            throws IOException {
        final Map<String, String> queued = new LinkedHashMap<>();
        final Map<String, String> addressedTo = new LinkedHashMap<>();
        final Map<String, Integer> queuedNow = new LinkedHashMap<>();
        final List<MessageDelivery.Held> held = new ArrayList<>();
        final List<MessageDelivery.Held> refused = new ArrayList<>();
        for (final Path message : accepted(mailbox.accepted())) {
            final String name = message.getFileName().toString();
            final List<String> addressed = addressees(message);
            if (addressed.size() != 1) {
                hold(mailbox, message, name);
                held.add(
                        new MessageDelivery.Held(
                                name,
                                addressed.isEmpty()
                                        ? "it addresses nobody: metadata.to is missing or empty"
                                        // Fan-out is a copy per peer and a receipt per copy.
                                        // Refusing it plainly is better than delivering to the
                                        // first name and saying nothing about the rest.
                                        : "it addresses "
                                                + addressed.size()
                                                + " peers, and sending to more than one is not built"));
                continue;
            }
            // Before the peer lookup: the review is no peer, and no project can make it one.
            if (Mail.REVIEW.equals(addressed.get(0))) {
                reply(mailbox, message, name, held, refused);
                continue;
            }
            final Mail.Peer peer = mail.peer(addressed.get(0));
            if (peer == null) {
                hold(mailbox, message, name);
                held.add(
                        new MessageDelivery.Held(
                                name, "this project may not address '" + addressed.get(0) + "'"));
                continue;
            }
            // Last, and after the peer is known to exist: a person's decision is about a peer, and
            // "held" is a different answer from "this project may not address that name".
            final Path released = mailbox.accepted().resolve(name + RELEASED_SUFFIX);
            final String waiting =
                    Files.isRegularFile(released) && !moderation.refuses(addressed.get(0))
                            ? ""
                            : moderation.whyNotNow(addressed.get(0));
            if (!waiting.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, waiting));
                continue;
            }
            // And what the filter flagged, though it let it through because it only reports: only
            // that waits for a person, whatever the peer's mode (the operator in walk 10,
            // 2026-10-04). Held until a person releases it.
            final String flagged =
                    Files.isRegularFile(released)
                            ? ""
                            : MessageLookup.heldBack(mailbox, MessageFile.id(message));
            if (!flagged.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, flagged));
                continue;
            }
            final String full =
                    budget == null
                            ? ""
                            : budget.outbound(
                                    addressed.get(0), queuedNow.getOrDefault(addressed.get(0), 0));
            if (!full.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, full));
                continue;
            }
            final Path active = mailbox.queueActive(peer.transport());
            // Never the mailbox again, if its task was removed during the pass.
            mailbox.ensure(active);
            // The destination is written down here rather than resolved again at send time: the
            // peer table can change between the two, and a message must go where it was addressed
            // when it was accepted.
            Files.writeString(
                    active.resolve(name + DESTINATION_SUFFIX),
                    peer.destination(),
                    StandardCharsets.UTF_8);
            move(mailbox.accepted().resolve(name + ".sig"), active.resolve(name + ".sig"));
            Files.move(message, active.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(released);
            queued.put(name, peer.transport());
            addressedTo.put(name, peer.name());
            queuedNow.merge(peer.name(), 1, Integer::sum);
        }
        return new Outcome(queued, held, addressedTo, refused);
    }

    /**
     * Hands a task's reply to the review, as the task whose mailbox it came through.
     *
     * <p>The message chooses the thread; nothing in it chooses the task. Its {@code metadata.from}
     * is the task's own word and is never read here: the review is told the task as the host's own
     * record gives the mailbox, its project and recorded container with its name, so a reply can
     * only ever speak for the task that wrote it.
     */
    private void reply(
            final Mailbox mailbox,
            final Path message,
            final String name,
            final List<MessageDelivery.Held> held,
            final List<MessageDelivery.Held> refused)
            throws IOException {
        final Path released = mailbox.accepted().resolve(name + RELEASED_SUFFIX);
        // What the filter flagged waits for a person, as any message does.
        final String flagged =
                Files.isRegularFile(released)
                        ? ""
                        : MessageLookup.heldBack(mailbox, MessageFile.id(message));
        if (!flagged.isEmpty()) {
            hold(mailbox, message, name);
            held.add(new MessageDelivery.Held(name, flagged));
            return;
        }
        final Reply reply = Reply.of(Json.parse(Files.readString(message, StandardCharsets.UTF_8)));
        final TaskIdentity task = tasks.of(mailbox.root().getFileName().toString());
        String why = reply.refusal();
        if (why.isEmpty() && task == null) {
            why = "no task of this machine owns the mailbox it came through";
        }
        if (why.isEmpty() && task != null) {
            why = replies.take(task, reply.thread(), reply.text()).orElse("");
        }
        Files.deleteIfExists(released);
        if (why.isEmpty()) {
            // Kept by the review, which holds it from now on.
            Files.deleteIfExists(mailbox.accepted().resolve(name + ".sig"));
            Files.delete(message);
            return;
        }
        // Final, so it lies with what the filter refused rather than with what a person may
        // release: the same reply would be refused again.
        move(mailbox.accepted().resolve(name + ".sig"), mailbox.rejected().resolve(name + ".sig"));
        final Path rejected = mailbox.rejected().resolve(name);
        Files.move(message, rejected, StandardCopyOption.REPLACE_EXISTING);
        final String reason = "it was not taken by the review: " + why;
        new HostBounce().write(mailbox, rejected, reason, true);
        refused.add(new MessageDelivery.Held(name, reason));
    }

    /**
     * A reply to a review thread as its message carries it: {@code metadata.kind} {@code comment},
     * {@code metadata.thread} and one text part.
     *
     * @param thread The thread it answers, or {@code ""} when refused.
     * @param text Its text, or {@code ""} when refused.
     * @param refusal Why the message is no reply, or {@code ""} when it is one. Never quotes it.
     */
    record Reply(String thread, String text, String refusal) {

        /** The only kind a reply has. */
        static final String KIND = "comment";

        static Reply of(final @org.jspecify.annotations.Nullable Object parsed) {
            if (!(parsed instanceof Map<?, ?> document)
                    || !(document.get("metadata") instanceof Map<?, ?> metadata)) {
                return refused("a reply to the review is a message with metadata");
            }
            if (!KIND.equals(metadata.get("kind"))) {
                return refused(
                        "a reply to the review has metadata.kind '" + KIND + "', and nothing else");
            }
            if (!(metadata.get("thread") instanceof String thread) || thread.isBlank()) {
                return refused(
                        "a reply to the review names the thread it answers in metadata.thread");
            }
            // One text part and nothing else: a task never sends the review a report, only words.
            if (!(document.get("parts") instanceof List<?> parts)
                    || parts.size() != 1
                    || !(parts.get(0) instanceof Map<?, ?> part)
                    || !(part.get("text") instanceof String text)
                    || part.containsKey("data")
                    || part.containsKey("raw")
                    || part.containsKey("url")
                    || part.containsKey("file")) {
                return refused("a reply to the review has exactly one text part and no other part");
            }
            return new Reply(thread, text, "");
        }

        private static Reply refused(final String why) {
            return new Reply("", "", why);
        }
    }

    private List<String> addressees(final Path message) throws IOException {
        final Object parsed = Json.parse(Files.readString(message, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> document)
                || !(document.get("metadata") instanceof Map<?, ?> metadata)) {
            return List.of();
        }
        final Object to = metadata.get("to");
        if (to instanceof String one) {
            return one.isBlank() ? List.of() : List.of(one);
        }
        if (to instanceof List<?> many) {
            return many.stream().map(String::valueOf).filter(name -> !name.isBlank()).toList();
        }
        return List.of();
    }

    private void hold(final Mailbox mailbox, final Path message, final String name)
            throws IOException {
        // Held again for another reason - a full budget, say: the release was for that once.
        Files.deleteIfExists(mailbox.accepted().resolve(name + RELEASED_SUFFIX));
        move(mailbox.accepted().resolve(name + ".sig"), mailbox.hold().resolve(name + ".sig"));
        Files.move(message, mailbox.hold().resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private void move(final Path from, final Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> accepted(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
