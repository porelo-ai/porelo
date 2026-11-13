package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * A project's {@code project.yml}, kept in its own repository: fetched, checked against the keys of
 * its admins, and changed by a signed commit. A project's own repository may hold other files too,
 * by other signers, so only the newest commit that changed this file is judged.
 */
public final class ProjectFile {

    /** It was read. */
    public static final String READ = "read";

    /** The repository could not be reached or could not say. */
    public static final String CANNOT_SAY = "cannot-say";

    /** It is there and is not to be believed. */
    public static final String REFUSED = "refused";

    private static final String PATH = "project.yml";

    private static final String ADMIN = "admin";

    private static final String REPOSITORY_PATH = "porelo.yml";

    private static final Set<String> REPOSITORY_ROLES = Set.of(ADMIN, "developer");

    /** What a signing adds, as only a commit that changes the file is judged. */

    /**
     * What a read found.
     *
     * @param state {@link #READ}, {@link #CANNOT_SAY} or {@link #REFUSED}.
     * @param revision The project repository's tip, or {@code null} unless read.
     * @param project The file, or {@code null} unless read.
     * @param why Why it is refused, or {@code null}.
     * @param signer The key the newest change is signed by, or {@code null} when it has none that
     *     verifies.
     * @param renames The file's keys still under an old name, read alike; none unless read.
     */
    public record Read(
            String state,
            @Nullable String revision,
            @Nullable Project project,
            @Nullable String why,
            @Nullable String signer,
            List<String> renames) {

        /**
         * Constructor.
         *
         * @param state What the read found.
         * @param revision The tip read.
         * @param project The file.
         * @param why Why it is refused.
         * @param signer The key of the newest change.
         * @param renames The keys under an old name.
         */
        public Read {
            renames = renames == null ? List.of() : List.copyOf(renames);
        }

        /** What a read found, with nothing to rename. */
        public Read(
                final String state,
                final @Nullable String revision,
                final @Nullable Project project,
                final @Nullable String why,
                final @Nullable String signer) {
            this(state, revision, project, why, signer, List.of());
        }

        /** What a read found, with no signer to name. */
        public Read(
                final String state,
                final @Nullable String revision,
                final @Nullable Project project,
                final @Nullable String why) {
            this(state, revision, project, why, null);
        }
    }

    /**
     * What an admin reads before signing the file anew.
     *
     * @param read What a read of it found.
     * @param revision The project repository's tip, which a signing names, or {@code null} when it
     *     could not be reached.
     * @param text The file as it stands, or {@code null} when there is none that can be shown.
     * @param blocked What a signature would not change, or {@code null} when one would do.
     */
    public record Look(
            Read read,
            @Nullable String revision,
            @Nullable String text,
            @Nullable String blocked) {}

    /** A repository file and the newest change whose signature takes responsibility for it. */
    public record RepositoryLook(Look look, @Nullable String change) {}

    private final CommandRunner runner;

    private final FetchAccess access;

    private final CommitSigning signing;

    private final Path memory;

    /**
     * Constructor.
     *
     * @param runner How git runs.
     * @param access What a fetch and a push may use.
     * @param signing Whose agent signs a change; a read signs nothing.
     * @param memory The directory of this machine's state that keeps, per repository, the date of
     *     the newest change it accepted.
     */
    public ProjectFile(
            final CommandRunner runner,
            final FetchAccess access,
            final CommitSigning signing,
            final Path memory) {
        this.runner = runner;
        this.access = access;
        this.signing = signing;
        this.memory = memory;
    }

    /**
     * Reads the file.
     *
     * @param address The project repository.
     * @param keys The project's signing keys, as its organization answers them.
     * @return What was found; a failure to reach the repository is never read as an empty project.
     * @throws IOException When something unexpected happened.
     */
    public Read read(final String address, final List<SigningKey> keys) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            return judged(clone, keys, address);
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    /**
     * Reads the file in a repository already on this machine, with no network and no memory: the
     * caller holds the commit it took last and the keys in force.
     *
     * @param runner How git runs.
     * @param repository The git directory, a bare copy another account may own.
     * @param keys The project's signing keys.
     * @return What was found.
     * @throws IOException When something unexpected happened.
     */
    public static Read standing(
            final CommandRunner runner, final Path repository, final List<SigningKey> keys)
            throws IOException {
        try (TreeRepository clone = TreeRepository.standing(repository, runner)) {
            return judged(clone, keys, null, "");
        } catch (CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    private Read judged(
            final TreeRepository clone, final List<SigningKey> keys, final String address)
            throws IOException {
        return judged(clone, keys, this, address);
    }

    private static Read judged(
            final TreeRepository clone,
            final List<SigningKey> keys,
            final @Nullable ProjectFile guard,
            final String address)
            throws IOException {
        return judged(clone, keys, guard, address, null);
    }

    /**
     * Judges the file; with a name, a file naming another project is refused before this machine
     * remembers anything of it, since the keys it was judged with are that name's.
     */
    private static Read judged(
            final TreeRepository clone,
            final List<SigningKey> keys,
            final @Nullable ProjectFile guard,
            final String address,
            final @Nullable String names)
            throws IOException {
        try {
            final String tip = clone.tip();
            final Optional<String> newest = newestChange(clone, PATH);
            if (newest.isEmpty()) {
                return refused("the project repository holds no project.yml");
            }
            final String commit = newest.orElseThrow();
            final String signer = clone.signer(commit);
            if (!anAdminsKey(signer, keys, clone.dated(commit))) {
                return refused(
                        "the project.yml of "
                                + commit
                                + " is signed by "
                                + PersonCard.fingerprint(signer)
                                + ", which is no key of an admin of this project now"
                                + wayOut(clone, tip),
                        signer);
            }
            final Optional<String> blob = clone.object(tip, PATH);
            if (blob.isEmpty()) {
                return refused("the project repository holds no project.yml");
            }
            final Instant dated = OffsetDateTime.parse(clone.dated(commit)).toInstant();
            final Instant accepted = guard == null ? null : guard.accepted(address);
            if (accepted != null && dated.isBefore(accepted)) {
                return older(clone, tip, commit, accepted, signer);
            }
            final String text = clone.text(blob.orElseThrow());
            final Project project = parsed(text, inClone(clone, tip));
            if (names != null && !names.equals(project.name())) {
                return refused(
                        "the checkout's project.yml names the project "
                                + project.name()
                                + ", not "
                                + names,
                        signer);
            }
            final Read read =
                    new Read(
                            READ,
                            tip,
                            project,
                            null,
                            signer,
                            org.fuin.porelo.core.project.file.OldNames.of(text));
            if (guard != null) {
                // Judged again where it is written: another read may have accepted a newer file
                // since the check above.
                final Instant newer = guard.held(address, dated);
                if (newer != null) {
                    return older(clone, tip, commit, newer, signer);
                }
            }
            return read;
        } catch (CardException ex) {
            return refused(String.valueOf(ex.getMessage()));
        } catch (ProjectException ex) {
            // Past the signature only: an admin of the project wrote what this quotes, and a file
            // that is no YAML is named by its line alone. Without it a person cannot tell what to
            // change.
            return refused(
                    "the project repository's project.yml does not read: "
                            + RegistryText.printable(String.valueOf(ex.getMessage())));
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, which anyone with write access wrote.
            return refused("the project repository's project.yml does not read");
        }
    }

    private static Read older(
            final TreeRepository clone,
            final String tip,
            final String commit,
            final Instant accepted,
            final String signer) {
        return refused(
                "the project.yml of "
                        + commit
                        + " is older than the one this machine accepted, from "
                        + accepted
                        + wayOut(clone, tip),
                signer);
    }

    /**
     * Returns the date last accepted. A record that does not read is no record: the guard only ever
     * refuses, so losing it costs what a new machine has too.
     */
    private @Nullable Instant accepted(final String address) throws IOException {
        return accepted(memory, address);
    }

    /**
     * Returns the date last accepted for a repository, under any key it may be kept under.
     *
     * @param memory The directory of the dates accepted.
     * @param address The repository's address, in any form.
     * @return The latest date, or {@code null} for none.
     * @throws IOException When a record cannot be read.
     */
    static @Nullable Instant accepted(final Path memory, final String address) throws IOException {
        // Kept under the address as it was given before one form was kept: the latest of them
        // holds.
        @Nullable Instant latest = null;
        for (final String key : keys(address)) {
            final Instant each = accepted(memory.resolve(TreeRepository.digest(key)));
            if (each != null && (latest == null || each.isAfter(latest))) {
                latest = each;
            }
        }
        return latest;
    }

    /**
     * Returns every key a floor for the address may be kept under: its one form, and the keys an
     * older Porelo kept it under. Those were the address as given, the one form before a path's
     * case was folded and ports were told apart, and, for an ssh address, the https address it was
     * made from as filed: the ssh address carries that path as it was, the host in the
     * organization's spelling.
     */
    private static java.util.Set<String> keys(final String address) {
        final java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(oneForm(address));
        keys.add(address);
        keys.add(earlierForm(address));
        final java.util.regex.Matcher scp = EARLIER_SCP.matcher(address);
        final java.util.regex.Matcher ssh = EARLIER_SSH.matcher(address);
        final java.util.regex.Matcher form = scp.matches() ? scp : ssh.matches() ? ssh : null;
        if (form != null) {
            final String path = form.group(2);
            String base = path;
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.endsWith(".git")) {
                base = base.substring(0, base.length() - ".git".length());
            }
            for (final String host : List.of(form.group(1), asciiLower(form.group(1)))) {
                for (final String port : List.of("", ":443")) {
                    for (final String filed :
                            List.of(path, base, base + "/", base + ".git", base + ".git/")) {
                        keys.add("https://" + host + port + "/" + filed);
                    }
                }
            }
        }
        return keys;
    }

    private static @Nullable Instant accepted(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Instant.parse(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static final java.util.regex.Pattern EARLIER_SCP =
            java.util.regex.Pattern.compile("git@([^:/@]+):(.+)");

    private static final java.util.regex.Pattern EARLIER_SSH =
            java.util.regex.Pattern.compile("ssh://git@([^:/@]+)(?::[0-9]+)?/(.+)");

    private static final java.util.regex.Pattern EARLIER_HTTPS =
            java.util.regex.Pattern.compile("https://([^:/@]+)(?::443)?/(.+)");

    /**
     * Returns the one form as Porelo kept it before this: the host in lower case, no trailing slash
     * and no trailing {@code .git}, the path's case kept and an ssh port dropped. Read only, so a
     * floor kept under it still holds.
     */
    private static String earlierForm(final String address) {
        for (final java.util.regex.Pattern each :
                List.of(EARLIER_SCP, EARLIER_SSH, EARLIER_HTTPS)) {
            final java.util.regex.Matcher form = each.matcher(address);
            if (form.matches()) {
                String trimmed = form.group(2);
                while (trimmed.endsWith("/")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                if (trimmed.endsWith(".git")) {
                    trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
                }
                return "https://"
                        + form.group(1).toLowerCase(java.util.Locale.ROOT)
                        + "/"
                        + trimmed;
            }
        }
        return address;
    }

    /** {@code scheme://[user@]host[:port]/path}, the scheme in any case. */
    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile(
                    "(?<scheme>[A-Za-z][A-Za-z0-9+.-]*)://(?:[^@/]*@)?(?<host>[^:/@]+)"
                            + "(?::(?<port>[0-9]+))?/(?<path>.+)");

    /** {@code user@host:path}, as git reads it over ssh. */
    private static final java.util.regex.Pattern SCP =
            java.util.regex.Pattern.compile("[^@/:]+@(?<host>[^:/@]+):(?<path>.+)");

    /**
     * Returns an address in the one form its floor is kept under. A forge answers to every spelling
     * of one repository: over https, http or ssh, as any user, with the scheme and host in any
     * case, the path in any case, with or without a trailing slash or {@code .git}, and on the
     * default port written or not. Those share the form {@code https://host/path}, host and path in
     * lower case. Another port is another server: it is kept in the form, so two ssh ports of one
     * host never share a floor. A host is folded as ASCII alone, so a lookalike keeps a form of its
     * own. A floor shared by mistake only refuses.
     */
    static String oneForm(final String address) {
        final java.util.regex.Matcher url = URL.matcher(address);
        if (url.matches()) {
            final String scheme = asciiLower(url.group("scheme"));
            final String port = url.group("port");
            final String kind =
                    switch (scheme) {
                        case "https", "http" -> "https";
                        case "ssh", "git+ssh", "ssh+git" -> "ssh";
                        default -> null;
                    };
            if (kind == null || port != null && port.length() > 5) {
                return address;
            }
            final int number = port == null ? -1 : Integer.parseInt(port);
            final int usual = "ssh".equals(kind) ? 22 : "https".equals(scheme) ? 443 : 80;
            return number < 0 || number == usual
                    ? form("https", url.group("host"), "", url.group("path"))
                    : form(
                            "ssh".equals(kind) ? "ssh" : scheme,
                            url.group("host"),
                            ":" + number,
                            url.group("path"));
        }
        final java.util.regex.Matcher scp = SCP.matcher(address);
        if (scp.matches()) {
            return form("https", scp.group("host"), "", scp.group("path"));
        }
        return address;
    }

    private static String form(
            final String scheme, final String host, final String port, final String path) {
        String trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (asciiLower(trimmed).endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        return scheme + "://" + asciiLower(host) + port + "/" + asciiLower(trimmed);
    }

    /** Folds A to Z alone: Java's lower case takes the Kelvin sign for 'k'. */
    private static String asciiLower(final String text) {
        final StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char each = text.charAt(i);
            folded.append(each >= 'A' && each <= 'Z' ? (char) (each + ('a' - 'A')) : each);
        }
        return folded.toString();
    }

    /** One check and write of a floor at a time in this JVM; a file lock holds it across them. */
    private static final java.util.concurrent.locks.ReentrantLock FLOOR =
            new java.util.concurrent.locks.ReentrantLock();

    /** A step on the floor, taken under its lock. */
    @FunctionalInterface
    private interface FloorStep<T> {
        @Nullable T take() throws IOException;
    }

    /** Takes a step on the floor under a lock this JVM and every other process here take. */
    private <T> @Nullable T locked(final FloorStep<T> step) throws IOException {
        FLOOR.lock();
        try {
            Files.createDirectories(memory);
            try (java.nio.channels.FileChannel channel =
                            java.nio.channels.FileChannel.open(
                                    memory.resolve("floor.lock"),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                    java.nio.channels.FileLock ignored = channel.lock()) {
                return step.take();
            }
        } finally {
            FLOOR.unlock();
        }
    }

    /**
     * Checks the floor and raises it in one step.
     *
     * @return The newer date accepted meanwhile, which the file is older than, or {@code null} when
     *     the date is now the floor.
     */
    private @Nullable Instant held(final String address, final Instant dated) throws IOException {
        return locked(
                () -> {
                    final Instant accepted = accepted(address);
                    if (accepted != null && dated.isBefore(accepted)) {
                        return accepted;
                    }
                    write(address, dated);
                    return null;
                });
    }

    /**
     * Sets the floor to the date of a signing anew, which may lower it: that is its way out. A
     * later date kept under an older key is lowered with it, or it would hold the floor up.
     */
    private void remember(final String address, final Instant dated) throws IOException {
        locked(
                () -> {
                    write(address, dated);
                    for (final String key : keys(address)) {
                        final Path kept = memory.resolve(TreeRepository.digest(key));
                        final Instant was = accepted(kept);
                        if (was != null && was.isAfter(dated)) {
                            write(kept, dated);
                        }
                    }
                    return null;
                });
    }

    private void write(final String address, final Instant dated) throws IOException {
        write(memory.resolve(TreeRepository.digest(oneForm(address))), dated);
    }

    private void write(final Path file, final Instant dated) throws IOException {
        final Path temporary = Files.createTempFile(memory, "accepted", ".tmp");
        try {
            Files.writeString(temporary, dated + "\n", StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Read refused(final String why) {
        return refused(why, null);
    }

    private static Read refused(final String why, final @Nullable String signer) {
        return new Read(REFUSED, null, null, why, signer);
    }

    private static Project parsed(final String text, final SnippetFiles snippets) {
        return ProjectReader.read(new StringReader(text), PATH, snippets);
    }

    /** Reads what the file names from that commit: the caller's directory has no part in it. */
    private static SnippetFiles inClone(final TreeRepository clone, final String tip) {
        return (path, origin) -> {
            final Path normal = Path.of(path).normalize();
            if (normal.isAbsolute() || normal.startsWith("..")) {
                throw new ProjectException(origin + ": a run file leaves the repository");
            }
            final Optional<String> object = clone.object(tip, normal.toString());
            if (object.isEmpty()) {
                throw new ProjectException(origin + ": no such run file");
            }
            return clone.text(object.orElseThrow());
        };
    }

    /**
     * The newest commit on the first-parent line that changed the file, or signed it anew with no
     * change: either way its signer is judged.
     */
    private static Optional<String> newestChange(final TreeRepository clone, final String path) {
        final List<Map.Entry<String, String>> line = clone.line(null);
        final java.util.Set<String> signedAnew = clone.signingAnew(path);
        for (int each = line.size() - 1; each >= 0; each--) {
            final Map.Entry<String, String> step = line.get(each);
            if (signedAnew.contains(step.getKey())
                    || clone.changed(step.getKey(), step.getValue()).contains(path)) {
                return Optional.of(step.getKey());
            }
        }
        return Optional.empty();
    }

    /** A key whose card was withdrawn signs nothing that is still believed, whatever it claims. */
    private static boolean anAdminsKey(
            final String key, final List<SigningKey> keys, final String dated) {
        return aKeyOf(key, keys, dated, Set.of(ADMIN));
    }

    private static boolean aKeyOf(
            final String key,
            final List<SigningKey> keys,
            final String dated,
            final Set<String> roles) {
        final LocalDate day = OffsetDateTime.parse(dated).toLocalDate();
        return keys.stream()
                .anyMatch(
                        row ->
                                roles.contains(row.role())
                                        && row.key().equals(key)
                                        && row.revokedSince() == null
                                        && !day.isBefore(row.validFrom())
                                        && day.isBefore(row.validUntil()));
    }

    /**
     * Reads the file for an admin who may sign it anew. It changes nothing in the repository, and
     * on this machine what every read does: a file that reads is remembered as accepted.
     *
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param project The project the file must name.
     * @return What was found, with the file's text.
     * @throws IOException When something unexpected happened.
     */
    public Look look(final String address, final List<SigningKey> keys, final String project)
            throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final String tip = clone.tip();
            final Read read = judged(clone, keys, address);
            return new Look(read, tip, shown(clone, tip), blocked(clone, tip, project));
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new Look(new Read(CANNOT_SAY, null, null, null), null, null, null);
        }
    }

    /**
     * Signs the file anew as it stands, so that it is read again: whoever does it takes on the
     * whole of it, which is why the person reads it first and names what they read.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param project The project the file must name.
     * @return What it made, or made before under this id.
     * @throws Refused {@code not-allowed} when the key that acts is no admin's, {@code moved-on}
     *     when the repository is not what was read, {@code refused-state} when a signature would
     *     not make the file read.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written sign(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String project)
            throws IOException {
        return sign(change, address, keys, project, PATH, Set.of(ADMIN));
    }

    /**
     * Signs a repository's {@code porelo.yml} anew on its default branch, with the key of a project
     * admin or developer valid today. The file's bytes stay as they were.
     *
     * @param change Who acts, against the revision read, under which id.
     * @param address The repository's upstream address.
     * @param keys The project's signing keys taken from the organization.
     * @return What it made, or made before under this id.
     * @throws IOException When the repository cannot be read or the change cannot be made.
     * @throws Refused When the key may not sign, the tip moved, or the file is absent.
     */
    public OrganizationWrites.Written signRepositoryFile(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys)
            throws IOException {
        return sign(change, address, keys, REPOSITORY_PATH, REPOSITORY_PATH, REPOSITORY_ROLES);
    }

    /**
     * Reads the project's file in the checkout a person keeps of the project's repository, at its
     * {@code main}, as a start reads it fetched: against the keys of the project's admins and what
     * this machine accepted of that repository before.
     *
     * @param checkout The checkout's top directory.
     * @param address The project repository's address in the organization, which names what this
     *     machine remembers of it.
     * @param keys The project's signing keys.
     * @param project The project the file must name, whose keys these are.
     * @return What was found.
     * @throws Refused {@code usage} when it is no checkout of this account.
     * @throws IOException When something unexpected happened.
     */
    public Read readCheckout(
            final Path checkout,
            final String address,
            final List<SigningKey> keys,
            final String project)
            throws IOException {
        try (TreeRepository clone =
                TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner)) {
            return judged(clone, keys, this, address, project);
        } catch (CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    /**
     * Shows a repository's file at the {@code main} of the checkout a person keeps, and its newest
     * change, before they sign it.
     *
     * @param checkout The checkout's top directory.
     * @param keys The project's signing keys.
     * @return The text and signature at that main.
     * @throws Refused {@code usage} when it is no checkout of this account, {@code refused-state}
     *     when it has no main.
     * @throws IOException When a signature cannot be checked.
     */
    public RepositoryLook lookCheckout(final Path checkout, final List<SigningKey> keys)
            throws IOException {
        try (TreeRepository clone =
                TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner)) {
            return looked(clone, keys);
        } catch (CommandException ex) {
            return new RepositoryLook(
                    new Look(new Read(CANNOT_SAY, null, null, null), null, null, null), null);
        }
    }

    /**
     * Signs a repository's file anew from the checkout a person keeps: an empty signed commit on
     * the {@code main} they read, pushed to the repository's upstream as a plain step forward, and
     * only then put into the checkout. Nothing else of the checkout is read by its own settings or
     * changed.
     *
     * @param change Who acts, under which id; its base is not asked, since the main read is signed.
     * @param checkout The checkout's top directory.
     * @param upstream The repository's address, as the judged project file names it.
     * @param keys The project's signing keys.
     * @return What it made, or made before under this id.
     * @throws Refused {@code usage} when it is no checkout of this account or its origin names
     *     another address, {@code not-allowed} when the key may not sign, {@code moved-on} when the
     *     forge's main is not what was read, {@code refused-state} when the checkout holds commits
     *     the forge does not or no file.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written signCheckout(
            final OrganizationWrites.Change change,
            final Path checkout,
            final String upstream,
            final List<SigningKey> keys)
            throws IOException {
        final String acting = change.acting().key();
        final String subject =
                TreeRepository.asked("Sign the repository file anew", REPOSITORY_PATH);
        try (TreeRepository clone =
                TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner)) {
            final String read = clone.tip();
            final String origin = clone.origin();
            if (!origin.equals(upstream)) {
                throw new Refused(
                        Refused.USAGE,
                        "the checkout's origin is "
                                + (origin.isEmpty() ? "none" : origin)
                                + ", but the project names "
                                + upstream
                                + " for this repository",
                        read);
            }
            if (!aKeyOf(acting, keys, OffsetDateTime.now().toString(), REPOSITORY_ROLES)) {
                throw new Refused(
                        Refused.NOT_ALLOWED,
                        "the key that acts is no key of this project's admin or developer",
                        read);
            }
            final String forge = clone.mainAt(upstream, access);
            // Asked again under its id: only a commit the forge holds was made, whatever the
            // checkout holds of its own.
            final Optional<String> made = own(clone, change);
            if (made.isPresent()
                    && (forge.equals(made.orElseThrow())
                            || clone.onlyHere(forge).isPresent()
                                    && clone.hasAncestor(forge, made.orElseThrow()))) {
                if (!clone.subject(made.orElseThrow()).equals(subject)) {
                    throw new Refused(
                            Refused.USAGE, "this --id named another change", made.orElseThrow());
                }
                return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
            }
            signable(clone, read, REPOSITORY_PATH, REPOSITORY_PATH);
            if (forge.isEmpty()) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        "the forge's repository has no main to sign on top of",
                        read);
            }
            if (!forge.equals(read)) {
                final Optional<List<String>> local = clone.onlyHere(forge);
                if (local.isPresent() && !local.orElseThrow().isEmpty()) {
                    throw new Refused(
                            Refused.REFUSED_STATE,
                            "the checkout's main holds commits the forge does not, which a"
                                    + " signature would carry along: "
                                    + String.join(", ", local.orElseThrow()),
                            read);
                }
                throw new Refused(
                        Refused.MOVED_ON,
                        "the forge's main moved on from what the checkout holds: fetch, read"
                                + " again and sign again",
                        read);
            }
            final String commit =
                    clone.commit(
                            read,
                            Map.of(),
                            subject,
                            change.id(),
                            signing,
                            acting,
                            change.acting().login(),
                            REPOSITORY_PATH);
            if (!clone.push(upstream, access)) {
                throw new GitTreeStore.Unreachable(
                        "the repository did not take the change: fetch, read again and sign again");
            }
            // The forge holds it now; a checkout the person moved meanwhile gets it by a fetch.
            clone.handBack(commit, read);
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * Returns the address the {@code origin} of a checkout a person keeps names.
     *
     * @param runner How git runs.
     * @param checkout The checkout's top directory.
     * @return It, or "" when it names none.
     * @throws IOException When it is no checkout or has no main.
     */
    public static String checkoutOrigin(final CommandRunner runner, final Path checkout)
            throws IOException {
        try (TreeRepository clone =
                TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner)) {
            return clone.origin();
        }
    }

    /**
     * Returns where the {@code main} of a checkout a person keeps stands.
     *
     * @param runner How git runs.
     * @param checkout The checkout's top directory.
     * @return Its commit.
     * @throws IOException When it is no checkout or has no main.
     */
    public static String checkoutMain(final CommandRunner runner, final Path checkout)
            throws IOException {
        try (TreeRepository clone =
                TreeRepository.borrowing(TreeRepository.checkoutDirectory(checkout), runner)) {
            return clone.tip();
        }
    }

    private OrganizationWrites.Written sign(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String project,
            final String path,
            final Set<String> roles)
            throws IOException {
        final String acting = change.acting().key();
        final String subject =
                TreeRepository.asked(
                        PATH.equals(path) ? "Sign the file anew" : "Sign the repository file anew",
                        project);
        try (TreeRepository clone = fetch(address, path)) {
            final Optional<String> made = own(clone, change);
            if (made.isPresent()) {
                if (!clone.subject(made.orElseThrow()).equals(subject)) {
                    throw new Refused(
                            Refused.USAGE, "this --id named another change", made.orElseThrow());
                }
                return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
            }
            final String tip = clone.tip();
            if (!aKeyOf(acting, keys, OffsetDateTime.now().toString(), roles)) {
                throw new Refused(
                        Refused.NOT_ALLOWED,
                        "the key that acts is no key of this project's "
                                + (PATH.equals(path) ? "admin" : "admin or developer"),
                        tip);
            }
            if (change.base() != null && !change.base().equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the project repository moved on", tip);
            }
            // Reading here says nothing of a machine that accepted a newer one, so it signs too.
            signable(clone, tip, project, path);
            // An empty commit: the file stays byte for byte, and the trailer makes this
            // signature the one its newest change is judged by.
            final String commit =
                    clone.commit(
                            tip,
                            Map.of(),
                            subject,
                            change.id(),
                            signing,
                            acting,
                            change.acting().login(),
                            path);
            if (!clone.push(address, access)) {
                throw new GitTreeStore.Unreachable(
                        "the project repository did not take the change");
            }
            if (PATH.equals(path)) {
                remember(address, OffsetDateTime.parse(clone.dated(commit)).toInstant());
            }
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * Shows the repository's file and its newest change without needing a signing agent.
     *
     * @param address The repository's upstream address.
     * @param keys The project's signing keys.
     * @return The text and signature at the default branch, or {@code cannot-say} if unreachable.
     * @throws IOException When a signature cannot be checked.
     */
    public RepositoryLook lookRepositoryFile(final String address, final List<SigningKey> keys)
            throws IOException {
        try (TreeRepository clone = fetch(address, REPOSITORY_PATH)) {
            return looked(clone, keys);
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new RepositoryLook(
                    new Look(new Read(CANNOT_SAY, null, null, null), null, null, null), null);
        }
    }

    private static RepositoryLook looked(final TreeRepository clone, final List<SigningKey> keys)
            throws IOException {
        final String tip = clone.tip();
        final String text = clone.object(tip, REPOSITORY_PATH).map(clone::text).orElse(null);
        final String change = newestChange(clone, REPOSITORY_PATH).orElse(null);
        Read read;
        try {
            final String signer = change == null ? null : clone.signer(change);
            read =
                    change != null
                                    && signer != null
                                    && aKeyOf(signer, keys, clone.dated(change), REPOSITORY_ROLES)
                            ? new Read(READ, tip, null, null, signer)
                            : refused(
                                    "the newest change has no project admin's or developer's signature",
                                    signer);
        } catch (CardException ex) {
            read = refused(String.valueOf(ex.getMessage()));
        }
        final String blocked = text == null ? "the repository holds no porelo.yml" : null;
        return new RepositoryLook(new Look(read, tip, text, blocked), change);
    }

    private TreeRepository fetch(final String address, final String path) throws IOException {
        return PATH.equals(path)
                ? TreeRepository.fetch(address, runner, access)
                : TreeRepository.fetchDefault(address, runner, access);
    }

    private static void signable(
            final TreeRepository clone, final String tip, final String project, final String path) {
        if (PATH.equals(path)) {
            signable(clone, tip, project);
        } else if (clone.object(tip, path).isEmpty()) {
            throw new Refused(Refused.REFUSED_STATE, "the repository holds no " + path, tip);
        }
    }

    /** The file's text, or {@code null} when there is none that can be shown. */
    private static @Nullable String shown(final TreeRepository clone, final String tip) {
        try {
            return clone.object(tip, PATH).map(clone::text).orElse(null);
        } catch (CardException ex) {
            return null;
        }
    }

    /** What a signature would not change, or {@code null} when one would do. */
    private static @Nullable String blocked(
            final TreeRepository clone, final String tip, final String project) {
        try {
            signable(clone, tip, project);
            return null;
        } catch (Refused refused) {
            return refused.getMessage();
        }
    }

    /** What a refusal offers, which is the verb only where a signature would make the file read. */
    private static String wayOut(final TreeRepository clone, final String tip) {
        try {
            parsed(clone.text(clone.object(tip, PATH).orElseThrow()), inClone(clone, tip));
            return ": porelo project sign shows an admin whether signing it anew will do";
        } catch (RuntimeException ex) {
            return ", and it does not read either: an admin mends it and signs that commit";
        }
    }

    /** Refuses unless a signature would make the file as it stands read. */
    private static void signable(
            final TreeRepository clone, final String tip, final String project) {
        final Optional<String> text;
        final @Nullable String named;
        try {
            text = clone.object(tip, PATH).map(clone::text);
            named = text.isEmpty() ? null : parsed(text.orElseThrow(), inClone(clone, tip)).name();
        } catch (ProjectException ex) {
            // Made printable: it quotes the file, which anyone with write access wrote.
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml does not read, and a signature would not"
                            + " change that: "
                            + RegistryText.printable(String.valueOf(ex.getMessage())),
                    tip);
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, which anyone with write access wrote.
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml does not read, and a signature would not"
                            + " change that",
                    tip);
        }
        if (text.isEmpty()) {
            throw new Refused(
                    Refused.REFUSED_STATE, "the project repository holds no project.yml", tip);
        }
        if (!project.equals(named)) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml names another project",
                    tip);
        }
    }

    /**
     * Fills an empty project repository with its first signed commit.
     *
     * @param change Who acts, under which id.
     * @param address The project repository.
     * @param text The file's first text.
     * @return What it made, or made before under this id.
     * @throws Refused {@code no-repository} when it is not there, {@code exists} when it holds
     *     anything else.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written found(
            final OrganizationWrites.Change change, final String address, final String text)
            throws IOException {
        final String acting = change.acting().key();
        final Optional<TreeRepository> empty = TreeRepository.founding(address, runner, access);
        if (empty.isEmpty()) {
            try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
                final Optional<String> made = own(clone, change);
                if (made.isPresent()) {
                    if (!text.equals(shown(clone, made.orElseThrow()))) {
                        throw new Refused(
                                Refused.USAGE,
                                "this --id named another change: its first file says otherwise",
                                made.orElseThrow());
                    }
                    return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
                }
                throw new Refused(
                        Refused.EXISTS, "the project's repository holds something", clone.tip());
            } catch (GitTreeStore.Empty ex) {
                throw new Refused(Refused.EXISTS, "the project's repository holds something", null);
            } catch (CardException ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        }
        try (TreeRepository clone = empty.orElseThrow()) {
            final String commit =
                    clone.commit(
                            "",
                            Map.of(PATH, text),
                            "Create the project's repository",
                            change.id(),
                            signing,
                            acting,
                            change.acting().login());
            if (!clone.push(address, access)) {
                throw new GitTreeStore.Unreachable(
                        "the project repository did not take the first commit");
            }
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * A change here is one commit, signed by the key that acts. Anyone with write access can append
     * a commit carrying the same id, which must not be answered as that person's.
     */
    private static Optional<String> own(
            final TreeRepository clone, final OrganizationWrites.Change change) throws IOException {
        final String fingerprint = PersonCard.fingerprint(change.acting().key());
        final Optional<String> made = clone.changeNamed(change.id(), fingerprint);
        return made.isPresent() && clone.signedBy(made.orElseThrow(), fingerprint)
                ? made
                : Optional.empty();
    }
}
