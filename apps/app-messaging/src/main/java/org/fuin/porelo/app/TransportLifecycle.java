package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Runs a transport's lifecycle - including {@code setup}, {@code enroll}, {@code retire}, {@code
 * join}, {@code part} and {@code members} - for a transport that keeps a conversation of its own,
 * and keeps what it hands back.
 *
 * <p><strong>Porelo knows no transport here.</strong> What a room is, how an account is made, which
 * homeserver runs where: all the transport's, behind its lifecycle verbs. Porelo hands each verb
 * the project's settings for it as JSON on stdin, exactly as {@code mail.transports.<scheme>} says
 * them, and the secrets it kept as environment variables; it keeps the {@code secrets} and {@code
 * account} a verb prints in the account's vault, opaque, and hands them back as they were printed.
 *
 * <p><strong>Three scopes, three entries, none ever in a task:</strong> the account's ({@code
 * transport/<scheme>/account}, only for the lifecycle verbs), a project's ({@code
 * transport/<scheme>/project/<p>}, what {@code poll} reads), and a task's ({@code
 * transport/<scheme>/task/<t>}, what {@code send}, {@code read} and {@code receipt} read).
 *
 * <p><strong>A fourth thing is only borrowed:</strong> a secret of the person's that {@code
 * describe} says a verb needs is read from their vault for that one run and kept nowhere of the
 * transport's. See {@link #borrowed}.
 */
final class TransportLifecycle {

    /** What a transport could not do, said in its own words and with its exit code. */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final String cause;

        private final boolean vaultShut;

        Refused(String message, int code) {
            this(message, code, StartStep.cleanCause(message));
        }

        Refused(String message, int code, String cause) {
            this(message, code, cause, false);
        }

        private Refused(String message, int code, String cause, boolean vaultShut) {
            super(message);
            this.code = code;
            this.cause = cause;
            this.vaultShut = vaultShut;
        }

        /** The vault could not lend what a verb borrows, so the transport was never run. */
        private static Refused lendingShut(String message) {
            return new Refused(message, 77, StartStep.cleanCause(message), true);
        }

        /**
         * Returns the transport's exit code: 75 temporary, 77 refused, 78 configuration missing.
         *
         * @return The code, or 0 when it never ran; 77 too when it never ran because the vault
         *     could not lend it what it borrows, which {@link #vaultShut()} tells apart.
         */
        int code() {
            return code;
        }

        /**
         * Says whether the vault could not lend what the verb borrows. A command that answers every
         * refusal of the transport alike still answers this one as itself.
         *
         * @return {@code true} when the transport was not run for that reason.
         */
        boolean vaultShut() {
            return vaultShut;
        }

        String cause() {
            return cause;
        }
    }

    /**
     * What {@code setup} said of a project's conversation.
     *
     * @param conversation What a peer address {@code <scheme>:} stands for, handed to {@code send}
     *     as {@code --to}.
     * @param reaches The hosts the project's messages reach through this transport.
     * @param login The person's login this account goes by there, as the transport kept it, or "".
     * @param admitted Whether this machine is in the conversation. False on a shared server until a
     *     person there has let this machine's account in; absent from what the transport said means
     *     true.
     * @param address This machine's account there, as the server names it, or "".
     * @param room The conversation as a person names it - an alias or the id the settings give - or
     *     "".
     */
    record Conversation(
            String conversation,
            List<String> reaches,
            String login,
            boolean admitted,
            String address,
            String room) {

        /**
         * Constructor for a conversation this machine is in, as a transport without a shared server
         * says it.
         *
         * @param conversation What a peer address stands for.
         * @param reaches The hosts the project's messages reach.
         */
        Conversation(String conversation, List<String> reaches) {
            this(conversation, reaches, "", true, "", "");
        }

        private static Conversation of(Map<?, ?> said) {
            return new Conversation(
                    text(said.get("conversation")),
                    said.get("reaches") instanceof List<?> hosts
                            ? hosts.stream().map(String::valueOf).toList()
                            : List.of(),
                    text(said.get("login")),
                    !Boolean.FALSE.equals(said.get("admitted")),
                    text(said.get("address")),
                    text(said.get("room")));
        }

        private static String text(@org.jspecify.annotations.Nullable Object value) {
            return value instanceof String said ? said : "";
        }
    }

    private final PoreloContext context;

    private final TransportDirectory transports;

    /** Where a host's files are; another place only in a test. */
    private final Path hostFiles;

    /** From this length a value is withheld inside a word too. */
    private static final int WHOLE_ANYWHERE = 8;

    private java.util.function.Consumer<String> saying =
            line -> {
                if (Offer.atTerminal()) {
                    System.err.println("porelo: " + line);
                }
            };

    /**
     * Constructor.
     *
     * @param context Where the vault and the state are, and how commands run.
     * @param transports Where the adapters are.
     */
    TransportLifecycle(PoreloContext context, TransportDirectory transports) {
        this(context, transports, OrganizationProjects.HOST_FILES);
    }

    /**
     * Constructor with the host's files elsewhere, for a test.
     *
     * @param context Where the vault and the state are, and how commands run.
     * @param transports Where the adapters are.
     * @param hostFiles Where a host's files are.
     */
    TransportLifecycle(PoreloContext context, TransportDirectory transports, Path hostFiles) {
        this.context = context;
        this.transports = transports;
        this.hostFiles = hostFiles;
    }

    /**
     * Says where what a transport says while it runs goes: a line each, cleaned and with what it
     * was handed withheld. By default a person at a terminal reads it, and nobody else.
     *
     * @param lines Takes each line.
     * @return What took them until now.
     */
    java.util.function.Consumer<String> saying(final java.util.function.Consumer<String> lines) {
        final java.util.function.Consumer<String> before = this.saying;
        this.saying = lines;
        return before;
    }

    /**
     * Sets up a project's conversation on a transport, or confirms it: run every time, it keeps
     * what exists.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param settings What the project says to this transport, or an empty map.
     * @return What the conversation is and what it reaches.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    Conversation setup(String scheme, String project, Map<String, Object> settings) throws Refused {
        return setup(scheme, project, settings, false);
    }

    /**
     * Sets up a project's conversation, telling the transport when it may reach nothing beyond this
     * machine.
     *
     * <p><strong>{@code --loopback-only}</strong> for an offline project: the transport refuses
     * (78) before it contacts anything but this machine's loopback. Checking {@code reaches}
     * afterwards alone came too late - the transport had already reached out (found on 2026-09-30).
     * Both hold: the transport is told, and what it says it reaches is still checked.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return What the conversation is and what it reaches.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    Conversation setup(
            String scheme, String project, Map<String, Object> settings, boolean loopbackOnly)
            throws Refused {
        lendable(scheme, "setup");
        writable(scheme);
        // One setup of the account at a time, its secrets read inside: a start that gives the
        // transport none has it make an account of its own, and starts at once each did.
        final Conversation[] found = new Conversation[1];
        final Refused[] refused = new Refused[1];
        try {
            FileLocks.holding(
                    lockOf(accountFile(scheme)),
                    () -> {
                        try {
                            found[0] = setupHeld(scheme, project, settings, loopbackOnly);
                        } catch (Refused ex) {
                            refused[0] = ex;
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            throw new Refused(
                    "the "
                            + scheme
                            + " transport's account could not be set up alone: "
                            + ex.getMessage(),
                    0);
        }
        if (refused[0] != null) {
            throw refused[0];
        }
        return java.util.Objects.requireNonNull(found[0], "a setup that was not refused answers");
    }

    /** Where the account's lock lies, beside the transport's state. */
    private Path accountFile(String scheme) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("account");
    }

    /** Runs the transport's setup and keeps what it said; under the account's lock. */
    private Conversation setupHeld(
            String scheme, String project, Map<String, Object> settings, boolean loopbackOnly)
            throws Refused {
        // The account's and the project's, as they were printed: so it keeps what it made - the
        // project's relay
        // - rather than making it again (asked on 2026-09-30).
        final Map<String, String> given = given(scheme, project);
        final Map<?, ?> said =
                run(scheme, settings, given, loopbackOnly, "setup", "--project", project);
        final Conversation found = Conversation.of(said);
        if (loopbackOnly) {
            // What it says it reaches is checked here, after every setup, not only before a task
            // enrolls: a setup through the daemon or a join is checked nowhere else. Before
            // anything
            // is kept: the project's secrets in the vault would start polling a server elsewhere.
            final List<String> away =
                    found.reaches().stream()
                            .filter(host -> !TaskConversations.loopback(host))
                            .toList();
            if (!away.isEmpty()) {
                throw new Refused(
                        "an offline project's messages never leave this machine, and its "
                                + scheme
                                + " conversation reaches "
                                + String.join(", ", away)
                                + "; give it a conversation on this machine's loopback",
                        77);
            }
        }
        if (said.get("account") instanceof Map<?, ?> account && !account.isEmpty()) {
            keep(scheme, "account", account);
        }
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "project/" + project, secrets);
        }
        // Kept even when this machine is not admitted yet: the account the transport registered is
        // in the vault by now, and the name a person needs to let it in is in what is remembered.
        remember(scheme, project, found);
        return found;
    }

    /**
     * Enrolls a task into its project's conversation, or confirms it.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    void enroll(String scheme, String project, String task, Map<String, Object> settings)
            throws Refused {
        enroll(scheme, project, task, settings, false);
    }

    /**
     * Enrolls a task, telling the transport when it may reach nothing beyond this machine, shown by
     * its short name.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    void enroll(
            String scheme,
            String project,
            String task,
            Map<String, Object> settings,
            boolean loopbackOnly)
            throws Refused {
        enroll(scheme, project, task, null, settings, loopbackOnly);
    }

    /**
     * Enrolls a task under its agent's name in the room, where the transport shows one.
     *
     * <p>The name is chosen and recorded under the addresses file's lock, around the transport's
     * enroll: two starts at once would otherwise both take one agent's name, and a head would name
     * two tasks. A name a task of the project holds stays its own until it is retired.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param agent The task's agent, or {@code null} to show it by its short name.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @throws Refused If the transport refused, or its secrets or its name cannot be kept.
     */
    void enroll(
            String scheme,
            String project,
            String task,
            @Nullable String agent,
            Map<String, Object> settings,
            boolean loopbackOnly)
            throws Refused {
        lendable(scheme, "enroll");
        writable(scheme);
        final Map<String, String> given = given(scheme, project);
        final Path adapter = transports.find(scheme);
        final boolean shows =
                adapter != null
                        && TransportDescription.of(context.runner(), adapter).takes(DISPLAY);
        final Path file = addressesFile(scheme, project);
        final Path shown = displaysFile(scheme, project);
        final Refused[] refused = new Refused[1];
        try {
            FileLocks.holding(
                    lockOf(file),
                    () -> {
                        try {
                            enrolled(
                                    scheme,
                                    project,
                                    task,
                                    agent,
                                    settings,
                                    loopbackOnly,
                                    given,
                                    shows,
                                    file,
                                    shown);
                        } catch (Refused ex) {
                            refused[0] = ex;
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            // A name nobody recorded could be handed to the next task as well.
            throw new Refused(
                    "the names of "
                            + project
                            + "'s tasks in its "
                            + scheme
                            + " conversation could not be kept: "
                            + ex.getMessage(),
                    0);
        }
        if (refused[0] != null) {
            throw refused[0];
        }
    }

    /** Runs the transport's enroll and records what it said; under the addresses file's lock. */
    private void enrolled(
            String scheme,
            String project,
            String task,
            @Nullable String agent,
            Map<String, Object> settings,
            boolean loopbackOnly,
            Map<String, String> given,
            boolean shows,
            Path file,
            Path shownFile)
            throws Refused, IOException {
        final Map<String, String> held = new LinkedHashMap<>(names(shownFile));
        final String shown =
                shows
                        ? display(
                                enrollmentTask(project, task),
                                task,
                                agent,
                                heldWithEarlier(project, held, file))
                        : null;
        final String named = contractName(project, task);
        final List<String> verb =
                new java.util.ArrayList<>(List.of("enroll", "--project", project, "--task", named));
        if (shown != null) {
            verb.add("--display");
            verb.add(shown);
        }
        final Map<?, ?> said =
                run(scheme, settings, given, loopbackOnly, verb.toArray(String[]::new));
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "task/" + task, secrets);
        }
        Directories.create(file.getParent());
        final Path enrolledFile = enrolledFile(scheme, project);
        final Map<String, String> enrolledAs = new LinkedHashMap<>(names(enrolledFile));
        if (!named.equals(task) && !named.equals(enrolledAs.get(task))) {
            // Retire must give the transport the name the account was made under.
            Directories.create(enrolledFile.getParent());
            enrolledAs.put(task, named);
            whole(enrolledFile, Json.write(enrolledAs));
        }
        if (shown != null && !shown.equals(held.get(task))) {
            Directories.create(shownFile.getParent());
            held.put(task, shown);
            whole(shownFile, Json.write(held));
        }
        if (said.get("address") instanceof String address && !address.isBlank()) {
            // How the task is named in the conversation - not a secret - so what is sent to it can
            // be asked about: 'receipt --by' takes it. Under the lock: two tasks of a project
            // started at once each wrote the file as it read it, and one task's address was lost.
            try {
                final Map<String, String> addresses = new LinkedHashMap<>(names(file));
                addresses.put(task, address);
                whole(file, Json.write(addresses));
            } catch (IOException ex) {
                // The task takes part all the same; only whether it read something cannot be asked.
            }
        }
    }

    /**
     * Returns the name a task has in a transport's contract: {@code porelo-<project>-<task>} from
     * its own name, so a room reads it as the project's task; the container itself where its own
     * name cannot be found, as a task made before is named so already.
     */
    private String contractName(String project, String container) throws IOException {
        // A task made before is named in the contract's form already: no lookup in podman, which
        // a start would otherwise wait on at every enroll.
        if (!project.equals("porelo")
                && container.startsWith(ContainerName.contract(project, ""))) {
            return container;
        }
        final String own = enrollmentTask(project, container);
        return own == null ? container : ContainerName.contract(project, own);
    }

    private @Nullable String enrollmentTask(String project, String container) throws IOException {
        final String logical = TaskRecipients.task(context, container, project);
        if (logical != null) return logical;
        if (!container.matches("porelo-[a-z0-9_-]+")) return null;
        final var pending = new TaskReservations(context).unmade(container);
        return pending != null && project.equals(pending.project()) ? pending.task() : null;
    }

    /**
     * Returns the names held, with each task enrolled before names were recorded holding the one
     * its enroll was given then, "Agent" and its short name: the room still shows it so.
     */
    private Map<String, String> heldWithEarlier(
            String project, Map<String, String> held, Path addresses) {
        final Map<String, String> all = new LinkedHashMap<>(held);
        for (final String enrolled : names(addresses).keySet()) {
            final String then = agentName(TaskRecipients.task(context, enrolled, project));
            if (then != null) {
                all.putIfAbsent(enrolled, then);
            }
        }
        return all;
    }

    /** What a transport announces when its enroll takes the name the room shows for a task. */
    static final String DISPLAY = "display";

    /** The most characters a name the room shows may have. */
    private static final int DISPLAY_LENGTH = 64;

    /** An agent's name as a room may show it: lowercase words joined by hyphens. */
    private static final java.util.regex.Pattern AGENT =
            java.util.regex.Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * Returns the name a task is to be shown by in its project's room: "Agent" and its own name
     * ("Agent Pi" for the task {@code pi}, whatever agent runs it), the name a line headed for it
     * reaches; the agent's name follows in brackets only where another task of the project holds
     * the first. A task that holds a name keeps it, unless it is the agent-name form it was given
     * before the room showed tasks by their own names.
     *
     * @param task The verified logical task.
     * @param container The actual container.
     * @param agent The task's agent, or {@code null} when it is not known.
     * @param held The names the project's enrolled tasks hold, by container.
     * @return It, or {@code null} when no name fits that no other task holds.
     */
    static @Nullable String display(
            final @Nullable String task,
            final String container,
            final @Nullable String agent,
            final Map<String, String> held) {
        final String agentForm =
                agent != null && AGENT.matcher(agent).matches() ? capitalized(agent) : null;
        final String named = agentName(task);
        final java.util.Set<String> others = new java.util.HashSet<>();
        held.forEach(
                (other, name) -> {
                    if (!other.equals(container)) {
                        others.add(name.toLowerCase(java.util.Locale.ROOT));
                    }
                });
        final String own = held.get(container);
        // A name the task holds stays, unless another task holds it too or it is the agent's form
        // the room gave before it showed tasks by their own names.
        if (own != null
                && !others.contains(own.toLowerCase(java.util.Locale.ROOT))
                && !earlierForm(task, agent, own)) {
            return own;
        }
        // A container of another form has no name of its own to show: then its agent's, as before.
        final String first = named != null ? named : agentForm;
        if (first == null || first.length() > DISPLAY_LENGTH) {
            return null;
        }
        if (!others.contains(first.toLowerCase(java.util.Locale.ROOT))) {
            return first;
        }
        final String within =
                named != null && agentForm != null
                        ? agentForm.substring("Agent ".length())
                        : task == null || task.isEmpty() ? container : task;
        // Numbered where two short names cut to one: a task shown by no name of its own is shown
        // by its account, which no head names.
        for (int number = 1; number <= held.size() + 1; number++) {
            final String suffix = number == 1 ? "" : "-" + number;
            final String before =
                    ContainerName.shortened(
                            first, DISPLAY_LENGTH - " ()".length() - suffix.length() - 1);
            final int room = DISPLAY_LENGTH - before.length() - " ()".length();
            final String cut = ContainerName.shortened(within, room - suffix.length());
            if (cut.isEmpty()) {
                return null;
            }
            final String second = before + " (" + cut + suffix + ")";
            if (!others.contains(second.toLowerCase(java.util.Locale.ROOT))) {
                return second;
            }
        }
        return null;
    }

    /**
     * Says whether a name a task holds is the agent's form the room gave it before it showed tasks
     * by their own names: "Agent Scout" for a task not called scout, or "Agent Scout (fix)" with
     * its short name in lower case. A name the room gives now, or one a person chose, is not.
     *
     * @param task The verified logical task.
     * @param agent The task's agent, or {@code null} when it is not known.
     * @param own The name it holds.
     * @return {@code true} when the task is to be given its own name instead.
     */
    static boolean earlierForm(
            final @Nullable String task, final @Nullable String agent, final String own) {
        final String agentForm =
                agent != null && AGENT.matcher(agent).matches() ? capitalized(agent) : null;
        final String named = agentName(task);
        if (agentForm == null || named == null) {
            return false;
        }
        if (own.equals(agentForm)) {
            return !own.equals(named);
        }
        final String start = agentForm + " (";
        return own.startsWith(start)
                && own.length() > start.length()
                && !Character.isUpperCase(own.charAt(start.length()));
    }

    /** Returns the room name for a verified logical task, without decoding a container. */
    static @Nullable String agentName(final @Nullable String task) {
        if (task == null || !task.matches(org.fuin.porelo.core.project.Project.TASK_NAME_PATTERN)) {
            return null;
        }
        final String shown = capitalized(task);
        return shown.length() <= DISPLAY_LENGTH ? shown : null;
    }

    /** Returns "Agent" and a hyphenated name with each word capitalized, preserving empty words. */
    private static String capitalized(final String name) {
        final StringBuilder shown = new StringBuilder("Agent ");
        final String[] words = name.split("-", -1);
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                shown.append('-');
            }
            if (!words[i].isEmpty()) {
                shown.append(Character.toUpperCase(words[i].charAt(0)))
                        .append(words[i], 1, words[i].length());
            }
        }
        return shown.toString();
    }

    /**
     * Returns the names the room shows for a project's enrolled tasks, as their enroll chose them.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Task container to its name; empty when none was given one.
     */
    Map<String, String> displays(String scheme, String project) {
        return names(displaysFile(scheme, project));
    }

    /**
     * Returns the recorded room displays a wake can use without asking a transport or the vault.
     */
    List<String> displayNames(final String project, final String container) throws IOException {
        final Path directory = context.paths().xdg().state().resolve("transport");
        if (!Files.isDirectory(directory)) {
            return TaskRecipients.displays(
                    TaskRecipients.task(context, container, project), container, Map.of());
        }
        try (var schemes = Files.list(directory)) {
            final List<Map<String, String>> rooms =
                    schemes.filter(Files::isDirectory)
                            .map(path -> path.getFileName().toString())
                            .filter(
                                    scheme ->
                                            Files.isRegularFile(state(scheme, project))
                                                    || Files.isRegularFile(
                                                            displaysFile(scheme, project))
                                                    || Files.isRegularFile(
                                                            addressesFile(scheme, project)))
                            .map(scheme -> displays(scheme, project))
                            .toList();
            return rooms.isEmpty()
                    ? TaskRecipients.displays(
                            TaskRecipients.task(context, container, project), container, Map.of())
                    : rooms.stream()
                            .flatMap(
                                    room ->
                                            TaskRecipients.displays(
                                                    TaskRecipients.task(
                                                            context, container, project),
                                                    container,
                                                    room)
                                                    .stream())
                            .distinct()
                            .toList();
        }
    }

    /**
     * Returns how each enrolled task of a project is named in its conversation, as {@code enroll}
     * said.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Task container to its address; empty when the transport named none.
     */
    Map<String, String> addresses(String scheme, String project) {
        return names(addressesFile(scheme, project));
    }

    /**
     * Returns who has joined a project's conversation, as {@code join} named their account.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Person to their account.
     */
    Map<String, String> people(String scheme, String project) {
        return names(peopleFile(scheme, project));
    }

    /**
     * Returns the members of an organization's project, where the transport hands over persons'
     * words and they are kept in the room: by its members verb, or by the host on its own
     * homeserver. Their words are then taken without a join.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Their host accounts; empty where the transport hands over no persons' words, or the
     *     project is no organization's placed here.
     */
    java.util.Set<String> persons(String scheme, String project) {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            return java.util.Set.of();
        }
        final TransportDescription described = TransportDescription.of(context.runner(), adapter);
        if (!described.takes(TransportConversations.PERSONS)) {
            return java.util.Set.of();
        }
        try {
            if (described.offers("members")) {
                return ProjectMembers.admitted(context, project);
            }
            // Kept in the room by the host on its own homeserver, where the transport offers no
            // members verb (porelo-message-matrix 4d8bb16): asking for one there heard nobody.
            // Only the host's file says so, never this account's own record, which it may write
            // and which a lock-out leaves behind.
            return ProjectMembers.admittedOnTheHostsHomeserver(
                    new OrganizationProjects(
                            context.paths().projects().organizationProjects(), hostFiles),
                    GateSupport.byName(context, project),
                    scheme);
        } catch (ProjectMembers.Withheld | RuntimeException ex) {
            // An unreadable admission decision grants nobody access by their host account.
            return java.util.Set.of();
        }
    }

    /**
     * Returns who is heard in a project's conversation now: who joined, and each person taken by
     * their host account while the project's members still names them. Who was taken is kept apart
     * from who joined, so a person the file drops is heard no more and answered no more at once,
     * and a later join is never undone by it.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param persons The project's admitted members, as {@link #persons} read them.
     * @return Person to their account.
     */
    Map<String, String> heard(String scheme, String project, java.util.Set<String> persons) {
        final Map<String, String> people = admittedJoins(scheme, project);
        // Unreadable, it names nobody: only admitted joins are heard.
        names(takenFile(scheme, project))
                .forEach(
                        (person, user) -> {
                            if (persons.contains(person)
                                    && !people.containsKey(person)
                                    && !people.containsValue(user)) {
                                people.put(person, user);
                            }
                        });
        return people;
    }

    /** A join counts only while admitted; its record stays for a later unlock. */
    private Map<String, String> admittedJoins(String scheme, String project) {
        final Map<String, String> people = new LinkedHashMap<>(people(scheme, project));
        if (people.isEmpty()) {
            return people;
        }
        try {
            if (ProjectMembers.listed(context, project)) {
                people.keySet().retainAll(ProjectMembers.admitted(context, project));
            }
            return people;
        } catch (ProjectMembers.Withheld | RuntimeException ex) {
            // An unreadable access decision cannot count as permission to hear a joined account.
            return new LinkedHashMap<>();
        }
    }

    /**
     * Returns who is heard in a project's conversation, reading the persons only when somebody was
     * taken by their host account: a listing of who joined never runs the transport.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Person to their account.
     */
    Map<String, String> heard(String scheme, String project) {
        if (!Files.exists(takenFile(scheme, project))) {
            return admittedJoins(scheme, project);
        }
        return heard(scheme, project, persons(scheme, project));
    }

    /**
     * Keeps the account a person writes under, the first time they are taken by their host account,
     * so a task can answer them; apart from who joined, under a lock of its own.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Their host account.
     * @param user The account the conversation knows them by.
     */
    void remember(String scheme, String project, String person, String user) {
        final Path taken = takenFile(scheme, project);
        try {
            Directories.create(taken.getParent());
            FileLocks.holding(
                    lockOf(taken),
                    () -> {
                        final Map<String, String> kept = new LinkedHashMap<>(names(taken));
                        if (!kept.containsKey(person) && !kept.containsValue(user)) {
                            kept.put(person, user);
                            whole(taken, Json.write(kept));
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            // Taken all the same; only answering them by name waits for the next time.
        }
    }

    private static Path lockOf(Path file) {
        return file.resolveSibling("." + file.getFileName() + ".lock");
    }

    private Path takenFile(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("taken")
                .resolve(project + ".json");
    }

    private Path peopleFile(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("members")
                .resolve(project + ".json");
    }

    private Path displaysFile(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("displays")
                .resolve(project + ".json");
    }

    /** Each task's container and the name it was enrolled under, where the two differ. */
    private Path enrolledFile(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("enrolled")
                .resolve(project + ".json");
    }

    private Path addressesFile(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("addresses")
                .resolve(project + ".json");
    }

    private static Map<String, String> names(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> read) {
                final Map<String, String> names = new LinkedHashMap<>();
                read.forEach((key, value) -> names.put(String.valueOf(key), String.valueOf(value)));
                return names;
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: nobody is named.
        }
        return Map.of();
    }

    /**
     * Retires a task from its project's conversation and forgets its secrets. A task the transport
     * holds nothing for is left as it is.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @throws Refused If the transport refused; its secrets are then kept, so it can be retried.
     */
    /**
     * The secret by which the matrix transport marks an account on a central homeserver: set there,
     * empty or absent on the account's own, where a retire deactivates the account for good.
     */
    static final String CENTRAL_LOGIN = "PORELO_MATRIX_LOGIN";

    /**
     * Takes a stopped task out of its room where its next enroll brings it back: on a central
     * homeserver, as the task's own kept secrets say, by the transport's retire. Elsewhere nothing
     * is asked, since a retire there ends the account for good.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task.
     * @param settings The transport's settings in the project.
     * @return Whether the task left.
     * @throws Refused When the transport refused.
     */
    boolean leave(String scheme, String project, String task, Map<String, Object> settings)
            throws Refused {
        // The task's own secrets alone: the account's or the project's may hold a login from
        // another
        // setup, and a retire read from them would end an account on its own homeserver for good.
        final String login = secrets(scheme, "task/" + task).get(CENTRAL_LOGIN);
        if (login == null || login.isBlank()) {
            return false;
        }
        retire(scheme, project, task, settings);
        return true;
    }

    void retire(String scheme, String project, String task, Map<String, Object> settings)
            throws Refused {
        lendable(scheme, "retire");
        final Map<String, String> own = secrets(scheme, "task/" + task);
        if (!own.isEmpty()) {
            final Map<String, String> given = given(scheme, project);
            given.putAll(own);
            final String named = names(enrolledFile(scheme, project)).getOrDefault(task, task);
            run(scheme, settings, given, false, "retire", "--project", project, "--task", named);
            forget(scheme, "task/" + task);
        }
        // Only once its account is gone: until then a head of its name still reaches it.
        release(scheme, project, task);
        // What its direct chats were polled into ends with its account, held secrets or not.
        final Path direct =
                context.paths()
                        .xdg()
                        .state()
                        .resolve("transport")
                        .resolve(scheme)
                        .resolve("direct")
                        .resolve(task);
        try {
            if (Files.exists(direct, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // Not following links: a link in it is removed, never what it points to.
                try (var walk = Files.walk(direct)) {
                    for (final Path path :
                            walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException ex) {
            throw new Refused(
                    "what its direct chats left, "
                            + direct
                            + ", could not be removed: "
                            + ex.getMessage(),
                    0);
        }
    }

    /** Frees the name a retired task held in the room, under the addresses file's lock. */
    private void release(String scheme, String project, String task) {
        final Path shown = displaysFile(scheme, project);
        if (!Files.isRegularFile(shown)
                && !Files.isRegularFile(addressesFile(scheme, project))
                && !Files.isRegularFile(enrolledFile(scheme, project))) {
            return;
        }
        try {
            FileLocks.holding(
                    lockOf(addressesFile(scheme, project)),
                    () -> {
                        final Map<String, String> held = new LinkedHashMap<>(names(shown));
                        if (held.remove(task) != null) {
                            whole(shown, Json.write(held));
                        }
                        // Its address too: a task enrolled before names were recorded holds by it.
                        final Path file = addressesFile(scheme, project);
                        final Map<String, String> addresses = new LinkedHashMap<>(names(file));
                        if (addresses.remove(task) != null) {
                            whole(file, Json.write(addresses));
                        }
                        final Path enrolled = enrolledFile(scheme, project);
                        final Map<String, String> named = new LinkedHashMap<>(names(enrolled));
                        if (named.remove(task) != null) {
                            whole(enrolled, Json.write(named));
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            // Held a while longer: a name kept too long is shown with the short name, never twice.
        }
    }

    /**
     * Clears a project's conversation, or with no project the account's, and forgets what Porelo
     * kept for it.
     *
     * <p>The transport's {@code clear} runs where its {@code describe} offers it; Porelo's own
     * entries go either way: kept, a task started again after the transport had cleared ran no
     * {@code setup} and stayed outside a conversation that was gone. A project's are its own and
     * its tasks'; the account's is cleared only with no project, after the last.
     *
     * @param scheme The transport.
     * @param project The project, or {@code null} for the account.
     * @param settings What the project says to this transport; empty for the account.
     * @return What the transport said it removed.
     * @throws Refused If the transport refused; what Porelo kept is then kept too, so it can be
     *     retried.
     */
    List<String> clear(String scheme, @Nullable String project, Map<String, Object> settings)
            throws Refused {
        final List<String> removed = new java.util.ArrayList<>();
        final Path adapter = transports.find(scheme);
        if (adapter != null && TransportDescription.of(context.runner(), adapter).offers("clear")) {
            final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
            if (project != null) {
                given.putAll(secrets(scheme, "project/" + project));
            }
            final Map<?, ?> said =
                    project == null
                            ? run(scheme, settings, given, false, "clear")
                            : run(scheme, settings, given, false, "clear", "--project", project);
            if (said.get("removed") instanceof List<?> listed) {
                listed.forEach(each -> removed.add(String.valueOf(each)));
            }
        }
        if (!context.vault().exists() || context.opener().isEmpty()) {
            return removed;
        }
        if (project == null) {
            forget(scheme, "account");
            return removed;
        }
        try {
            new TaskReservations(context)
                    .locked(
                            () -> {
                                final String tasks =
                                        TaskSecrets.TRANSPORT_PREFIX + scheme + "/task/";
                                context.vault()
                                        .update(
                                                context.opener().orElseThrow(),
                                                all -> {
                                                    all.remove(entry(scheme, "project/" + project));
                                                    all.keySet()
                                                            .removeIf(
                                                                    name ->
                                                                            name.startsWith(tasks)
                                                                                    && owns(
                                                                                            project,
                                                                                            name
                                                                                                    .substring(
                                                                                                            tasks
                                                                                                                    .length())));
                                                    return all;
                                                });
                                return Boolean.TRUE;
                            });
        } catch (IOException | org.fuin.porelo.vault.VaultException ex) {
            throw new Refused("Task credentials could not be cleared: " + ex.getMessage(), 0);
        }
        try {
            Files.deleteIfExists(state(scheme, project));
            Files.deleteIfExists(addressesFile(scheme, project));
            Files.deleteIfExists(displaysFile(scheme, project));
            Files.deleteIfExists(takenFile(scheme, project));
            Files.deleteIfExists(
                    context.paths()
                            .xdg()
                            .state()
                            .resolve("transport")
                            .resolve(scheme)
                            .resolve("members")
                            .resolve(project + ".json"));
        } catch (IOException ex) {
            throw new Refused(
                    "what this machine kept of the "
                            + scheme
                            + " conversation of "
                            + project
                            + " could not be removed: "
                            + ex.getMessage(),
                    0);
        }
        return removed;
    }

    /**
     * Lets a person into a project's conversation, and returns what they are to be shown, once.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @return What the transport printed: {@code login} and {@code shown}. Never kept.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(
            String scheme,
            String project,
            String person,
            boolean reset,
            Map<String, Object> settings)
            throws Refused {
        return join(scheme, project, person, reset, settings, false);
    }

    /**
     * Lets a person in, telling the transport when it may reach nothing beyond this machine.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return What the transport printed.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(
            String scheme,
            String project,
            String person,
            boolean reset,
            Map<String, Object> settings,
            boolean loopbackOnly)
            throws Refused {
        return join(scheme, project, person, reset, settings, loopbackOnly, false);
    }

    /**
     * Lets a person in, asking for the person's access token instead of a password when asked to.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @param token Whether the transport answers the access token in {@code secrets}, and no
     *     password anywhere.
     * @return What the transport printed.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(
            String scheme,
            String project,
            String person,
            boolean reset,
            Map<String, Object> settings,
            boolean loopbackOnly,
            boolean token)
            throws Refused {
        return join(scheme, project, person, reset, settings, loopbackOnly, token, null);
    }

    /**
     * Lets a person in under the name the conversation shows for them.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @param token Whether the transport answers the access token in {@code secrets}, and no
     *     password anywhere.
     * @param display The name shown for the person, checked by the transport, or {@code null} for
     *     the transport's own.
     * @return What the transport printed.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(
            String scheme,
            String project,
            String person,
            boolean reset,
            Map<String, Object> settings,
            boolean loopbackOnly,
            boolean token,
            @Nullable String display)
            throws Refused {
        notAnAgent(person, display);
        final Map<String, String> given = given(scheme, project);
        final java.util.List<String> verb =
                new java.util.ArrayList<>(
                        java.util.List.of("join", "--project", project, "--person", person));
        if (reset) {
            verb.add("--reset");
        }
        if (token) {
            verb.add("--token");
        }
        if (display != null) {
            verb.add("--display");
            verb.add(display);
        }
        return run(scheme, settings, given, loopbackOnly, verb.toArray(String[]::new));
    }

    /**
     * Removes a person from a conversation, with the same secrets and settings as join.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom to remove.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return What the transport removed or kept, or an empty object when nothing remained.
     * @throws Refused If part is not offered or the transport refused.
     */
    Map<?, ?> part(
            String scheme,
            String project,
            String person,
            Map<String, Object> settings,
            boolean loopbackOnly)
            throws Refused {
        // Listed by a join before persons were checked, a name read as an option is one.
        login(person);
        final Path adapter = transports.find(scheme);
        if (adapter == null || !TransportDescription.of(context.runner(), adapter).offers("part")) {
            throw new Refused("the " + scheme + " transport does not offer part", 0);
        }
        final Map<String, String> given = given(scheme, project);
        return run(
                scheme,
                settings,
                given,
                loopbackOnly,
                "part",
                "--project",
                project,
                "--person",
                person);
    }

    /** The room snapshot is bounded before JSON is parsed, including fields we do not return. */
    private static final int MEMBERS_ANSWER_BYTES = 1024 * 1024;

    private static final int MOST_MEMBERS = 1024;

    private static final int ROOM_ID_LENGTH = 1024;

    private static final int SHOWN_LENGTH = 256;

    /**
     * Reads current membership without making a room or keeping any of the transport's answer.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return The judged room and its members, with an empty room before setup.
     * @throws Refused If the verb is unsupported, the transport refuses or its answer is invalid.
     */
    RoomMembers members(
            String scheme, String project, Map<String, Object> settings, boolean loopbackOnly)
            throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        final TransportDescription described = TransportDescription.of(context.runner(), adapter);
        if (!described.offers("members") || !described.takes(TransportInput.INPUT)) {
            throw new Refused(
                    "the " + scheme + " transport must offer members with secrets on stdin", 78);
        }
        final Map<?, ?> said =
                run(
                        scheme,
                        described,
                        settings,
                        given(scheme, project),
                        loopbackOnly,
                        "members",
                        "--project",
                        project);
        return judgedMembers(said, project, described.takes(LOGIN), containersOf(scheme, project));
    }

    /** Each name a task was enrolled under, to the container it is. */
    private Map<String, String> containersOf(String scheme, String project) {
        final Map<String, String> containers = new LinkedHashMap<>();
        names(enrolledFile(scheme, project))
                .forEach((container, named) -> containers.put(named, container));
        return containers;
    }

    private RoomMembers judgedMembers(
            Map<?, ?> said, String project, boolean takesLogin, Map<String, String> enrolledAs)
            throws Refused {
        if (!(said.get("members") instanceof List<?> listed) || listed.size() > MOST_MEMBERS) {
            throw badMembers("members must be a list of at most " + MOST_MEMBERS);
        }
        final String room =
                said.containsKey("room") ? memberText(said, "room", ROOM_ID_LENGTH, true) : "";
        if (room.isEmpty() && !listed.isEmpty()) {
            throw badMembers("room is absent for a nonempty members list");
        }
        final List<RoomMembers.Member> members = new java.util.ArrayList<>();
        final java.util.Set<String> ids = new java.util.HashSet<>();
        final java.util.Set<String> tasks = new java.util.HashSet<>();
        final String ownLogin = takesLogin ? login() : "";
        java.util.@Nullable Set<String> containers = null;
        for (final Object each : listed) {
            if (!(each instanceof Map<?, ?> row)) {
                throw badMembers("member must be an object");
            }
            final String id = memberText(row, "id", ROOM_ID_LENGTH, false);
            final String shown = memberText(row, "shown", SHOWN_LENGTH, true);
            final String state = memberText(row, "state", 6, false);
            final String kind = memberText(row, "kind", 6, false);
            if (!java.util.Set.of("join", "invite").contains(state)) {
                throw badMembers("state must be join or invite");
            }
            if (!java.util.Set.of("task", "person", "helper", "other").contains(kind)) {
                throw badMembers("kind must be task, person, helper or other");
            }
            if (!ids.add(id)) {
                throw badMembers("duplicate member id");
            }
            if (!"task".equals(kind)) {
                if (row.containsKey("task")) {
                    throw badMembers("task is allowed only on kind task");
                }
                members.add(new RoomMembers.Member(id, shown, state, kind, null));
                continue;
            }
            final String named = memberText(row, "task", ContainerName.MAX_LENGTH, false);
            final String task = enrolledAs.getOrDefault(named, named);
            if (takesLogin) {
                final String owner = memberText(row, LOGIN, 32, false);
                if (!LOGIN_NAME.matcher(owner).matches()) {
                    throw badMembers("login is not a login name");
                }
                if (!ownLogin.equals(owner)) {
                    continue;
                }
            }
            if (containers == null) {
                // One host snapshot, only when a task of this account needs verification.
                containers =
                        new TaskInventory(context)
                                .tasks().stream()
                                        .filter(eachTask -> project.equals(eachTask.project()))
                                        .map(TaskInventory.Task::name)
                                        .collect(java.util.stream.Collectors.toSet());
            }
            if (!containers.contains(task)) {
                throw badMembers("task is not a recorded container of project " + project);
            }
            if (!tasks.add(task)) {
                throw badMembers("duplicate task container");
            }
            members.add(new RoomMembers.Member(id, shown, state, kind, task));
        }
        return new RoomMembers(room, members);
    }

    private static String memberText(Map<?, ?> row, String field, int longest, boolean empty)
            throws Refused {
        if (!(row.get(field) instanceof String text)
                || text.length() > longest
                || !empty && text.isBlank()
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw badMembers(
                    field + " must be a control-free string of at most " + longest + " characters");
        }
        return text;
    }

    private static Refused badMembers(String detail) {
        return new Refused("the transport answered members with an invalid " + detail, 76);
    }

    /** Even a successful display field must not carry a credential supplied on this invocation. */
    private static boolean echoesCredential(@Nullable Object value, Map<String, String> secrets) {
        return switch (value) {
            case String text -> !withheld(text, secrets).equals(text);
            case Map<?, ?> map ->
                    map.entrySet().stream()
                            .anyMatch(
                                    entry ->
                                            echoesCredential(entry.getKey(), secrets)
                                                    || echoesCredential(entry.getValue(), secrets));
            case List<?> list -> list.stream().anyMatch(each -> echoesCredential(each, secrets));
            case null -> false;
            default -> false;
        };
    }

    /**
     * Returns what {@code setup} said last of a project's conversation.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return It, or {@code null} when it was never set up.
     */
    @Nullable Conversation conversation(String scheme, String project) {
        final Path file = state(scheme, project);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> read) {
                return Conversation.of(read);
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: set up again.
        }
        return null;
    }

    private boolean owns(String project, String container) {
        if (!container.matches("porelo-[a-z0-9_-]+")) return false;
        final var identity = new TaskIdentities(context).lookup(container, null);
        if (identity instanceof TaskIdentities.Found found) {
            return project.equals(found.identity().project());
        }
        if (identity instanceof TaskIdentities.Refused refused
                && refused.reason() == TaskIdentities.Reason.MISSING) {
            final var owner = new TaskReservations(context).owner(container);
            return owner != null && project.equals(owner.project());
        }
        return false;
    }

    /**
     * Returns the secrets kept for a scope.
     *
     * @param scheme The transport.
     * @param scope {@code account}, {@code project/<p>} or {@code task/<t>}.
     * @return The variables, empty when nothing is kept or the vault is locked.
     */
    Map<String, String> secrets(String scheme, String scope) {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            // Locked: nothing read before may be used any longer.
            VaultCache.drop();
            return Map.of();
        }
        final VaultEntry entry;
        try {
            entry = VaultCache.read(context.vault(), opener.get()).get(entry(scheme, scope));
        } catch (org.fuin.porelo.vault.VaultException ex) {
            return Map.of();
        }
        if (entry == null) {
            return Map.of();
        }
        try {
            if (Json.parse(entry.value()) instanceof Map<?, ?> read) {
                final Map<String, String> secrets = new LinkedHashMap<>();
                read.forEach(
                        (name, value) -> secrets.put(String.valueOf(name), String.valueOf(value)));
                return secrets;
            }
        } catch (RuntimeException ex) {
            // A damaged entry holds nothing usable; the verb that made it is run again.
        }
        return Map.of();
    }

    /**
     * What a transport says of the settings a project gives it.
     *
     * @param refused What it would refuse wherever it runs.
     * @param warnings What only this machine lacks, or what is allowed but not advised.
     */
    record SettingsCheck(List<String> refused, List<String> warnings) {}

    /**
     * Asks a transport what it makes of the settings a project gives it, without the network, the
     * vault or any secret - so a draft is checked before it is committed rather than refused at the
     * first task start. Only a transport whose {@code describe} lists {@code settings} is asked.
     *
     * @param scheme The transport.
     * @param settings What the project says to it.
     * @return What it said, or {@code null} when it is not installed here or does not offer the
     *     check.
     * @throws Refused If it offers the check and could not answer it.
     */
    @org.jspecify.annotations.Nullable
    SettingsCheck settings(String scheme, Map<String, Object> settings) throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null
                || !TransportDescription.of(context.runner(), adapter).offers("settings")) {
            return null;
        }
        final Map<?, ?> said = run(scheme, settings, Map.of(), false, "settings");
        return new SettingsCheck(strings(said.get("refused")), strings(said.get("warnings")));
    }

    /**
     * Lets another machine into a project's conversation on this machine's server.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param machine The machine let in, by the name its transport goes by.
     * @param settings What the project says to this transport.
     * @return What the transport printed: {@code admission}, and {@code secrets} holding what the
     *     other machine needs and nobody else may see. Never kept here.
     * @throws Refused If the transport does not offer it, or refused.
     */
    Map<?, ?> admit(String scheme, String project, String machine, Map<String, Object> settings)
            throws Refused {
        offered(scheme, "admit");
        lendable(scheme, "admit");
        readable(scheme);
        return run(
                scheme,
                settings,
                given(scheme, project),
                false,
                "admit",
                "--project",
                project,
                "--machine",
                machine);
    }

    /**
     * Takes another machine off this machine's conversation server, asked through one project.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param machine The machine taken out.
     * @param settings What the project says to this transport.
     * @return What the transport printed: what it removed.
     * @throws Refused If the transport does not offer it, or refused.
     */
    Map<?, ?> dismiss(String scheme, String project, String machine, Map<String, Object> settings)
            throws Refused {
        offered(scheme, "dismiss");
        lendable(scheme, "dismiss");
        readable(scheme);
        return run(
                scheme,
                settings,
                given(scheme, project),
                false,
                "dismiss",
                "--project",
                project,
                "--machine",
                machine);
    }

    /** Refuses a verb the transport does not list: a verb it does not list is never run. */
    private void offered(String scheme, String verb) throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        if (!TransportDescription.of(context.runner(), adapter).offers(verb)) {
            throw new Refused("the " + scheme + " transport does not offer " + verb, 69);
        }
    }

    private static List<String> strings(@org.jspecify.annotations.Nullable Object listed) {
        return listed instanceof List<?> each
                ? each.stream().map(String::valueOf).toList()
                : List.of();
    }

    /**
     * Refuses before a verb runs when what it hands back could not be kept: a transport that made
     * an account whose token is then dropped has an account nobody can use or remove (found on
     * 2026-09-30, with a locked vault: the homeserver's administrator registered and lost).
     *
     * @param scheme The transport.
     * @throws Refused If the vault is locked or does not exist.
     */
    private void writable(String scheme) throws Refused {
        if (!context.vault().exists()) {
            throw new Refused(
                    "there is no vault, and what the "
                            + scheme
                            + " transport hands back has to be kept in"
                            + " one; 'porelo vault init' at the machine",
                    0);
        }
        if (context.opener().isEmpty()) {
            throw new Refused(
                    "the vault is locked, and what the "
                            + scheme
                            + " transport hands back has to be kept"
                            + " in it; 'porelo vault unlock' at the machine",
                    0);
        }
    }

    /** Refuses a verb that needs the secrets the vault keeps while there is none or it is shut. */
    private void readable(String scheme) throws Refused {
        if (!context.vault().exists()) {
            throw new Refused(
                    "there is no vault, and the "
                            + scheme
                            + " transport needs the secrets it keeps; 'porelo vault init' at the"
                            + " machine",
                    0);
        }
        if (context.opener().isEmpty()) {
            throw new Refused(
                    "the vault is locked, and the "
                            + scheme
                            + " transport needs the secrets it keeps; 'porelo vault unlock' at the"
                            + " machine",
                    0);
        }
    }

    /**
     * What a verb borrows, of what this transport names: only what is named for its own scheme. The
     * person's other credentials - {@code porelo vault put anthropic} - are simple names too, and
     * naming one in {@code describe} must not be enough to be handed it.
     */
    private static List<String> borrows(
            String scheme, TransportDescription described, String verb) {
        return described.lentOn(verb).stream()
                .filter(name -> lentEntry(name).startsWith(scheme + "-"))
                .toList();
    }

    /**
     * The vault entry a borrowed credential is looked for under: its name, as a person types it.
     */
    private static String lentEntry(String name) {
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith(LENT_PREFIX) ? lower.substring(LENT_PREFIX.length()) : lower)
                .replace('_', '-');
    }

    /**
     * Refuses before a verb's other checks when it borrows from a vault that is shut: those answer
     * for what the transport hands back, and this says what it needs first. The transport is asked
     * only when the vault is shut, so a transport that borrows nothing runs as it always did.
     */
    private void lendable(String scheme, String verb) throws Refused {
        if (!context.vault().exists() || context.opener().isPresent()) {
            return;
        }
        final Path adapter = transports.find(scheme);
        if (adapter != null
                && !borrows(scheme, TransportDescription.of(context.runner(), adapter), verb)
                        .isEmpty()) {
            throw shut(scheme, verb);
        }
    }

    private static Refused shut(String scheme, String verb) {
        return Refused.lendingShut(
                "the vault is shut, and the "
                        + scheme
                        + " transport borrows from it what it needs to "
                        + verb
                        + "; 'porelo vault unlock' at the machine");
    }

    /**
     * Reads what a verb borrows from the person's vault, for this one run: under each name's entry
     * for the server the settings name, and only there, since a project's file names that server
     * and a plain entry would go to any it chose. Not found, it goes without and the transport
     * refuses for itself. Handed back by no verb and kept by none: {@link #own} takes it out of
     * what a verb prints.
     *
     * @return Name to value, as the transport named them.
     * @throws Refused If the vault is shut or cannot be read: that is not the same as not holding
     *     it.
     */
    private Map<String, String> borrowed(
            String scheme,
            TransportDescription described,
            String verb,
            Map<String, Object> settings)
            throws Refused {
        final List<String> names = borrows(scheme, described, verb);
        if (names.isEmpty()) {
            return Map.of();
        }
        final Map<String, VaultEntry> held =
                context.readableCredentials().orElseThrow(() -> shut(scheme, verb));
        final Map<String, String> found = new LinkedHashMap<>();
        for (final String name : names) {
            final String entry = LentEntry.name(lentEntry(name), settings.get(LENT_SERVER));
            final VaultEntry lent = entry == null ? null : held.get(entry);
            if (lent != null) {
                found.put(name, lent.value());
            }
        }
        return found;
    }

    /**
     * What a verb printed, less anything holding what it borrowed: what it prints under {@code
     * secrets} and {@code account} is kept, and a credential that was only lent must not be, nested
     * or inside a longer value either.
     */
    private static Map<?, ?> own(Map<?, ?> said, Map<String, String> borrowed) {
        if (borrowed.isEmpty()) {
            return said;
        }
        final Map<Object, Object> kept = new LinkedHashMap<>(said);
        for (final String key : List.of("secrets", "account")) {
            if (said.get(key) instanceof Map<?, ?> held) {
                final Map<Object, Object> rest = new LinkedHashMap<>(held);
                rest.entrySet()
                        .removeIf(
                                each ->
                                        borrowed.containsKey(String.valueOf(each.getKey()))
                                                || borrowed.values().stream()
                                                        .anyMatch(
                                                                String.valueOf(each.getValue())
                                                                        ::contains));
                kept.put(key, rest);
            }
        }
        return kept;
    }

    private Map<?, ?> run(
            String scheme,
            Map<String, Object> settings,
            Map<String, String> kept,
            boolean loopbackOnly,
            String... verb)
            throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        return run(
                scheme,
                TransportDescription.of(context.runner(), adapter),
                settings,
                kept,
                loopbackOnly,
                verb);
    }

    /** Keeps invocation and judgment on one capability snapshot. */
    private Map<?, ?> run(
            String scheme,
            TransportDescription described,
            Map<String, Object> settings,
            Map<String, String> kept,
            boolean loopbackOnly,
            String... verb)
            throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        final List<String> arguments = new java.util.ArrayList<>();
        arguments.add(adapter.toString());
        arguments.addAll(List.of(verb));
        if (loopbackOnly) {
            arguments.add("--loopback-only");
        }
        // The person's login, to a transport that takes it: every account it makes on a shared
        // homeserver is suffixed by it, so two persons' tasks of the same name never share one.
        // Never what setup answered, and never a machine's name: a person reaches the host from any
        // machine, and their Porelo runs once, in their own account there.
        // admit and dismiss name the other machine themselves, so no login is added.
        if ("members".equals(verb[0])
                && (!described.offers("members") || !described.takes(TransportInput.INPUT))) {
            throw new Refused(
                    "the " + scheme + " transport must offer members with secrets on stdin", 78);
        }
        final Map<String, String> borrowed = borrowed(scheme, described, verb[0], settings);
        final Map<String, String> secrets = new LinkedHashMap<>(kept);
        secrets.putAll(borrowed);
        if (!"settings".equals(verb[0])
                && !"part".equals(verb[0])
                && !"admit".equals(verb[0])
                && !"dismiss".equals(verb[0])
                && described.takes(LOGIN)) {
            arguments.add("--" + LOGIN);
            arguments.add(login());
        }
        final CommandResult result;
        try {
            result =
                    context.runner()
                            .run(
                                    HelperEnvironment.chosen(
                                            TransportInput.handed(
                                                    Command.of(arguments),
                                                    described,
                                                    settings,
                                                    secrets)),
                                    line -> say(scheme, line, secrets));
        } catch (org.fuin.porelo.core.process.CommandException ex) {
            throw ex.withStandardError(withheld(StartStep.cleanLines(ex.standardError()), secrets));
        }
        if (!result.successful()) {
            // What it printed before failing may hold secrets it made; they are withheld too.
            // Cleaned first: a secret split by an escape sequence would be whole again once
            // cleaned.
            final Map<String, String> withheld = new LinkedHashMap<>(secrets);
            withheld.putAll(printed(result.standardOutput()));
            final String said =
                    withheld(StartStep.cleanLines(result.standardError()), withheld).strip();
            throw new Refused(
                    "the "
                            + scheme
                            + " transport could not "
                            + verb[0]
                            + " (exit "
                            + result.exitCode()
                            + ")"
                            + (said.isEmpty() ? "" : ": " + said),
                    result.exitCode(),
                    StartStep.cleanCause(said));
        }
        if ("members".equals(verb[0])
                && (result.standardOutput().length() > MEMBERS_ANSWER_BYTES
                        || result.standardOutput().getBytes(StandardCharsets.UTF_8).length
                                > MEMBERS_ANSWER_BYTES)) {
            throw badMembers("answer larger than " + MEMBERS_ANSWER_BYTES + " bytes");
        }
        try {
            if (Json.parse(result.standardOutput()) instanceof Map<?, ?> said) {
                if ("members".equals(verb[0]) && echoesCredential(said, secrets)) {
                    throw badMembers("answer containing a supplied credential");
                }
                return own(said, borrowed);
            }
        } catch (RuntimeException ex) {
            // Falls through to the refusal below; what it printed is never repeated - it may hold a
            // secret.
        }
        throw new Refused(
                "the "
                        + scheme
                        + " transport answered "
                        + verb[0]
                        + " with something that is not a"
                        + " JSON object",
                76);
    }

    /** What a transport that takes this account's login lists in its {@code takes}. */
    static final String LOGIN = "login";

    /** What a borrowed credential's name begins with, which its vault entry does not. */
    private static final String LENT_PREFIX = "porelo_";

    /** The setting whose host a person's entry is named for. */
    private static final String LENT_SERVER = "homeserver";

    /**
     * What a login looks like, as every other login rule of Porelo and the transports' own take it:
     * never an option, never blank, no dot and no upper case.
     */
    private static final java.util.regex.Pattern LOGIN_NAME =
            java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /**
     * Returns this account's login on this host, which is the person's, as a transport that takes
     * it is given it.
     *
     * @return The login.
     * @throws Refused If it is no name a transport takes.
     */
    static String login() throws Refused {
        return login(System.getProperty("user.name", ""));
    }

    /**
     * Refuses a person who would be shown as a task's agent: a line headed by that name would then
     * be read as the task's own words.
     *
     * @param person Whom the account is for.
     * @param display The name shown for them, or {@code null} for the transport's own.
     * @throws Refused If either begins with "Agent " or "agent-", in any case.
     */
    static void notAnAgent(final String person, final @Nullable String display) throws Refused {
        for (final String name : java.util.Arrays.asList(person, display)) {
            if (name != null && agentLike(name)) {
                throw new Refused(
                        "'"
                                + name.replaceAll("\\p{Cntrl}", "?")
                                + "' is no name a person may join under: a name beginning with"
                                + " 'Agent ' or 'agent-' is a task's in the room",
                        64);
            }
        }
    }

    /** Anything a room may show as nothing or as a space: separators, controls and formats. */
    private static final java.util.regex.Pattern INVISIBLE =
            java.util.regex.Pattern.compile("[\\s\\p{Z}\\p{Cc}\\p{Cf}]");

    /**
     * Returns whether a name would read as an agent's in the room: "agent" alone, or followed by a
     * space, a hyphen, an underscore or a dot, past anything invisible, in any case and width.
     */
    private static boolean agentLike(final String name) {
        final String seen =
                INVISIBLE
                        .matcher(
                                java.text.Normalizer.normalize(
                                        name, java.text.Normalizer.Form.NFKC))
                        .replaceAll(" ")
                        .stripLeading()
                        .toLowerCase(java.util.Locale.ROOT);
        return seen.equals("agent") || seen.matches("agent[ ._-].*");
    }

    /**
     * Returns a login as a transport is given it.
     *
     * @param login The account's login.
     * @return The login.
     * @throws Refused If it is no name a transport takes.
     */
    static String login(final String login) throws Refused {
        if (!LOGIN_NAME.matcher(login).matches()) {
            throw new Refused(
                    "'"
                            + login.replaceAll("\\p{Cntrl}", "?")
                            + "' is no login a transport takes: a lowercase letter, then lowercase"
                            + " letters, digits or '-', at most 32",
                    78);
        }
        return login;
    }

    /**
     * The secrets a verb printed on its standard output, under {@code secrets}, {@code account} or
     * {@code login}.
     */
    private static Map<String, String> printed(String stdout) {
        final Map<String, String> found = new LinkedHashMap<>();
        try {
            if (Json.parse(stdout) instanceof Map<?, ?> said) {
                for (final String key : List.of("secrets", "account", "login")) {
                    if (said.get(key) instanceof Map<?, ?> held) {
                        held.forEach(
                                (name, value) -> {
                                    if (value instanceof String text) {
                                        found.put(key + "." + name, text);
                                    }
                                });
                    }
                }
            }
        } catch (RuntimeException ex) {
            // Nothing readable was printed, so nothing printed can be withheld by its value.
        }
        return found;
    }

    private void say(final String scheme, final String line, final Map<String, String> secrets) {
        String safe = withheld(StartStep.cleanLines(line), secrets);
        safe = safe.strip();
        if (!safe.isEmpty()) {
            saying.accept(scheme + ": " + safe);
        }
    }

    /**
     * Takes every value a transport was handed out of what it said, line by line, as a key of many
     * lines comes back a line at a time, and in one pass, so a value is not looked for inside the
     * mark that replaced another.
     *
     * <p>A short value only where it stands alone: one short password is as much a secret as a long
     * one, and a value of one letter must not take that letter out of every word of the refusal.
     *
     * @param text What it said.
     * @param secrets What it was handed.
     * @return The text without them.
     */
    static String withheld(final String text, final Map<String, String> secrets) {
        final String values =
                secrets.values().stream()
                        .flatMap(String::lines)
                        .map(String::strip)
                        .filter(part -> !part.isEmpty())
                        .distinct()
                        .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                        .map(
                                part ->
                                        part.length() >= WHOLE_ANYWHERE
                                                ? java.util.regex.Pattern.quote(part)
                                                : "(?<![\\p{L}\\p{N}])"
                                                        + java.util.regex.Pattern.quote(part)
                                                        + "(?![\\p{L}\\p{N}])")
                        .collect(java.util.stream.Collectors.joining("|"));
        return values.isEmpty()
                ? text
                : java.util.regex.Pattern.compile(values).matcher(text).replaceAll("[withheld]");
    }

    private void keep(String scheme, String scope, Map<?, ?> secrets) throws Refused {
        final Map<String, String> kept = new LinkedHashMap<>();
        secrets.forEach((name, value) -> kept.put(String.valueOf(name), String.valueOf(value)));
        final VaultEntry entry =
                new VaultEntry(Json.write(kept), "transport-secrets", Map.of("transport", scheme));
        update(scheme, all -> all.put(entry(scheme, scope), entry));
    }

    private void forget(String scheme, String scope) throws Refused {
        update(scheme, all -> all.remove(entry(scheme, scope)));
    }

    private void update(String scheme, java.util.function.Consumer<Map<String, VaultEntry>> change)
            throws Refused {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            throw new Refused(
                    "the vault is locked, and what the "
                            + scheme
                            + " transport handed back goes into it;"
                            + " 'porelo vault unlock' at the machine",
                    0);
        }
        try {
            context.vault()
                    .update(
                            opener.get(),
                            all -> {
                                change.accept(all);
                                return all;
                            });
        } catch (org.fuin.porelo.vault.VaultException ex) {
            throw new Refused("the vault cannot be written: " + ex.getMessage(), 0);
        }
    }

    private void remember(String scheme, String project, Conversation conversation) throws Refused {
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put("conversation", conversation.conversation());
        written.put("reaches", conversation.reaches());
        written.put("login", conversation.login());
        written.put("admitted", conversation.admitted());
        written.put("address", conversation.address());
        written.put("room", conversation.room());
        try {
            final Path file = state(scheme, project);
            Directories.create(file.getParent());
            whole(file, Json.write(written));
        } catch (IOException ex) {
            throw new Refused(
                    "the conversation of " + project + " cannot be recorded: " + ex.getMessage(),
                    0);
        }
    }

    /**
     * Writes a file beside itself and renames it in: a pass reading it while a task's start writes
     * it would find it half written, and read it as no conversation and nobody's address.
     */
    private static void whole(final Path file, final String text) throws IOException {
        final Path staged =
                Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private Path state(String scheme, String project) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve(project + ".json");
    }

    /** The secrets a lifecycle verb about a project is given: the account's and the project's. */
    private Map<String, String> given(String scheme, String project) {
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        return given;
    }

    private static String entry(String scheme, String scope) {
        return TaskSecrets.TRANSPORT_PREFIX + scheme + "/" + scope;
    }
}
