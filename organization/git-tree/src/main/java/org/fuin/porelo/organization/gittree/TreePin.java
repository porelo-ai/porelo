package org.fuin.porelo.organization.gittree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
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

    TreePin(
            final String host,
            final Set<String> pin,
            final String since,
            final boolean resumed,
            final OrganizationQuestions.Description description) {
        this.host = host;
        this.paths = "hosts/" + host + "/";
        this.pin = new HashSet<>(pin);
        this.since = since;
        this.active = resumed;
        this.description = description;
    }

    @Override
    public void passed(
            final String commit,
            final @Nullable String signer,
            final List<String> changed,
            final TreeModel model) {
        if (!active && !since.isEmpty()) {
            active = since.equals(commit);
            if (active) {
                follow(commit, model, changed);
            }
            return;
        }
        final boolean signed = signer != null && pin.contains(signer);
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
            follow(commit, model, changed);
        }
    }

    /** Takes the admins the walk now holds as the pin; none would leave nothing to check with. */
    private void follow(final String commit, final TreeModel model, final List<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        final Set<String> admins =
                new GitTreeOrganization(commit, model, List.of(), List.of(), description)
                        .getHosts().stream()
                                .filter(each -> each.host().equals(host))
                                .flatMap(each -> each.admins().stream())
                                .map(Organization.Owner::fingerprint)
                                .collect(Collectors.toSet());
        if (!admins.isEmpty()) {
            pin = admins;
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
