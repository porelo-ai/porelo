package org.fuin.porelo.organization.gittree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * What a host adds to the walk: every commit on its own paths must be signed by a key of its pin.
 * The pin follows the host's admins as the walk accepts them, so a new admin or a handed-on key is
 * a verified handover. Before the first fetch the host has taken nothing, so the commits before the
 * first one signed for the pin stand on the walk alone: an owner makes a host, and that commit pins
 * nothing.
 */
final class TreePin implements TreeWalk.Watch {

    private final String host;

    private final String paths;

    private final String since;

    private final OrganizationQuestions.Description description;

    private Set<String> pin;

    /** Whether commits on the host's paths now answer to the pin. */
    private boolean active;

    /** The organization admins' keys as the commit before this one left them. */
    private Set<String> above;

    /** The same, for the commit being judged: a commit listing its signer gains nothing by it. */
    private Set<String> standing = Set.of();

    TreePin(
            final String host,
            final Set<String> pin,
            final String since,
            final @Nullable TreeModel resumed,
            final OrganizationQuestions.Description description) {
        this.host = host;
        this.paths = "hosts/" + host + "/";
        this.pin = new HashSet<>(pin);
        this.since = since;
        this.active = resumed != null;
        this.above = resumed == null ? Set.of() : organizationAdmins(resumed);
        this.description = description;
    }

    @Override
    public void passed(
            final String commit,
            final @Nullable String signer,
            final List<String> changed,
            final TreeModel model) {
        final Set<String> now = organizationAdmins(model);
        standing = above;
        above = now;
        judge(commit, signer, changed, model);
    }

    private void judge(
            final String commit,
            final @Nullable String signer,
            final List<String> changed,
            final TreeModel model) {
        if (!active && !since.isEmpty()) {
            active = since.equals(commit);
            if (active) {
                follow(commit, signer, model, changed);
            }
            return;
        }
        final boolean signed = vouchedFor(signer);
        if (changed.stream().anyMatch(path -> path.startsWith(paths))) {
            if (active && !signed) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        commit
                                + " changes this host's files and is signed by "
                                + (signer == null ? "nobody" : signer)
                                + ", which is not for the pin",
                        commit);
            }
            active = active || signed;
        }
        if (active) {
            follow(commit, signer, model, changed);
        }
    }

    /**
     * Moves the pin to the admins the walk now holds and the organization admins, along what the
     * pin vouches for: a commit signed for it, or by an organization admin, or a key an admin
     * handed on from a pinned one. An owner not listed is none of these, so the host stops until
     * its admin renews the pin on the machine. An organization admin is, on the operator's word:
     * one who is dismissed must be replaceable with no step of theirs, and one stolen key of that
     * role reaches every server's pin.
     */
    private void follow(
            final String commit,
            final @Nullable String signer,
            final TreeModel model,
            final List<String> changed) {
        final TreeModel.HostState state = model.hosts().get(host);
        if (changed.isEmpty() || state == null) {
            return;
        }
        final boolean signed = vouchedFor(signer);
        final Set<String> admins = new HashSet<>(organizationAdmins(model));
        for (final TreeFiles.HostAdmin admin : state.file().admins()) {
            final String backup = admin.backupKey();
            if (backup != null) {
                final String fingerprint = PersonCard.fingerprint(backup);
                if (!model.revoked().contains(fingerprint)
                        && (signed || pin.contains(fingerprint))) {
                    admins.add(fingerprint);
                }
                continue;
            }
            final TreeModel.Person person = model.people().get(admin.login());
            if (person != null) {
                held(commit, admin.login(), person, signed, admins);
            }
        }
        if (admins.isEmpty()) {
            // Nobody to hand on to: the pin stays, but never with a key that was dismissed.
            pin.removeAll(standing);
        } else {
            pin = admins;
        }
    }

    private boolean vouchedFor(final @Nullable String signer) {
        return signer != null && (pin.contains(signer) || standing.contains(signer));
    }

    /** The keys of those listed, each on a card the forge vouched for and not revoked. */
    private static Set<String> organizationAdmins(final TreeModel model) {
        final List<String> listed = model.organizationAdmins();
        if (listed == null) {
            return Set.of();
        }
        final Set<String> keys = new HashSet<>();
        for (final String login : listed) {
            final TreeModel.Person person = model.people().get(login);
            if (person != null
                    && Organization.TRUSTED.equals(person.state())
                    && !model.revoked().contains(person.fingerprint())) {
                keys.add(person.fingerprint());
            }
        }
        return keys;
    }

    /** Adds what the pin holds for one admin, a card not yet vouched for keeping what led to it. */
    private void held(
            final String commit,
            final String login,
            final TreeModel.Person person,
            final boolean signed,
            final Set<String> admins) {
        final String own = person.fingerprint();
        final List<String> chain = person.handedOn().stream().filter(pin::contains).toList();
        if (!Organization.TRUSTED.equals(person.state())) {
            admins.addAll(chain);
            return;
        }
        if (signed || pin.contains(own) || !chain.isEmpty()) {
            admins.add(own);
        } else if (!person.earlier().isEmpty()) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    commit
                            + " puts "
                            + own
                            + " on the card of "
                            + login
                            + ", an admin of this host, and neither that person's key nor the pin"
                            + " signed for it: renew the pin on this host if it is right",
                    commit);
        }
    }

    /**
     * Says whether the walk reached the pin at all.
     *
     * @throws Refused When nothing it passed answers to the pin: {@code refused-state} on a first
     *     fetch, whose history nothing signed for the pin vouches for; {@code cannot-say} when the
     *     source stopped the walk before the commit last taken, which says nothing of that commit.
     */
    void finish() {
        if (active) {
            return;
        }
        if (since.isEmpty()) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "no commit on this host's files is signed for the pin",
                    null);
        }
        throw new Refused(
                Refused.CANNOT_SAY,
                "the identity source could not say enough to reach the commit last taken",
                since);
    }
}
