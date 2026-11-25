package org.fuin.porelo.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestPinning;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.Role;

/**
 * Finds a pull request's place by its address: one {@link PullRequestPlace} for every pull request
 * at a forge instance this machine knows and reads, its adapter installed. Nothing is kept: each
 * question reads the machine's forge file again, so a changed comment setting holds from the next
 * run on.
 */
public final class PullRequestPlaces implements ReviewPlaces {

    private final ForgeInstances forges;
    private final Predicate<String> installed;
    private final Function<String, ForgeReview> adapters;
    private final Function<ForgeReview, PullRequestPinning> pinnings;
    private final ProfileReading reading;
    private final CommentCheck check;
    private final ProfileWriting writing;
    private final ReleasedSender.Credentials credentials;
    private final Function<String, Role> roles;
    private final PullRequestPlace.AuthorPins authors;

    /**
     * Constructor.
     *
     * @param forges The forge instances this machine knows.
     * @param installed Whether a forge adapter of a name is installed.
     * @param adapters A forge adapter's review calls, by its name.
     * @param pinnings Fetches and pins a pull request's head into a reviewer's gate, through a
     *     forge adapter's review calls.
     * @param reading The one input path for profile documents.
     * @param check The check of each comment against the pinned push.
     * @param writing The one writer of profile 2.
     * @param credentials Lends a forge's read credential for a pin's facts.
     * @param roles A task's role, as its run recorded it.
     * @param authors Pins an author at its own pull request.
     */
    public PullRequestPlaces(
            final ForgeInstances forges,
            final Predicate<String> installed,
            final Function<String, ForgeReview> adapters,
            final Function<ForgeReview, PullRequestPinning> pinnings,
            final ProfileReading reading,
            final CommentCheck check,
            final ProfileWriting writing,
            final ReleasedSender.Credentials credentials,
            final Function<String, Role> roles,
            final PullRequestPlace.AuthorPins authors) {
        this.forges = Objects.requireNonNull(forges, "forges");
        this.installed = Objects.requireNonNull(installed, "installed");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.pinnings = Objects.requireNonNull(pinnings, "pinnings");
        this.reading = Objects.requireNonNull(reading, "reading");
        this.check = Objects.requireNonNull(check, "check");
        this.writing = Objects.requireNonNull(writing, "writing");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.authors = Objects.requireNonNull(authors, "authors");
    }

    @Override
    public Optional<ReviewPlace> named(final String name) {
        final PullRequestRef ref;
        try {
            ref = PullRequestRef.of(new URI(name));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
        try {
            return Optional.of(place(ref));
        } catch (PlaceUnreadable ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns a pull request's place, or why there is none.
     *
     * @param ref The pull request.
     * @return Its place.
     * @throws PlaceUnreadable {@code UnknownForge} when the machine knows no forge at its host or
     *     cannot read its entry, {@code ForgeFileUnreadable} when the forge file cannot be read,
     *     {@code ForgeAdapterMissing} when the entry's adapter is not installed.
     */
    public PullRequestPlace place(final PullRequestRef ref) throws PlaceUnreadable {
        final Optional<ForgeInstance> found;
        try {
            found = forges.of(ref.url());
        } catch (ForgeFileUnreadable ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                    "this machine's forge file cannot be read: " + ex.getMessage());
        }
        if (found.isEmpty() || found.get().unreadable() != null) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNKNOWN_FORGE,
                    "this machine knows no readable forge at " + ref.forge());
        }
        final ForgeInstance instance = found.get();
        final String adapter = Objects.requireNonNull(instance.adapter(), "a read entry's adapter");
        if (!installed.test(adapter)) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING,
                    "the forge adapter " + adapter + " is not installed");
        }
        final ForgeReview forge = adapters.apply(adapter);
        return new PullRequestPlace(
                ref,
                instance,
                pinnings.apply(forge),
                forge,
                reading,
                check,
                writing,
                credentials,
                roles,
                authors);
    }
}
