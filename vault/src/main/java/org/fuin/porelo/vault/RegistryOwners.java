package org.fuin.porelo.vault;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * A registry's {@code owners.yml}: its owners, and the admins of each host, each by login and key.
 *
 * <p>A refusal names the file, line and column and never quotes what is written there: the file
 * comes from anybody with write access to the registry, and its text reaches a terminal.
 *
 * @param owners The registry's owners, in the file's order; at least one.
 * @param hostAdmins The admins of each host, by host label.
 */
record RegistryOwners(List<Entry> owners, Map<String, List<Entry>> hostAdmins) {
    RegistryOwners {
        owners = List.copyOf(owners);
        final Map<String, List<Entry>> copy = new LinkedHashMap<>();
        hostAdmins.forEach((host, admins) -> copy.put(host, List.copyOf(admins)));
        hostAdmins = Collections.unmodifiableMap(copy);
    }

    /**
     * One owner or host admin.
     *
     * @param login The forge login.
     * @param key Their key, {@code ssh-ed25519 AAAA...}.
     * @param backup Whether this is an offline backup key.
     */
    record Entry(String login, String key, boolean backup) {
        String fingerprint() {
            return PersonCard.fingerprint(key);
        }
    }

    /** A login as a person card accepts it. */
    static final Pattern LOGIN = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** A host label as the registry verifier accepts it. */
    static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final Pattern KEY = Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]+={0,2}");

    /** As the repository bounds a file it reads. */
    private static final int LIMIT = 1024 * 1024;

    /**
     * Reads the file.
     *
     * @param text Its content.
     * @param origin Where it came from, for messages.
     * @return The owners and host admins.
     * @throws CardException Naming the line and column of what is wrong, never quoting it.
     */
    static RegistryOwners read(String text, String origin) {
        return new Reader(text, origin).read();
    }

    /** Whether a login is one a person may carry: Porelo's own accounts take the others. */
    static boolean login(String login) {
        return LOGIN.matcher(login).matches()
                && !login.equals("porelo")
                && !login.startsWith("porelo-");
    }

    private static final class Reader {
        private final List<Event> events = new ArrayList<>();
        private final String origin;
        private int at;

        Reader(String text, String origin) {
            this.origin = origin;
            final LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setCodePointLimit(LIMIT);
            try {
                new Yaml(new SafeConstructor(options))
                        .parse(new StringReader(text))
                        .forEach(events::add);
            } catch (MarkedYAMLException ex) {
                // The parser's own message quotes the line; only its position is passed on.
                throw bad(
                        ex.getProblemMark() != null ? ex.getProblemMark() : ex.getContextMark(),
                        "not well-formed YAML");
            } catch (RuntimeException ex) {
                throw bad(null, "not well-formed YAML");
            }
            for (Event event : events) {
                if (event.is(Event.ID.Alias)
                        || event instanceof CollectionStartEvent start
                                && (start.getAnchor() != null || start.getTag() != null)
                        || event instanceof ScalarEvent scalar
                                && (scalar.getAnchor() != null || scalar.getTag() != null)) {
                    throw bad(event.getStartMark(), "anchors, aliases and tags are not allowed");
                }
            }
        }

        RegistryOwners read() {
            expect(Event.ID.StreamStart);
            expect(Event.ID.DocumentStart);
            expect(Event.ID.MappingStart);
            final Set<String> seen = new HashSet<>();
            List<Entry> owners = List.of();
            final Map<String, List<Entry>> hostAdmins = new LinkedHashMap<>();
            while (!next(Event.ID.MappingEnd)) {
                final Event key = current();
                final String field = scalar("a field name");
                if (!Set.of("owners", "host_admins").contains(field) || !seen.add(field)) {
                    throw bad(key.getStartMark(), "unknown or repeated field");
                }
                if (field.equals("owners")) {
                    owners = entries();
                } else {
                    hosts(hostAdmins);
                }
            }
            expect(Event.ID.MappingEnd);
            expect(Event.ID.DocumentEnd);
            expect(Event.ID.StreamEnd);
            if (at != events.size()) {
                throw bad(current().getStartMark(), "only one document is allowed");
            }
            if (!seen.contains("owners")) {
                throw bad(null, "owners is required");
            }
            return new RegistryOwners(owners, hostAdmins);
        }

        private void hosts(Map<String, List<Entry>> hostAdmins) {
            expect(Event.ID.MappingStart);
            while (!next(Event.ID.MappingEnd)) {
                final Event key = current();
                final String host = scalar("a host");
                if (!HOST.matcher(host).matches()) {
                    throw bad(key.getStartMark(), "not a host's name, a lower-case DNS label");
                }
                if (hostAdmins.put(host, entries()) != null) {
                    throw bad(key.getStartMark(), "the host is given twice");
                }
            }
            expect(Event.ID.MappingEnd);
        }

        /** A list of at least one entry, each login once. */
        private List<Entry> entries() {
            final Event start = current();
            expect(Event.ID.SequenceStart);
            final List<Entry> entries = new ArrayList<>();
            final Set<String> logins = new HashSet<>();
            while (!next(Event.ID.SequenceEnd)) {
                final Event opening = current();
                final Entry entry = entry();
                if (!logins.add(entry.login())) {
                    throw bad(opening.getStartMark(), "the login is given twice");
                }
                entries.add(entry);
            }
            expect(Event.ID.SequenceEnd);
            if (entries.isEmpty()) {
                throw bad(start.getStartMark(), "at least one entry is required");
            }
            return entries;
        }

        private Entry entry() {
            final Event opening = current();
            expect(Event.ID.MappingStart);
            final Set<String> seen = new HashSet<>();
            String login = "";
            String key = "";
            boolean backup = false;
            while (!next(Event.ID.MappingEnd)) {
                final Event name = current();
                final String field = scalar("a field name");
                if (!Set.of("login", "key", "backup").contains(field) || !seen.add(field)) {
                    throw bad(name.getStartMark(), "unknown or repeated field");
                }
                final Event value = current();
                final String text = scalar("a value");
                switch (field) {
                    case "login" -> {
                        if (!login(text)) {
                            throw bad(value.getStartMark(), "not a person's login");
                        }
                        login = text;
                    }
                    case "key" -> {
                        if (!KEY.matcher(text).matches() || !ed25519(text)) {
                            throw bad(value.getStartMark(), "not an Ed25519 public key");
                        }
                        key = text;
                    }
                    default -> {
                        if (!(value instanceof ScalarEvent plain && plain.isPlain())
                                || !Set.of("true", "false").contains(text)) {
                            throw bad(value.getStartMark(), "backup must be true or false");
                        }
                        backup = text.equals("true");
                    }
                }
            }
            expect(Event.ID.MappingEnd);
            if (!seen.containsAll(Set.of("login", "key"))) {
                throw bad(opening.getStartMark(), "an entry requires login and key");
            }
            return new Entry(login, key, backup);
        }

        private static boolean ed25519(String key) {
            try {
                PersonCard.fingerprint(key);
                return true;
            } catch (CardException ex) {
                return false;
            }
        }

        private String scalar(String what) {
            final Event event = current();
            if (!(event instanceof ScalarEvent scalar)) {
                throw bad(event.getStartMark(), what + " must be text");
            }
            at++;
            final String value = scalar.getValue();
            if (value.chars().anyMatch(Character::isISOControl)
                    || value.equals("<<")
                    || scalar.isPlain()
                            && List.of("", "~", "null", "Null", "NULL").contains(value)) {
                throw bad(
                        event.getStartMark(),
                        what + " must be text without control characters, nulls or merge keys");
            }
            return value;
        }

        private Event current() {
            if (at >= events.size()) {
                throw bad(null, "the file ends early");
            }
            return events.get(at);
        }

        private boolean next(Event.ID id) {
            return current().is(id);
        }

        private void expect(Event.ID id) {
            final Event event = current();
            if (!event.is(id)) {
                throw bad(event.getStartMark(), "expected " + id);
            }
            at++;
        }

        private CardException bad(@Nullable Mark mark, String why) {
            return new CardException(
                    origin
                            + (mark == null
                                    ? ""
                                    : ", line "
                                            + (mark.getLine() + 1)
                                            + ", column "
                                            + (mark.getColumn() + 1))
                            + ": "
                            + why);
        }
    }
}
