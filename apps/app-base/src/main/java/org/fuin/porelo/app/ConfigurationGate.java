package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;

/**
 * Decides whether a commit is one this machine may take its configuration from.
 *
 * <p>Everything else Porelo guards goes the other way: the gate holds what an agent wrote until a
 * person has read it. Configuration arriving from a repository reverses that direction, and the
 * reversal is what makes this necessary - <strong>a project's configuration says which hosts its
 * tasks may reach</strong>, so whoever can push to its repository could otherwise open the firewall
 * on every machine that follows it, at the next tick and without anybody looking.
 *
 * <p><strong>The anchor is pinned out of band.</strong> One or more keys in an {@code
 * allowed_signers} file an operator wrote when the machine was prepared. A machine with none
 * verifies nothing and therefore applies nothing - which is the safe direction, and is reported
 * rather than left silent.
 *
 * <p><strong>Verified by git rather than by us.</strong> A commit's signature covers the commit
 * object, and reproducing that canonicalisation here would be a second implementation of something
 * git already does exactly. So this runs {@code git verify-commit} with the pinned file and reads
 * its answer.
 */
public final class ConfigurationGate {

    /** What the machine decided about a commit. */
    public enum Outcome {

        /** Signed by a key this machine was told to trust. It may be applied. */
        VERIFIED,

        /** No signature at all. */
        NOT_SIGNED,

        /** The signature is not authorized by the keys in force and the revocation policy. */
        UNKNOWN_KEY,

        /** Nothing is pinned here, so nothing can be verified and nothing may be applied. */
        NO_ANCHOR,

        /** The repository or the commit could not be read. */
        UNREADABLE
    }

    /**
     * What one check found.
     *
     * @param outcome What was decided.
     * @param commit The commit that was checked, or "" when it could not be read.
     * @param signer The principal whose key signed it, or "" when there is none to name.
     * @param detail What to tell an operator, in words they can act on.
     */
    public record Verdict(Outcome outcome, String commit, String signer, String detail) {

        /**
         * Says whether this configuration may be applied.
         *
         * @return {@code true} only for {@link Outcome#VERIFIED}.
         */
        public boolean mayApply() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private final CommandRunner runner;

    private final Path signers;

    private final @org.jspecify.annotations.Nullable Path krl;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param signers The pinned {@code allowed_signers} file.
     */
    public ConfigurationGate(final CommandRunner runner, final Path signers) {
        this(runner, signers, null);
    }

    /**
     * Constructor with an explicit revocation list.
     *
     * @param runner How commands are run.
     * @param signers The pinned allowed-signers file.
     * @param krl The revocation list, or {@code null} when none is configured.
     */
    public ConfigurationGate(
            final CommandRunner runner,
            final Path signers,
            final @org.jspecify.annotations.Nullable Path krl) {
        this.krl = krl;
        this.runner = runner;
        this.signers = signers;
    }

    /**
     * Checks one commit of a project against the keys pinned for that project alone.
     *
     * <p>Checked against every line of the file, any principal in it was accepted: a key pinned for
     * a public project verified a commit served for another one. Only the project's own lines are
     * given to git here.
     *
     * @param repository A clone on this machine.
     * @param ref What to check.
     * @param project The project the commit is for, the principal its keys are pinned under.
     * @return What was decided.
     */
    public Verdict verify(final Path repository, final String ref, final String project) {
        return active(repository, ref, project, true);
    }

    /**
     * Checks a new commit against all active principals in the file.
     *
     * @param repository The clone.
     * @param ref The commit to check.
     * @return The verdict; retired entries never authorize a new commit.
     */
    public Verdict verify(final Path repository, final String ref) {
        return active(repository, ref, null, true);
    }

    /** Checks with the revocation policy already validated by the range walker. */
    Verdict verifyAfterRevocations(final Path repository, final String ref) {
        return active(repository, ref, null, false);
    }

    private Verdict active(
            final Path repository,
            final String ref,
            final @org.jspecify.annotations.Nullable String project,
            final boolean checkRevocations) {
        final String commit = commitOf(repository, ref);
        try {
            if (checkRevocations) {
                requireRevocations(runner, krl);
            }
        } catch (final java.io.IOException ex) {
            return new Verdict(
                    Outcome.UNREADABLE,
                    commit,
                    "",
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "revocation input failures carry their reason"));
        }
        if (Files.notExists(signers, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return new Verdict(
                    Outcome.NO_ANCHOR,
                    commit,
                    "",
                    "no key is pinned in "
                            + signers
                            + ", so no configuration can be applied here. Pin the project's key.");
        }
        Path own = null;
        try {
            final java.util.List<String> lines = new java.util.ArrayList<>();
            for (final String line : AllowedSigner.read(runner, signers)) {
                if (line.isBlank() || line.strip().startsWith("#")) {
                    continue;
                }
                final AllowedSigner signer = AllowedSigner.parse(line);
                if (signer == null) {
                    throw new java.io.IOException("invalid allowed-signers line");
                }
                if (!signer.retired() && (project == null || signer.principal().equals(project))) {
                    lines.add(line);
                }
            }
            own = Files.createTempFile("porelo-signers-", "");
            Files.write(own, lines);
            return verifyWith(repository, ref, own);
        } catch (final java.io.IOException ex) {
            return new Verdict(
                    Outcome.UNREADABLE,
                    commit,
                    "",
                    "the pinned keys could not be read: " + ex.getMessage());
        } finally {
            if (own != null) {
                try {
                    Files.deleteIfExists(own);
                } catch (final java.io.IOException ignored) {
                    // Only public keys remain in a temporary file.
                }
            }
        }
    }

    /** Refuses a configured policy input that cannot be read, even when no signature is needed. */
    static void requireRevocations(
            final CommandRunner runner, final @org.jspecify.annotations.Nullable Path krl)
            throws java.io.IOException {
        if (krl == null) {
            return;
        }
        try {
            if (!Files.isRegularFile(krl)) {
                throw new java.io.IOException("not a regular file");
            }
            try (var input = Files.newInputStream(krl)) {
                if (input.readNBytes(1).length == 0) {
                    throw new java.io.IOException("empty KRL");
                }
            }
            final CommandResult checked =
                    runner.run(
                            Command.of("ssh-keygen", "-Q", "-f", krl.toAbsolutePath().toString()));
            if (checked.exitCode() != 0 && checked.exitCode() != 1) {
                throw new java.io.IOException("invalid KRL: " + checked.standardError().strip());
            }
        } catch (final java.io.IOException | RuntimeException ex) {
            throw new java.io.IOException(
                    "the revocation list cannot be read: " + krl + ": " + ex.getMessage(), ex);
        }
    }

    private Verdict verifyWith(final Path repository, final String ref, final Path active) {
        final String commit = commitOf(repository, ref);
        final CommandResult result;
        try {
            // The commit as resolved, not the ref: a fetch between the two could make what was
            // checked differ from what is applied.
            result =
                    runner.run(
                            Command.of(
                                    "git",
                                    "-C",
                                    repository.toString(),
                                    "-c",
                                    "gpg.format=ssh",
                                    "-c",
                                    "gpg.ssh.allowedSignersFile=" + active.toAbsolutePath(),
                                    "-c",
                                    "gpg.ssh.revocationFile="
                                            + (krl == null ? "" : krl.toAbsolutePath()),
                                    "verify-commit",
                                    commit.isEmpty() ? ref : commit));
        } catch (final RuntimeException ex) {
            return new Verdict(
                    Outcome.UNREADABLE, commit, "", "git could not be run: " + ex.getMessage());
        }
        final String said = (result.standardError() + result.standardOutput()).strip();
        if (result.successful()) {
            return new Verdict(Outcome.VERIFIED, commit, principalIn(said), "");
        }
        if (commit.isEmpty()) {
            return new Verdict(
                    Outcome.UNREADABLE,
                    "",
                    "",
                    "'" + ref + "' is not something " + repository + " has: " + said);
        }
        // Measured against git 2.53: an unsigned commit fails with nothing to say, and one signed
        // by a key that is not pinned reports a good signature and "No principal matched". The two
        // have different cures - somebody forgot to sign, or somebody who may not sign did - and an
        // operator should not have to tell them apart from the same sentence.
        if (said.contains("No principal matched") || said.contains("Good \"git\" signature with")) {
            // The fingerprint, because it is what turns "signed by a key you were not given" into
            // something a person can act on: they compare it with the one they meant to pin. A
            // public key's fingerprint is not a secret, and nothing else about the key is said.
            final String fingerprint = fingerprintIn(said);
            return new Verdict(
                    Outcome.UNKNOWN_KEY,
                    commit,
                    fingerprint,
                    "signed by a key that is not pinned on this machine, so it is not applied"
                            + (fingerprint.isEmpty() ? "" : ": " + fingerprint));
        }
        final String key = SignedBy.keyOf(runner, repository, commit);
        if (key != null) {
            final String fingerprint = SignedBy.fingerprintOf(key);
            return new Verdict(
                    Outcome.UNKNOWN_KEY,
                    commit,
                    fingerprint == null ? "" : fingerprint,
                    "the signature did not verify against the keys in force and the revocation"
                            + " list, so it is not applied: "
                            + said);
        }
        return new Verdict(
                Outcome.NOT_SIGNED, commit, "", "carries no signature, so it is not applied");
    }

    /**
     * Reads the key's fingerprint out of git's own sentence.
     *
     * <p>git prints {@code Good "git" signature with ED25519 key SHA256:<base64>} for a signature
     * it could check but not attribute. Taken by its shape rather than by position, so a git that
     * words the rest of the line differently still gives up the fingerprint.
     *
     * @param said What git wrote.
     * @return The fingerprint, or "" when there is none in there.
     */
    private static String fingerprintIn(final String said) {
        final java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("SHA256:[A-Za-z0-9+/]{43}").matcher(said);
        return found.find() ? found.group() : "";
    }

    private String commitOf(final Path repository, final String ref) {
        try {
            final CommandResult result =
                    runner.run(Command.of("git", "-C", repository.toString(), "rev-parse", ref));
            return result.successful() ? result.standardOutput().strip() : "";
        } catch (final RuntimeException ex) {
            return "";
        }
    }

    /**
     * Reads the principal out of git's own sentence.
     *
     * @param said What git printed.
     * @return The principal, or "" when the sentence does not name one.
     */
    private static String principalIn(final String said) {
        final int at = said.indexOf("signature for ");
        if (at < 0) {
            return "";
        }
        final String rest = said.substring(at + "signature for ".length());
        final int end = rest.indexOf(" with ");
        return end < 0 ? rest.strip() : rest.substring(0, end).strip();
    }
}
