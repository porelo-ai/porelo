package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A change of the person's own key, kept until every place took it: {@code
 * $XDG_STATE_HOME/porelo/identity/ops/<id>.json}, owner-only and written whole.
 *
 * <p>The record is what makes a rotation safe to interrupt: it holds the new card before the card
 * is kept or sent anywhere, so a second run of the same id answers this operation instead of making
 * another key, and a retry sends this card again to the places it has not reached.
 *
 * @param id The operation's id.
 * @param action {@code rotate} or {@code revoke-lost}.
 * @param fingerprint The new key's fingerprint.
 * @param predecessor The old key's fingerprint.
 * @param card The new card, as written.
 * @param done Whether every place took it.
 * @param published Each place, as last tried.
 * @param kept Whether the card is kept here as the person's own: a run stopped before it was is
 *     finished by the next run of the same id.
 */
record IdentityOperation(
        String id,
        String action,
        String fingerprint,
        String predecessor,
        String card,
        boolean done,
        List<Place> published,
        boolean kept) {

    /** A rotation: the new card signed by both keys. */
    static final String ROTATE = "rotate";

    /** A lost key's replacement: the new card signed by the new key alone. */
    static final String REVOKE_LOST = "revoke-lost";

    /** An id a caller may choose. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** The longest record read. */
    private static final long LIMIT = 1024 * 1024;

    /** Compact constructor: the places are copied and cannot be changed afterwards. */
    IdentityOperation {
        published = List.copyOf(published);
    }

    /**
     * A person the place waits for.
     *
     * @param login Their login.
     * @param name What they are called, or {@code null} when no card a registry accepted says it.
     * @param email How to reach them, or {@code null} when their card names nothing.
     */
    record Contact(String login, @Nullable String name, @Nullable String email) {

        /**
         * A contact as {@link IdentityFacts#contact} answers it.
         *
         * @param answer {@code login}, {@code name} and {@code email}.
         * @return The contact.
         */
        static Contact of(final Map<String, @Nullable Object> answer) {
            return new Contact(
                    String.valueOf(answer.get("login")),
                    answer.get("name") instanceof String name ? name : null,
                    answer.get("email") instanceof String email ? email : null);
        }

        Map<String, @Nullable Object> answer() {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("login", login);
            answer.put("name", name);
            answer.put("email", email);
            return answer;
        }
    }

    /**
     * A place the new card goes to.
     *
     * @param where {@code registry}, {@code project} or {@code host}.
     * @param name The registry's origin, the project's or the host's name.
     * @param status {@code planned}, {@code done}, {@code pending} or {@code refused}.
     * @param why Why it is not done, or {@code null}.
     * @param whoMay Who may accept it there; none when the person's own key does.
     */
    record Place(
            String where, String name, String status, @Nullable String why, List<Contact> whoMay) {

        /**
         * Compact constructor: the contacts are copied and cannot be changed afterwards, and no URL
         * in why keeps its user part, which is printed and kept.
         */
        Place {
            why = why == null ? null : RegistryOrigin.withoutUserinfoIn(why);
            whoMay = List.copyOf(whoMay);
        }

        /** The field naming the place, by what it is. */
        private String nameField() {
            return switch (where) {
                case "registry" -> "address";
                case "project" -> "project";
                default -> "host";
            };
        }

        Place with(final String newStatus, final @Nullable String newWhy) {
            return new Place(where, name, newStatus, newWhy, whoMay);
        }

        Map<String, @Nullable Object> answer() {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("where", where);
            answer.put(nameField(), name);
            answer.put("status", status);
            answer.put("why", why);
            answer.put("who_may", whoMay.stream().map(Contact::answer).toList());
            return answer;
        }
    }

    /**
     * Whether every place took the card.
     *
     * @param places The places.
     * @return {@code true} when each is {@code done}.
     */
    static boolean allDone(final List<Place> places) {
        return places.stream().allMatch(place -> place.status().equals("done"));
    }

    /**
     * The same operation, its places as tried now.
     *
     * @param places The places.
     * @return The operation.
     */
    IdentityOperation publishedTo(final List<Place> places) {
        return new IdentityOperation(
                id, action, fingerprint, predecessor, card, allDone(places), places, kept);
    }

    /**
     * The same operation, its card kept here.
     *
     * @return The operation.
     */
    IdentityOperation keptHere() {
        return new IdentityOperation(
                id, action, fingerprint, predecessor, card, done, published, true);
    }

    /** The answer of rotate, revoke, publish and status, in the contract's order. */
    Map<String, @Nullable Object> answer() {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("id", id);
        answer.put("action", action);
        answer.put("fingerprint", fingerprint);
        answer.put("predecessor", predecessor);
        answer.put("published", published.stream().map(Place::answer).toList());
        return answer;
    }

    private String toJson() {
        final Map<String, @Nullable Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("action", action);
        record.put("fingerprint", fingerprint);
        record.put("predecessor", predecessor);
        record.put("card", card);
        record.put("done", done);
        record.put("published", published.stream().map(Place::answer).toList());
        record.put("kept", kept);
        return Json.write(record);
    }

    /**
     * The directory the operations are kept in.
     *
     * @param context The machine.
     * @return Its path, whether or not it exists.
     */
    static Path directory(final PoreloContext context) {
        return context.paths().xdg().state().resolve("identity").resolve("ops");
    }

    /**
     * Reads the operation of an id.
     *
     * @param context The machine.
     * @param id A valid id.
     * @return The operation, or {@code null} when none is kept under it.
     * @throws IOException When it is kept but cannot be read as an operation; naming the file,
     *     never quoting it.
     */
    static @Nullable IdentityOperation read(final PoreloContext context, final String id)
            throws IOException {
        final Path file = directory(context).resolve(id + ".json");
        if (!IdentityStore.privateDirectoryFound(context.paths().xdg().state(), directory(context))
                || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT) {
            throw new IOException(file + " is not a regular file of an operation's size");
        }
        try {
            final IdentityOperation read =
                    of(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            // A record copied under another name would answer, and be kept, as that one.
            if (!read.id().equals(id)) {
                throw new IOException(file + " is the record of another operation");
            }
            return read;
        } catch (RuntimeException ex) {
            throw new IOException(file + " does not read as an identity operation");
        }
    }

    private static IdentityOperation of(final @Nullable Object read) {
        if (!(read instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("not an object");
        }
        final List<Place> places = new ArrayList<>();
        if (!(map.get("published") instanceof List<?> listed)) {
            throw new IllegalArgumentException("no places");
        }
        for (final Object each : listed) {
            if (!(each instanceof Map<?, ?> place)) {
                throw new IllegalArgumentException("a place is not an object");
            }
            final String where = text(place, "where");
            final String field =
                    switch (where) {
                        case "registry" -> "address";
                        case "project" -> "project";
                        case "host" -> "host";
                        default -> throw new IllegalArgumentException("no such place");
                    };
            final List<Contact> contacts = new ArrayList<>();
            if (!(place.get("who_may") instanceof List<?> who)) {
                throw new IllegalArgumentException("no contacts");
            }
            for (final Object contact : who) {
                if (!(contact instanceof Map<?, ?> person)) {
                    throw new IllegalArgumentException("a contact is not an object");
                }
                contacts.add(
                        new Contact(
                                text(person, "login"),
                                person.get("name") instanceof String name ? name : null,
                                person.get("email") instanceof String email ? email : null));
            }
            places.add(
                    new Place(
                            where,
                            text(place, field),
                            text(place, "status"),
                            place.get("why") instanceof String why ? why : null,
                            contacts));
        }
        if (!(map.get("done") instanceof Boolean done)) {
            throw new IllegalArgumentException("no done");
        }
        final String id = text(map, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not an id");
        }
        final String action = text(map, "action");
        if (!action.equals(ROTATE) && !action.equals(REVOKE_LOST)) {
            throw new IllegalArgumentException("no such action");
        }
        return new IdentityOperation(
                id,
                action,
                text(map, "fingerprint"),
                text(map, "predecessor"),
                text(map, "card"),
                done,
                places,
                // A record written before this field was kept its card at once.
                !(map.get("kept") instanceof Boolean kept) || kept);
    }

    private static String text(final Map<?, ?> map, final String key) {
        if (map.get(key) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException("no " + key);
    }

    /**
     * Keeps the operation in place of the one kept under its id.
     *
     * @param context The machine.
     * @throws IOException When it cannot be written.
     */
    void keep(final PoreloContext context) throws IOException {
        final Path staged = staged(context);
        try {
            Files.move(
                    staged,
                    directory(context).resolve(id + ".json"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Keeps a new operation under its id, only when none is kept there yet: two runs at once never
     * both start one under the same id.
     *
     * @param context The machine.
     * @return {@code false} when an operation is kept under the id already, which is left alone.
     * @throws IOException When it cannot be written.
     */
    boolean keepNew(final PoreloContext context) throws IOException {
        final Path staged = staged(context);
        try {
            // A link is made whole or not at all, and never over a file that is there.
            Files.createLink(directory(context).resolve(id + ".json"), staged);
            return true;
        } catch (FileAlreadyExistsException ex) {
            return false;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private Path staged(final PoreloContext context) throws IOException {
        final Path directory =
                IdentityStore.privateDirectory(context.paths().xdg().state(), directory(context));
        final Path staged =
                Files.createTempFile(
                        directory,
                        ".",
                        ".json.staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(staged, toJson(), StandardCharsets.UTF_8);
        return staged;
    }

    /**
     * Makes an id of the action and the day, {@code rot-<yyyymmdd>-<n>} or {@code
     * lost-<yyyymmdd>-<n>}, with the first number no operation kept here carries.
     *
     * @param context The machine.
     * @param action The action.
     * @param today The UTC day.
     * @return The id.
     */
    static String newId(final PoreloContext context, final String action, final LocalDate today) {
        final String prefix =
                (action.equals(ROTATE) ? "rot-" : "lost-")
                        + today.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "-";
        for (int number = 1; ; number++) {
            final String id = prefix + number;
            if (!Files.exists(
                    directory(context).resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS)) {
                return id;
            }
        }
    }
}
