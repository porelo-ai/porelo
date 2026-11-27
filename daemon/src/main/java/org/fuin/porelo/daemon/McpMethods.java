package org.fuin.porelo.daemon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * The methods an MCP client is never offered as tools, as {@code porelo-mcp} asks for them, beside
 * each method's {@code x-mcp} marker in the interface description.
 *
 * <p>{@code never} is Porelo's, named one by one and never guessed from a method's name; no method
 * it names is marked {@code x-mcp: true}. Every name is a method's name without the interface, as a
 * tool is named.
 */
final class McpMethods {

    /**
     * Methods never offered, whatever their marker says: each stops every task, removes what cannot
     * be had back, hands out a secret ({@code ReadHostVaultShare}, {@code HostIdentityRead} and the
     * other host identity methods, and {@code ReadHeld} with a refused message's text), changes
     * where a credential is sent ({@code WriteDestination}, {@code CredentialDeclare}), or signs an
     * agent in on the host's own network ({@code AgentSignInStart}, and {@code AgentSignInWait},
     * which holds a call while one runs). {@code PullRequests}, {@code PullRequestsPage} and {@code
     * WatchPullRequests} spend or hand out what the person's forge identity read of reviews
     * awaiting human judgment, so no task asks them. PushToCentral releases approved work with the
     * person's central credential and is never offered. {@code Comment}, {@code Reply}, {@code
     * Comments}, {@code ResolveThread} and {@code ReviewSummary} write, read and count a review as
     * the owner account, so a task never reaches them.
     */
    static final Set<String> NEVER =
            Set.of(
                    // Every task's command lines, a project's whole: the person's planner reads
                    // them.
                    "Records",
                    "RecordCalls",
                    "PushToCentral",
                    "PullRequests",
                    "PullRequestsPage",
                    "WatchPullRequests",
                    // A run begins for a task its start placed, never at a task's own asking.
                    "BeginRun",
                    // A host-credentialed query returns unfiltered server names for the operator.
                    "RoomMembers",
                    "RoomMembersPage",
                    // What any task handed back, another project's too, for the person to read.
                    "Result",
                    "Panic",
                    "ReadHeld",
                    "WriteDestination",
                    "CredentialDeclare",
                    "Clear",
                    "VaultClear",
                    "Prune",
                    "RemoveProject",
                    "Remove",
                    "DeleteBackup",
                    "RestoreBackup",
                    "CredentialRemove",
                    "CredentialReplace",
                    "RevokeKeyslot",
                    "ReadHostVaultShare",
                    // A vault entry carried to a host: sealed for the host's daemon alone, and
                    // stored there, but still the person's to send, never a task's.
                    "CredentialReceiver",
                    "SealCredential",
                    "ReceiveCredential",
                    "ImportableCredentials",
                    // A host's keys: the person's to keep, read and replace, never a task's.
                    "HostIdentityKeep",
                    "HostIdentityRead",
                    "HostIdentityPublic",
                    "HostIdentities",
                    "AgentSignInStart",
                    "AgentSignInWait",
                    // The window's offer to take a repository out of 'default', on the person's
                    // word.
                    "NamedDefaultRepositories",
                    "LeaveDefault",
                    // A review's comments, written, read, resolved and counted as the owner
                    // account: a task answers a thread only through its mailbox.
                    "Comment",
                    "Reply",
                    "Comments",
                    "ResolveThread",
                    "ReviewSummary",
                    // What is open at a place, read for the person's planner.
                    "ReviewOpen",
                    // Held review items are the owner account's to decide, and how a forge's
                    // comments reach a run is its choice.
                    "HeldItems",
                    "DecideItem",
                    "Forges",
                    "ForgeSet",
                    // What any task reported back, for whoever started it: never another agent's
                    // prompt.
                    "Reports");

    private McpMethods() {}

    /**
     * Answers the methods never offered.
     *
     * @return The reply's fields.
     */
    static Map<String, Object> answer() {
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("never", List.copyOf(new TreeSet<>(NEVER)));
        return reply;
    }

    /**
     * The methods the interface description names, which are the methods this daemon serves.
     *
     * @return Their names.
     */
    static Set<String> described() {
        final Set<String> names = new TreeSet<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?m)^method\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(")
                        .matcher(PoreloDaemon.description());
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    static void register(final VarlinkServer server) {
        server.method("McpMethods", (parameters, replies) -> replies.last(answer()));
    }
}
