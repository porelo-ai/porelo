package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultException;
import org.junit.jupiter.api.Test;

/** Guards the card an identity verb makes through the agent before it is kept or filed. */
class IdentityChangeTest {

    private static final LocalDate MADE = LocalDate.of(2026, 10, 31);

    private final SigningKey own = SigningKey.generate("own");

    private final SigningKey other = SigningKey.generate("other");

    /** A key the agent names by one blob and signs for with another key, or not at all. */
    private record Agent(AgentKey named, AgentKey signs) implements AgentKey {

        @Override
        public String comment() {
            return named.comment();
        }

        @Override
        public byte[] keyBlob() {
            return named.keyBlob();
        }

        @Override
        public String authorizedKeysLine() {
            return named.authorizedKeysLine();
        }

        @Override
        public byte[] sign(byte[] data, int flags) {
            if (signs == null) {
                throw new VaultException("the agent refused to sign");
            }
            return signs.sign(data, flags);
        }
    }

    /** A card the agent signed with another key verifies nowhere; it must never be kept. */
    @Test
    void an_agent_signing_with_another_key_is_key_not_in_agent() {
        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                IdentityChange.signed(
                                        "alice", "Alice", "", new Agent(own, other), MADE, null));
        assertThat(refused.code()).isEqualTo(65);
        assertThat(refused.reason()).isEqualTo("key-not-in-agent");
    }

    /** The predecessor's signature is what hands the key on; a wrong one hands nothing on. */
    @Test
    void a_predecessor_signing_wrongly_is_key_not_in_agent() {
        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                IdentityChange.signed(
                                        "alice", "Alice", "", own, MADE, new Agent(other, own)));
        assertThat(refused.reason()).isEqualTo("key-not-in-agent");
    }

    /** An agent that will not sign is the person's to unlock, typed, never an internal error. */
    @Test
    void an_agent_refusing_to_sign_is_key_not_in_agent() {
        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                IdentityChange.signed(
                                        "alice", "Alice", "", new Agent(own, null), MADE, null));
        assertThat(refused.code()).isEqualTo(65);
        assertThat(refused.answer()).containsEntry("reason", "key-not-in-agent");
    }

    @Test
    void an_honest_agent_makes_a_card_that_verifies() throws Exception {
        final String card = IdentityChange.signed("alice", "Alice", "", own, MADE, other);
        assertThat(org.fuin.porelo.vault.PersonCard.read(card, "test").signedAsItMustBe()).isTrue();
    }

    /**
     * A refusal may quote a URL with its token; what a place's why says is printed and kept, so no
     * token, with a slash in it or in an scp-style address, stays in it. The rule itself is {@code
     * UserinfoTest}'s.
     */
    @Test
    void no_token_stays_in_a_places_why() {
        final IdentityOperation.Place place =
                new IdentityOperation.Place(
                        "registry",
                        "https://forge.example.org/acme/registry.git",
                        "refused",
                        "git said 'https://alice:to/k3n@forge.example.org/acme/r.git' and"
                                + " alice:s3cret@forge.example.org:acme/r.git, mail a@example.org, now",
                        java.util.List.of());
        assertThat(place.why())
                .doesNotContain("k3n", "s3cret", "alice")
                .contains(
                        "forge.example.org/acme/r.git'",
                        "forge.example.org:acme/r.git",
                        "mail example.org, now");
    }
}
