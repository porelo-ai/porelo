package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.CardException;
import org.jspecify.annotations.Nullable;

/**
 * An organization of the new structure, read from the repository its settings name. With a state
 * directory it keeps a trust record, so that a later read goes on from what an earlier one
 * accepted, and refuses a tip that does not continue it: an older state served again, or a fork.
 */
public final class GitTreeStore {

    /** The repository could not be reached: the one failure {@link #last} is an answer to. */
    public static class Unreachable extends IOException {

        private static final long serialVersionUID = 1L;

        /** Why it was not reached, as a named value a person's display renders. */
        public enum Cause {
            /** Ssh did not trust the forge's host key. */
            HOST_KEY("host-key"),
            /** The forge refused the key or the credential offered. */
            KEY_REFUSED("key-refused"),
            /** The forge's name was not resolved, or no connection came about. */
            NO_NETWORK("no-network"),
            /** Git did not end in time. */
            TIMEOUT("timeout"),
            /** Anything else. */
            UNREACHABLE("unreachable");

            private final String word;

            Cause(final String word) {
                this.word = word;
            }

            /**
             * Returns its name in an answer.
             *
             * @return The word.
             */
            public String word() {
                return word;
            }
        }

        private final Cause cause;

        private final @Nullable String said;

        /**
         * Creates one with no word of git's.
         *
         * @param message What a person reads.
         */
        public Unreachable(final String message) {
            this(message, Cause.UNREACHABLE, null);
        }

        /**
         * Creates one with its cause and git's own lines.
         *
         * @param message What a person reads.
         * @param cause Why.
         * @param said Git's own lines, cleaned, or {@code null}.
         */
        public Unreachable(final String message, final Cause cause, final @Nullable String said) {
            super(message);
            this.cause = cause;
            this.said = said;
        }

        /**
         * Creates one from what git wrote on its standard error, keeping only git's own lines.
         *
         * @param lead What a person reads before git's words.
         * @param standardError What git wrote.
         * @return It.
         */
        public static Unreachable fromGit(final String lead, final String standardError) {
            final String own =
                    org.fuin.porelo.vault.RegistryText.printable(
                            TreeRepository.gitsOwnWords(standardError).replace("\n", "; "));
            if (own.isEmpty()) {
                return new Unreachable(lead);
            }
            final String said = own.length() > SAID ? own.substring(0, SAID) : own;
            return new Unreachable(lead + ": " + said, causeOf(said), said);
        }

        private static final int SAID = 400;

        private static Cause causeOf(final String said) {
            if (said.contains("Host key verification failed")
                    || said.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")) {
                return Cause.HOST_KEY;
            }
            if (said.contains("Permission denied")
                    || said.contains("Authentication failed")
                    || said.contains("could not read Username")
                    || said.contains("The requested URL returned error: 401")
                    || said.contains("The requested URL returned error: 403")) {
                return Cause.KEY_REFUSED;
            }
            if (said.contains("Could not resolve host")
                    || said.contains("Network is unreachable")
                    || said.contains("No route to host")
                    || said.contains("Connection refused")
                    || said.contains("Connection timed out")
                    || said.contains("Failed to connect")) {
                return Cause.NO_NETWORK;
            }
            return Cause.UNREACHABLE;
        }

        /**
         * Returns why.
         *
         * @return The cause.
         */
        public Cause cause() {
            return cause;
        }

        /**
         * Returns git's own lines, never the forge's.
         *
         * @return Them, or {@code null} when there are none.
         */
        public @Nullable String said() {
            return said;
        }
    }

    /** The repository was reached and did not take a push, or the answer that it did was lost. */
    public static final class NotTaken extends Unreachable {

        private static final long serialVersionUID = 1L;

        NotTaken() {
            super(
                    "the organization's repository did not take the change: see whether this person"
                            + " may push there. It may have been made all the same");
        }
    }

    /**
     * The repository was reached and holds nothing yet: a person may still prepare a first card for
     * it. Anyone with no use for that goes on treating it as unreachable, since nothing can be
     * read.
     */
    public static final class Empty extends Unreachable {

        private static final long serialVersionUID = 1L;

        Empty(final String message) {
            super(message);
        }
    }

    private static final String RECORD = "trust-record.json";

    private static final Set<PosixFilePermission> NOT_OWNERS =
            EnumSet.complementOf(
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));

    private final CommandRunner runner;

    private final String repository;

    private final String organisation;

    private final FetchAccess access;

    private final @Nullable Path state;

    private final OrganizationQuestions.Description description;

    GitTreeStore(
            final CommandRunner runner,
            final String repository,
            final String organisation,
            final String forge,
            final FetchAccess access,
            final @Nullable Path state) {
        this.runner = runner;
        this.repository = repository;
        this.organisation = organisation;
        this.access = access;
        this.state = state;
        final String vouchedBy = "github.com".equals(forge) ? "GitHub" : forge;
        this.description =
                new OrganizationQuestions.Description(
                        organisation,
                        "the " + vouchedBy + " organization " + organisation,
                        vouchedBy);
    }

    private static GitTreeStore opened(
            final CommandRunner runner,
            final String repository,
            final FetchAccess access,
            final @Nullable Path state) {
        final ForgeAddress origin = ForgeAddress.of(repository);
        return new GitTreeStore(
                runner, repository, origin.organization(), origin.forge().getHost(), access, state);
    }

    /**
     * Opens a store at an address that names no forge, such as a directory of a test organization,
     * saying itself what the address would say.
     *
     * @param runner How git runs.
     * @param repository The repository's address.
     * @param organisation The organisation it belongs to.
     * @param forge The forge's host name.
     * @param access What a fetch may use.
     * @param state The directory the record is kept in.
     * @return The store.
     */
    public static GitTreeStore at(
            final CommandRunner runner,
            final String repository,
            final String organisation,
            final String forge,
            final FetchAccess access,
            final Path state) {
        return new GitTreeStore(runner, repository, organisation, forge, access, state);
    }

    /**
     * Opens a store that keeps nothing. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param access What a fetch may use.
     * @return The store.
     * @throws IllegalArgumentException When the address names no forge and organisation.
     */
    public static GitTreeStore open(
            final CommandRunner runner, final String repository, final FetchAccess access) {
        return opened(runner, repository, access, null);
    }

    /**
     * Opens a store that keeps its trust record. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param access What a fetch may use.
     * @param state The directory the record is kept in, made owner-only when first written.
     * @return The store.
     * @throws IllegalArgumentException When the address names no forge and organisation.
     */
    public static GitTreeStore open(
            final CommandRunner runner,
            final String repository,
            final FetchAccess access,
            final Path state) {
        return opened(runner, repository, access, state);
    }

    /**
     * Fetches the repository and walks its history to the branch's tip.
     *
     * @param identities Who says whose keys are whose.
     * @return The answers, at the commit the walk reached.
     * @throws Unreachable When it cannot be fetched.
     * @throws IOException When it cannot be read, or the record cannot be kept.
     * @throws OrganizationException When its history proves nothing.
     * @throws OrganizationQuestions.Refused When it does not continue the record, or no owner can
     *     be proven since the founder's key is no longer listed. A single change nobody allowed is
     *     not thrown: it is among the answers' refusals.
     */
    public OrganizationQuestions read(final IdentitySource identities) throws IOException {
        return walk(identities, null);
    }

    /** Whether a display refresh still belongs to the configured organization. */
    @FunctionalInterface
    public interface Current {
        /**
         * Checks that the requesting display's organization is still configured.
         *
         * @return Whether it is still current.
         * @throws IOException When the settings cannot be checked.
         */
        boolean check() throws IOException;
    }

    /**
     * Refreshes display state only while its configuration is current, checked under the record's
     * transaction lock and again before keeping anything. An incomplete walk leaves the kept
     * snapshot unchanged. Decisions always use {@link #read}.
     *
     * @param identities Who says whose keys are whose.
     * @param current Whether the requesting display still belongs to this organization.
     * @return A fresh answer, or empty for an obsolete request.
     * @throws IOException When the source or the settings cannot be read.
     */
    public Optional<OrganizationQuestions> refresh(IdentitySource identities, Current current)
            throws IOException {
        final TreeReadLock.Read<Optional<OrganizationQuestions>> refreshing =
                () -> {
                    if (!current.check()) return Optional.empty();
                    final OrganizationQuestions answer =
                            walkLocked(
                                    identities,
                                    null,
                                    () -> TreeRepository.fetch(repository, runner, access),
                                    current,
                                    false);
                    return current.check() ? Optional.of(answer) : Optional.empty();
                };
        return state == null ? refreshing.run() : TreeReadLock.holding(state, refreshing);
    }

    /**
     * Fetches the repository and walks its history to a revision, so that several answers rest on
     * one state. It keeps nothing.
     *
     * @param identities Who says whose keys are whose.
     * @param revision The revision, one an earlier answer named.
     * @return The answers, at that revision.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing, does not continue the record,
     *     or the revision is not on its line or could not be reached.
     */
    public OrganizationQuestions read(final IdentitySource identities, final String revision)
            throws IOException {
        return walk(identities, revision);
    }

    /**
     * Reads the organization from the checkout a person keeps of its repository, at the checkout's
     * {@code main}, fetching nothing: walked as a fetched one is, against the record last trusted,
     * which it keeps as a read does. The checkout's own settings and hooks have no say, and nothing
     * in it changes.
     *
     * @param checkout The checkout's top directory.
     * @param identities Who says whose keys are whose.
     * @return The answers, at the commit the walk reached.
     * @throws IOException When it cannot be read, or the record cannot be kept.
     * @throws OrganizationException When its history proves nothing.
     * @throws OrganizationQuestions.Refused When it is no checkout of this account, or it does not
     *     continue the record.
     */
    public OrganizationQuestions readCheckout(final Path checkout, final IdentitySource identities)
            throws IOException {
        return walk(
                identities,
                null,
                () -> TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner));
    }

    /** How the repository a walk reads is had. */
    @FunctionalInterface
    private interface Opening {
        TreeRepository open() throws IOException;
    }

    /**
     * Answers the last state kept, fetching nothing, for when the repository cannot be reached.
     * With no history at hand it cannot check the record against it, so it rests on the owner-only
     * file alone: <strong>no security check may rest on this answer.</strong>
     *
     * @return The answers, or empty when no usable record is kept.
     * @throws IOException When the record cannot be read.
     */
    public Optional<OrganizationQuestions> last() throws IOException {
        return lastChecked().map(Checked::answers);
    }

    /**
     * A locally validated kept value; neither field grants authority for a decision.
     *
     * @param answers What the record kept.
     * @param checkedAt Its complete check time, or null when unknown.
     */
    public record Checked(OrganizationQuestions answers, @Nullable Instant checkedAt) {}

    /**
     * Reads the checked kept value and its timestamp from the same file, without waiting for a
     * fetch.
     *
     * @return The value, or empty when the record fails its checks or is absent.
     * @throws IOException When it cannot be read.
     */
    public Optional<Checked> lastChecked() throws IOException {
        final List<String> warnings = new ArrayList<>();
        final TreeRecord kept = kept(warnings);
        return kept == null
                ? Optional.empty()
                : Optional.of(new Checked(answers(kept, warnings), kept.checkedAt()));
    }

    private OrganizationQuestions walk(
            final IdentitySource identities, final @Nullable String revision) throws IOException {
        return walk(identities, revision, () -> TreeRepository.fetch(repository, runner, access));
    }

    private OrganizationQuestions walk(
            final IdentitySource identities, final @Nullable String revision, final Opening opening)
            throws IOException {
        return state == null
                ? walkLocked(identities, revision, opening)
                : TreeReadLock.holding(state, () -> walkLocked(identities, revision, opening));
    }

    private OrganizationQuestions walkLocked(
            final IdentitySource identities, final @Nullable String revision, final Opening opening)
            throws IOException {
        return walkLocked(identities, revision, opening, () -> true, true);
    }

    private OrganizationQuestions walkLocked(
            final IdentitySource identities,
            final @Nullable String revision,
            final Opening opening,
            final Current current,
            final boolean keepIncomplete)
            throws IOException {
        final List<String> warnings = new ArrayList<>();
        final TreeRecord kept = kept(warnings);
        try (TreeRepository clone = opening.open()) {
            final List<String> line = clone.line(null).stream().map(Map.Entry::getKey).toList();
            final String commit = kept == null ? null : kept.commit();
            if (commit != null && !line.contains(commit)) {
                throw new OrganizationQuestions.Refused(
                        OrganizationQuestions.Refused.REFUSED_STATE,
                        "the organization's tip does not continue the commit last trusted: an older"
                                + " state served again, or a fork, at "
                                + commit,
                        commit);
            }
            final TreeWalk walk = new TreeWalk(clone, identities, organisation);
            walk.readChains();
            final TreeRecord start = start(walk, kept, line, revision, warnings);
            final TreeRecord walked = walk.read(revision, start);
            final boolean whole = revision != null || line.getLast().equals(walked.commit());
            if ((whole || keepIncomplete)
                    && revision == null
                    && keeps(line, commit, walked.commit())
                    && (whole || !java.util.Objects.equals(commit, walked.commit()))
                    && current.check()) {
                keep(walked, whole);
            }
            return answers(walked, warnings, whole);
        } catch (CommandException ex) {
            // Git did not end in time: a forge that hangs says nothing of the state.
            throw new Unreachable(
                    "the organization's repository did not answer in time",
                    Unreachable.Cause.TIMEOUT,
                    null);
        } catch (CardException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }

    /**
     * Fetches the repository and answers it for one host, against its pin and the last state taken.
     *
     * @param identities Who says whose keys are whose: the forge's public list, here.
     * @param host The host's name.
     * @param pin The fingerprints of the keys it holds for its admins.
     * @param since The revision taken last, or "" before the first.
     * @return The answers, at the commit the walk reached.
     * @throws Unreachable When it cannot be fetched.
     * @throws IOException When it cannot be read, or the record cannot be kept.
     * @throws OrganizationQuestions.Refused When it refuses: an older state, a commit not signed
     *     for the pin, or a walk that cannot reach what was taken.
     */
    OrganizationQuestions fetchFor(
            final IdentitySource identities,
            final String host,
            final Set<String> pin,
            final String since)
            throws IOException {
        return state == null
                ? fetchForLocked(identities, host, pin, since)
                : TreeReadLock.holding(state, () -> fetchForLocked(identities, host, pin, since));
    }

    private OrganizationQuestions fetchForLocked(
            final IdentitySource identities,
            final String host,
            final Set<String> pin,
            final String since)
            throws IOException {
        final List<String> warnings = new ArrayList<>();
        final TreeRecord kept = kept(warnings);
        try (TreeRepository clone = TreeRepository.fetch(repository, runner, access)) {
            final List<String> line = clone.line(null).stream().map(Map.Entry::getKey).toList();
            if (!since.isEmpty() && !line.contains(since)) {
                throw new OrganizationQuestions.Refused(
                        OrganizationQuestions.Refused.REFUSED_STATE,
                        "the organization's tip does not continue the commit last taken: an older"
                                + " state served again, or a fork, at "
                                + since,
                        since);
            }
            if (kept != null && kept.commit() != null && !line.contains(kept.commit())) {
                throw new OrganizationQuestions.Refused(
                        OrganizationQuestions.Refused.REFUSED_STATE,
                        "the organization's tip does not continue the commit this host read last:"
                                + " an older state served again, or a fork, at "
                                + kept.commit(),
                        kept.commit());
            }
            final TreeWalk walk = new TreeWalk(clone, identities, organisation);
            walk.readChains();
            // Only from the commit taken: a later record may hold commits no pin has judged yet.
            final TreeRecord start =
                    kept != null
                                    && since.equals(kept.commit())
                                    && !walk.unsettled(kept)
                                    && !walk.missedTheList(kept)
                            ? kept
                            : null;
            final TreePin watch =
                    new TreePin(
                            host, pin, since, start == null ? null : start.model(), description);
            walk.underPin(watch);
            final TreeRecord walked = walk.read(null, start);
            watch.finish();
            if (state != null && walked.commit() != null) {
                keep(walked, line.getLast().equals(walked.commit()));
            }
            return answers(walked, warnings);
        } catch (CommandException ex) {
            // Git did not end in time: a forge that hangs says nothing of the state.
            throw new Unreachable(
                    "the organization's repository did not answer in time",
                    Unreachable.Cause.TIMEOUT,
                    null);
        } catch (CardException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }

    private OrganizationQuestions answers(final TreeRecord walked, final List<String> warnings) {
        return answers(walked, warnings, true);
    }

    private OrganizationQuestions answers(
            final TreeRecord walked, final List<String> warnings, final boolean whole) {
        return new GitTreeOrganization(
                walked.commit(), walked.model(), walked.refusals(), warnings, description, whole);
    }

    /** The record to go on from: none for a revision before it, or when a card it held changed. */
    private static @Nullable TreeRecord start(
            final TreeWalk walk,
            final @Nullable TreeRecord kept,
            final List<String> line,
            final @Nullable String revision,
            final List<String> warnings)
            throws IOException {
        if (kept == null) {
            return null;
        }
        if (revision != null
                && line.indexOf(revision) >= 0
                && line.indexOf(revision) < line.indexOf(kept.commit())) {
            return null;
        }
        if (walk.missedTheList(kept)) {
            warnings.add(
                    "this machine's record is from before the organization's list of admins;"
                            + " the organization was read afresh from its first commit");
            return null;
        }
        if (walk.unsettled(kept)) {
            warnings.add(
                    "the identity source now says otherwise of a card it could not settle;"
                            + " the organization was read afresh from its first commit");
            return null;
        }
        return kept;
    }

    /** A record never goes back: a fresh walk the source stopped early leaves the later one. */
    private boolean keeps(
            final List<String> line, final @Nullable String kept, final @Nullable String reached) {
        return state != null
                && reached != null
                && (kept == null || line.indexOf(reached) >= line.indexOf(kept));
    }

    /**
     * Reads the record kept. One that is no owner-only regular file, or does not read whole, or
     * speaks for another address is discarded, said in a warning: slower, never weaker.
     */
    private @Nullable TreeRecord kept(final List<String> warnings) throws IOException {
        if (state == null) {
            return null;
        }
        final Path file = state.resolve(RECORD);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Collections.disjoint(
                            Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS),
                            NOT_OWNERS)) {
                throw new CardException("the trust record is no owner-only regular file");
            }
            return TreeRecord.read(Files.readString(file, StandardCharsets.UTF_8), repository);
        } catch (CardException | MalformedInputException ex) {
            warnings.add(
                    "the trust record was discarded and the organization read afresh from its"
                            + " first commit: "
                            + (ex instanceof CardException
                                    ? ex.getMessage()
                                    : "it is not UTF-8 text"));
            return null;
        }
    }

    /** Writes the record owner-only and whole: a reader never finds half of one. */
    private void keep(final TreeRecord record, final boolean whole) throws IOException {
        final Path directory = java.util.Objects.requireNonNull(state, "a store that keeps");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path staged =
                Files.createTempFile(
                        directory,
                        ".",
                        ".json.staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(
                    staged,
                    new TreeRecord(
                                    record.commit(),
                                    record.model(),
                                    record.refusals(),
                                    whole ? Instant.now() : null)
                            .toJson(repository),
                    StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    directory.resolve(RECORD),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
