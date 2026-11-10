package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * Which methods an MCP client may be offered as tools, as {@code porelo-mcp} asks for them.
 *
 * <p>{@code reading}, {@code never} and {@code judgement} are Porelo's, named one by one and never
 * guessed from a method's name. {@code writing} is the person's own: the names in their local list,
 * less any in {@code never} and any Tasks1 does not have. Every name is a method's name without the
 * interface, as a tool is named.
 */
final class McpMethods {

    /** The person's own list of writing methods, one name per line, under the config directory. */
    static final String LOCAL_LIST = "mcp-writing";

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
                    "GetPlanningWorkflow",
                    "ListStories",
                    "GetStory",
                    "ListStoryIssues",
                    "GetIssue",
                    "WatchStories",
                    "GetStoryWork",
                    "ProposeStoryWork",
                    "List",
                    "McpMethods",
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
                    "Agents",
                    "AgentSignIns",
                    "Providers",
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
     * cannot be had back, hands out a secret ({@code ReadHostVaultShare}, and {@code ReadHeld} with
     * a refused message's text), changes where a credential is sent ({@code WriteDestination},
     * {@code CredentialDeclare}), or signs an agent in on the host's own network ({@code
     * AgentSignInStart}, and {@code AgentSignInWait}, which holds a call while one runs), or starts
     * or stops the board's work ({@code StartStoryWork}, {@code StopStoryWork}): no agent starts
     * another. {@code PullRequests} and {@code WatchPullRequests} spend the person's forge identity
     * to read reviews awaiting human judgment, so a task cannot ask them even from the local list.
     * PushToCentral releases approved work with the person's central credential and is never
     * offered.
     */
    static final Set<String> NEVER =
            Set.of(
                    "PushToCentral",
                    "PullRequests",
                    "WatchPullRequests",
                    // A host-credentialed query returns unfiltered server names for the operator.
                    "RoomMembers",
                    "StartStoryWork",
                    "StopStoryWork",
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
                    "AgentSignInStart",
                    "AgentSignInWait",
                    // The window's offer to take a repository out of 'default', on the person's
                    // word.
                    "NamedDefaultRepositories",
                    "LeaveDefault");

    /** Methods that stand for a person's own judgement; offered only from the list, and named. */
    static final Set<String> JUDGEMENT =
            Set.of(
                    "SaveMoves",
                    "ResolveIssueReading",
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
     * Answers the four sets for this account.
     *
     * @param list The person's local list.
     * @param methods The methods Tasks1 has.
     * @return The reply's fields.
     * @throws IOException If the list exists and cannot be read.
     */
    static Map<String, Object> answer(final Path list, final Set<String> methods)
            throws IOException {
        final Set<String> writing = new TreeSet<>();
        for (final String name : listed(list)) {
            if (methods.contains(name) && !NEVER.contains(name) && !READING.contains(name)) {
                writing.add(name);
            }
        }
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("reading", List.copyOf(new TreeSet<>(READING)));
        reply.put("writing", List.copyOf(writing));
        reply.put("never", List.copyOf(new TreeSet<>(NEVER)));
        reply.put("judgement", List.copyOf(new TreeSet<>(JUDGEMENT)));
        return reply;
    }

    /** Larger than any list of Tasks1's methods with comments; a larger file is refused unread. */
    static final int LARGEST_LIST = 16 * 1024;

    private static List<String> listed(final Path list) throws IOException {
        final java.nio.file.attribute.PosixFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            list,
                            java.nio.file.attribute.PosixFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return List.of();
        }
        // Who may change this list decides what a client may do, so it is this account's own file,
        // read as it is: not through a link, not a pipe that never ends, not without bound.
        if (attributes.isSymbolicLink()) {
            throw new IOException(
                    list + " is a link; the list is read only from a file of its own");
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(list + " is not a regular file");
        }
        if ((int) Files.getAttribute(list, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS)
                != (int) Files.getAttribute(Path.of("/proc/self"), "unix:uid")) {
            throw new IOException(list + " belongs to another account");
        }
        final byte[] bytes;
        try (java.io.InputStream in =
                Files.newInputStream(list, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(LARGEST_LIST + 1);
        }
        if (bytes.length > LARGEST_LIST) {
            throw new IOException(list + " is too large for a list of methods");
        }
        final List<String> names = new ArrayList<>();
        for (final String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            final int hash = line.indexOf('#');
            final String name = (hash < 0 ? line : line.substring(0, hash)).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
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

    static void register(final VarlinkServer server, final PoreloContext context) {
        server.method(
                "McpMethods",
                (parameters, replies) -> {
                    try {
                        replies.last(
                                answer(
                                        context.paths().xdg().config().resolve(LOCAL_LIST),
                                        described()));
                    } catch (IOException ex) {
                        throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                "The local list of MCP methods cannot be read: " + ex.getMessage());
                    }
                });
    }
}
