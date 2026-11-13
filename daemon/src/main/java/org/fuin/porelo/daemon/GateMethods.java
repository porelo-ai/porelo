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
            final @org.jspecify.annotations.Nullable String against) {
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
        if (against != null && !against.isBlank()) {
            command.addAll(List.of("--against", against));
        }
        if (flag(parameters, "ignoreLineEndings")) {
            command.add("--ignore-line-endings");
        }
        command.addAll(List.of("--", name));
        return List.copyOf(command);
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
                    details.put("storeCommand", List.of("porelo", "host", "forge-token"));
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
    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control) {
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
                (parameters, replies) ->
                        replies.last(
                                pullRequestReply(
                                        () ->
                                                new org.fuin.porelo.app.HostPullRequestReader(
                                                                context)
                                                        .poll(
                                                                GateSupport.byName(
                                                                        context,
                                                                        text(
                                                                                parameters,
                                                                                "project")),
                                                                List.of()))));
        server.method(
                "WatchPullRequests",
                (parameters, replies) -> {
                    final var reader = new org.fuin.porelo.app.HostPullRequestReader(context);
                    watchPullRequests(
                            previous ->
                                    reader.poll(
                                            GateSupport.byName(
                                                    context, text(parameters, "project")),
                                            previous),
                            replies,
                            () -> sleep(java.time.Duration.ofSeconds(5)));
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

        server.method(
                "Review",
                (parameters, replies) -> {
                    final GitGate gate = gate(parameters, context);
                    final String name = text(parameters, "name");
                    final String against =
                            text(parameters, "against").isEmpty()
                                    ? org.fuin.porelo.app.TaskBase.reviewAgainst(
                                            context,
                                            GateSupport.project(projectFile(parameters, context)),
                                            name,
                                            gate)
                                    : text(parameters, "against");
                    final org.fuin.porelo.gate.ReviewRanking.Review review =
                            gate.rankedReview(name, against, flag(parameters, "ignoreLineEndings"));
                    // Matched by content against what was handed into the task that pushed.
                    final Map<String, org.fuin.porelo.app.DeliveredOrigins.Origin> origins =
                            org.fuin.porelo.app.DeliveredOrigins.of(
                                    org.fuin.porelo.app.HandIns.of(context)
                                            .deliveredForPush(
                                                    GateSupport.project(
                                                                    projectFile(
                                                                            parameters, context))
                                                            .name(),
                                                    name),
                                    gate.blobs(
                                            name,
                                            review.files().stream()
                                                    .filter(file -> !"D".equals(file.status()))
                                                    .map(file -> file.path())
                                                    .toList()));
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("diff", review.patch());
                    reply.put("log", gate.log(name, against));
                    reply.put(
                            "commits",
                            gate.made(name, against).stream()
                                    .map(
                                            made -> {
                                                final Map<String, Object> row =
                                                        new LinkedHashMap<>();
                                                row.put("commit", made.commit());
                                                row.put("subject", made.subject());
                                                row.put("author", made.author());
                                                row.put("committer", made.committer());
                                                row.put("trailers", made.trailers());
                                                row.put(
                                                        "message",
                                                        made.message() == null
                                                                ? ""
                                                                : made.message());
                                                return row;
                                            })
                                    .toList());
                    reply.put(
                            "files",
                            review.files().stream()
                                    .map(
                                            file -> {
                                                final Map<String, Object> row =
                                                        new LinkedHashMap<>();
                                                row.put("path", file.path());
                                                row.put("status", file.status());
                                                row.put("added", file.added());
                                                row.put("removed", file.removed());
                                                row.put(
                                                        "addedIgnoringLineEndings",
                                                        file.addedIgnoringLineEndings());
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
                                    .toList());
                    final ReviewText.Instruction asked =
                            ReviewText.instruction(
                                    context.paths(),
                                    GateSupport.project(projectFile(parameters, context)).name(),
                                    name);
                    if (asked.found()) {
                        reply.put("asked", asked.prompt() == null ? "" : asked.prompt());
                    }
                    final GitGate.Waiting head = gate.waitingWork(name);
                    final String arrival =
                            head == null
                                    ? null
                                    : org.fuin.porelo.app.ArrivalVerdicts.of(
                                            context,
                                            GateSupport.project(projectFile(parameters, context))
                                                    .name(),
                                            name,
                                            head.commit());
                    if (arrival != null) {
                        reply.put("arrival", arrival);
                    }
                    replies.last(
                            ReviewCut.fitted(
                                    reply,
                                    name,
                                    reviewCommand(parameters, context, name, against),
                                    org.fuin.porelo.wire.varlink.VarlinkConnection.MAX_MESSAGE));
                });

        server.method(
                "Approve",
                (parameters, replies) -> {
                    // The single call that sends anything anywhere, and it makes the caller name
                    // where.
                    final GitGate gate = gate(parameters, context);
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
                                            gate,
                                            text(parameters, "name"),
                                            branch,
                                            empty(parameters, "commit"));
                        } else {
                            pullRequest =
                                    gate.approve(
                                            text(parameters, "name"),
                                            branch,
                                            empty(parameters, "commit"));
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
                                    + (empty(parameters, "commit") == null
                                            ? ""
                                            : ", reviewed at " + empty(parameters, "commit"))
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
                                Map.of("message", String.valueOf(ex.getMessage())));
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
                                Map.of("message", String.valueOf(ex.getMessage())));
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
     * tests.
     */
    static void watchPullRequests(
            java.util.function.Function<
                            List<org.fuin.porelo.app.HostPullRequestReader.Pull>,
                            org.fuin.porelo.app.HostPullRequestReader.Round>
                    scan,
            VarlinkServer.Replies replies,
            Runnable pause)
            throws java.io.IOException {
        List<org.fuin.porelo.app.HostPullRequestReader.Pull> previous = List.of();
        Map<String, Object> last = null;
        while (replies.open()) {
            final var before = previous;
            final var round = pullRequestRound(() -> scan.apply(before));
            final Map<String, Object> answer = round.asMap();
            if (!replies.streaming()) {
                replies.last(answer);
                return;
            }
            if (!answer.equals(last)) {
                replies.more(answer);
                last = answer;
            }
            // Only open rows survive a round: each confirmed terminal row is sent once.
            previous = round.open();
            pause.run();
        }
    }

    /** Maps the reader's safe named outcomes without letting forge words become wire errors. */
    static Map<String, Object> pullRequestReply(
            java.util.function.Supplier<org.fuin.porelo.app.HostPullRequestReader.Round> read) {
        return pullRequestRound(read).asMap();
    }

    private static org.fuin.porelo.app.HostPullRequestReader.Round pullRequestRound(
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
                            List.of("porelo", "host", "forge-token")));
        } catch (org.fuin.porelo.app.HostPullRequestReader.Refused ex) {
            throw new VarlinkException(
                    INTERFACE + ".HostPullRequestsRefused",
                    Map.of("reason", ex.reason(), "message", String.valueOf(ex.getMessage())));
        }
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
