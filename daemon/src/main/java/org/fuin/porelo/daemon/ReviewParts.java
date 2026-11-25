package org.fuin.porelo.daemon;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.app.AdapterForgeReview;
import org.fuin.porelo.app.CommentIntake;
import org.fuin.porelo.app.ConversationNote;
import org.fuin.porelo.app.ForgeAdapters;
import org.fuin.porelo.app.ForgeInstanceFile;
import org.fuin.porelo.app.GateEnds;
import org.fuin.porelo.app.GateSupport;
import org.fuin.porelo.app.HeldRuns;
import org.fuin.porelo.app.InboundCommentFilter;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PullRequestPins;
import org.fuin.porelo.app.PullRequestPlace;
import org.fuin.porelo.app.PullRequestPlaces;
import org.fuin.porelo.app.PullRequestRunStart;
import org.fuin.porelo.app.ReleasedSender;
import org.fuin.porelo.app.RunIntake;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.TaskReviewRole;
import org.fuin.porelo.core.net.GitCertificates;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.fuin.porelo.gate.review.PullRequestPinning;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.ReviewSarif;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * The review's parts for runs at a pull request, made once for the daemon's life: the run each task
 * is in, the held items, the task's role, the places, the start, the intake of a run's submission
 * and the sending of what the owner released. Every part is keyed by the task's container name.
 *
 * <p>Credentials are read from the vault per call and never kept.
 */
final class ReviewParts {

    /** How long a pull request's head may take to fetch, before this account's factor. */
    private static final Duration FETCH = Duration.ofMinutes(5);

    private final HeldRuns runs;
    private final PullRequestRunStart start;
    private final RunIntake intake;
    private final ReleasedSender sender;

    // One pass at a time: two passes over the same due items would post one item twice.
    private final java.util.concurrent.ExecutorService sending =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("porelod-review-send").factory());

    private ReviewParts(
            final HeldRuns runs,
            final PullRequestRunStart start,
            final RunIntake intake,
            final ReleasedSender sender) {
        this.runs = runs;
        this.start = start;
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
        final ReleasedSender.Credentials credentials = entry -> lend(context, entry);
        final TaskReviewRole role = role(context, forges);
        final PullRequestPlaces places =
                new PullRequestPlaces(
                        forges,
                        adapters::installed,
                        adapter -> AdapterForgeReview.of(context, adapter),
                        forge -> pinning(context, forge),
                        reading,
                        CommentIntake.Parts.of(context).check(),
                        writing,
                        credentials,
                        role::role,
                        AUTHOR_PINS);
        final PullRequestRunStart start =
                new PullRequestRunStart(
                        places,
                        holds,
                        runs,
                        writing,
                        new InboundCommentFilter(
                                context.runner(),
                                context.paths().messaging().messageFilter(),
                                context.limits(),
                                context.paths().xdg().state().resolve("comment-check")),
                        new ConversationNote(context),
                        credentials);
        return new ReviewParts(
                runs,
                start,
                new RunIntake(
                        reading,
                        CommentIntake.Parts.of(context).check(),
                        role,
                        holds,
                        runs,
                        places),
                new ReleasedSender(holds, places, role, credentials, Clock.systemUTC()));
    }

    /**
     * An author's pin at its own pull request refuses until the check that its gate forwarded the
     * head lands: {@code TaskReviewRole.mayPinAtPullRequest(task, upstream, branch, head)} plugs in
     * here, and the author is then pinned to the facts' head.
     */
    static final PullRequestPlace.AuthorPins AUTHOR_PINS =
            (task, facts) -> {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                        "an author's run at its own pull request is not pinned on this machine yet");
            };

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
            start.begin(container, place);
        } catch (RunPlaceCheck.Refused refused) {
            throw new VarlinkException(refused.refusal().error(), refused.refusal().fields());
        }
    }

    /**
     * Sends what the owner released, in one pass after another off the caller's thread, so a
     * decision's answer never waits on a forge; a failure is marked on its hold, and one the pass
     * could not mark is said in the daemon's journal.
     */
    void send() {
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

    private static ForgeCredential lend(final PoreloContext context, final String entry)
            throws PlaceUnreadable {
        final Optional<Map<String, VaultEntry>> held = context.readableCredentials();
        final VaultEntry found = held.map(entries -> entries.get(entry)).orElse(null);
        if (found == null || found.value().isBlank()) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                    held.isEmpty()
                            ? "the vault is shut, so '" + entry + "' cannot be lent"
                            : "the vault holds no '" + entry + "'");
        }
        return new ForgeCredential(entry, found.value());
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
                        entry -> lend(context, entry),
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

    /** The task's role and accounts as this machine records them. */
    private static TaskReviewRole role(
            final PoreloContext context, final ForgeInstanceFile forges) {
        final GateEnds ends = new GateEnds(context);
        return new TaskReviewRole(
                container -> context.paths().tasks().containerState(container),
                new TaskReviewRole.Entries() {
                    @Override
                    public boolean onHost() {
                        return Files.isRegularFile(context.paths().hostProgram());
                    }

                    @Override
                    public String taskAccount(final String task) {
                        return TaskReviewRole.taskForgeEntry(task);
                    }

                    @Override
                    public Optional<String> projectBuilds(final String task) {
                        return summary(context, task)
                                .map(ContainerSummary::project)
                                .filter(Objects::nonNull)
                                .flatMap(
                                        project -> {
                                            try {
                                                final var builds =
                                                        GateSupport.byName(context, project)
                                                                .builds();
                                                return builds == null
                                                        ? Optional.empty()
                                                        : Optional.of(builds.credential());
                                            } catch (RuntimeException ex) {
                                                return Optional.empty();
                                            }
                                        });
                    }

                    @Override
                    public boolean held(final String entry) {
                        return context.readableCredentials()
                                .map(entries -> entries.containsKey(entry))
                                .orElse(false);
                    }
                },
                forges,
                new org.fuin.porelo.app.ForgeAccounts(
                        (ref, credential) -> {
                            // The adapter of the pull request's forge entry says whose token it is.
                            final Optional<org.fuin.porelo.gate.review.ForgeInstance> found;
                            try {
                                found = forges.of(ref.url());
                            } catch (org.fuin.porelo.gate.review.ForgeFileUnreadable ex) {
                                throw new PlaceUnreadable(
                                        PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                                        "this machine's forge file cannot be read");
                            }
                            final String adapter =
                                    found.filter(each -> each.unreadable() == null)
                                            .map(org.fuin.porelo.gate.review.ForgeInstance::adapter)
                                            .orElse(null);
                            if (adapter == null) {
                                throw new PlaceUnreadable(
                                        PlaceUnreadable.Reason.UNKNOWN_FORGE,
                                        "this machine knows no readable forge at " + ref.forge());
                            }
                            return AdapterForgeReview.of(context, adapter).facts(ref, credential);
                        },
                        entry -> lend(context, entry)),
                task -> {
                    final Optional<String> id =
                            summary(context, task)
                                    .map(ContainerSummary::id)
                                    .filter(Objects::nonNull);
                    final Optional<PushEnd> end =
                            id.isEmpty() ? Optional.empty() : ends.store().read(id.get());
                    return end;
                });
    }
}
