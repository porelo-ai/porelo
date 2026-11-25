package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;

/**
 * Names the forge account an entry's token stands for, as the forge adapter answers it with a pull
 * request's facts read with that token.
 *
 * <p>What a task's items go out as must be the account the forge sees, so it is never taken from a
 * name a file holds: a forge that does not say, or names an account of another forge, leaves the
 * place unreadable.
 */
public final class ForgeAccounts implements TaskReviewRole.Accounts {

    private final Facts forge;

    private final Credentials credentials;

    /** Reads a pull request's facts, as {@link ForgeReview#facts} does. */
    @FunctionalInterface
    public interface Facts {

        /**
         * Reads a pull request's facts with a lent token.
         *
         * @param pullRequest The pull request.
         * @param credential The token lent for this read.
         * @return Its facts now.
         * @throws PlaceUnreadable When its forge cannot be asked or refuses.
         */
        PullRequestFacts facts(PullRequestRef pullRequest, ForgeCredential credential)
                throws PlaceUnreadable;
    }

    /** Lends an entry's token for one read. */
    @FunctionalInterface
    public interface Credentials {

        /**
         * Lends the token an entry holds.
         *
         * @param entry The vault entry.
         * @return The credential.
         * @throws PlaceUnreadable When the vault holds no such entry or cannot be read.
         */
        ForgeCredential lend(String entry) throws PlaceUnreadable;
    }

    /**
     * Constructor.
     *
     * @param forge What reads a pull request's facts from its forge, such as {@link
     *     ForgeReview#facts}.
     * @param credentials What lends an entry's token.
     */
    public ForgeAccounts(final Facts forge, final Credentials credentials) {
        this.forge = Objects.requireNonNull(forge, "forge");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public ForgeAccount of(final PullRequestRef pullRequest, final String entry)
            throws PlaceUnreadable {
        final PullRequestFacts facts = forge.facts(pullRequest, credentials.lend(entry));
        final ForgeAccount viewer = facts.viewer();
        if (viewer == null) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the forge did not say which account the token of " + entry + " stands for");
        }
        if (!viewer.forge().equals(pullRequest.forge())) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the account answered for the token of "
                            + entry
                            + " is not at "
                            + pullRequest.forge());
        }
        return viewer;
    }
}
