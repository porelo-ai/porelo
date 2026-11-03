package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.AddressForm;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * The organization's changes. Each is made in a fresh clone and judged by the same walk a reader
 * makes before it is pushed, so a change nobody allowed is refused here rather than filed and then
 * refused by everyone who reads it. The refusals named before that walk only say why more plainly.
 */
public final class GitTreeWrites implements OrganizationWrites {

    /** What a change does to the files, given what was accepted. */
    @FunctionalInterface
    private interface Edit {
        List<Step> steps(TreeModel model);
    }

    /**
     * One commit of a change.
     *
     * @param files What it changes: a path and its text, {@code null} to remove it.
     * @param key The key that signs it.
     */
    private record Step(Map<String, @Nullable String> files, String key) {}

    private final CommandRunner runner;

    private final String address;

    private final String organisation;

    private final FetchAccess access;

    private final IdentitySource identities;

    private final CommitSigning signing;

    /**
     * Prepares the changes of one person on one organization.
     *
     * @param runner How git runs.
     * @param address The repository's address.
     * @param organisation The organisation it belongs to.
     * @param access What a fetch and a push may use.
     * @param identities Who says whose keys are whose.
     * @param signing How the acting person signs.
     */
    public GitTreeWrites(
            final CommandRunner runner,
            final String address,
            final String organisation,
            final FetchAccess access,
            final IdentitySource identities,
            final CommitSigning signing) {
        this.runner = runner;
        this.address = address;
        this.organisation = organisation;
        this.access = access;
        this.identities = identities;
        this.signing = signing;
    }

    /**
     * Files the acting person's first card.
     *
     * @param change Who acts, against which state, under which id.
     * @param card The card, already signed by their agent.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written addCard(final Change change, final String card) throws IOException {
        final String path = ownCard(change, card);
        return change(
                change,
                TreeRepository.asked("Add a person's card", card),
                model -> {
                    final TreeModel.Person held = model.people().get(change.acting().login());
                    if (held != null && !Organization.REFUSED.equals(held.state())) {
                        throw new Refused(Refused.EXISTS, "this login has a card", null);
                    }
                    return List.of(new Step(Map.of(path, card), change.acting().key()));
                });
    }

    /**
     * Changes the acting person's card, or hands it on to a new key.
     *
     * @param change Who acts, against which state, under which id.
     * @param card The card, already signed by their agent, and by the old key when the key changes.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written changeCard(final Change change, final String card) throws IOException {
        final String path = ownCard(change, card);
        return change(
                change,
                TreeRepository.asked("Change a person's card", card),
                model -> {
                    final String login = change.acting().login();
                    final TreeModel.Person held = model.people().get(login);
                    if (held == null) {
                        throw new Refused(Refused.NO_CARD, "this login has no card", null);
                    }
                    final Step filing = new Step(Map.of(path, card), change.acting().key());
                    final String fingerprint = PersonCard.fingerprint(change.acting().key());
                    final Optional<TreeFiles.Owner> owning =
                            model.owners().stream()
                                    .filter(
                                            owner ->
                                                    !owner.backup()
                                                            && owner.login().equals(login)
                                                            && !owner.fingerprint()
                                                                    .equals(fingerprint))
                                    .findFirst();
                    if (owning.isEmpty()) {
                        return List.of(filing);
                    }
                    // Otherwise the handed-on key would go on signing as an owner, and the new one
                    // could not. The old key names its successor while it still may.
                    return List.of(
                            new Step(
                                    Map.of(
                                            "owners.yml",
                                            ownersWith(
                                                    model,
                                                    login,
                                                    change.acting().key(),
                                                    fingerprint)),
                                    owning.orElseThrow().key()),
                            filing);
                });
    }

    /**
     * Files a backup key's public half for the place the acting person holds.
     *
     * @param change Who acts, against which state, under which id.
     * @param host The host, or {@code null} for the organization's owner.
     * @param key The backup key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written addBackupKey(final Change change, final @Nullable String host, final String key)
            throws IOException {
        if (!TreeFiles.key(key)) {
            throw new Refused(Refused.USAGE, "a backup key is an Ed25519 public key", null);
        }
        final String fingerprint = PersonCard.fingerprint(key);
        return change(
                change,
                TreeRepository.asked("Add a backup key", host, key),
                model ->
                        List.of(
                                new Step(
                                        host == null
                                                ? ownerBackup(
                                                        model, change.acting(), key, fingerprint)
                                                : hostBackup(model, change.acting(), host, key),
                                        change.acting().key())));
    }

    /**
     * Withdraws a card, which revokes its key from this commit on.
     *
     * @param change Who acts: the person themselves, or an owner.
     * @param login Whose card.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written revokeCard(final Change change, final String login) throws IOException {
        return change(
                change,
                TreeRepository.asked("Withdraw a person's card", login),
                model -> {
                    final TreeModel.Person held = model.people().get(login);
                    if (held == null || Organization.REFUSED.equals(held.state())) {
                        throw new Refused(Refused.NOT_FOUND, "this login has no card", null);
                    }
                    if (model.owners().stream()
                            .filter(owner -> !owner.backup())
                            .allMatch(owner -> owner.login().equals(login))) {
                        // Nothing could ever be accepted again, and nobody could put it right.
                        throw new Refused(
                                Refused.LAST_OF_ITS_KIND,
                                "this is the only owner's card: replace its key from a backup, or"
                                        + " name another owner first",
                                null);
                    }
                    final Map<String, @Nullable String> files = new HashMap<>();
                    files.put("people/" + login + ".card", null);
                    return List.of(new Step(files, change.acting().key()));
                });
    }

    /**
     * Puts a new card in place of a lost key's, which revokes that key; for an owner alone.
     *
     * @param change Who acts, against which state, under which id.
     * @param login Whose card.
     * @param card The new card, already signed by its own key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written replaceCard(final Change change, final String login, final String card)
            throws IOException {
        final String path = cardOf(login, card).path;
        return change(
                change,
                TreeRepository.asked("Replace a person's card", login, card),
                model -> {
                    if (model.owners().stream()
                            .noneMatch(
                                    owner ->
                                            !owner.backup()
                                                    && owner.login()
                                                            .equals(change.acting().login()))) {
                        throw new Refused(
                                Refused.NOT_ALLOWED, "only an owner replaces a card", null);
                    }
                    if (!model.people().containsKey(login)) {
                        throw new Refused(Refused.NOT_FOUND, "this login has no card", null);
                    }
                    return List.of(new Step(Map.of(path, card), change.acting().key()));
                });
    }

    /**
     * Replaces the key a backup key backs, the acting key being that backup key: only the card of
     * the person who filed it, revoking the old key as an owner's replacement does.
     *
     * @param change Who acts, against which state, under which id.
     * @param host The host, or {@code null} for the organization's owner.
     * @param card The new card, already signed by its own key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    @Override
    public Written replaceKeyWithBackup(
            final Change change, final @Nullable String host, final String card)
            throws IOException {
        final String login = change.acting().login();
        final Filed filed = cardOf(login, card);
        final String backup = PersonCard.fingerprint(change.acting().key());
        return change(
                change,
                TreeRepository.asked("Replace a key with its backup", host, card),
                model -> {
                    if (!model.people().containsKey(login)) {
                        throw new Refused(Refused.NO_CARD, "this login has no card", null);
                    }
                    final Map<String, @Nullable String> filing = Map.of(filed.path, card);
                    if (host == null) {
                        if (!backsOwner(model, login, backup)) {
                            throw new Refused(
                                    Refused.NOT_ALLOWED,
                                    "this key backs no owner of this login",
                                    null);
                        }
                        // The backup key signs no card: it names the new key, which then does.
                        return List.of(
                                new Step(
                                        Map.of(
                                                "owners.yml",
                                                ownersWith(
                                                        model,
                                                        login,
                                                        filed.key,
                                                        filed.fingerprint)),
                                        change.acting().key()),
                                new Step(filing, filed.key));
                    }
                    if (!backedOnHost(model, host, login, backup)) {
                        throw new Refused(
                                Refused.NOT_ALLOWED, "this key backs no such place", null);
                    }
                    return List.of(new Step(filing, change.acting().key()));
                });
    }

    @Override
    public Written createOrganization(final Change change, final String card) throws IOException {
        final Filed filed = cardOf(change.acting().login(), card);
        if (!filed.fingerprint.equals(PersonCard.fingerprint(change.acting().key()))) {
            throw new Refused(Refused.USAGE, "the card is not of the key that acts", null);
        }
        final String subject = TreeRepository.asked("Found the organization", card);
        final Optional<TreeRepository> empty = TreeRepository.founding(address, runner, access);
        if (empty.isEmpty()) {
            return filledAlready(change, subject);
        }
        try (TreeRepository clone = empty.orElseThrow()) {
            final String owners =
                    TreeFiles.ownersText(
                            List.of(
                                    new TreeFiles.Owner(
                                            change.acting().login(),
                                            filed.key,
                                            filed.fingerprint,
                                            false)));
            final String commit =
                    clone.commit(
                            "",
                            Map.of("owners.yml", owners, filed.path, card),
                            subject,
                            change.id(),
                            signing,
                            change.acting().key(),
                            change.acting().login());
            judged(clone, List.of(commit), "", TreeModel.empty(), change.acting().login());
            if (!clone.push(address, access)) {
                throw new GitTreeStore.NotTaken();
            }
            return new Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /** Asked again, the founding is answered; anything else already there is somebody's. */
    private Written filledAlready(final Change change, final String subject) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final TreeRecord record = walk(clone);
            final Optional<String> made =
                    clone.changeNamed(change.id(), PersonCard.fingerprint(change.acting().key()));
            if (made.isPresent() && !refused(record, made.orElseThrow())) {
                sameChange(clone, made.orElseThrow(), subject);
                return new Written(made.orElseThrow(), change.id(), true);
            }
            throw new Refused(Refused.EXISTS, "the repository is not empty", record.commit());
        } catch (GitTreeStore.Empty ex) {
            throw new Refused(Refused.EXISTS, "the repository is not empty", null);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    @Override
    public Written addOrganizationOwner(final Change change, final String login)
            throws IOException {
        return one(
                change,
                TreeRepository.asked("Add an owner", login),
                model -> {
                    final TreeModel.Person person = standing(model, login);
                    if (model.owners().stream()
                            .anyMatch(each -> !each.backup() && each.login().equals(login))) {
                        throw new Refused(Refused.EXISTS, "this login is an owner", null);
                    }
                    final List<TreeFiles.Owner> owners = new ArrayList<>(model.owners());
                    owners.add(
                            new TreeFiles.Owner(
                                    login,
                                    PersonCard.read(person.card(), "the card").key(),
                                    person.fingerprint(),
                                    false));
                    return Map.of("owners.yml", TreeFiles.ownersText(owners));
                });
    }

    @Override
    public Written removeOrganizationOwner(final Change change, final String login)
            throws IOException {
        return one(
                change,
                TreeRepository.asked("Remove an owner", login),
                model -> {
                    if (model.owners().stream()
                            .noneMatch(each -> !each.backup() && each.login().equals(login))) {
                        throw new Refused(Refused.NOT_FOUND, "this login is no owner", null);
                    }
                    final List<TreeFiles.Owner> kept =
                            model.owners().stream()
                                    .filter(each -> !each.login().equals(login))
                                    .toList();
                    if (kept.stream().allMatch(TreeFiles.Owner::backup)) {
                        throw new Refused(Refused.LAST_OF_ITS_KIND, "this is the only owner", null);
                    }
                    return Map.of("owners.yml", TreeFiles.ownersText(kept));
                });
    }

    @Override
    public Written addProject(final Change change, final String project, final String admin)
            throws IOException {
        if (project.equals(TreeWalk.RESERVED)) {
            throw new Refused(Refused.RESERVED_NAME, "no project may be named this", null);
        }
        named(project);
        final String key = change.acting().key();
        return change(
                change,
                TreeRepository.asked("Add a project", project, admin),
                model -> {
                    if (model.projects().containsKey(project)) {
                        throw new Refused(Refused.EXISTS, "this project stands", null);
                    }
                    standing(model, admin);
                    final String prefix = "projects/" + project + "/";
                    // Two commits: a reader judges the members against a project that stands.
                    return List.of(
                            new Step(Map.of(prefix + "card", projectCard(project, admin)), key),
                            new Step(
                                    Map.of(
                                            prefix + "members.yml",
                                            TreeFiles.membersText(
                                                    List.of(
                                                            new TreeFiles.Member(
                                                                    admin,
                                                                    TreeFiles.ADMIN,
                                                                    false)))),
                                    key));
                });
    }

    @Override
    public Written removeProject(final Change change, final String project) throws IOException {
        return one(
                change,
                TreeRepository.asked("Remove a project", project),
                model -> {
                    final TreeModel.ProjectState state = project(model, project);
                    final String prefix = "projects/" + project + "/";
                    final Map<String, @Nullable String> files = new HashMap<>();
                    files.put(prefix + "card", null);
                    if (state.members() != null) {
                        files.put(prefix + "members.yml", null);
                    }
                    if (state.address() != null) {
                        files.put(prefix + "repository.yml", null);
                    }
                    return files;
                });
    }

    @Override
    public Written changeProjectAdmin(final Change change, final String project, final String admin)
            throws IOException {
        final String key = change.acting().key();
        return change(
                change,
                TreeRepository.asked("Change a project's first admin", project, admin),
                model -> {
                    final TreeModel.ProjectState state = project(model, project);
                    standing(model, admin);
                    final List<TreeFiles.Member> members = new ArrayList<>();
                    boolean listed = false;
                    for (final TreeFiles.Member member : members(state)) {
                        if (member.login().equals(admin)) {
                            listed = true;
                            members.add(
                                    new TreeFiles.Member(admin, TreeFiles.ADMIN, member.locked()));
                        } else {
                            members.add(member);
                        }
                    }
                    if (!listed) {
                        members.add(new TreeFiles.Member(admin, TreeFiles.ADMIN, false));
                    }
                    final String prefix = "projects/" + project + "/";
                    return List.of(
                            new Step(Map.of(prefix + "card", projectCard(project, admin)), key),
                            new Step(
                                    Map.of(prefix + "members.yml", TreeFiles.membersText(members)),
                                    key));
                });
    }

    @Override
    public Written changeProjectRepository(
            final Change change, final String project, final String repository) throws IOException {
        if (!AddressForm.isRepository(repository)) {
            throw new Refused(Refused.USAGE, "that is no repository's address", null);
        }
        return one(
                change,
                TreeRepository.asked("File a project's address", project, repository),
                model -> {
                    project(model, project);
                    return Map.of(
                            "projects/" + project + "/repository.yml",
                            "address: \"" + repository + "\"\n");
                });
    }

    @Override
    public Written addProjectMember(
            final Change change, final String project, final String login, final String role)
            throws IOException {
        role(role);
        return one(
                change,
                TreeRepository.asked("Add a project's member", project, login, role),
                model -> {
                    final TreeModel.ProjectState state = project(model, project);
                    standing(model, login);
                    final List<TreeFiles.Member> members = new ArrayList<>(members(state));
                    if (members.stream().anyMatch(each -> each.login().equals(login))) {
                        throw new Refused(Refused.EXISTS, "this login is a member", null);
                    }
                    members.add(new TreeFiles.Member(login, role, false));
                    return membersFile(project, members);
                });
    }

    @Override
    public Written changeProjectMemberRole(
            final Change change, final String project, final String login, final String role)
            throws IOException {
        role(role);
        return memberChange(
                change,
                TreeRepository.asked("Change a project member's role", project, login, role),
                project,
                login,
                member -> new TreeFiles.Member(login, role, member.locked()));
    }

    @Override
    public Written removeProjectMember(
            final Change change, final String project, final String login) throws IOException {
        return memberChange(change, "Remove a project's member", project, login, member -> null);
    }

    @Override
    public Written lockProjectMember(final Change change, final String project, final String login)
            throws IOException {
        return memberChange(
                change,
                TreeRepository.asked("Lock a project's member", project, login),
                project,
                login,
                member -> new TreeFiles.Member(login, member.role(), true));
    }

    @Override
    public Written unlockProjectMember(
            final Change change, final String project, final String login) throws IOException {
        return memberChange(
                change,
                TreeRepository.asked("Unlock a project's member", project, login),
                project,
                login,
                member -> new TreeFiles.Member(login, member.role(), false));
    }

    /** What a change does to one member: their new entry, or {@code null} when they go. */
    @FunctionalInterface
    private interface MemberEdit {
        TreeFiles.@Nullable Member apply(TreeFiles.Member member);
    }

    private Written memberChange(
            final Change change,
            final String subject,
            final String project,
            final String login,
            final MemberEdit edit)
            throws IOException {
        final String acting = PersonCard.fingerprint(change.acting().key());
        return one(
                change,
                subject,
                model -> {
                    final List<TreeFiles.Member> members = new ArrayList<>();
                    boolean found = false;
                    for (final TreeFiles.Member member : members(project(model, project))) {
                        if (!member.login().equals(login)) {
                            members.add(member);
                            continue;
                        }
                        found = true;
                        final TreeFiles.Member changed = edit.apply(member);
                        if (changed != null) {
                            members.add(changed);
                        }
                    }
                    if (!found) {
                        throw new Refused(Refused.NOT_FOUND, "this login is no member", null);
                    }
                    // An owner may remove a project's last admin; its own admins may not.
                    if (!model.owner(acting)
                            && members.stream()
                                    .noneMatch(
                                            each ->
                                                    each.role().equals(TreeFiles.ADMIN)
                                                            && !each.locked())) {
                        throw new Refused(
                                Refused.LAST_OF_ITS_KIND,
                                "this would leave the project no admin who may act",
                                null);
                    }
                    return membersFile(project, members);
                });
    }

    @Override
    public Written addHost(final Change change, final String host, final String admin)
            throws IOException {
        if (!TreeFiles.host(host)) {
            throw new Refused(Refused.USAGE, "that is no host's name", null);
        }
        return one(
                change,
                TreeRepository.asked("Add a host", host, admin),
                model -> {
                    if (model.hosts().containsKey(host)) {
                        throw new Refused(Refused.EXISTS, "this host stands", null);
                    }
                    standing(model, admin);
                    return hostFile(
                            host,
                            new TreeFiles.HostFile(
                                    List.of(new TreeFiles.HostAdmin(admin, null)), List.of()));
                });
    }

    @Override
    public Written removeHost(final Change change, final String host) throws IOException {
        return one(
                change,
                TreeRepository.asked("Remove a host", host),
                model -> {
                    final TreeModel.HostState state = host(model, host);
                    final Map<String, @Nullable String> files = new HashMap<>();
                    files.put("hosts/" + host + "/host.yml", null);
                    if (state.card() != null) {
                        files.put("hosts/" + host + "/card", null);
                    }
                    return files;
                });
    }

    @Override
    public Written changeHost(final Change change, final String host, final String card)
            throws IOException {
        try {
            if (!HostCard.read(card, "the card").host().equals(host)) {
                throw new Refused(Refused.USAGE, "the card names another host", null);
            }
        } catch (CardException ex) {
            throw new Refused(Refused.USAGE, "that is no host's card", null);
        }
        return one(
                change,
                TreeRepository.asked("Change a host's card", host, card),
                model -> {
                    host(model, host);
                    return Map.of("hosts/" + host + "/card", card);
                });
    }

    @Override
    public Written addHostAdmin(final Change change, final String host, final String login)
            throws IOException {
        return one(
                change,
                TreeRepository.asked("Add a host's admin", host, login),
                model -> {
                    final TreeFiles.HostFile file = host(model, host).file();
                    standing(model, login);
                    if (file.admins().stream()
                            .anyMatch(each -> !each.backup() && each.login().equals(login))) {
                        throw new Refused(Refused.EXISTS, "this login is its admin", null);
                    }
                    final List<TreeFiles.HostAdmin> admins = new ArrayList<>(file.admins());
                    admins.add(new TreeFiles.HostAdmin(login, null));
                    return hostFile(host, new TreeFiles.HostFile(admins, file.projects()));
                });
    }

    @Override
    public Written removeHostAdmin(final Change change, final String host, final String login)
            throws IOException {
        return one(
                change,
                TreeRepository.asked("Remove a host's admin", host, login),
                model -> {
                    final TreeFiles.HostFile file = host(model, host).file();
                    if (file.admins().stream()
                            .noneMatch(each -> !each.backup() && each.login().equals(login))) {
                        throw new Refused(Refused.NOT_FOUND, "this login is not its admin", null);
                    }
                    final List<TreeFiles.HostAdmin> kept =
                            file.admins().stream()
                                    .filter(each -> !each.login().equals(login))
                                    .toList();
                    if (kept.stream().allMatch(TreeFiles.HostAdmin::backup)) {
                        throw new Refused(
                                Refused.LAST_OF_ITS_KIND, "this is the host's only admin", null);
                    }
                    return hostFile(host, new TreeFiles.HostFile(kept, file.projects()));
                });
    }

    @Override
    public Written addProjectToHost(final Change change, final String host, final String project)
            throws IOException {
        return one(
                change,
                TreeRepository.asked("Put a project on a host", host, project),
                model -> {
                    final TreeFiles.HostFile file = host(model, host).file();
                    project(model, project);
                    if (file.projects().contains(project)) {
                        throw new Refused(Refused.EXISTS, "this host carries it", null);
                    }
                    if (model.hosts().values().stream()
                            .anyMatch(each -> each.file().projects().contains(project))) {
                        throw new Refused(Refused.ON_ANOTHER_HOST, "another host carries it", null);
                    }
                    final List<String> projects = new ArrayList<>(file.projects());
                    projects.add(project);
                    return hostFile(host, new TreeFiles.HostFile(file.admins(), projects));
                });
    }

    @Override
    public Written removeProjectFromHost(
            final Change change, final String host, final String project) throws IOException {
        return one(
                change,
                TreeRepository.asked("Take a project off a host", host, project),
                model -> {
                    final TreeFiles.HostFile file = host(model, host).file();
                    if (!file.projects().contains(project)) {
                        throw new Refused(Refused.NOT_FOUND, "this host does not carry it", null);
                    }
                    return hostFile(
                            host,
                            new TreeFiles.HostFile(
                                    file.admins(),
                                    file.projects().stream()
                                            .filter(each -> !each.equals(project))
                                            .toList()));
                });
    }

    /** What a change of one commit, signed by the acting key, does to the files. */
    @FunctionalInterface
    private interface Files {
        Map<String, @Nullable String> of(TreeModel model);
    }

    private Written one(final Change change, final String subject, final Files files)
            throws IOException {
        return change(
                change,
                subject,
                model -> List.of(new Step(files.of(model), change.acting().key())));
    }

    private static void named(final String project) {
        if (!project.matches(Project.NAME_PATTERN)) {
            throw new Refused(Refused.USAGE, "that is no project's name", null);
        }
    }

    private static void role(final String role) {
        if (!role.equals(TreeFiles.ADMIN) && !role.equals(TreeFiles.DEVELOPER)) {
            throw new Refused(Refused.USAGE, "a role is admin or developer", null);
        }
    }

    private static String projectCard(final String project, final String admin) {
        return "name: " + project + "\nadmin: " + admin + "\n";
    }

    private static TreeModel.ProjectState project(final TreeModel model, final String project) {
        final TreeModel.ProjectState state = model.projects().get(project);
        if (state == null) {
            throw new Refused(Refused.NOT_FOUND, "no such project", null);
        }
        return state;
    }

    private static List<TreeFiles.Member> members(final TreeModel.ProjectState state) {
        final List<TreeFiles.Member> members = state.members();
        return members == null ? List.of() : members;
    }

    private static Map<String, @Nullable String> membersFile(
            final String project, final List<TreeFiles.Member> members) {
        return Map.of("projects/" + project + "/members.yml", TreeFiles.membersText(members));
    }

    private static TreeModel.HostState host(final TreeModel model, final String host) {
        final TreeModel.HostState state = model.hosts().get(host);
        if (state == null) {
            throw new Refused(Refused.NOT_FOUND, "no such host", null);
        }
        return state;
    }

    private static Map<String, @Nullable String> hostFile(
            final String host, final TreeFiles.HostFile file) {
        return Map.of("hosts/" + host + "/host.yml", TreeFiles.hostFileText(file));
    }

    /** The login's card, which holds a role only when nobody refused it. */
    private static TreeModel.Person standing(final TreeModel model, final String login) {
        final TreeModel.Person person = model.people().get(login);
        if (person == null || Organization.REFUSED.equals(person.state())) {
            throw new Refused(Refused.NO_CARD, "this login has no card", null);
        }
        return person;
    }

    private static boolean backedOnHost(
            final TreeModel model, final String host, final String login, final String backup) {
        final TreeModel.HostState state = model.hosts().get(host);
        if (state == null) {
            throw new Refused(Refused.NOT_FOUND, "no such host", null);
        }
        return state.file().admins().stream()
                .anyMatch(
                        admin ->
                                admin.login().equals(login)
                                        && admin.backupKey() != null
                                        && PersonCard.fingerprint(admin.backupKey())
                                                .equals(backup));
    }

    private static boolean backsOwner(
            final TreeModel model, final String login, final String backup) {
        return model.owners().stream()
                .anyMatch(
                        owner ->
                                owner.backup()
                                        && owner.login().equals(login)
                                        && owner.fingerprint().equals(backup));
    }

    /** The owners with a new key in place of the login's own. */
    private static String ownersWith(
            final TreeModel model, final String login, final String key, final String fingerprint) {
        final List<TreeFiles.Owner> owners = new ArrayList<>();
        for (final TreeFiles.Owner owner : model.owners()) {
            owners.add(
                    !owner.backup() && owner.login().equals(login)
                            ? new TreeFiles.Owner(login, key, fingerprint, false)
                            : owner);
        }
        return TreeFiles.ownersText(owners);
    }

    /** A card's place, and its key. */
    private record Filed(String path, String key, String fingerprint) {}

    /** Checks that a card is this login's, and says where it is filed. */
    private static Filed cardOf(final String login, final String text) {
        final PersonCard card;
        try {
            card = PersonCard.read(text, "the card");
        } catch (CardException ex) {
            throw new Refused(Refused.USAGE, "that is no card", null);
        }
        if (!TreeFiles.login(login) || !card.login().equals(login)) {
            throw new Refused(Refused.USAGE, "the card names another login", null);
        }
        return new Filed("people/" + login + ".card", card.key(), card.fingerprint());
    }

    private static Map<String, @Nullable String> ownerBackup(
            final TreeModel model,
            final Acting acting,
            final String key,
            final String fingerprint) {
        if (model.owners().stream()
                .noneMatch(owner -> !owner.backup() && owner.login().equals(acting.login()))) {
            throw new Refused(Refused.NOT_ALLOWED, "only an owner files an owner's backup", null);
        }
        if (model.owners().stream().anyMatch(owner -> owner.fingerprint().equals(fingerprint))) {
            throw new Refused(Refused.EXISTS, "this key is already an owner's", null);
        }
        final List<TreeFiles.Owner> owners = new ArrayList<>(model.owners());
        owners.add(new TreeFiles.Owner(acting.login(), key, fingerprint, true));
        return Map.of("owners.yml", TreeFiles.ownersText(owners));
    }

    private static Map<String, @Nullable String> hostBackup(
            final TreeModel model, final Acting acting, final String host, final String key) {
        final TreeModel.HostState state = model.hosts().get(host);
        if (state == null) {
            throw new Refused(Refused.NOT_FOUND, "no such host", null);
        }
        final TreeFiles.HostFile file = state.file();
        if (file.admins().stream()
                .noneMatch(admin -> !admin.backup() && admin.login().equals(acting.login()))) {
            throw new Refused(
                    Refused.NOT_ALLOWED, "only the host's admin files their backup", null);
        }
        if (file.admins().stream().anyMatch(admin -> key.equals(admin.backupKey()))) {
            throw new Refused(Refused.EXISTS, "this key already backs an admin of this host", null);
        }
        final List<TreeFiles.HostAdmin> admins = new ArrayList<>(file.admins());
        admins.add(new TreeFiles.HostAdmin(acting.login(), key));
        return Map.of(
                "hosts/" + host + "/host.yml",
                TreeFiles.hostFileText(new TreeFiles.HostFile(admins, file.projects())));
    }

    /** Checks that a card is the acting person's own, and returns where it is filed. */
    private static String ownCard(final Change change, final String text) {
        final Filed filed = cardOf(change.acting().login(), text);
        if (!filed.fingerprint.equals(PersonCard.fingerprint(change.acting().key()))) {
            throw new Refused(Refused.USAGE, "the card is not of the key that acts", null);
        }
        return filed.path;
    }

    private Written change(final Change change, final String subject, final Edit edit)
            throws IOException {
        final String fingerprint = PersonCard.fingerprint(change.acting().key());
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final TreeRecord before = walk(clone);
            final Optional<String> made = clone.changeNamed(change.id(), fingerprint);
            if (made.isPresent() && !refused(before, made.orElseThrow())) {
                sameChange(clone, made.orElseThrow(), subject);
                return new Written(made.orElseThrow(), change.id(), true);
            }
            final String tip = clone.tip();
            if (!tip.equals(before.commit())) {
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the forge could not say enough of its people's keys to read the organization to"
                                + " its tip: see whether this person's forge token is set",
                        before.commit());
            }
            final String base = change.base();
            if (base != null && !base.equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the organization has moved on", tip);
            }
            final List<String> commits = new ArrayList<>();
            String commit = tip;
            for (final Step step : edit.steps(before.model())) {
                commit =
                        clone.commit(
                                commit,
                                step.files(),
                                subject,
                                change.id(),
                                signing,
                                step.key(),
                                change.acting().login());
                commits.add(commit);
            }
            if (clone.changed(commit, tip).isEmpty()) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        "the file says this already, in a commit no reader accepts: an owner sets"
                                + " the project's admin anew first",
                        tip);
            }
            judged(clone, commits, tip, before.model(), change.acting().login());
            if (!clone.push(address, access)) {
                return afterRefusedPush(change, commit, tip);
            }
            return new Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /** An id names one change: answering another as made would say what never happened. */
    private static void sameChange(
            final TreeRepository clone, final String made, final String subject) {
        if (!clone.subject(made).equals(subject)) {
            throw new Refused(Refused.USAGE, "this --id named another change", made);
        }
    }

    /** Refuses what every reader would refuse, before anybody has to. */
    private void judged(
            final TreeRepository clone,
            final List<String> commits,
            final String tip,
            final TreeModel model,
            final String login)
            throws IOException {
        final TreeRecord after = walk(clone);
        for (final var refusal : after.refusals()) {
            if (commits.contains(refusal.revision())) {
                throw new Refused(reason(model, login, refusal.path()), refusal.why(), tip);
            }
        }
        if (!commits.getLast().equals(after.commit())) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the forge could not say enough of its people's keys to judge this change: see"
                            + " whether this person's forge token is set",
                    tip);
        }
    }

    /** A lock is said as one: someone locked asks why, someone without the role asks for it. */
    private static String reason(final TreeModel model, final String login, final String path) {
        final String[] parts = path.split("/");
        final TreeModel.ProjectState project =
                parts.length > 1 && parts[0].equals("projects")
                        ? model.projects().get(parts[1])
                        : null;
        final List<TreeFiles.Member> members = project == null ? null : project.members();
        return members != null
                        && members.stream()
                                .anyMatch(each -> each.login().equals(login) && each.locked())
                ? Refused.LOCKED
                : Refused.NOT_ALLOWED;
    }

    /** A push refused says nothing of why: what the repository now holds says it. */
    private Written afterRefusedPush(final Change change, final String commit, final String tip)
            throws IOException {
        try (TreeRepository again = TreeRepository.fetch(address, runner, access)) {
            final String now = again.tip();
            // Another writer may have gone on from it before this look.
            if (again.line(null).stream().anyMatch(each -> each.getKey().equals(commit))) {
                return new Written(commit, change.id(), false);
            }
            if (!now.equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the organization has moved on", now);
            }
        }
        throw new GitTreeStore.NotTaken();
    }

    private TreeRecord walk(final TreeRepository clone) throws IOException {
        final TreeWalk walk = new TreeWalk(clone, identities, organisation);
        walk.readChains();
        return walk.read(null, null);
    }

    private static boolean refused(final TreeRecord record, final String commit) {
        return record.refusals().stream().anyMatch(each -> each.revision().equals(commit));
    }
}
