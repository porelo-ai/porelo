package org.fuin.porelo.organization.gittree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.AddressForm;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * The organization's YAML files, read strictly. A refusal never quotes what is written there: the
 * files come from anybody with write access to the repository, and the text reaches a terminal.
 */
final class TreeFiles {

    /** A project member's role that may change the members. */
    static final String ADMIN = "admin";

    /** A project member's role that may not. */
    static final String DEVELOPER = "developer";

    /** A host's name: one lower-case DNS label, as a host's card must carry it. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final Pattern KEY = Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]+={0,2}");

    private static final int LIMIT = 1024 * 1024;

    private TreeFiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One entry of {@code owners.yml}.
     *
     * @param login The login.
     * @param key The key, {@code ssh-ed25519 AAAA...}.
     * @param fingerprint The key's fingerprint.
     * @param backup Whether it is an offline backup key.
     */
    record Owner(String login, String key, String fingerprint, boolean backup) {}

    /**
     * One entry of a project's {@code members.yml}.
     *
     * @param login The login.
     * @param role {@link #ADMIN} or {@link #DEVELOPER}.
     * @param locked Whether it is listed but may nothing.
     */
    record Member(String login, String role, boolean locked) {}

    /**
     * One entry of a host's {@code admins}. A key of the admin's own is on their card, so only a
     * backup key is named here.
     *
     * @param login The login.
     * @param backupKey The offline backup key, or {@code null} for the admin's place itself.
     */
    record HostAdmin(String login, @Nullable String backupKey) {

        boolean backup() {
            return backupKey != null;
        }
    }

    /**
     * A host's {@code host.yml}.
     *
     * @param admins Its admins.
     * @param projects The names of the projects it carries.
     */
    record HostFile(List<HostAdmin> admins, List<String> projects) {}

    /** Whether a name is one a host may carry. */
    static boolean host(final String name) {
        return HOST.matcher(name).matches();
    }

    /** Whether a login is one a person may carry: Porelo's own accounts take the others. */
    static boolean login(final String login) {
        return PersonCard.isLogin(login) && !login.equals("porelo") && !login.startsWith("porelo-");
    }

    /**
     * Reads {@code owners.yml}.
     *
     * @param text Its content.
     * @return Its entries, at least one of them no backup key.
     * @throws CardException Saying what is wrong, never quoting it.
     */
    static List<Owner> owners(final String text) {
        final Map<?, ?> file = document(text, Set.of("owners"));
        final List<Owner> owners = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        for (final Object each : list(file.get("owners"), "owners")) {
            final Map<?, ?> entry = entry(each, Set.of("login", "key", "backup"));
            final String key = string(entry.get("key"), "key");
            if (!KEY.matcher(key).matches()) {
                throw new CardException("an owner's key is not an Ed25519 key");
            }
            if (!keys.add(key)) {
                throw new CardException("a key is named twice");
            }
            owners.add(
                    new Owner(
                            login(entry.get("login")),
                            key,
                            PersonCard.fingerprint(key),
                            flag(entry.get("backup"), "backup")));
        }
        if (owners.stream().allMatch(Owner::backup)) {
            throw new CardException("no owner holds a key of their own");
        }
        return List.copyOf(owners);
    }

    /**
     * Reads a project's {@code card}.
     *
     * @param text Its content.
     * @param project The project its path names.
     * @return The login of the first admin an owner named.
     * @throws CardException Saying what is wrong, never quoting it.
     */
    static String projectAdmin(final String text, final String project) {
        final Map<?, ?> file = document(text, Set.of("name", "admin"));
        if (!project.equals(file.get("name"))) {
            throw new CardException("the card names another project than its path");
        }
        return login(file.get("admin"));
    }

    /**
     * Reads a project's {@code members.yml}.
     *
     * @param text Its content.
     * @return Its members, each login once.
     * @throws CardException Saying what is wrong, never quoting it.
     */
    static List<Member> members(final String text) {
        final Map<?, ?> file = document(text, Set.of("members"));
        final List<Member> members = new ArrayList<>();
        final Set<String> logins = new HashSet<>();
        for (final Object each : list(file.get("members"), "members")) {
            final Map<?, ?> entry = entry(each, Set.of("login", "role", "locked"));
            final String login = login(entry.get("login"));
            if (!logins.add(login)) {
                throw new CardException("a member is named twice");
            }
            final String role = string(entry.get("role"), "role");
            if (!role.equals(ADMIN) && !role.equals(DEVELOPER)) {
                throw new CardException("a member's role is neither admin nor developer");
            }
            members.add(new Member(login, role, flag(entry.get("locked"), "locked")));
        }
        return List.copyOf(members);
    }

    /**
     * Reads a host's {@code host.yml}.
     *
     * @param text Its content.
     * @return Its admins, at least one holding the place itself, and its projects, each once.
     * @throws CardException Saying what is wrong, never quoting it.
     */
    static HostFile hostFile(final String text) {
        final Map<?, ?> file = document(text, Set.of("admins", "projects"));
        final List<HostAdmin> admins = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Object each : list(file.get("admins"), "admins")) {
            final HostAdmin admin = hostAdmin(entry(each, Set.of("login", "key", "backup")));
            if (!seen.add(admin.login() + " " + admin.backupKey())) {
                throw new CardException("an admin is named twice");
            }
            admins.add(admin);
        }
        if (admins.stream().allMatch(HostAdmin::backup)) {
            throw new CardException("no admin holds the place with a key of their own");
        }
        final List<String> projects = new ArrayList<>();
        for (final Object each : list(file.get("projects"), "projects")) {
            final String name = string(each, "a project");
            if (!name.matches(Project.NAME_PATTERN) || projects.contains(name)) {
                throw new CardException("a project is no name, or is named twice");
            }
            projects.add(name);
        }
        return new HostFile(List.copyOf(admins), List.copyOf(projects));
    }

    /**
     * Reads {@code organization-admins.yml}.
     *
     * @param text Its content.
     * @return The logins it lists, each once.
     * @throws CardException Saying what is wrong, never quoting it.
     */
    static List<String> organizationAdmins(final String text) {
        final Map<?, ?> file = document(text, Set.of("admins"));
        final List<String> logins = new ArrayList<>();
        for (final Object each : list(file.get("admins"), "admins")) {
            final String login = string(entry(each, Set.of("login")).get("login"), "a login");
            if (!login(login) || logins.contains(login)) {
                throw new CardException("a login is no login, or is named twice");
            }
            logins.add(login);
        }
        return List.copyOf(logins);
    }

    /**
     * Writes {@code organization-admins.yml} so that {@link #organizationAdmins} reads it back.
     *
     * @param logins The logins.
     * @return The file's text.
     */
    static String organizationAdminsText(final List<String> logins) {
        if (logins.isEmpty()) {
            return "admins: []\n";
        }
        final StringBuilder text = new StringBuilder("admins:\n");
        logins.forEach(login -> text.append("  - login: ").append(login).append('\n'));
        return text.toString();
    }

    /**
     * Writes {@code owners.yml} so that {@link #owners} reads it back.
     *
     * @param owners The entries.
     * @return The file's text.
     */
    static String ownersText(final List<Owner> owners) {
        final StringBuilder text = new StringBuilder("owners:\n");
        for (final Owner owner : owners) {
            text.append("  - login: ")
                    .append(owner.login())
                    .append("\n    key: \"")
                    .append(owner.key())
                    .append("\"\n");
            if (owner.backup()) {
                text.append("    backup: true\n");
            }
        }
        return text.toString();
    }

    /**
     * Writes a host's {@code host.yml} so that {@link #hostFile} reads it back.
     *
     * @param file The file.
     * @return Its text.
     */
    static String hostFileText(final HostFile file) {
        final StringBuilder text = new StringBuilder("admins:\n");
        for (final HostAdmin admin : file.admins()) {
            text.append("  - login: ").append(admin.login()).append('\n');
            if (admin.backup()) {
                text.append("    key: \"")
                        .append(admin.backupKey())
                        .append("\"\n    backup: true\n");
            }
        }
        text.append(file.projects().isEmpty() ? "projects: []\n" : "projects:\n");
        file.projects().forEach(each -> text.append("  - ").append(each).append('\n'));
        return text.toString();
    }

    /**
     * Writes a project's {@code members.yml} so that {@link #members} reads it back.
     *
     * @param members The members.
     * @return The file's text.
     */
    static String membersText(final List<Member> members) {
        if (members.isEmpty()) {
            return "members: []\n";
        }
        final StringBuilder text = new StringBuilder("members:\n");
        for (final Member member : members) {
            text.append("  - login: ")
                    .append(member.login())
                    .append("\n    role: ")
                    .append(member.role())
                    .append('\n');
            if (member.locked()) {
                text.append("    locked: true\n");
            }
        }
        return text.toString();
    }

    /**
     * Tells whether a text is a key this organization's files may hold.
     *
     * @param key The text.
     * @return Whether it is an Ed25519 key.
     */
    static boolean key(final String key) {
        return KEY.matcher(key).matches();
    }

    private static HostAdmin hostAdmin(final Map<?, ?> entry) {
        final String login = login(entry.get("login"));
        final boolean backup = flag(entry.get("backup"), "backup");
        if (!backup) {
            if (entry.containsKey("key")) {
                throw new CardException("an admin's own key is on their card, not here");
            }
            return new HostAdmin(login, null);
        }
        final String key = string(entry.get("key"), "key");
        if (!KEY.matcher(key).matches()) {
            throw new CardException("a backup key is not an Ed25519 key");
        }
        PersonCard.fingerprint(key);
        return new HostAdmin(login, key);
    }

    /**
     * Reads a project's {@code repository.yml}.
     *
     * @param text Its content.
     * @return The address, which holds no credential and nothing git would run.
     * @throws CardException Saying what is wrong, never quoting it: it may hold a token.
     */
    static String repositoryAddress(final String text) {
        final String address = string(document(text, Set.of("address")).get("address"), "address");
        if (!AddressForm.isRepository(address)) {
            throw new CardException("the address is none a repository may have");
        }
        return address;
    }

    private static Map<?, ?> document(final String text, final Set<String> known) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(LIMIT);
        final Object loaded;
        try {
            refuseIndirection(options, text);
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (CardException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // The parser's own message quotes the line.
            throw new CardException("not well-formed YAML");
        }
        return entry(loaded, known);
    }

    private static void refuseIndirection(final LoaderOptions options, final String text) {
        for (final Event event :
                new Yaml(new SafeConstructor(options)).parse(new java.io.StringReader(text))) {
            if (event.is(Event.ID.Alias)
                    || event instanceof CollectionStartEvent start
                            && (start.getAnchor() != null || start.getTag() != null)
                    || event instanceof ScalarEvent scalar
                            && (scalar.getAnchor() != null || scalar.getTag() != null)) {
                throw new CardException("anchors, aliases and tags are not allowed");
            }
        }
    }

    private static Map<?, ?> entry(final @Nullable Object value, final Set<String> known) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new CardException("expected a mapping");
        }
        for (final Object key : map.keySet()) {
            if (!(key instanceof String name) || !known.contains(name)) {
                throw new CardException("a field is none of " + new java.util.TreeSet<>(known));
            }
        }
        return map;
    }

    private static List<?> list(final @Nullable Object value, final String field) {
        if (!(value instanceof List<?> list)) {
            throw new CardException(field + " is not a list");
        }
        return list;
    }

    private static String string(final @Nullable Object value, final String field) {
        if (!(value instanceof String text)) {
            throw new CardException(field + " is not a string");
        }
        return text;
    }

    private static boolean flag(final @Nullable Object value, final String field) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new CardException(field + " is neither true nor false");
        }
        return flag;
    }

    private static String login(final @Nullable Object value) {
        final String login = string(value, "login");
        if (!login(login)) {
            throw new CardException("a login is none a person may carry");
        }
        return login;
    }
}
