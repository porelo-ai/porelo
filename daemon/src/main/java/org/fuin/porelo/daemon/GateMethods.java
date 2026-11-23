package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.GateSupport;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.ReviewText;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for the gate: what is waiting, its review and the decision.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 *
 * <p>The one crossing where work leaves the machine. These are thin over GitGate, which is what the
 * CLI drives too, so an approval means the same thing from either.
 */
final class GateMethods {

    private GateMethods() {}

    @FunctionalInterface
    interface GatheredPush {
        org.fuin.porelo.app.HostGatherPush.Result run(
                org.fuin.porelo.app.HostGatherPush.Progress progress,
                java.util.function.BooleanSupplier open)
                throws java.io.IOException;
    }

    /**
     * The command that shows on this machine the whole review a {@code Review} call asked for: the
     * same project, repository, upstream, base and line-ending switch, every name resolved.
     */
    static List<String> reviewCommand(
            final Map<String, Object> parameters,
            final PoreloContext context,
            final String name,
            final @org.jspecify.annotations.Nullable String against,
            final String commit) {
        final org.fuin.porelo.core.project.Project project =
                GateSupport.project(projectFile(parameters, context));
        final List<String> command =
                new java.util.ArrayList<>(
                        List.of(
                                "porelo",
                                "gate",
                                "review",
                                "-p",
                                project.name(),
                                "-r",
                                GateSupport.repository(project, text(parameters, "repository"))
                                        .name()));
        if (!text(parameters, "upstream").isEmpty()) {
            command.addAll(List.of("--upstream", text(parameters, "upstream")));
        }
        // An explicit empty base keeps the CLI from selecting a newer default.
        command.addAll(List.of("--against", against == null ? "" : against));
        if (flag(parameters, "ignoreLineEndings")) {
            command.add("--ignore-line-endings");
        }
        command.addAll(List.of("--commit", commit));
        command.addAll(List.of("--", name));
        return List.copyOf(command);
    }

    /**
     * What a review is read from: the gate, the push's name, its waiting commit and the base, each
     * resolved once; ReviewPage takes the base Review resolved, so both read the same review.
     *
     * @param gate The repository's gate.
     * @param name The pending push's name.
     * @param head The commit waiting under it.
     * @param against The base, or {@code null} for the whole history.
     */
    record Reading(
            GitGate gate,
            String name,
            GitGate.Waiting head,
            @org.jspecify.annotations.Nullable String against) {

        /**
         * Returns the ranked files and the patch.
         *
         * @param parameters The call's parameters, for the line-ending switch.
         * @return The review.
         */
        org.fuin.porelo.gate.ReviewRanking.Review ranked(final Map<String, Object> parameters) {
            return gate.rankedReview(head, against, flag(parameters, "ignoreLineEndings"));
        }

        /**
         * Returns what names this review among the reviews kept for their pages.
         *
         * @param parameters The call's parameters, for the line-ending switch.
         * @return The key.
         */
        KeptReviews.Key key(final Map<String, Object> parameters) {
            return new KeptReviews.Key(
                    gate.mirror().toString(),
                    name,
                    head.commit(),
                    against == null ? "" : against,
                    flag(parameters, "ignoreLineEndings"));
        }
    }

    /** A full commit id, as ReviewPage takes its base. */
    private static final java.util.regex.Pattern FULL_COMMIT =
            java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** Resolves what a Review call reads, its base the default one unless the call names one. */
    private static Reading reading(
            final Map<String, Object> parameters, final PoreloContext context) {
        final GitGate gate = gate(parameters, context);
        final String name = text(parameters, "name");
        // A mistyped name is answered with what does wait.
        final GitGate.Waiting head = gate.pendingWork(name);
        // A base the client named that names nothing is refused, never taken as none.
        final String against =
                text(parameters, "against").isBlank()
                        ? gate.reviewBase(
                                org.fuin.porelo.app.TaskBase.reviewAgainst(
                                        context,
                                        GateSupport.project(projectFile(parameters, context)),
                                        name,
                                        gate))
                        : gate.namedBase(text(parameters, "against"));
        return new Reading(gate, name, head, against);
    }

    /** The commits of a review, newest first, as Review.commits holds them. */
    private static List<Map<String, Object>> commits(final Reading reading) {
        return reading.gate().made(reading.head(), reading.against()).stream()
                .map(
                        made -> {
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("commit", made.commit());
                            row.put("subject", made.subject());
                            row.put("author", made.author());
                            row.put("committer", made.committer());
                            row.put("trailers", made.trailers());
                            row.put("message", made.message() == null ? "" : made.message());
                            return row;
                        })
                .toList();
    }

    /** The files of a review, ranked, as Review.files holds them. */
    private static List<Map<String, Object>> files(
            final Map<String, Object> parameters,
            final PoreloContext context,
            final Reading reading,
            final org.fuin.porelo.gate.ReviewRanking.Review review) {
        // Matched by content against what was handed into the task that pushed.
        final Map<String, org.fuin.porelo.app.DeliveredOrigins.Origin> origins =
                org.fuin.porelo.app.DeliveredOrigins.of(
                        org.fuin.porelo.app.HandIns.of(context)
                                .deliveredForPush(
                                        GateSupport.project(projectFile(parameters, context))
                                                .name(),
                                        reading.name()),
                        reading.gate()
                                .blobs(
                                        reading.head(),
                                        review.files().stream()
                                                .filter(file -> !"D".equals(file.status()))
                                                .map(file -> file.path())
                                                .toList()));
        return review.files().stream()
                .map(
                        file -> {
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("path", file.path());
                            row.put("status", file.status());
                            row.put("added", file.added());
                            row.put("removed", file.removed());
                            row.put("addedIgnoringLineEndings", file.addedIgnoringLineEndings());
                            row.put(
                                    "removedIgnoringLineEndings",
                                    file.removedIgnoringLineEndings());
                            row.put("rank", file.rank().name());
                            row.put("reason", file.reason());
                            final var origin = origins.get(file.path());
                            if (origin != null) {
                                row.put("origin", origin.asMap());
                            }
                            return row;
                        })
                .toList();
    }

    static void pushToCentral(GatheredPush push, VarlinkServer.Replies replies)
            throws java.io.IOException {
        try {
            final var result =
                    push.run(
                            (repository, stage) -> {
                                if (replies.streaming())
                                    replies.more(Map.of("repository", repository, "stage", stage));
                            },
                            () -> !replies.streaming() || replies.open());
            replies.last(Map.of("pushed", result.pushed(), "upToDate", result.upToDate()));
        } catch (org.fuin.porelo.app.HostGatherRefusal ex) {
            final var details = new LinkedHashMap<String, Object>();
            details.put("repository", "");
            details.put("pushed", List.of());
            details.put("upToDate", List.of());
            details.put("reason", "transfer");
            details.put("branch", "");
            details.put("storeCommand", List.of());
            details.putAll(ex.details());
            throw switch (ex.kind()) {
                case VAULT_SHUT -> {
                    final Object command = details.get("unlockCommand");
                    final String route =
                            List.of("porelo", "vault", "init").equals(command)
                                    ? "init"
                                    : List.of("porelo", "host", "unlock", "<host>").equals(command)
                                            ? "host"
                                            : List.of().equals(command) ? "device" : "passphrase";
                    details.put("unlock", route);
                    yield new VarlinkException(
                            INTERFACE + ".CentralVaultShut",
                            fields(details, "unlock", "unlockCommand"));
                }
                case MISSING_CENTRAL_TOKEN ->
                        new VarlinkException(
                                INTERFACE + ".MissingCentralToken",
                                fields(details, "credential", "storeCommand"));
                case CENTRAL_TOKEN_REFUSED -> {
                    final var parameters =
                            fields(
                                    details,
                                    "credential",
                                    "storeCommand",
                                    "repository",
                                    "pushed",
                                    "upToDate");
                    for (String name : List.of("forge", "status"))
                        if (details.containsKey(name)) parameters.put(name, details.get(name));
                    yield new VarlinkException(INTERFACE + ".CentralTokenRefused", parameters);
                }
                case NOTHING_TO_PUSH ->
                        new VarlinkException(
                                INTERFACE + ".NothingToPush",
                                fields(details, "pushed", "upToDate"));
                case HOST_TOKEN -> {
                    details.putIfAbsent("reason", "issuer");
                    if (!"busy".equals(details.get("reason"))) details.put("reason", "issuer");
                    details.put(
                            "storeCommand", List.of("porelo", "host", "forge-token", "--if-forge"));
                    yield new VarlinkException(
                            INTERFACE + ".HostPushTokenUnavailable",
                            fields(details, "reason", "storeCommand"));
                }
                case PUSH ->
                        new VarlinkException(
                                INTERFACE + ".CentralPushRefused",
                                fields(
                                        details,
                                        "repository",
                                        "reason",
                                        "branch",
                                        "pushed",
                                        "upToDate"));
                case CANCELLED ->
                        new VarlinkException(
                                INTERFACE + ".CentralPushUncertain",
                                fields(details, "repository", "pushed", "upToDate"));
                case HOST -> {
                    details.put("code", ex.code());
                    yield new VarlinkException(
                            INTERFACE + ".HostPushRefused",
                            fields(details, "reason", "code", "repository", "pushed", "upToDate"));
                }
            };
        } catch (RuntimeException ex) {
            // VarlinkServer otherwise returns exception messages, which may hold child-process
            // secrets.
            throw new VarlinkException(
                    INTERFACE + ".HostPushRefused",
                    Map.of(
                            "reason",
                            "unexpected",
                            "code",
                            70,
                            "repository",
                            "",
                            "pushed",
                            List.of(),
                            "upToDate",
                            List.of()));
        }
    }

    private static List<String> repositories(Map<String, Object> parameters) {
        final Object value = parameters.get("repositories");
        if (value == null) return List.of();
        if (!(value instanceof List<?> names)
                || names.stream().anyMatch(name -> !(name instanceof String)))
            throw org.fuin.porelo.app.HostGatherRefusal.host(
                    "selection", 64, "repositories must be strings");
        return names.stream().map(String.class::cast).toList();
    }

    private static Map<String, Object> fields(Map<String, Object> details, String... names) {
        final Map<String, Object> selected = new LinkedHashMap<>();
        for (String name : names) selected.put(name, details.getOrDefault(name, ""));
        return selected;
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    /** How long WatchPullRequests waits before it reads the forge again. */
    static final java.time.Duration PULL_REQUEST_POLL = java.time.Duration.ofSeconds(5);

    /**
     * Returns how long WatchPullRequests waits before it reads the forge again.
     *
     * @param limits The factors.
     * @return {@link #PULL_REQUEST_POLL} under {@code polling}.
     */
    static java.time.Duration pullRequestPoll(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.polling(PULL_REQUEST_POLL);
    }

    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control) {
        // Readings too large for one reply, kept for their pages: this daemon's alone.
        final PullRequestPages pullRequestPages = new PullRequestPages(java.time.Instant::now);
        // Reviews just read, kept for their pages: this daemon's alone.
        final KeptReviews keptReviews = new KeptReviews(java.time.Instant::now);
        server.method(
                "PushToCentral",
                (parameters, replies) ->
                        pushToCentral(
                                (progress, open) ->
                                        new org.fuin.porelo.app.HostGatherPush(context)
                                                .push(
                                                        text(parameters, "project"),
                                                        repositories(parameters),
                                                        progress,
                                                        open),
                                replies));
        server.method(
                "Arrivals",
                (parameters, replies) ->
                        replies.last(
                                org.fuin.porelo.app.HostArrivalLedger.read(
                                        text(parameters, "project"),
                                        text(parameters, "repository"))));
        server.method(
                "PullRequests",
                (parameters, replies) -> {
                    final String project = text(parameters, "project");
                    final var reader = new org.fuin.porelo.app.HostPullRequestReader(context);
                    replies.last(
                            pullRequestReply(
                                    takingWhereCarried(
                                            () -> reader.takesFirst(project),
                                            () ->
                                                    org.fuin.porelo.app.ProjectTaking.takeToAsk(
                                                            context, project)),
                                    () -> reader.poll(project, List.of()),
                                    pullRequestPages,
                                    project));
                });
        server.method(
                "PullRequestsPage",
                (parameters, replies) ->
                        replies.last(
                                pullRequestPages.page(
                                        text(parameters, "project"),
                                        text(parameters, "reading"),
                                        org.fuin.porelo.wire.varlink.VarlinkNumbers.longParameter(
                                                parameters, "from"))));
        server.method(
                "WatchPullRequests",
                (parameters, replies) -> {
                    final String project = text(parameters, "project");
                    final var reader = new org.fuin.porelo.app.HostPullRequestReader(context);
                    watchPullRequests(
                            takingWhereCarried(
                                    () -> reader.takesFirst(project),
                                    () ->
                                            org.fuin.porelo.app.ProjectTaking.takeToAsk(
                                                    context, project)),
                            previous -> reader.poll(project, previous),
                            pullRequestPages,
                            project,
                            replies,
                            () -> sleep(pullRequestPoll(context.limits())));
                });
        server.method(
                "CredentialTryPush",
                (parameters, replies) -> {
                    final String source = text(parameters, "source");
                    if (!source.equals("AGENT") && !source.equals("FILE")) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", "source must be AGENT or FILE"));
                    }
                    final var project = GateSupport.byName(context, text(parameters, "project"));
                    final var repository =
                            GateSupport.repository(project, text(parameters, "repository"));
                    final String upstream = repository.upstream();
                    if (upstream == null) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", "This repository has no upstream."));
                    }
                    final var gate = GateSupport.gate(context, project, repository, null, null);
                    replies.last(
                            org.fuin.porelo.app.PushCredentialHelp.tryPush(
                                    context,
                                    gate.mirror(),
                                    upstream,
                                    text(parameters, "refspec"),
                                    "AGENT".equals(text(parameters, "source")),
                                    text(parameters, "id")));
                });

        server.method(
                "Pending",
                (parameters, replies) -> {
                    final GitGate gate = opened(parameters, context);
                    if (!GateSupport.holds(gate)) {
                        // No mirror, so nothing waits; the mode and path are what a start makes.
                        replies.last(
                                Map.of(
                                        "mirror",
                                        gate.mirror().toString(),
                                        "mode",
                                        gate.mode().name().toLowerCase(),
                                        "seededFrom",
                                        "",
                                        "pending",
                                        List.of()));
                        return;
                    }
                    final java.time.Instant now = java.time.Instant.now();
                    final java.util.Set<String> upstreamHas =
                            PoreloDaemon.alreadyUpstream(parameters, context);
                    final List<Map<String, Object>> waiting =
                            gate.pendingDetail().stream()
                                    .map(
                                            push -> {
                                                final Map<String, Object> row =
                                                        new LinkedHashMap<>();
                                                row.put("name", push.name());
                                                row.put("commit", push.commit());
                                                row.put("subject", push.subject());
                                                row.put("waiting", push.lagText(now));
                                                row.put("at", push.at().toString());
                                                // Read in a person's own clone, over their ssh: the
                                                // line to paste. Nothing runs the work.
                                                row.put(
                                                        "fetch",
                                                        org.fuin.porelo.app.WaitingFetch.of(
                                                                gate.mirror(), push.name()));
                                                row.put(
                                                        "alreadyUpstream",
                                                        upstreamHas.contains(push.name()));
                                                // What the gate decided when it arrived, so an
                                                // unsigned push is seen as one before approval.
                                                final String arrival =
                                                        org.fuin.porelo.app.ArrivalVerdicts.of(
                                                                context,
                                                                text(parameters, "project"),
                                                                push.name(),
                                                                push.commit());
                                                if (arrival != null) {
                                                    row.put("arrival", arrival);
                                                }
                                                // Who made its head, for the reviewer to judge;
                                                // only of the commit this row names, as a push
                                                // in between moves the ref.
                                                final org.fuin.porelo.gate.GitGate.Made head =
                                                        gate.madeHead(push.name());
                                                if (head != null
                                                        && head.commit().equals(push.commit())) {
                                                    row.put("author", head.author());
                                                    row.put("committer", head.committer());
                                                    row.put("trailers", head.trailers());
                                                    if (head.message() != null) {
                                                        row.put("message", head.message());
                                                    }
                                                }
                                                return row;
                                            })
                                    .toList();
                    replies.last(
                            Map.of(
                                    "mirror",
                                    gate.mirror().toString(),
                                    "mode",
                                    gate.mode().name().toLowerCase(),
                                    "seededFrom",
                                    gate.seededFrom() == null ? "" : gate.seededFrom(),
                                    "pending",
                                    waiting));
                });

        // The rest of a review too large for one message, page by page, read as Review read it.
        // Both end with their caller: git's reads for a review nobody waits for any more stop,
        // and a reading cut short is never kept.
        server.methodEndingWithItsCaller(
                "ReviewPage",
                (parameters, replies) -> {
                    final String part = text(parameters, "part");
                    if (!ReviewPages.PARTS.contains(part)) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "part"));
                    }
                    final Integer from =
                            org.fuin.porelo.wire.varlink.VarlinkNumbers.intParameter(
                                    parameters, "from");
                    if (from == null) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "from"));
                    }
                    // Required, never a default resolved again: the pages are of the base and
                    // the switch Review read with, whatever has moved since.
                    if (!(parameters.get("against") instanceof String base)
                            || !(base.isEmpty() || FULL_COMMIT.matcher(base).matches())) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "against"));
                    }
                    if (!(parameters.get("ignoreLineEndings") instanceof Boolean)) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "ignoreLineEndings"));
                    }
                    final GitGate gate = gate(parameters, context);
                    final String name = text(parameters, "name");
                    final Reading reading =
                            new Reading(
                                    gate,
                                    name,
                                    gate.pendingWork(name),
                                    base.isEmpty() ? null : gate.namedBase(base));
                    final String commit = text(parameters, "commit").strip();
                    if (!reading.head().commit().equals(commit)) {
                        // Every page is of the commit Review answered, as Approve forwards only it.
                        throw new VarlinkException(
                                INTERFACE + ".MovedSinceReview",
                                Map.of(
                                        "name",
                                        reading.name(),
                                        "reviewed",
                                        commit,
                                        "now",
                                        reading.head().commit()));
                    }
                    final Object whole =
                            switch (part) {
                                case "files" ->
                                        files(
                                                parameters,
                                                context,
                                                reading,
                                                keptReviews.review(
                                                        reading.key(parameters),
                                                        () -> reading.ranked(parameters)));
                                case "commits" -> commits(reading);
                                case "log" -> reading.gate().log(reading.head(), reading.against());
                                default ->
                                        keptReviews
                                                .review(
                                                        reading.key(parameters),
                                                        () -> reading.ranked(parameters))
                                                .patch();
                            };
                    replies.last(ReviewPages.page(part, whole, from));
                });

        server.methodEndingWithItsCaller(
                "Review",
                (parameters, replies) -> {
                    final Reading reading = reading(parameters, context);
                    final GitGate gate = reading.gate();
                    final String name = reading.name();
                    final GitGate.Waiting head = reading.head();
                    final String against = reading.against();
                    final org.fuin.porelo.gate.ReviewRanking.Review review =
                            reading.ranked(parameters);
                    keptReviews.keep(reading.key(parameters), review);
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("commit", head.commit());
                    reply.put("diff", review.patch());
                    reply.put("log", gate.log(head, against));
                    reply.put("commits", commits(reading));
                    reply.put("files", files(parameters, context, reading, review));
                    final ReviewText.Instruction asked =
                            ReviewText.instruction(
                                    context.paths(),
                                    GateSupport.project(projectFile(parameters, context)).name(),
                                    name);
                    if (asked.found()) {
                        reply.put("asked", asked.prompt() == null ? "" : asked.prompt());
                    }
                    final String arrival =
                            org.fuin.porelo.app.ArrivalVerdicts.of(
                                    context,
                                    GateSupport.project(projectFile(parameters, context)).name(),
                                    name,
                                    head.commit());
                    if (arrival != null) {
                        reply.put("arrival", arrival);
                    }
                    replies.last(
                            ReviewCut.fitted(
                                    reply,
                                    name,
                                    against == null ? "" : against,
                                    reviewCommand(
                                            parameters, context, name, against, head.commit()),
                                    org.fuin.porelo.wire.varlink.VarlinkConnection.MAX_MESSAGE));
                });

        server.method(
                "Approve",
                (parameters, replies) -> {
                    // The single call that sends anything anywhere, and it makes the caller name
                    // where.
                    final GitGate gate = gate(parameters, context);
                    final String name = text(parameters, "name");
                    final String commit = text(parameters, "commit").strip();
                    if (commit.isEmpty()) {
                        // Refused first as the approval would be - a mistyped name, an offline
                        // project, no upstream - so the commit offered is one that can go.
                        final GitGate.Waiting waiting = gate.approvable(name);
                        throw new VarlinkException(
                                INTERFACE + ".CommitNotNamed",
                                Map.of("name", name, "waiting", waiting.commit()));
                    }
                    final String branch = approvalBranch(parameters, context);
                    if (branch == null) {
                        throw new VarlinkException(
                                INTERFACE + ".BranchRequired",
                                Map.of("name", text(parameters, "name")));
                    }
                    final String pullRequest;
                    try {
                        if (flag(parameters, "signed")) {
                            pullRequest =
                                    org.fuin.porelo.app.GateSupport.approveSigned(
                                            gate, text(parameters, "name"), branch, commit);
                        } else {
                            pullRequest = gate.approve(text(parameters, "name"), branch, commit);
                        }
                    } catch (org.fuin.porelo.app.PushRefused ex) {
                        throw new VarlinkException(INTERFACE + ".PushRefused", ex.details());
                    } catch (org.fuin.porelo.gate.GateException.MovedSinceReview ex) {
                        // Typed, so an interface says it in its own words and offers the review
                        // again.
                        throw new VarlinkException(
                                INTERFACE + ".MovedSinceReview",
                                Map.of(
                                        "name",
                                        text(parameters, "name"),
                                        "reviewed",
                                        ex.reviewed(),
                                        "now",
                                        ex.now()));
                    } catch (org.fuin.porelo.gate.GateException.BranchExists ex) {
                        // Typed, so an interface offers another branch in the same dialog instead
                        // of showing git's text.
                        throw new VarlinkException(
                                INTERFACE + ".BranchExists",
                                Map.of("branch", ex.branch(), "at", ex.at()));
                    }
                    org.fuin.porelo.app.EventLog.record(
                            context,
                            org.fuin.porelo.app.EventLog.APPROVED,
                            text(parameters, "project"),
                            text(parameters, "name"),
                            (flag(parameters, "signed")
                                            ? "signed and forwarded onto "
                                            : "forwarded onto ")
                                    + branch
                                    + ", reviewed at "
                                    + commit
                                    + ", through the daemon");
                    final Map<String, Object> reply = new java.util.LinkedHashMap<>();
                    reply.put("forwarded", text(parameters, "name"));
                    reply.put("branch", branch);
                    if (pullRequest != null) reply.put("pullRequest", pullRequest);
                    replies.last(reply);
                });

        // For work merged elsewhere: the interface takes it as a bundle, a person merges and signs
        // it on their own computer and pushes it, and Landed clears it once the upstream holds it
        // (decided, 2026-09-30). Nothing is signed or pushed here.
        server.method(
                "PendingBundle",
                (parameters, replies) -> {
                    final GitGate gate = gate(parameters, context);
                    final String chosen = approvalBranch(parameters, context);
                    final String branch = chosen == null ? "main" : chosen;
                    final java.nio.file.Path scratch;
                    try {
                        // In a directory of its own, owner-only: a file made and then deleted in
                        // the shared /tmp freed its name, and anyone could take it - or its '.lock'
                        // - before git wrote there.
                        scratch =
                                java.nio.file.Files.createTempDirectory("porelo-pending-")
                                        .resolve("pending.bundle");
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of(
                                        "message",
                                        FileFailure.said(
                                                "no scratch directory for the pending work's bundle",
                                                ex)));
                    }
                    try {
                        final long size = gate.bundle(text(parameters, "name"), branch, scratch);
                        if (size > GitGate.BUNDLE_LIMIT) {
                            throw new VarlinkException(
                                    INTERFACE + ".BundleTooLarge",
                                    Map.of("bytes", size, "limit", GitGate.BUNDLE_LIMIT));
                        }
                        replies.last(
                                Map.of(
                                        "bundle",
                                        java.util.Base64.getEncoder()
                                                .encodeToString(
                                                        java.nio.file.Files.readAllBytes(scratch)),
                                        "bytes",
                                        size));
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of(
                                        "message",
                                        FileFailure.said(
                                                "the pending work of "
                                                        + text(parameters, "name")
                                                        + " cannot be bundled",
                                                ex)));
                    } finally {
                        try {
                            java.nio.file.Files.deleteIfExists(scratch);
                            java.nio.file.Files.deleteIfExists(scratch.getParent());
                        } catch (java.io.IOException ex) {
                            // A temporary file left behind harms nothing.
                        }
                    }
                });

        server.method(
                "Landed",
                (parameters, replies) -> {
                    final GitGate gate = gate(parameters, context);
                    final String branch = text(parameters, "branch");
                    if (branch.isEmpty()) {
                        throw new VarlinkException(
                                INTERFACE + ".BranchRequired",
                                Map.of("name", text(parameters, "name")));
                    }
                    final GitGate.Landing landing = gate.landed(text(parameters, "name"), branch);
                    replies.last(
                            Map.of(
                                    "landed",
                                    landing.landed(),
                                    "commit",
                                    landing.commit(),
                                    "detail",
                                    landing.detail()));
                });

        server.method(
                "Reject",
                (parameters, replies) -> {
                    final GitGate gate = gate(parameters, context);
                    final String name = text(parameters, "name");
                    if (PoreloDaemon.alreadyUpstream(parameters, context).contains(name)
                            && !flag(parameters, "force")) {
                        // Discarding work somebody pushed upstream by hand records the opposite of
                        // what happened.
                        throw new VarlinkException(
                                INTERFACE + ".AlreadyUpstream", Map.of("name", name));
                    }
                    gate.reject(name, false, true);
                    org.fuin.porelo.app.EventLog.record(
                            context,
                            org.fuin.porelo.app.EventLog.REJECTED,
                            text(parameters, "project"),
                            name,
                            "discarded, through the daemon");
                    // The task the work came from is told, reason or not: an agent whose work
                    // silently vanished hands it over again.
                    boolean told;
                    try {
                        told =
                                org.fuin.porelo.app.PersonNote.rejectedWork(
                                        context,
                                        org.fuin.porelo.app.GateSupport.byName(
                                                context, text(parameters, "project")),
                                        text(parameters, "name"),
                                        empty(parameters, "reason"));
                    } catch (java.io.IOException ex) {
                        told = false;
                    }
                    replies.last(Map.of("rejected", text(parameters, "name"), "told", told));
                });
    }

    /**
     * Streams observations on the caller's connection, with an injectable wait for deterministic
     * tests. A snapshot that does not fit one message is paged as a reading of its own.
     */
    static void watchPullRequests(
            ProjectQuestion take,
            java.util.function.Function<
                            List<org.fuin.porelo.app.HostPullRequestReader.Pull>,
                            org.fuin.porelo.app.HostPullRequestReader.Round>
                    scan,
            PullRequestPages pages,
            String project,
            VarlinkServer.Replies replies,
            Runnable pause)
            throws java.io.IOException {
        takePullRequestProject(take);
        List<org.fuin.porelo.app.HostPullRequestReader.Pull> previous = List.of();
        PullRequestPages.Snapshot last = null;
        while (replies.open()) {
            final var before = previous;
            final var round = pullRequestRound(() -> scan.apply(before));
            final var snapshot = PullRequestPages.Snapshot.of(round);
            if (!replies.streaming()) {
                replies.last(pages.first(project, snapshot));
                return;
            }
            // Compared by its rows: a paged reply names a new reading each time it is made.
            if (!snapshot.equals(last)) {
                replies.more(pages.first(project, snapshot));
                last = snapshot;
            }
            // Open and kept rows survive a round: each confirmed terminal row is sent once, and a
            // refused repository's rows are confirmed once it answers again.
            previous =
                    java.util.stream.Stream.concat(round.open().stream(), round.kept().stream())
                            .toList();
            pause.run();
        }
    }

    @FunctionalInterface
    interface ProjectQuestion {
        void take() throws org.fuin.porelo.app.ProjectTaking.Refused;
    }

    /**
     * Takes a project for a pull-request read only where the host carries it for this person and no
     * start placed it yet: any other name goes to the read's own refusal, untaken, so a read never
     * fetches what is not this person's, nor says a fetch's words.
     *
     * @param carried Whether to take; it refuses as the read would where the host withholds it.
     * @param take The take.
     * @return The question a read asks first.
     */
    static ProjectQuestion takingWhereCarried(
            java.util.function.BooleanSupplier carried, ProjectQuestion take) {
        return () -> {
            final boolean takes;
            try {
                takes = carried.getAsBoolean();
            } catch (org.fuin.porelo.app.HostPullRequestReader.Refused ex) {
                throw new VarlinkException(
                        INTERFACE + ".HostPullRequestsRefused",
                        Map.of("reason", ex.reason(), "message", String.valueOf(ex.getMessage())));
            }
            if (takes) {
                take.take();
            }
        };
    }

    private static void takePullRequestProject(ProjectQuestion take) {
        try {
            take.take();
        } catch (org.fuin.porelo.app.ProjectTaking.Refused ex) {
            throw new VarlinkException(
                    INTERFACE + ".HostPullRequestsRefused",
                    Map.of("reason", ex.reason(), "message", String.valueOf(ex.getMessage())));
        }
    }

    static Map<String, Object> pullRequestReply(
            ProjectQuestion take,
            java.util.function.Supplier<org.fuin.porelo.app.HostPullRequestReader.Round> read,
            PullRequestPages pages,
            String project) {
        takePullRequestProject(take);
        return pages.first(project, pullRequestRound(read));
    }

    /** Maps the reader's safe named outcomes without letting forge words become wire errors. */
    static org.fuin.porelo.app.HostPullRequestReader.Round pullRequestRound(
            java.util.function.Supplier<org.fuin.porelo.app.HostPullRequestReader.Round> read) {
        try {
            return read.get();
        } catch (org.fuin.porelo.app.HostPullRequestReader.MissingToken ex) {
            throw new VarlinkException(
                    INTERFACE + ".MissingHostForgeToken",
                    Map.of(
                            "credential",
                            ex.credential(),
                            "storeCommand",
                            List.of("porelo", "host", "forge-token", "--if-forge")));
        } catch (org.fuin.porelo.app.HostPullRequestReader.Refused ex) {
            throw refused(ex);
        }
    }

    private static VarlinkException refused(org.fuin.porelo.app.HostPullRequestReader.Refused ex) {
        return new VarlinkException(
                INTERFACE + ".HostPullRequestsRefused",
                Map.of("reason", ex.reason(), "message", String.valueOf(ex.getMessage())));
    }

    /**
     * Returns the branch a call approves onto: the one named, or in a project with a branch block
     * the task's own.
     *
     * @param parameters {@code project}, {@code repository}, {@code name} and {@code branch}.
     * @param context The machine.
     * @return The branch, or {@code null} when the project names no scheme and none was named.
     * @throws VarlinkException {@code BranchRefused} for a named branch the pattern does not
     *     accept; {@code BranchRequired} when the task's own is not recorded.
     */
    static @org.jspecify.annotations.Nullable String approvalBranch(
            final Map<String, Object> parameters, final PoreloContext context) {
        final org.fuin.porelo.core.project.Project project =
                GateSupport.project(projectFile(parameters, context));
        try {
            return org.fuin.porelo.app.ApprovalBranch.of(
                    context,
                    project,
                    // As PoreloDaemon.gate opens the gate, so both judge one repository.
                    GateSupport.repository(project, text(parameters, "repository")),
                    text(parameters, "name"),
                    empty(parameters, "branch"));
        } catch (org.fuin.porelo.app.ApprovalBranch.Refused ex) {
            throw new VarlinkException(
                    INTERFACE + ".BranchRefused",
                    Map.of("branch", ex.branch(), "pattern", ex.pattern()));
        } catch (org.fuin.porelo.core.project.ProjectException ex) {
            throw new VarlinkException(
                    INTERFACE + ".BranchRequired", Map.of("name", text(parameters, "name")));
        }
    }
}
