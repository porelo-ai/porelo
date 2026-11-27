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
 * tool is named. {@link #READING} and {@link #JUDGEMENT} bound a task's own endpoint until the
 * markers replace them.
 */
final class McpMethods {

    /**
     * Methods that change nothing and hand out no secret. Not here though they seem to read: {@code
     * LogArchive} and {@code PendingBundle} write a file; {@code CanStart} may take a project,
     * lending a forge token; {@code FirstStart} keeps a note and looks things up; {@code Node}
     * makes the node's id on its first ask; {@code Landed} and {@code TakeProject} change state;
     * {@code Tighten} changes modes unless asked for a dry run.
     */
    static final Set<String> READING =
            Set.of(
                    "Arrivals",
                    "List",
                    "McpMethods",
                    "Limits",
                    "AccountSettings",
                    "LogBefore",
                    "ScreenBefore",
                    "Watch",
                    "Logs",
                    "Tail",
                    "WorkHeld",
                    "ImageSettings",
                    "Screen",
                    "HandIns",
                    "Usage",
                    "HostKeys",
                    "Projects",
                    "WatchProjects",
                    "ProviderModels",
                    "Backups",
                    "DefaultRepositories",
                    "ProjectFileSchema",
                    "CheckProjectFile",
                    "Sets",
                    "Egress",
                    "Reach",
                    "Prompts",
                    "Credentials",
                    "SshKeys",
                    "CredentialCheck",
                    "Keyslots",
                    "Authorizations",
                    "Destinations",
                    "Destination",
                    "HostVaultShare",
                    "Pending",
                    "Review",
                    "ReviewPage",
                    "Agents",
                    "AgentSignIns",
                    "Providers",
                    "Models",
                    "Installable",
                    "Ecosystems",
                    "Peers",
                    "Talk",
                    "Held",
                    "MessageMembers",
                    "Doctor",
                    "Process",
                    "Events",
                    "VerifyEvents");

    /**
     * Methods never offered, whatever the person's list says: each stops every task, removes what
     * cannot be had back, hands out a secret ({@code ReadHostVaultShare}, {@code HostIdentityRead}
     * and the other host identity methods, and {@code ReadHeld} with a refused message's text),
     * changes where a credential is sent ({@code WriteDestination}, {@code CredentialDeclare}), or
     * signs an agent in on the host's own network ({@code AgentSignInStart}, and {@code
     * AgentSignInWait}, which holds a call while one runs). {@code PullRequests}, {@code
     * PullRequestsPage} and {@code WatchPullRequests} spend or hand out what the person's forge
     * identity read of reviews awaiting human judgment, so a task cannot ask them even from the
     * local list. PushToCentral releases approved work with the person's central credential and is
     * never offered. {@code Comment}, {@code Reply}, {@code Comments}, {@code ResolveThread} and
     * {@code ReviewSummary} write, read and count a review as the owner account, so a task never
     * reaches them.
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

    /** Methods that stand for a person's own judgement. */
    static final Set<String> JUDGEMENT =
            Set.of(
                    "Approve",
                    "Reject",
                    "Decide",
                    "TrustHostKey",
                    "ConfirmFirstStart",
                    "Authorize",
                    "EnrollDevice",
                    "UnlockWithShare",
                    "Release");

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
