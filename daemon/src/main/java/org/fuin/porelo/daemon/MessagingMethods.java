package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for messages, their conversation and their moderation.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 */
final class MessagingMethods {

    private MessagingMethods() {}

    /** Reads the project's live room membership. */
    static Map<String, Object> roomMembers(PoreloContext context, Map<String, Object> parameters) {
        final String name = text(parameters, "project");
        final org.fuin.porelo.core.project.Project project;
        try {
            project = org.fuin.porelo.app.GateSupport.byName(context, name);
        } catch (RuntimeException ex) {
            throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
        }
        try {
            return org.fuin.porelo.app.RoomMembers.read(context, project).asMap();
        } catch (org.fuin.porelo.app.RoomMembers.Refused ex) {
            throw switch (ex.failure()) {
                case NO_TRANSPORT ->
                        new VarlinkException(
                                INTERFACE + ".NoTransport",
                                Map.of("project", name, "transport", ex.transport()));
                case NO_ROOM ->
                        new VarlinkException(INTERFACE + ".NoRoom", Map.of("project", name));
                case UNREACHABLE ->
                        new VarlinkException(
                                INTERFACE + ".HomeserverUnreachable",
                                Map.of(
                                        "project",
                                        name,
                                        "transport",
                                        ex.transport(),
                                        "message",
                                        String.valueOf(ex.getMessage()),
                                        "code",
                                        ex.code()));
                case REFUSED ->
                        new VarlinkException(
                                INTERFACE + ".ConversationRefused",
                                Map.of(
                                        "message",
                                        String.valueOf(ex.getMessage()),
                                        "code",
                                        ex.code()));
            };
        }
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
        server.method("MessageKey", (parameters, replies) -> replies.last(messageKey(context)));

        server.method(
                "RoomMembers",
                (parameters, replies) -> replies.last(roomMembers(context, parameters)));

        // The five message verbs. They are the same objects 'porelo talk' shows, through the same
        // classes, so an interface and a terminal cannot disagree about what a mailbox holds.
        server.method(
                "Peers",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.Mailbox mailbox = mailboxOf(context, parameters);
                    final org.fuin.porelo.core.project.Project project =
                            org.fuin.porelo.core.project.ProjectReader.read(
                                    projectFile(parameters, context));
                    final org.fuin.porelo.app.Moderation moderation =
                            org.fuin.porelo.app.Moderation.of(context, project);
                    final java.util.List<Map<String, Object>> peers = new java.util.ArrayList<>();
                    // With the project's other tasks, as the pass delivers to them: the contract
                    // says whom this task may address, and listing only the file's peers hid the
                    // tasks it reaches by name.
                    final org.fuin.porelo.core.project.Mail mail =
                            org.fuin.porelo.app.MessageRelease.peersOf(
                                    context, text(parameters, "task"));
                    // Counted the way the budget counts, so what a client shows is what the next
                    // pass enforces.
                    final org.fuin.porelo.app.MessageBudget budget =
                            new org.fuin.porelo.app.MessageBudget(
                                    new org.fuin.porelo.app.MessageRecord(mailbox), mail);
                    for (final org.fuin.porelo.core.project.Mail.Peer peer : mail.peers()) {
                        final org.fuin.porelo.app.Moderation.Peer state =
                                moderation.peer(peer.name());
                        final Map<String, Object> each = new LinkedHashMap<>();
                        each.put("name", peer.name());
                        each.put("address", peer.address());
                        each.put("trust", peer.trust());
                        each.put("perDay", peer.perDay());
                        each.put("sentToday", budget.sentToday(peer.name()));
                        each.put("receivedToday", budget.receivedToday(peer.name()));
                        each.put("mode", state.mode());
                        each.put("held", state.held());
                        peers.add(each);
                    }
                    replies.last(Map.of("peers", peers));
                });

        server.method(
                "Talk",
                (parameters, replies) -> {
                    if (!replies.streaming()) {
                        throw new VarlinkException(
                                INTERFACE + ".StreamRequired", Map.of("method", "Talk"));
                    }
                    // Every mailbox on the machine, from the records themselves: what happened to a
                    // message is written down as it happens, so following the record is following
                    // the conversation without a second copy of it that could disagree.
                    final Map<String, Integer> seen = new java.util.HashMap<>();
                    boolean first = true;
                    try {
                        while (replies.open()) {
                            for (final org.fuin.porelo.app.Mailbox mailbox :
                                    new org.fuin.porelo.app.Mailboxes(context.paths()).all()) {
                                final String task = mailbox.root().getFileName().toString();
                                final java.util.List<Map<String, Object>> lines =
                                        new org.fuin.porelo.app.MessageRecord(mailbox).entries();
                                if (first) {
                                    // Live only, as the contract says: what was there before a
                                    // client listened is not news.
                                    seen.put(task, lines.size());
                                    continue;
                                }
                                for (int i = seen.getOrDefault(task, 0); i < lines.size(); i++) {
                                    final Map<String, Object> line = lines.get(i);
                                    replies.more(
                                            Map.of(
                                                    "task",
                                                    task,
                                                    "at",
                                                    String.valueOf(line.getOrDefault("at", "")),
                                                    "event",
                                                    String.valueOf(line.getOrDefault("event", "")),
                                                    "message",
                                                    String.valueOf(
                                                            line.getOrDefault("message", "")),
                                                    "id",
                                                    String.valueOf(line.getOrDefault("id", "")),
                                                    "peer",
                                                    String.valueOf(line.getOrDefault("peer", "")),
                                                    "detail",
                                                    String.valueOf(
                                                            line.getOrDefault("detail", ""))));
                                }
                                seen.put(task, lines.size());
                            }
                            first = false;
                            Thread.sleep(TALK_INTERVAL.toMillis());
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });

        server.method(
                "Say",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.Mailbox mailbox = mailboxOf(context, parameters);
                    final String refused =
                            org.fuin.porelo.app.MessageSay.refusal(
                                    org.fuin.porelo.app.MessageRelease.peersOf(
                                            context, text(parameters, "task")),
                                    text(parameters, "peer"));
                    if (refused != null) {
                        throw new VarlinkException(
                                INTERFACE + ".NoSuchPeer",
                                Map.of("peer", text(parameters, "peer"), "detail", refused));
                    }
                    final String written =
                            new org.fuin.porelo.app.MessageSay()
                                    .write(
                                            mailbox,
                                            text(parameters, "peer"),
                                            text(parameters, "kind"),
                                            empty(parameters, "context"),
                                            text(parameters, "text"));
                    // Written, not sent: the next pass puts it through the filter like any other
                    // message, and saying "sent" here would promise something this step does not
                    // do.
                    replies.last(Map.of("message", written, "outcome", "WRITTEN"));
                });

        server.method(
                "Tell",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.Mailbox mailbox = mailboxOf(context, parameters);
                    final String said = text(parameters, "text").strip();
                    if (said.isEmpty()) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed", Map.of("message", "nothing to tell"));
                    }
                    final String told =
                            new org.fuin.porelo.app.PersonNote().tell(mailbox, said, null);
                    // A person's word to this one task: its agent at rest is told it is there
                    // (2026-10-04).
                    new org.fuin.porelo.app.AgentWake(context)
                            .announce(mailbox, mailbox.root().getFileName().toString(), null);
                    replies.last(Map.of("message", told));
                });

        server.method(
                "ReadHeld",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.MessageRead.Held held =
                            new org.fuin.porelo.app.MessageRead()
                                    .read(mailboxOf(context, parameters), text(parameters, "id"));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("outcome", held.outcome().name());
                    answer.put("standing", held.standing());
                    answer.put("message", held.message());
                    answer.put("id", held.id());
                    answer.put("role", held.role());
                    answer.put("peer", held.peer());
                    answer.put("kind", held.kind());
                    answer.put("at", held.at());
                    answer.put("text", held.text());
                    answer.put("reason", held.reason());
                    answer.put("direction", held.direction());
                    replies.last(answer);
                });

        server.method(
                "Held",
                (parameters, replies) -> {
                    // One task's mailbox when one is named - an unknown name is NoSuchTask, as
                    // everywhere - or every mailbox on the machine.
                    final java.util.List<org.fuin.porelo.app.Mailbox> mailboxes =
                            empty(parameters, "task") == null
                                    ? new org.fuin.porelo.app.Mailboxes(context.paths()).all()
                                    : java.util.List.of(mailboxOf(context, parameters));
                    final java.util.List<Map<String, Object>> messages =
                            new java.util.ArrayList<>();
                    for (final org.fuin.porelo.app.Mailbox mailbox : mailboxes) {
                        for (final org.fuin.porelo.app.MessageRead.Held held :
                                new org.fuin.porelo.app.MessageRead().list(mailbox)) {
                            final Map<String, Object> message = new LinkedHashMap<>();
                            message.put("task", mailbox.root().getFileName().toString());
                            message.put("standing", held.standing());
                            message.put("message", held.message());
                            message.put("id", held.id());
                            message.put("role", held.role());
                            message.put("peer", held.peer());
                            message.put("kind", held.kind());
                            message.put("at", held.at());
                            message.put("reason", held.reason());
                            message.put("direction", held.direction());
                            messages.add(message);
                        }
                    }
                    replies.last(Map.of("messages", messages));
                });

        server.method(
                "Release",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.MessageRelease.Result result =
                            new org.fuin.porelo.app.MessageRelease()
                                    .because(empty(parameters, "reason"))
                                    .enrolledIn(
                                            org.fuin.porelo.app.MessageRelease.enrolled(
                                                    context, text(parameters, "task")))
                                    .decide(
                                            mailboxOf(context, parameters),
                                            text(parameters, "id"),
                                            flag(parameters, "refuse"),
                                            org.fuin.porelo.app.MessageRelease.peersOf(
                                                    context, text(parameters, "task")));
                    final Map<String, Object> reply = new java.util.LinkedHashMap<>();
                    reply.put("outcome", result.outcome().name());
                    reply.put("message", result.message());
                    reply.put("id", result.id());
                    reply.put("detail", result.detail());
                    final org.fuin.porelo.app.MessageRelease.NotDeliverable code = result.code();
                    if (code != null) {
                        // Absent otherwise: a client that does not read it sees no change.
                        reply.put("code", code.name());
                    }
                    replies.last(reply);
                });

        // A person grants an authorization once, in a browser somewhere else. The stream says
        // "needed" with the link, then how it ended: no client polls a consent flow, so no question
        // gets two grants. A person joins a project's conversation with an account made for them.
        // Answered once: the login is in this reply and nowhere else, never kept.
        server.method(
                "SetUpMessages",
                (parameters, replies) -> replies.last(setUpMessages(context, parameters)));

        server.method(
                "JoinMessages",
                (parameters, replies) -> replies.last(joinMessages(context, parameters)));

        // Answered once: the admitted machine's secret is in this reply and nowhere else.
        server.method(
                "AdmitMachine",
                (parameters, replies) -> replies.last(admitMachine(context, parameters)));

        server.method(
                "DismissMachine",
                (parameters, replies) -> replies.last(dismissMachine(context, parameters)));

        server.method(
                "RemoveMessageMember",
                (parameters, replies) -> replies.last(removeMessageMember(context, parameters)));

        server.method(
                "MessageMembers",
                (parameters, replies) -> {
                    final org.fuin.porelo.core.project.Project project =
                            conversational(context, text(parameters, "project"));
                    replies.last(
                            Map.of(
                                    "members",
                                    org.fuin.porelo.app.Conversations.members(context, project)
                                            .entrySet()
                                            .stream()
                                            .map(
                                                    member ->
                                                            Map.of(
                                                                    "person",
                                                                    member.getKey(),
                                                                    "user",
                                                                    member.getValue()))
                                            .toList()));
                });

        server.method(
                "Moderate",
                (parameters, replies) -> {
                    // Per project, for every task of it: 'task' is no longer needed and is ignored.
                    final org.fuin.porelo.core.project.Project project =
                            org.fuin.porelo.core.project.ProjectReader.read(
                                    projectFile(parameters, context));
                    final org.fuin.porelo.app.Moderation.Change change =
                            org.fuin.porelo.app.Moderation.of(context, project)
                                    .set(
                                            text(parameters, "name"),
                                            parameters.get("held") instanceof Boolean held
                                                    ? held
                                                    : null,
                                            empty(parameters, "mode"),
                                            project);
                    if (change.peer() == null) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed", Map.of("message", change.refused()));
                    }
                    replies.last(
                            Map.of(
                                    "name",
                                    text(parameters, "name"),
                                    "mode",
                                    change.peer().mode(),
                                    "held",
                                    change.peer().held()));
                });
    }

    /**
     * Sets up a project's conversation, answering what the transport made.
     *
     * @param context The machine.
     * @param parameters {@code project}.
     * @return {@code messages}.
     */
    static Map<String, Object> setUpMessages(
            PoreloContext context, Map<String, Object> parameters) {
        final org.fuin.porelo.core.project.Project project =
                talking(context, text(parameters, "project"));
        try {
            return Map.of("messages", org.fuin.porelo.app.Conversations.setUp(context, project));
        } catch (org.fuin.porelo.app.Conversations.Refused ex) {
            throw new VarlinkException(
                    INTERFACE + ".ConversationRefused",
                    Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
        }
    }

    /**
     * Joins a person to a project's conversation, answering the login made for them once.
     *
     * @param context The machine.
     * @param parameters {@code project}, {@code person}, {@code reset}.
     * @return {@code transport}, {@code login}, {@code shown} and {@code invited}.
     */
    static Map<String, Object> joinMessages(PoreloContext context, Map<String, Object> parameters) {
        final org.fuin.porelo.core.project.Project project =
                talking(context, text(parameters, "project"));
        final Map<?, ?> said;
        try {
            said =
                    org.fuin.porelo.app.Conversations.join(
                            context,
                            project,
                            text(parameters, "person"),
                            flag(parameters, "reset"));
        } catch (org.fuin.porelo.app.Conversations.Refused ex) {
            if (ex.exists() != null) {
                throw new VarlinkException(
                        INTERFACE + ".MemberExists",
                        Map.of("person", text(parameters, "person"), "user", ex.exists()));
            }
            throw new VarlinkException(
                    INTERFACE + ".ConversationRefused",
                    Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
        }
        final Map<String, String> login = new LinkedHashMap<>();
        if (said.get("login") instanceof Map<?, ?> given) {
            given.forEach((key, value) -> login.put(String.valueOf(key), String.valueOf(value)));
        }
        return Map.of(
                "transport",
                project.mail().conversations().iterator().next(),
                "login",
                login,
                "shown",
                said.get("shown") instanceof String shown ? shown : "",
                // An account the person has already is invited, not made: no password to show.
                "invited",
                Boolean.TRUE.equals(said.get("invited")));
    }

    /** Returns the removal outcome or the same transport refusal as joining. */
    static Map<String, Object> removeMessageMember(
            PoreloContext context, Map<String, Object> parameters) {
        final org.fuin.porelo.core.project.Project project =
                talking(context, text(parameters, "project"));
        final org.fuin.porelo.app.Conversations.Removal removed;
        try {
            removed =
                    org.fuin.porelo.app.Conversations.remove(
                            context, project, text(parameters, "person"));
        } catch (org.fuin.porelo.app.Conversations.Refused ex) {
            if (ex.missing() != null) {
                throw new VarlinkException(
                        INTERFACE + ".NoSuchMember", Map.of("person", ex.missing()));
            }
            throw new VarlinkException(
                    INTERFACE + ".ConversationRefused",
                    Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
        }
        return Map.of("user", removed.user(), "kept", removed.kept(), "why", removed.why());
    }

    /**
     * Lets another machine in, answering its admission and its secrets apart, once.
     *
     * @param context The machine.
     * @param parameters {@code project}, {@code machine}.
     * @return {@code admission} and {@code secrets}.
     */
    static Map<String, Object> admitMachine(PoreloContext context, Map<String, Object> parameters) {
        final org.fuin.porelo.core.project.Project project =
                talking(context, text(parameters, "project"));
        final Map<?, ?> said;
        try {
            said =
                    org.fuin.porelo.app.MachineTransports.admit(
                            context, project, text(parameters, "machine"));
        } catch (org.fuin.porelo.app.Conversations.Refused ex) {
            throw refused(ex);
        }
        return Map.of(
                "admission",
                strings(said.get("admission")),
                "secrets",
                strings(said.get("secrets")));
    }

    /**
     * Takes another machine out.
     *
     * @param context The machine.
     * @param parameters {@code project}, {@code machine}.
     * @return {@code removed}.
     */
    static Map<String, Object> dismissMachine(
            PoreloContext context, Map<String, Object> parameters) {
        final org.fuin.porelo.core.project.Project project =
                talking(context, text(parameters, "project"));
        final Map<?, ?> said;
        try {
            said =
                    org.fuin.porelo.app.MachineTransports.dismiss(
                            context, project, text(parameters, "machine"));
        } catch (org.fuin.porelo.app.Conversations.Refused ex) {
            throw refused(ex);
        }
        return Map.of("removed", list(said.get("removed")));
    }

    /**
     * Returns the project a conversation, a member or a machine is set up for, refusing {@code
     * default} by name before its file is read or any transport runs: it talks to nobody, and the
     * answer names a project repository as the way, as every surface says it.
     */
    private static org.fuin.porelo.core.project.Project talking(
            PoreloContext context, String name) {
        if (org.fuin.porelo.app.DefaultProject.is(name)) {
            throw new VarlinkException(
                    INTERFACE + ".ConversationRefused",
                    Map.of(
                            "message",
                            org.fuin.porelo.app.DefaultProject.NO_CONVERSATION,
                            "code",
                            78));
        }
        return conversational(context, name);
    }

    private static VarlinkException refused(org.fuin.porelo.app.Conversations.Refused ex) {
        return new VarlinkException(
                INTERFACE + ".ConversationRefused",
                Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
    }

    /** A map of strings from a parameter or an answer, empty when absent; other values dropped. */
    private static Map<String, String> strings(@org.jspecify.annotations.Nullable Object given) {
        final Map<String, String> found = new LinkedHashMap<>();
        if (given instanceof Map<?, ?> map) {
            map.forEach(
                    (key, value) -> {
                        if (value instanceof String text) {
                            found.put(String.valueOf(key), text);
                        }
                    });
        }
        return found;
    }

    /** A list of strings from a parameter or an answer, empty when absent. */
    private static java.util.List<String> list(@org.jspecify.annotations.Nullable Object given) {
        return given instanceof java.util.List<?> each
                ? each.stream().map(String::valueOf).toList()
                : java.util.List.of();
    }
}
