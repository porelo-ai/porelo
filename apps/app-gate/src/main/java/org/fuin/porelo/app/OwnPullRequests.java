package org.fuin.porelo.app;

import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.build.api.BuildReaderException;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.build.api.InstalledReviewReader;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Directories;

/**
 * Finds an author's own pull request: the one open from its branch, asked of the forge's adapter at
 * each start with the forge's read credential lent for that call alone. Nothing of it is kept.
 */
public final class OwnPullRequests {

    private final Adapter adapter;

    private final Credentials credentials;

    /**
     * Constructor.
     *
     * @param adapter Asks a forge's adapter for the pull request open from a branch.
     * @param credentials Lends a vault entry's token for one call.
     */
    public OwnPullRequests(final Adapter adapter, final Credentials credentials) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Returns this machine's: its installed adapters and its vault.
     *
     * @param context The machine.
     * @return Them.
     */
    public static OwnPullRequests of(final PoreloContext context) {
        return new OwnPullRequests(Adapter.of(context), Credentials.of(context));
    }

    /**
     * Asks a forge adapter for the pull request open from a branch.
     *
     * <p>A stand-in answers fixed addresses.
     */
    @FunctionalInterface
    public interface Adapter {

        /**
         * Asks for the open pull request from a branch; none is opened.
         *
         * @param name The adapter's name, as the forge's entry names it.
         * @param credential The forge's read credential, lent for this call.
         * @param upstream The repository, as the project file names it.
         * @param branch The branch.
         * @return Its address as the adapter answered it, or empty when none is open.
         * @throws PlaceUnreadable When the adapter or the forge does not answer.
         */
        Optional<String> pullRequestOf(
                String name, ForgeCredential credential, String upstream, String branch)
                throws PlaceUnreadable;

        /**
         * Returns the adapters installed on this machine, each started for one question.
         *
         * @param context The machine.
         * @return Them.
         */
        static Adapter of(final PoreloContext context) {
            return (name, credential, upstream, branch) -> {
                final Path executable;
                try {
                    executable =
                            InstalledBuildReader.find(
                                    name,
                                    List.of(
                                            context.paths().xdg().data().resolve("builds"),
                                            PoreloPaths.packaged(InstalledBuildReader.PACKAGED)));
                } catch (BuildReaderException ex) {
                    throw new PlaceUnreadable(
                            PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING,
                            "the forge adapter " + name + " is not installed");
                }
                final Path directory = directory(context);
                try (InstalledReviewReader reader =
                        new InstalledReviewReader(
                                executable,
                                name,
                                directory.resolve("review.sock"),
                                context.limits())) {
                    return Optional.ofNullable(
                            reader.pullRequestOf(
                                    new ForgeAccess("", credential.token()), upstream, branch));
                } catch (ForgeReviewRefused ex) {
                    throw ex.reason() == ForgeReviewRefused.Reason.CREDENTIAL_REFUSED
                            ? new PlaceUnreadable(
                                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                                    "the forge refused the token '" + credential.entry() + "'")
                            : new PlaceUnreadable(
                                    PlaceUnreadable.Reason.UNREACHABLE,
                                    "the forge did not say which pull request is open from "
                                            + branch
                                            + " ("
                                            + ex.reason().wire()
                                            + ")");
                } catch (BuildReaderException ex) {
                    // Not its message: an adapter's text may hold what it was lent.
                    throw new PlaceUnreadable(
                            PlaceUnreadable.Reason.UNREACHABLE,
                            "the forge adapter " + name + " failed to answer");
                } finally {
                    remove(directory);
                }
            };
        }

        /** An owner-only directory of its own for one adapter's socket. */
        private static Path directory(final PoreloContext context) throws PlaceUnreadable {
            try {
                final Path root = context.paths().tasks().buildQueries();
                Directories.create(
                        root,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
                return Files.createTempDirectory(
                        root,
                        "review-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            } catch (IOException ex) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.UNREACHABLE,
                        "no place for the forge adapter's socket: " + ex.getMessage());
            }
        }

        private static void remove(final Path directory) {
            try (var left = Files.list(directory)) {
                for (final Path each : (Iterable<Path>) left::iterator) {
                    Files.deleteIfExists(each);
                }
                Files.deleteIfExists(directory);
            } catch (IOException ex) {
                // Left for the next query's directory beside it; it holds no secret.
            }
        }
    }

    /**
     * Lends a vault entry's token for one call.
     *
     * <p>A stand-in answers a fixed credential.
     */
    @FunctionalInterface
    public interface Credentials {

        /**
         * Lends the token an entry holds.
         *
         * @param entry The entry's name.
         * @return The credential, to be used for one call and dropped.
         * @throws PlaceUnreadable When the vault is shut or does not hold it.
         */
        ForgeCredential lend(String entry) throws PlaceUnreadable;

        /**
         * Returns this machine's vault.
         *
         * @param context The machine.
         * @return It.
         */
        static Credentials of(final PoreloContext context) {
            return entry -> {
                final Optional<Map<String, VaultEntry>> held = context.readableCredentials();
                final VaultEntry found = held.map(all -> all.get(entry)).orElse(null);
                if (found == null || found.value().isBlank()) {
                    throw new PlaceUnreadable(
                            PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                            held.isEmpty()
                                    ? "the vault is shut, so '" + entry + "' cannot be read"
                                    : "the vault holds no '" + entry + "'");
                }
                return new ForgeCredential(entry, found.value());
            };
        }
    }

    /** No pull request is open from the branch. */
    public static final class NoneOpen extends Exception {

        @Serial private static final long serialVersionUID = 1L;

        private final String branch;

        /**
         * Constructor.
         *
         * @param branch The branch.
         */
        public NoneOpen(final String branch) {
            super("no pull request is open from " + branch, null, false, false);
            this.branch = branch;
        }

        /**
         * Returns the branch.
         *
         * @return Its name.
         */
        public String branch() {
            return branch;
        }
    }

    /**
     * Returns the pull request open from a branch at a forge.
     *
     * @param forge The forge the upstream is at.
     * @param upstream The repository, as the project file names it.
     * @param branch The branch.
     * @return The pull request.
     * @throws PlaceUnreadable When no adapter reads the forge, the vault lends no token, or the
     *     adapter answers no pull request of this forge.
     * @throws NoneOpen When none is open from the branch.
     */
    public PullRequestRef of(final ForgeInstance forge, final String upstream, final String branch)
            throws PlaceUnreadable, NoneOpen {
        final String name = forge.adapter();
        if (name == null) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING,
                    "this machine's entry of " + forge.host() + " names no adapter it reads");
        }
        final ForgeCredential credential = credentials.lend(forge.credential());
        final Optional<String> url = adapter.pullRequestOf(name, credential, upstream, branch);
        if (url.isEmpty()) {
            throw new NoneOpen(branch);
        }
        final PullRequestRef ref;
        try {
            ref = PullRequestRef.of(new URI(url.get()));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the forge adapter " + name + " answered no pull request's address");
        }
        if (!ref.forge().toLowerCase(Locale.ROOT).equals(forge.host())) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the forge adapter " + name + " answered a pull request at another forge");
        }
        return ref;
    }
}
