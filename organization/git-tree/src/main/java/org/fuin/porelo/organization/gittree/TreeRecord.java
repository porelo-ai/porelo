package org.fuin.porelo.organization.gittree;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.AddressForm;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Where a walk ended, and what it accepted: the trust record kept between reads, written through
 * plain maps so that a native image needs no reflection for it. Anything malformed refuses the
 * whole record, naming the field and never quoting it, so a damaged one is never half-used.
 *
 * @param commit The commit reached, or {@code null} when nothing was accepted yet.
 * @param model What was accepted.
 * @param refusals What was refused, the latest last.
 * @param checkedAt When a complete fresh read checked this state, or null for an older record.
 * @param waiting Where a walk that did not reach the tip waits, or {@code null}.
 */
record TreeRecord(
        @Nullable String commit,
        TreeModel model,
        List<Organization.Refusal> refusals,
        @Nullable Instant checkedAt,
        OrganizationQuestions.@Nullable Waiting waiting) {

    TreeRecord(@Nullable String commit, TreeModel model, List<Organization.Refusal> refusals) {
        this(commit, model, refusals, null, null);
    }

    TreeRecord(
            @Nullable String commit,
            TreeModel model,
            List<Organization.Refusal> refusals,
            @Nullable Instant checkedAt) {
        this(commit, model, refusals, checkedAt, null);
    }

    /** The words a walk waits with: only these are kept, so a record quotes nothing else. */
    static final Set<String> WAITS =
            Set.of(
                    "the identity source could not say that the founder owns the organisation",
                    "the identity source could not say that the founding key is the founder's",
                    "the identity source could not say whose card this is",
                    "the identity source has not vouched for the signer's card");

    private static final Pattern OBJECT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    TreeRecord {
        refusals = List.copyOf(refusals);
    }

    /**
     * Writes the record.
     *
     * @param address The repository's address, the only one this record speaks for.
     * @return The JSON document.
     */
    String toJson(final String address) {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("address", address);
        record.put("commit", commit);
        record.put("checked_at", checkedAt == null ? null : checkedAt.toString());
        record.put(
                "owners",
                model.owners().stream()
                        .map(
                                owner ->
                                        Map.of(
                                                "login",
                                                owner.login(),
                                                "key",
                                                owner.key(),
                                                "backup",
                                                owner.backup()))
                        .toList());
        final Map<String, Object> people = new LinkedHashMap<>();
        model.people().forEach((login, person) -> people.put(login, person(person)));
        record.put("people", people);
        final Map<String, Object> projects = new LinkedHashMap<>();
        model.projects().forEach((name, project) -> projects.put(name, project(project)));
        record.put("projects", projects);
        record.put("revoked", List.copyOf(model.revoked()));
        record.put("cards", model.cards());
        final Map<String, Object> hosts = new LinkedHashMap<>();
        model.hosts().forEach((name, host) -> hosts.put(name, host(host)));
        record.put("hosts", hosts);
        if (model.organizationAdmins() != null) {
            record.put("organization_admins", model.organizationAdmins());
        }
        if (waiting != null) {
            final Map<String, Object> step = new LinkedHashMap<>();
            step.put("commit", waiting.revision());
            step.put("words", waiting.words());
            step.put("kind", waiting.kind());
            record.put("waiting", step);
        }
        record.put(
                "refusals",
                refusals.stream()
                        .map(
                                refusal ->
                                        Map.of(
                                                "commit",
                                                refusal.revision(),
                                                "path",
                                                refusal.path(),
                                                "why",
                                                refusal.why()))
                        .toList());
        return Json.write(record);
    }

    private static Map<String, Object> person(final TreeModel.Person person) {
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put("fingerprint", person.fingerprint());
        written.put("state", person.state());
        written.put("handed_on", person.handedOn());
        written.put("predecessor", person.predecessor());
        written.put("card", person.card());
        written.put(
                "earlier",
                person.earlier().stream()
                        .map(
                                each ->
                                        Map.of(
                                                "key",
                                                each.key(),
                                                "valid_from",
                                                each.validFrom().toString(),
                                                "valid_until",
                                                each.validUntil().toString(),
                                                "revoked_since",
                                                each.revokedSince()))
                        .toList());
        return written;
    }

    private static Map<String, Object> project(final TreeModel.ProjectState project) {
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put("admin", project.admin());
        final List<TreeFiles.Member> members = project.members();
        written.put(
                "members",
                members == null
                        ? null
                        : members.stream()
                                .map(
                                        member ->
                                                Map.of(
                                                        "login",
                                                        member.login(),
                                                        "role",
                                                        member.role(),
                                                        "locked",
                                                        member.locked()))
                                .toList());
        written.put("address", project.address());
        return written;
    }

    private static Map<String, Object> host(final TreeModel.HostState host) {
        final Map<String, Object> written = new LinkedHashMap<>();
        final List<Map<String, Object>> admins = new ArrayList<>();
        for (final TreeFiles.HostAdmin admin : host.file().admins()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("login", admin.login());
            entry.put("backup_key", admin.backupKey());
            admins.add(entry);
        }
        written.put("admins", admins);
        written.put("projects", host.file().projects());
        if (!host.file().repositories().isEmpty()) {
            written.put("repositories", new java.util.TreeMap<>(host.file().repositories()));
        }
        final HostCard card = host.card();
        written.put("card", card == null ? null : card.body() + card.signature());
        return written;
    }

    /**
     * Reads a record {@link #toJson} wrote.
     *
     * @param text The JSON document.
     * @param address The repository's address the caller reads.
     * @return The record.
     * @throws CardException Naming the field that is wrong, never quoting its value.
     */
    static TreeRecord read(final String text, final String address) {
        final Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            throw new CardException("the trust record is not well-formed JSON");
        }
        final Map<?, ?> record = map(parsed, "the record");
        require(address.equals(record.get("address")), "address");
        final String commit = string(record.get("commit"), "commit");
        require(OBJECT.matcher(commit).matches(), "commit");
        final Instant checkedAt;
        try {
            checkedAt =
                    record.get("checked_at") == null
                            ? null
                            : Instant.parse(string(record.get("checked_at"), "checked_at"));
        } catch (DateTimeParseException ex) {
            throw new CardException("the trust record has no valid checked_at");
        }
        final TreeModel model = TreeModel.empty();
        for (final Object each : list(record.get("owners"), "owners")) {
            model.owners().add(owner(map(each, "owners")));
        }
        map(record.get("people"), "people")
                .forEach(
                        (login, person) ->
                                model.people()
                                        .put(
                                                login(login, "people"),
                                                person(
                                                        string(login, "people"),
                                                        map(person, "people"))));
        map(record.get("projects"), "projects")
                .forEach(
                        (name, project) -> {
                            final String named = string(name, "projects");
                            require(
                                    named.matches(
                                            org.fuin.porelo.core.project.Project.NAME_PATTERN),
                                    "projects");
                            model.projects().put(named, project(map(project, "projects")));
                        });
        for (final Object each : list(record.get("revoked"), "revoked")) {
            model.revoked().add(fingerprint(each, "revoked"));
        }
        map(record.get("cards"), "cards")
                .forEach(
                        (path, object) -> {
                            final String blob = string(object, "cards");
                            require(OBJECT.matcher(blob).matches(), "cards");
                            model.cards().put(string(path, "cards"), blob);
                        });
        map(record.get("hosts"), "hosts")
                .forEach(
                        (name, host) -> {
                            final String named = string(name, "hosts");
                            require(TreeFiles.host(named), "hosts");
                            model.hosts().put(named, host(named, map(host, "hosts")));
                        });
        final List<Organization.Refusal> refusals = new ArrayList<>();
        for (final Object each : list(record.get("refusals"), "refusals")) {
            final Map<?, ?> refusal = map(each, "refusals");
            refusals.add(
                    new Organization.Refusal(
                            string(refusal.get("commit"), "refusals"),
                            string(refusal.get("path"), "refusals"),
                            string(refusal.get("why"), "refusals")));
        }
        final OrganizationQuestions.Waiting waiting = waiting(record.get("waiting"));
        final Object listed = record.get("organization_admins");
        if (listed == null) {
            return new TreeRecord(commit, model, refusals, checkedAt, waiting);
        }
        final List<String> logins = new ArrayList<>();
        for (final Object each : list(listed, "organization_admins")) {
            logins.add(login(each, "organization_admins"));
        }
        return new TreeRecord(
                commit,
                model.withOrganizationAdmins(List.copyOf(logins)),
                refusals,
                checkedAt,
                waiting);
    }

    private static OrganizationQuestions.@Nullable Waiting waiting(final @Nullable Object value) {
        if (value == null) {
            return null;
        }
        final Map<?, ?> step = map(value, "waiting");
        final String commit = string(step.get("commit"), "waiting");
        require(OBJECT.matcher(commit).matches(), "waiting");
        final String words = string(step.get("words"), "waiting");
        require(WAITS.contains(words), "waiting");
        final Object kind = step.get("kind");
        require(kind == null || HttpForgeDirectory.KINDS.contains(kind), "waiting");
        return new OrganizationQuestions.Waiting(commit, words, (String) kind);
    }

    private static TreeFiles.Owner owner(final Map<?, ?> entry) {
        final String key = string(entry.get("key"), "owners");
        final String fingerprint;
        try {
            fingerprint = PersonCard.fingerprint(key);
        } catch (RuntimeException ex) {
            throw new CardException("the trust record's owners are malformed");
        }
        return new TreeFiles.Owner(
                login(entry.get("login"), "owners"),
                key,
                fingerprint,
                flag(entry.get("backup"), "owners"));
    }

    private static TreeModel.Person person(final String login, final Map<?, ?> entry) {
        final String state = string(entry.get("state"), "people");
        require(
                Set.of(Organization.TRUSTED, Organization.FIRST_SEEN, Organization.REFUSED)
                        .contains(state),
                "people");
        final String predecessor = string(entry.get("predecessor"), "people");
        final List<String> handedOn = new ArrayList<>();
        for (final Object each : list(entry.get("handed_on"), "people")) {
            handedOn.add(fingerprint(each, "people"));
        }
        require(predecessor.isEmpty() || FINGERPRINT.matcher(predecessor).matches(), "people");
        final String card = string(entry.get("card"), "people");
        final String fingerprint = fingerprint(entry.get("fingerprint"), "people");
        final List<TreeModel.EarlierKey> earlier = new ArrayList<>();
        for (final Object each : list(entry.get("earlier"), "people")) {
            earlier.add(earlier(map(each, "people")));
        }
        try {
            final PersonCard read = PersonCard.read(card, "the trust record");
            require(read.login().equals(login) && read.fingerprint().equals(fingerprint), "people");
        } catch (CardException ex) {
            throw malformed("people");
        }
        return new TreeModel.Person(
                fingerprint, state, List.copyOf(handedOn), predecessor, card, List.copyOf(earlier));
    }

    private static TreeModel.EarlierKey earlier(final Map<?, ?> entry) {
        try {
            return new TreeModel.EarlierKey(
                    string(entry.get("key"), "people"),
                    LocalDate.parse(string(entry.get("valid_from"), "people")),
                    LocalDate.parse(string(entry.get("valid_until"), "people")),
                    string(entry.get("revoked_since"), "people"));
        } catch (DateTimeParseException ex) {
            throw malformed("people");
        }
    }

    private static TreeModel.ProjectState project(final Map<?, ?> entry) {
        List<TreeFiles.Member> members = null;
        if (entry.get("members") != null) {
            final List<TreeFiles.Member> read = new ArrayList<>();
            for (final Object each : list(entry.get("members"), "projects")) {
                final Map<?, ?> member = map(each, "projects");
                final String role = string(member.get("role"), "projects");
                require(
                        role.equals(TreeFiles.ADMIN) || role.equals(TreeFiles.DEVELOPER),
                        "projects");
                read.add(
                        new TreeFiles.Member(
                                login(member.get("login"), "projects"),
                                role,
                                flag(member.get("locked"), "projects")));
            }
            members = List.copyOf(read);
        }
        String address = null;
        if (entry.get("address") != null) {
            address = string(entry.get("address"), "projects");
            require(AddressForm.isRepository(address), "projects");
        }
        return new TreeModel.ProjectState(login(entry.get("admin"), "projects"), members, address);
    }

    private static TreeModel.HostState host(final String name, final Map<?, ?> entry) {
        final List<TreeFiles.HostAdmin> admins = new ArrayList<>();
        for (final Object each : list(entry.get("admins"), "hosts")) {
            final Map<?, ?> admin = map(each, "hosts");
            final Object backup = admin.get("backup_key");
            admins.add(
                    new TreeFiles.HostAdmin(
                            login(admin.get("login"), "hosts"),
                            backup == null ? null : string(backup, "hosts")));
        }
        final List<String> projects = new ArrayList<>();
        for (final Object each : list(entry.get("projects"), "hosts")) {
            projects.add(string(each, "hosts"));
        }
        // A record from before a host carried single repositories has none: each project whole.
        final Map<String, List<String>> repositories = new java.util.LinkedHashMap<>();
        if (entry.get("repositories") != null) {
            for (final Map.Entry<?, ?> some : map(entry.get("repositories"), "hosts").entrySet()) {
                final String project = string(some.getKey(), "hosts");
                final List<String> carried = new ArrayList<>();
                for (final Object each : list(some.getValue(), "hosts")) {
                    carried.add(string(each, "hosts"));
                }
                require(projects.contains(project) && !carried.isEmpty(), "hosts");
                repositories.put(project, List.copyOf(carried));
            }
        }
        HostCard card = null;
        if (entry.get("card") != null) {
            try {
                card = HostCard.read(string(entry.get("card"), "hosts"), "the trust record");
            } catch (CardException ex) {
                throw new CardException("the trust record's hosts are malformed");
            }
            require(card.host().equals(name), "hosts");
        }
        return new TreeModel.HostState(
                new TreeFiles.HostFile(
                        List.copyOf(admins), List.copyOf(projects), Map.copyOf(repositories)),
                card);
    }

    private static String fingerprint(final @Nullable Object value, final String field) {
        final String text = string(value, field);
        require(FINGERPRINT.matcher(text).matches(), field);
        return text;
    }

    private static String login(final @Nullable Object value, final String field) {
        final String text = string(value, field);
        require(TreeFiles.login(text), field);
        return text;
    }

    private static String string(final @Nullable Object value, final String field) {
        if (!(value instanceof String text)) {
            throw malformed(field);
        }
        return text;
    }

    private static boolean flag(final @Nullable Object value, final String field) {
        if (!(value instanceof Boolean each)) {
            throw malformed(field);
        }
        return each;
    }

    private static Map<?, ?> map(final @Nullable Object value, final String field) {
        if (!(value instanceof Map<?, ?> each)) {
            throw malformed(field);
        }
        return each;
    }

    private static List<?> list(final @Nullable Object value, final String field) {
        if (!(value instanceof List<?> each)) {
            throw malformed(field);
        }
        return each;
    }

    private static void require(final boolean holds, final String field) {
        if (!holds) {
            throw malformed(field);
        }
    }

    private static CardException malformed(final String field) {
        return new CardException("the trust record's " + field + " are malformed");
    }
}
