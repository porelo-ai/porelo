package org.fuin.porelo.vault;

import java.util.List;

/**
 * What a person files into a registry checkout: the path a card or access file goes to, and the
 * keys the registry's rule lets sign the commit that files it.
 *
 * <p>Every file is read as the reader will read it before it is filed, so a file the rule would
 * refuse for its own content never becomes a commit; a refusal names the file and never quotes it.
 */
public final class RegistryFiles {

    private RegistryFiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A file ready to be filed.
     *
     * @param path Where it goes in the registry, relative to its root.
     * @param signers The keys that may sign the commit, {@code ssh-ed25519 AAAA...} each; at least
     *     one.
     */
    public record Filing(String path, List<String> signers) {
        /** Compact constructor: the keys are copied and cannot be changed afterwards. */
        public Filing {
            signers = List.copyOf(signers);
        }
    }

    /**
     * Reads a person's card for filing: it goes to {@code people/<login>.card} and is filed by its
     * own key.
     *
     * @param text The card.
     * @param origin Where it came from, for messages.
     * @param revoked The fingerprints of the keys the registry revoked, which sign nothing it
     *     accepts.
     * @return The filing.
     * @throws CardException If it is not a card, not signed as it must be, or its key or the key it
     *     is handed on from is revoked.
     */
    public static Filing card(String text, String origin, java.util.Collection<String> revoked) {
        final PersonCard card = PersonCard.read(text, origin);
        if (!card.signedAsItMustBe()) {
            throw new CardException(origin + ": the card is not signed as it must be");
        }
        if (revoked.contains(card.fingerprint())) {
            throw new CardException(
                    origin
                            + ": the card's key "
                            + card.fingerprint()
                            + " is revoked in the registry");
        }
        // A revoked key hands nothing on: the reader refuses the change it signed.
        if (!card.predecessor().isEmpty()
                && revoked.contains(PersonCard.fingerprint(card.predecessor()))) {
            throw new CardException(
                    origin
                            + ": the card is handed on from "
                            + PersonCard.fingerprint(card.predecessor())
                            + ", which is revoked in the registry");
        }
        return new Filing("people/" + card.login() + ".card", List.of(card.key()));
    }

    /**
     * Reads a host's card for filing: it goes to {@code hosts/<host>/card} and is filed by the host
     * admin who signed it, who must be one {@code owners.yml} names for the host.
     *
     * @param text The card.
     * @param origin Where it came from, for messages.
     * @param host The host it is filed for.
     * @param admins The admins of the host, as the read of the registry accepted them from {@code
     *     owners.yml}; none when it names none.
     * @return The filing.
     * @throws CardException If it is not this host's card, or not signed by one of its admins.
     */
    public static Filing hostCard(
            String text, String origin, String host, List<RegistryState.Owner> admins) {
        checkAdmins(host, admins);
        final HostCard card = HostCard.read(text, origin);
        if (!card.host().equals(host)) {
            throw new CardException(origin + ": the card is not the card of " + host);
        }
        final String signer = card.signer();
        final List<String> keys =
                admins.stream()
                        .filter(admin -> admin.fingerprint().equals(signer))
                        .map(RegistryState.Owner::key)
                        .toList();
        if (keys.isEmpty()) {
            throw new CardException(
                    origin
                            + ": the card is signed by "
                            + signer
                            + ", whom owners.yml does not name as an admin of "
                            + host);
        }
        card.checkSignedBy(signer);
        return new Filing("hosts/" + host + "/card", keys);
    }

    /**
     * The removal of a host's card: {@code hosts/<host>/card} goes, filed by any admin {@code
     * owners.yml} names for the host, as the reader judges every change to the host's files.
     *
     * @param host The host whose card is removed.
     * @param admins The admins of the host, as the read of the registry accepted them from {@code
     *     owners.yml}; none when it names none.
     * @return The filing.
     * @throws CardException If the host has no admin.
     */
    public static Filing hostCardRemoval(String host, List<RegistryState.Owner> admins) {
        checkAdmins(host, admins);
        return new Filing(
                "hosts/" + host + "/card", admins.stream().map(RegistryState.Owner::key).toList());
    }

    /**
     * Reads a host's access file for filing: it goes to {@code hosts/<host>/access.yml} and is
     * filed by any admin {@code owners.yml} names for the host.
     *
     * @param text The access file.
     * @param origin Where it came from, for messages.
     * @param host The host it is filed for.
     * @param admins The admins of the host, as the read of the registry accepted them from {@code
     *     owners.yml}; none when it names none.
     * @return The filing.
     * @throws CardException If it is not an access file, or the host has no admin.
     */
    public static Filing hostAccess(
            String text, String origin, String host, List<RegistryState.Owner> admins) {
        checkAdmins(host, admins);
        RegistryAccess.read(text, origin);
        return new Filing(
                "hosts/" + host + "/access.yml",
                admins.stream().map(RegistryState.Owner::key).toList());
    }

    private static void checkAdmins(String host, List<RegistryState.Owner> admins) {
        if (!RegistryOwners.HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "the host must be a lower-case DNS label, not '"
                            + RegistryText.printable(host)
                            + "'");
        }
        if (admins.isEmpty()) {
            throw new CardException("the accepted owners.yml names no admin of " + host);
        }
    }
}
