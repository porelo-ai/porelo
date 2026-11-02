package org.fuin.porelo.organization.gittree;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.vault.HostCard;
import org.jspecify.annotations.Nullable;

/**
 * What a walk has accepted so far; copied before each commit, which is judged against it.
 *
 * @param owners The entries of {@code owners.yml}.
 * @param people Each login's card.
 * @param projects Each project.
 * @param revoked The fingerprints of the keys revoked.
 * @param cards The blob last accepted at each card's path.
 * @param hosts Each host.
 */
record TreeModel(
        List<TreeFiles.Owner> owners,
        Map<String, Person> people,
        Map<String, ProjectState> projects,
        Set<String> revoked,
        Map<String, String> cards,
        Map<String, HostState> hosts) {

    /**
     * A person's card as it stands.
     *
     * @param fingerprint The fingerprint of its key.
     * @param state {@link Organization#TRUSTED}, {@link Organization#FIRST_SEEN} or {@link
     *     Organization#REFUSED}.
     * @param handedOn The keys it was handed on from, newest first, never its own.
     * @param predecessor The fingerprint of the key the card names as its predecessor, or "".
     * @param card The card's text, answered as it is.
     * @param earlier The keys this login held before, oldest first.
     */
    record Person(
            String fingerprint,
            String state,
            List<String> handedOn,
            String predecessor,
            String card,
            List<EarlierKey> earlier) {

        Person with(final String newState) {
            return new Person(fingerprint, newState, handedOn, predecessor, card, earlier);
        }
    }

    /**
     * A key a login no longer holds.
     *
     * @param key The key.
     * @param validFrom From when its card said it held.
     * @param validUntil Until when its card said it held.
     * @param revokedSince The date of the commit that replaced it, as its signer wrote it.
     */
    record EarlierKey(String key, LocalDate validFrom, LocalDate validUntil, String revokedSince) {}

    /**
     * A project as it stands.
     *
     * @param admin The first admin an owner named.
     * @param members Its members, or {@code null} before the first {@code members.yml}.
     * @param address The address of its repository, or {@code null} until one is filed.
     */
    record ProjectState(
            String admin, @Nullable List<TreeFiles.Member> members, @Nullable String address) {}

    /**
     * A host as it stands.
     *
     * @param file Its admins and what it carries.
     * @param card Its card, or {@code null} until an admin files one.
     */
    record HostState(TreeFiles.HostFile file, @Nullable HostCard card) {}

    static TreeModel empty() {
        return new TreeModel(
                new ArrayList<>(),
                new TreeMap<>(),
                new TreeMap<>(),
                new TreeSet<>(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    TreeModel copy() {
        return new TreeModel(
                new ArrayList<>(owners),
                new TreeMap<>(people),
                new TreeMap<>(projects),
                new TreeSet<>(revoked),
                new TreeMap<>(cards),
                new TreeMap<>(hosts));
    }

    boolean owner(final String fingerprint) {
        return owners.stream()
                .anyMatch(owner -> owner.fingerprint().equals(fingerprint) && !owner.backup());
    }
}
