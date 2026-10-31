package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may sign for a principal, read from an OpenSSH {@code allowed_signers} file.
 *
 * <p>The same file {@code ssh-keygen -Y verify} takes, and deliberately so: the keys it names can
 * be checked by somebody with no Porelo, and written by somebody who has only ever used ssh.
 *
 * <p><strong>What cannot be read is not silently skipped.</strong> A line this cannot parse makes
 * the whole file refused, because a keyring that quietly lost an entry is a keyring that quietly
 * stops trusting - or, worse, quietly stops recognising the signer whose key was on that line.
 */
public final class AllowedSigners {

    /**
     * One principal, and the keys allowed to sign for it.
     *
     * @param principal As the file names it.
     * @param keys OpenSSH public key blobs.
     */
    public record Signer(String principal, List<byte[]> keys) {}

    private AllowedSigners() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the principals and their keys.
     *
     * @param file The file, which need not exist.
     * @return One entry per principal, with every key allowed for it. Empty when there is no file.
     * @throws IOException Reading failed, or a line is not one this understands.
     */
    public static List<Signer> read(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file);
    }

    /**
     * Reads lines in the {@code allowed_signers} format.
     *
     * @param lines The lines.
     * @param origin What to call them in an error.
     * @return One entry per principal, with every key listed for it.
     * @throws IOException If any line cannot be read.
     */
    static List<Signer> parse(final List<String> lines, final Path origin) throws IOException {
        final Path file = origin;
        final Map<String, List<byte[]>> byPrincipal = new LinkedHashMap<>();
        int number = 0;
        for (final String line : lines) {
            number++;
            final String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final String[] fields = trimmed.split("\\s+");
            if (fields.length < 3 || !"ssh-ed25519".equals(fields[1])) {
                throw new IOException(
                        file
                                + ":"
                                + number
                                + ": expected '<principal> ssh-ed25519 <key>', got: "
                                + trimmed);
            }
            final byte[] key;
            try {
                key = Base64.getDecoder().decode(fields[2]);
            } catch (final IllegalArgumentException e) {
                throw new IOException(file + ":" + number + ": the key is not base64", e);
            }
            byPrincipal.computeIfAbsent(fields[0], principal -> new ArrayList<>()).add(key);
        }
        final List<Signer> signers = new ArrayList<>();
        byPrincipal.forEach(
                (principal, keys) -> signers.add(new Signer(principal, List.copyOf(keys))));
        return List.copyOf(signers);
    }
}
