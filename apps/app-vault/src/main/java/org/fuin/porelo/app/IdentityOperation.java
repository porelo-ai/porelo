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
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A card on its way to the organization, or waiting there for an owner: {@code
 * $XDG_STATE_HOME/porelo/identity/ops/<id>.json}, owner-only and written whole.
 *
 * <p>The record is what makes a change safe to interrupt: it holds the new card before the card is
 * kept or filed, so a second run of the same id answers this operation instead of making another
 * key, and goes on from where it stopped.
 *
 * @param id The operation's id.
 * @param action {@code create}, {@code rotate} or {@code revoke-lost}.
 * @param fingerprint The new key's fingerprint.
 * @param predecessor The old key's fingerprint, "" for a first card.
 * @param card The new card, as written.
 * @param done Whether the organization took it.
 * @param revision The revision it stands at once done, or {@code null}.
 * @param kept Whether the card is kept here as the person's own: a run stopped before it was is
 *     finished by the next run of the same id.
 * @param pushTried Whether a run may have pushed its card: written before each try, and cleared
 *     only by an answer that says nothing was pushed. What the store shows later proves nothing
 *     here, since a forge may serve an older state again.
 */
record IdentityOperation(
        String id,
        String action,
        String fingerprint,
        String predecessor,
        String card,
        boolean done,
        @Nullable String revision,
        boolean kept,
        boolean pushTried) {

    /** A first card, signed by its key. */
    static final String CREATE = "create";

    /** A rotation: the new card signed by both keys. */
    static final String ROTATE = "rotate";

    /** A lost key's replacement: the new card signed by the new key alone. */
    static final String REVOKE_LOST = "revoke-lost";

    /** An id a caller may choose. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** The longest record read. */
    private static final long LIMIT = 1024 * 1024;

    /**
     * A person who may accept a card.
     *
     * @param login Their login.
     * @param name What they are called, or {@code null} when no accepted card says it.
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
     * The same operation, the organization having taken its card.
     *
     * @param at The revision it stands at.
     * @return The operation.
     */
    IdentityOperation filedAt(final String at) {
        return new IdentityOperation(
                id, action, fingerprint, predecessor, card, true, at, kept, pushTried);
    }

    /**
     * The same operation, its card kept here.
     *
     * @return The operation.
     */
    IdentityOperation keptHere() {
        return new IdentityOperation(
                id, action, fingerprint, predecessor, card, done, revision, true, pushTried);
    }

    /**
     * The same operation, saying whether a run may have pushed its card.
     *
     * @param tried Whether one may have.
     * @return The operation.
     */
    IdentityOperation pushTried(final boolean tried) {
        return new IdentityOperation(
                id, action, fingerprint, predecessor, card, done, revision, kept, tried);
    }

    /**
     * The answer of a change: what section one of the commands asks of a write, and its keys.
     *
     * @param again Whether its id had already made it.
     * @return The answer, in the contract's order.
     */
    Map<String, @Nullable Object> answer(final boolean again) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", revision);
        answer.put("id", id);
        answer.put("again", again);
        answer.put("of", "organization");
        answer.put("action", action);
        answer.put("fingerprint", fingerprint);
        answer.put("predecessor", predecessor);
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
        record.put("revision", revision);
        record.put("kept", kept);
        record.put("push_tried", pushTried);
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
        if (!(map.get("done") instanceof Boolean done)) {
            throw new IllegalArgumentException("no done");
        }
        final String id = text(map, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not an id");
        }
        final String action = text(map, "action");
        if (!action.equals(CREATE) && !action.equals(ROTATE) && !action.equals(REVOKE_LOST)) {
            throw new IllegalArgumentException("no such action");
        }
        return new IdentityOperation(
                id,
                action,
                text(map, "fingerprint"),
                text(map, "predecessor"),
                text(map, "card"),
                done,
                map.get("revision") instanceof String at ? at : null,
                // A record written before this field was kept its card at once.
                !(map.get("kept") instanceof Boolean kept) || kept,
                Boolean.TRUE.equals(map.get("push_tried")));
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

    /**
     * Lets the operation go: it made nothing, or another took its place.
     *
     * @param context The machine.
     * @throws IOException When its record cannot be removed.
     */
    void forget(final PoreloContext context) throws IOException {
        Files.deleteIfExists(directory(context).resolve(id + ".json"));
    }

    /**
     * Reads the operations not done, sorted by id. One that does not read is no operation anything
     * could go on from, and is left where it is.
     *
     * @param context The machine.
     * @return The operations.
     * @throws IOException When the directory cannot be listed.
     */
    static List<IdentityOperation> pending(final PoreloContext context) throws IOException {
        final Path directory = directory(context);
        if (!IdentityStore.privateDirectoryFound(context.paths().xdg().state(), directory)) {
            return List.of();
        }
        final List<Path> records;
        try (var listed = Files.list(directory)) {
            records =
                    listed.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList();
        }
        final List<IdentityOperation> pending = new ArrayList<>();
        for (final Path record : records) {
            final String name = record.getFileName().toString();
            if (ID.matcher(name.substring(0, name.length() - ".json".length())).matches()) {
                try {
                    final IdentityOperation read =
                            read(context, name.substring(0, name.length() - ".json".length()));
                    if (read != null && !read.done()) {
                        pending.add(read);
                    }
                } catch (IOException ex) {
                    continue;
                }
            }
        }
        return pending;
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
     * Makes an id of the action and the day, {@code new-<yyyymmdd>-<n>}, {@code rot-<yyyymmdd>-<n>}
     * or {@code lost-<yyyymmdd>-<n>}, with the first number no operation kept here carries.
     *
     * @param context The machine.
     * @param action The action.
     * @param today The UTC day.
     * @return The id.
     */
    static String newId(final PoreloContext context, final String action, final LocalDate today) {
        final String prefix =
                switch (action) {
                            case CREATE -> "new-";
                            case ROTATE -> "rot-";
                            default -> "lost-";
                        }
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
