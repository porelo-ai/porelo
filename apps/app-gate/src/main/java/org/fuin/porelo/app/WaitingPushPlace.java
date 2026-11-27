package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Another task's push waiting at this machine's gate, as the place of a task that reads it: the
 * waiting head and its base are pinned into the reading task's own gate, its conversation is the
 * push's threads in the review store, and the reading task's items go into those threads through
 * the review's intake as the task itself. No account is used and nothing leaves the machine.
 *
 * <p>The threads follow the push whatever its head, as the store keeps them: a push that moves
 * while a task reads it leaves the task at the commit it was pinned to, and its items are checked
 * and kept against that commit. A push of the name another task pushed after the one read went is
 * none of it: never pinned to, and nothing written into its threads.
 */
public final class WaitingPushPlace implements ReviewPlace {

    /**
     * What a run at a waiting push is read and written with: nothing, since the threads are this
     * machine's. {@link RunStart} and {@link ReleasedSender} lend a credential for every place; for
     * a waiting push they lend this one, which is never read, and ask no vault.
     */
    public static final ForgeCredential NO_ACCOUNT =
            new ForgeCredential("porelo.gate.local", "none");

    /** What a push's project, repository or name may be, as a start takes them. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /**
     * Pins a waiting push's head and base into the reading task's own gate, under its pinned ref.
     *
     * <p>A stand-in records what it was asked.
     */
    @FunctionalInterface
    public interface Pinning {

        /**
         * Pins it.
         *
         * @param task The reading task's container name.
         * @param push The push as it waits now, bound to the task that pushed it.
         * @throws PlaceUnreadable When the push is the reading task's own, of another project, or
         *     cannot be pinned; nothing was moved then.
         */
        void pin(String task, Push push) throws PlaceUnreadable;
    }

    private final String project;
    private final @Nullable String repository;
    private final String push;
    private final CommentIntake.Pushes pushes;
    private final CommentIntake.Parts parts;
    private final Pinning pinning;

    /**
     * Constructor.
     *
     * @param project The project the push waits in.
     * @param repository Its repository, or {@code null} for the project's own.
     * @param push The push's name at the gate.
     * @param parts The review's parts: the waiting pushes, the check, the store and the writer.
     * @param pinning Pins the push into a reading task's gate.
     * @throws IllegalArgumentException For a name no start takes.
     */
    public WaitingPushPlace(
            final String project,
            final @Nullable String repository,
            final String push,
            final CommentIntake.Parts parts,
            final Pinning pinning) {
        this.project = named(project);
        this.repository =
                repository == null || repository.equals(project) ? null : named(repository);
        this.push = named(push);
        this.parts = Objects.requireNonNull(parts, "parts");
        this.pushes = parts.pushes();
        this.pinning = Objects.requireNonNull(pinning, "pinning");
    }

    private static String named(final String name) {
        if (!NAME.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("no push's name");
        }
        return name;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code <project>/<repository>/<name>}, the project's own repository by the project's
     *     name: never an https address, so no forge account is ever asked for it.
     */
    @Override
    public String name() {
        return project + "/" + (repository == null ? project : repository) + "/" + push;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The push as it waits now, its head and the base its review compares with, pinned into the
     * reading task's gate; the pinned push stays bound to the task that pushed it, whose threads
     * the run reads and writes in.
     *
     * @param task The reading task's container name.
     */
    @Override
    public Pinned pin(final String task) throws PlaceUnreadable {
        final Push waiting;
        try {
            waiting = pushes.waiting(project, repository, push);
        } catch (ReviewRefused ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.NO_SUCH_PUSH,
                    "nothing waits at the gate of project " + project + " under '" + push + "'");
        }
        if (!parts.tasks().reads(task, waiting)) {
            // The work read went, and another task's waits under its name: never pinned to.
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.NO_SUCH_PUSH,
                    "the push read no longer waits at the gate of project "
                            + project
                            + " under '"
                            + push
                            + "'");
        }
        pinning.pin(task, waiting);
        return new Pinned(waiting, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every thread the store keeps for the pinned push, the oldest first, each opening comment
     * before its replies; read whole from this machine, so it is complete. The credential is not
     * read.
     */
    @Override
    public Conversation conversation(final Pinned pinned, final ForgeCredential credential) {
        final List<Kept> items = new ArrayList<>();
        final List<String> resolved = new ArrayList<>();
        for (final ReviewThread thread : parts.store().threads(pinned.push())) {
            items.addAll(thread.items());
            if (thread.state() == ThreadState.RESOLVED) {
                resolved.add(thread.id());
            }
        }
        return new Conversation(
                items, Set.copyOf(resolved), List.of(), Completeness.COMPLETE, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Written as the gate writes a push's threads, page by page; a thread whose opening comment
     * is not among the items is left out.
     */
    @Override
    public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
        final Map<String, List<Kept>> byThread = new LinkedHashMap<>();
        for (final Kept item : conversation.items()) {
            byThread.computeIfAbsent(item.thread(), id -> new ArrayList<>()).add(item);
        }
        final List<ReviewThread> threads =
                byThread.entrySet().stream()
                        .filter(each -> each.getValue().get(0).item() instanceof ReviewComment)
                        .map(
                                each ->
                                        new ReviewThread(
                                                each.getKey(),
                                                conversation.resolved().contains(each.getKey())
                                                        ? ThreadState.RESOLVED
                                                        : ThreadState.OPEN,
                                                each.getValue()))
                        .collect(Collectors.toList());
        final List<byte[]> pages = new ArrayList<>();
        String cursor = null;
        do {
            final ReviewWriter.Page page;
            try {
                page = parts.writer().page(pinned.push(), threads, cursor);
            } catch (ReviewRefused ex) {
                throw new IllegalStateException("a cursor the writer handed out was refused", ex);
            }
            pages.add(page.sarif().getBytes(StandardCharsets.UTF_8));
            cursor = page.next();
        } while (cursor != null);
        return List.copyOf(pages);
    }

    /**
     * Takes a reading task's item into the pinned push's threads through the review's one intake of
     * a task's items, as the task itself, judged there by its recorded rights and place.
     *
     * @param task The reading task's container name.
     * @param pinned The push its run is pinned to.
     * @param item The item.
     * @return The item as kept.
     * @throws ReviewRefused As the intake refuses it; nothing is kept then.
     */
    public Kept take(final String task, final Push pinned, final ReviewItem item)
            throws ReviewRefused {
        return parts.intake().takeAsTask(task, new Task(task), pinned, List.of(item)).get(0);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Into the push's threads through the review's intake, as the task, against the commit its
     * run was pinned to. The credential is not read: {@link ReleasedSender} lends {@link
     * #NO_ACCOUNT} for a waiting push and asks no vault.
     */
    @Override
    public WriteOutcome write(final Held held, final ForgeCredential credential) {
        final Push now;
        try {
            now = pushes.waiting(project, repository, push);
        } catch (ReviewRefused ex) {
            return new WriteOutcome.Refused(ex.refusal().error().wireName());
        }
        if (!parts.tasks().reads(held.task(), now)) {
            // Another task's push waits under the name now: none of the work the item was for.
            return new WriteOutcome.Refused(ReviewError.NO_SUCH_PUSH.wireName());
        }
        final Push pinned =
                new Push(
                        now.project(),
                        now.repository(),
                        now.name(),
                        held.commit(),
                        now.base(),
                        now.task());
        try {
            if (held.item() instanceof org.fuin.porelo.gate.review.ThreadResolve resolve) {
                return resolved(held, now, pinned, resolve);
            }
            return new WriteOutcome.Delivered(take(held.task(), pinned, held.item()).thread());
        } catch (ReviewRefused ex) {
            return new WriteOutcome.Refused(ex.refusal().error().wireName());
        }
    }

    /**
     * Whether a thread of that id is one of the pinned push's, as the store keeps its threads.
     *
     * @param pinned The push a run is pinned to.
     * @param thread The thread's id.
     * @return True when the store holds it for that push.
     */
    public boolean holdsThread(final Push pinned, final String thread) {
        return parts.store().threads(pinned).stream().anyMatch(each -> each.id().equals(thread));
    }

    /**
     * A released resolve, written through the review's intake as the task only while the push still
     * waits at the commit its run was pinned to: a push that moved or was pushed again under the
     * name is not the one the owner account released it for.
     */
    private WriteOutcome resolved(
            final Held held,
            final Push now,
            final Push pinned,
            final org.fuin.porelo.gate.review.ThreadResolve resolve)
            throws ReviewRefused {
        if (!held.commit().equals(now.head())) {
            return new WriteOutcome.Refused(ForgeWrite.MOVED_SINCE_REVIEW);
        }
        parts.intake().takeAsTask(held.task(), new Task(held.task()), pinned, List.of(resolve));
        return new WriteOutcome.Delivered(resolve.thread());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link CommentReading#TRUSTED}: every item in the store was checked for its format
     *     and size by the intake that kept it, and the message filter's check reads a pull
     *     request's page, which a gate push has none of. The run is still told to read it as
     *     untrusted data.
     */
    @Override
    public CommentReading reading() {
        return CommentReading.TRUSTED;
    }

    /** Names the place, never a credential. */
    @Override
    public String toString() {
        return "WaitingPushPlace[" + name() + "]";
    }

    /**
     * Finds a waiting push's place by its name, and every other name among the pull requests.
     *
     * @param parts The review's parts.
     * @param pinning Pins a push into a reading task's gate.
     * @param pullRequests The places of pull requests.
     * @return The places.
     */
    public static ReviewPlaces places(
            final CommentIntake.Parts parts,
            final Pinning pinning,
            final ReviewPlaces pullRequests) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(pinning, "pinning");
        Objects.requireNonNull(pullRequests, "pullRequests");
        return name -> {
            if (name.regionMatches(true, 0, "https:", 0, "https:".length())) {
                return pullRequests.named(name);
            }
            final String[] named = name.split("/", -1);
            if (named.length != 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(
                        new WaitingPushPlace(named[0], named[1], named[2], parts, pinning));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        };
    }
}
