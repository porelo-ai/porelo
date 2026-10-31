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
                "https://bob:tok@4711@example.org/demo.git",
                "git@example.org:acme/demo.git"
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
}
