package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards which access repositories a host's access file may name. Harm: an address kept with a
 * blank reaches git as something else than what was checked, and one with an {@code @} after its
 * host names a user part as the host, in the answer and in the vault entry a refusal names.
 */
class RegistryAccessTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@example.org/demo.git",
                "https://example.org/acme/demo.git",
                "git@example.org:acme/demo.git",
                "https://example.org",
                "https://example.org:8443/demo.git?ref=main#top",
                "https://[2001:db8::1]:8443/demo.git",
                "ssh://git@[2001:db8::1]:2222/demo.git",
                "git@[2001:db8::1]:demo.git",
                "git@[::1]:demo.git",
                "git@build_host.example.org:acme/demo.git",
                "git@xn--bcher-kva.example:acme/demo.git"
            })
    void an_address_of_the_accepted_forms_is_taken(final String address) {
        assertThat(RegistryAccess.accessRepository(address)).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@example.org/demo.git ",
                "ssh://git@example.org/demo.git\t",
                "ssh://git@example.org/demo.git\n",
                "git@example.org:acme/demo.git\r",
                " ssh://git@example.org/demo.git",
                "ssh://git@example.org/de mo.git",
                "https://bob:tok/4711@example.org/demo.git",
                "https://example.org/x/@github.com/demo.git"
            })
    void an_address_with_a_blank_anywhere_or_an_at_after_its_host_is_refused(final String address) {
        assertThat(RegistryAccess.accessRepository(address))
                .as("'" + address.replace("\n", "\\n") + "'")
                .isFalse();
    }

    /**
     * Each spelling git or ssh reads with a credential as its host, or answers with one: git ends
     * an https host at {@code ?} or {@code #}, reads {@code %40} as an at sign, takes the first of
     * two at signs where ssh takes the last, and splits {@code user@host:path} at its first colon.
     * No credential at all is taken: an https user part may be a token, and git decodes an ssh
     * user's {@code %} spelling and hands ssh the password.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://tok-4711?x@example.org/demo.git",
                "https://tok-4711#x@example.org/demo.git",
                "https://bob:tok-4711%40example.org/demo.git",
                "https://example.org?x=tok-4711@other.example.org",
                "https://bob:tok@4711@example.org/demo.git",
                "ssh://tok-4711?x@example.org/demo.git",
                "ssh://bob@tok-4711@example.org/demo.git",
                "bob@tok-4711@example.org:acme/demo.git",
                "https@bob:tok-4711@example.org/demo.git",
                "bob%3Atok-4711@example.org:acme/demo.git",
                "git@example.org:acme/tok-4711@demo.git",
                "git@-oProxyCommand=x:acme/demo.git",
                "https://bob:tok%404711@example.org/demo.git",
                "https://tok-4711@example.org/demo.git",
                "ssh://git:tok:4711@example.org:2222/demo.git",
                "ssh://git%3Atok-4711@example.org/demo.git",
                "-git@example.org:acme/demo.git"
            })
    void an_address_whose_host_git_or_ssh_would_read_otherwise_is_refused(final String address) {
        assertThat(RegistryAccess.accessRepository(address))
                .as("'" + address.replace("\n", "\\n") + "'")
                .isFalse();
    }
}
