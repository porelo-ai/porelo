package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.RootOwnedFile;

/** Reads the host's published repository arrivals without a privileged command. */
public final class HostArrivalLedger {
    private static final Path DIRECTORY = Path.of("/etc/porelo-host/organization/arrivals");
    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;

    private HostArrivalLedger() {}

    /** Reads a repository's published judgements; unavailable data answers cannot-say. */
    public static Map<String, Object> read(String project, String repository) {
        return read(DIRECTORY, HostCentral.ATTRIBUTES, project, repository);
    }

    /** Reads one repository using explicit file attributes for a synthetic host in tests. */
    public static Map<String, Object> read(
            Path directory,
            RootOwnedFile.Attributes attributes,
            String project,
            String repository) {
        if (!HostCentral.validProject(project)
                || !repository.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}"))
            return unavailable("host-answer-refused");
        final byte[] bytes;
        try {
            bytes =
                    RootOwnedFile.read(
                            directory
                                    .resolve(project)
                                    .resolve(
                                            repository.toLowerCase(java.util.Locale.ROOT)
                                                    + ".jsonl"),
                            MAXIMUM_BYTES,
                            attributes,
                            0);
        } catch (IOException | IllegalArgumentException ex) {
            return unavailable("host-file-unavailable");
        }
        try {
            final String content =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (content.isEmpty() || !content.endsWith("\n"))
                return unavailable("host-answer-refused");
            final List<Object> entries = new ArrayList<>();
            for (String line : content.lines().toList()) {
                final Object parsed = Json.parseExact(line);
                if (!(parsed instanceof Map<?, ?> row) || !valid(row))
                    return unavailable("host-answer-refused");
                entries.add(row);
            }
            return Map.of("state", "read", "entries", List.copyOf(entries));
        } catch (IOException | IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
            return unavailable("host-answer-refused");
        }
    }

    private static Map<String, Object> unavailable(String why) {
        return Map.of("state", "cannot-say", "why", why, "entries", List.of());
    }

    private static boolean nullableText(@org.jspecify.annotations.Nullable Object value) {
        return value == null || value instanceof String text && text.length() <= 16 * 1024;
    }

    private static boolean valid(Map<?, ?> row) {
        if (!(row.get("arrived") instanceof String arrived) || !arrived.endsWith("Z")) return false;
        try {
            Instant.parse(arrived);
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
        if ("armed".equals(row.get("verdict")))
            return row.keySet().equals(java.util.Set.of("verdict", "arrived"));
        if (!(row.get("commit") instanceof String commit)
                || !commit.matches("[0-9a-f]{40}([0-9a-f]{24})?")) return false;
        if ("migrated".equals(row.get("verdict")))
            return row.keySet().equals(java.util.Set.of("verdict", "arrived", "commit"));
        return "accepted".equals(row.get("verdict"))
                && row.get("ref") instanceof String ref
                && ref.matches("refs/[\\x21-\\x7e]+")
                && row.keySet()
                        .equals(java.util.Set.of("verdict", "arrived", "commit", "ref", "signer"))
                && row.get("signer") instanceof Map<?, ?> signer
                && signer.keySet()
                        .equals(
                                java.util.Set.of(
                                        "kind",
                                        "fingerprint",
                                        "principal",
                                        "certificate",
                                        "signed_by"))
                && ("person".equals(signer.get("kind")) || "task".equals(signer.get("kind")))
                && nullableText(signer.get("principal"))
                && (signer.get("certificate") == null
                        || "certificate".equals(signer.get("certificate"))
                        || "recorded".equals(signer.get("certificate")))
                && (signer.get("signed_by") == null
                        || signer.get("signed_by") instanceof String signedBy
                                && signedBy.matches("SHA256:[A-Za-z0-9+/]{43}"))
                && signer.get("fingerprint") instanceof String fingerprint
                && fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}");
    }
}
