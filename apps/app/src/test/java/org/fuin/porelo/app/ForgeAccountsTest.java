package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests for {@link ForgeAccounts}: the account an entry's token stands for, as the forge says. */
class ForgeAccountsTest {

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/7"));

    private static final String ENTRY = "forge/github.com/porelo-review";

    private static final String SHA = "a".repeat(40);

    private @Nullable ForgeAccount viewer = new ForgeAccount("github.com", "583231", "octocat");

    private final List<String> asked = new ArrayList<>();

    private final ForgeReview forge =
            new ForgeReview() {
                @Override
                public PullRequestFacts facts(
                        final PullRequestRef ref, final ForgeCredential credential) {
                    asked.add(ref.url() + " with " + credential.entry());
                    return new PullRequestFacts(
                            ref,
                            "https://github.com/uc/shop",
                            SHA,
                            SHA,
                            SHA,
                            PullRequestState.OPEN,
                            false,
                            null,
                            null,
                            null,
                            viewer);
                }

                @Override
                public ForgePages conversation(
                        final PullRequestRef ref, final ForgeCredential credential) {
                    throw new UnsupportedOperationException("an account reads no conversation");
                }

                @Override
                public WriteOutcome post(
                        final PullRequestRef ref,
                        final byte[] document,
                        final ForgeCredential credential) {
                    throw new UnsupportedOperationException("an account writes nothing");
                }
            };

    private final ForgeAccounts accounts =
            new ForgeAccounts(
                    forge::facts, entry -> new ForgeCredential(entry, "token-of-" + entry));

    @Test
    void theAccountIsTheViewerOfThePullRequestsFactsReadWithTheEntrysToken() throws Exception {
        assertThat(accounts.of(REF, ENTRY))
                .as("the account the forge says the token stands for")
                .isEqualTo(new ForgeAccount("github.com", "583231", "octocat"));
        assertThat(asked)
                .as("the place's pull request, read with that entry's token")
                .containsExactly("https://github.com/uc/shop/pull/7 with " + ENTRY);
    }

    @Test
    void aForgeThatDoesNotSayWhichAccountIsUnreachable() {
        viewer = null;

        assertThatThrownBy(() -> accounts.of(REF, ENTRY))
                .as("no account is never guessed")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the contract's reason")
                                        .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE));
    }

    @Test
    void anAccountOfAnotherForgeIsUnreachable() {
        viewer = new ForgeAccount("gitlab.com", "583231", "octocat");

        assertThatThrownBy(() -> accounts.of(REF, ENTRY))
                .as("an account the pull request's forge did not answer is none of its own")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the contract's reason")
                                        .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE));
    }

    @Test
    void aTokenTheVaultCannotLendIsPassedOnAsItsRefusal() {
        final ForgeAccounts lacking =
                new ForgeAccounts(
                        forge::facts,
                        entry -> {
                            throw new PlaceUnreadable(
                                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN, entry);
                        });

        assertThatThrownBy(() -> lacking.of(REF, ENTRY))
                .as("the vault's refusal, never a read without a token")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the refusal's reason")
                                        .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN));
        assertThat(asked).as("the forge is never asked").isEmpty();
    }
}
