package org.fuin.porelo.vault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Follows a card back through its changes of key to a key already trusted.
 *
 * <p>Trust is pinned once, to a key's fingerprint. A later card is accepted only when every step
 * from the pinned key to it was a change signed by both keys, so a card of a key nobody pinned is
 * never taken on its word.
 */
public final class PersonCardChain {

    /** More steps than any person makes; it stops a list of cards that leads in a circle. */
    private static final int MAX_STEPS = 64;

    private PersonCardChain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks a card against a pinned key.
     *
     * @param card The card to accept.
     * @param earlier Earlier cards of the same person, in any order, as many as the chain needs.
     * @param pinned The fingerprint trusted, {@code SHA256:...}.
     * @return The fingerprints from the pinned key to the card's, both included.
     * @throws CardException If a card is not signed as it must be, belongs to another login, or the
     *     chain does not reach the pinned key.
     */
    public static List<String> check(
            final PersonCard card, final List<PersonCard> earlier, final String pinned) {
        return check(card, earlier, pinned, fingerprint -> false);
    }

    /**
     * Checks a card against a pinned key, never through a revoked one.
     *
     * <p>The revoked keys are left out while the chain is sought, not looked at in the chain found:
     * a person who re-certified their key around a revoked one is accepted whatever order the cards
     * come in. The card's own key is the caller's to judge.
     *
     * @param card The card to accept.
     * @param earlier Earlier cards of the same person, in any order, as many as the chain needs.
     * @param pinned The fingerprint trusted, {@code SHA256:...}.
     * @param revoked Whether a fingerprint is on the revocation list.
     * @return The fingerprints from the pinned key to the card's, both included.
     * @throws CardException If a card is not signed as it must be, belongs to another login, or no
     *     chain reaches the pinned key except through a revoked one.
     */
    public static List<String> check(
            final PersonCard card,
            final List<PersonCard> earlier,
            final String pinned,
            final Predicate<String> revoked) {
        final Set<String> seen = new HashSet<>();
        seen.add(card.fingerprint());
        return walk(card, card, earlier, pinned, revoked, seen);
    }

    /**
     * Walks back from one card. Each key is followed once: a key that led nowhere leads nowhere the
     * second time either, so a list of many copies costs no more than one of each.
     */
    private static List<String> walk(
            final PersonCard card,
            final PersonCard current,
            final List<PersonCard> earlier,
            final String pinned,
            final Predicate<String> revoked,
            final Set<String> seen) {
        if (!current.signedAsItMustBe()) {
            throw new CardException(
                    "the card of "
                            + current.fingerprint()
                            + " is not signed by its own key"
                            + (current.predecessor().isEmpty() ? "" : " and its predecessor"));
        }
        if (!current.login().equals(card.login())) {
            throw new CardException(
                    "the card of "
                            + current.fingerprint()
                            + " is "
                            + current.login()
                            + "'s, not "
                            + card.login()
                            + "'s");
        }
        if (current.fingerprint().equals(pinned)) {
            return List.of(pinned);
        }
        if (current.predecessor().isEmpty()) {
            throw new CardException(
                    card.login() + "'s card does not lead back to the pinned key " + pinned);
        }
        final String before = PersonCard.fingerprint(current.predecessor());
        if (revoked.test(before)) {
            throw new CardException(
                    card.login() + "'s card leads back through " + before + ", which is revoked");
        }
        if (before.equals(pinned)) {
            return List.of(before, current.fingerprint());
        }
        if (!seen.add(before) || seen.size() > MAX_STEPS) {
            throw new CardException(card.login() + "'s cards lead in a circle");
        }
        final List<PersonCard> candidates =
                earlier.stream().filter(each -> each.fingerprint().equals(before)).toList();
        if (candidates.isEmpty()) {
            throw new CardException(
                    card.login()
                            + "'s card of "
                            + before
                            + " is needed to lead back to the pinned key "
                            + pinned);
        }
        CardException refused = null;
        for (final PersonCard candidate : candidates) {
            try {
                final List<String> chain =
                        new ArrayList<>(walk(card, candidate, earlier, pinned, revoked, seen));
                chain.add(current.fingerprint());
                return List.copyOf(chain);
            } catch (final CardException ex) {
                refused = ex;
            }
        }
        throw Objects.requireNonNull(refused);
    }
}
