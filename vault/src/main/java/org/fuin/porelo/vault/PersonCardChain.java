package org.fuin.porelo.vault;

import java.util.ArrayList;
import java.util.List;

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
        return walk(card, card, earlier, pinned, new java.util.HashSet<>());
    }

    /**
     * Walks back from one card; when several earlier cards carry the key it needs, each is tried,
     * so the order they were given in changes nothing.
     */
    private static List<String> walk(
            final PersonCard card,
            final PersonCard current,
            final List<PersonCard> earlier,
            final String pinned,
            final java.util.Set<String> seen) {
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
        if (!seen.add(current.fingerprint()) || seen.size() > MAX_STEPS) {
            throw new CardException(card.login() + "'s cards lead in a circle");
        }
        try {
            if (current.predecessor().isEmpty()) {
                throw new CardException(
                        card.login() + "'s card does not lead back to the pinned key " + pinned);
            }
            final String before = PersonCard.fingerprint(current.predecessor());
            if (before.equals(pinned)) {
                return List.of(before, current.fingerprint());
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
                            new ArrayList<>(walk(card, candidate, earlier, pinned, seen));
                    chain.add(current.fingerprint());
                    return List.copyOf(chain);
                } catch (final CardException ex) {
                    refused = ex;
                }
            }
            throw java.util.Objects.requireNonNull(refused);
        } finally {
            seen.remove(current.fingerprint());
        }
    }
}
