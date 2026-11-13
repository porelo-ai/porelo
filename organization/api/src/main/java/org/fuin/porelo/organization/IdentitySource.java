package org.fuin.porelo.organization;

import java.util.List;
import java.util.Optional;

/**
 * Who says whose keys are whose, today the central forge about its accounts: the keys it lists for
 * a login, and who owns an organisation. A registry reader asks it when a card or the first owners
 * file is first seen, and again for a card it could not settle yet; an empty answer means the forge
 * could not be asked, never that it said no.
 */
public interface IdentitySource {

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

    /**
     * Returns why the last question asked on this thread could not be answered, in Porelo's own
     * words and never the forge's: one of a few fixed kinds such as {@code no answer} or {@code
     * rate limited}.
     *
     * @return The kind, or empty when the last question was answered or the source does not say.
     */
    default Optional<String> cannotSay() {
        return Optional.empty();
    }
}
