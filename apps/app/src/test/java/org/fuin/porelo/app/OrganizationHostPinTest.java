package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.OrganizationHostPin.Listed;
import org.fuin.porelo.app.OrganizationHostPin.Pin;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A host the organization lists is reached trusting only the key its card pins; the card counts
 * only from a read that is whole and fresh, signed by an admin the host still lists.
 */
class OrganizationHostPinTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyPinnedByTheCard";

    private static final String ADMIN = "SHA256:admin";

    private static OrganizationQuestions.Host host(
            final String name, final Organization.@Nullable Card card) {
        return new OrganizationQuestions.Host(
                name,
                card,
                List.of(
                        new Organization.Owner("ada", "ssh-ed25519 AAAA", ADMIN, false),
                        new Organization.Owner("ada", "ssh-ed25519 BBBB", "SHA256:backup", true)),
                List.of("uc"),
                Map.of());
    }

    private static Organization.Card card(
            final String address, final int port, final String key, final String signer) {
        return new Organization.Card(address, port, key, 3000, 2222, 8448, signer);
    }

    private static Listed whole(final OrganizationQuestions.Host... hosts) {
        return new Listed(List.of(hosts), true);
    }

    @Test
    void aListedHostIsPinnedByItsNameOrItsAddress() throws Exception {
        final Listed listed = whole(host("build", card("build.example.org", 2201, KEY, ADMIN)));

        final Pin byName = OrganizationHostPin.choose("me@build", listed);
        final Pin byAddress = OrganizationHostPin.choose("me@Build.Example.org.", listed);

        assertThat(byName)
                .as("named as the organization names it")
                .isEqualTo(new Pin("build", "build.example.org", 2201, KEY, List.of("ada")));
        assertThat(byAddress).as("its address, however spelled").isEqualTo(byName);
    }

    @Test
    void noOrganizationOrAnUnlistedHostTakesTodaysPath() throws Exception {
        assertThat(OrganizationHostPin.choose("me@build", null)).as("no organization").isNull();
        assertThat(
                        OrganizationHostPin.choose(
                                "me@elsewhere.example.org",
                                whole(host("build", card("build.example.org", 22, KEY, ADMIN)))))
                .as("a host it does not list")
                .isNull();
        assertThat(
                        OrganizationHostPin.choose(
                                "me@elsewhere.example.org",
                                new Listed(
                                        List.of(
                                                host(
                                                        "build",
                                                        card("build.example.org", 22, KEY, ADMIN))),
                                        false)))
                .as("a stale read that does not list it either")
                .isNull();
    }

    @Test
    void aCardThatDoesNotCountGivesNoPin() throws Exception {
        assertThat(OrganizationHostPin.choose("me@build", whole(host("build", null))))
                .as("no accepted card")
                .isNull();
        assertThat(
                        OrganizationHostPin.choose(
                                "me@build",
                                whole(host("build", card("build.example.org", 22, "", ADMIN)))))
                .as("no key on the card")
                .isNull();
        assertThat(
                        OrganizationHostPin.choose(
                                "me@build",
                                whole(
                                        host(
                                                "build",
                                                card(
                                                        "build.example.org",
                                                        22,
                                                        KEY,
                                                        "SHA256:gone")))))
                .as("signed by no admin the host lists now")
                .isNull();
        assertThat(
                        OrganizationHostPin.choose(
                                "me@build",
                                whole(
                                        host(
                                                "build",
                                                card(
                                                        "build.example.org",
                                                        22,
                                                        KEY,
                                                        "SHA256:backup")))))
                .as("signed by a backup key, which is no admin")
                .isNull();
    }

    @Test
    void aListedHostInAStaleReadIsRefused() {
        final Listed stale =
                new Listed(
                        List.of(host("build", card("build.example.org", 22, KEY, ADMIN))), false);

        assertThatThrownBy(() -> OrganizationHostPin.choose("me@build.example.org", stale))
                .isInstanceOf(OrganizationHostPin.Refused.class)
                .hasMessageContaining("cannot be checked")
                .extracting(ex -> ((OrganizationHostPin.Refused) ex).code())
                .isEqualTo(69);
    }

    @Test
    void twoHostsClaimingOneAddressAreRefused() {
        final Listed listed =
                whole(
                        host("build", card("build.example.org", 22, KEY, ADMIN)),
                        host("other", card("build.example.org", 22, KEY, ADMIN)));

        assertThatThrownBy(() -> OrganizationHostPin.choose("me@build.example.org", listed))
                .isInstanceOf(OrganizationHostPin.Refused.class)
                .hasMessageContaining("more than one host")
                .extracting(ex -> ((OrganizationHostPin.Refused) ex).code())
                .isEqualTo(65);
    }

    @Test
    void aCardSshWouldNotTakeIsRefusedNamingItsAdmins() {
        for (final Organization.Card card :
                List.of(
                        card("-oProxyCommand=x", 22, KEY, ADMIN),
                        card("build.example.org", 0, KEY, ADMIN),
                        card("build.example.org", 22, "ssh-ed25519 AAAA\nother", ADMIN))) {
            assertThatThrownBy(
                            () ->
                                    OrganizationHostPin.choose(
                                            "me@build", whole(host("build", card))))
                    .as("%s", card)
                    .isInstanceOf(OrganizationHostPin.Refused.class)
                    .hasMessageContaining("ada")
                    .extracting(ex -> ((OrganizationHostPin.Refused) ex).code())
                    .isEqualTo(65);
        }
    }
}
