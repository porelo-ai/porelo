package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PrivateDirectory;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Keeps the original leave across a lost push reply or interrupted local cleanup. */
record OrgDeparture(
        String repository,
        String login,
        String fingerprint,
        String base,
        String id,
        List<String> left,
        List<Map<String, String>> stillListed,
        boolean pushed) {

    OrgDeparture {
        left = List.copyOf(left);
        stillListed = stillListed.stream().map(Map::copyOf).toList();
    }

    OrgDeparture afterPush() {
        return new OrgDeparture(repository, login, fingerprint, base, id, left, stillListed, true);
    }

    Map<String, @Nullable Object> answer() {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put(
                "organization",
                org.fuin.porelo.organization.gittree.ForgeAddress.of(repository).organization());
        answer.put("login", login);
        answer.put("left", left);
        answer.put("stillListed", stillListed);
        answer.put("forgotten", true);
        return answer;
    }

    private static Path directory(final PoreloContext machine) {
        return machine.paths().xdg().state().resolve("organization-leave");
    }

    void write(final PoreloContext machine) throws IOException {
        final Path directory =
                PrivateDirectory.make(machine.paths().xdg().state(), directory(machine));
        final Map<String, @Nullable Object> record = new LinkedHashMap<>(answer());
        record.remove("organization");
        record.remove("forgotten");
        record.put("repository", repository);
        record.put("fingerprint", fingerprint);
        record.put("base", base);
        record.put("id", id);
        record.put("pushed", pushed);
        final Path temporary =
                Files.createTempFile(
                        directory,
                        "leave",
                        ".json",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, Json.write(record));
            Files.move(
                    temporary,
                    directory.resolve("receipt.json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static @Nullable OrgDeparture read(final PoreloContext machine) throws IOException {
        final Path directory = directory(machine);
        if (!PrivateDirectory.found(machine.paths().xdg().state(), directory)) return null;
        final Path file = directory.resolve("receipt.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > 1024 * 1024) {
            throw new IOException(
                    "the unfinished leave's receipt is not a regular file of its size");
        }
        try {
            if (!(Json.parse(Files.readString(file)) instanceof Map<?, ?> record)
                    || !(record.get("pushed") instanceof Boolean pushed)) {
                throw new IllegalArgumentException("not a departure");
            }
            final List<String> left = new ArrayList<>();
            for (final Object role : list(record, "left")) {
                if (!(role instanceof String named))
                    throw new IllegalArgumentException("not a role");
                left.add(named);
            }
            final List<Map<String, String>> rows = new ArrayList<>();
            for (final Object row : list(record, "stillListed")) {
                if (!(row instanceof Map<?, ?> listing))
                    throw new IllegalArgumentException("not a listing");
                rows.add(Map.of("file", string(listing, "file"), "role", string(listing, "role")));
            }
            return new OrgDeparture(
                    string(record, "repository"),
                    string(record, "login"),
                    string(record, "fingerprint"),
                    string(record, "base"),
                    string(record, "id"),
                    left,
                    rows,
                    pushed);
        } catch (RuntimeException ex) {
            throw new IOException("the unfinished leave's receipt does not read", ex);
        }
    }

    private static String string(final Map<?, ?> record, final String field) {
        if (record.get(field) instanceof String text) return text;
        throw new IllegalArgumentException("missing " + field);
    }

    private static List<?> list(final Map<?, ?> record, final String field) {
        if (record.get(field) instanceof List<?> values) return values;
        throw new IllegalArgumentException("missing " + field);
    }

    void check(final PoreloContext machine) throws IOException {
        final String configured = OrgAccess.repository(machine).orElse(null);
        if ((!pushed && configured == null)
                || (configured != null && !repository.equals(configured))) {
            throw changed();
        }
        final Path config = machine.paths().xdg().config();
        if (PrivateDirectory.found(config, config.resolve("identity"))) {
            final Path card = config.resolve("identity/card");
            if (Files.exists(card, LinkOption.NOFOLLOW_LINKS)) {
                final PersonCard own = OrgWriting.ownCard(machine);
                if (!login.equals(own.login()) || !fingerprint.equals(own.fingerprint()))
                    throw changed();
            }
        }
    }

    private static Refused changed() {
        return new Refused(
                Refused.MOVED_ON,
                "the organization or identity here changed while leaving; the new settings are kept",
                null);
    }

    void forget(final PoreloContext machine) throws IOException {
        check(machine);
        if (!pushed) throw new IOException("the departure has not been confirmed");
        final Path config = machine.paths().xdg().config();
        Files.deleteIfExists(config.resolve("organization.yml"));
        Files.deleteIfExists(config.resolve("checkouts.yml"));
        for (final String name :
                List.of("organization", "organization-host", "organization-projects", "identity")) {
            deleteTree(machine.paths().xdg().state().resolve(name));
        }
        if (PrivateDirectory.found(config, config.resolve("identity"))) {
            for (final String name : List.of("card.id", "key.pub", "card")) {
                Files.deleteIfExists(config.resolve("identity").resolve(name));
            }
        }
        remove(machine);
    }

    static void remove(final PoreloContext machine) throws IOException {
        final Path directory = directory(machine);
        // Listing follows a link, so the directory is judged as porelo's own before anything goes.
        if (!PrivateDirectory.found(machine.paths().xdg().state(), directory)) return;
        final Path receipt = directory.resolve("receipt.json");
        // Keep recovery possible until orphaned staging files have also been removed.
        try (var paths = Files.list(directory)) {
            for (final Path path : paths.filter(path -> !path.equals(receipt)).toList()) {
                deleteTree(path);
            }
        }
        Files.deleteIfExists(receipt);
        Files.delete(directory);
    }

    private static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
