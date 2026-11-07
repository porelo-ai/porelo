package org.fuin.porelo.organization.contract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory store, the attacker a change on its line: honest with no {@link Break}, and with one
 * it accepts what it must refuse. It asks no identity source: the checks are about what a store
 * accepts, not about who vouches.
 */
final class StandIn implements OrganizationFixture, OfflineFixture {

    private enum Kind {
        FOUND,
        CARD,
        PROJECT,
        FIRST_SEEN_CARD,
        DENIED_CARD,
        SQUATTED_CARD,
        REMOVED_CARD,
        MEMBER,
        LOCK,
        UNLOCK,
        OWNER,
        ORGANIZATION_ADMINS,
        REPLACE,
        HOST,
        HOST_ADMIN,
        HOST_CHANGE,
        HOST_REPOSITORIES
    }

    /** One change on the line; its id says it apart from any other with the same content. */
    private record Entry(int id, Kind kind, @Nullable String signer, List<String> args) {}

    private final Set<Break> breaks;

    private final List<Entry> line = new ArrayList<>();

    private List<Entry> record = List.of();

    private int nextId;

    private final Map<String, Integer> generations = new HashMap<>();

    StandIn(final Break... breaks) {
        this.breaks =
                breaks.length == 0
                        ? EnumSet.noneOf(Break.class)
                        : EnumSet.copyOf(Arrays.asList(breaks));
    }

    private boolean broken(final Break what) {
        return breaks.contains(what);
    }

    private String add(final Kind kind, final @Nullable String signer, final String... args) {
        final Entry entry = new Entry(nextId++, kind, signer, List.of(args));
        line.add(entry);
        return id(entry);
    }

    private String keyOf(final String login, final int generation) {
        return "ssh-ed25519 " + login + "-" + generation;
    }

    @Override
    public String key(final String login) {
        return keyOf(login, generations.getOrDefault(login, 0));
    }

    @Override
    public String fingerprint(final String login) {
        return "SHA256:" + key(login);
    }

    /** What a walk of a line accepted. */
    private final class Walk {
        final Set<String> owners = new LinkedHashSet<>();
        Optional<List<String>> organizationAdmins = Optional.empty();
        final Map<String, String> cards = new HashMap<>();
        final Map<String, Set<String>> holders = new HashMap<>();
        final Set<String> revoked = new HashSet<>();
        final Map<String, Map<String, String>> members = new HashMap<>();
        final Set<String> locked = new HashSet<>();
        final Set<String> firstSeen = new HashSet<>();
        final Map<String, Set<String>> hostAdmins = new HashMap<>();
        final Map<String, List<String>> carried = new HashMap<>();
        final Set<String> pin;
        final List<Organization.Refusal> refusals = new ArrayList<>();
        final List<String> refusedKinds = new ArrayList<>();

        Walk(final List<Entry> entries, final int trustedUpTo) {
            this(entries, trustedUpTo, Set.of());
        }

        Walk(final List<Entry> entries, final int trustedUpTo, final Set<String> pin) {
            this.pin = pin;
            for (int i = 0; i < entries.size(); i++) {
                if (i == trustedUpTo && broken(Break.FORGETS_REVOCATION_ACROSS_A_READ)) {
                    revoked.clear();
                }
                final Entry entry = entries.get(i);
                final boolean skipped =
                        broken(Break.JUDGES_ONLY_THE_HEAD) && i < entries.size() - 1;
                final @Nullable String why = skipped ? null : judge(entry);
                if (why == null) {
                    apply(entry);
                } else {
                    refusedKinds.add(entry.kind().name());
                    if (!broken(Break.SAYS_NOTHING_OF_A_REFUSAL)) {
                        final Entry named =
                                broken(Break.NAMES_ANOTHER_REVISION) ? entries.get(0) : entry;
                        refusals.add(new Organization.Refusal(id(named), "the change", why));
                    }
                }
            }
        }

        private Set<String> signerLogins(final Entry entry) {
            return entry.signer() == null
                    ? Set.of()
                    : holders.getOrDefault(entry.signer(), Set.of());
        }

        private void hold(final String key, final String login) {
            holders.computeIfAbsent(key, held -> new HashSet<>()).add(login);
        }

        private @Nullable String judge(final Entry entry) {
            final List<String> args = entry.args();
            return switch (entry.kind()) {
                case FOUND, FIRST_SEEN_CARD -> null;
                case CARD -> judgeCard(entry);
                case OWNER, ORGANIZATION_ADMINS -> judgeBy(entry, owners);
                case PROJECT, REPLACE, HOST -> judgeBy(entry, organizationAdmins());
                case HOST_ADMIN ->
                        organizationAdmins.isPresent()
                                ? judgeBy(entry, organizationAdmins())
                                : judgeHostChange(entry);
                case DENIED_CARD ->
                        broken(Break.ACCEPTS_CARD_THE_SOURCE_DENIES)
                                ? null
                                : "the source denies it";
                case SQUATTED_CARD ->
                        broken(Break.ACCEPTS_SQUATTED_CARD) ? null : "the source does not list it";
                case REMOVED_CARD -> judgeRemoval(entry);
                case MEMBER -> judgeMember(entry);
                case LOCK, UNLOCK -> judgeLock(entry);
                case HOST_CHANGE, HOST_REPOSITORIES -> judgeHostChange(entry);
            };
        }

        private Set<String> organizationAdmins() {
            return organizationAdmins.map(HashSet::new).orElseGet(() -> new HashSet<>(owners));
        }

        private boolean lockKey(final String project, final String login) {
            return locked.contains(project + "/" + login);
        }

        /** The project's unlocked admins, who alone change its members. */
        private Set<String> admins(final String project) {
            final Set<String> admins = new HashSet<>();
            members.getOrDefault(project, Map.of())
                    .forEach(
                            (login, role) -> {
                                final boolean counts =
                                        !lockKey(project, login)
                                                || broken(Break.ACCEPTS_LOCKED_ADMIN);
                                if ("admin".equals(role) && counts) {
                                    admins.add(login);
                                }
                            });
            return admins;
        }

        private @Nullable String judgeMember(final Entry entry) {
            final String project = entry.args().get(0);
            final String role = entry.args().get(2);
            if (broken(Break.NONE_OF_A_MEMBERS_CHANGES) && "developer".equals(role)) {
                return "refused";
            }
            final Set<String> allowed =
                    organizationAdmins.isPresent()
                                    && ("admin".equals(role)
                                            || "admin"
                                                    .equals(
                                                            members.getOrDefault(project, Map.of())
                                                                    .get(entry.args().get(1))))
                            ? organizationAdmins()
                            : admins(project);
            if (broken(Break.ADMIN_OF_ANY_PROJECT)) {
                members.keySet().forEach(other -> allowed.addAll(admins(other)));
            }
            final boolean namedAdmin = "admin".equals(role) && !broken(Break.OWNER_NAMES_NO_ADMIN);
            if (organizationAdmins.isEmpty() && namedAdmin || broken(Break.OWNER_ADDS_DEVELOPER)) {
                allowed.addAll(owners);
            }
            return judgeBy(entry, allowed);
        }

        private @Nullable String judgeLock(final Entry entry) {
            final String project = entry.args().get(0);
            final Set<String> allowed =
                    organizationAdmins.isPresent()
                                    && "admin"
                                            .equals(
                                                    members.getOrDefault(project, Map.of())
                                                            .get(entry.args().get(1)))
                            ? organizationAdmins()
                            : admins(project);
            if (broken(Break.OWNER_TOUCHES_LOCKS)) {
                allowed.addAll(owners);
            }
            return judgeBy(entry, allowed);
        }

        private @Nullable String judgeCard(final Entry entry) {
            final String login = entry.args().get(0);
            final String key = entry.args().get(1);
            if (!key.equals(entry.signer()) && !broken(Break.ACCEPTS_CARD_FILED_BY_ANOTHER)) {
                return "signed by another key";
            }
            if (revoked.contains(key)) {
                return broken(Break.ACCEPTS_PLAYED_BACK_CARD) ? null : "signed by a revoked key";
            }
            final @Nullable String held = cards.get(login);
            final boolean another = held != null && !held.equals(key);
            return another && !broken(Break.ACCEPTS_CARD_TAKEN_OVER)
                    ? "the card holds another key"
                    : null;
        }

        private @Nullable String judgeRemoval(final Entry entry) {
            final boolean own =
                    String.valueOf(entry.signer()).equals(cards.get(entry.args().get(0)));
            final boolean byOwner =
                    signerLogins(entry).stream().anyMatch(organizationAdmins()::contains);
            return own || byOwner || broken(Break.ACCEPTS_REMOVAL_BY_ANYONE) ? null : "not allowed";
        }

        private @Nullable String judgeBy(final Entry entry, final Set<String> allowed) {
            if (entry.signer() == null) {
                return broken(Break.ACCEPTS_UNSIGNED) ? null : "unsigned";
            }
            if (revoked.contains(entry.signer()) && !broken(Break.ACCEPTS_REVOKED_KEY)) {
                return "signed by a revoked key";
            }
            if (broken(Break.ACCEPTS_ANY_SIGNER)) {
                return null;
            }
            if (entry.kind() == Kind.REPLACE && broken(Break.ACCEPTS_REPLACEMENT_BY_ANYONE)) {
                return null;
            }
            return signerLogins(entry).stream().anyMatch(allowed::contains) ? null : "not allowed";
        }

        private @Nullable String judgeHostChange(final Entry entry) {
            if (entry.signer() == null) {
                return broken(Break.OFFLINE_ACCEPTS_UNSIGNED) ? null : "unsigned";
            }
            final Set<String> admins = hostAdmins.getOrDefault(entry.args().get(0), Set.of());
            final boolean allowed = signerLogins(entry).stream().anyMatch(admins::contains);
            final boolean owner =
                    broken(Break.OFFLINE_OWNER_PASSES_THE_PIN)
                            && signerLogins(entry).stream().anyMatch(owners::contains);
            final boolean inSet =
                    broken(Break.OFFLINE_TRUSTS_THE_WHOLE_SET)
                            && pin.contains("SHA256:" + entry.signer());
            return allowed || owner || inSet || broken(Break.OFFLINE_ACCEPTS_ANY_SIGNER)
                    ? null
                    : "not allowed";
        }

        private void apply(final Entry entry) {
            final List<String> args = entry.args();
            switch (entry.kind()) {
                case FOUND -> {
                    owners.add(args.get(0));
                    cards.put(args.get(0), args.get(1));
                    hold(args.get(1), args.get(0));
                }
                case CARD, DENIED_CARD, SQUATTED_CARD -> {
                    cards.put(args.get(0), args.get(1));
                    hold(args.get(1), args.get(0));
                    revoked.remove(args.get(1));
                }
                case FIRST_SEEN_CARD -> {
                    cards.put(args.get(0), args.get(1));
                    hold(args.get(1), args.get(0));
                    firstSeen.add(args.get(0));
                }
                case REMOVED_CARD -> cards.remove(args.get(0));
                case LOCK -> locked.add(args.get(0) + "/" + args.get(1));
                case UNLOCK -> locked.remove(args.get(0) + "/" + args.get(1));
                case OWNER -> owners.add(args.get(0));
                case ORGANIZATION_ADMINS -> organizationAdmins = Optional.of(List.copyOf(args));
                case PROJECT ->
                        members.computeIfAbsent(args.get(0), p -> new HashMap<>())
                                .put(args.get(1), "admin");
                case MEMBER ->
                        members.computeIfAbsent(args.get(0), p -> new HashMap<>())
                                .put(args.get(1), args.get(2));
                case REPLACE -> {
                    revoked.add(cards.get(args.get(0)));
                    cards.put(args.get(0), args.get(1));
                    hold(args.get(1), args.get(0));
                }
                case HOST ->
                        hostAdmins
                                .computeIfAbsent(args.get(0), host -> new HashSet<>())
                                .add(args.get(1));
                case HOST_ADMIN -> {
                    if (!broken(Break.OFFLINE_PIN_STAYS_PUT)) {
                        hostAdmins.get(args.get(0)).add(args.get(1));
                    }
                }
                case HOST_CHANGE -> {
                    // A change that was accepted says nothing a check asks about.
                }
                case HOST_REPOSITORIES -> carried.put(args.get(1), args.subList(2, args.size()));
            }
        }
    }

    private static String id(final Entry entry) {
        return "r" + entry.id();
    }

    private static boolean continues(final List<Entry> served, final List<Entry> trusted) {
        return served.size() >= trusted.size() && served.subList(0, trusted.size()).equals(trusted);
    }

    private boolean toleratedThough(final List<Entry> served, final List<Entry> trusted) {
        if (trusted.subList(0, Math.min(served.size(), trusted.size())).equals(served)) {
            return broken(Break.ACCEPTS_OLDER_STATE);
        }
        final boolean droppedARevocation =
                trusted.stream()
                        .filter(entry -> !served.contains(entry))
                        .anyMatch(entry -> entry.kind() == Kind.REPLACE);
        return droppedARevocation
                ? broken(Break.ACCEPTS_DISCARDED_REVOCATION)
                : broken(Break.ACCEPTS_REWRITTEN_HISTORY);
    }

    @Override
    public OrganizationQuestions read() {
        final List<Entry> served = List.copyOf(line);
        if (!continues(served, record) && !toleratedThough(served, record)) {
            if (broken(Break.FORGETS_ON_REFUSAL)) {
                record = served;
            }
            throw new Refused(Refused.REFUSED_STATE, "does not continue what was trusted", null);
        }
        final int trustedUpTo = record.size();
        record = served;
        return new Answers(new Walk(served, trustedUpTo), served);
    }

    @Override
    public void found(final String owner) {
        add(Kind.FOUND, key(owner), owner, key(owner));
    }

    void listOrganizationAdmins(final String owner, final String... logins) {
        add(Kind.ORGANIZATION_ADMINS, key(owner), logins);
    }

    @Override
    public void fileCard(final String login) {
        add(Kind.CARD, key(login), login, key(login));
    }

    @Override
    public void fileCardWithKeyOf(final String login, final String holder) {
        add(Kind.CARD, key(holder), login, key(holder));
    }

    @Override
    public void makeProject(final String owner, final String project, final String admin) {
        add(Kind.PROJECT, key(owner), project, admin);
    }

    @Override
    public void addMember(
            final String admin, final String project, final String login, final String role) {
        add(Kind.MEMBER, key(admin), project, login, role);
    }

    @Override
    public void replaceLostKey(final String owner, final String login) {
        generations.merge(login, 1, Integer::sum);
        add(Kind.REPLACE, key(owner), login, key(login));
    }

    @Override
    public void addOwner(final String owner, final String login) {
        add(Kind.OWNER, key(owner), login);
    }

    @Override
    public void ownerNamesAdmin(final String owner, final String project, final String login) {
        add(Kind.MEMBER, key(owner), project, login, "admin");
    }

    @Override
    public void lockMember(final String admin, final String project, final String login) {
        add(Kind.LOCK, key(admin), project, login);
    }

    @Override
    public String adminNobodyAllowed(
            final @Nullable String signer, final String project, final String login) {
        return add(Kind.MEMBER, signer == null ? null : key(signer), project, login, "admin");
    }

    @Override
    public String developerNobodyAllowed(
            final @Nullable String signer, final String project, final String login) {
        return add(Kind.MEMBER, signer == null ? null : key(signer), project, login, "developer");
    }

    @Override
    public String lockNobodyAllowed(final String signer, final String project, final String login) {
        return add(Kind.LOCK, key(signer), project, login);
    }

    @Override
    public String unlockNobodyAllowed(
            final String signer, final String project, final String login) {
        return add(Kind.UNLOCK, key(signer), project, login);
    }

    @Override
    public String ownerNobodyAllowed(final @Nullable String signer, final String login) {
        return add(Kind.OWNER, signer == null ? null : key(signer), login);
    }

    private String lost(final String login) {
        return keyOf(login, generations.get(login) - 1);
    }

    @Override
    public String adminWithLostKey(
            final String lostKeyOf, final String project, final String login) {
        return add(Kind.MEMBER, lost(lostKeyOf), project, login, "admin");
    }

    @Override
    public String ownerWithLostKey(final String lostKeyOf, final String login) {
        return add(Kind.OWNER, lost(lostKeyOf), login);
    }

    @Override
    public String playBackOlderCard(final String login) {
        return add(Kind.CARD, lost(login), login, lost(login));
    }

    @Override
    public String cardNobodyAllowed(final String signer, final String login) {
        return add(Kind.CARD, key(signer), login, key(signer));
    }

    @Override
    public String cardFiledByAnother(final String signer, final String login) {
        return add(Kind.CARD, key(signer), login, key(login));
    }

    @Override
    public String removeCardNobodyAllowed(final String signer, final String login) {
        return add(Kind.REMOVED_CARD, key(signer), login);
    }

    @Override
    public String replacementNobodyAllowed(final String signer, final String login) {
        return add(Kind.REPLACE, key(signer), login, keyOf(signer + "-as-" + login, 0));
    }

    @Override
    public String squatFirstCard(final String signer, final String login) {
        return add(Kind.SQUATTED_CARD, key(signer), login, key(signer));
    }

    @Override
    public String fileCardTheSourceDenies(final String login) {
        return add(Kind.DENIED_CARD, key(login), login, key(login));
    }

    @Override
    public void serveOlderState() {
        line.remove(line.size() - 1);
    }

    @Override
    public void rewriteHistory() {
        line.remove(line.size() - 1);
        fileCard("rewriter");
    }

    @Override
    public void discardRevocation() {
        rewriteHistory();
    }

    @Override
    public void hostWithAdmin(
            final String owner, final String host, final String admin, final String project) {
        found(owner);
        fileCard(admin);
        makeProject(owner, project, admin);
        add(Kind.HOST, key(owner), host, admin);
    }

    @Override
    public void adminByOwner(final String owner, final String host, final String login) {
        add(Kind.HOST_ADMIN, key(owner), host, login);
    }

    @Override
    public void firstSeenPerson(
            final String admin, final String host, final String project, final String login) {
        add(Kind.MEMBER, key(admin), project, login, "developer");
        add(Kind.FIRST_SEEN_CARD, key(login), login, key(login));
        add(Kind.HOST_ADMIN, key(admin), host, login);
    }

    @Override
    public void handOverHost(final String admin, final String host, final String login) {
        add(Kind.HOST_ADMIN, key(admin), host, login);
        add(Kind.HOST_CHANGE, key(login), host);
    }

    @Override
    public void carrySome(
            final String admin,
            final String host,
            final String project,
            final List<String> repositories) {
        final List<String> args = new ArrayList<>(List.of(host, project));
        args.addAll(repositories);
        add(Kind.HOST_REPOSITORIES, key(admin), args.toArray(String[]::new));
    }

    @Override
    public void hostChangeNobodyAllowed(final @Nullable String signer, final String host) {
        add(Kind.HOST_CHANGE, signer == null ? null : key(signer), host);
    }

    @Override
    public OfflineOrganization copy() {
        final List<Entry> brought = List.copyOf(line);
        return new OfflineOrganization() {
            @Override
            public Map<String, Object> check(
                    final String host, final String pinned, final String since) throws IOException {
                return check(host, Set.of(pinned), since);
            }

            @Override
            public Map<String, Object> check(
                    final String host, final Set<String> pinned, final String since)
                    throws IOException {
                return StandIn.this.check(brought, host, pinned, since);
            }
        };
    }

    private Map<String, Object> check(
            final List<Entry> brought,
            final String host,
            final Set<String> pinned,
            final String since)
            throws IOException {
        if (broken(Break.OFFLINE_REFUSES_THE_PIN)) {
            throw refused("the pin");
        }
        final Walk walk = new Walk(brought, brought.size(), pinned);
        final Set<String> admins = walk.hostAdmins.get(host);
        if (admins == null) {
            throw new Refused(Refused.NOT_FOUND, "no such host", null);
        }
        final boolean forThePin =
                admins.stream().anyMatch(each -> pinned.contains("SHA256:" + walk.cards.get(each)));
        if (!forThePin && !broken(Break.OFFLINE_IGNORES_THE_PIN)) {
            throw refused("another key is pinned");
        }
        if (!walk.refusals.isEmpty() || !walk.refusedKinds.isEmpty()) {
            throw refused("a change nobody allowed");
        }
        final String at = id(brought.get(brought.size() - 1));
        if (!since.isEmpty() && !since.equals(at)) {
            refuseUnlessItContinues(brought, since);
        }
        if (broken(Break.OFFLINE_ANSWERS_NOTHING)) {
            return Map.of(
                    "revision",
                    at,
                    "host_card",
                    Map.of(),
                    "admins",
                    Map.of(),
                    "projects",
                    List.of(),
                    "persons",
                    List.of());
        }
        return Map.of(
                "revision",
                at,
                "host_card",
                Map.of("host", host, "address", host + ".example.org"),
                "admins",
                admins.stream()
                        .filter(each -> given(walk, each, Break.OFFLINE_GIVES_FIRST_SEEN_ADMIN))
                        .map(
                                each ->
                                        Map.of(
                                                "login",
                                                each,
                                                "fingerprint",
                                                "SHA256:" + walk.cards.get(each)))
                        .toList(),
                "projects",
                walk.members.keySet().stream().map(name -> project(walk, name)).toList(),
                "persons",
                walk.cards.keySet().stream()
                        .filter(each -> given(walk, each, Break.OFFLINE_GIVES_FIRST_SEEN_PERSON))
                        .map(each -> Map.of("login", each, "revoked", false))
                        .toList());
    }

    private boolean given(final Walk walk, final String login, final Break unless) {
        return !walk.firstSeen.contains(login) || broken(unless);
    }

    private Map<String, Object> project(final Walk walk, final String name) {
        final List<Map<String, String>> members =
                walk.members.get(name).keySet().stream()
                        .filter(
                                each ->
                                        !walk.firstSeen.contains(each)
                                                || !broken(Break.OFFLINE_DROPS_A_FIRST_SEEN_ROLE))
                        .map(each -> Map.of("login", each))
                        .toList();
        final List<Map<String, String>> signingKeys =
                walk.members.get(name).keySet().stream()
                        .filter(each -> given(walk, each, Break.OFFLINE_GIVES_FIRST_SEEN_SIGNER))
                        .map(each -> Map.of("login", each))
                        .toList();
        final Map<String, Object> row = new HashMap<>();
        row.put("project", name);
        row.put("members", members);
        row.put("signing_keys", signingKeys);
        final List<String> some = walk.carried.get(name);
        if (some != null && !broken(Break.OFFLINE_CARRIES_WHOLE)) {
            row.put("repositories", some);
        }
        return row;
    }

    private void refuseUnlessItContinues(final List<Entry> brought, final String since) {
        final boolean onTheLine = brought.stream().anyMatch(entry -> id(entry).equals(since));
        if (onTheLine) {
            return;
        }
        final int number = Integer.parseInt(since.substring(1));
        final boolean older = brought.stream().allMatch(entry -> entry.id() < number);
        if (older && !broken(Break.OFFLINE_ACCEPTS_OLDER_STATE)) {
            throw refused("older than what was applied");
        }
        if (!older && !broken(Break.OFFLINE_ACCEPTS_REWRITTEN_HISTORY)) {
            throw refused("off the line");
        }
    }

    private static Refused refused(final String why) {
        return new Refused(Refused.REFUSED_STATE, why, null);
    }

    /** The answers of one walk; a question no check asks fails, rather than answering wrongly. */
    private static final class Answers implements OrganizationQuestions {
        private final StandIn.Walk walk;
        private final List<Entry> served;

        Answers(final StandIn.Walk walk, final List<Entry> served) {
            this.walk = walk;
            this.served = served;
        }

        private static UnsupportedOperationException unasked() {
            return new UnsupportedOperationException("no check asks this of the stand-in");
        }

        @Override
        public @Nullable String revision() {
            return served.isEmpty() ? null : id(served.get(served.size() - 1));
        }

        @Override
        public boolean isOrganizationAdmin(final String login) {
            return walk.organizationAdmins.isEmpty()
                    ? isOrganizationOwner(login)
                    : walk.organizationAdmins().contains(login)
                            && walk.cards.containsKey(login)
                            && !walk.firstSeen.contains(login);
        }

        @Override
        public java.util.Optional<java.util.List<String>> getOrganizationAdmins() {
            return walk.organizationAdmins;
        }

        @Override
        public boolean isOrganizationOwner(final String login) {
            return walk.owners.contains(login);
        }

        @Override
        public Optional<String> isProjectMember(final String project, final String login) {
            return walk.cards.containsKey(login) && !walk.lockKey(project, login)
                    ? Optional.ofNullable(walk.members.getOrDefault(project, Map.of()).get(login))
                    : Optional.empty();
        }

        @Override
        public boolean isHostAdmin(final String host, final String login) {
            throw unasked();
        }

        @Override
        public Optional<List<String>> projectAdmins(final String project) {
            final Map<String, String> members = walk.members.get(project);
            return members == null
                    ? Optional.empty()
                    : Optional.of(
                            members.entrySet().stream()
                                    .filter(entry -> "admin".equals(entry.getValue()))
                                    .map(Map.Entry::getKey)
                                    .sorted()
                                    .toList());
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
        public Optional<String> hostOf(final String project) {
            throw unasked();
        }

        @Override
        public Optional<String> getProjectRepository(final String project) {
            throw unasked();
        }

        @Override
        public List<Organization.Refusal> getRefusals() {
            return List.copyOf(walk.refusals);
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
            return key.equals(walk.cards.get(login))
                    ? new Trust(Trust.YES, "")
                    : new Trust(Trust.NO, "not the key on the card");
        }

        @Override
        public Optional<String> keyHolder(final String key) {
            return walk.cards.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(key))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        @Override
        public List<SigningKey> signingKeys(final String project) {
            throw unasked();
        }

        @Override
        public List<Person> getPersons() {
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
        public List<Organization.Owner> getOrganizationOwners() {
            throw unasked();
        }

        @Override
        public Optional<String> getCard(final String login) {
            throw unasked();
        }

        @Override
        public Optional<String> getHostCard(final String host) {
            throw unasked();
        }

        @Override
        public List<Organization.Sole> getSoleWithoutBackup() {
            throw unasked();
        }
    }
}
