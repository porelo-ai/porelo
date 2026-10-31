package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What a person wrote in a project's conversation, as a transport handed it over, made a message
 * for the agents.
 *
 * <p>The transport states what the conversation states and reads no meaning into the text: who
 * wrote it, the accounts it named with {@code @agentname} or answered, whether it was said in a
 * direct chat, and the words. This turns the accounts into the names agents address - a task's
 * short name, a person's plain name - and the words into the message an agent reads, {@code
 * ROLE_USER} from that person.
 *
 * @param readers The tasks that read it: every task of the project, or in a direct chat the one it
 *     is with.
 * @param person Who wrote it, by the plain name they joined with.
 * @param message The message, as the agents get it.
 * @param refused Why nobody gets it, or {@code null}.
 * @param answerTo The person's account to answer for a helper mention, or {@code null}.
 * @param learned The account a person let in by the room's maker writes under, the first time they
 *     are taken by their host account, so it is kept for answering them; {@code null} for anybody
 *     already known.
 */
record PersonsWords(
        List<TransportConversations.Member> readers,
        @Nullable String person,
        byte @Nullable [] message,
        @Nullable String refused,
        @Nullable String answerTo,
        @Nullable String learned) {

    PersonsWords(
            final List<TransportConversations.Member> readers,
            final @Nullable String person,
            final byte @Nullable [] message,
            final @Nullable String refused,
            final @Nullable String answerTo) {
        this(readers, person, message, refused, answerTo, null);
    }

    /**
     * Reads a person's words as a transport handed them over.
     *
     * @param file The transport's file.
     * @param members The project's tasks on this machine.
     * @param accounts Each task's account, by container.
     * @param people Each person who joined, to their account.
     * @return Whom it is for and what they read, or why nobody gets it.
     * @throws IOException Reading failed.
     */
    static PersonsWords read(
            final Path file,
            final List<TransportConversations.Member> members,
            final Map<String, String> accounts,
            final Map<String, String> people)
            throws IOException {
        return read(file, members, accounts, people, Set.of());
    }

    /**
     * Reads a person's words, taking a person of the applied access file by the host account the
     * transport names them by.
     *
     * @param file The transport's file.
     * @param members The project's tasks on this machine.
     * @param accounts Each task's account, by container.
     * @param people Each person who joined, to their account.
     * @param persons The applied access file's persons, where the room's maker keeps them in the
     *     room as persons; empty otherwise.
     * @return Whom it is for and what they read, or why nobody gets it.
     * @throws IOException Reading failed.
     */
    static PersonsWords read(
            final Path file,
            final List<TransportConversations.Member> members,
            final Map<String, String> accounts,
            final Map<String, String> people,
            final Set<String> persons)
            throws IOException {
        return read(file, members, accounts, people, persons, Set.of());
    }

    /**
     * Reads a person's words, taking a person by their host account only from an account that is no
     * task's and not this machine's.
     *
     * @param file The transport's file.
     * @param members The project's tasks on this machine.
     * @param accounts Each task's account, by container.
     * @param people Each person who joined, to their account.
     * @param persons The applied access file's persons, where the room's maker keeps them in the
     *     room as persons; empty otherwise.
     * @param ours This machine's own accounts in the conversation.
     * @return Whom it is for and what they read, or why nobody gets it.
     * @throws IOException Reading failed.
     */
    static PersonsWords read(
            final Path file,
            final List<TransportConversations.Member> members,
            final Map<String, String> accounts,
            final Map<String, String> people,
            final Set<String> persons,
            final Set<String> ours)
            throws IOException {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            return refused("it is not a person's message that can be read");
        }
        if (!(parsed instanceof Map<?, ?> said)
                || !(said.get("sender") instanceof String sender)
                || !(said.get("body") instanceof String body)) {
            return refused("it names no sender or no words");
        }
        String person =
                people.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(sender))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
        String learned = null;
        // Let in by the room's maker, never through join: the transport names their host
        // account, and only a person of the applied access file is taken by it. One who
        // joined under another account of the room is not taken over.
        // A task's account or this machine's is never a person's, whatever the transport says.
        if (person == null
                && said.get("account") instanceof String account
                && persons.contains(account)
                && !people.containsKey(account)
                && !sender.isBlank()
                && !accounts.containsValue(sender)
                && !ours.contains(sender)) {
            person = account;
            learned = sender;
        }
        if (person == null) {
            // Only somebody let into the project's conversation: the room is how a person reaches
            // its agents, and an account the transport admitted is not yet somebody the project let
            // in.
            return refused("it is from " + sender + ", who is not in the project's conversation");
        }
        if (Boolean.TRUE.equals(said.get("helpers"))) {
            return new PersonsWords(List.of(), person, null, null, sender, learned);
        }
        final List<String> to = new ArrayList<>();
        final List<TransportConversations.Member> named = new ArrayList<>();
        if (said.get("to") instanceof List<?> accountsNamed) {
            for (final Object account : accountsNamed) {
                for (final TransportConversations.Member member : members) {
                    if (String.valueOf(account).equals(accounts.get(member.container()))) {
                        named.add(member);
                        final String task =
                                org.fuin.porelo.runtime.ContainerName.taskIn(
                                        member.project(), member.container());
                        to.add(task != null ? task : member.container());
                    }
                }
                people.forEach(
                        (who, theirs) -> {
                            if (theirs.equals(String.valueOf(account)) && !to.contains(who)) {
                                to.add(who);
                            }
                        });
            }
        }
        final boolean direct = Boolean.TRUE.equals(said.get("direct"));
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", person);
        metadata.put("to", to);
        // Where it was said, so it is answered there: a room message in the room, a direct one in
        // the direct chat (the operator in walk 10, 2026-10-04: "a private answer only when he was
        // written to privately").
        metadata.put("via", direct ? VIA_DIRECT : VIA_ROOM);
        if (direct) {
            metadata.put("direct", true);
        }
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put(
                "messageId",
                messageId(
                        said.get("eventId") instanceof String event
                                        && !event.isEmpty()
                                        && !event.equals("$")
                                ? event
                                : file.getFileName().toString()));
        message.put("role", MessageSay.ROLE);
        message.put(
                "parts",
                List.of(new LinkedHashMap<>(Map.of("text", body, "mediaType", "text/plain"))));
        message.put("metadata", metadata);
        return new PersonsWords(
                direct ? named : members,
                person,
                Json.write(message).getBytes(StandardCharsets.UTF_8),
                null,
                null,
                learned);
    }

    /** Said in the project's conversation, where everybody reads it. */
    static final String VIA_ROOM = "room";

    /** Said in a direct chat with the one task. */
    static final String VIA_DIRECT = "direct";

    private static String messageId(final String event) {
        final String bare = event.startsWith("$") ? event.substring(1) : event;
        // The filter's default maximum ID length is 128, including the person- prefix.
        if (event.startsWith("$")
                && !bare.isEmpty()
                && bare.matches("[A-Za-z0-9._:-]+")
                && !bare.startsWith("h-")
                && "person-".length() + bare.length() <= 128) {
            return "person-" + bare;
        }
        // Unprefixed values hash too, so foo cannot alias the readable Matrix event $foo.
        try {
            return "person-h-"
                    + java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(event.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for person message IDs", ex);
        }
    }

    private static PersonsWords refused(final String why) {
        return new PersonsWords(List.of(), null, null, why, null);
    }
}
