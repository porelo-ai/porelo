package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshCertificate;

/**
 * Task keys certified for a test by a certifier of its own, with {@code ssh-keygen -s} as a
 * person's Porelo certifies them, and what a receiver looks their certificates up in.
 */
final class CertifiedTaskKeys {

    private final Path dir;

    private final String certifier;

    private final Map<String, SshCertificate> kept = new LinkedHashMap<>();

    /**
     * Constructor that makes the certifier's key.
     *
     * @param dir Where a directory of its own is made for its key and the certificates.
     */
    CertifiedTaskKeys(final Path dir) {
        try {
            this.dir = Files.createTempDirectory(Files.createDirectories(dir), "certifier-");
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "certifier", "-f", ca());
            final String[] fields = Files.readString(this.dir.resolve("ca.pub")).strip().split(" ");
            this.certifier = fields[0] + " " + fields[1];
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Returns the certifier's public key.
     *
     * @return {@code ssh-ed25519 AAAA...}.
     */
    String certifier() {
        return certifier;
    }

    /**
     * Makes a task's key, certified for a day around now.
     *
     * @param project The task's project.
     * @param task The task.
     * @return Its key, whose certificate is kept here.
     */
    SigningKey task(final String project, final String task) {
        return task(project, task, "-1d:+1d");
    }

    /**
     * Makes a task's key, certified for a given time.
     *
     * @param project The task's project.
     * @param task The task.
     * @param validity As {@code ssh-keygen -V} takes it.
     * @return Its key, whose certificate is kept here.
     */
    SigningKey task(final String project, final String task, final String validity) {
        final SigningKey key = SigningKey.generate(task);
        kept.put(key.authorizedKeysLine(), certify(key, "task:" + project + ":" + task, validity));
        return key;
    }

    /**
     * Makes a key certified for a principal as given, whatever its shape.
     *
     * @param principal The one principal its certificate names.
     * @return Its key, whose certificate is kept here.
     */
    SigningKey certifiedFor(final String principal) {
        final SigningKey key = SigningKey.generate("certified");
        kept.put(key.authorizedKeysLine(), certify(key, principal, "-1d:+1d"));
        return key;
    }

    /**
     * Returns the certificate kept for a key.
     *
     * @param key The task's key.
     * @return Its certificate.
     */
    SshCertificate certificate(final SigningKey key) {
        final SshCertificate certificate = kept.get(key.authorizedKeysLine());
        if (certificate == null) {
            throw new IllegalStateException("no certificate for " + key.comment());
        }
        return certificate;
    }

    /**
     * Returns where a receiver looks a certificate up: every one kept here and by the others.
     *
     * @param others Further certifiers whose certificates are kept on the same machine.
     * @return The lookup by fingerprint.
     */
    MessageSigners.Certificates certificates(final CertifiedTaskKeys... others) {
        return fingerprint -> {
            for (final CertifiedTaskKeys each : concat(others)) {
                for (final SshCertificate certificate : each.kept.values()) {
                    if (certificate.fingerprint().equals(fingerprint)) {
                        return certificate;
                    }
                }
            }
            return null;
        };
    }

    /**
     * Returns a task's keys that believe this certifier alone.
     *
     * @param project The receiving task's project.
     * @param own The receiving task's own key.
     * @param others Further certifiers whose certificates are kept, but who certify nothing here.
     * @return Its keys.
     */
    MessageSigners signers(
            final String project, final SigningKey own, final CertifiedTaskKeys... others) {
        return new MessageSigners(
                () -> own, certificates(others), project, () -> List.of(certifier));
    }

    private List<CertifiedTaskKeys> concat(final CertifiedTaskKeys... others) {
        final List<CertifiedTaskKeys> all = new java.util.ArrayList<>(List.of(this));
        all.addAll(List.of(others));
        return all;
    }

    private SshCertificate certify(
            final SigningKey key, final String principal, final String validity) {
        try {
            final Path pub = Files.createTempFile(dir, "task-", ".pub");
            Files.writeString(pub, key.authorizedKeysLine() + "\n");
            run(
                    "ssh-keygen",
                    "-q",
                    "-s",
                    ca(),
                    "-I",
                    "porelo task " + key.comment() + " by tester",
                    "-n",
                    principal,
                    "-V",
                    validity,
                    pub.toString());
            final String name = pub.getFileName().toString();
            final Path cert =
                    dir.resolve(name.substring(0, name.length() - ".pub".length()) + "-cert.pub");
            return SshCertificate.read(Files.readString(cert).strip());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String ca() {
        return dir.resolve("ca").toString();
    }

    private static void run(final String... command) throws Exception {
        final Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed");
        }
    }
}
