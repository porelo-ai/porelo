package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CommitNotInMirror;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewRole;
import org.fuin.porelo.gate.review.ReviewSarif;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Answers what is open at a place for one role, and the place's decision, read anew from the gate's
 * records at each call: the push that waits, the end kept for its task, and the review's journal.
 * Nothing is stored for the answer and nothing is started.
 *
 * <p>The one implementation for the daemon's {@code ReviewOpen} and {@code porelo review open}, so
 * both answer alike. A pull request is read through its forge's adapter for the task whose run
 * asks; the task's own pull request is refused until it is found by its branch.
 */
public final class ReviewOpen {

    /** The reason of a time that could not be read. */
    static final String TIME_MISSING = "TimeMissing";

    /** The reason of a gate record of another push or another head than the one asked about. */
    static final String RECORD_MISMATCH = "RecordMismatch";

    /** The reason of a review journal that could not be read. */
    static final String JOURNAL_UNREADABLE = "JournalUnreadable";

    private final Parts parts;

    /**
     * Constructor with what the answer is read from.
     *
     * @param parts The gate, the journal, its counts and the kept ends.
     */
    public ReviewOpen(final Parts parts) {
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    /**
     * Returns the answer of a machine, read from its gates, its journals and its kept ends.
     *
     * @param context The machine.
     * @return The answer's reader.
     */
    public static ReviewOpen of(final PoreloContext context) {
        return new ReviewOpen(Parts.of(context));
    }

    /**
     * Answers what is open at a place for a role, and its decision.
     *
     * @param project The project's name.
     * @param place Where.
     * @param role Who asks; null means the author.
     * @param task The task whose run asks, or null; required at a pull request, whose side and held
     *     items are the task's.
     * @return The answer.
     * @throws Refused As {@code PlaceUnreadable} when the place cannot be read, as {@code
     *     UnknownForge} for a host no forge of this machine is, as {@code NoSuchPullRequest} for an
     *     address the forge does not know, as {@code HeadNotInGate} for a pull request whose head
     *     the asking task's gate never forwarded.
     * @throws IllegalArgumentException For a pull request of no task.
     */
    public Answer answer(
            final String project,
            final Place place,
            final @Nullable Role role,
            final @Nullable String task)
            throws Refused {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(place, "place");
        return switch (place) {
            case GatePush push -> atGate(project, push, TaskReviewRole.asksAsAuthor(role));
            case PullRequest pullRequest -> {
                if (task == null || task.isBlank()) {
                    throw new IllegalArgumentException("task");
                }
                yield atPullRequest(project, pullRequest.ref(), task);
            }
            case OwnPullRequest _ -> {
                if (task == null || task.isBlank()) {
                    throw new IllegalArgumentException("task");
                }
                throw Refused.placeUnreadable(
                        "ForgeAdapterMissing", "a pull request place is not read yet");
            }
        };
    }

    private Answer atGate(final String project, final GatePush place, final boolean asAuthor)
            throws Refused {
        final Standing standing = parts.gate().standing(project, place.repository(), place.name());
        final Reading reading = new Reading();
        final Push waiting = standing.waiting();
        final Push push;
        final Decision decision;
        final String head;
        @Nullable String mergedCommit = null;
        @Nullable String mergedHead = null;
        final @Nullable Instant since;
        final @Nullable Instant untimed;
        final PushEnd entry = entry(standing);
        if (waiting != null) {
            push = waiting;
            decision = Decision.OPEN;
            head = waiting.head();
            if (entry == null) {
                // Taken before the gate kept when a push was taken: the time is missing, and the
                // commit's own time is never said in its place.
                since = null;
            } else if (entry.end() == PushEnd.End.WAITING
                    && entry.name().equals(standing.name())
                    && entry.commit().equals(waiting.head())) {
                since = entry.at();
            } else {
                // A record of another push or another head says nothing of this one.
                reading.incomplete(RECORD_MISMATCH);
                since = null;
            }
            untimed = since;
        } else {
            // Only an end of this push decides it; a waiting record or another push's end does not.
            final PushEnd end =
                    entry != null
                                    && entry.end() != PushEnd.End.WAITING
                                    && entry.name().equals(standing.name())
                            ? entry
                            : null;
            final Optional<Push> recorded = recorded(standing, reading);
            if (end == null) {
                // Threads without an end: the push stopped waiting in a way no end was kept for,
                // so no decision can be said.
                throw Refused.placeUnreadable(
                        "NoSuchPush",
                        recorded.isEmpty()
                                ? "nothing of that name waits, ended or was reviewed"
                                : "nothing of that name waits and how it ended is not kept");
            }
            push = recorded.orElse(null);
            head = end.commit();
            decision =
                    switch (end.end()) {
                        case REJECTED ->
                                end.openThreads() > 0 ? Decision.RETURNED : Decision.REJECTED;
                        case APPROVED -> Decision.APPROVED;
                        case LANDED -> Decision.LANDED;
                        case WAITING ->
                                throw new IllegalStateException("a waiting record is no end");
                    };
            mergedCommit = end.mergedCommit();
            mergedHead = end.branch();
            since = end.at();
            // Carried along by the end when the gate kept when the push was taken.
            untimed = end.waitingSince();
        }
        final List<Point> points = new ArrayList<>();
        int findings = 0;
        if (push != null) {
            try {
                for (final ReviewThread thread : parts.store().threads(push)) {
                    final Point point = point(thread, standing, asAuthor, untimed, since, reading);
                    if (point != null) {
                        points.add(point);
                    }
                }
                findings = parts.counts().of(push).openFindings();
            } catch (RuntimeException ex) {
                // A journal refused is never read as no thread at all.
                points.clear();
                findings = 0;
                reading.incomplete(JOURNAL_UNREADABLE);
            }
        }
        if (since == null) {
            reading.incomplete(TIME_MISSING);
        }
        return Answer.of(
                head,
                decision,
                mergedCommit,
                mergedHead,
                since == null ? null : since.toString(),
                points,
                findings,
                0,
                reading.reason);
    }

    /**
     * Reads a pull request whole: its forge instance, the read credential lent for this answer, its
     * facts, its conversation through the one input path, and the asking task's side and held
     * items. Nothing is pinned, fetched or kept.
     */
    private Answer atPullRequest(final String project, final PullRequestRef ref, final String task)
            throws Refused {
        final ForgeInstance instance;
        try {
            instance =
                    parts.forges()
                            .of(ref.url())
                            .orElseThrow(
                                    () -> new Refused("UnknownForge", Map.of("host", ref.forge())));
        } catch (ForgeFileUnreadable ex) {
            throw Refused.placeUnreadable(
                    "ForgeAdapterMissing", "this machine's forge file cannot be read");
        }
        final String adapter = instance.adapter();
        if (adapter == null) {
            throw Refused.placeUnreadable(
                    "ForgeAdapterMissing", "the forge entry of " + ref.forge() + " does not read");
        }
        final PullRequestParts reader =
                parts.adapters()
                        .of(instance)
                        .orElseThrow(
                                () ->
                                        Refused.placeUnreadable(
                                                "ForgeAdapterMissing",
                                                "no client of the forge adapter "
                                                        + adapter
                                                        + " is called on this machine"));
        final Recorded recorded =
                reader.containers()
                        .recorded(project, task)
                        .orElseThrow(
                                () ->
                                        Refused.placeUnreadable(
                                                "NoSuchPush", "no task is recorded for " + task));
        final String address = ref.url().toString();
        final Producer side;
        final ForgeCredential credential;
        final PullRequestFacts facts;
        try {
            credential = reader.credentials().lend(instance.credential());
            facts = reader.forge().facts(ref, credential);
            // Asked only of a pull request the forge knows.
            final ReviewPlace place =
                    reader.places()
                            .named(address)
                            .orElseThrow(
                                    () ->
                                            new PlaceUnreadable(
                                                    PlaceUnreadable.Reason.UNREACHABLE,
                                                    "the pull request cannot be reached"));
            // The run's role and its held items are kept under the task's container.
            side = side(reader.role(), recorded.container(), place);
        } catch (PlaceUnreadable ex) {
            throw refused(ex, ref);
        }
        final Reading reading = new Reading();
        final Pinned pinned =
                new Pinned(
                        new Push(
                                project,
                                recorded.repository(),
                                task,
                                facts.head(),
                                facts.mergeBase(),
                                recorded.recordedId()),
                        facts);
        List<Kept> items;
        Set<String> resolved;
        try {
            final Conversation conversation =
                    new ConversationRead(reader.forge(), reader.reading(), reader.check())
                            .read(ref, pinned, credential);
            items = conversation.items();
            resolved = conversation.resolved();
            if (conversation.reason() != null) {
                reading.incomplete(conversation.reason());
            }
        } catch (CommitNotInMirror ex) {
            // The run pinned a head its gate never forwarded: nothing there can be judged.
            throw new Refused("HeadNotInGate", Map.of("head", facts.head()));
        } catch (PlaceUnreadable ex) {
            if (ex.reason() != PlaceUnreadable.Reason.UNREACHABLE) {
                throw refused(ex, ref);
            }
            // The facts were read, so the decision stands; the threads are what is missing.
            items = List.of();
            resolved = Set.of();
            reading.incomplete("Unreachable");
        }
        final List<Point> points = forgePoints(items, resolved, side, reading);
        int findings = 0;
        try {
            findings = parts.counts().of(pinned.push()).openFindings();
        } catch (RuntimeException ex) {
            // As at the gate: a journal refused is never read as no finding at all.
            reading.incomplete(JOURNAL_UNREADABLE);
        }
        final int held =
                (int)
                        reader.holds().undecided(recorded.container()).stream()
                                .filter(each -> each.place().equals(address))
                                .count();
        // An open pull request's decision has no time to miss; a merge or a close carries the
        // forge's own.
        final Instant decided = facts.decidedAt();
        return Answer.of(
                facts.head(),
                switch (facts.state()) {
                    case OPEN -> facts.approved() ? Decision.APPROVED : Decision.OPEN;
                    case MERGED -> Decision.MERGED;
                    case CLOSED -> Decision.CLOSED;
                },
                facts.mergeCommit(),
                facts.mergedHead(),
                decided == null ? null : decided.truncatedTo(ChronoUnit.SECONDS).toString(),
                points,
                findings,
                held,
                reading.reason);
    }

    /** The account the asking task's side writes as; a refusal the role carries is passed on. */
    private static Producer side(final ReviewRole role, final String task, final ReviewPlace place)
            throws PlaceUnreadable {
        try {
            return role.postingAccount(task, place);
        } catch (IllegalStateException ex) {
            if (ex.getCause() instanceof PlaceUnreadable unreadable) {
                throw unreadable;
            }
            throw ex;
        }
    }

    /** A forge's refusal by the contract's error: an unknown pull request names its address. */
    private static Refused refused(final PlaceUnreadable ex, final PullRequestRef ref) {
        return switch (ex.reason()) {
            case NO_SUCH_PULL_REQUEST ->
                    new Refused("NoSuchPullRequest", Map.of("url", ref.url().toString()));
            case UNKNOWN_FORGE -> new Refused("UnknownForge", Map.of("host", ref.forge()));
            case FORGE_FILE_UNREADABLE ->
                    Refused.placeUnreadable(
                            "ForgeAdapterMissing", "this machine's forge file cannot be read");
            default ->
                    Refused.placeUnreadable(
                            ex.reason().wireName(), String.valueOf(ex.getMessage()));
        };
    }

    /**
     * The open points of a pull request's conversation: each thread, in the order it was read, not
     * resolved on the forge and whose last item is not the asking side's.
     */
    private static List<Point> forgePoints(
            final List<Kept> items,
            final Set<String> resolved,
            final Producer side,
            final Reading reading) {
        final Map<String, List<Kept>> threads = new LinkedHashMap<>();
        for (final Kept item : items) {
            threads.computeIfAbsent(item.thread(), id -> new ArrayList<>()).add(item);
        }
        final List<Point> points = new ArrayList<>();
        for (final Map.Entry<String, List<Kept>> thread : threads.entrySet()) {
            final Kept last = thread.getValue().getLast();
            final Producer by = last.origin().producer();
            if (resolved.contains(thread.getKey()) || same(by, side)) {
                continue;
            }
            Instant at = last.at();
            if (at == null) {
                reading.incomplete(TIME_MISSING);
                at = Instant.EPOCH;
            }
            @Nullable String path = null;
            @Nullable Integer line = null;
            if (thread.getValue().getFirst().item() instanceof ReviewComment comment
                    && comment.anchor() instanceof LineAnchor anchor) {
                path = anchor.path();
                line = anchor.startLine();
            }
            points.add(
                    new Point(
                            thread.getKey(),
                            path,
                            line,
                            by instanceof ForgeAccount account ? account.name() : by.words(),
                            at.toString()));
        }
        return points;
    }

    /** Whether two producers are one: a forge account by its forge and id, its name may change. */
    private static boolean same(final Producer one, final Producer other) {
        if (one instanceof ForgeAccount a && other instanceof ForgeAccount b) {
            return a.forge().equals(b.forge()) && a.id().equals(b.id());
        }
        return one.equals(other);
    }

    /** The task container's record of its last push: waiting, or how it ended; null for none. */
    private @Nullable PushEnd entry(final Standing standing) {
        try {
            return parts.ends().read(standing.task()).orElse(null);
        } catch (IOException ex) {
            throw new UncheckedIOException("where the push stands could not be read", ex);
        }
    }

    private Optional<Push> recorded(final Standing standing, final Reading reading) {
        try {
            return parts.store()
                    .recorded(
                            standing.project(),
                            standing.repository(),
                            standing.name(),
                            standing.task());
        } catch (RuntimeException ex) {
            reading.incomplete(JOURNAL_UNREADABLE);
            return Optional.empty();
        }
    }

    /** The thread as a point for the role, or null when it is none. */
    private static @Nullable Point point(
            final ReviewThread thread,
            final Standing standing,
            final boolean asAuthor,
            final @Nullable Instant untimed,
            final @Nullable Instant decided,
            final Reading reading) {
        if (thread.state() != ThreadState.OPEN) {
            return null;
        }
        final Kept last = thread.items().getLast();
        final boolean authors = authors(last.origin().producer(), standing.taskName());
        if (authors == asAuthor) {
            // The asking role's side wrote last: the thread waits for the other side.
            return null;
        }
        Instant at = last.at();
        if (at == null) {
            at = untimed;
        }
        if (at == null) {
            // Neither the item's time nor its push's waiting time is held; the decision's time is
            // the latest it can have been written, and the answer says it is incomplete.
            reading.incomplete(TIME_MISSING);
            at = decided == null ? Instant.EPOCH : decided;
        }
        final @Nullable String path;
        final @Nullable Integer line;
        switch (thread.items().getFirst().item()) {
            case ReviewComment comment -> {
                switch (comment.anchor()) {
                    case LineAnchor anchor -> {
                        path = anchor.path();
                        line = anchor.startLine();
                    }
                }
            }
            default -> {
                path = null;
                line = null;
            }
        }
        return new Point(thread.id(), path, line, last.origin().producer().words(), at.toString());
    }

    /** Whether a producer is on the author's side: the push's own task. */
    private static boolean authors(final Producer producer, final String taskName) {
        return producer instanceof Task task && task.id().equals(taskName);
    }

    /** What could not be read while answering; the first reason is said. */
    private static final class Reading {
        private @Nullable String reason;

        void incomplete(final String why) {
            if (reason == null) {
                reason = why;
            }
        }
    }

    /** A place's decision, as the contract names it. */
    public enum Decision {
        /** The push waits. */
        OPEN,
        /** Rejected with open threads, back to the author. */
        RETURNED,
        /** Rejected with no open thread. */
        REJECTED,
        /** Found merged elsewhere. */
        LANDED,
        /** Approved and forwarded. */
        APPROVED,
        /** A pull request merged. */
        MERGED,
        /** A pull request closed unmerged. */
        CLOSED
    }

    /** Where a run works: exactly one place. */
    public sealed interface Place permits GatePush, PullRequest, OwnPullRequest {}

    /**
     * A push at this machine's gate.
     *
     * @param repository The project's repository, or null for the task's own.
     * @param name The push's name, its task's name.
     */
    public record GatePush(@Nullable String repository, String name) implements Place {

        /** Refuses a blank name. */
        public GatePush {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name");
            }
        }
    }

    /**
     * A pull request at a forge, by its https address.
     *
     * @param ref The pull request.
     */
    public record PullRequest(PullRequestRef ref) implements Place {

        /** Refuses a missing reference. */
        public PullRequest {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** The asking task's own pull request. */
    public record OwnPullRequest() implements Place {}

    /**
     * An open thread, as the answer counts it.
     *
     * @param thread The thread's id.
     * @param path The file it is on, or null for one on the whole push.
     * @param line Its first line on that file, or null with path.
     * @param lastBy Who wrote its last item, in the producer's words.
     * @param lastAt When, UTC, RFC 3339, whole seconds.
     */
    public record Point(
            String thread,
            @Nullable String path,
            @Nullable Integer line,
            String lastBy,
            String lastAt) {

        /** Refuses a missing part. */
        public Point {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(lastBy, "lastBy");
            Objects.requireNonNull(lastAt, "lastAt");
        }

        Map<String, Object> fields() {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("thread", thread);
            if (path != null) {
                fields.put("path", path);
            }
            if (line != null) {
                fields.put("line", line);
            }
            fields.put("lastBy", lastBy);
            fields.put("lastAt", lastAt);
            return fields;
        }
    }

    /**
     * What is open at a place and its decision, as the contract answers it.
     *
     * @param head The head at the place, or null when nothing stands there.
     * @param decision The decision.
     * @param mergedCommit The forwarded or merge commit, or null.
     * @param mergedHead The head that commit took, or null with it.
     * @param decisionSince When the decision began, or null for a missing time.
     * @param points The open threads the asking role did not answer last.
     * @param findings Findings no one answered.
     * @param held Review items held back from the run.
     * @param complete Whether every part could be read.
     * @param reason Why not, or null when complete.
     * @param version A SHA-256 digest of the answer without it.
     */
    public record Answer(
            @Nullable String head,
            Decision decision,
            @Nullable String mergedCommit,
            @Nullable String mergedHead,
            @Nullable String decisionSince,
            List<Point> points,
            int findings,
            int held,
            boolean complete,
            @Nullable String reason,
            String version) {

        /** Copies the points and refuses a missing part. */
        public Answer {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(version, "version");
            points = List.copyOf(points);
        }

        static Answer of(
                final @Nullable String head,
                final Decision decision,
                final @Nullable String mergedCommit,
                final @Nullable String mergedHead,
                final @Nullable String decisionSince,
                final List<Point> points,
                final int findings,
                final int held,
                final @Nullable String reason) {
            final Answer unversioned =
                    new Answer(
                            head,
                            decision,
                            mergedCommit,
                            mergedHead,
                            decisionSince,
                            points,
                            findings,
                            held,
                            reason == null,
                            reason,
                            "");
            return new Answer(
                    head,
                    decision,
                    mergedCommit,
                    mergedHead,
                    decisionSince,
                    points,
                    findings,
                    held,
                    reason == null,
                    reason,
                    digest(Json.write(unversioned.unversioned())));
        }

        /**
         * Returns the answer by the contract's names, in its order; an absent field is left out.
         *
         * @return The fields.
         */
        public Map<String, Object> fields() {
            final Map<String, Object> fields = unversioned();
            fields.put("version", version);
            return fields;
        }

        private Map<String, Object> unversioned() {
            final Map<String, Object> fields = new LinkedHashMap<>();
            if (head != null) {
                fields.put("head", head);
            }
            fields.put("decision", decision.name());
            if (mergedCommit != null) {
                fields.put("mergedCommit", mergedCommit);
            }
            if (mergedHead != null) {
                fields.put("mergedHead", mergedHead);
            }
            if (decisionSince != null) {
                fields.put("decisionSince", decisionSince);
            }
            fields.put("points", points.stream().map(Point::fields).toList());
            fields.put("findings", findings);
            fields.put("held", held);
            fields.put("complete", complete);
            if (reason != null) {
                fields.put("reason", reason);
            }
            return fields;
        }

        private static String digest(final String text) {
            try {
                return HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("every Java runtime has SHA-256", ex);
            }
        }
    }

    /**
     * A typed refusal of the contract: its error's name and fields.
     *
     * <p>{@code PlaceUnreadable} at either place; {@code UnknownForge} and {@code
     * NoSuchPullRequest} at a pull request.
     */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final String error;

        private final Map<String, Object> fields;

        /**
         * Constructor with the error's name and fields.
         *
         * @param error The error's name without the interface, such as {@code PlaceUnreadable}.
         * @param fields Its fields, by the contract's names.
         */
        public Refused(final String error, final Map<String, Object> fields) {
            super(error);
            this.error = Objects.requireNonNull(error, "error");
            // Kept in the order given, so a refusal prints its fields as the contract lists them.
            this.fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * Returns a {@code PlaceUnreadable}.
         *
         * @param reason Its reason, one of the contract's.
         * @param detail What a person reads.
         * @return The refusal.
         */
        public static Refused placeUnreadable(final String reason, final String detail) {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("reason", reason);
            fields.put("detail", detail);
            return new Refused("PlaceUnreadable", fields);
        }

        /**
         * Returns the error's name.
         *
         * @return The name, without the interface.
         */
        public String error() {
            return error;
        }

        /**
         * Returns the error's fields.
         *
         * @return The fields, by the contract's names.
         */
        public Map<String, Object> fields() {
            return fields;
        }
    }

    /**
     * What the gate holds of a push: the task recorded for it and, while it waits, the push.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param task The container id recorded for its task, which keys its journal and its end.
     * @param taskName The task's name, whose items are the author's side.
     * @param waiting The push as it waits, or null when nothing of that name waits.
     */
    public record Standing(
            String project,
            String repository,
            String name,
            String task,
            String taskName,
            @Nullable Push waiting) {

        /** Refuses a missing part. */
        public Standing {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(taskName, "taskName");
        }
    }

    /**
     * Finds what the gate holds of a push.
     *
     * <p>A stand-in answers a fixed standing, or refuses.
     */
    @FunctionalInterface
    public interface Gate {

        /**
         * Returns what the gate holds of a push.
         *
         * @param project The project's name.
         * @param repository The repository's name, or null for the task's own.
         * @param name The push's name.
         * @return The standing.
         * @throws Refused {@code PlaceUnreadable} as {@code NotInProject} for a project or
         *     repository this machine does not have, as {@code NoSuchPush} for a push no task is
         *     recorded for.
         */
        Standing standing(String project, @Nullable String repository, String name) throws Refused;
    }

    /** Reads the end kept for a task container. */
    @FunctionalInterface
    public interface Ends {

        /**
         * Returns the end kept for a task container.
         *
         * @param task The recorded container id.
         * @return The end, or empty.
         * @throws IOException When it cannot be read.
         */
        Optional<PushEnd> read(String task) throws IOException;
    }

    /** Finds the forge instance a pull request's address belongs to. */
    @FunctionalInterface
    public interface Forges {

        /**
         * Finds the instance of an address's host.
         *
         * @param url The pull request's address.
         * @return The instance, an unreadable entry included, or empty for a host this machine
         *     knows no forge of.
         * @throws ForgeFileUnreadable When the machine's forge file cannot be read.
         */
        Optional<ForgeInstance> of(URI url) throws ForgeFileUnreadable;
    }

    /** Finds what reads pull requests through a forge instance's adapter. */
    @FunctionalInterface
    public interface Adapters {

        /**
         * Returns what reads pull requests at an instance.
         *
         * @param instance A readable instance, which names its adapter.
         * @return The parts, or empty when no client of its adapter is called on this machine.
         */
        Optional<PullRequestParts> of(ForgeInstance instance);
    }

    /**
     * The container recorded for a task: its name keys the run's role and its held items, its id
     * the task's push and its checks.
     *
     * @param container The container's name.
     * @param recordedId The container's recorded id.
     * @param repository The project's repository the task works in, whose mirror holds what its
     *     gate forwarded: never the forge's address, which names no mirror here.
     */
    public record Recorded(String container, String recordedId, String repository) {

        /** Refuses a missing part. */
        public Recorded {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(recordedId, "recordedId");
            Objects.requireNonNull(repository, "repository");
        }
    }

    /** Finds the container recorded for a task. */
    @FunctionalInterface
    public interface Containers {

        /**
         * Returns the container recorded for a task.
         *
         * @param project The project's name.
         * @param task The task's name.
         * @return Its container, or empty when none is recorded.
         */
        Optional<Recorded> recorded(String project, String task);
    }

    /**
     * What a pull request is read with: its adapter's review calls, the one input path, the check
     * of each comment, the read credential's lender, and the run's side, task and held items.
     *
     * @param forge The adapter's review calls.
     * @param reading The one input path for profile documents.
     * @param check The check of each comment against the pinned push.
     * @param credentials Lends the forge's read credential for one call.
     * @param places Finds the place a pull request's address names.
     * @param role Whose account the asking task's side writes as.
     * @param containers The container recorded for a task.
     * @param holds The items held for the owner account's decision.
     */
    public record PullRequestParts(
            ForgeReview forge,
            ProfileReading reading,
            CommentCheck check,
            ReleasedSender.Credentials credentials,
            ReviewPlaces places,
            ReviewRole role,
            Containers containers,
            ReviewHolds holds) {

        /** Refuses a missing part. */
        public PullRequestParts {
            Objects.requireNonNull(forge, "forge");
            Objects.requireNonNull(reading, "reading");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(places, "places");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(containers, "containers");
            Objects.requireNonNull(holds, "holds");
        }
    }

    /**
     * What the answer is read from.
     *
     * @param gate What the gate holds of a push.
     * @param store The review's threads.
     * @param counts The review's counts.
     * @param ends The kept ends.
     * @param forges The forge instances this machine knows.
     * @param adapters What reads pull requests at an instance.
     */
    public record Parts(
            Gate gate,
            ReviewStore store,
            ReviewCounts counts,
            Ends ends,
            Forges forges,
            Adapters adapters) {

        /** Refuses a missing part. */
        public Parts {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(counts, "counts");
            Objects.requireNonNull(ends, "ends");
            Objects.requireNonNull(forges, "forges");
            Objects.requireNonNull(adapters, "adapters");
        }

        /**
         * Returns a machine's parts: its gates, the owner account's journals, the ends kept beside
         * them, its forge file, and a pull request read through its forge's installed adapter.
         *
         * @param context The machine.
         * @return The parts.
         */
        public static Parts of(final PoreloContext context) {
            final CommentIntake.Parts comments = CommentIntake.Parts.of(context);
            final ForgeInstanceFile forges = ForgeInstanceFile.of(context);
            return new Parts(
                    gateAt(context),
                    comments.store(),
                    comments.counts(),
                    new GateEnds(context).store()::read,
                    forges::of,
                    pullRequestsAt(context, forges, ForgeAdapters.of(context)));
        }
    }

    /**
     * Returns what reads a pull request at a forge whose adapter is installed: the same adapter
     * calls, input path, check, credentials and role a run at it uses. An answer never pins, so its
     * places and its role pin nothing.
     *
     * @param context The machine.
     * @param forges Its forge file.
     * @param adapters The forge adapters installed.
     * @return The reader, empty for an instance whose adapter is not installed.
     */
    static Adapters pullRequestsAt(
            final PoreloContext context,
            final ForgeInstanceFile forges,
            final ForgeAdapters adapters) {
        return instance -> {
            final String adapter = instance.adapter();
            if (adapter == null || !adapters.installed(adapter)) {
                return Optional.empty();
            }
            final ProfileTranslator reading = new ProfileTranslator();
            final CommentCheck check = CommentIntake.Parts.of(context).check();
            final ReleasedSender.Credentials credentials =
                    entry -> MachineReviewRole.lend(context, entry);
            final TaskReviewRole role =
                    MachineReviewRole.of(
                            context,
                            forges,
                            (task, upstream, branch) -> {
                                throw new IOException("ReviewOpen never pins a head");
                            });
            final PullRequestPlaces places =
                    new PullRequestPlaces(
                            forges,
                            adapters::installed,
                            each -> AdapterForgeReview.of(context, each),
                            review ->
                                    (ref, task) -> {
                                        throw new PlaceUnreadable(
                                                PlaceUnreadable.Reason.NOT_IN_PROJECT,
                                                "ReviewOpen never pins a head");
                                    },
                            reading,
                            check,
                            new ReviewSarif(),
                            credentials,
                            role::mayPush,
                            (task, facts) -> {
                                throw new PlaceUnreadable(
                                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                                        "ReviewOpen never pins a head");
                            });
            return Optional.of(
                    new PullRequestParts(
                            AdapterForgeReview.of(context, adapter),
                            reading,
                            check,
                            credentials,
                            places,
                            role,
                            (project, task) -> {
                                try {
                                    final TaskIdentity identity =
                                            CommentIntake.recorded(context, project, task);
                                    return Optional.of(
                                            new Recorded(
                                                    identity.container(),
                                                    identity.recordedId(),
                                                    GateSupport.repository(
                                                                    context,
                                                                    GateSupport.byName(
                                                                            context, project),
                                                                    null,
                                                                    task)
                                                            .name()));
                                } catch (ReviewRefused ex) {
                                    return Optional.empty();
                                }
                            },
                            new HoldJournal(context.paths().gate().reviewHolds())));
        };
    }

    /**
     * Returns what a machine's gates hold of a push: the task recorded for it, and the push while
     * it waits.
     *
     * @param context The machine.
     * @return The gate.
     */
    static Gate gateAt(final PoreloContext context) {
        return (projectName, repositoryName, name) -> {
            final Project project;
            final Repository repository;
            try {
                project = GateSupport.byName(context, projectName);
                repository = GateSupport.repository(context, project, repositoryName, name);
            } catch (RuntimeException ex) {
                throw Refused.placeUnreadable(
                        "NotInProject",
                        repositoryName == null
                                ? "this machine has no project " + projectName
                                : "this machine has no repository "
                                        + repositoryName
                                        + " in project "
                                        + projectName);
            }
            final TaskIdentity identity;
            try {
                identity = CommentIntake.recorded(context, project.name(), name);
            } catch (ReviewRefused ex) {
                throw Refused.placeUnreadable("NoSuchPush", "no task is recorded for " + name);
            }
            final GitGate gate = GateSupport.gate(context, project, repository, null, null);
            @Nullable Push waiting = null;
            if (GateSupport.holds(gate)) {
                try {
                    final GitGate.Waiting head = gate.pendingWork(name);
                    waiting =
                            new Push(
                                    project.name(),
                                    repository.name(),
                                    name,
                                    head.commit(),
                                    null,
                                    identity.recordedId());
                } catch (GateException ex) {
                    // Nothing of that name waits; its end and journal say the rest.
                    waiting = null;
                }
            }
            return new Standing(
                    project.name(),
                    repository.name(),
                    name,
                    identity.recordedId(),
                    identity.task(),
                    waiting);
        };
    }
}
