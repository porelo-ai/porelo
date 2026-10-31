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

    /**
     * Reads the registry's owners file for filing: it goes to {@code owners.yml} and is filed by
     * any owner the accepted one names, by their own key; a backup key files nothing here.
     *
     * @param text The owners file.
     * @param origin Where it came from, for messages.
     * @param owners The owners, as the read of the registry accepted them.
     * @param revoked The fingerprints of the keys the registry revoked.
     * @return The filing.
     * @throws CardException If it is not an owners file, or no owner's key may file it.
     */
    public static Filing owners(
            String text,
            String origin,
            List<RegistryState.Owner> owners,
            java.util.Collection<String> revoked) {
        RegistryOwners.read(text, origin);
        final List<String> keys =
                owners.stream()
                        .filter(owner -> !owner.backup() && !revoked.contains(owner.fingerprint()))
                        .map(RegistryState.Owner::key)
                        .toList();
        if (keys.isEmpty()) {
            throw new CardException("the registry accepts no owners.yml whose owners may file one");
        }
        return new Filing("owners.yml", keys);
    }

    /**
     * Reads the owners file for filing by a backup key: the key must be one the accepted file names
     * as a backup key, not revoked; what it may change the reader judges.
     *
     * @param text The owners file.
     * @param origin Where it came from, for messages.
     * @param key The backup key, {@code ssh-ed25519 AAAA...}.
     * @param state The registry as read.
     * @return The filing, by that key alone.
     * @throws CardException If it is not an owners file, or the key is no backup key in force.
     */
    public static Filing ownersByBackup(
            String text, String origin, String key, RegistryState state) {
        RegistryOwners.read(text, origin);
        final String fingerprint = PersonCard.fingerprint(key);
        final boolean backup =
                java.util.stream.Stream.concat(
                                state.owners().stream(),
                                state.hostAdmins().values().stream().flatMap(List::stream))
                        .anyMatch(
                                entry -> entry.backup() && entry.fingerprint().equals(fingerprint));
        if (!backup || state.revoked().contains(fingerprint)) {
            throw new CardException(
                    "the key file's key "
                            + fingerprint
                            + " is no backup key the accepted owners.yml names");
        }
        return new Filing("owners.yml", List.of(key));
    }

    /**
     * The entries an owners file names for the registry, or for one host.
     *
     * @param text The owners file.
     * @param origin Where it came from, for messages.
     * @param host The host whose admins are asked, or {@code null} for the registry's owners.
     * @return Them, in the file's order; none for a host it names no admin of.
     * @throws CardException If it is not an owners file.
     */
    public static List<RegistryState.Owner> entries(
            String text, String origin, @org.jspecify.annotations.Nullable String host) {
        final RegistryOwners file = RegistryOwners.read(text, origin);
        return (host == null ? file.owners() : file.hostAdmins().getOrDefault(host, List.of()))
                .stream()
                        .map(
                                entry ->
                                        new RegistryState.Owner(
                                                entry.login(),
                                                entry.key(),
                                                entry.fingerprint(),
                                                entry.backup()))
                        .toList();
    }

    /**
     * The admins an owners file names for each host.
     *
     * @param text The owners file.
     * @param origin Where it came from, for messages.
     * @return Them, by host label in order, each host's in the file's order.
     * @throws CardException If it is not an owners file.
     */
    public static java.util.Map<String, List<RegistryState.Owner>> hostAdmins(
            String text, String origin) {
        final java.util.Map<String, List<RegistryState.Owner>> hosts = new java.util.TreeMap<>();
        for (String host : RegistryOwners.read(text, origin).hostAdmins().keySet()) {
            hosts.put(host, entries(text, origin, host));
        }
        return hosts;
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
