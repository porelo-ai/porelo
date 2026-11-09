package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * Takes a task into its project's conversations when it starts, and out of them when it is removed.
 *
 * <p>For every transport a peer of the project reaches through the project's own conversation - a
 * {@code <scheme>:} address - the transport's {@code setup} is run (it keeps what exists) and then
 * its {@code enroll} for the task, <strong>before anything of the task exists</strong>: a task
 * whose messages could not reach anybody is refused as a credential nobody can reach is, not
 * discovered when its first message stalls.
 *
 * <p><strong>An offline project's messages never leave the machine</strong> (decided 2026-09-29):
 * what {@code setup} says the conversation reaches must be this machine's loopback, or the start is
 * refused. The rule is Porelo's; what a transport reaches is the transport's to say.
 */
final class TaskConversations {

    private final TransportLifecycle lifecycle;

    private final PoreloContext context;

    /** What a start exits with when a task cannot take part. */
    private static final int NOT_ENROLLED = 69;

    private int refusedWith = NOT_ENROLLED;

    /**
     * Constructor.
     *
     * @param context Where the vault, the state and the adapters are.
     */
    TaskConversations(PoreloContext context) {
        this.context = context;
        this.lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
    }

    /**
     * Takes a task into its project's conversations.
     *
     * @param project The task's project.
     * @param container The task.
     * @return Why it cannot be, in a person's words, or {@code null} when it is in every one.
     */
    @Nullable String enroll(Project project, String container) {
        return enroll(project, container, null, new StartSteps(step -> {}));
    }

    /**
     * Takes a task into its project's conversations, shown in each room by its agent's name.
     *
     * @param project The task's project.
     * @param container The task.
     * @param agent The task's agent: the one selected at a start, the recorded one on a resume;
     *     {@code null} where it has none, which shows it by its short name.
     * @param steps Where the start's steps are said.
     * @return Why it cannot be, in a person's words, or {@code null} when it is in every one.
     */
    @Nullable String enroll(
            Project project, String container, @Nullable String agent, StartSteps steps) {
        final java.util.function.Consumer<String> before =
                lifecycle.saying(line -> steps.waiting(StartStep.Step.CONVERSATION, line));
        try {
            return enrolling(project, container, agent, steps);
        } finally {
            lifecycle.saying(before);
        }
    }

    private @Nullable String enrolling(
            Project project, String container, @Nullable String agent, StartSteps steps) {
        // 'default' has no room as its normal case: a task there talks to nobody, whatever a file
        // of that name says and whatever this machine has.
        if (DefaultProject.is(project.name())) {
            return null;
        }
        for (final String scheme : project.mail().conversations()) {
            steps.conversation(scheme);
            final java.nio.file.Path adapter =
                    context.paths().messaging().transportDirectory().find(scheme);
            final String waitsOn =
                    adapter == null
                            ? ""
                            : TransportDescription.of(context.runner(), adapter).waitsOn();
            steps.waiting(
                    StartStep.Step.CONVERSATION,
                    waitsOn.isEmpty() ? "the " + scheme + " transport" : waitsOn);
            try {
                final Map<String, Object> settings = settings(project, scheme);
                final boolean offline = project.securityClass() == SecurityClass.OFFLINE;
                final TransportLifecycle.Conversation conversation =
                        lifecycle.setup(scheme, project.name(), settings, offline);
                if (!conversation.admitted()) {
                    final String refusal = notAdmitted(scheme, conversation);
                    steps.failed(StartStep.Step.CONVERSATION, refusal);
                    return refusal;
                }
                if (project.securityClass() == SecurityClass.OFFLINE) {
                    final List<String> away =
                            conversation.reaches().stream()
                                    .filter(host -> !loopback(host))
                                    .toList();
                    if (!away.isEmpty()) {
                        final String refusal =
                                "an offline project's messages never leave this machine, and its "
                                        + scheme
                                        + " conversation reaches "
                                        + String.join(", ", away)
                                        + "; give it a conversation on this machine's loopback";
                        steps.failed(StartStep.Step.CONVERSATION, refusal);
                        return refusal;
                    }
                }
                lifecycle.enroll(scheme, project.name(), container, agent, settings, offline);
                steps.done(StartStep.Step.CONVERSATION);
            } catch (org.fuin.porelo.core.process.CommandException ex) {
                steps.failed(StartStep.Step.CONVERSATION, ex.standardError());
                throw ex;
            } catch (TransportLifecycle.Refused ex) {
                steps.failed(StartStep.Step.CONVERSATION, ex.cause());
                refusedWith = ex.vaultShut() ? ex.code() : NOT_ENROLLED;
                return "the task cannot take part in the project's "
                        + scheme
                        + " conversation: "
                        + ex.getMessage();
            }
        }
        return null;
    }

    /**
     * Returns what a start exits with when {@link #enroll} refused it.
     *
     * @return 77 when the vault could not lend what a transport borrows; 69 otherwise.
     */
    int refusedWith() {
        return refusedWith;
    }

    /**
     * Takes a task out of its project's conversations. Never stops the removal: what cannot be done
     * is said.
     *
     * @param project The task's project.
     * @param container The task.
     * @param err Where a failure is said.
     */
    void retire(Project project, String container, PrintWriter err) {
        for (final String scheme : project.mail().conversations()) {
            try {
                lifecycle.retire(scheme, project.name(), container, settings(project, scheme));
            } catch (TransportLifecycle.Refused ex) {
                // Its secrets stay in the vault, so the account can still be retired: nothing is
                // lost that a transport which answers again could not finish.
                err.println(
                        "porelo: "
                                + container
                                + " is removed, and its account in the "
                                + scheme
                                + " conversation is not: "
                                + ex.getMessage());
                err.flush();
            }
        }
    }

    /**
     * Takes a removed task out of its project's conversations, finding the project by the task's
     * name.
     *
     * @param context Where the projects are.
     * @param container The task.
     */
    void retire(PoreloContext context, String container) {
        retire(context, container, null);
    }

    /**
     * Takes a removed task out of its project's conversations.
     *
     * <p><strong>By the project the task's own records name</strong>, when they still do. Found by
     * the name alone, the first project whose name the container began with was taken, so project
     * {@code a} caught the tasks of {@code a-b}; without a record, the longest such name is the
     * likeliest.
     *
     * @param context Where the projects are.
     * @param container The task.
     * @param project The task's project as its records name it, or {@code null} when they are gone.
     */
    void retire(PoreloContext context, String container, @Nullable String project) {
        final Project found = projectOf(context, container, project);
        if (found != null) {
            retire(found, container, new PrintWriter(System.err, true));
        }
    }

    /**
     * Takes a task a resume brought back into its project's rooms, where one holds no name for it
     * yet; a task every room has a name for is left as it is, and no transport is asked.
     *
     * @param context Where the projects are.
     * @param container The task.
     * @param project The task's project as its records name it, or {@code null} when they do not.
     * @param agent The task's agent as its start recorded it, or {@code null}.
     * @param err Where what could not be done is said; the resume goes on either way.
     */
    void rejoin(
            PoreloContext context,
            String container,
            @Nullable String project,
            @Nullable String agent,
            PrintWriter err) {
        final Project found = projectOf(context, container, project);
        // A room that holds the task under the agent's form it gave before is asked again, so the
        // task takes its own name there.
        if (found == null
                || found.mail().conversations().stream()
                        .allMatch(
                                scheme -> {
                                    final String shown =
                                            lifecycle.displays(scheme, found.name()).get(container);
                                    return shown != null
                                            && !TransportLifecycle.earlierForm(
                                                    found.name(), container, agent, shown);
                                })) {
            return;
        }
        try {
            final String outside = enroll(found, container, agent, new StartSteps(step -> {}));
            if (outside != null) {
                err.println("porelo: " + outside);
                err.flush();
            }
        } catch (RuntimeException ex) {
            // Back all the same: its workspace is in it, and the next start enrolls it again.
            err.println("porelo: " + container + " is back outside its room: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Returns a task's project: the one its records name, when they still do. Found by the name
     * alone, the first project whose name the container began with was taken, so project {@code a}
     * caught the tasks of {@code a-b}; without a record, the longest such name is the likeliest.
     */
    private static @Nullable Project projectOf(
            PoreloContext context, String container, @Nullable String project) {
        final java.util.Optional<ProjectInventory.Summary> summary =
                new ProjectInventory(context)
                        .projects().stream()
                                .filter(each -> each.file() != null)
                                .filter(
                                        each ->
                                                project != null
                                                        ? each.name().equals(project)
                                                        : container.startsWith(
                                                                "porelo-" + each.name() + "-"))
                                .max(
                                        java.util.Comparator.comparingInt(
                                                each -> each.name().length()));
        if (summary.isEmpty()) {
            return null;
        }
        try {
            return GateSupport.project(
                    java.nio.file.Path.of(
                            java.util.Objects.requireNonNull(
                                    summary.get().file(), "filtered for a file above")));
        } catch (RuntimeException ex) {
            // A project file that cannot be read any more: its conversations cannot be named
            // either.
            return null;
        }
    }

    /**
     * Lets a person into a project's conversation, and returns what they are shown - once: nothing
     * of it but who joined as whom is kept.
     *
     * @param context Where the state is.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @return What the transport printed: {@code login} for an interface and {@code shown} for a
     *     terminal.
     * @throws TransportLifecycle.Refused If the project has no conversation, or the transport
     *     refused.
     */
    Map<?, ?> join(PoreloContext context, Project project, String person, boolean reset)
            throws TransportLifecycle.Refused {
        return join(context, project, person, reset, false);
    }

    /**
     * Lets a person into the project's conversation, asking for an access token instead of a
     * password when asked to.
     *
     * @param context Where the state is.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing account new credentials.
     * @param token Whether the transport answers the access token in {@code secrets}, and no
     *     password anywhere.
     * @return What the transport printed.
     * @throws TransportLifecycle.Refused If the person has joined already or the transport refused.
     */
    Map<?, ?> join(
            PoreloContext context, Project project, String person, boolean reset, boolean token)
            throws TransportLifecycle.Refused {
        return join(context, project, person, reset, token, null);
    }

    /**
     * Lets a person into the project's conversation under the name the conversation shows for them.
     *
     * @param context Where the state is.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing account new credentials.
     * @param token Whether the transport answers the access token in {@code secrets}, and no
     *     password anywhere.
     * @param display The name shown for the person, or {@code null} for the transport's own.
     * @return What the transport printed.
     * @throws TransportLifecycle.Refused If the person has joined already or the transport refused.
     */
    Map<?, ?> join(
            PoreloContext context,
            Project project,
            String person,
            boolean reset,
            boolean token,
            @Nullable String display)
            throws TransportLifecycle.Refused {
        // Before setup, too: nobody else checks the person's name.
        TransportLifecycle.notAnAgent(person, display);
        TransportLifecycle.login(person);
        try {
            ProjectMembers.requireAdmitted(context, project.name(), person);
        } catch (final ProjectMembers.Withheld ex) {
            throw new TransportLifecycle.Refused(String.valueOf(ex.getMessage()), 69);
        }
        final String scheme = scheme(project);
        if (!reset && members(context, project).containsKey(person)) {
            throw new TransportLifecycle.Refused(
                    "'"
                            + person
                            + "' has joined "
                            + project.name()
                            + " already, as "
                            + members(context, project).get(person)
                            + "; --reset gives them a new password",
                    17);
        }
        final boolean offline = project.securityClass() == SecurityClass.OFFLINE;
        lifecycle.setup(scheme, project.name(), settings(project, scheme), offline);
        final Map<?, ?> said =
                lifecycle.join(
                        scheme,
                        project.name(),
                        person,
                        reset,
                        settings(project, scheme),
                        offline,
                        token,
                        display);
        final String user =
                said.get("login") instanceof Map<?, ?> login && login.get("user") != null
                        ? String.valueOf(login.get("user"))
                        : person;
        try {
            member(context, project, scheme, person, user);
        } catch (java.io.IOException ex) {
            // The account exists and the person has their login; only the list of who joined misses
            // them.
        }
        return said;
    }

    /**
     * Removes a listed person only after the transport succeeds.
     *
     * @param context Where the state is.
     * @param project The project.
     * @param person Whom to remove.
     * @return What the transport removed or kept.
     * @throws Conversations.Refused If the person is absent or removal could not be completed.
     */
    Conversations.Removal part(PoreloContext context, Project project, String person)
            throws Conversations.Refused {
        if (!members(context, project).containsKey(person)) {
            throw new Conversations.Refused(
                    "'"
                            + person
                            + "' has not joined "
                            + project.name()
                            + "; 'porelo talk members "
                            + project.name()
                            + "' lists who has",
                    0,
                    null,
                    person);
        }
        final String scheme = scheme(project);
        final Map<?, ?> said;
        try {
            said =
                    lifecycle.part(
                            scheme,
                            project.name(),
                            person,
                            settings(project, scheme),
                            project.securityClass() == SecurityClass.OFFLINE);
        } catch (TransportLifecycle.Refused ex) {
            throw new Conversations.Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        }
        final Conversations.Removal removed;
        if (said.get("removed") instanceof String user) {
            removed = new Conversations.Removal(user, false, "");
        } else if (said.get("kept") instanceof String user
                && said.get("why") instanceof String why) {
            removed = new Conversations.Removal(user, true, why);
        } else if (said.isEmpty()) {
            removed = new Conversations.Removal("", false, "");
        } else {
            throw new Conversations.Refused(
                    "the " + scheme + " transport answered part with no removal outcome", 76, null);
        }
        try {
            member(context, project, scheme, person, null);
        } catch (java.io.IOException ex) {
            throw new Conversations.Refused(
                    "the members of "
                            + project.name()
                            + " could not be recorded: "
                            + ex.getMessage(),
                    0,
                    null);
        }
        return removed;
    }

    private void member(
            PoreloContext context,
            Project project,
            String scheme,
            String person,
            @Nullable String user)
            throws java.io.IOException {
        final java.nio.file.Path file = membersFile(context, project, scheme);
        FileLocks.holding(
                file.resolveSibling("." + file.getFileName() + ".lock"),
                () -> {
                    // Join and part share the lock so neither can restore a member from an old
                    // list.
                    final Map<String, String> members =
                            new java.util.LinkedHashMap<>(members(context, project));
                    if (user == null) {
                        members.remove(person);
                    } else {
                        members.put(person, user);
                    }
                    final java.nio.file.Path staged =
                            java.nio.file.Files.createTempFile(
                                    file.getParent(), "." + file.getFileName(), ".tmp");
                    try {
                        java.nio.file.Files.writeString(
                                staged,
                                org.fuin.porelo.wire.Json.write(members),
                                java.nio.charset.StandardCharsets.UTF_8);
                        java.nio.file.Files.move(
                                staged,
                                file,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        java.nio.file.Files.deleteIfExists(staged);
                    }
                    return Boolean.TRUE;
                });
    }

    /**
     * Returns who is heard in a project's conversation: admitted joins and each admitted person of
     * the project's members taken by their host account.
     *
     * @param context Where the state is.
     * @param project The project.
     * @return Person to their account.
     */
    Map<String, String> heard(PoreloContext context, Project project) {
        if (project.mail().conversations().isEmpty()) {
            return Map.of();
        }
        return lifecycle.heard(scheme(project), project.name());
    }

    /**
     * Returns who has joined a project's conversation, by the name join was given.
     *
     * @param context Where the state is.
     * @param project The project.
     * @return Person to the account the transport made, empty when nobody joined or it has none.
     */
    Map<String, String> members(PoreloContext context, Project project) {
        if (project.mail().conversations().isEmpty()) {
            return Map.of();
        }
        final java.nio.file.Path file = membersFile(context, project, scheme(project));
        if (!java.nio.file.Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.porelo.wire.Json.parse(
                            java.nio.file.Files.readString(
                                    file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> read) {
                final Map<String, String> members = new java.util.LinkedHashMap<>();
                read.forEach((who, user) -> members.put(String.valueOf(who), String.valueOf(user)));
                return members;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // Unreadable: nobody is listed; the accounts themselves are the transport's.
        }
        return Map.of();
    }

    /**
     * Returns the project's conversation as {@code setup} described it last.
     *
     * @param project The project.
     * @return The transport, the conversation and what it reaches, or {@code null} when it has none
     *     or it was never set up.
     */
    @Nullable Described describe(Project project) {
        if (project.mail().conversations().isEmpty()) {
            return null;
        }
        final String scheme = scheme(project);
        final TransportLifecycle.Conversation conversation =
                lifecycle.conversation(scheme, project.name());
        return new Described(
                scheme,
                conversation == null
                        ? new TransportLifecycle.Conversation("", List.of(), "", false, "", "")
                        : conversation,
                conversation != null);
    }

    /**
     * Sets a project's conversation up on this machine, or confirms it, without a task: so a
     * machine being bound to a project learns the name it goes by there, and whether it is let in
     * yet.
     *
     * @param project The project.
     * @return What it is now.
     * @throws TransportLifecycle.Refused If the project has no conversation, or the transport
     *     refused.
     */
    Described setUp(Project project) throws TransportLifecycle.Refused {
        final String scheme = scheme(project);
        final TransportLifecycle.Conversation conversation =
                lifecycle.setup(
                        scheme,
                        project.name(),
                        settings(project, scheme),
                        project.securityClass() == SecurityClass.OFFLINE);
        return new Described(scheme, conversation, true);
    }

    /**
     * Says why a task cannot take part while this machine is not let into the conversation.
     *
     * @param scheme The transport.
     * @param conversation What setup said.
     * @return In a person's words.
     */
    static String notAdmitted(String scheme, TransportLifecycle.Conversation conversation) {
        return "this machine is not in the project's "
                + scheme
                + " conversation yet: a person there lets its account"
                + (conversation.address().isEmpty() ? "" : " " + conversation.address())
                + " in"
                + (conversation.room().isEmpty() ? "" : " to " + conversation.room())
                + ", and the next task start takes it up";
    }

    /**
     * A project's conversation, as an interface is told of it.
     *
     * @param transport The transport it is on.
     * @param said What {@code setup} said of it last, empty before it ran.
     * @param setUp Whether it was set up on this machine.
     */
    record Described(String transport, TransportLifecycle.Conversation said, boolean setUp) {

        String conversation() {
            return said.conversation();
        }

        List<String> reaches() {
            return said.reaches();
        }
    }

    private static String scheme(Project project) throws IllegalStateException {
        if (project.mail().conversations().isEmpty()) {
            throw new IllegalStateException(
                    "'"
                            + project.name()
                            + "' has no conversation: no peer is reached"
                            + " through a transport's '<scheme>:' address");
        }
        return project.mail().conversations().iterator().next();
    }

    private static java.nio.file.Path membersFile(
            PoreloContext context, Project project, String scheme) {
        return context.paths()
                .xdg()
                .state()
                .resolve("transport")
                .resolve(scheme)
                .resolve("members")
                .resolve(project.name() + ".json");
    }

    private Map<String, Object> settings(Project project, String scheme)
            throws TransportLifecycle.Refused {
        try {
            return ProjectMembers.settings(context, project, scheme);
        } catch (ProjectMembers.Withheld ex) {
            throw new TransportLifecycle.Refused(String.valueOf(ex.getMessage()), 78);
        }
    }

    /**
     * Returns whether a host is this machine's loopback.
     *
     * @param host As the transport said it.
     * @return true for {@code 127.0.0.1}, {@code ::1}, {@code [::1]} and {@code localhost}, with or
     *     without a port.
     */
    static boolean loopback(String host) {
        return loopbackHost(host) != null;
    }

    /**
     * Returns the loopback host a reach names, without its brackets and port.
     *
     * @param reach As the transport said it.
     * @return {@code 127.0.0.1}, {@code ::1} or {@code localhost}; {@code null} for any other host,
     *     and for a bracketed one followed by anything but a port.
     */
    static @Nullable String loopbackHost(String reach) {
        final String bare;
        if (reach.startsWith("[")) {
            final int close = reach.indexOf(']');
            // "[localhost].example.org" is another host: only a port may follow the bracket.
            if (close < 0 || !reach.substring(close + 1).matches("(:[0-9]+)?")) {
                return null;
            }
            bare = reach.substring(1, close);
        } else {
            bare =
                    reach.contains(":") && reach.indexOf(':') == reach.lastIndexOf(':')
                            ? reach.substring(0, reach.indexOf(':'))
                            : reach;
        }
        return "127.0.0.1".equals(bare) || "::1".equals(bare) || "localhost".equals(bare)
                ? bare
                : null;
    }
}
