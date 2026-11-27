package org.fuin.porelo.daemon;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.app.AdapterForgeReview;
import org.fuin.porelo.app.CommentGateCheck;
import org.fuin.porelo.app.CommentIntake;
import org.fuin.porelo.app.ConversationNote;
import org.fuin.porelo.app.EventLog;
import org.fuin.porelo.app.ForgeAdapters;
import org.fuin.porelo.app.ForgeInstanceFile;
import org.fuin.porelo.app.GateSupport;
import org.fuin.porelo.app.HeldRuns;
import org.fuin.porelo.app.HoldingRule;
import org.fuin.porelo.app.InboundCommentFilter;
import org.fuin.porelo.app.MachineTaskRights;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PullRequestPins;
import org.fuin.porelo.app.PullRequestPlace;
import org.fuin.porelo.app.PullRequestPlaces;
import org.fuin.porelo.app.PullRequestRunStart;
import org.fuin.porelo.app.RecordedTaskRights;
import org.fuin.porelo.app.ReleasedSender;
import org.fuin.porelo.app.RunIntake;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.RunStart;
import org.fuin.porelo.app.TaskReject;
import org.fuin.porelo.app.UnheldWrites;
import org.fuin.porelo.app.WaitingPushPins;
import org.fuin.porelo.app.WaitingPushPlace;
import org.fuin.porelo.app.WaitingPushRunStart;
import org.fuin.porelo.core.net.GitCertificates;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.fuin.porelo.gate.review.PullRequestPinning;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewSarif;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.wire.TaskProfile;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * The review's parts for runs at a pull request and at another task's push waiting at the gate,
 * made once for the daemon's life: the run each task is in, the held items, the task's role, the
 * places, the start, the intake of a run's submission and the sending of what the owner released.
 * Every part is keyed by the task's container name.
 *
 * <p>Credentials are read from the vault per call and never kept.
 */
final class ReviewParts {

    /** How long a pull request's head may take to fetch, before this account's factor. */
    private static final Duration FETCH = Duration.ofMinutes(5);

    private final HeldRuns runs;
    private final PullRequestRunStart start;
    private final WaitingPushRunStart waiting;
    private final RunIntake intake;
    private final ReleasedSender sender;

    // Where each task's start recorded its role and place.
    private final java.util.function.Function<String, java.nio.file.Path> states;

    // One pass at a time: two passes over the same due items would post one item twice.
    private final java.util.concurrent.ExecutorService sending;

    private ReviewParts(
            final HeldRuns runs,
            final PullRequestRunStart start,
            final WaitingPushRunStart waiting,
            final RunIntake intake,
            final ReleasedSender sender,
            final java.util.concurrent.ExecutorService sending,
            final java.util.function.Function<String, java.nio.file.Path> states) {
        this.sending = sending;
        this.states = states;
        this.runs = runs;
        this.start = start;
        this.waiting = waiting;
        this.intake = intake;
        this.sender = sender;
    }

    /**
     * Makes this machine's parts over one run record.
     *
     * @param context The machine.
     * @param runs The run each task is in, one for the daemon's life.
     * @return The parts.
     */
    static ReviewParts of(final PoreloContext context, final HeldRuns runs) {
        final HoldJournal holds = new HoldJournal(context.paths().gate().reviewHolds());
        final ForgeInstanceFile forges = ForgeInstanceFile.of(context);
        final ForgeAdapters adapters = ForgeAdapters.of(context);
        final ProfileTranslator reading = new ProfileTranslator();
        final ReviewSarif writing = new ReviewSarif();
        final ReleasedSender.Credentials credentials =
                entry -> MachineTaskRights.lend(context, entry);
        final RecordedTaskRights role = MachineTaskRights.of(context, forges, forwarded(context));
        final CommentIntake.Parts comments = CommentIntake.Parts.of(context);
        final PullRequestPlaces places =
                new PullRequestPlaces(
                        forges,
                        adapters::installed,
                        adapter -> AdapterForgeReview.of(context, adapter),
                        forge -> pinning(context, forge),
                        reading,
                        comments.check(),
                        writing,
                        credentials,
                        task -> role.parts(task).worksOnItsOwn(),
                        authorPins(role, authors(context)));
        final WaitingPushPlace.Pinning pins = waitingPins(context);
        final ReviewPlaces everywhere = WaitingPushPlace.places(comments, pins, places);
        final InboundCommentFilter filter =
                new InboundCommentFilter(
                        context.runner(),
                        context.paths().messaging().messageFilter(),
                        context.limits(),
                        context.paths().gate().commentChecks());
        final ConversationNote delivery = new ConversationNote(context);
        final PullRequestRunStart start =
                new PullRequestRunStart(
                        places, holds, runs, writing, filter, delivery, credentials);
        final WaitingPushRunStart waiting =
                new WaitingPushRunStart(
                        container ->
                                PullRequestPins.gatesOn(context)
                                        .locate(recordedId(context, container))
                                        .project(),
                        comments,
                        pins,
                        holds,
                        runs,
                        container -> new CommentGateCheck(writing, filter, holds, container),
                        delivery);
        final ReleasedSender sender =
                new ReleasedSender(holds, everywhere, role, credentials, Clock.systemUTC());
        final java.util.concurrent.ExecutorService sending =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("porelod-review-send").factory());
        return new ReviewParts(
                runs,
                start,
                waiting,
                new RunIntake(
                                reading,
                                comments.check(),
                                role,
                                holds,
                                runs,
                                everywhere,
                                HoldingRule.of(context),
                                new UnheldWrites(
                                        comments.intake(),
                                        holds,
                                        () -> pass(sending, sender),
                                        // The only trace of an item that goes unheld: none goes
                                        // untraced.
                                        (kind, project, task, detail) ->
                                                EventLog.recordOrRefuse(
                                                        context, kind, project, task, detail)))
                        .rejecting(TaskReject.of(context, role, comments)::judge),
                sender,
                sending,
                container -> context.paths().tasks().containerState(container));
    }

    /**
     * Where an author's task stands for its own pull request.
     *
     * @param gate The task's gate, as a reviewer's pin finds it.
     * @param id The task's recorded container id.
     * @param upstream The repository its approvals go to, as its project names it.
     * @param branch The branch its work carries beyond the gate.
     */
    record Author(PullRequestPins.TaskGate gate, String id, String upstream, String branch) {}

    /** Finds where an author's task stands, by its container name. */
    @FunctionalInterface
    interface Authors {

        /**
         * Finds it.
         *
         * @param container The task's container name.
         * @return Where it stands.
         * @throws PlaceUnreadable As {@code NotInProject} when the task records no gate, upstream
         *     or branch.
         */
        Author of(String container) throws PlaceUnreadable;
    }

    /**
     * Pins an author at its own pull request to the head the forge answered, once the role allows
     * it: that head is the commit the task's own gate last forwarded to its branch. A refusal keeps
     * the contract's {@code NotTheTasksPush} as its cause, so the start answers with it.
     *
     * @param role The task's role, which asks its gate what it forwarded.
     * @param authors Where an author's task stands.
     * @return The pins.
     */
    static PullRequestPlace.AuthorPins authorPins(
            final RecordedTaskRights role, final Authors authors) {
        return (task, facts) -> {
            final Author at = authors.of(task);
            try {
                role.mayPinAtPullRequest(task, at.upstream(), at.branch(), facts.head());
            } catch (RunPlaceCheck.Refused refused) {
                final PlaceUnreadable unpinned =
                        new PlaceUnreadable(
                                PlaceUnreadable.Reason.NO_SUCH_PUSH, refused.refusal().said());
                unpinned.initCause(refused);
                throw unpinned;
            }
            return new Push(
                    at.gate().project(),
                    at.gate().repository(),
                    at.gate().push(),
                    facts.head(),
                    facts.mergeBase(),
                    at.id());
        };
    }

    /**
     * Returns the run each task is in.
     *
     * @return The runs.
     */
    HeldRuns runs() {
        return runs;
    }

    /**
     * Returns the intake of a run's submission, for the message pass.
     *
     * @return The intake.
     */
    RunIntake intake() {
        return intake;
    }

    /**
     * Asks the forge for a pull request before anything is made, as a start's check.
     *
     * @return The check.
     */
    RunPlaceCheck.PullRequestStarts checked() {
        return start::checked;
    }

    /**
     * Starts a task's run at its place, once it is made and before its agent runs; a refusal is the
     * contract's error.
     *
     * @param container The task's container.
     * @param place Its role and place.
     */
    void begin(final String container, final RunPlace place) {
        try {
            started(container, place);
        } catch (RunPlaceCheck.Refused refused) {
            throw new VarlinkException(refused.refusal().error(), refused.refusal().fields());
        }
    }

    /**
     * Begins a task's run at the pull request or the other task's waiting push its start recorded,
     * for a start that is not this daemon's own; a refusal is the contract's error.
     *
     * @param container The task's container.
     * @return The run's number and the head it is pinned to.
     */
    Map<String, Object> begun(final String container) {
        final RunPlace place =
                org.fuin.porelo.runtime.ContainerName.isTask(container)
                        ? RunPlace.readFrom(states.apply(container))
                        : null;
        if (place == null || !place.beginsRun()) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", "task"));
        }
        final RunStart.Outcome outcome;
        try {
            outcome = started(container, place);
        } catch (RunPlaceCheck.Refused refused) {
            throw new VarlinkException(refused.refusal().error(), refused.refusal().fields());
        }
        if (outcome == null) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", "task"));
        }
        return Map.of("run", outcome.run(), "head", outcome.head());
    }

    /** A run at a pull request, or at another task's push waiting at the gate. */
    private RunStart.@org.jspecify.annotations.Nullable Outcome started(
            final String container, final RunPlace place) {
        return place.pullRequest() != null
                ? start.begin(container, place)
                : waiting.begin(container, place);
    }

    /**
     * A reading task's pin to another task's waiting push through its own gate: the pin takes the
     * task's recorded container id, the run its container name.
     */
    private static WaitingPushPlace.Pinning waitingPins(final PoreloContext context) {
        return (container, push) ->
                WaitingPushPins.pin(context, recordedId(context, container), push);
    }

    /**
     * Sends what the owner released, in one pass after another off the caller's thread, so a
     * decision's answer never waits on a forge; a failure is marked on its hold, and one the pass
     * could not mark is said in the daemon's journal.
     */
    void send() {
        pass(sending, sender);
    }

    /**
     * Starts one pass of the released items' sending on the one sending thread; a failure is marked
     * on its hold, and one the pass could not mark is said in the daemon's journal.
     */
    private static void pass(
            final java.util.concurrent.ExecutorService sending, final ReleasedSender sender) {
        sending.execute(
                () -> {
                    try {
                        sender.send();
                    } catch (IllegalStateException ex) {
                        System.err.println("porelod: " + ex.getMessage());
                        System.err.flush();
                    }
                });
    }

    /**
     * Ends a task's run; its held items stay.
     *
     * @param container The task's container.
     */
    void end(final String container) {
        runs.end(container);
    }

    /**
     * A reviewer's pin through the task's gate: the forge's pinning takes the task's recorded
     * container id, every other part its container name.
     */
    private static PullRequestPinning pinning(
            final PoreloContext context, final ForgeReview forge) {
        final PullRequestPins pins =
                new PullRequestPins(
                        forge,
                        entry -> MachineTaskRights.lend(context, entry),
                        PullRequestPins.gatesOn(context),
                        context.runner(),
                        new GitCertificates(context.paths().xdg())::environment,
                        context.limits().services(FETCH));
        return (ref, container) -> pins.pin(ref, recordedId(context, container));
    }

    private static String recordedId(final PoreloContext context, final String container)
            throws PlaceUnreadable {
        return summary(context, container)
                .map(ContainerSummary::id)
                .filter(Objects::nonNull)
                .orElseThrow(
                        () ->
                                new PlaceUnreadable(
                                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                                        "no task " + container + " on this machine"));
    }

    private static Optional<ContainerSummary> summary(
            final PoreloContext context, final String container) {
        return context.podman().taskCandidates().stream()
                .filter(each -> container.equals(each.name()))
                .findFirst();
    }

    /** An author's gate, upstream and branch as its task recorded them. */
    private static Authors authors(final PoreloContext context) {
        return container -> {
            final String id = recordedId(context, container);
            final PullRequestPins.TaskGate gate = PullRequestPins.gatesOn(context).locate(id);
            final String upstream;
            try {
                final var project = GateSupport.byName(context, gate.project());
                final String named = GateSupport.repository(project, gate.repository()).upstream();
                upstream = named != null ? named : project.upstream();
            } catch (RuntimeException ex) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                        "project " + gate.project() + " cannot be read");
            }
            final TaskProfile profile = TaskProfile.readFrom(gate.state());
            final String branch = profile == null ? null : profile.forgeBranchOrToday();
            if (upstream == null || branch == null) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                        "task " + container + " records no upstream or branch");
            }
            return new Author(gate, id, upstream, branch);
        };
    }

    /** What a task's own gate last forwarded to a branch of an upstream, by its container name. */
    private static RecordedTaskRights.Forwarded forwarded(final PoreloContext context) {
        return (container, upstream, branch) -> {
            try {
                final PullRequestPins.TaskGate gate =
                        PullRequestPins.gatesOn(context).locate(recordedId(context, container));
                final var project = GateSupport.byName(context, gate.project());
                return GateSupport.gate(
                                context,
                                project,
                                GateSupport.repository(project, gate.repository()),
                                upstream,
                                null)
                        .forwarded(gate.taskName(), branch);
            } catch (PlaceUnreadable | RuntimeException ex) {
                throw new IOException(
                        "the gate of " + container + " cannot be read: " + ex.getMessage(), ex);
            }
        };
    }
}
