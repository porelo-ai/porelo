package org.fuin.porelo.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshCertificate;

/**
 * A task's own key, certified at its start by the person who starts it.
 *
 * <p>The key is made here and never leaves the host; its public half goes to the starting person's
 * Porelo, which certifies it with their key through their own agent, the agent never forwarded.
 * What comes back is checked before anything is kept: a certificate of this key, for exactly this
 * task's principal, valid now and for no longer than {@link #VALIDITY}, signed by one of the
 * project's certifiers. A task with no project is certified by its starting person alone.
 */
final class TaskCertification {

    /** How long a task's certificate is valid; renewed by {@code porelo task certify}. */
    static final Duration VALIDITY = Duration.ofDays(7);

    /** What a clock on the certifier's side may differ by. */
    private static final Duration SLACK = Duration.ofMinutes(5);

    /** The project of a task that has none. */
    static final String NO_PROJECT = "default";

    /** Certifies a task's key: the starting person's side. */
    @FunctionalInterface
    interface Certifier {

        /**
         * Certifies a key.
         *
         * @param key The task's public key, {@code ssh-ed25519 AAAA...}.
         * @param principal The one principal, {@code task:<project>:<task>}.
         * @param keyId The certificate's key id.
         * @param validBefore Until when.
         * @return The certificate line.
         */
        String certify(String key, String principal, String keyId, Instant validBefore);
    }

    /** A certificate that is not accepted for the task; the start stops. */
    static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * A certified task key.
     *
     * @param key The task's key, held only here.
     * @param certificate Its certificate.
     * @param principal The task's principal.
     * @param signers The lines the task's gate judges its commits by.
     */
    record Issued(SigningKey key, SshCertificate certificate, String principal, String signers) {

        @Override
        public String toString() {
            return "Issued[" + principal + ", " + certificate.fingerprint() + "]";
        }
    }

    private TaskCertification() {}

    /**
     * Makes a task's key and has it certified.
     *
     * @param project The task's project, or {@link #NO_PROJECT}.
     * @param task The task's name.
     * @param login Who starts it.
     * @param certifier The starting person's side.
     * @param certifiers The keys allowed to certify the project's tasks; empty for a task with no
     *     project, which its certifier's own key certifies.
     * @param clock The time.
     * @return The key, its certificate and the gate's lines.
     * @throws Refused If what came back is not accepted.
     */
    static Issued certify(
            String project,
            String task,
            String login,
            Certifier certifier,
            List<String> certifiers,
            Clock clock) {
        return certify(
                project,
                task,
                login,
                SigningKey.generate("porelo task " + task + " by " + login),
                certifier,
                certifiers,
                clock);
    }

    /**
     * Has a task's key certified anew, as a renewal does before its certificate ends.
     *
     * @param project The task's project, or {@link #NO_PROJECT}.
     * @param task The task's name.
     * @param login Who certifies it.
     * @param key The task's key, kept.
     * @param certifier The person's side.
     * @param certifiers The keys allowed to certify the project's tasks.
     * @param clock The time.
     * @return The same key, its new certificate and the gate's lines.
     * @throws Refused If what came back is not accepted.
     */
    static Issued certify(
            String project,
            String task,
            String login,
            SigningKey key,
            Certifier certifier,
            List<String> certifiers,
            Clock clock) {
        // None named is no leave to take whichever key signed it: only a task without a project
        // is certified by its starting person alone.
        if (certifiers.isEmpty() && !NO_PROJECT.equals(project)) {
            throw new Refused("no key of project " + project + "'s access file may certify it");
        }
        final String principal = "task:" + project + ":" + task;
        final String keyId = "porelo task " + task + " by " + login;
        final Instant now = clock.instant();
        final String[] fields = key.authorizedKeysLine().split(" ");
        final String publicKey = fields[0] + " " + fields[1];
        final String line = certifier.certify(publicKey, principal, keyId, now.plus(VALIDITY));
        final SshCertificate certificate;
        try {
            certificate = SshCertificate.read(line);
        } catch (CardException ex) {
            throw new Refused("the task's certificate is not accepted: " + ex.getMessage());
        }
        if (!certificate.key().equals(publicKey)) {
            throw new Refused("the certificate is of another key than this task's");
        }
        if (!certificate.principals().equals(List.of(principal))) {
            throw new Refused("the certificate must name exactly " + principal);
        }
        if (!certificate.validAt(now.plus(SLACK)) && !certificate.validAt(now)) {
            throw new Refused("the certificate is not valid now");
        }
        if (certificate.validBefore().isAfter(now.plus(VALIDITY).plus(SLACK))) {
            throw new Refused(
                    "the certificate is valid longer than " + VALIDITY.toDays() + " days");
        }
        if (!certifiers.isEmpty() && !certifiers.contains(certificate.signer())) {
            throw new Refused(
                    "the certificate is signed by "
                            + certificate.signer().split(" ")[0]
                            + " "
                            + org.fuin.porelo.vault.PersonCard.fingerprint(certificate.signer())
                            + ", not a certifier of "
                            + project);
        }
        final List<String> allowed =
                certifiers.isEmpty() ? List.of(certificate.signer()) : certifiers;
        final StringBuilder signers = new StringBuilder();
        for (final String each : allowed) {
            signers.append("task:")
                    .append(project)
                    .append(":* cert-authority,namespaces=\"git\" ")
                    .append(each)
                    .append('\n');
        }
        return new Issued(key, certificate, principal, signers.toString());
    }
}
