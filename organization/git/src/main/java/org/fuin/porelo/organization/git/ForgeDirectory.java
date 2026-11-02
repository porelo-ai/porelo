package org.fuin.porelo.organization.git;

import java.util.List;
import java.util.Optional;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;

/**
 * What the central forge says about its accounts: the keys it lists for a login, and who owns an
 * organisation. A registry reader asks it when a card or the first owners file is first seen, and
 * again for a card it could not settle yet; an empty answer means the forge could not be asked,
 * never that it said no.
 */
public interface ForgeDirectory {

    /**
     * The keys the forge lists for a login.
     *
     * @param keys The keys, {@code ssh-ed25519 AAAA...} each, possibly none.
     * @param complete Whether these are all the lists the forge keeps; when one of them could not
     *     be read, a key missing here may be in it, so its absence proves nothing.
     */
    record Keys(List<String> keys, boolean complete) {
        /** Compact constructor: the keys are copied and cannot be changed afterwards. */
        public Keys {
            keys = List.copyOf(keys);
        }

        /**
         * Returns whether the list holds the key of a fingerprint, a comment after it or not; a
         * line that is no Ed25519 key holds none.
         *
         * @param fingerprint The key's fingerprint, {@code SHA256:...}.
         * @return Whether it is listed.
         */
        public boolean lists(String fingerprint) {
            for (String key : keys) {
                try {
                    final String[] fields = key.strip().split("\\s+");
                    if (fields.length >= 2
                            && PersonCard.fingerprint(fields[0] + " " + fields[1])
                                    .equals(fingerprint)) {
                        return true;
                    }
                } catch (CardException ex) {
                    // A line that is no Ed25519 key lists no key a card can carry.
                }
            }
            return false;
        }
    }

    /**
     * Returns the public keys the forge lists for a login.
     *
     * @param login The forge login.
     * @return The keys, and whether the list is complete; or empty when the forge could not be
     *     asked.
     */
    Optional<Keys> keys(String login);

    /**
     * Returns the keys the forge lists for a login, asked about one key: a forge that keeps more
     * than one list may stop at the first that holds it, and then answers that list, incomplete.
     *
     * @param login The forge login.
     * @param fingerprint The fingerprint of the key asked about, {@code SHA256:...}.
     * @return The keys, and whether the list is complete; or empty when the forge could not be
     *     asked.
     */
    default Optional<Keys> keys(String login, String fingerprint) {
        return keys(login);
    }

    /**
     * Returns whether a login is an owner of an organisation on the forge.
     *
     * @param organisation The forge organisation.
     * @param login The forge login.
     * @return Whether it is an owner, only when the forge said so plainly; or empty when it cannot
     *     say.
     */
    Optional<Boolean> owns(String organisation, String login);
}
