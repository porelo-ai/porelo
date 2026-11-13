package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What a transport says about itself.
 *
 * <p>Asked rather than configured: an adapter is a package that arrives without Porelo being
 * rebuilt, so anything Porelo needs to know about it has to come from the adapter. Everything here
 * is a claim, and the two the host acts on are checked against what actually happens - a transport
 * that says it polls and fetches nothing has fetched nothing, and one that says it attests an owner
 * and does not write the file has its messages held.
 *
 * @param scheme The part before the colon in a peer's address.
 * @param polls Whether {@code poll} is worth calling at all.
 * @param attests What it proves about a message it hands over. Empty for one that carries bytes.
 * @param confirms How far it can confirm a message: {@code handover}, {@code receipt} or {@code
 *     read}.
 * @param lifecycle The lifecycle verbs it offers - {@code setup}, {@code enroll}, {@code retire},
 *     {@code join}, {@code part}, {@code members} - empty for a transport that keeps no
 *     conversation of its own.
 * @param waitsOn What its setup waits on, or empty.
 * @param takes The options it takes beyond the ones every transport does - {@code login} for the
 *     person's login on its lifecycle verbs. An option it does not list is never passed: an unknown
 *     one is its 64. {@link TransportInput#INPUT} when it takes its secrets on its standard input.
 * @param lent The credentials it borrows from the person's vault, by name, with the verbs it
 *     borrows each for. Porelo keeps no list of verbs: only this says which.
 */
public record TransportDescription(
        String scheme,
        boolean polls,
        List<String> attests,
        String confirms,
        List<String> lifecycle,
        List<String> takes,
        String waitsOn,
        Map<String, List<String>> lent) {

    /** What is assumed of a transport that cannot be asked: the least, so nothing is granted. */
    public static final TransportDescription UNKNOWN =
            new TransportDescription("", false, List.of(), "", List.of(), List.of(), "");

    /** What a credential a transport borrows is called: a variable's name, and nothing else. */
    private static final Pattern LENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * Constructor for a transport that borrows nothing from the person's vault.
     *
     * @param scheme The part before the colon in a peer's address.
     * @param polls Whether {@code poll} is worth calling at all.
     * @param attests What it proves about a message it hands over.
     * @param confirms How far it can confirm a message.
     * @param lifecycle The lifecycle verbs it offers.
     * @param takes The options it takes beyond the common ones.
     * @param waitsOn What its setup waits on, or empty.
     */
    public TransportDescription(
            String scheme,
            boolean polls,
            List<String> attests,
            String confirms,
            List<String> lifecycle,
            List<String> takes,
            String waitsOn) {
        this(scheme, polls, attests, confirms, lifecycle, takes, waitsOn, Map.of());
    }

    /**
     * Constructor for a transport with no lifecycle, the shape before transports kept a
     * conversation.
     *
     * @param scheme The part before the colon in a peer's address.
     * @param polls Whether {@code poll} is worth calling at all.
     * @param attests What it proves about a message it hands over.
     */
    public TransportDescription(String scheme, boolean polls, List<String> attests) {
        this(scheme, polls, attests, "", List.of(), List.of(), "");
    }

    /**
     * Constructor for a transport that takes no options beyond the common ones.
     *
     * @param scheme The part before the colon in a peer's address.
     * @param polls Whether {@code poll} is worth calling at all.
     * @param attests What it proves about a message it hands over.
     * @param confirms How far it can confirm a message.
     * @param lifecycle The lifecycle verbs it offers.
     */
    public TransportDescription(
            String scheme,
            boolean polls,
            List<String> attests,
            String confirms,
            List<String> lifecycle) {
        this(scheme, polls, attests, confirms, lifecycle, List.of(), "");
    }

    /**
     * Asks a transport to describe itself.
     *
     * @param runner How commands are run.
     * @param adapter The transport's executable.
     * @return What it said, or {@link #UNKNOWN} when it could not be asked or did not answer with
     *     an object. An unreadable description grants no capabilities.
     */
    public static TransportDescription of(final CommandRunner runner, final Path adapter) {
        try {
            return required(runner, adapter);
        } catch (final RuntimeException ex) {
            return UNKNOWN;
        }
    }

    /** Cleanup and live reads must distinguish execution failure from an unsupported verb. */
    static TransportDescription required(final CommandRunner runner, final Path adapter) {
        final CommandResult result;
        try {
            result =
                    TransportVerbs.of(runner)
                            .reaching(List.of(), true)
                            .run(Command.of(adapter.toString(), "describe"));
        } catch (final org.fuin.porelo.core.process.CommandException ex) {
            throw ex;
        } catch (final RuntimeException ex) {
            return UNKNOWN;
        }
        if (!result.successful()) {
            throw new org.fuin.porelo.core.process.CommandException(result.command(), result);
        }
        try {
            if (Json.parse(result.standardOutput()) instanceof Map<?, ?> said) {
                final List<String> attests =
                        said.get("attests") instanceof List<?> listed
                                ? listed.stream().map(String::valueOf).toList()
                                : List.of();
                final List<String> lifecycle =
                        said.get("lifecycle") instanceof List<?> verbs
                                ? verbs.stream().map(String::valueOf).toList()
                                : List.of();
                final List<String> takes =
                        said.get("takes") instanceof List<?> options
                                ? new java.util.ArrayList<>(
                                        options.stream().map(String::valueOf).toList())
                                : new java.util.ArrayList<>();
                // Announced as its own field: it shows a person a line of Porelo's, passed as
                // --shown.
                if (Boolean.TRUE.equals(said.get(TransportSend.SHOWN))) {
                    takes.add(TransportSend.SHOWN);
                }
                // Its secrets on its input rather than in its environment.
                if (TransportInput.ON_INPUT.equals(said.get("secrets"))) {
                    takes.add(TransportInput.INPUT);
                }
                // And a person's words in the room, and a task's direct chats.
                for (final String flag :
                        List.of(
                                TransportConversations.PERSONS,
                                TransportConversations.DIRECT,
                                TransportSend.MENTION,
                                TransportConversations.WAITS,
                                TransportLifecycle.DISPLAY)) {
                    if (Boolean.TRUE.equals(said.get(flag))) {
                        takes.add(flag);
                    }
                }
                return new TransportDescription(
                        said.get("scheme") instanceof String scheme ? scheme : "",
                        Boolean.TRUE.equals(said.get("poll")),
                        attests,
                        said.get("confirms") instanceof String confirms ? confirms : "",
                        lifecycle,
                        takes,
                        said.get("waitsOn") instanceof String wait
                                ? StartStep.cleanWaitingOn(wait)
                                : "",
                        lent(said.get("credentials")));
            }
        } catch (final RuntimeException ex) {
            return UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Reads which credentials a transport borrows. An entry with no {@code lent}, one that is not a
     * list of strings, or one whose name is no variable's lends nothing.
     */
    private static Map<String, List<String>> lent(final @Nullable Object credentials) {
        final Map<String, List<String>> lent = new LinkedHashMap<>();
        if (credentials instanceof List<?> listed) {
            for (final Object each : listed) {
                if (each instanceof Map<?, ?> credential
                        && credential.get("name") instanceof String name
                        && LENT_NAME.matcher(name).matches()
                        && credential.get("lent") instanceof List<?> verbs
                        && verbs.stream().allMatch(String.class::isInstance)) {
                    lent.put(name, verbs.stream().map(String.class::cast).toList());
                }
            }
        }
        return lent;
    }

    /**
     * Says what this transport borrows from the person's vault for a verb. Only a transport that
     * takes its secrets on its input is lent anything: in its environment, another process of the
     * account would read the person's credential too.
     *
     * @param verb The verb.
     * @return The names it gave; empty when it borrows nothing for this verb.
     */
    List<String> lentOn(final String verb) {
        if (!takes(TransportInput.INPUT)) {
            return List.of();
        }
        return lent.entrySet().stream()
                .filter(credential -> credential.getValue().contains(verb))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Says whether this transport promised to prove who owned a message.
     *
     * @return {@code true} when {@code attests} lists it.
     */
    public boolean attestsOwner() {
        return attests.contains(OwnerAttestation.ATTESTS);
    }

    /**
     * Says whether this transport keeps a conversation of its own - a room - that Porelo sets up
     * and enrolls tasks into.
     *
     * @return {@code true} when it offers {@code setup}.
     */
    public boolean keepsConversation() {
        return lifecycle.contains("setup");
    }

    /**
     * Says whether this transport offers a lifecycle verb.
     *
     * @param verb A lifecycle verb, including {@code members} for a live room read.
     * @return {@code true} when it listed it.
     */
    public boolean offers(String verb) {
        return lifecycle.contains(verb);
    }

    /**
     * Says whether this transport takes an option beyond the common ones.
     *
     * @param option {@code login}.
     * @return {@code true} when it listed it.
     */
    public boolean takes(String option) {
        return takes.contains(option);
    }
}
