package org.fuin.porelo.vault;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.project.Repository;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;

/** The host's pinned project admins and the access repositories they administer. */
record RegistryAccess(Map<String, String> admins, List<Project> projects) {
    RegistryAccess {
        admins = Collections.unmodifiableMap(new LinkedHashMap<>(admins));
        projects = List.copyOf(projects);
    }

    /** One project and the admin responsible for its access repository. */
    record Project(String name, String admin, String accessRepository) {}

    static RegistryAccess read(String text, String origin) {
        try {
            return new Reader(text, origin).read();
        } catch (CardException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CardException(origin + ": cannot read access.yml: " + ex.getMessage());
        }
    }

    private static final class Reader {
        private final List<Event> events = new ArrayList<>();
        private final String origin;
        private int at;

        Reader(String text, String origin) {
            this.origin = origin;
            new Yaml(new SafeConstructor(new LoaderOptions()))
                    .parse(new StringReader(text))
                    .forEach(events::add);
            for (Event event : events) {
                if (event.is(Event.ID.Alias)
                        || event instanceof CollectionStartEvent start
                                && (start.getAnchor() != null || start.getTag() != null)
                        || event instanceof ScalarEvent scalar
                                && (scalar.getAnchor() != null || scalar.getTag() != null)) {
                    throw bad("anchors, aliases and tags are not allowed");
                }
            }
        }

        RegistryAccess read() {
            expect(Event.ID.StreamStart);
            expect(Event.ID.DocumentStart);
            expect(Event.ID.MappingStart);
            final Set<String> seen = new HashSet<>();
            final Map<String, String> admins = new LinkedHashMap<>();
            final List<Project> projects = new ArrayList<>();
            while (!next(Event.ID.MappingEnd)) {
                final String field = field(seen, Set.of("admins", "projects"));
                if (field.equals("admins")) {
                    admins(admins);
                } else {
                    projects(projects);
                }
            }
            expect(Event.ID.MappingEnd);
            expect(Event.ID.DocumentEnd);
            expect(Event.ID.StreamEnd);
            if (at != events.size() || seen.size() != 2) {
                throw bad("exactly admins and projects are required");
            }
            for (Project project : projects) {
                if (!admins.containsKey(project.admin())) {
                    throw bad(
                            "project '"
                                    + project.name()
                                    + "' names an admin not in admins: '"
                                    + project.admin()
                                    + "'");
                }
            }
            return new RegistryAccess(admins, projects);
        }

        private void admins(Map<String, String> admins) {
            expect(Event.ID.MappingStart);
            while (!next(Event.ID.MappingEnd)) {
                final String login = scalar();
                if (!login.matches("[a-z][a-z0-9-]{0,31}")
                        || login.equals("porelo")
                        || login.startsWith("porelo-")) {
                    throw bad("'" + login + "' is not a person's login");
                }
                final String fingerprint = scalar();
                if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
                    throw bad("'" + login + "' needs a SHA256 fingerprint");
                }
                if (admins.putIfAbsent(login, fingerprint) != null) {
                    throw bad("admin '" + login + "' is given twice");
                }
            }
            expect(Event.ID.MappingEnd);
        }

        private void projects(List<Project> projects) {
            expect(Event.ID.SequenceStart);
            final Set<String> names = new HashSet<>();
            while (!next(Event.ID.SequenceEnd)) {
                expect(Event.ID.MappingStart);
                final Set<String> seen = new HashSet<>();
                final Map<String, String> fields = new LinkedHashMap<>();
                while (!next(Event.ID.MappingEnd)) {
                    final String field = field(seen, Set.of("name", "admin", "access_repository"));
                    fields.put(field, scalar());
                }
                expect(Event.ID.MappingEnd);
                if (seen.size() != 3) {
                    throw bad("a project requires name, admin and access_repository");
                }
                final String name = fields.getOrDefault("name", "");
                if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                    throw bad("invalid project name: '" + name + "'");
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw bad("project name is given twice: '" + name + "'");
                }
                final String repository = fields.getOrDefault("access_repository", "");
                if (repository.isBlank()) {
                    throw bad("project '" + name + "' needs access_repository");
                }
                Repository.refuseAsOption(repository, "access_repository");
                if (repository.matches("[^:]+::[\\s\\S]*")
                        || !repository.matches("(?:ssh|https)://[^/]+(?:/.*)?|[^@/:]+@[^/:]+:.+")) {
                    throw bad(
                            "project '"
                                    + name
                                    + "' needs an ssh://, https:// or user@host:path access_repository");
                }
                projects.add(new Project(name, fields.getOrDefault("admin", ""), repository));
            }
            expect(Event.ID.SequenceEnd);
        }

        private String field(Set<String> seen, Set<String> allowed) {
            final String field = scalar();
            if (!allowed.contains(field) || !seen.add(field)) {
                throw bad("unknown or repeated field '" + field + "'");
            }
            return field;
        }

        private String scalar() {
            if (at >= events.size() || !(events.get(at++) instanceof ScalarEvent scalar)) {
                throw bad("a field must be text");
            }
            final String value = scalar.getValue();
            if (value.chars().anyMatch(Character::isISOControl)
                    || value.equals("<<")
                    || scalar.isPlain()
                            && List.of("", "~", "null", "Null", "NULL").contains(value)) {
                throw bad("a field must be text without control characters, nulls or merge keys");
            }
            return value;
        }

        private boolean next(Event.ID id) {
            return at < events.size() && events.get(at).is(id);
        }

        private void expect(Event.ID id) {
            if (!next(id)) {
                throw bad("expected " + id);
            }
            at++;
        }

        private CardException bad(String why) {
            return new CardException(origin + ": " + why);
        }
    }
}
