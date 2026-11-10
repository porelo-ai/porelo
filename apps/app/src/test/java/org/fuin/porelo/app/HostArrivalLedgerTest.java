package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.RootOwnedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostArrivalLedgerTest {
    @TempDir Path directory;
    static final String ROW =
            "{\"commit\":\""
                    + "a".repeat(40)
                    + "\",\"ref\":\"refs/heads/main\",\"verdict\":\"accepted\",\"arrived\":\"2026-11-09T10:00:00Z\","
                    + "\"signer\":{\"kind\":\"person\",\"fingerprint\":\"SHA256:"
                    + "A".repeat(43)
                    + "\",\"principal\":\"alice\",\"certificate\":null,\"signed_by\":null}}\n";

    RootOwnedFile.Attributes attributes() {
        return path -> {
            final var values =
                    new java.util.HashMap<>(
                            Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
            values.put("uid", 0);
            if (!path.startsWith(directory)) values.put("mode", 0040755);
            return values;
        };
    }

    Path published() throws Exception {
        final Path file = Files.createDirectories(directory.resolve("alpha")).resolve("app.jsonl");
        Files.writeString(file, ROW);
        Files.setPosixFilePermissions(
                directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx--x--x"));
        Files.setPosixFilePermissions(
                file.getParent(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx--x--x"));
        Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        return file;
    }

    @Test
    void aReadingPreservesTheJudgementRatherThanVerifyingTheSignatureAgain() throws Exception {
        // Rejudging historical signatures can erase provenance after a key expires or is revoked.
        published();
        assertThat(HostArrivalLedger.read(directory, attributes(), "alpha", "app"))
                .as("a reading returns the recorded judgement unchanged")
                .isEqualTo(Map.of("state", "read", "entries", java.util.List.of(Json.parse(ROW))));
    }

    // A repository's display casing must not hide its existing host history.
    @Test
    void repositoryNamesUseTheForgesCaseFolding() throws Exception {
        final Path file = published();
        Files.move(file, file.resolveSibling("webapp.jsonl"));
        assertThat(HostArrivalLedger.read(directory, attributes(), "alpha", "WebApp").get("state"))
                .isEqualTo("read");
    }

    // Invalid repository names cannot select a host answer, even if a matching file exists.
    @ParameterizedTest
    @ValueSource(strings = {".", "..", "-app", "a/b"})
    void invalidRepositoryNamesAreRefused(String repository) throws Exception {
        final Path file = published();
        if (!repository.contains("/")) Files.move(file, file.resolveSibling(repository + ".jsonl"));
        assertThat(
                        HostArrivalLedger.read(directory, attributes(), "alpha", repository)
                                .get("state"))
                .isEqualTo("cannot-say");
    }

    // Malformed provenance cannot be presented as a successful recorded judgement.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "extra",
                "missing-signer",
                "kind",
                "principal",
                "certificate",
                "signed_by",
                "ref",
                "offset",
                "armed-extra",
                "migrated-extra"
            })
    void malformedProvenanceIsRefused(String flaw) throws Exception {
        final var row = new java.util.LinkedHashMap<>((Map<String, Object>) Json.parse(ROW));
        final var signer = new java.util.LinkedHashMap<>((Map<String, Object>) row.get("signer"));
        switch (flaw) {
            case "extra" -> row.put("extra", true);
            case "missing-signer" -> signer.remove("signed_by");
            case "kind" -> signer.put("kind", 123);
            case "principal" -> signer.put("principal", 123);
            case "certificate" -> signer.put("certificate", "unverified");
            case "signed_by" -> signer.put("signed_by", "bad");
            case "ref" -> row.put("ref", "refs/\u0000");
            case "offset" -> row.put("arrived", "2026-11-09T10:00:00+01:00");
            case "armed-extra" -> row.put("verdict", "armed");
            case "migrated-extra" -> row.put("verdict", "migrated");
        }
        row.put("signer", signer);
        Files.writeString(published(), Json.write(row) + "\n");
        assertThat(HostArrivalLedger.read(directory, attributes(), "alpha", "app").get("state"))
                .isEqualTo("cannot-say");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "file-owner",
                "file-group-write",
                "file-world-write",
                "file-link",
                "file-directory",
                "parent-owner",
                "parent-group-write",
                "parent-world-write",
                "parent-link",
                "ancestor-owner"
            })
    void anAbsentOrUntrustedPublicationCannotVouchThatNothingArrived(String flaw) throws Exception {
        // Forged paths cannot invent provenance, and missing data must not hide work.
        final Path file = published();
        if (flaw.equals("missing")) Files.delete(file);
        final var base = attributes();
        final RootOwnedFile.Attributes changed =
                path -> {
                    final var values = new java.util.HashMap<>(base.read(path));
                    final Path affected =
                            flaw.startsWith("file-")
                                    ? file
                                    : flaw.equals("ancestor-owner")
                                            ? directory.getParent()
                                            : file.getParent();
                    if (path.equals(affected)) {
                        if (flaw.endsWith("owner")) values.put("uid", 1001);
                        else if (flaw.endsWith("group-write"))
                            values.put("mode", ((int) values.get("mode")) | 0020);
                        else if (flaw.endsWith("world-write"))
                            values.put("mode", ((int) values.get("mode")) | 0002);
                        else if (flaw.endsWith("link")) values.put("mode", 0120700);
                        else if (flaw.equals("file-directory")) values.put("mode", 0040755);
                    }
                    return values;
                };
        assertThat(HostArrivalLedger.read(directory, changed, "alpha", "app").get("state"))
                .as("unavailable or unsafe arrivals must answer cannot-say: %s", flaw)
                .isEqualTo("cannot-say");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "not json\n", "{}\n", "[]\n", "{\"verdict\":\"accepted\"}\n", "partial"})
    void aDamagedPublicationCannotBecomeASuccessfulEmptyReading(String content) throws Exception {
        // Corrupt snapshots cannot erase or fabricate a reviewer's history.
        Files.writeString(published(), content);
        assertThat(HostArrivalLedger.read(directory, attributes(), "alpha", "app").get("state"))
                .as("malformed arrivals must answer cannot-say")
                .isEqualTo("cannot-say");
    }
}
