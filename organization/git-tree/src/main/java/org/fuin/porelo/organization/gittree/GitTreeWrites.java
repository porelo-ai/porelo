package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.organization.OrganizationWrites.Written;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * The organization's changes. Each is made in a fresh clone and judged by the same walk a reader
 * makes before it is pushed, so a change nobody allowed is refused here rather than filed and then
 * refused by everyone who reads it. The refusals named before that walk only say why more plainly.
 */
public final class GitTreeWrites {

    /** What a change does to the files, given what was accepted. */
    @FunctionalInterface
    private interface Edit {
        Map<String, @Nullable String> files(TreeModel model);
    }

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
    public Written addCard(final Change change, final String card) throws IOException {
        final String path = ownCard(change, card);
        return change(
                change,
                "Add a person's card",
                model -> {
                    final TreeModel.Person held = model.people().get(change.acting().login());
                    if (held != null && !Organization.REFUSED.equals(held.state())) {
                        throw new Refused(Refused.EXISTS, "this login has a card", null);
                    }
                    return Map.of(path, card);
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
    public Written changeCard(final Change change, final String card) throws IOException {
        final String path = ownCard(change, card);
        return change(
                change,
                "Change a person's card",
                model -> {
                    if (!model.people().containsKey(change.acting().login())) {
                        throw new Refused(Refused.NO_CARD, "this login has no card", null);
                    }
                    return Map.of(path, card);
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
    public Written addBackupKey(final Change change, final @Nullable String host, final String key)
            throws IOException {
        if (!TreeFiles.key(key)) {
            throw new Refused(Refused.USAGE, "a backup key is an Ed25519 public key", null);
        }
        final String fingerprint = PersonCard.fingerprint(key);
        return change(
                change,
                "Add a backup key",
                model ->
                        host == null
                                ? ownerBackup(model, change.acting(), key, fingerprint)
                                : hostBackup(model, change.acting(), host, key));
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
        final String login = change.acting().login();
        final PersonCard card;
        try {
            card = PersonCard.read(text, "the card");
        } catch (CardException ex) {
            throw new Refused(Refused.USAGE, "that is no card", null);
        }
        if (!card.login().equals(login) || !TreeFiles.login(login)) {
            throw new Refused(Refused.USAGE, "the card names another login", null);
        }
        if (!card.fingerprint().equals(PersonCard.fingerprint(change.acting().key()))) {
            throw new Refused(Refused.USAGE, "the card is not of the key that acts", null);
        }
        return "people/" + login + ".card";
    }

    private Written change(final Change change, final String subject, final Edit edit)
            throws IOException {
        final String fingerprint = PersonCard.fingerprint(change.acting().key());
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final TreeRecord before = walk(clone);
            final Optional<String> made = clone.changeNamed(change.id(), fingerprint);
            if (made.isPresent() && !refused(before, made.orElseThrow())) {
                return new Written(made.orElseThrow(), change.id(), true);
            }
            final String tip = clone.tip();
            if (!tip.equals(before.commit())) {
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the identity source could not say enough to reach the tip",
                        before.commit());
            }
            final String base = change.base();
            if (base != null && !base.equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the organization has moved on", tip);
            }
            final String commit =
                    clone.commit(
                            tip,
                            edit.files(before.model()),
                            subject,
                            change.id(),
                            signing,
                            change.acting().key(),
                            change.acting().login());
            judged(clone, commit, tip);
            if (!clone.push(address, access)) {
                return afterRefusedPush(change, commit, tip);
            }
            return new Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /** Refuses what every reader would refuse, before anybody has to. */
    private void judged(final TreeRepository clone, final String commit, final String tip)
            throws IOException {
        final TreeRecord after = walk(clone);
        for (final var refusal : after.refusals()) {
            if (refusal.revision().equals(commit)) {
                throw new Refused(Refused.NOT_ALLOWED, refusal.why(), tip);
            }
        }
        if (!commit.equals(after.commit())) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the identity source could not say enough to judge this change",
                    tip);
        }
    }

    /** A push refused says nothing of why: what the repository now holds says it. */
    private Written afterRefusedPush(final Change change, final String commit, final String tip)
            throws IOException {
        try (TreeRepository again = TreeRepository.fetch(address, runner, access)) {
            final String now = again.tip();
            if (now.equals(commit)) {
                return new Written(commit, change.id(), false);
            }
            if (!now.equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the organization has moved on", now);
            }
        }
        throw new GitTreeStore.Unreachable("the organization's repository did not take the change");
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
