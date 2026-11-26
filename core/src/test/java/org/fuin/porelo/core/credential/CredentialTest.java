package org.fuin.porelo.core.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Credential#covers}: a credential is for its host and its path, and nothing that
 * only begins alike.
 */
class CredentialTest {

    private static Credential at(String match) {
        return new Credential(
                "forge",
                Credential.Kind.TOKEN,
                match,
                null,
                Credential.ANY,
                Credential.Source.VAULT);
    }

    @Test
    void aMatchCoversItsHostAndWhatIsBelowItNotAHostOrPathThatOnlyBeginsLikeIt() {

        // A plain prefix: 'https://github.com' covered 'https://github.com.evil.example', and
        // '/org' '/org-evil'.
        assertThat(at("https://github.com").covers("https://github.com/org/repo")).isTrue();
        assertThat(at("https://github.com").covers("https://github.com")).isTrue();
        assertThat(at("https://github.com").covers("https://github.com.evil.example/x")).isFalse();
        assertThat(at("https://github.com/org").covers("https://github.com/org/repo")).isTrue();
        assertThat(at("https://github.com/org").covers("https://github.com/org-evil/repo"))
                .isFalse();
        assertThat(at("https://github.com/org/").covers("https://github.com/org/repo")).isTrue();
    }

    @Test
    void aRecordForAnIpv6HostCoversItsScpSpellingAndNoOtherHost() {

        // Normalised at the first colon inside the brackets, 'git@[2001:db8::1]:x' read as
        // 'ssh://[2001/db8::1]:x', which no record for its host covers.
        final CredentialRegistry registry =
                new CredentialRegistry(List.of(at("ssh://[2001:db8::1]")));
        assertThat(CredentialRegistry.normalise("git@[2001:db8::1]:acme/demo.git"))
                .isEqualTo("ssh://[2001:db8::1]/acme/demo.git");
        assertThat(registry.forUrl("git", "git@[2001:db8::1]:acme/demo.git"))
                .as("the record for the host git connects to")
                .isNotNull();
        assertThat(registry.forUrl("git", "git@[2001:db8::2]:acme/demo.git"))
                .as("another IPv6 host")
                .isNull();
    }

    @Test
    void aUserIsStrippedOnlyInsideTheAuthority() {
        // Read up to the first '/', the '@' after a '#' made github.com's record cover an address
        // git takes to evil.example.
        assertThat(CredentialRegistry.normalise("https://evil.example#@github.com/x"))
                .as("an https authority ends at '#'")
                .isEqualTo("https://evil.example#@github.com/x");
        assertThat(CredentialRegistry.normalise("https://evil.example?@github.com/x"))
                .as("an https authority ends at '?'")
                .isEqualTo("https://evil.example?@github.com/x");
        assertThat(CredentialRegistry.normalise("https://me@forge.example/x"))
                .as("a user in the authority is no part of the destination")
                .isEqualTo("https://forge.example/x");
        assertThat(CredentialRegistry.normalise("ssh://evil.example#@github.com/x"))
                .as("ssh takes the host after the last '@' before the first '/'")
                .isEqualTo("ssh://github.com/x");
        assertThat(CredentialRegistry.normalise("ssh://git@forge.example/a/@other.example/b"))
                .as("an '@' in the path is no user")
                .isEqualTo("ssh://forge.example/a/@other.example/b");
        assertThat(
                        new CredentialRegistry(List.of(at("https://github.com")))
                                .forUrl("git", "https://evil.example#@github.com/x"))
                .as("github.com's record does not cover an address git takes to evil.example")
                .isNull();
    }
}
