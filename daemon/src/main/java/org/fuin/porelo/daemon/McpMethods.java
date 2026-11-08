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
     * Methods that change nothing and hand out no secret. Not here though they only read: {@code
     * LogArchive} and {@code PendingBundle} write a file, {@code Landed} and {@code TakeProject}
     * change state, {@code Tighten} changes modes unless asked for a dry run, and {@code
     * ReadHostVaultShare} hands out a share of the vault.
     */
    static final Set<String> READING =
            Set.of(
                    "List",
                    "Watch",
                    "CanStart",
                    "Logs",
                    "Tail",
                    "WorkHeld",
                    "ImageSettings",
                    "Screen",
                    "HandIns",
                    "Usage",
                    "FirstStart",
                    "HostKeys",
                    "Projects",
                    "WatchProjects",
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
                    "Providers",
                    "Installable",
                    "Ecosystems",
                    "Peers",
                    "Talk",
                    "Held",
                    "ReadHeld",
                    "MessageMembers",
                    "Doctor",
                    "Node",
                    "Process",
                    "Events",
                    "VerifyEvents");

    /**
     * Methods never offered, whatever the person's list says: each stops every task, removes what
     * cannot be had back, or hands out a secret.
     */
    static final Set<String> NEVER =
            Set.of(
                    "Panic",
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
                    "ReadHostVaultShare");

    /** Methods that stand for a person's own judgement; offered only from the list, and named. */
    static final Set<String> JUDGEMENT =
            Set.of(
                    "Approve",
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

    private static List<String> listed(final Path list) throws IOException {
        final List<String> lines;
        try {
            lines = Files.readAllLines(list, StandardCharsets.UTF_8);
        } catch (NoSuchFileException ex) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final String line : lines) {
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
