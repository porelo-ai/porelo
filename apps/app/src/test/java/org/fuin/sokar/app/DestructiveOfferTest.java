package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the destructive remedies offered instead of sending a person to type an option: each defaults to no, says
 * what it touches first, and is never taken by --yes.
 */
class DestructiveOfferTest {

    private final StringWriter said = new StringWriter();

    private Offer answering(final String answer, final boolean yes) {
        return new Offer(question -> answer, false, yes, new PrintWriter(said, true));
    }

    @Test
    void clearOffersToStopTheTasksThatHoldATokenFromTheVault() {
        final List<Clearing.Item> refused = List.of(
                new Clearing.Item("task", "sokar-uc-a", Clearing.Status.NOT_REMOVED, "it runs with a token from the vault"),
                new Clearing.Item("vault", "/v", Clearing.Status.NOT_REMOVED, "a transport could not clear an account"));

        assertThat(ClearCommand.stopFirst(refused, answering("", false))).containsExactly("sokar-uc-a");
        assertThat(ClearCommand.stopFirst(refused, answering("n", false))).isEmpty();
        assertThat(ClearCommand.stopFirst(refused, Offer.NOBODY)).isEmpty();
    }

    @Test
    void clearOffersWhatOnlyForceClearsAsNoByDefaultNamingIt() {
        final List<Clearing.Item> refused = List.of(new Clearing.Item("project", "uc", Clearing.Status.NOT_REMOVED,
                "it holds work nobody has reviewed"));

        assertThat(ClearCommand.forceAnyway(refused, answering("", false))).isFalse();
        assertThat(ClearCommand.forceAnyway(refused, answering("", true))).as("never by --yes").isFalse();
        assertThat(ClearCommand.forceAnyway(refused, answering("y", false))).isTrue();
        assertThat(said.toString()).contains("project uc");
    }

    @Test
    void vaultRemoveOffersToForgetATokenTheServiceMustRevokeOnlyOnAnExplicitYes() {
        assertThat(VaultRemoveCommand.withoutRevokingAfterAll("copilot", "at github.com", answering("", false)))
                .isFalse();
        assertThat(VaultRemoveCommand.withoutRevokingAfterAll("copilot", "at github.com", answering("y", false)))
                .isTrue();
        assertThat(said.toString()).contains("works there until it is revoked");
        assertThat(VaultRemoveCommand.withoutRevokingAfterAll("copilot", "x", Offer.NOBODY)).isFalse();
    }

    @Test
    void credentialsForgetOffersToRemoveTheSecretLeftInTheVault() {
        assertThat(CredentialsForgetCommand.removeToo("deploy-key", answering("", false))).isFalse();
        assertThat(CredentialsForgetCommand.removeToo("deploy-key", answering("y", false))).isTrue();
        assertThat(CredentialsForgetCommand.removeToo("deploy-key", Offer.NOBODY)).isFalse();
    }
}
