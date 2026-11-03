package org.fuin.porelo.organization.contract;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory store changed only through its API: honest with no {@link WriteBreak}, and with one
 * it does what it must not. A card is its plain words, since the checks are about what a store
 * takes, not about signatures.
 */
final class WritesStandIn implements WritesFixture, OrganizationWrites {

    /** What a check's setup builds on, so that a lost change fails a check, not its setup. */
    private static final Set<String> STANDS_UNDER_THE_REST = Set.of("found", "card", "project");

    private record Made(String what, String revision) {}

    @FunctionalInterface
    private interface Step {
        void run();
    }

    private final Set<WriteBreak> breaks;

    private final Map<String, String> cards = new LinkedHashMap<>();

    private final Set<String> lostKeys = new HashSet<>();

    private final Set<String> owners = new LinkedHashSet<>();

    private final Set<String> organizationAdmins = new LinkedHashSet<>();

    private final Map<String, Map<String, String>> projects = new HashMap<>();

    private final Set<String> locked = new HashSet<>();

    private final Map<String, Set<String>> hosts = new HashMap<>();

    private final Map<String, String> hostCards = new HashMap<>();

    private final Map<String, String> hostOf = new HashMap<>();

    private final Set<String> opened = new HashSet<>();

    private final Map<String, String> addresses = new HashMap<>();

    private final Map<String, Boolean> sourceOwners = new HashMap<>();

    private final Map<String, Made> made = new HashMap<>();

    private final Map<String, String> stillActing = new HashMap<>();

    private final Map<String, String> addedBy = new HashMap<>();

    private final List<Organization.Refusal> refusals = new ArrayList<>();

    private final Map<String, String> backups = new HashMap<>();

    private final Map<String, String> listedOnly = new HashMap<>();

    private final Map<String, Set<String>> listed = new HashMap<>();

    /** Who a refused change left in the store's text, though no reader counts them. */
    private final Set<String> ghosts = new HashSet<>();

    private int revision;

    WritesStandIn(final WriteBreak... breaks) {
        this.breaks =
                breaks.length == 0
                        ? EnumSet.noneOf(WriteBreak.class)
                        : EnumSet.copyOf(Arrays.asList(breaks));
    }

    private boolean broken(final WriteBreak what) {
        return breaks.contains(what);
    }

    private @Nullable String revisionNow() {
        return revision == 0 ? null : "r" + revision;
    }

    private static Refused refuse(final String reason) {
        return new Refused(reason, reason, null);
    }

    /** The id first, then the base, then the step's own judgment and change. */
    private Written change(final Change change, final String what, final Step step) {
        final String key =
                (broken(WriteBreak.TAKES_ANOTHER_PERSONS_ID) ? "" : change.acting().login())
                        + "/"
                        + change.id();
        final Made before = broken(WriteBreak.MAKES_AN_ID_TWICE) ? null : made.get(key);
        if (before != null) {
            if (!before.what().equals(what)
                    && !broken(WriteBreak.ANSWERS_ANOTHER_CHANGE_AS_MADE)
                    && !broken(WriteBreak.TAKES_ANOTHER_PERSONS_ID)) {
                throw refuse(Refused.USAGE);
            }
            return new Written(before.revision(), change.id(), true);
        }
        if (!broken(WriteBreak.IGNORES_BASE)
                && !broken(WriteBreak.MAKES_AN_ID_TWICE)
                && !"found".equals(what)
                && !("r" + revision).equals(change.base())) {
            throw refuse(Refused.MOVED_ON);
        }
        final Map<String, String> savedCards = new LinkedHashMap<>(cards);
        final Set<String> savedOwners = new LinkedHashSet<>(owners);
        final Set<String> savedAdmins = new LinkedHashSet<>(organizationAdmins);
        final Map<String, Map<String, String>> savedProjects = new HashMap<>();
        projects.forEach((name, members) -> savedProjects.put(name, new HashMap<>(members)));
        final Set<String> savedLocked = new HashSet<>(locked);
        try {
            step.run();
        } catch (final Refused refused) {
            if (broken(WriteBreak.MOVES_ON_THOUGH_REFUSED)) {
                revision++;
            }
            throw refused;
        }
        if (broken(WriteBreak.LOSES_THE_CHANGE)
                && !STANDS_UNDER_THE_REST.contains(what.split(" ")[0])) {
            cards.clear();
            cards.putAll(savedCards);
            owners.clear();
            owners.addAll(savedOwners);
            organizationAdmins.clear();
            organizationAdmins.addAll(savedAdmins);
            projects.clear();
            projects.putAll(savedProjects);
            locked.clear();
            locked.addAll(savedLocked);
        }
        revision++;
        final String now = "r" + revision;
        made.put(key, new Made(what, now));
        return new Written(now, change.id(), false);
    }

    /** The person acting, whose key must be the one on their card. */
    private String acting(final Change change) {
        final String login = change.acting().login();
        final boolean hers =
                change.acting().key().equals(cards.get(login))
                        || login.equals(stillActing.get(change.acting().key()));
        if (!hers && !broken(WriteBreak.IGNORES_ACTING_KEY)) {
            throw refuse(Refused.NOT_ALLOWED);
        }
        return login;
    }

    private void allowed(final boolean allowed) {
        if (!allowed && !broken(WriteBreak.ANY_SIGNER)) {
            throw refuse(Refused.NOT_ALLOWED);
        }
    }

    private boolean adminOf(final String project, final String login) {
        if (locked.contains(project + "/" + login) && !broken(WriteBreak.LOCKED_ADMIN_ACTS)) {
            throw refuse(Refused.LOCKED);
        }
        if (broken(WriteBreak.ADMIN_OF_ANY_PROJECT)) {
            return projects.values().stream().anyMatch(m -> "admin".equals(m.get(login)));
        }
        return "admin".equals(projects.getOrDefault(project, Map.of()).get(login));
    }

    private void memberAllowed(final String project, final String actor, final boolean admin) {
        if (locked.contains(project + "/" + actor) && !broken(WriteBreak.LOCKED_ADMIN_ACTS)) {
            throw refuse(Refused.LOCKED);
        }
        allowed(admin ? organizationAdmins.contains(actor) : adminOf(project, actor));
    }

    private Map<String, String> members(final String project) {
        final Map<String, String> members = projects.get(project);
        if (members == null) {
            throw refuse(Refused.NOT_FOUND);
        }
        return members;
    }

    private void hasCard(final String login) {
        if (!cards.containsKey(login)) {
            throw refuse(Refused.NO_CARD);
        }
    }

    private void cardFits(final String card, final String login, final String key) {
        final String[] words = card.split("\\|");
        if (!broken(WriteBreak.ACCEPTS_ANY_CARD)
                && (!words[0].equals(login) || !words[1].equals(key))) {
            throw refuse(Refused.USAGE);
        }
    }

    @Override
    public Written createOrganization(final Change change, final String card) {
        return change(
                change,
                "found",
                () -> {
                    if (revision != 0 && !broken(WriteBreak.FOUNDS_AGAIN)) {
                        throw refuse(Refused.EXISTS);
                    }
                    final String login = change.acting().login();
                    if (!sourceOwners.getOrDefault(login, false)
                            && !broken(WriteBreak.FOUNDS_FOR_ANYONE)) {
                        throw refuse(Refused.NOT_ALLOWED);
                    }
                    cardFits(card, login, change.acting().key());
                    cards.put(login, change.acting().key());
                    owners.add(login);
                    organizationAdmins.add(login);
                    if (broken(WriteBreak.FOUNDS_WITH_A_SECOND_OWNER)) {
                        cards.put("planted", "ssh-ed25519 planted");
                        owners.add("planted");
                    }
                });
    }

    @Override
    public Written addCard(final Change change, final String card) {
        return change(
                change,
                "card " + card,
                () -> {
                    final String login = change.acting().login();
                    final String key = change.acting().key();
                    cardFits(card, login, key);
                    if (!listed.getOrDefault(login, Set.of(key(login))).contains(key)
                            && !broken(WriteBreak.TAKES_A_FREE_LOGIN)) {
                        throw refuse(Refused.NOT_ALLOWED);
                    }
                    if (lostKeys.contains(key) && !broken(WriteBreak.ACCEPTS_LOST_KEY)) {
                        throw refuse(Refused.NOT_ALLOWED);
                    }
                    if (cards.containsKey(login)) {
                        throw refuse(Refused.EXISTS);
                    }
                    cards.put(login, key);
                });
    }

    @Override
    public Written revokeCard(final Change change, final String login) {
        return change(
                change,
                "revoke " + login,
                () -> {
                    final String actor = acting(change);
                    allowed(actor.equals(login) || organizationAdmins.contains(actor));
                    if (owners.contains(login)
                            && owners.size() == 1
                            && !broken(WriteBreak.REMOVES_THE_LAST)) {
                        throw refuse(Refused.LAST_OF_ITS_KIND);
                    }
                    hasCard(login);
                    lostKeys.add(cards.remove(login));
                });
    }

    @Override
    public Written replaceCard(final Change change, final String login, final String card) {
        return change(
                change,
                "replace " + login,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    hasCard(login);
                    final String next = card.split("\\|")[1];
                    final String old = cards.put(login, next);
                    if (!broken(WriteBreak.KEEPS_WHAT_A_REPLACED_KEY_DID)) {
                        forgetWhatItDid(old);
                    }
                    if (broken(WriteBreak.ACCEPTS_LOST_KEY)) {
                        stillActing.put(old, login);
                    } else {
                        lostKeys.add(old);
                    }
                });
    }

    /** What this key alone vouched for leaves, and a reader says so. */
    private void forgetWhatItDid(final String key) {
        addedBy.forEach(
                (member, by) -> {
                    if (by.equals(key)) {
                        final String[] names = member.split("/");
                        projects.getOrDefault(names[0], new HashMap<>()).remove(names[1]);
                        ghosts.add(member);
                        refusals.add(
                                new Organization.Refusal(
                                        "r" + revision, "the change", "a key no longer listed"));
                    }
                });
    }

    @Override
    public Written changeCard(final Change change, final String card) {
        return change(
                change,
                "change card " + card,
                () -> {
                    final String login = change.acting().login();
                    final String[] words = card.split("\\|");
                    cardFits(card, login, change.acting().key());
                    final String old = cards.get(login);
                    final boolean sourcesWord =
                            !broken(WriteBreak.REFUSES_THE_SOURCES_WORD)
                                    && change.acting().key().equals(listedOnly.get(login));
                    if ((words.length != 3 || !words[2].equals(old))
                            && !sourcesWord
                            && !broken(WriteBreak.HANDS_ON_WITHOUT_THE_OLD_KEY)) {
                        throw refuse(Refused.NOT_ALLOWED);
                    }
                    cards.put(login, change.acting().key());
                    if (broken(WriteBreak.LOSES_WHAT_A_HANDED_ON_KEY_DID)) {
                        forgetWhatItDid(old);
                    }
                });
    }

    @Override
    public Written addOrganizationOwner(final Change change, final String login) {
        return change(
                change,
                "owner " + login,
                () -> {
                    allowed(owners.contains(acting(change)));
                    hasCard(login);
                    owners.add(login);
                });
    }

    @Override
    public Written addOrganizationAdmin(final Change change, final String login) {
        return change(
                change,
                "organization admin " + login,
                () -> {
                    allowed(owners.contains(acting(change)));
                    hasCard(login);
                    if (!organizationAdmins.add(login)) {
                        throw refuse(Refused.EXISTS);
                    }
                });
    }

    @Override
    public Written removeOrganizationAdmin(final Change change, final String login) {
        return change(
                change,
                "no organization admin " + login,
                () -> {
                    allowed(owners.contains(acting(change)));
                    if (!organizationAdmins.remove(login)) {
                        throw refuse(Refused.NOT_FOUND);
                    }
                });
    }

    @Override
    public Written removeOrganizationOwner(final Change change, final String login) {
        return change(
                change,
                "no owner " + login,
                () -> {
                    allowed(owners.contains(acting(change)));
                    if (owners.size() == 1 && !broken(WriteBreak.REMOVES_THE_LAST)) {
                        throw refuse(Refused.LAST_OF_ITS_KIND);
                    }
                    owners.remove(login);
                });
    }

    @Override
    public Written addProject(final Change change, final String project, final String admin) {
        return change(
                change,
                "project " + project,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    hasCard(admin);
                    final Map<String, String> members = new HashMap<>();
                    members.put(admin, "admin");
                    projects.put(project, members);
                });
    }

    @Override
    public Written removeProject(final Change change, final String project) {
        return change(
                change,
                "no project " + project,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    members(project);
                    projects.remove(project);
                });
    }

    @Override
    public Written changeProjectRepository(
            final Change change, final String project, final String address) {
        return change(
                change,
                "address " + project,
                () -> {
                    allowed(adminOf(project, acting(change)));
                    members(project);
                    opened.add(project);
                    addresses.put(project, address);
                });
    }

    @Override
    public Written addProjectMember(
            final Change change, final String project, final String login, final String role) {
        return change(
                change,
                "member " + project + " " + login + " " + role,
                () -> {
                    final String actor = acting(change);
                    final boolean ownerMay =
                            organizationAdmins.contains(actor)
                                    && ("admin".equals(role)
                                            || broken(WriteBreak.OWNER_ADDS_DEVELOPER));
                    allowed("admin".equals(role) ? ownerMay : adminOf(project, actor) || ownerMay);
                    hasCard(login);
                    if (ghosts.contains(project + "/" + login)) {
                        if (broken(WriteBreak.ANSWERS_NOTHING_AS_MADE)) {
                            return;
                        }
                        throw refuse(Refused.REFUSED_STATE);
                    }
                    members(project).put(login, role);
                    addedBy.put(project + "/" + login, change.acting().key());
                });
    }

    @Override
    public Written changeProjectMemberRole(
            final Change change, final String project, final String login, final String role) {
        return change(
                change,
                "role " + project + " " + login + " " + role,
                () -> {
                    memberAllowed(
                            project,
                            acting(change),
                            "admin".equals(role) || "admin".equals(members(project).get(login)));
                    members(project).put(login, role);
                });
    }

    @Override
    public Written removeProjectMember(
            final Change change, final String project, final String login) {
        return change(
                change,
                "no member " + project + " " + login,
                () -> {
                    final String actor = acting(change);
                    final Map<String, String> members = members(project);
                    final boolean ownerMay =
                            organizationAdmins.contains(actor)
                                    && ("admin".equals(members.get(login))
                                            || broken(WriteBreak.OWNER_ADDS_DEVELOPER));
                    final long admins = members.values().stream().filter("admin"::equals).count();
                    if ("admin".equals(members.get(login))
                            && admins == 1
                            && !owners.contains(actor)
                            && !broken(WriteBreak.REMOVES_THE_LAST)) {
                        throw refuse(Refused.LAST_OF_ITS_KIND);
                    }
                    if (locked.contains(project + "/" + actor)
                            && !broken(WriteBreak.LOCKED_ADMIN_ACTS)) {
                        throw refuse(Refused.LOCKED);
                    }
                    allowed(
                            "admin".equals(members.get(login))
                                    ? ownerMay
                                    : adminOf(project, actor) || ownerMay);
                    members.remove(login);
                });
    }

    @Override
    public Written lockProjectMember(
            final Change change, final String project, final String login) {
        return change(
                change,
                "lock " + project + " " + login,
                () -> {
                    memberAllowed(
                            project, acting(change), "admin".equals(members(project).get(login)));
                    locked.add(project + "/" + login);
                });
    }

    @Override
    public Written addHost(final Change change, final String host, final String admin) {
        return change(
                change,
                "host " + host,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    hasCard(admin);
                    hosts.put(host, new HashSet<>(Set.of(admin)));
                });
    }

    @Override
    public Written addHostAdmin(final Change change, final String host, final String login) {
        return change(
                change,
                "host admin " + host + " " + login,
                () -> {
                    final String actor = acting(change);
                    allowed(
                            organizationAdmins.contains(actor)
                                    || (broken(WriteBreak.OWNER_ON_ANY_HOST)
                                            && owners.contains(actor)));
                    hosts.get(host).add(login);
                });
    }

    @Override
    public Written addProjectToHost(final Change change, final String host, final String project) {
        return change(
                change,
                "on host " + host + " " + project,
                () -> {
                    final String actor = acting(change);
                    allowed(
                            hosts.getOrDefault(host, Set.of()).contains(actor)
                                    || broken(WriteBreak.OWNER_ON_ANY_HOST)
                                            && owners.contains(actor));
                    if (!opened.contains(project)
                            && !broken(WriteBreak.CARRIES_A_PROJECT_NEVER_OPENED)) {
                        throw refuse(Refused.NOT_OPENED);
                    }
                    hostOf.put(project, host);
                });
    }

    private static UnsupportedOperationException unasked() {
        return new UnsupportedOperationException("no check asks this of the stand-in");
    }

    @Override
    public Written changeProjectAdmin(
            final Change change, final String project, final String admin) {
        return change(
                change,
                "first admin " + project + " " + admin,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    members(project).put(admin, "admin");
                    refusals.clear();
                    ghosts.removeIf(member -> member.startsWith(project + "/"));
                });
    }

    @Override
    public Written unlockProjectMember(
            final Change change, final String project, final String login) {
        return change(
                change,
                "unlock " + project + " " + login,
                () -> {
                    memberAllowed(
                            project, acting(change), "admin".equals(members(project).get(login)));
                    locked.remove(project + "/" + login);
                });
    }

    @Override
    public Written removeHost(final Change change, final String host) {
        return change(
                change,
                "no host " + host,
                () -> {
                    final String actor = acting(change);
                    allowed(
                            organizationAdmins.contains(actor)
                                    || hosts.getOrDefault(host, Set.of()).contains(actor));
                    hosts.remove(host);
                    hostOf.values().removeIf(host::equals);
                });
    }

    @Override
    public Written changeHost(final Change change, final String host, final String card) {
        return change(
                change,
                "host card " + host,
                () -> {
                    final String actor = acting(change);
                    allowed(
                            hosts.getOrDefault(host, Set.of()).contains(actor)
                                    || broken(WriteBreak.OWNER_ON_ANY_HOST)
                                            && owners.contains(actor));
                    hostCards.put(host, card);
                });
    }

    @Override
    public Written removeHostAdmin(final Change change, final String host, final String login) {
        return change(
                change,
                "no host admin " + host + " " + login,
                () -> {
                    allowed(organizationAdmins.contains(acting(change)));
                    hosts.get(host).remove(login);
                });
    }

    @Override
    public Written removeProjectFromHost(
            final Change change, final String host, final String project) {
        return change(
                change,
                "off host " + host + " " + project,
                () -> {
                    final String actor = acting(change);
                    allowed(
                            hosts.getOrDefault(host, Set.of()).contains(actor)
                                    || broken(WriteBreak.OWNER_ON_ANY_HOST)
                                            && owners.contains(actor));
                    hostOf.remove(project);
                });
    }

    @Override
    public Written addBackupKey(
            final Change change, final @Nullable String host, final String key) {
        return change(
                change,
                "backup " + key,
                () -> {
                    final String actor = acting(change);
                    allowed(owners.contains(actor));
                    backups.put(actor, key);
                });
    }

    @Override
    public Written replaceKeyWithBackup(
            final Change change, final @Nullable String host, final String card) {
        return change(
                change,
                "backup takes " + card,
                () -> {
                    final String login = change.acting().login();
                    if (!change.acting().key().equals(backups.get(login))
                            && !broken(WriteBreak.ANY_KEY_TAKES_A_PLACE)) {
                        throw refuse(Refused.NOT_ALLOWED);
                    }
                    lostKeys.add(cards.put(login, card.split("\\|")[1]));
                    backups.remove(login);
                });
    }

    @Override
    public OrganizationWrites writes() {
        return this;
    }

    @Override
    public String key(final String holder) {
        return "ssh-ed25519 " + holder;
    }

    @Override
    public String card(final String login, final String holder) {
        return login + "|" + key(holder);
    }

    @Override
    public String hostCard(final String host, final String holder) {
        return host + "|" + key(holder);
    }

    @Override
    public String handedOn(final String login, final String holder, final String previous) {
        return card(login, holder) + "|" + key(previous);
    }

    @Override
    public void sourceLists(final String login, final String... holders) {
        listed.put(login, new HashSet<>(Arrays.stream(holders).map(this::key).toList()));
        if (holders.length == 1) {
            listedOnly.put(login, key(holders[0]));
        }
    }

    @Override
    public void sourceSaysOwner(final String login, final boolean owns) {
        sourceOwners.put(login, owns);
    }

    @Override
    public OrganizationQuestions read() {
        return new Answers();
    }

    /** Answers what the store holds now, and only what the checks ask. */
    private final class Answers implements OrganizationQuestions {

        @Override
        public @Nullable String revision() {
            return revisionNow();
        }

        @Override
        public boolean isOrganizationAdmin(final String login) {
            return organizationAdmins.contains(login);
        }

        @Override
        public java.util.Optional<java.util.List<String>> getOrganizationAdmins() {
            return Optional.of(List.copyOf(organizationAdmins));
        }

        @Override
        public boolean isOrganizationOwner(final String login) {
            return owners.contains(login);
        }

        @Override
        public Optional<String> isProjectMember(final String project, final String login) {
            return locked.contains(project + "/" + login)
                    ? Optional.empty()
                    : Optional.ofNullable(projects.getOrDefault(project, Map.of()).get(login));
        }

        @Override
        public boolean isHostAdmin(final String host, final String login) {
            return hosts.getOrDefault(host, Set.of()).contains(login);
        }

        @Override
        public Optional<List<String>> projectAdmins(final String project) {
            if (!projects.containsKey(project)) {
                return Optional.empty();
            }
            final List<String> admins = new ArrayList<>();
            projects.getOrDefault(project, Map.of())
                    .forEach(
                            (login, role) -> {
                                if ("admin".equals(role)) {
                                    admins.add(login);
                                }
                            });
            return Optional.of(admins);
        }

        @Override
        public Optional<String> hostOf(final String project) {
            return Optional.ofNullable(hostOf.get(project));
        }

        @Override
        public List<Organization.Refusal> getRefusals() {
            return broken(WriteBreak.READER_REFUSES_WHAT_IT_WROTE) && revision > 1
                    ? List.of(new Organization.Refusal("r" + revision, "the change", "refused"))
                    : List.copyOf(refusals);
        }

        @Override
        public List<Person> getPersons() {
            final List<Person> persons = new ArrayList<>();
            cards.forEach(
                    (login, key) ->
                            persons.add(
                                    new Person(
                                            login,
                                            login,
                                            "",
                                            key,
                                            "SHA256:" + key,
                                            Organization.TRUSTED,
                                            LocalDate.of(2030, 1, 1),
                                            false,
                                            "")));
            return persons;
        }

        @Override
        public List<Organization.Owner> getOrganizationOwners() {
            return owners.stream()
                    .map(
                            login ->
                                    new Organization.Owner(
                                            login,
                                            cards.get(login),
                                            "SHA256:" + cards.get(login),
                                            false))
                    .toList();
        }

        @Override
        public Optional<Reach> reach(final String host) {
            throw unasked();
        }

        @Override
        public List<String> hostsFor(final String login) {
            throw unasked();
        }

        @Override
        public Optional<String> getProjectRepository(final String project) {
            return Optional.ofNullable(addresses.get(project));
        }

        @Override
        public List<String> getWarnings() {
            throw unasked();
        }

        @Override
        public Description describe() {
            throw unasked();
        }

        @Override
        public Trust trusts(final String login, final String key) {
            throw unasked();
        }

        @Override
        public Optional<String> keyHolder(final String key) {
            throw unasked();
        }

        @Override
        public List<SigningKey> signingKeys(final String project) {
            throw unasked();
        }

        @Override
        public List<Project> getProjects() {
            throw unasked();
        }

        @Override
        public Optional<List<Member>> getProjectMembers(final String project) {
            throw unasked();
        }

        @Override
        public List<Host> getHosts() {
            throw unasked();
        }

        @Override
        public Optional<String> getCard(final String login) {
            throw unasked();
        }

        @Override
        public Optional<String> getHostCard(final String host) {
            return Optional.ofNullable(hostCards.get(host));
        }

        @Override
        public List<Organization.Sole> getSoleWithoutBackup() {
            throw unasked();
        }
    }
}
