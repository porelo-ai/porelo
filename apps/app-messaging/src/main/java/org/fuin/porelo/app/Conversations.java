package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * What an interface may ask about a project's conversation - its room on a transport that keeps one
 * - and how it lets a person in or removes them.
 */
public final class Conversations {

    /** Why membership could not be changed. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final @Nullable String exists;

        private final @Nullable String missing;

        Refused(String message, int code, @Nullable String exists) {
            this(message, code, exists, null);
        }

        Refused(String message, int code, @Nullable String exists, @Nullable String missing) {
            super(message);
            this.code = code;
            this.exists = exists;
            this.missing = missing;
        }

        /**
         * Returns the transport's exit code, or 0 when it never ran.
         *
         * @return The code.
         */
        public int code() {
            return code;
        }

        /**
         * Returns the account the person already has, when that is why.
         *
         * @return The account, or {@code null} for any other reason.
         */
        public @Nullable String exists() {
            return exists;
        }

        /**
         * Returns the person who has not joined, when that is why.
         *
         * @return The person, or {@code null} for another refusal.
         */
        public @Nullable String missing() {
            return missing;
        }
    }

    /**
     * A successful removal from a project's conversation.
     *
     * @param user The removed or retained account, or empty when nothing was left.
     * @param kept Whether the transport kept the account.
     * @param why Why the account stays, or empty.
     */
    public record Removal(String user, boolean kept, String why) {}

    private Conversations() {}

    /**
     * Lets a person into a project's conversation, and returns what they are shown - once.
     *
     * @param context Where the vault and the state are.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @return What the transport printed: {@code login} and {@code shown}.
     * @throws Refused If the person has joined already and {@code reset} was not asked, or the
     *     transport refused.
     */
    public static Map<?, ?> join(
            PoreloContext context, Project project, String person, boolean reset) throws Refused {
        final TaskConversations conversations = new TaskConversations(context);
        try {
            ProjectMembers.requireAdmitted(context, project.name(), person);
        } catch (final ProjectMembers.Withheld ex) {
            throw new Refused(String.valueOf(ex.getMessage()), 69, null);
        }
        final String existing = conversations.members(context, project).get(person);
        if (existing != null && !reset) {
            throw new Refused(
                    "'" + person + "' has joined " + project.name() + " already, as " + existing,
                    0,
                    existing);
        }
        try {
            return conversations.join(context, project, person, reset);
        } catch (TransportLifecycle.Refused ex) {
            throw new Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        }
    }

    /**
     * Removes a listed person after the transport has successfully parted them.
     *
     * @param context Where the vault and state are.
     * @param project The project.
     * @param person Whom to remove.
     * @return The transport's removal outcome.
     * @throws Refused If the person is not listed, part is unavailable, or removal fails.
     */
    public static Removal remove(PoreloContext context, Project project, String person)
            throws Refused {
        return new TaskConversations(context).part(context, project, person);
    }

    /**
     * Returns who is heard in a project's conversation: admitted joins and each admitted person of
     * the project's members taken by their host account.
     *
     * @param context Where the state is.
     * @param project The project.
     * @return Person to their account.
     */
    public static Map<String, String> members(PoreloContext context, Project project) {
        // A person taken by their host account counts while the members admit them.
        return new TaskConversations(context).heard(context, project);
    }

    /**
     * Returns a project's conversation as a {@code Project} row carries it.
     *
     * @param context Where the state and the adapters are.
     * @param project The project.
     * @return The row's {@code messages}, or {@code null} for a project with no conversation.
     */
    public static @Nullable Map<String, Object> row(PoreloContext context, Project project) {
        final TaskConversations.Described described =
                new TaskConversations(context).describe(project);
        return described == null ? null : row(context, project, described);
    }

    /**
     * Returns a listed project's conversation, as its row carries it.
     *
     * <p>A project the host carries for this person and not taken here yet has no file to read its
     * mail section from: its conversation is the host's room, not set up here until the person sets
     * it up or its first task starts. A file that does not read says why, instead of leaving the
     * row without a conversation and its room without a word (2026-11-14).
     *
     * @param context The machine.
     * @param summary The project as listed.
     * @param hostFiles Where a host's files of the projects it carries are.
     * @return Its {@code messages}, or {@code null} when it has no conversation.
     */
    public static @Nullable Map<String, Object> ofProject(
            final PoreloContext context,
            final ProjectInventory.Summary summary,
            final java.nio.file.Path hostFiles) {
        final String file = summary.file();
        if (file == null || file.isEmpty()) {
            if (!summary.carried()) {
                return null;
            }
            final OrganizationProjects.Placed host;
            try {
                host = OrganizationProjects.host(hostFiles, summary.name());
            } catch (java.io.IOException | RuntimeException ignored) {
                return unreadable(context, "the host's file of the project does not read");
            }
            if (host == null || host.homeserver() == null) {
                return null;
            }
            final String room = host.room() == null ? "" : host.room();
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("transport", "matrix");
            row.put("conversation", room);
            row.put("reaches", java.util.List.of());
            row.put("ready", false);
            row.put(
                    "detail",
                    "carried by the host and not set up here yet: set it up, or start the"
                            + " project's first task here");
            row.put("loopbackOnly", false);
            row.put("machine", "");
            row.put("admitted", false);
            row.put("address", "");
            row.put("room", room);
            waiting(context, row);
            return row;
        }
        final Project project;
        try {
            project = org.fuin.porelo.core.project.ProjectReader.read(java.nio.file.Path.of(file));
        } catch (RuntimeException ignored) {
            return unreadable(
                    context, "the project's file does not read: 'porelo project check' says why");
        }
        try {
            return row(context, project);
        } catch (RuntimeException ignored) {
            return unreadable(context, "the project's conversation cannot be read");
        }
    }

    /**
     * A conversation that cannot be said, with why, so the room's button says it. The error's own
     * words stay out: a project file's refusal quotes the value it refused, which can be a password
     * in an address.
     */
    private static Map<String, Object> unreadable(final PoreloContext context, final String why) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("transport", "");
        row.put("conversation", "");
        row.put("reaches", java.util.List.of());
        row.put("ready", false);
        row.put("detail", why);
        row.put("loopbackOnly", false);
        row.put("machine", "");
        row.put("admitted", false);
        row.put("address", "");
        row.put("room", "");
        waiting(context, row);
        return row;
    }

    private static void waiting(final PoreloContext context, final Map<String, Object> row) {
        final VaultWait waiting = vaultWaiting(context);
        row.put("waits", waiting.waits());
        row.put("waitsOn", waiting.waitsOn());
        row.put("vault", waiting.vault());
    }

    /**
     * Sets a project's conversation up on this machine, or confirms it, without starting a task -
     * so a machine being bound learns the name it goes by there and whether it is let in yet.
     *
     * @param context Where the vault, the state and the adapters are.
     * @param project The project.
     * @return Its row, as {@link #row(PoreloContext, Project)} gives it.
     * @throws Refused If the project has no conversation, or the transport refused.
     */
    public static Map<String, Object> setUp(PoreloContext context, Project project) throws Refused {
        try {
            return row(context, project, new TaskConversations(context).setUp(project));
        } catch (TransportLifecycle.Refused ex) {
            throw new Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        }
    }

    private static Map<String, Object> row(
            PoreloContext context, Project project, TaskConversations.Described described) {
        final boolean installed =
                context.paths().messaging().transportDirectory().find(described.transport())
                        != null;
        final TransportLifecycle.Conversation said = described.said();
        final boolean admitted = described.setUp() && said.admitted();
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("transport", described.transport());
        row.put("conversation", described.conversation());
        row.put("reaches", described.reaches());
        row.put("ready", installed && admitted);
        row.put(
                "detail",
                !installed
                        ? "no '"
                                + described.transport()
                                + "' transport is installed on this machine"
                        : !described.setUp()
                                ? "set up when the project's first task starts, or when somebody joins"
                                : !admitted
                                        ? TaskConversations.notAdmitted(described.transport(), said)
                                        : "");
        row.put("loopbackOnly", project.securityClass() == SecurityClass.OFFLINE);
        // Named for the machine it was before a person's login took its place.
        row.put("machine", said.login());
        row.put("admitted", admitted);
        row.put("address", said.address());
        row.put("room", said.room());
        waiting(context, row);
        return row;
    }

    /** What a conversation waits for while the vault is locked. */
    static final String VAULT_LOCKED =
            "the vault is locked, so no conversation can be read or written: 'porelo vault"
                    + " unlock' lets them go on";

    /**
     * Says why every conversation waits now: a locked vault holds every project's and task's
     * account.
     *
     * <p>A vault unlocked for a time locked itself, and every message stopped without a word - none
     * was fetched, an answer was deferred, nothing in the journal (2026-10-04).
     *
     * @param context The machine.
     * @return {@link #VAULT_LOCKED}, or "" when the vault is open or there is none.
     */
    public static String vaultWait(PoreloContext context) {
        return vaultWaiting(context).waits();
    }

    private record VaultWait(String waits, String waitsOn, String vault) {}

    private static VaultWait vaultWaiting(PoreloContext context) {
        try {
            return java.nio.file.Files.isRegularFile(context.paths().vault().vaultFile())
                            && context.opener().isEmpty()
                    ? new VaultWait(
                            VAULT_LOCKED, "vault", context.paths().vault().vaultFile().toString())
                    : new VaultWait("", "", "");
        } catch (RuntimeException ex) {
            return new VaultWait("", "", "");
        }
    }
}
