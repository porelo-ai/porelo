package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Harm guarded: a forge asked, with this person's token, that is another host than the one git
 * fetches from.
 */
class ForgeAddressTest {

    @ParameterizedTest
    @CsvSource({
        "https://forge.example.org/acme/o.git, https://forge.example.org, acme",
        "https://forge.example.org/acme/o, https://forge.example.org, acme",
        "https://forge.example.org:8443/acme/o.git, https://forge.example.org:8443, acme",
        "git@github.com:Acme-Org/o.git, https://github.com, Acme-Org",
        "ssh://git@forge.example.org:2222/acme/o.git, https://forge.example.org, acme",
    })
    void an_address_names_the_forge_and_the_organization(
            final String address, final String forge, final String organization) {
        final ForgeAddress read = ForgeAddress.of(address);

        assertThat(read.forge()).hasToString(forge);
        assertThat(read.organization()).isEqualTo(organization);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://forge.example.org/acme/o.git",
                "https://forge.example.org/acme",
                "https://forge.example.org/acme/sub/o.git",
                "https://forge.example.org/../o.git",
                "https://user@forge.example.org/acme/o.git",
                "git@forge.example.org:acme/sub/o.git",
                "ext::sh -c touch% /tmp/x",
                "/srv/git/o.git",
                "https://forge.example.org/acme/o.git\u001b[2J",
            })
    void an_address_of_another_form_is_refused_and_named_without_controls(final String address) {
        assertThatThrownBy(() -> ForgeAddress.of(address))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not https://HOST/ORG/REPO")
                .hasMessageNotContaining("\u001b");
    }

    @Test
    void theRepositoryIsTheAddressesLastNameWithoutGit() {
        assertThat(ForgeAddress.of("git@github.com:acme/registry.git").repository())
                .isEqualTo("registry");
        assertThat(ForgeAddress.of("https://forge.example:3000/acme/registry/").repository())
                .isEqualTo("registry");
        assertThat(ForgeAddress.of("ssh://git@forge.example:2222/acme/registry").repository())
                .isEqualTo("registry");
    }
}
