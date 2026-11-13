package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.contract.OfflineFixture;
import org.fuin.porelo.organization.contract.OrganizationFixture;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.jspecify.annotations.Nullable;

/**
 * The contract's fixture on a real repository: people's allowed changes and the attacker are both
 * raw commits, since nothing goes through the adapter, and every key is made in the run.
 */
final class GitTreeFixture implements OrganizationFixture, OfflineFixture {

    private static final String ORGANISATION = "acme";

    private static final String REWRITER = "rewriter";

    private final Path dir;

    private final Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, SigningKey> keys = new HashMap<>();

    private final Map<String, String> current = new HashMap<>();

    private final Map<String, Map<String, String>> members = new HashMap<>();

    private final Forge forge = new Forge();

    private final Map<String, Set<String>> locked = new HashMap<>();

    private final List<String> ownerLogins = new ArrayList<>();

    private final List<String> hostAdmins = new ArrayList<>();

    private String hostProjects = "[]";

    /**
     * An identity source that answers what the fixture sets, and "could not be asked" otherwise.
     */
    private static final class Forge implements IdentitySource {
        final Map<String, List<String>> keys = new HashMap<>();
        final Map<String, Boolean> owners = new HashMap<>();

        @Override
        public Optional<Keys> keys(final String login) {
            return Optional.ofNullable(keys.get(login)).map(listed -> new Keys(listed, true));
        }

        @Override
        public Optional<Boolean> owns(final String organisation, final String login) {
            return Optional.ofNullable(owners.get(login));
        }
    }

    GitTreeFixture(final Path dir) throws IOException {
        this.dir = dir;
        this.repo = Files.createDirectory(dir.resolve("organization"));
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Organization");
        git("config", "user.email", "organization@example.org");
        git("config", "gpg.format", "ssh");
    }

    private String git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(
                        Command.of(all)
                                .in(repo)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    /** Makes the key file of this name the first time it is asked for. */
    private SigningKey keyNamed(final String name) {
        return keys.computeIfAbsent(
                name,
                absent -> {
                    final Path file = dir.resolve(name);
                    runner.runOrFail(
                            Command.of(
                                    "ssh-keygen",
                                    "-q",
                                    "-t",
                                    "ed25519",
                                    "-N",
                                    "",
                                    "-f",
                                    file.toString()));
                    keyFiles.put(name, file);
                    try {
                        return new SigningKey(
                                Base64.getDecoder()
                                        .decode(
                                                OpenSshPrivateKey.seedBase64(
                                                        Files.readString(file))),
                                "test");
                    } catch (final IOException e) {
                        throw new IllegalStateException("the key just made cannot be read", e);
                    }
                });
    }

    private String nameOf(final String login) {
        return current.getOrDefault(login, login);
    }

    private String line(final String name) {
        return PersonCard.keyLine(keyNamed(name));
    }

    private void write(final String path, final String text) throws IOException {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Commits everything, signed by the key of this name, or unsigned for {@code null}. */
    private String commit(final @Nullable String name) {
        git("add", "-A");
        if (name == null) {
            git("commit", "-q", "--allow-empty", "-m", "change");
        } else {
            keyNamed(name);
            git(
                    "-c",
                    "user.signingkey=" + keyFiles.get(name),
                    "commit",
                    "-q",
                    "--allow-empty",
                    "-S",
                    "-m",
                    "change");
        }
        return git("rev-parse", "HEAD");
    }

    private String card(final String login, final String name) {
        return PersonCard.write(
                login,
                login,
                "",
                keyNamed(name),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                null);
    }

    private String owners(final List<String> logins) {
        final StringBuilder text = new StringBuilder("owners:\n");
        for (final String login : logins) {
            text.append("  - login: ")
                    .append(login)
                    .append("\n    key: \"")
                    .append(key(login))
                    .append("\"\n");
        }
        return text.toString();
    }

    private void writeMembers(
            final String project, final Map<String, String> listed, final Set<String> lockedHere)
            throws IOException {
        final StringBuilder text = new StringBuilder("members:\n");
        listed.forEach(
                (login, role) -> {
                    text.append("  - login: ")
                            .append(login)
                            .append("\n    role: ")
                            .append(role)
                            .append('\n');
                    if (lockedHere.contains(login)) {
                        text.append("    locked: true\n");
                    }
                });
        write("projects/" + project + "/members.yml", text.toString());
    }

    private Set<String> lockedIn(final String project) {
        return locked.computeIfAbsent(project, absent -> new TreeSet<>());
    }

    private void writeMembers(final String project) throws IOException {
        writeMembers(project, members.get(project), lockedIn(project));
    }

    /** Lists a member in one change only, which the fixture does not take as what stands. */
    private String listMember(
            final String project,
            final String login,
            final String role,
            final @Nullable String name)
            throws IOException {
        final Map<String, String> listed = new LinkedHashMap<>(members.get(project));
        listed.put(login, role);
        writeMembers(project, listed, lockedIn(project));
        return commit(name);
    }

    private String withLock(
            final String project, final String login, final boolean on, final String name)
            throws IOException {
        final Set<String> asked = new TreeSet<>(lockedIn(project));
        if (on) {
            asked.add(login);
        } else {
            asked.remove(login);
        }
        writeMembers(project, members.get(project), asked);
        return commit(name);
    }

    private String ownersWith(final String login, final @Nullable String name) throws IOException {
        final List<String> logins = new ArrayList<>(ownerLogins);
        logins.add(login);
        write("owners.yml", owners(logins));
        return commit(name);
    }

    @Override
    public OrganizationQuestions read() throws IOException {
        return new GitTreeStore(
                        runner,
                        repo.toString(),
                        ORGANISATION,
                        "github.com",
                        FetchAccess.NONE,
                        dir.resolve("state"))
                .read(forge);
    }

    @Override
    public String key(final String login) {
        return line(nameOf(login));
    }

    @Override
    public void found(final String owner) throws IOException {
        ownerLogins.add(owner);
        forge.owners.put(owner, true);
        forge.keys.put(owner, List.of(key(owner)));
        write("owners.yml", owners(ownerLogins));
        commit(nameOf(owner));
    }

    @Override
    public void fileCard(final String login) throws IOException {
        forge.keys.put(login, List.of(key(login)));
        write("people/" + login + ".card", card(login, nameOf(login)));
        commit(nameOf(login));
    }

    void fileCardNamed(final String login, final String display) throws IOException {
        write(
                "people/" + login + ".card",
                PersonCard.write(
                        login,
                        display,
                        "",
                        keyNamed(nameOf(login)),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));
        commit(nameOf(login));
    }

    @Override
    public void fileCardWithKeyOf(final String login, final String holder) throws IOException {
        forge.keys.put(login, List.of(key(holder)));
        write("people/" + login + ".card", card(login, nameOf(holder)));
        commit(nameOf(holder));
    }

    @Override
    public void makeProject(final String owner, final String project, final String admin)
            throws IOException {
        write("projects/" + project + "/card", "name: " + project + "\nadmin: " + admin + "\n");
        commit(nameOf(owner));
        members.put(project, new LinkedHashMap<>(Map.of(admin, "admin")));
        writeMembers(project);
        commit(nameOf(admin));
    }

    @Override
    public void addMember(
            final String admin, final String project, final String login, final String role)
            throws IOException {
        members.get(project).put(login, role);
        writeMembers(project);
        commit(nameOf(admin));
    }

    @Override
    public void replaceLostKey(final String owner, final String login) throws IOException {
        final String lost = nameOf(login);
        final String replacement = lost + "-new";
        forge.keys.put(login, List.of(line(lost), line(replacement)));
        current.put(login, replacement);
        write("people/" + login + ".card", card(login, replacement));
        commit(nameOf(owner));
    }

    @Override
    public void addOwner(final String owner, final String login) throws IOException {
        forge.owners.put(login, true);
        ownersWith(login, nameOf(owner));
        ownerLogins.add(login);
    }

    @Override
    public void ownerNamesAdmin(final String owner, final String project, final String login)
            throws IOException {
        addMember(owner, project, login, "admin");
    }

    @Override
    public void lockMember(final String admin, final String project, final String login)
            throws IOException {
        lockedIn(project).add(login);
        writeMembers(project);
        commit(nameOf(admin));
    }

    private static @Nullable String signed(
            final @Nullable String login, final java.util.function.Function<String, String> named) {
        return login == null ? null : named.apply(login);
    }

    @Override
    public String adminNobodyAllowed(
            final @Nullable String signer, final String project, final String login)
            throws IOException {
        return listMember(project, login, "admin", signed(signer, this::nameOf));
    }

    @Override
    public String developerNobodyAllowed(
            final @Nullable String signer, final String project, final String login)
            throws IOException {
        return listMember(project, login, "developer", signed(signer, this::nameOf));
    }

    @Override
    public String lockNobodyAllowed(final String signer, final String project, final String login)
            throws IOException {
        return withLock(project, login, true, nameOf(signer));
    }

    @Override
    public String unlockNobodyAllowed(final String signer, final String project, final String login)
            throws IOException {
        return withLock(project, login, false, nameOf(signer));
    }

    @Override
    public String ownerNobodyAllowed(final @Nullable String signer, final String login)
            throws IOException {
        return ownersWith(login, signed(signer, this::nameOf));
    }

    /** The name of the key {@link #replaceLostKey} revoked: the current one's, less its suffix. */
    private String lostName(final String login) {
        return nameOf(login).substring(0, nameOf(login).length() - "-new".length());
    }

    @Override
    public String adminWithLostKey(final String lostKeyOf, final String project, final String login)
            throws IOException {
        return listMember(project, login, "admin", lostName(lostKeyOf));
    }

    @Override
    public String ownerWithLostKey(final String lostKeyOf, final String login) throws IOException {
        return ownersWith(login, lostName(lostKeyOf));
    }

    @Override
    public String playBackOlderCard(final String login) throws IOException {
        final String lost = lostName(login);
        write("people/" + login + ".card", card(login, lost));
        return commit(lost);
    }

    @Override
    public String cardNobodyAllowed(final String signer, final String login) throws IOException {
        write("people/" + login + ".card", card(login, nameOf(signer)));
        return commit(nameOf(signer));
    }

    @Override
    public String cardFiledByAnother(final String signer, final String login) throws IOException {
        forge.keys.put(login, List.of(key(login)));
        write("people/" + login + ".card", card(login, nameOf(login)));
        return commit(nameOf(signer));
    }

    @Override
    public String removeCardNobodyAllowed(final String signer, final String login)
            throws IOException {
        Files.delete(repo.resolve("people/" + login + ".card"));
        return commit(nameOf(signer));
    }

    /** The source lists the new key too, so that only who signed tells this from the real thing. */
    @Override
    public String replacementNobodyAllowed(final String signer, final String login)
            throws IOException {
        final String replacement = signer + "-as-" + login;
        forge.keys.put(login, List.of(key(login), line(replacement)));
        write("people/" + login + ".card", card(login, replacement));
        return commit(nameOf(signer));
    }

    @Override
    public String squatFirstCard(final String signer, final String login) throws IOException {
        write("people/" + login + ".card", card(login, nameOf(signer)));
        return commit(nameOf(signer));
    }

    @Override
    public String fileCardTheSourceDenies(final String login) throws IOException {
        forge.keys.put(login, List.of(line(login + "-listed")));
        write("people/" + login + ".card", card(login, nameOf(login)));
        return commit(nameOf(login));
    }

    @Override
    public String fingerprint(final String login) {
        return PersonCard.fingerprint(key(login));
    }

    private String hostCard(final String host, final String signer) {
        return HostCard.write(
                host,
                host + ".example.org",
                22,
                line("host-key"),
                3000,
                2222,
                8448,
                keyNamed(signer));
    }

    private void writeHost(final String host, final List<String> admins, final String projects)
            throws IOException {
        final StringBuilder text = new StringBuilder("admins:\n");
        admins.forEach(login -> text.append("  - login: ").append(login).append('\n'));
        write(
                "hosts/" + host + "/host.yml",
                text.append("projects: ").append(projects).append('\n').toString());
    }

    @Override
    public void hostWithAdmin(
            final String owner, final String host, final String admin, final String project)
            throws IOException {
        found(owner);
        fileCard(admin);
        makeProject(owner, project, admin);
        hostAdmins.add(admin);
        hostProjects = "[" + project + "]";
        writeHost(host, hostAdmins, "[]");
        commit(nameOf(owner));
        writeHost(host, hostAdmins, hostProjects);
        commit(nameOf(admin));
        write("hosts/" + host + "/card", hostCard(host, nameOf(admin)));
        commit(nameOf(admin));
    }

    @Override
    public void carrySome(
            final String admin,
            final String host,
            final String project,
            final List<String> repositories)
            throws IOException {
        hostProjects =
                "[{name: "
                        + project
                        + ", repositories: ["
                        + String.join(", ", repositories)
                        + "]}]";
        writeHost(host, hostAdmins, hostProjects);
        commit(nameOf(admin));
    }

    @Override
    public void hostChangeNobodyAllowed(final @Nullable String signer, final String host)
            throws IOException {
        write(
                "hosts/" + host + "/card",
                signer == null ? "not signed\n" : hostCard(host, nameOf(signer)));
        commit(signed(signer, this::nameOf));
    }

    @Override
    public void adminByOwner(final String owner, final String host, final String login)
            throws IOException {
        final List<String> admins = new ArrayList<>(hostAdmins);
        admins.add(login);
        writeHost(host, admins, hostProjects);
        commit(nameOf(owner));
    }

    /** The source has no entry for the login, which is how it says nothing of a person. */
    @Override
    public void firstSeenPerson(
            final String admin, final String host, final String project, final String login)
            throws IOException {
        addMember(admin, project, login, "developer");
        write("people/" + login + ".card", card(login, nameOf(login)));
        commit(nameOf(login));
        final List<String> admins = new ArrayList<>(hostAdmins);
        admins.add(login);
        writeHost(host, admins, hostProjects);
        commit(nameOf(admin));
    }

    @Override
    public void handOverHost(final String admin, final String host, final String login)
            throws IOException {
        hostAdmins.add(login);
        writeHost(host, hostAdmins, hostProjects);
        commit(nameOf(admin));
        write("hosts/" + host + "/card", hostCard(host, nameOf(login)));
        commit(nameOf(login));
    }

    @Override
    public OfflineOrganization copy() {
        return new GitTreeOffline(
                GitTreeStore.at(
                        runner,
                        repo.toString(),
                        ORGANISATION,
                        "github.com",
                        FetchAccess.NONE,
                        dir.resolve("host-state")),
                forge);
    }

    @Override
    public void serveOlderState() {
        git("reset", "-q", "--hard", "HEAD~1");
    }

    @Override
    public void rewriteHistory() throws IOException {
        serveOlderState();
        fileCard(REWRITER);
    }

    @Override
    public void discardRevocation() throws IOException {
        rewriteHistory();
    }
}
