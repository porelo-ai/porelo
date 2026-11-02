package org.fuin.porelo.organization.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.Organization;
import org.junit.jupiter.api.Test;

class GitOrganizationTest {

    private static Organization.Person person(
            final String login, final String fingerprint, final String state) {
        return new Organization.Person(
                login, login, login + "@example.org", "ssh-ed25519 AAAA", fingerprint, state, "");
    }

    private static Organization holding(final Organization.Person... people) {
        return new GitOrganization(
                null,
                null,
                List.of(),
                Map.of(),
                List.of(people),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    void the_holder_of_a_key_on_an_accepted_card_is_named() {
        final Organization organization = holding(person("ann", "SHA256:a", Organization.TRUSTED));

        assertThat(organization.keyHolder("SHA256:a", null)).isEqualTo("ann");
        assertThat(organization.keyHolder("SHA256:a", "bob")).isEqualTo("ann");
    }

    @Test
    void the_login_given_is_no_other_holder() {
        final Organization organization = holding(person("ann", "SHA256:a", Organization.TRUSTED));

        assertThat(organization.keyHolder("SHA256:a", "ann")).isNull();
    }

    @Test
    void a_refused_card_holds_no_key() {
        final Organization organization = holding(person("ann", "SHA256:a", Organization.REFUSED));

        assertThat(organization.keyHolder("SHA256:a", null))
                .as("a refused card is answered but is no card")
                .isNull();
    }

    @Test
    void a_key_on_no_card_has_no_holder() {
        assertThat(holding().keyHolder("SHA256:a", null)).isNull();
    }
}
