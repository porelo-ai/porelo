package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A vault entry carried to a host as ciphertext only the host's one-use key opens. */
class CredentialSealTest {

    private static final VaultEntry ENTRY =
            new VaultEntry("fixture-secret-value", "api-key", Map.of("expires", "2027-01-01"));

    @Test
    void anEntrySealedToAReceiverOpensWithItsPrivateHalfAsItWas() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();

        final String sealed = CredentialSeal.seal(receiver.publicKey(), "anthropic", ENTRY);

        assertThat(receiver.open(sealed, "anthropic", "api-key")).isEqualTo(ENTRY);
    }

    @Test
    void theSealedFormHoldsNoByteOfTheValue() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();

        final byte[] sealed =
                Base64.getDecoder()
                        .decode(CredentialSeal.seal(receiver.publicKey(), "anthropic", ENTRY));

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1))
                .doesNotContain("fixture-secret-value");
    }

    @Test
    void aSealToAnotherReceiverDoesNotOpen() {
        final String sealed =
                CredentialSeal.seal(CredentialSeal.receiver().publicKey(), "anthropic", ENTRY);

        assertThatThrownBy(() -> CredentialSeal.receiver().open(sealed, "anthropic", "api-key"))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void aChangedNameOrKindDoesNotOpen() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();
        final String sealed = CredentialSeal.seal(receiver.publicKey(), "anthropic", ENTRY);

        assertThatThrownBy(() -> receiver.open(sealed, "openai", "api-key"))
                .isInstanceOf(CredentialSeal.Refused.class);
        assertThatThrownBy(() -> receiver.open(sealed, "anthropic", "oauth"))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void anEntryWithoutAKindOrSettingsSurvivesTheSeal() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();
        final VaultEntry bare = new VaultEntry("v", null, Map.of());

        assertThat(receiver.open(CredentialSeal.seal(receiver.publicKey(), "x", bare), "x", ""))
                .isEqualTo(bare);
    }

    @Test
    void aKeyThatIsNoX25519PublicKeyIsRefused() {
        assertThatThrownBy(() -> CredentialSeal.seal("bm90IGEga2V5", "anthropic", ENTRY))
                .isInstanceOf(CredentialSeal.Refused.class);
        assertThatThrownBy(() -> CredentialSeal.seal("%%%", "anthropic", ENTRY))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void garbageIsRefusedNotThrownAsSomethingElse() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();

        assertThatThrownBy(() -> receiver.open("AAAA", "anthropic", "api-key"))
                .isInstanceOf(CredentialSeal.Refused.class);
        assertThatThrownBy(() -> receiver.open("%%%", "anthropic", "api-key"))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void aNameAndKindCannotTradeANulBetweenThem() {
        // Bound as one string joined by NUL, ("a\0b", "") and ("a", "b\0") were the same data.
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();
        final String sealed =
                CredentialSeal.seal(
                        receiver.publicKey(), "a\0b", new VaultEntry("v", null, Map.of()));

        assertThatThrownBy(() -> receiver.open(sealed, "a", "b\0"))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void anEntryTooLargeToOpenIsNotSealed() {
        final VaultEntry large = new VaultEntry("v".repeat(1 << 20), null, Map.of());

        assertThatThrownBy(
                        () ->
                                CredentialSeal.seal(
                                        CredentialSeal.receiver().publicKey(), "x", large))
                .isInstanceOf(CredentialSeal.TooLarge.class);
    }

    @Test
    void aLowOrderKeyIsRefused() {
        final String zero = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> CredentialSeal.seal(zero, "anthropic", ENTRY))
                .isInstanceOf(CredentialSeal.Refused.class);
    }

    @Test
    void aChangedByteOrVersionDoesNotOpen() {
        final CredentialSeal.Receiver receiver = CredentialSeal.receiver();
        final byte[] blob =
                Base64.getDecoder()
                        .decode(CredentialSeal.seal(receiver.publicKey(), "anthropic", ENTRY));
        final byte[] body = blob.clone();
        body[body.length - 1] ^= 1;
        final byte[] version = blob.clone();
        version[0] = 2;

        for (final byte[] changed : new byte[][] {body, version}) {
            final String sealed = Base64.getEncoder().encodeToString(changed);
            assertThatThrownBy(() -> receiver.open(sealed, "anthropic", "api-key"))
                    .isInstanceOf(CredentialSeal.Refused.class);
        }
    }
}
