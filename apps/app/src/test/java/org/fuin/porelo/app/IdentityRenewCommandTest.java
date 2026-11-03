package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person renews their card with the same key, on the real store and a real agent. A card that
 * ends locks its person out of every machine, so one past its end must still come back.
 */
class IdentityRenewCommandTest {

    @TempDir Path dir;

    private RegistryCheckout registry;

    private TestOrganization organization;

    @BeforeEach
    void founded() throws Exception {
        registry = new RegistryCheckout(dir);
        organization = new TestOrganization(registry);
        registry.agent("alice");
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
    }

    private void filed() throws Exception {
        new IdentityStore(registry.context()).keep(organization.filed("alice"));
    }

    private int renew(final String id) {
        return IdentityRun.identity(organization, "renew", "--id", id, "--json");
    }

    private PersonCard kept() throws Exception {
        return new IdentityStore(registry.context()).card().orElseThrow();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @Test
    void aRenewalFilesACardOfTheSameKeyWithANewTerm() throws Exception {
        filed();
        final String before = organization.tip();

        assertThat(renew("r1")).as(registry.err::toString).isZero();

        final Map<?, ?> answer = registry.answer();
        assertThat(answer.get("action")).isEqualTo("renew");
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(answer.get("expires")).isEqualTo(today().plusDays(30).toString());
        assertThat(answer.get("revision")).isEqualTo(organization.tip());
        assertThat(organization.tip()).isNotEqualTo(before);
        final PersonCard card = PersonCard.read(organization.card("alice"), "filed");
        assertThat(card.fingerprint()).isEqualTo(registry.pin("alice"));
        assertThat(card.expires()).isEqualTo(today().plusDays(30));
        assertThat(card.predecessor()).as("no change of key").isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
        assertThat(kept().expires()).isEqualTo(today().plusDays(30));
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        final Map<?, ?> shown = registry.answer();
        assertThat(shown.get("card_refused")).as("a reader takes it").isNotEqualTo(true);
        assertThat(((Number) shown.get("days_left")).intValue()).isEqualTo(30);
        assertThat(shown.get("renew_due")).isEqualTo(false);
    }

    @Test
    void aCardPastItsEndIsRenewedAndStandsForAReader() throws Exception {
        filed();
        final String ended =
                PersonCard.write(
                        "alice",
                        "Alice",
                        "",
                        registry.keys.get("alice"),
                        today().minusDays(60),
                        today().minusDays(30),
                        null);
        new IdentityStore(registry.context()).keep(ended);

        assertThat(renew("r1")).as(registry.err::toString).isZero();

        assertThat(kept().expires()).isEqualTo(today().plusDays(30));
        assertThat(PersonCard.read(organization.card("alice"), "filed").expires())
                .isEqualTo(today().plusDays(30));
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(registry.answer().get("card_refused")).isNotEqualTo(true);
    }

    @Test
    void aCardPastItsEndOrCloseToItIsShownAsDue() throws Exception {
        filed();
        for (final int left : new int[] {-3, 7, 8}) {
            new IdentityStore(registry.context())
                    .keep(
                            PersonCard.write(
                                    "alice",
                                    "Alice",
                                    "",
                                    registry.keys.get("alice"),
                                    today().minusDays(60),
                                    today().plusDays(left),
                                    null));

            assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();

            final Map<?, ?> shown = registry.answer();
            assertThat(((Number) shown.get("days_left")).intValue()).isEqualTo(left);
            assertThat(shown.get("renew_due")).isEqualTo(left <= 7);
        }
    }

    @Test
    void theSameIdAgainAnswersTheSameRenewal() throws Exception {
        filed();
        assertThat(renew("r1")).isZero();
        final String tip = organization.tip();

        assertThat(renew("r1")).isZero();

        assertThat(registry.answer().get("again")).isEqualTo(true);
        assertThat(organization.tip()).isEqualTo(tip);
    }

    @Test
    void aKeyTheAgentDoesNotServeRenewsNothing() throws Exception {
        filed();
        final String tip = organization.tip();
        registry.runner.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                                "ssh-add", "-d", registry.keyFiles.get("alice") + ".pub")
                        .withEnvironment(
                                Map.of(
                                        "SSH_AUTH_SOCK",
                                        registry.environment().apply("SSH_AUTH_SOCK"))));

        assertThat(renew("r1")).isEqualTo(69);

        assertThat(registry.answer().get("reason")).isEqualTo("no-key");
        assertThat(organization.tip()).isEqualTo(tip);
    }
}
