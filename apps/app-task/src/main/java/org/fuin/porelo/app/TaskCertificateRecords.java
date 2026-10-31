package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The certificates of the task keys this machine held, each found by its key's fingerprint.
 *
 * <p>A signature made by a task's plain key names only the key. This is what names the task, the
 * person who certified it and when it was valid, kept after the task and its key are gone. Each
 * record is the line {@code porelo-host-person task --show} answers on a host, so a reader needs
 * one format for both.
 */
final class TaskCertificateRecords {

    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    TaskCertificateRecords(PoreloContext context) {
        this.context = context;
    }

    /**
     * Keeps a task key's certificate.
     *
     * @param task The task's name.
     * @param login Who started it.
     * @param certificate Its certificate.
     * @throws IOException If it cannot be written.
     */
    void keep(String task, String login, SshCertificate certificate) throws IOException {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("task", task);
        record.put("login", login);
        record.put("fingerprint", certificate.fingerprint());
        record.put("signed_by", PersonCard.fingerprint(certificate.signer()));
        record.put("key_id", certificate.keyId());
        record.put("principals", certificate.principals());
        record.put("valid_after", certificate.validAfter().toString());
        record.put("valid_before", certificate.validBefore().toString());
        record.put("certificate", certificate.line());
        final Path file = file(certificate.fingerprint());
        Files.createDirectories(file.getParent());
        final Path written = Files.createTempFile(file.getParent(), ".certificate-", ".json");
        try {
            Files.writeString(written, Json.write(record) + "\n", StandardCharsets.UTF_8);
            Files.move(written, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /**
     * Finds the certificate of a key.
     *
     * @param fingerprint The key's fingerprint, {@code SHA256:...}.
     * @return Its record, or {@code null} when none is kept or it is not a fingerprint.
     */
    @Nullable Map<String, Object> find(String fingerprint) {
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            return null;
        }
        final Path file = file(fingerprint);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> record =
                    Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                                    instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : null;
            return record;
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    /** One file per key, named by its fingerprint in a file name's alphabet. */
    private Path file(String fingerprint) {
        final String name =
                fingerprint.substring("SHA256:".length()).replace('+', '-').replace('/', '_');
        return context.paths().tasks().certificates().resolve(name + ".json");
    }
}
