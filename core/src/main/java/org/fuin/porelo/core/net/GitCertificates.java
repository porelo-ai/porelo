package org.fuin.porelo.core.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;

/**
 * Additional host git trust without replacing the system's authorities.
 *
 * @param paths The account's directories.
 * @param systemBundles System bundle candidates, in preference order.
 */
public record GitCertificates(XdgPaths paths, List<Path> systemBundles) {

    /** Copies the candidates so callers cannot change the trust policy during a write. */
    public GitCertificates {
        systemBundles = List.copyOf(systemBundles);
    }

    /**
     * Uses the Debian/Ubuntu bundle, or Fedora's when the first is absent.
     *
     * @param paths The account's directories.
     */
    public GitCertificates(final XdgPaths paths) {
        this(
                paths,
                List.of(
                        Path.of("/etc/ssl/certs/ca-certificates.crt"),
                        Path.of("/etc/pki/tls/certs/ca-bundle.crt")));
    }

    /**
     * Returns additional trust for an HTTPS destination, whether or not a credential is lent.
     *
     * @param url Where git is about to connect.
     * @return The bundle variable, or no variables when no additional trust applies.
     */
    public Map<String, String> environment(final String url) {
        final Path account = TrustedCertificates.file(paths);
        if (!url.regionMatches(true, 0, "https://", 0, "https://".length())
                || Files.notExists(account)) {
            return Map.of();
        }
        final List<Certificate> added = TrustedCertificates.read(account);
        final Path system =
                systemBundles.stream()
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "cannot add the certificates in "
                                                        + account
                                                        + ": no system CA bundle found; looked for "
                                                        + systemBundles));
        final List<Certificate> combined = new ArrayList<>(TrustedCertificates.read(system));
        combined.addAll(added);
        final Path bundle = paths.gitCaCertificates();
        write(bundle, pem(combined));
        return Map.of("GIT_SSL_CAINFO", bundle.toString());
    }

    private static String pem(final List<Certificate> certificates) {
        final StringBuilder content = new StringBuilder();
        final Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        try {
            for (final Certificate certificate : certificates) {
                content.append("-----BEGIN CERTIFICATE-----\n")
                        .append(encoder.encodeToString(certificate.getEncoded()))
                        .append("\n-----END CERTIFICATE-----\n");
            }
        } catch (CertificateEncodingException ex) {
            throw new IllegalStateException("cannot encode the git CA bundle", ex);
        }
        return content.toString();
    }

    private static void write(final Path bundle, final String content) {
        try {
            if (Files.isRegularFile(bundle) && Files.readString(bundle).equals(content)) {
                Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rw-------"));
                return;
            }
            final Path directory =
                    java.util.Objects.requireNonNull(
                            bundle.getParent(), "the git CA bundle has a state directory");
            Files.createDirectories(directory);
            final Path temporary =
                    Files.createTempFile(
                            directory,
                            "git-ca-",
                            ".pem",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(temporary, content, StandardCharsets.US_ASCII);
                // Git must see one complete trust snapshot even when two processes refresh it.
                Files.move(temporary, bundle, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "cannot write the git CA bundle " + bundle + ": " + ex.getMessage(), ex);
        }
    }
}
