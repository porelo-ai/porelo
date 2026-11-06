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
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("diff", review.patch());
                    reply.put("log", gate.log(name, against));
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
                    replies.last(reply);
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
